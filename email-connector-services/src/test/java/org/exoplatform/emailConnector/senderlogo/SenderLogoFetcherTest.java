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

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * The sender logo fetch (EXO-90893) against a stub server on loopback, standing for
 * the public internet: BIMI under an enforced DMARC policy, the icon fallback, and every
 * guard -- internal addresses at connect time and after a redirect, the redirect
 * count, the size limit, the declared and the real type, the timeout, https only.
 */
class SenderLogoFetcherTest {

  private static final String           SVG  = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\">"
      + "<script>alert(1)</script><rect width=\"10\" height=\"10\" fill=\"#00c805\"/></svg>";

  private static final byte[]           PNG  = { (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 13 };

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
    SenderLogoAddressGuard guard = new SenderLogoAddressGuard(Set.of("http"), Set.of(port), this::resolve, PUBLIC_HOSTS);
    fetcher = new SenderLogoFetcher(guard,
                                    this::lookup,
                                    1024,
                                    Duration.ofSeconds(2),
                                    Duration.ofMillis(500),
                                    Duration.ofSeconds(2),
                                    2,
                                    "http://%s:" + port + "/favicon.ico");
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
   * A BIMI logo under an enforced DMARC policy is used, sanitised, and the icon is
   * never asked for.
   */
  @Test
  void aBimiLogoUnderAnEnforcedPolicyIsUsed() {
    bimi("brand.example", url("cdn.example", "/logo.svg"));
    txt.put("_dmarc.brand.example", List.of("v=DMARC1; p=reject"));
    answers.put("/logo.svg", ok(SenderLogoUtils.SVG, SVG.getBytes(StandardCharsets.UTF_8)));

    SenderLogo logo = fetcher.resolve("brand.example");

    assertEquals(SenderLogo.SOURCE_BIMI, logo.getSource());
    assertEquals(SenderLogoUtils.SVG, logo.getContentType());
    String svg = new String(logo.getData(), StandardCharsets.UTF_8);
    assertFalse(svg.contains("script"), svg);
    assertTrue(svg.contains("#00c805"), svg);
    assertEquals(List.of("/logo.svg"), hits);
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
    assertEquals(List.of("/favicon.ico"), hits, "only the icon was asked for, and it has none");
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
   * The production fetcher reads https only: a plain http URL is refused before any
   * connection, and so is the logo of a BIMI record pointing at one.
   */
  @Test
  void productionReadsHttpsOnly() {
    SenderLogoFetcher production = new SenderLogoFetcher(this::lookup);
    try {
      answers.put("/favicon.ico", ok("image/png", PNG));
      assertNull(production.fetch(url("brand.example", "/favicon.ico"), Set.of(SenderLogoUtils.PNG)));
      assertNull(production.fetch("http://brand.example/favicon.ico", Set.of(SenderLogoUtils.PNG)));
      assertEquals(List.of(), hits);
    } finally {
      production.close();
    }
  }

  /**
   * A fetcher over the test names with another DNS.
   *
   * @param dns the DNS
   * @return the fetcher
   */
  private SenderLogoFetcher fetcher(DnsTxtLookup dns) {
    SenderLogoAddressGuard guard = new SenderLogoAddressGuard(Set.of("http"), Set.of(port), this::resolve, PUBLIC_HOSTS);
    return new SenderLogoFetcher(guard,
                                 dns,
                                 1024,
                                 Duration.ofSeconds(2),
                                 Duration.ofMillis(500),
                                 Duration.ofSeconds(2),
                                 2,
                                 "http://%s:" + port + "/favicon.ico");
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
