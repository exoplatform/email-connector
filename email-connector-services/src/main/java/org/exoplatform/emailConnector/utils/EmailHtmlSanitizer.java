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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.DataNode;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Entities;
import org.jsoup.safety.Cleaner;
import org.jsoup.safety.Safelist;

import org.exoplatform.emailConnector.model.EmailLink;
import org.exoplatform.emailConnector.model.SanitizedEmailBody;

/**
 * Makes the HTML body of a received message safe to display in the reader (EXO-90841).
 * <p>
 * The body comes from whoever sent the mail. It is cleaned on the server, before it
 * reaches the browser, so that nothing the sender wrote runs and, unless the user
 * agreed, nothing the sender pointed at is fetched:
 * <ul>
 * <li>Elements and attributes are kept from an allow-list (jsoup's {@link Cleaner}):
 * scripts, frames, objects, forms, SVG and MathML, event handlers, {@code srcset} and
 * every attribute that is not formatting are dropped. Link targets keep only
 * {@code http}, {@code https}, {@code mailto} and {@code tel}; images only
 * {@code http}, {@code https}, {@code data} images and unresolved {@code cid}.</li>
 * <li>CSS, in {@code <style>} elements and {@code style} attributes, is rewritten by
 * {@link #sanitizeCss}: comments go, escapes are decoded so that what is checked is what
 * the browser will read, {@code @import} and the string-taking image functions go, and
 * every {@code url()} is kept only when it points at no network resource, or at an
 * {@code http(s)} one the user agreed to load.</li>
 * <li>When remote content is blocked, {@code <img src>} and {@code background} pointing
 * at the internet are removed, and the result says so, so the reader can offer to
 * load them.</li>
 * </ul>
 * Inline images reach this as {@code data:} URLs ({@link EmailConnectorUtils} inlines
 * {@code cid:} parts at sync), so they always show.
 * <p>
 * The {@code <style>} elements of the message's {@code <head>} are kept, moved into the
 * body: mail clients rely on them, and the reader's frame places the whole result in
 * its own {@code <body>}. The cleaning drops the {@code <body>} element itself, so the
 * colours, background and style it asks for are carried by a wrapping div.
 */
public final class EmailHtmlSanitizer {

  /** The link relation forced on every link, so the target page gets no handle on the reader. */
  static final String          LINK_REL               = "noopener noreferrer nofollow";

  /** What a removed {@code url()} becomes: a valid keyword where an image is expected, and an invalid value elsewhere. */
  private static final String  BLOCKED_URL            = "none";

  /** What a function or property that must not run becomes: an unknown name, which the browser ignores. */
  private static final String  NEUTRALISED            = "x-blocked";

  private static final Pattern CSS_COMMENT            = Pattern.compile("/\\*.*?(\\*/|\\z)", Pattern.DOTALL);

  private static final Pattern CSS_ESCAPE             = Pattern.compile("\\\\(?:([0-9a-fA-F]{1,6})[ \\t\\n\\f\\r]?|(\\r\\n|[\\n\\f\\r])|(.))",
                                                                        Pattern.DOTALL);

  private static final Pattern CSS_IMPORT             = Pattern.compile("@import[^;]*;?", Pattern.CASE_INSENSITIVE);

  private static final Pattern CSS_URL                = Pattern.compile("(?:url|src)\\s*\\(\\s*(?:\"([^\"]*)\"|'([^']*)'|([^)\"'\\s]*))\\s*\\)",
                                                                        Pattern.CASE_INSENSITIVE);

  /** A {@code url(} or {@code src(} left over once the well-formed ones were handled: malformed, so never kept. */
  private static final Pattern CSS_URL_LEFTOVER       = Pattern.compile("(?:url|src)\\s*\\(", Pattern.CASE_INSENSITIVE);

  /** The functions that take an image address as a bare string, or that run code (IE's expression). */
  private static final Pattern CSS_STRING_IMAGE_FN    =
                                                   Pattern.compile("(?<![\\w-])(?:-webkit-|-moz-)?(?:image-set|cross-fade|image|element|expression)\\s*\\(",
                                                                   Pattern.CASE_INSENSITIVE);

  /** The properties that bind behaviour to an element (IE behaviours, Mozilla XBL). */
  private static final Pattern CSS_BINDING_PROPERTY   = Pattern.compile("(?:-moz-binding|behavior)\\s*:", Pattern.CASE_INSENSITIVE);

  private static final Pattern CSS_COLOR_VALUE        = Pattern.compile("#?[0-9a-zA-Z]{1,20}");

  private static final Safelist SAFELIST              = buildSafelist();

  private EmailHtmlSanitizer() {
  }

  /**
   * Cleans a received HTML body for the reader.
   *
   * @param html the body as the message carries it
   * @param allowRemote whether resources on the internet may be fetched: true once the
   *          user agreed (for this message, for its sender, or for all mail)
   * @return the cleaned body, whether remote resources were taken out, and its links;
   *         an empty body for a blank input
   */
  public static SanitizedEmailBody sanitize(String html, boolean allowRemote) {
    if (StringUtils.isBlank(html)) {
      return new SanitizedEmailBody("", false, List.of());
    }
    Document dirty = Jsoup.parse(html);
    // The <head> is not cleaned (the Cleaner reads the body only), so its stylesheets
    // are moved into the body first, where they are cleaned like any other.
    List<Element> headStyles = new ArrayList<>(dirty.head().select("style"));
    for (int i = headStyles.size() - 1; i >= 0; i--) {
      dirty.body().prependChild(headStyles.get(i));
    }
    // A protocol-relative image is an http(s) one: given its scheme, it is held back or
    // shown like the others, where the allow-list would drop it for good.
    for (Element image : dirty.body().select("img[src]")) {
      if (compact(image.attr("src")).startsWith("//")) {
        image.attr("src", "https:" + compact(image.attr("src")));
      }
    }
    String bodyStyle = bodyStyle(dirty.body());
    Document clean = new Cleaner(SAFELIST).clean(dirty);
    clean.outputSettings().prettyPrint(false).escapeMode(Entities.EscapeMode.base).charset("UTF-8");
    Element body = clean.body();
    boolean[] blocked = { false };
    for (Element style : body.select("style")) {
      String css = sanitizeCss(style.data(), allowRemote, blocked);
      if (StringUtils.isBlank(css)) {
        style.remove();
      } else {
        style.empty();
        style.appendChild(new DataNode(css));
      }
    }
    for (Element element : body.select("[style]")) {
      String css = sanitizeCss(element.attr("style"), allowRemote, blocked);
      if (StringUtils.isBlank(css)) {
        element.removeAttr("style");
      } else {
        element.attr("style", css);
      }
    }
    for (Element image : body.select("img[src]")) {
      String source = compact(image.attr("src")).toLowerCase(Locale.ROOT);
      if (source.startsWith("data:") && !source.startsWith("data:image/")) {
        image.removeAttr("src");
      } else if (isRemote(source) && !allowRemote) {
        image.removeAttr("src");
        blocked[0] = true;
      }
    }
    for (Element element : body.select("[background]")) {
      if (!allowRemote) {
        element.removeAttr("background");
        blocked[0] = true;
      }
    }
    List<EmailLink> links = new ArrayList<>();
    for (Element link : body.select("a[href]")) {
      links.add(new EmailLink(link.text().trim(), link.attr("href")));
    }
    String sanitizedBodyStyle = bodyStyle == null ? null : sanitizeCss(bodyStyle, allowRemote, blocked);
    String result = body.html();
    if (StringUtils.isNotBlank(sanitizedBodyStyle)) {
      // Built around the serialised body rather than by re-parsing it into the wrapper:
      // what was cleaned is exactly what is served.
      String wrapper = clean.createElement("div").attr("style", sanitizedBodyStyle).outerHtml();
      result = wrapper.substring(0, wrapper.length() - "</div>".length()) + result + "</div>";
    }
    return new SanitizedEmailBody(result, blocked[0], links);
  }

  /**
   * Rewrites a piece of CSS so that it runs nothing and fetches nothing the user did not
   * agree to.
   * <p>
   * The text is first brought to what the browser will read: comments removed and
   * escapes decoded, so that {@code u\72l(} or {@code ur\l(} cannot hide a {@code url(}.
   * Every check then runs on that decoded text, which is also what is emitted, with its
   * backslashes and {@code <} re-escaped: a decoded backslash cannot start a second
   * escape the check never saw, and a decoded closing style tag cannot close the element.
   * Then:
   * <ul>
   * <li>{@code @import} is removed: a stylesheet is never needed to read a mail.</li>
   * <li>{@code url()} and {@code src()} are kept for a fragment ({@code #id}), a
   * {@code data:} or {@code cid:} address, which fetch nothing; for an {@code http(s)}
   * address only when {@code allowRemote}, else replaced by {@code none} and recorded
   * as blocked; every other address (relative ones would resolve against the portal) is
   * replaced by {@code none}.</li>
   * <li>The functions that take an image address as a plain string ({@code image-set},
   * {@code image}, {@code cross-fade}, {@code element}), IE's {@code expression} and the
   * behaviour-binding properties are renamed to an unknown name, which the browser
   * ignores.</li>
   * </ul>
   * Matching is deliberately broader than the grammar (a {@code url(} inside a string is
   * rewritten too): rewriting something inert costs nothing, missing something live
   * costs a request.
   *
   * @param css the CSS text, a stylesheet or a declaration list
   * @param allowRemote whether {@code http(s)} addresses may be kept
   * @param blocked set to true when an {@code http(s)} address was removed
   * @return the rewritten CSS, possibly empty
   */
  static String sanitizeCss(String css, boolean allowRemote, boolean[] blocked) {
    if (StringUtils.isBlank(css)) {
      return "";
    }
    String decoded = decodeCssEscapes(CSS_COMMENT.matcher(css).replaceAll(" ")).replace('\0', '�');
    decoded = CSS_IMPORT.matcher(decoded).replaceAll("");
    decoded = CSS_STRING_IMAGE_FN.matcher(decoded).replaceAll(NEUTRALISED + "(");
    decoded = CSS_BINDING_PROPERTY.matcher(decoded).replaceAll(NEUTRALISED + ":");
    StringBuilder out = new StringBuilder(decoded.length());
    Matcher url = CSS_URL.matcher(decoded);
    int last = 0;
    while (url.find()) {
      out.append(escapeCssText(neutraliseLeftoverUrls(decoded.substring(last, url.start()))));
      String target = url.group(1) != null ? url.group(1) : url.group(2) != null ? url.group(2) : url.group(3);
      out.append(rewriteUrl(target, allowRemote, blocked));
      last = url.end();
    }
    out.append(escapeCssText(neutraliseLeftoverUrls(decoded.substring(last))));
    return out.toString().trim();
  }

  /**
   * What one {@code url()} of the CSS becomes.
   *
   * @param target the address it named, unquoted
   * @param allowRemote whether {@code http(s)} addresses may be kept
   * @param blocked set to true when an {@code http(s)} address is removed
   * @return {@code url("...")} when kept, {@code none} otherwise
   */
  private static String rewriteUrl(String target, boolean allowRemote, boolean[] blocked) {
    String address = compact(target);
    String lower = address.toLowerCase(Locale.ROOT);
    boolean inert = address.startsWith("#") || lower.startsWith("data:") || lower.startsWith("cid:");
    if (isRemote(lower)) {
      if (!allowRemote) {
        blocked[0] = true;
        return BLOCKED_URL;
      }
      inert = true;
      if (address.startsWith("//")) {
        address = "https:" + address;
      }
    }
    return inert && !address.isEmpty() ? "url(\"" + escapeCssString(address) + "\")" : BLOCKED_URL;
  }

  /**
   * Decodes the CSS escapes of a text: {@code \hex} with its optional trailing white
   * space, an escaped newline (dropped, as in a string) and any other escaped
   * character. A code point of zero, a surrogate or one past Unicode becomes U+FFFD, as
   * in the browser.
   *
   * @param css the CSS text, without comments
   * @return the text with every escape replaced by the character it stands for
   */
  static String decodeCssEscapes(String css) {
    Matcher escape = CSS_ESCAPE.matcher(css);
    StringBuilder out = new StringBuilder(css.length());
    while (escape.find()) {
      String replacement;
      if (escape.group(1) != null) {
        int codePoint = Integer.parseInt(escape.group(1), 16);
        boolean valid = codePoint != 0 && codePoint <= Character.MAX_CODE_POINT
            && (codePoint < Character.MIN_SURROGATE || codePoint > Character.MAX_SURROGATE);
        replacement = new String(Character.toChars(valid ? codePoint : 0xFFFD));
      } else if (escape.group(2) != null) {
        replacement = "";
      } else {
        replacement = escape.group(3);
      }
      escape.appendReplacement(out, Matcher.quoteReplacement(replacement));
    }
    escape.appendTail(out);
    return out.toString();
  }

  /**
   * Renames a {@code url(} or {@code src(} that is not a well-formed call (an
   * unterminated string, a stray quote), which the browser could still read as one.
   *
   * @param css a stretch of decoded CSS with no well-formed {@code url()} in it
   * @return the stretch with every such opening renamed
   */
  private static String neutraliseLeftoverUrls(String css) {
    return CSS_URL_LEFTOVER.matcher(css).replaceAll(NEUTRALISED + "(");
  }

  /**
   * Re-escapes the two characters of decoded CSS that would change its meaning once
   * emitted: a backslash, which would start a new escape, and {@code <}, which could
   * close the {@code <style>} element it sits in.
   *
   * @param css decoded CSS
   * @return the text safe to emit
   */
  private static String escapeCssText(String css) {
    return css.replace("\\", "\\5c ").replace("<", "\\3c ");
  }

  /**
   * Escapes an address for a double-quoted CSS string.
   *
   * @param value the address
   * @return the escaped address
   */
  private static String escapeCssString(String value) {
    return escapeCssText(value).replace("\"", "\\22 ");
  }

  /**
   * Strips the white space and control characters a browser ignores in an address, so
   * that {@code java&#9;script:} or {@code  h ttp://} is judged as the browser reads it.
   *
   * @param value an address
   * @return the address without them
   */
  private static String compact(String value) {
    if (value == null) {
      return "";
    }
    StringBuilder out = new StringBuilder(value.length());
    value.codePoints().filter(c -> c > 0x20 && c != 0x7F).forEach(out::appendCodePoint);
    return out.toString();
  }

  /**
   * Whether an address, compacted and lower-cased, is fetched from the internet.
   *
   * @param address the address
   * @return true for {@code http:}, {@code https:} and protocol-relative addresses
   */
  private static boolean isRemote(String address) {
    return address.startsWith("http:") || address.startsWith("https:") || address.startsWith("//");
  }

  /**
   * The presentation the message's {@code <body>} element asks for, as CSS: its
   * {@code bgcolor}, {@code text} and {@code background}, then its {@code style}. The
   * cleaning drops the element, so they are carried by a wrapper; the background image
   * goes through the same {@code url()} policy as any other CSS.
   *
   * @param body the parsed message's body element
   * @return the CSS declarations, or null when it asks for none
   */
  private static String bodyStyle(Element body) {
    StringBuilder style = new StringBuilder();
    if (body.hasAttr("bgcolor") && CSS_COLOR_VALUE.matcher(body.attr("bgcolor").trim()).matches()) {
      style.append("background-color:").append(body.attr("bgcolor").trim()).append(';');
    }
    if (body.hasAttr("text") && CSS_COLOR_VALUE.matcher(body.attr("text").trim()).matches()) {
      style.append("color:").append(body.attr("text").trim()).append(';');
    }
    String background = body.attr("background").trim();
    if (!background.isEmpty() && background.indexOf('"') < 0 && background.indexOf('\\') < 0) {
      style.append("background-image:url(\"").append(background).append("\");");
    }
    if (StringUtils.isNotBlank(body.attr("style"))) {
      style.append(body.attr("style"));
    }
    return style.isEmpty() ? null : style.toString();
  }

  /**
   * The elements and attributes a mail may keep. Formatting only: no element that runs
   * code or embeds another document, no form control, no event handler, no
   * {@code srcset}, no {@code name}. Every link opens outside the reader, in a new
   * context that gets no reference to it.
   *
   * @return the allow-list
   */
  private static Safelist buildSafelist() {
    return new Safelist().addTags("a", "abbr", "address", "article", "aside", "b", "bdi", "bdo", "big", "blockquote", "br",
                                  "caption", "center", "cite", "code", "col", "colgroup", "dd", "del", "details", "dfn",
                                  "div", "dl", "dt", "em", "figcaption", "figure", "font", "footer", "h1", "h2", "h3", "h4",
                                  "h5", "h6", "header", "hr", "i", "img", "ins", "kbd", "label", "li", "main", "mark", "nav",
                                  "ol", "p", "pre", "q", "rp", "rt", "ruby", "s", "samp", "section", "small", "span",
                                  "strike", "strong", "style", "sub", "summary", "sup", "table", "tbody", "td", "tfoot",
                                  "th", "thead", "time", "tr", "tt", "u", "ul", "var", "wbr")
                         .addAttributes(":all", "align", "bgcolor", "border", "class", "color", "dir", "height", "id",
                                        "lang", "style", "title", "valign", "width")
                         .addAttributes("a", "href")
                         .addAttributes("img", "src", "alt", "hspace", "vspace")
                         .addAttributes("table", "cellpadding", "cellspacing", "summary", "frame", "rules", "background")
                         .addAttributes("td", "colspan", "rowspan", "nowrap", "abbr", "scope", "headers", "background")
                         .addAttributes("th", "colspan", "rowspan", "nowrap", "abbr", "scope", "headers", "background")
                         .addAttributes("tr", "background")
                         .addAttributes("col", "span")
                         .addAttributes("colgroup", "span")
                         .addAttributes("ol", "start", "type")
                         .addAttributes("ul", "type")
                         .addAttributes("li", "type", "value")
                         .addAttributes("font", "face", "size")
                         .addAttributes("hr", "size", "noshade")
                         .addAttributes("blockquote", "type")
                         .addAttributes("details", "open")
                         .addAttributes("time", "datetime")
                         .addProtocols("a", "href", "http", "https", "mailto", "tel")
                         .addProtocols("img", "src", "http", "https", "data", "cid")
                         .addProtocols("table", "background", "http", "https")
                         .addProtocols("td", "background", "http", "https")
                         .addProtocols("th", "background", "http", "https")
                         .addProtocols("tr", "background", "http", "https")
                         .addEnforcedAttribute("a", "target", "_blank")
                         .addEnforcedAttribute("a", "rel", LINK_REL);
  }
}
