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

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.model.SenderLogo;
import org.exoplatform.emailConnector.utils.SenderLogoUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.upload.SvgUploadValidator;

import io.meeds.commons.http.SafeFetchException;
import io.meeds.commons.http.SafeFetchPolicy;
import io.meeds.commons.http.SafeFetchRequest;
import io.meeds.commons.http.SafeFetchResponse;
import io.meeds.commons.http.SafeHttpFetcher;

import jakarta.annotation.PreDestroy;

/**
 * Finds a mail domain's brand logo on the internet (EXO-90893): the logo the domain
 * publishes through BIMI, else its site's own icon.
 * <p>
 * <b>BIMI.</b> The TXT record of {@code default._bimi.<domain>}, then of the
 * organisational domain's when the domain has none ({@code news.brand.com}, then
 * {@code brand.com}). Its logo is used only when the domain's DMARC policy is enforced
 * (its own {@code _dmarc} record, else its organisational domain's), as BIMI requires;
 * a domain that declines (an empty {@code l=}) gets no logo at all, its icon included.
 * BIMI logos are SVG and nothing else is accepted from one. An SVG, from BIMI or as an
 * icon, is served only when the platform's own SVG upload check accepts it
 * ({@link #isSafeSvg}); the endpoint's Content-Security-Policy covers what that check
 * leaves, such as an external {@code href}, which no image renders.
 * <p>
 * <b>The icon.</b> {@code https://<organisational domain>/favicon.ico}, one request
 * (two redirects at most): the address every browser asks a site for, so most sites with
 * an icon answer it. When it gives no image -- missing, empty, an HTML page, or a
 * redirect to one -- the site's home page is read instead, at most
 * {@link #PAGE_MAX_BYTES} of it under the same guard, and the first icon it declares in
 * a {@code <link rel="icon">} (or {@code shortcut icon}, {@code apple-touch-icon}) is
 * fetched as the favicon is (EXO-90909). The page is parsed by jsoup for that one link
 * ({@code SenderLogoUtils#declaredIconUrl}); nothing else of it is read or kept.
 * <p>
 * <b>Bounds</b> are the platform's {@link SafeHttpFetcher}'s under this policy: https
 * on port 443 and public addresses only, judged by the HTTP client's own resolver at
 * every connection, redirects included; a connect and a read timeout, a deadline over
 * each fetch, a body limit counted on the decoded bytes, two redirects at most, the
 * declared type checked against the image types before the body is read and the
 * bytes against what they claim. Each fetch has its own deadline, so one domain's
 * lookup -- BIMI logo, favicon, home page, declared icon -- takes at most four of
 * them ({@link #TOTAL_TIMEOUT} each), on a background thread. <b>Nothing of the
 * platform goes out</b>: no cookie, no credentials, no referrer, no retry.
 * <p>
 * Any failure -- no DNS, no answer, a refused address, a wrong type, a body too large
 * -- is "no logo"; the caller caches that too.
 */
@Component
public class SenderLogoFetcher {

  /** Largest logo read, in bytes. */
  public static final int          MAX_BYTES       = 64 * 1024;

  /** Longest wait for a connection. */
  static final Duration            CONNECT_TIMEOUT = Duration.ofSeconds(3);

  /** Longest wait between two reads. */
  static final Duration            READ_TIMEOUT    = Duration.ofSeconds(3);

  /** Longest fetch of one logo, redirects included. */
  static final Duration            TOTAL_TIMEOUT   = Duration.ofSeconds(6);

  /** Most redirects followed. */
  static final int                 MAX_REDIRECTS   = 2;

  /** Where a domain's icon is read, the organisational domain filling the blank. */
  static final String              ICON_URL        = "https://%s/favicon.ico";

  /**
   * The most of a home page read for the icon it declares, in bytes: its head comes
   * first, and what lies past this limit is never read.
   */
  static final int                 PAGE_MAX_BYTES  = 256 * 1024;

  private static final String      USER_AGENT      = "eXo-Email-Connector-Sender-Logo/1.0";

  private static final String      ACCEPT          = "image/svg+xml, image/png, image/x-icon, image/vnd.microsoft.icon, image/webp, image/jpeg;q=0.9";

  private static final String      PAGE_ACCEPT     = "text/html, application/xhtml+xml;q=0.9";

  private static final Set<String> BIMI_TYPES      = Set.of(SenderLogoUtils.SVG);

  private static final Set<String> ICON_TYPES      = Set.of(SenderLogoUtils.SVG,
                                                            SenderLogoUtils.PNG,
                                                            SenderLogoUtils.ICO,
                                                            SenderLogoUtils.JPEG,
                                                            SenderLogoUtils.WEBP);

  private static final Log         LOG             = ExoLogger.getLogger(SenderLogoFetcher.class);

  private final SafeHttpFetcher    fetcher;

  private final DnsTxtLookup       dns;

  private final int                maxBytes;

  private final int                pageMaxBytes;

  private final String             iconUrl;

  private final SvgUploadValidator svgValidator    = new SvgUploadValidator(MAX_BYTES);

  /**
   * The production fetcher: https on port 443, public addresses only, the bounds
   * above.
   *
   * @param dns the DNS client of the BIMI and DMARC lookups
   */
  @Autowired
  public SenderLogoFetcher(DnsTxtLookup dns) {
    this(SafeFetchPolicy.builder()
                        .name("email-connector-sender-logo")
                        .userAgent(USER_AGENT)
                        .httpsOnly()
                        .allowedPorts(Set.of(443))
                        .maxBytes(PAGE_MAX_BYTES)
                        .maxRedirects(MAX_REDIRECTS)
                        .connectTimeout(CONNECT_TIMEOUT)
                        .readTimeout(READ_TIMEOUT)
                        .totalTimeout(TOTAL_TIMEOUT)
                        .build(),
         dns,
         MAX_BYTES,
         PAGE_MAX_BYTES,
         ICON_URL);
  }

  /**
   * A fetcher with its policy, DNS and limits handed in, for the tests.
   *
   * @param policy what may be read and how far a fetch goes
   * @param dns the DNS client
   * @param maxBytes largest logo read
   * @param pageMaxBytes most of a home page read
   * @param iconUrl where an icon is read, {@code %s} standing for the domain
   */
  SenderLogoFetcher(SafeFetchPolicy policy, DnsTxtLookup dns, int maxBytes, int pageMaxBytes, String iconUrl) {
    this.fetcher = new SafeHttpFetcher(policy);
    this.dns = dns;
    this.maxBytes = maxBytes;
    this.pageMaxBytes = pageMaxBytes;
    this.iconUrl = iconUrl;
  }

  /**
   * A domain's logo: its BIMI logo when it publishes one under an enforced DMARC
   * policy, else its site's icon -- the favicon, else the icon its home page declares
   * -- else none.
   *
   * @param domain a normalised domain ({@code SenderLogoUtils#normaliseDomain})
   * @return the logo, or the "none" answer; never null
   */
  public SenderLogo resolve(String domain) {
    long now = System.currentTimeMillis();
    String organisational = SenderLogoUtils.organisationalDomain(domain);
    String location = bimiLocation(domain, organisational);
    if (location != null && location.isEmpty()) {
      return SenderLogo.none(now);
    }
    if (location != null && dmarcEnforced(domain, organisational)) {
      byte[] svg = fetch(location, BIMI_TYPES);
      if (svg != null && isSafeSvg(svg)) {
        return new SenderLogo(svg, SenderLogoUtils.SVG, SenderLogo.SOURCE_BIMI, now);
      }
    }
    String favicon = String.format(iconUrl, organisational);
    SenderLogo icon = iconLogo(fetch(favicon, ICON_TYPES), now);
    if (icon == null) {
      icon = iconLogo(declaredIcon(favicon), now);
    }
    return icon == null ? SenderLogo.none(now) : icon;
  }

  /**
   * The icon a domain's home page declares (EXO-90909), for a site whose favicon gave
   * no image: the page, at the root of the favicon's site, read within
   * {@link #pageMaxBytes} under the same guard, timeouts and redirect bound, its first
   * declared icon then fetched as the favicon is. An icon declared at the favicon's own
   * address, which just gave nothing, is not asked for again.
   *
   * @param favicon the favicon's URL, whose site's root is the home page
   * @return the icon's bytes, or null when the page declares none usable
   */
  private byte[] declaredIcon(String favicon) {
    URI home;
    try {
      home = new URI(favicon).resolve("/");
    } catch (URISyntaxException | IllegalArgumentException e) {
      return null; // NOSONAR null is "nothing usable"
    }
    SafeFetchResponse page = fetch(home, SenderLogoUtils.PAGE_TYPES, pageMaxBytes, true, PAGE_ACCEPT);
    String declared = page == null ? null : SenderLogoUtils.declaredIconUrl(page.body(), page.uri().toString());
    if (declared == null || declared.equals(favicon)) {
      return null; // NOSONAR as above
    }
    return fetch(declared, ICON_TYPES);
  }

  /**
   * A fetched icon as a logo, served as the type its bytes are: none for nothing, for
   * bytes that are no accepted image, or for an SVG the platform's check refuses.
   *
   * @param icon the bytes, or null
   * @param now when it was resolved
   * @return the logo, or null when the icon is unusable
   */
  private SenderLogo iconLogo(byte[] icon, long now) {
    String type = SenderLogoUtils.sniffImageType(icon);
    if (icon == null || type == null || SenderLogoUtils.SVG.equals(type) && !isSafeSvg(icon)) {
      return null; // NOSONAR null is "no usable icon"
    }
    return new SenderLogo(icon, type, SenderLogo.SOURCE_ICON, now);
  }

  /**
   * Whether an SVG logo is safe to serve, as the platform judges an uploaded SVG
   * ({@code SvgUploadValidator}): well-formed, no document type declaration, no
   * external entity or XInclude, no script, foreign object, frame, object, embed or
   * applet, no event handler, no {@code javascript:} or {@code data:text/html} value, no
   * {@code xml-stylesheet} instruction. An unsafe one is refused whole -- the domain
   * gets its next fallback, never a cleaned copy. The validator parses by streaming, so
   * no nesting depth exhausts the stack.
   *
   * @param svg the SVG bytes
   * @return true when the platform's validator accepts them
   */
  boolean isSafeSvg(byte[] svg) {
    try {
      svgValidator.validate("logo.svg", SenderLogoUtils.SVG, new ByteArrayInputStream(svg));
      return true;
    } catch (Exception e) {
      LOG.debug("An SVG logo was refused: {}", e.getMessage());
      return false;
    }
  }

  /**
   * Closes the connections.
   */
  @PreDestroy
  public void close() {
    fetcher.close();
  }

  /**
   * @return the policy the logos are fetched under, for the tests to pin the
   *         production one
   */
  SafeFetchPolicy policy() {
    return fetcher.getPolicy();
  }

  /**
   * The BIMI location of a domain, from its own record, else from its organisational
   * domain's.
   *
   * @param domain the domain
   * @param organisational its organisational domain
   * @return the location, an empty string when the domain declines, null when there
   *         is none
   */
  private String bimiLocation(String domain, String organisational) {
    String location = SenderLogoUtils.bimiLocation(txt("default._bimi." + domain));
    if (location == null && !organisational.equals(domain)) {
      location = SenderLogoUtils.bimiLocation(txt("default._bimi." + organisational));
    }
    return location;
  }

  /**
   * Whether the domain's DMARC policy is enforced: its own record when it has one, else
   * its organisational domain's, read for a subdomain.
   *
   * @param domain the domain
   * @param organisational its organisational domain
   * @return true when enforced
   */
  private boolean dmarcEnforced(String domain, String organisational) {
    List<String> own = txt("_dmarc." + domain);
    if (SenderLogoUtils.hasDmarcRecord(own) || organisational.equals(domain)) {
      return SenderLogoUtils.dmarcEnforced(own, false);
    }
    return SenderLogoUtils.dmarcEnforced(txt("_dmarc." + organisational), true);
  }

  /**
   * A name's TXT records; none when the DNS cannot answer.
   *
   * @param name the DNS name
   * @return the records
   */
  private List<String> txt(String name) {
    try {
      return dns.lookup(name);
    } catch (DnsLookupException e) {
      LOG.debug("No DNS answer for a sender logo lookup: {}", e.getCause() == null ? null : e.getCause().getClass().getSimpleName());
      return List.of();
    }
  }

  /**
   * Reads an image, following at most {@link #MAX_REDIRECTS} redirects, each target
   * checked by the guard before it is requested and again by the resolver when the
   * connection opens.
   *
   * @param url the URL
   * @param types the image types accepted, as sniffed from the bytes
   * @return the body, or null when nothing usable was read
   */
  byte[] fetch(String url, Set<String> types) {
    URI uri;
    try {
      uri = new URI(url);
    } catch (URISyntaxException e) {
      return null; // NOSONAR null is "nothing usable"
    }
    SafeFetchResponse response = fetch(uri, SenderLogoUtils.DECLARED_TYPES, maxBytes, false, ACCEPT);
    byte[] body = response == null ? null : response.body();
    String type = SenderLogoUtils.sniffImageType(body);
    return type != null && types.contains(type) ? body : null;
  }

  /**
   * Reads a body through the platform's fetcher, which follows at most
   * {@link #MAX_REDIRECTS} redirects, each target checked by the guard before it is
   * requested and again by the resolver when the connection opens.
   *
   * @param url the URL
   * @param declaredTypes the {@code Content-Type} media types accepted
   * @param limit the most bytes read
   * @param keepPrefix whether a longer body is cut at the limit (a page, whose head
   *          comes first) rather than refused (an image, useless cut)
   * @param accept the {@code Accept} header sent
   * @return the answer, with the URL it came from, or null when nothing usable was
   *         read
   */
  private SafeFetchResponse fetch(URI url, Set<String> declaredTypes, int limit, boolean keepPrefix, String accept) {
    SafeFetchRequest request = SafeFetchRequest.get(url).withAccept(accept).withAcceptedContentTypes(declaredTypes).withMaxBytes(limit);
    if (keepPrefix) {
      request = request.truncatedAtLimit();
    }
    try {
      return fetcher.fetch(request);
    } catch (SafeFetchException e) {
      LOG.debug("A sender logo URL gave nothing usable: {}", e.getFailure());
      return null; // NOSONAR null is "nothing usable"
    }
  }
}
