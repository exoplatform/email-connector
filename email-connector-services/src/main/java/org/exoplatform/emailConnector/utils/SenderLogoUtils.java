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
package org.exoplatform.emailConnector.utils;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.IDN;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.psl.PublicSuffixMatcherLoader;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

/**
 * The parts of the sender brand logo (EXO-90893) that read text and bytes only: which
 * domain a logo is looked up for, what the domain's BIMI and DMARC records say, what
 * kind of image a fetched body really is, and which icon a home page declares.
 */
public final class SenderLogoUtils {

  /** The type an SVG logo is served as. */
  public static final String       SVG                 = "image/svg+xml";

  /** The type a PNG logo is served as. */
  public static final String       PNG                 = "image/png";

  /** The type a JPEG logo is served as. */
  public static final String       JPEG                = "image/jpeg";

  /** The type a WebP logo is served as. */
  public static final String       WEBP                = "image/webp";

  /** The type a Windows icon is served as. */
  public static final String       ICO                 = "image/x-icon";

  /**
   * The {@code Content-Type} values a logo response may declare: the image types a
   * mail reader shows, and the three names a Windows icon goes by. Anything else -- an
   * HTML error page answered with a 200, a script, a type left to the browser's
   * guessing -- is refused before its bytes are looked at.
   */
  private static final Set<String> DECLARED_TYPES      = Set.of(SVG,
                                                                PNG,
                                                                JPEG,
                                                                WEBP,
                                                                ICO,
                                                                "image/vnd.microsoft.icon",
                                                                "image/ico");

  /** The declared types of a home page read for the icon it declares. */
  private static final Set<String> PAGE_TYPES          = Set.of("text/html", "application/xhtml+xml");

  /**
   * The {@code rel} tokens that declare a site's icon: {@code icon} (alone or in
   * {@code shortcut icon}) and Apple's touch icons. {@code mask-icon}, a one-colour
   * silhouette, is not a logo.
   */
  private static final Set<String> ICON_RELS           = Set.of("icon", "apple-touch-icon", "apple-touch-icon-precomposed");

  /** The schemes a declared icon is fetched from; the fetcher's guard narrows them further. */
  private static final Set<String> ICON_SCHEMES        = Set.of("https", "http");

  /** The longest domain name, in characters (RFC 1035). */
  private static final int         MAX_DOMAIN_LENGTH   = 253;

  /** One label of a domain name in its ASCII form: letters, digits and inner hyphens. */
  private static final Pattern     LABEL               = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  /** A top-level domain: alphabetic, or an IDN one in its punycode form. */
  private static final Pattern     TOP_LEVEL           = Pattern.compile("[a-z]{2,63}|xn--[a-z0-9-]{1,59}");

  /** How many leading bytes are read to tell an SVG document from anything else. */
  private static final int         SVG_SNIFF_LENGTH    = 1024;

  private SenderLogoUtils() {
  }

  /**
   * A domain name in the one form the logo is cached and served under: ASCII
   * (punycode), lower-cased, without a trailing dot -- or null when it is not a domain
   * name a mail could come from: an address literal, a single label, a label too long
   * or holding anything but letters, digits and inner hyphens, a numeric top level.
   *
   * @param domain the domain as written
   * @return the normalised domain, or null when it is not one
   */
  public static String normaliseDomain(String domain) {
    if (StringUtils.isBlank(domain)) {
      return null;
    }
    String ascii;
    try {
      ascii = IDN.toASCII(StringUtils.removeEnd(domain.trim(), "."), IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return null;
    }
    if (ascii.isEmpty() || ascii.length() > MAX_DOMAIN_LENGTH) {
      return null;
    }
    String[] labels = ascii.split("\\.", -1);
    if (labels.length < 2 || !TOP_LEVEL.matcher(labels[labels.length - 1]).matches()) {
      return null;
    }
    for (String label : labels) {
      if (!LABEL.matcher(label).matches()) {
        return null;
      }
    }
    return ascii;
  }

  /**
   * The normalised domain of a mail address.
   *
   * @param address the address
   * @return its domain, or null when the address has no valid one
   */
  public static String domainOfAddress(String address) {
    if (StringUtils.isBlank(address) || !address.contains("@")) {
      return null;
    }
    return normaliseDomain(StringUtils.substringAfterLast(address.trim(), "@"));
  }

  /**
   * The organisational domain of a normalised domain, as DMARC and BIMI define it: the
   * public suffix plus one label, read from the Public Suffix List the server's HTTP
   * client ships ({@code PublicSuffixMatcherLoader}), private suffixes included. So
   * {@code news.brand.com} gives {@code brand.com}, {@code mail.brand.co.uk} gives
   * {@code brand.co.uk}, and {@code tenant.herokuapp.com} stays itself: the hosting
   * platform's own brand is never shown on a tenant's mail. A domain that is itself a
   * public suffix is its own.
   *
   * @param domain a normalised domain
   * @return its organisational domain
   */
  public static String organisationalDomain(String domain) {
    String root = PublicSuffixMatcherLoader.getDefault().getDomainRoot(domain);
    return root == null ? domain : root;
  }

  /**
   * One TXT record as a single string. A record is made of character-strings, which are
   * one value concatenated without separator (as for SPF, RFC 7208 §3.3). The JDK's DNS
   * lookup answers them joined by single spaces, each one bare, or quoted when it holds
   * a space, a quote or a backslash or is empty -- with {@code \\}, {@code \"} and
   * {@code \DDD} (a decimal byte) escapes inside the quotes. Each is read back and
   * the pieces are joined.
   *
   * @param raw the record as the lookup answered it
   * @return the record's value
   */
  public static String txtValue(String raw) {
    if (raw == null) {
      return "";
    }
    String value = raw.trim();
    StringBuilder joined = new StringBuilder();
    int i = 0;
    while (i < value.length()) {
      char c = value.charAt(i);
      if (c == ' ') {
        i++;
      } else if (c == '"') {
        i = readQuoted(value, i + 1, joined);
      } else {
        int end = value.indexOf(' ', i);
        end = end < 0 ? value.length() : end;
        joined.append(value, i, end);
        i = end;
      }
    }
    return joined.toString();
  }

  /**
   * Reads one quoted character-string, its escapes resolved.
   *
   * @param value the record as answered
   * @param start the index just after the opening quote
   * @param joined where the string's characters are appended
   * @return the index just after the closing quote, or the end of the record
   */
  private static int readQuoted(String value, int start, StringBuilder joined) {
    int i = start;
    while (i < value.length()) {
      char c = value.charAt(i);
      if (c == '"') {
        return i + 1;
      }
      if (c == '\\' && i + 3 < value.length() && Character.isDigit(value.charAt(i + 1)) && Character.isDigit(value.charAt(i + 2))
          && Character.isDigit(value.charAt(i + 3))) {
        joined.append((char) Integer.parseInt(value.substring(i + 1, i + 4)));
        i += 4;
      } else if (c == '\\' && i + 1 < value.length()) {
        joined.append(value.charAt(i + 1));
        i += 2;
      } else {
        joined.append(c);
        i++;
      }
    }
    return i;
  }

  /**
   * What a domain's BIMI records say about its logo.
   * <p>
   * Only the records whose first tag is {@code v=BIMI1} count, and exactly one must:
   * none means no BIMI, and several make the answer ambiguous, which the BIMI draft
   * reads as none. An empty {@code l=} is the domain declining to show a logo. The
   * location is not judged here: the fetch's address guard refuses anything but an
   * {@code https} URL, which then counts as no BIMI.
   * The evidence document ({@code a=}, a Verified Mark Certificate) is not checked:
   * verifying it needs an X.509 chain to the mark verifying authorities and the
   * logotype extension read out of the certificate, which this addon has no library
   * for; the DMARC enforcement checked beside it is what BIMI asks of every domain.
   *
   * @param records the TXT records of {@code default._bimi.<domain>}, as
   *          {@link #txtValue} gives them
   * @return the logo's location; an empty string when the domain declines; null when
   *         it publishes no BIMI record
   */
  public static String bimiLocation(List<String> records) {
    Map<String, String> tags = singleRecord(records, "BIMI1");
    if (tags == null) {
      return null;
    }
    String location = StringUtils.trimToEmpty(tags.get("l"));
    if (location.isEmpty()) {
      return "";
    }
    // An early draft allowed a comma-separated list; the first entry is the logo.
    return StringUtils.substringBefore(location, ",").trim();
  }

  /**
   * Whether a name's TXT records hold a DMARC record at all, so that a subdomain
   * without one of its own is judged by its organisational domain's.
   *
   * @param records the TXT records of {@code _dmarc.<domain>}
   * @return true when one of them opens with {@code v=DMARC1}
   */
  public static boolean hasDmarcRecord(List<String> records) {
    return records != null && records.stream()
                                     .map(SenderLogoUtils::tags)
                                     .anyMatch(tags -> !tags.isEmpty() && "v".equals(tags.keySet().iterator().next())
                                         && "DMARC1".equalsIgnoreCase(tags.get("v")));
  }

  /**
   * Whether a domain's DMARC record enforces its policy, which BIMI requires before a
   * logo is shown: a policy of {@code quarantine} or {@code reject}, applied to every
   * message ({@code pct} absent or 100). For a subdomain read from its organisational
   * domain's record, the subdomain policy {@code sp=} applies when the record sets one.
   *
   * @param records the TXT records of {@code _dmarc.<domain>}, as {@link #txtValue}
   *          gives them
   * @param subdomain whether the record is the organisational domain's, read for one of
   *          its subdomains
   * @return true when enforced; false with no record, several, or a weaker policy
   */
  public static boolean dmarcEnforced(List<String> records, boolean subdomain) {
    Map<String, String> tags = singleRecord(records, "DMARC1");
    if (tags == null) {
      return false;
    }
    String policy = subdomain && tags.containsKey("sp") ? tags.get("sp") : tags.get("p");
    String percent = tags.get("pct");
    boolean enforcing = "quarantine".equalsIgnoreCase(policy) || "reject".equalsIgnoreCase(policy);
    return enforcing && (percent == null || "100".equals(percent.trim()));
  }

  /**
   * Whether a response's declared {@code Content-Type} is one a logo may carry,
   * parameters ignored.
   *
   * @param contentType the header value
   * @return true when allowed
   */
  public static boolean isAllowedDeclaredType(String contentType) {
    if (StringUtils.isBlank(contentType)) {
      return false;
    }
    return DECLARED_TYPES.contains(StringUtils.substringBefore(contentType, ";").trim().toLowerCase(Locale.ROOT));
  }

  /**
   * Whether a response's declared {@code Content-Type} is a web page's, parameters
   * ignored: what a home page read for its declared icon must say it is.
   *
   * @param contentType the header value
   * @return true for HTML or XHTML
   */
  public static boolean isPageDeclaredType(String contentType) {
    if (StringUtils.isBlank(contentType)) {
      return false;
    }
    return PAGE_TYPES.contains(StringUtils.substringBefore(contentType, ";").trim().toLowerCase(Locale.ROOT));
  }

  /**
   * The icon a home page declares (EXO-90909): the first {@code <link>} whose
   * {@code rel} holds {@code icon}, {@code apple-touch-icon} or
   * {@code apple-touch-icon-precomposed} and whose {@code href}, resolved against the
   * page's address (and its {@code <base>}), is an http or https URL. A {@code data:},
   * {@code javascript:} or any other scheme is skipped. The page is parsed for that link
   * only, by jsoup, whatever its charset: nothing else of it is read or kept.
   *
   * @param page the page's bytes, already bounded by the caller
   * @param pageUrl the address the page was read from, after its redirects
   * @return the icon's absolute URL, or null when the page declares none usable
   */
  public static String declaredIconUrl(byte[] page, String pageUrl) {
    if (page == null || page.length == 0 || StringUtils.isBlank(pageUrl)) {
      return null;
    }
    Document document;
    try {
      document = Jsoup.parse(new ByteArrayInputStream(page), null, pageUrl);
    } catch (IOException | RuntimeException e) {
      return null; // NOSONAR null is "no icon declared"
    }
    for (Element link : document.select("link[rel][href]")) {
      if (!declaresIcon(link.attr("rel"))) {
        continue;
      }
      String url = link.absUrl("href");
      if (isFetchableIconUrl(url)) {
        return url;
      }
    }
    return null;
  }

  /**
   * Whether a {@code rel} value holds one of the icon tokens, case ignored.
   *
   * @param rel the attribute's value, space-separated tokens
   * @return true when it declares an icon
   */
  private static boolean declaresIcon(String rel) {
    for (String token : StringUtils.split(rel.toLowerCase(Locale.ROOT))) {
      if (ICON_RELS.contains(token)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a resolved icon URL may be handed to the fetcher at all: absolute, with a
   * host, on http or https -- never {@code data:}, {@code javascript:} or a relative
   * URL jsoup could not resolve.
   *
   * @param url the resolved URL, empty when jsoup could not resolve it
   * @return true when it may be fetched
   */
  private static boolean isFetchableIconUrl(String url) {
    if (StringUtils.isBlank(url)) {
      return false;
    }
    try {
      URI uri = new URI(url);
      return uri.getScheme() != null && ICON_SCHEMES.contains(uri.getScheme().toLowerCase(Locale.ROOT))
          && StringUtils.isNotBlank(uri.getHost());
    } catch (URISyntaxException e) {
      return false;
    }
  }

  /**
   * What kind of image some bytes really are, read from their first bytes, whatever
   * the response declared: the type a logo is served as is this one, never the
   * declared one.
   *
   * @param data the body
   * @return {@link #PNG}, {@link #JPEG}, {@link #WEBP}, {@link #ICO}, {@link #SVG}, or
   *         null when it is none of them
   */
  public static String sniffImageType(byte[] data) {
    if (data == null || data.length < 4) {
      return null;
    }
    if (startsWith(data, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A)) {
      return PNG;
    }
    if (startsWith(data, 0xFF, 0xD8, 0xFF)) {
      return JPEG;
    }
    if (data.length >= 12 && startsWith(data, 'R', 'I', 'F', 'F') && data[8] == 'W' && data[9] == 'E' && data[10] == 'B'
        && data[11] == 'P') {
      return WEBP;
    }
    if (startsWith(data, 0x00, 0x00, 0x01, 0x00) && data.length >= 6 && (data[4] != 0 || data[5] != 0)) {
      return ICO;
    }
    return looksLikeSvg(data) ? SVG : null;
  }

  /**
   * Whether the bytes open like an SVG document: after an optional byte order mark and
   * white space, an XML declaration, a comment or the {@code svg} element, with an
   * {@code <svg} within the first kilobyte.
   *
   * @param data the body
   * @return true when it looks like SVG
   */
  private static boolean looksLikeSvg(byte[] data) {
    String head = new String(data, 0, Math.min(data.length, SVG_SNIFF_LENGTH), StandardCharsets.UTF_8);
    head = StringUtils.removeStart(head, "\uFEFF").stripLeading().toLowerCase(Locale.ROOT);
    return (head.startsWith("<?xml") || head.startsWith("<svg") || head.startsWith("<!--")) && head.contains("<svg");
  }

  /**
   * Whether the bytes start with the given values.
   *
   * @param data the bytes
   * @param prefix the expected values, each 0 to 255
   * @return true when they do
   */
  private static boolean startsWith(byte[] data, int... prefix) {
    if (data.length < prefix.length) {
      return false;
    }
    for (int i = 0; i < prefix.length; i++) {
      if ((data[i] & 0xFF) != prefix[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * The tags of the one record of a kind among a name's TXT records.
   *
   * @param records the records
   * @param version the version the record's first tag must name: {@code v=<version>}
   * @return the tags by lower-cased name, or null when there is not exactly one such
   *         record
   */
  private static Map<String, String> singleRecord(List<String> records, String version) {
    if (records == null) {
      return null; // NOSONAR null is "no record", which an empty map would not tell from a record without tags
    }
    Map<String, String> found = null;
    for (String record : records) {
      Map<String, String> tags = tags(record);
      String first = tags.isEmpty() ? null : tags.keySet().iterator().next();
      if ("v".equals(first) && version.equalsIgnoreCase(tags.get("v"))) {
        if (found != null) {
          return null; // NOSONAR as above: two records are no usable record
        }
        found = tags;
      }
    }
    return found;
  }

  /**
   * The {@code name=value} tags of a record, in their order, names lower-cased; a part
   * with no {@code =} is skipped, and a repeated name keeps its first value.
   *
   * @param record the record
   * @return the tags, empty for a blank record
   */
  private static Map<String, String> tags(String record) {
    Map<String, String> tags = new LinkedHashMap<>();
    if (StringUtils.isBlank(record)) {
      return tags;
    }
    for (String part : record.split(";")) {
      int equals = part.indexOf('=');
      if (equals > 0) {
        tags.putIfAbsent(part.substring(0, equals).trim().toLowerCase(Locale.ROOT), part.substring(equals + 1).trim());
      }
    }
    return tags;
  }
}
