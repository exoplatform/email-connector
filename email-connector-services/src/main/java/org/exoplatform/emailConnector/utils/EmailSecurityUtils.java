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

import java.net.IDN;
import java.net.URI;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

import org.exoplatform.emailConnector.model.EmailLink;
import org.exoplatform.emailConnector.model.EmailSecurityWarning;
import org.exoplatform.emailConnector.model.EmailSecurityWarningType;

/**
 * The checks behind the reader's phishing banner that need nothing but the message
 * itself (EXO-90841): the receiving server's authentication verdict and links whose
 * text shows another domain than they lead to. The check that needs the platform's
 * users lives in {@code EmailSecurityService}.
 */
public final class EmailSecurityUtils {

  /** The header the receiving server writes its SPF, DKIM and DMARC verdicts in (RFC 8601). */
  public static final String   HEADER_AUTHENTICATION_RESULTS = "Authentication-Results";

  /** A DMARC failure: the sender's domain disowns the message. */
  public static final String   AUTH_DMARC                    = "DMARC";

  /** An SPF hard failure with no DKIM signature to vouch for the message. */
  public static final String   AUTH_SPF                      = "SPF";

  /** A failed DKIM signature with nothing else to vouch for the message. */
  public static final String   AUTH_DKIM                     = "DKIM";

  private static final Pattern COMMENT                       = Pattern.compile("\\([^()]*\\)");

  private static final Pattern RESULT                        = Pattern.compile("^([a-z0-9-]+)\\s*=\\s*([a-z]+)");

  /** The domain a DKIM result names: {@code header.d=}, or the part after {@code @} of {@code header.i=}. */
  private static final Pattern DKIM_DOMAIN                   = Pattern.compile("\\bheader\\.(?:d=|i=[^@\\s;]*@)([a-z0-9.-]+)");

  /** The mark of a DKIM result whose signing domain is not the {@code From} one. */
  private static final String  UNALIGNED                     = "-unaligned";

  /**
   * A text that reads as a web address: an optional scheme, a host of at least two
   * labels ending in an alphabetic top-level domain, an optional port and path. No white
   * space and no {@code @}, so a sentence or a mail address never qualifies.
   */
  private static final Pattern URL_LIKE_TEXT                 =
                                             Pattern.compile("^(?:https?://)?((?:[\\p{L}\\p{N}](?:[\\p{L}\\p{N}-]*[\\p{L}\\p{N}])?\\.)+[\\p{L}]{2,63})\\.?(?::\\d{1,5})?(?:[/?#][^\\s@]*)?$",
                                                             Pattern.CASE_INSENSITIVE);

  /**
   * The second-level labels under which a country-code domain registers names
   * ({@code example.co.uk}, {@code example.com.br}), for the registrable-domain
   * approximation; no public-suffix list is on the classpath.
   */
  private static final Set<String> SECOND_LEVEL_LABELS       = Set.of("ac", "co", "com", "edu", "gob", "gouv", "gov", "ltd",
                                                                      "me", "mil", "ne", "net", "nic", "or", "org", "plc",
                                                                      "sch");

  /**
   * File extensions that are also, or look like, top-level domains: a link reading
   * "report.pdf" or "photos.zip" names a file, not a site, unless it carries a scheme or
   * a {@code www.} prefix.
   */
  private static final Set<String> FILE_EXTENSIONS           = Set.of("7z", "avi", "csv", "doc", "docx", "eml", "gif", "gz",
                                                                      "htm", "html", "ics", "jpeg", "jpg", "json", "log", "md",
                                                                      "mov", "mp3", "mp4", "msg", "odp", "ods", "odt", "pdf",
                                                                      "png", "ppt", "pptx", "rar", "rtf", "svg", "tar", "tgz",
                                                                      "tif", "tiff", "txt", "vcf", "wav", "webp", "xls",
                                                                      "xlsx", "xml", "zip");

  private EmailSecurityUtils() {
  }

  /**
   * Reads the receiving server's verdict out of a message's {@code Authentication-Results}
   * headers, and names the one failure worth a warning.
   * <p>
   * Only the first header counts: the receiving server adds its own on top, and any
   * lower one may have been written by the sender. A forged one can only hide a warning
   * where the server wrote none, which is the same as no header at all; it can never
   * raise one on someone else's mail. Comments are dropped, then each
   * {@code method=result} is read. The verdict, chosen to stay quiet on legitimate bulk
   * mail, which routinely breaks SPF through forwarders and lists but keeps a valid
   * DKIM signature:
   * <ul>
   * <li>{@code dmarc=fail}: {@link #AUTH_DMARC}, whatever else passed, since DMARC
   * already accounts for both.</li>
   * <li>Otherwise, when DMARC said nothing conclusive and no DKIM signature aligned with
   * the {@code From} domain passed: {@code spf=fail} (a hard fail, never
   * {@code softfail}) gives {@link #AUTH_SPF}, and an aligned {@code dkim=fail} with SPF
   * not passing gives {@link #AUTH_DKIM}; an unaligned signature, passing or failing, is
   * ignored, as DMARC ignores it. A signature is aligned when its {@code header.d} (or the
   * domain of its {@code header.i}) has the same registrable domain as {@code From}, as
   * DMARC's relaxed alignment reads it; one that names no domain, or a message whose
   * {@code From} is unknown, counts as aligned, so a terse server raises no warning. A
   * sender signing with their own domain cannot vouch for someone else's.</li>
   * </ul>
   *
   * @param headerValues the header's values, top first, as the message carries them
   * @param fromAddress the message's {@code From} address, or null when it has none
   * @return {@link #AUTH_DMARC}, {@link #AUTH_SPF}, {@link #AUTH_DKIM}, or null when
   *         nothing failed or nothing was said
   */
  public static String authenticationFailure(String[] headerValues, String fromAddress) {
    if (headerValues == null || headerValues.length == 0 || StringUtils.isBlank(headerValues[0])) {
      return null;
    }
    String header = headerValues[0].replaceAll("[\\r\\n]+", " ").toLowerCase(Locale.ROOT);
    String previous;
    do {
      previous = header;
      header = COMMENT.matcher(header).replaceAll(" ");
    } while (!header.equals(previous));
    List<String> dmarc = new ArrayList<>();
    List<String> spf = new ArrayList<>();
    List<String> dkim = new ArrayList<>();
    String[] parts = header.split(";");
    // The first part is the authserv-id of the server that wrote the header.
    for (int i = 1; i < parts.length; i++) {
      Matcher result = RESULT.matcher(parts[i].trim());
      if (result.find()) {
        switch (result.group(1)) {
        case "dmarc" -> dmarc.add(result.group(2));
        case "spf" -> spf.add(result.group(2));
        case "dkim" -> dkim.add(result.group(2) + (aligned(parts[i], fromAddress) ? "" : UNALIGNED));
        default -> {
          // Other methods (arc, compauth, iprev...) are not part of the verdict.
        }
        }
      }
    }
    if (dmarc.contains("fail")) {
      return AUTH_DMARC;
    }
    if (dmarc.contains("pass") || dkim.contains("pass")) {
      return null;
    }
    if (spf.contains("fail")) {
      return AUTH_SPF;
    }
    if (dkim.contains("fail") && !spf.contains("pass")) {
      return AUTH_DKIM;
    }
    return null;
  }

  /**
   * Whether a DKIM result's signing domain is aligned with the message's {@code From}:
   * same registrable domain, or either unknown.
   *
   * @param result one {@code dkim=...} part of the header, lower-cased, comments removed
   * @param fromAddress the message's {@code From} address, or null
   * @return true when aligned or undecidable
   */
  private static boolean aligned(String result, String fromAddress) {
    String fromDomain = StringUtils.isBlank(fromAddress) ? null : StringUtils.substringAfterLast(fromAddress.trim(), "@");
    Matcher domain = DKIM_DOMAIN.matcher(result);
    if (StringUtils.isBlank(fromDomain) || !domain.find()) {
      return true;
    }
    return registrableDomain(asciiHost(domain.group(1))).equals(registrableDomain(asciiHost(fromDomain)));
  }

  /**
   * The first link of a body whose visible text is a web address on another domain
   * than the one it leads to: "www.mybank.com" leading to {@code evil.example}.
   * <p>
   * Domains are compared by their registrable part, so {@code www.example.com} shown
   * for {@code login.example.com} is fine. A text that does not read as an address
   * ("Click here", a sentence, a mail address) is never judged, nor is a link that does
   * not lead to the web ({@code mailto:}, {@code tel:}).
   *
   * @param links the body's links
   * @return the warning for the first deceptive link, or null when there is none
   */
  public static EmailSecurityWarning deceptiveLink(List<EmailLink> links) {
    if (links == null) {
      return null;
    }
    for (EmailLink link : links) {
      String shownHost = hostOfText(link.text());
      String targetHost = hostOfHref(link.href());
      if (shownHost == null || targetHost == null) {
        continue;
      }
      String shown = registrableDomain(shownHost);
      String target = registrableDomain(targetHost);
      if (!shown.equals(target)) {
        return new EmailSecurityWarning(EmailSecurityWarningType.DECEPTIVE_LINK, shownHost, targetHost);
      }
    }
    return null;
  }

  /**
   * The host a link's text shows, when the whole text reads as a web address. A name
   * ending in a file extension ("report.pdf") counts only with a scheme or {@code www.}.
   *
   * @param text the link's visible text
   * @return the host, lower-cased in its ASCII form, or null when the text is not an address
   */
  static String hostOfText(String text) {
    if (StringUtils.isBlank(text)) {
      return null;
    }
    String value = text.trim();
    Matcher url = URL_LIKE_TEXT.matcher(value);
    if (!url.matches()) {
      return null;
    }
    String host = url.group(1);
    String topLevel = StringUtils.substringAfterLast(host, ".").toLowerCase(Locale.ROOT);
    boolean explicit = value.regionMatches(true, 0, "http", 0, 4) || host.regionMatches(true, 0, "www.", 0, 4);
    return !explicit && FILE_EXTENSIONS.contains(topLevel) ? null : asciiHost(host);
  }

  /**
   * The host a link leads to, for a web link.
   *
   * @param href the link's target
   * @return the host, lower-cased in its ASCII form, or null for a link that is not
   *         {@code http(s)} or cannot be read
   */
  static String hostOfHref(String href) {
    if (StringUtils.isBlank(href)) {
      return null;
    }
    try {
      URI uri = URI.create(href.trim().replace(" ", "%20"));
      String scheme = uri.getScheme();
      if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
        return null;
      }
      String host = uri.getHost();
      if (host == null && uri.getRawAuthority() != null) {
        // URI leaves the host null when the authority is not a valid server name (a
        // non-ASCII host, an underscore): take it from the authority, without user
        // information or port.
        String authority = uri.getRawAuthority();
        authority = authority.substring(authority.lastIndexOf('@') + 1);
        host = authority.replaceFirst(":\\d*$", "");
      }
      return StringUtils.isBlank(host) ? null : asciiHost(host);
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

  /**
   * A host name in the form two names of the same host compare equal in: its ASCII
   * (punycode) form, lower-cased, without a trailing dot.
   *
   * @param host the host
   * @return the normalised host, or the lower-cased input when it is not a valid name
   */
  private static String asciiHost(String host) {
    String trimmed = StringUtils.removeEnd(host.trim(), ".");
    try {
      return IDN.toASCII(trimmed, IDN.ALLOW_UNASSIGNED).toLowerCase(Locale.ROOT);
    } catch (IllegalArgumentException e) {
      return trimmed.toLowerCase(Locale.ROOT);
    }
  }

  /**
   * The part of a host name an organisation registers, approximated without a
   * public-suffix list: the last two labels, or the last three under a two-letter
   * country code whose second level is a registry category ({@code co.uk},
   * {@code com.br}). A wrong guess only ever makes two hosts of one organisation look
   * different, or two of one registry look alike, never hides a different domain under
   * a common one beyond that registry.
   *
   * @param host a normalised host name
   * @return its registrable domain
   */
  static String registrableDomain(String host) {
    String[] labels = host.split("\\.");
    if (labels.length <= 2) {
      return host;
    }
    String topLevel = labels[labels.length - 1];
    String secondLevel = labels[labels.length - 2];
    int kept = topLevel.length() == 2 && SECOND_LEVEL_LABELS.contains(secondLevel) ? 3 : 2;
    return String.join(".", Arrays.copyOfRange(labels, labels.length - kept, labels.length));
  }

  /**
   * A person's name in the form two spellings of it compare equal in: accents removed,
   * lower-cased, punctuation and quotes dropped, white space collapsed, and a
   * "Last, First" form turned into "First Last".
   *
   * @param name a display name
   * @return the normalised name, empty for a blank one
   */
  public static String normaliseName(String name) {
    if (StringUtils.isBlank(name)) {
      return "";
    }
    String value = name.trim();
    int comma = value.indexOf(',');
    if (comma > 0 && comma == value.lastIndexOf(',')) {
      value = value.substring(comma + 1) + " " + value.substring(0, comma);
    }
    value = Normalizer.normalize(value, Normalizer.Form.NFKD).replaceAll("\\p{M}+", "");
    value = value.toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", " ");
    return value.trim();
  }
}
