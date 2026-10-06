/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.senderlogo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.utils.SenderLogoUtils;

import io.meeds.commons.http.SafeFetchPolicy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The sender logo fetch (EXO-90893) against a stub server on loopback, standing for
 * the public internet: BIMI under an enforced DMARC policy, the icon fallback, and every
 * guard the platform's fetcher applies under this policy -- internal addresses at
 * connect time and after a redirect, the redirect count, the size limit, the declared
 * and the real type, the timeout, https only.
 */
class SenderLogoFetcherTest {

  private static final String           SVG  = "<svg xmlns=\"http://www.w3.org/2000/svg\" version=\"1.2\" baseProfile=\"tiny-ps\""
      + " viewBox=\"0 0 10 10\"><title>Brand</title><rect width=\"10\" height=\"10\" fill=\"#00c805\"/></svg>";

  private static final String           UNSAFE_SVG = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
      + "<script>alert(1)</script><rect width=\"10\" height=\"10\" fill=\"#00c805\"/></svg>";

  private static final byte[]           PNG  = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13 };

  /** The most of a home page the test fetcher reads. */
  private static final int              PAGE_LIMIT = 2048;

  /** The names the loopback stub answers for as if it were on the internet. */
  private static final Set<String>      PUBLIC_HOSTS = Set.of("brand.example", "news.brand.example", "cdn.example");

  private final Map<String, InetAddress[]> hosts = new HashMap<>();

  private final Map<String, List<String>> txt  = new HashMap<>();

  private final Map<String, Answer>     answers = new HashMap<>();

  private final List<String>            hits = new CopyOnWriteArrayList<>();

  private HttpServer                    server;

  private ExecutorService               executor;

  private InetAddress                   stub;

  private int                           port;

  private SenderLogoFetcher             fetcher;

  /**
   * What the stub answers on a path.
   *
   * @param status the status
   * @param type the Content-Type, or null for none
   * @param body the body
   * @param location the Location, or null
   * @param chunked whether the body is sent without a length
   * @param delayMs how long to wait before answering
   */
  private record Answer(int status, String type, byte[] body, String location, boolean chunked, long delayMs) {
  }

  /**
   * Starts the stub on loopback and maps the test names.
   *
   * @throws Exception when the server cannot start
   */
  @BeforeEach
  void start() throws Exception {
    stub = InetAddress.getByAddress("brand.example", new byte[] { 127, 0, 0, 1 });
    server = HttpServer.create(new InetSocketAddress(stub, 0), 0);
    executor = Executors.newCachedThreadPool();
    server.setExecutor(executor);
    server.createContext("/", this::serve);
    server.start();
    port = server.getAddress().getPort();
    hosts.put("brand.example", new InetAddress[] { stub });
    hosts.put("news.brand.example", new InetAddress[] { stub });
    hosts.put("cdn.example", new InetAddress[] { stub });
    // The stub itself, reachable, under a name that is not exempted: only the guard
    // stands between a request to it and an answer.
    hosts.put("loopback.example", new InetAddress[] { stub });
    hosts.put("internal.example", new InetAddress[] { InetAddress.getByAddress(new byte[] { 10, 0, 0, 5 }) });
    hosts.put("metadata.example", new InetAddress[] { InetAddress.getByAddress(new byte[] { (byte) 169, (byte) 254, (byte) 169, (byte) 254 }) });
    fetcher = fetcher(this::lookup);
  }

  /**
   * Stops the stub and the fetcher.
   */
  @AfterEach
  void stop() {
    fetcher.close();
    server.stop(0);
    executor.shutdownNow();
  }

  /**
   * A BIMI logo under an enforced DMARC policy that the platform's SVG check accepts is
   * served as fetched, and the icon is never asked for.
   */
  @Test
  void aBimiLogoUnderAnEnforcedPolicyIsUsed() {
    bimi("brand.example", url("cdn.example", "/logo.svg"));
    txt.put("_dmarc.brand.example", List.of("v=DMARC1; p=reject"));
    answers.put("/logo.svg", ok(SenderLogoUtils.SVG, SVG.getBytes(StandardCharsets.UTF_8)));

    SenderLogo logo = fetcher.resolve("brand.example");

    assertEquals(SenderLogo.SOURCE_BIMI, logo.getSource());
    assertEquals(SenderLogoUtils.SVG, logo.getContentType());
    assertEquals(SVG, new String(logo.getData(), StandardCharsets.UTF_8));
    assertEquals(List.of("/logo.svg"), hits);
  }

  /**
   * An SVG the platform's check refuses is never served, from BIMI or as an icon: the
   * domain falls back to its icon, then to "no logo" -- the initials.
   */
  @Test
  void anUnsafeSvgIsRefused() {
    bimi("brand.example", url("cdn.example", "/logo.svg"));
    txt.put("_dmarc.brand.example", List.of("v=DMARC1; p=reject"));
    answers.put("/logo.svg", ok(SenderLogoUtils.SVG, UNSAFE_SVG.getBytes(StandardCharsets.UTF_8)));
    answers.put("/favicon.ico", ok("image/x-icon", PNG));
    assertEquals(SenderLogo.SOURCE_ICON, fetcher.resolve("brand.example").getSource(), "an unsafe BIMI logo gives way to the icon");

    answers.put("/favicon.ico", ok(SenderLogoUtils.SVG, UNSAFE_SVG.getBytes(StandardCharsets.UTF_8)));
    assertFalse(fetcher.resolve("brand.example").isPresent(), "an unsafe SVG icon gives no logo");
  }

  /**
   * What the platform's SVG check refuses -- a script, an event handler, a foreign
   * object, a script URL, a document type declaration (no entity is expanded, nothing
   * external read), a style sheet instruction, a document that is not well-formed --
   * and what it accepts, a plain drawing, however deeply its groups nest: the check
   * streams, so no depth exhausts the stack.
   */
  @Test
  void thePlatformsSvgCheckDecides() {
    String open = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\">";
    for (String unsafe : new String[] { UNSAFE_SVG, open + "<rect onload=\"alert(1)\"/></svg>",
        open + "<foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\"/></foreignObject></svg>",
        open + "<a xlink:href=\"javascript:alert(1)\"><rect/></a></svg>",
        "<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>" + open + "<text>&x;</text></svg>",
        "<?xml-stylesheet href=\"https://evil.example/a.css\"?>" + open + "</svg>", open + "<rect>" }) {
      assertFalse(fetcher.isSafeSvg(unsafe.getBytes(StandardCharsets.UTF_8)), unsafe);
    }
    assertTrue(fetcher.isSafeSvg(SVG.getBytes(StandardCharsets.UTF_8)));
    assertTrue(fetcher.isSafeSvg((open + "<g>".repeat(5000) + "</g>".repeat(5000) + "</svg>").getBytes(StandardCharsets.UTF_8)));
  }

  /**
   * A subdomain without records of its own is judged by its organisational domain's:
   * the BIMI record, and the DMARC subdomain policy.
   */
  @Test
  void aSubdomainUsesItsOrganisationalDomainsRecords() {
    bimi("brand.example", url("cdn.example", "/logo.svg"));
    txt.put("_dmarc.brand.example", List.of("v=DMARC1; p=none; sp=quarantine"));
    answers.put("/logo.svg", ok(SenderLogoUtils.SVG, SVG.getBytes(StandardCharsets.UTF_8)));

    assertEquals(SenderLogo.SOURCE_BIMI, fetcher.resolve("news.brand.example").getSource());
  }

  /**
   * Without an enforced DMARC policy the BIMI logo is not even fetched: the icon is
   * used instead, served as the type its bytes are.
   */
  @Test
  void withoutEnforcementTheIconIsUsed() {
    bimi("brand.example", url("cdn.example", "/logo.svg"));
    txt.put("_dmarc.brand.example", List.of("v=DMARC1; p=none"));
    answers.put("/favicon.ico", ok("image/x-icon", PNG));

    SenderLogo logo = fetcher.resolve("brand.example");

    assertEquals(SenderLogo.SOURCE_ICON, logo.getSource());
    assertEquals(SenderLogoUtils.PNG, logo.getContentType());
    assertEquals(List.of("/favicon.ico"), hits);
  }

  /**
   * A domain declining BIMI gets no logo at all, its icon included: nothing is fetched.
   */
  @Test
  void aDomainDecliningGetsNoLogo() {
    txt.put("default._bimi.brand.example", List.of("v=BIMI1; l=;"));
    answers.put("/favicon.ico", ok("image/x-icon", PNG));

    assertFalse(fetcher.resolve("brand.example").isPresent());
    assertEquals(List.of(), hits);
  }

  /**
   * A BIMI logo that is not SVG, or on an internal address, is refused, and the icon
   * is used; so is a DNS that cannot answer.
   */
  @Test
  void anUnusableBimiLogoFallsBackToTheIcon() {
    bimi("brand.example", url("cdn.example", "/logo.svg"));
    txt.put("_dmarc.brand.example", List.of("v=DMARC1; p=reject"));
    answers.put("/logo.svg", ok(SenderLogoUtils.PNG, PNG));
    answers.put("/favicon.ico", ok("image/x-icon", PNG));
    assertEquals(SenderLogo.SOURCE_ICON, fetcher.resolve("brand.example").getSource());

    bimi("brand.example", url("internal.example", "/logo.svg"));
    hits.clear();
    assertEquals(SenderLogo.SOURCE_ICON, fetcher.resolve("brand.example").getSource());
    assertEquals(List.of("/favicon.ico"), hits);

    SenderLogoFetcher noDns = fetcher(name -> {
      throw new DnsLookupException(new IOException("no DNS"));
    });
    try {
      assertEquals(SenderLogo.SOURCE_ICON, noDns.resolve("brand.example").getSource());
    } finally {
      noDns.close();
    }
  }

  /**
   * A name resolving to the reachable stub on loopback is refused by the lookup the
   * connection itself makes: the stub never sees the request, first or redirected, nor
   * a BIMI logo pointing at it.
   */
  @Test
  void aReachableLoopbackIsRefusedAtConnect() {
    answers.put("/x", ok("image/png", PNG));
    assertNull(fetcher.fetch(url("loopback.example", "/x"), Set.of(SenderLogoUtils.PNG)));
    answers.put("/favicon.ico", redirect(url("loopback.example", "/x")));
    assertNull(fetcher.fetch(url("brand.example", "/favicon.ico"), Set.of(SenderLogoUtils.PNG)));
    assertEquals(List.of("/favicon.ico"), hits);

    hits.clear();
    answers.remove("/favicon.ico");
    bimi("brand.example", url("loopback.example", "/logo.svg"));
    txt.put("_dmarc.brand.example", List.of("v=DMARC1; p=reject"));
    answers.put("/logo.svg", ok(SenderLogoUtils.SVG, SVG.getBytes(StandardCharsets.UTF_8)));
    assertFalse(fetcher.resolve("brand.example").isPresent());
    assertEquals(List.of("/favicon.ico", "/"), hits, "only the icon and the home page were asked for, and they have none");
  }

  /**
   * A host resolving to an internal address is refused when the connection opens, at
   * the first request or after a redirect, loopback and the metadata address included;
   * the stub never sees the redirected request.
   */
  @Test
  void internalAddressesAreRefusedAtConnectAndAfterRedirects() {
    assertNull(fetcher.fetch(url("internal.example", "/favicon.ico"), Set.of(SenderLogoUtils.PNG)));
    assertNull(fetcher.fetch(url("metadata.example", "/latest/meta-data/"), Set.of(SenderLogoUtils.PNG)));
    for (String target : new String[] { url("internal.example", "/x"), url("loopback.example", "/x"),
        url("metadata.example", "/x") }) {
      hits.clear();
      answers.put("/favicon.ico", redirect(target));
      answers.put("/x", ok("image/png", PNG));
      assertNull(fetcher.fetch(url("brand.example", "/favicon.ico"), Set.of(SenderLogoUtils.PNG)), target);
      assertEquals(List.of("/favicon.ico"), hits, target);
    }
  }

  /**
   * A redirect's target is checked by its shape before it is requested, as the first
   * URL is: one carrying credentials, or on a scheme or port not allowed, is refused
   * though its host is the public stub.
   */
  @Test
  void aRedirectTargetIsCheckedByItsShape() {
    answers.put("/x", ok("image/png", PNG));
    for (String target : new String[] { "http://user:secret@brand.example:" + port + "/x", "ftp://brand.example:" + port + "/x" }) {
      hits.clear();
      answers.put("/favicon.ico", redirect(target));
      assertNull(fetcher.fetch(url("brand.example", "/favicon.ico"), Set.of(SenderLogoUtils.PNG)), target);
      assertEquals(List.of("/favicon.ico"), hits, target);
    }
  }

  /**
   * Two redirects are followed, a third is not.
   */
  @Test
  void atMostTwoRedirectsAreFollowed() {
    answers.put("/a", redirect("/b"));
    answers.put("/b", redirect(url("cdn.example", "/c")));
    answers.put("/c", ok("image/png", PNG));
    assertEquals(PNG.length, fetcher.fetch(url("brand.example", "/a"), Set.of(SenderLogoUtils.PNG)).length);

    answers.put("/c", redirect("/d"));
    answers.put("/d", ok("image/png", PNG));
    hits.clear();
    assertNull(fetcher.fetch(url("brand.example", "/a"), Set.of(SenderLogoUtils.PNG)));
    assertEquals(List.of("/a", "/b", "/c"), hits);
  }

  /**
   * A body past the limit is refused, whether its length is declared or not.
   */
  @Test
  void anOversizedBodyIsRefused() {
    byte[] big = new byte[2048];
    System.arraycopy(PNG, 0, big, 0, PNG.length);
    answers.put("/declared", ok("image/png", big));
    answers.put("/chunked", new Answer(200, "image/png", big, null, true, 0));
    assertNull(fetcher.fetch(url("brand.example", "/declared"), Set.of(SenderLogoUtils.PNG)));
    assertNull(fetcher.fetch(url("brand.example", "/chunked"), Set.of(SenderLogoUtils.PNG)));
  }

  /**
   * The declared type must be an image type, and the bytes must be an image of a type
   * accepted: an HTML page, whatever it declares, an image declared as HTML, or no type
   * at all, is refused; so is an error status.
   */
  @Test
  void theDeclaredAndTheRealTypeMustBothBeImages() {
    answers.put("/html-as-png", ok("image/png", "<html><body>hi</body></html>".getBytes(StandardCharsets.UTF_8)));
    answers.put("/png-as-html", ok("text/html", PNG));
    answers.put("/untyped", ok(null, PNG));
    answers.put("/svg-not-accepted", ok(SenderLogoUtils.SVG, SVG.getBytes(StandardCharsets.UTF_8)));
    answers.put("/missing", new Answer(404, "image/png", PNG, null, false, 0));
    for (String path : new String[] { "/html-as-png", "/png-as-html", "/untyped", "/svg-not-accepted", "/missing" }) {
      assertNull(fetcher.fetch(url("brand.example", path), Set.of(SenderLogoUtils.PNG)), path);
    }
  }

  /**
   * A server that does not answer in time gives nothing.
   */
  @Test
  void aSlowServerGivesNothing() {
    answers.put("/slow", new Answer(200, "image/png", PNG, null, false, 1500));
    assertNull(fetcher.fetch(url("brand.example", "/slow"), Set.of(SenderLogoUtils.PNG)));
  }

  /**
   * The production fetcher reads https on port 443 only, public addresses only,
   * nothing exempted, two redirects at most: pinned on its policy, since a plain
   * http URL refused by its shape and a name the real DNS cannot resolve both
   * give "no logo". And indeed a plain http URL is refused before any connection,
   * the logo of a BIMI record pointing at one included.
   */
  @Test
  void productionReadsHttpsOnly() {
    SenderLogoFetcher production = new SenderLogoFetcher(this::lookup);
    try {
      assertEquals(Set.of("https"), production.policy().getAllowedSchemes());
      assertEquals(Set.of(443), production.policy().getAllowedPorts());
      assertFalse(production.policy().isAnyPortAllowed());
      assertFalse(production.policy().isInternalAddressesAllowed());
      assertTrue(production.policy().getExemptHosts().isEmpty());
      assertTrue(production.policy().getExemptAddresses().isEmpty());
      assertEquals(SenderLogoFetcher.MAX_REDIRECTS, production.policy().getMaxRedirects());
      assertEquals(SenderLogoFetcher.TOTAL_TIMEOUT, production.policy().getTotalTimeout());
      answers.put("/favicon.ico", ok("image/png", PNG));
      assertNull(production.fetch(url("brand.example", "/favicon.ico"), Set.of(SenderLogoUtils.PNG)));
      assertNull(production.fetch("http://brand.example/favicon.ico", Set.of(SenderLogoUtils.PNG)));
      assertEquals(List.of(), hits);
    } finally {
      production.close();
    }
  }

  /**
   * A site with no favicon gets the icon its home page declares, a relative href
   * resolved against the page: the page and that icon are read, nothing else.
   */
  @Test
  void aMissingFaviconFallsBackToTheDeclaredIcon() {
    answers.put("/", page("<html><head><link rel=\"shortcut icon\" href=\"static/brand.png\"></head></html>"));
    answers.put("/static/brand.png", ok("image/png", PNG));

    SenderLogo logo = fetcher.resolve("brand.example");

    assertEquals(SenderLogo.SOURCE_ICON, logo.getSource());
    assertEquals(SenderLogoUtils.PNG, logo.getContentType());
    assertEquals(List.of("/favicon.ico", "/", "/static/brand.png"), hits);
  }

  /**
   * A favicon that redirects to a web page, that is a web page, or that is empty, gives
   * way to the declared icon; a working favicon never has the page read.
   */
  @Test
  void aFaviconThatIsNoImageFallsBackToTheDeclaredIcon() {
    answers.put("/", page("<link rel=\"apple-touch-icon\" href=\"" + url("cdn.example", "/touch.png") + "\">"));
    answers.put("/touch.png", ok("image/png", PNG));
    answers.put("/landing", page("<p>welcome</p>"));
    for (Answer favicon : new Answer[] { redirect("/landing"), page("<p>not found</p>"), ok("image/x-icon", new byte[0]) }) {
      hits.clear();
      answers.put("/favicon.ico", favicon);
      assertEquals(SenderLogo.SOURCE_ICON, fetcher.resolve("brand.example").getSource(), favicon.toString());
      assertTrue(hits.contains("/touch.png"), favicon.toString());
    }

    hits.clear();
    answers.put("/favicon.ico", ok("image/x-icon", PNG));
    assertEquals(SenderLogo.SOURCE_ICON, fetcher.resolve("brand.example").getSource());
    assertEquals(List.of("/favicon.ico"), hits);
  }

  /**
   * A declared icon that is a data: or a javascript: URL is skipped, never fetched nor
   * kept, and the next declared one is used; a page declaring only those, or only a
   * mask icon, gives no logo.
   */
  @Test
  void aDeclaredIconOnAnotherSchemeIsSkipped() {
    String refused = "<link rel=\"icon\" href=\"data:image/png;base64,iVBORw0KGgo=\">"
        + "<link rel=\"icon\" href=\"javascript:alert(1)\"><link rel=\"mask-icon\" href=\"/mask.svg\">";
    answers.put("/", page(refused));
    answers.put("/mask.svg", ok(SenderLogoUtils.SVG, SVG.getBytes(StandardCharsets.UTF_8)));
    assertFalse(fetcher.resolve("brand.example").isPresent());
    assertEquals(List.of("/favicon.ico", "/"), hits);

    hits.clear();
    answers.put("/", page(refused + "<link rel=\"ICON\" href=\"/real.png\">"));
    answers.put("/real.png", ok("image/png", PNG));
    assertEquals(SenderLogo.SOURCE_ICON, fetcher.resolve("brand.example").getSource());
    assertEquals(List.of("/favicon.ico", "/", "/real.png"), hits);
  }

  /**
   * A page is read up to its limit and no further: an icon declared within it is found
   * though the page is longer, one declared past it is never seen. A page that is not
   * declared as HTML is not parsed at all.
   */
  @Test
  void aHomePageIsReadUpToItsLimit() {
    String padding = "<!--" + "x".repeat(PAGE_LIMIT) + "-->";
    answers.put("/", page("<link rel=\"icon\" href=\"/early.png\">" + padding));
    answers.put("/early.png", ok("image/png", PNG));
    answers.put("/late.png", ok("image/png", PNG));
    assertEquals(SenderLogo.SOURCE_ICON, fetcher.resolve("brand.example").getSource(), "declared within the limit");

    hits.clear();
    answers.put("/", page(padding + "<link rel=\"icon\" href=\"/late.png\">"));
    assertFalse(fetcher.resolve("brand.example").isPresent(), "declared past the limit");
    assertEquals(List.of("/favicon.ico", "/"), hits);

    hits.clear();
    answers.put("/", ok("text/plain", "<link rel=\"icon\" href=\"/early.png\">".getBytes(StandardCharsets.UTF_8)));
    assertFalse(fetcher.resolve("brand.example").isPresent(), "a page not declared as HTML");
    assertEquals(List.of("/favicon.ico", "/"), hits);
  }

  /**
   * The home page and its declared icon go through the same guard as the favicon: a
   * page redirecting to an internal address, or declaring an icon on one, is refused,
   * and the internal target never sees a request.
   */
  @Test
  void theHomePageAndItsIconAreGuarded() {
    answers.put("/", redirect(url("loopback.example", "/page")));
    answers.put("/page", page("<link rel=\"icon\" href=\"/x.png\">"));
    answers.put("/x.png", ok("image/png", PNG));
    assertFalse(fetcher.resolve("brand.example").isPresent());
    assertEquals(List.of("/favicon.ico", "/"), hits, "the redirect to loopback is never followed");

    for (String host : new String[] { "loopback.example", "internal.example", "metadata.example" }) {
      hits.clear();
      answers.put("/", page("<link rel=\"icon\" href=\"" + url(host, "/x.png") + "\">"));
      assertFalse(fetcher.resolve("brand.example").isPresent(), host);
      assertEquals(List.of("/favicon.ico", "/"), hits, host);
    }
  }

  /**
   * A page's base URL resolves its relative icon, and an icon declared at the favicon's
   * own address, which just gave nothing, is not asked for twice.
   */
  @Test
  void theDeclaredIconIsResolvedAgainstThePage() {
    answers.put("/", page("<head><base href=\"" + url("cdn.example", "/assets/") + "\"><link rel=\"icon\" href=\"logo.png\"></head>"));
    answers.put("/assets/logo.png", ok("image/png", PNG));
    assertEquals(SenderLogo.SOURCE_ICON, fetcher.resolve("brand.example").getSource());
    assertEquals(List.of("/favicon.ico", "/", "/assets/logo.png"), hits);

    hits.clear();
    answers.put("/", page("<link rel=\"icon\" href=\"/favicon.ico\">"));
    assertFalse(fetcher.resolve("brand.example").isPresent());
    assertEquals(List.of("/favicon.ico", "/"), hits);
  }

  /**
   * A fetcher over the test names: plain http at the stub's port, the public names
   * exempted, 1 KB per logo, {@link #PAGE_LIMIT} per page, short timeouts, two
   * redirects.
   *
   * @param dns the DNS
   * @return the fetcher
   */
  private SenderLogoFetcher fetcher(DnsTxtLookup dns) {
    SafeFetchPolicy policy = SafeFetchPolicy.builder()
                                            .allowedSchemes(Set.of("http"))
                                            .allowedPorts(Set.of(port))
                                            .resolver(this::resolve)
                                            .exemptHosts(PUBLIC_HOSTS)
                                            .maxBytes(PAGE_LIMIT)
                                            .maxRedirects(2)
                                            .connectTimeout(Duration.ofSeconds(2))
                                            .readTimeout(Duration.ofMillis(500))
                                            .totalTimeout(Duration.ofSeconds(2))
                                            .build();
    return new SenderLogoFetcher(policy, dns, 1024, PAGE_LIMIT, "http://%s:" + port + "/favicon.ico");
  }

  /**
   * Publishes a BIMI record.
   *
   * @param domain the domain
   * @param location the logo's URL
   */
  private void bimi(String domain, String location) {
    txt.put("default._bimi." + domain, List.of("v=BIMI1; l=" + location));
  }

  /**
   * A URL on the stub's port.
   *
   * @param host the host
   * @param path the path
   * @return the URL
   */
  private String url(String host, String path) {
    return "http://" + host + ":" + port + path;
  }

  /**
   * A 200 answer.
   *
   * @param type the Content-Type, or null
   * @param body the body
   * @return the answer
   */
  private static Answer ok(String type, byte[] body) {
    return new Answer(200, type, body, null, false, 0);
  }

  /**
   * A 200 HTML page.
   *
   * @param html the page
   * @return the answer
   */
  private static Answer page(String html) {
    return ok("text/html; charset=utf-8", html.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * A 302 answer.
   *
   * @param location where to
   * @return the answer
   */
  private static Answer redirect(String location) {
    return new Answer(302, null, new byte[0], location, false, 0);
  }

  /**
   * The test names' addresses.
   *
   * @param host the host
   * @return its addresses
   * @throws UnknownHostException when it is not a test name
   */
  private InetAddress[] resolve(String host) throws UnknownHostException {
    InetAddress[] addresses = hosts.get(host);
    if (addresses == null) {
      throw new UnknownHostException(host);
    }
    return addresses;
  }

  /**
   * The test names' TXT records.
   *
   * @param name the DNS name
   * @return its records
   */
  private List<String> lookup(String name) {
    return txt.getOrDefault(name, List.of());
  }

  /**
   * Answers a request as the test set it.
   *
   * @param exchange the request
   * @throws IOException when the client went away
   */
  private void serve(HttpExchange exchange) throws IOException {
    String path = exchange.getRequestURI().getPath();
    hits.add(path);
    Answer answer = answers.getOrDefault(path, new Answer(404, null, new byte[0], null, false, 0));
    try {
      if (answer.delayMs() > 0) {
        Thread.sleep(answer.delayMs());
      }
      if (answer.type() != null) {
        exchange.getResponseHeaders().add("Content-Type", answer.type());
      }
      if (answer.location() != null) {
        exchange.getResponseHeaders().add("Location", answer.location());
      }
      exchange.sendResponseHeaders(answer.status(), answer.chunked() ? 0 : (answer.body().length == 0 ? -1 : answer.body().length));
      if (answer.body().length > 0) {
        try (OutputStream out = exchange.getResponseBody()) {
          out.write(answer.body());
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException e) {
      // the client went away, as a limit test intends
    } finally {
      exchange.close();
    }
  }
}
