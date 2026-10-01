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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Locale;
import java.util.regex.Pattern;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.EmailLink;
import org.exoplatform.emailConnector.model.SanitizedEmailBody;

/**
 * The received-mail cleaning (EXO-90841), against hostile bodies: whatever the sender
 * wrote, nothing runs, and nothing on the internet is fetched until the user agreed.
 * Each check re-parses the output the way a browser would read it, so a pass means the
 * served markup holds no such thing, not merely that a string is absent.
 */
class EmailHtmlSanitizerTest {

  /** Any CSS {@code url(}, {@code src(} or image function left pointing at a network address. */
  private static final Pattern REMOTE_CSS = Pattern.compile("(?:url|src|image-set|image)\\s*\\(\\s*['\"]?\\s*(?:https?:)?//",
                                                            Pattern.CASE_INSENSITIVE);

  /**
   * Scripts, event handlers, frames, objects, forms and the head elements that act
   * (refresh, base, stylesheet links) are gone, whatever the case of their names.
   */
  @Test
  void nothingTheSenderWroteRuns() {
    String html = "<html><head><META http-equiv=\"refresh\" content=\"0;url=https://evil.example/\">"
        + "<base href=\"https://evil.example/\"><LINK rel=stylesheet href=\"https://evil.example/s.css\"></head>"
        + "<body OnLoad=\"alert(1)\"><ScRiPt>alert(1)</sCrIpT><p OnClick=\"alert(2)\" oNmOuSeOvEr=alert(3)>Hello</p>"
        + "<IFRAME SRC=\"https://evil.example/\"></IFRAME><object data=\"https://evil.example/x.swf\"></object>"
        + "<embed src=\"https://evil.example/x\"><form action=\"https://evil.example/\"><input name=p type=password>"
        + "<button formaction=\"https://evil.example/\">Go</button></form><noscript><p>ns</p></noscript>"
        + "<template><img src=x onerror=alert(4)></template><details open ontoggle=alert(5)><summary>s</summary></details>"
        + "</body></html>";
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize(html, false);
    Document served = Jsoup.parse(result.html());
    assertTrue(served.select("script, iframe, object, embed, form, input, button, meta, base, link, template").isEmpty(),
               result.html());
    for (Element element : served.getAllElements()) {
      element.attributes().forEach(attribute -> assertFalse(attribute.getKey().toLowerCase(Locale.ROOT).startsWith("on"),
                                                             "event handler kept: " + result.html()));
    }
    assertTrue(served.text().contains("Hello"));
    assertFalse(result.html().contains("evil.example"), result.html());
  }

  /**
   * A link keeps a web, mail or phone target only: {@code javascript:}, hidden behind
   * entities, mixed case, a tab or a newline, and {@code data:} and {@code vbscript:}
   * lose their target. Every kept link opens in a new context with no handle on the reader.
   */
  @Test
  void linksKeepOnlyWebMailAndPhoneTargets() {
    String html = "<a href=\"JaVaScRiPt:alert(1)\">a</a>"
        + "<a href=\"jav&#x61;script&colon;alert(1)\">b</a>"
        + "<a href=\"java&#9;script:alert(1)\">c</a>"
        + "<a href=\"java\nscript:alert(1)\">d</a>"
        + "<a href=\"data:text/html;base64,PHNjcmlwdD5hbGVydCgxKTwvc2NyaXB0Pg==\">e</a>"
        + "<a href=\"vbscript:msgbox(1)\">f</a>"
        + "<a href=\"/portal/rest/logout\">g</a>"
        + "<a href=\"HTTPS://Good.Example/path\" target=\"_self\">h</a>"
        + "<a href=\"mailto:someone@example.com\">i</a>";
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize(html, false);
    Document served = Jsoup.parse(result.html());
    assertEquals(2, served.select("a[href]").size(), result.html());
    Element web = served.select("a[href]").first();
    assertTrue(web.attr("href").toLowerCase(Locale.ROOT).startsWith("https://good.example"), result.html());
    assertEquals("_blank", web.attr("target"));
    assertEquals(EmailHtmlSanitizer.LINK_REL, web.attr("rel"));
    assertEquals(2, result.links().size());
    assertEquals(new EmailLink("h", web.attr("href")), result.links().get(0));
  }

  /**
   * Images from the internet are held back until the user agrees, in any spelling of
   * the scheme, and the result says so; {@code srcset} is dropped either way; inline
   * images ({@code data:} once inlined, an unresolved {@code cid:}) always show; a
   * relative address, which would resolve against the portal, never does.
   */
  @Test
  void remoteImagesWaitForConsentAndInlineOnesShow() {
    String html = "<IMG SRC=\"HTTP://tracker.example/p.gif\" width=1 height=1>"
        + "<img src=\" https://tracker.example/q.png\">"
        + "<img src=\"//tracker.example/r.png\">"
        + "<img src=\"data:image/png;base64,iVBORw0KGgo=\" SrcSet=\"https://tracker.example/s.png 2x\">"
        + "<img src=\"cid:part1@example\">"
        + "<img src=\"/portal/rest/v1/social/users/me\">"
        + "<img src=\"data:text/html;base64,PHNjcmlwdD4=\">";
    SanitizedEmailBody blocked = EmailHtmlSanitizer.sanitize(html, false);
    assertTrue(blocked.remoteContentBlocked());
    assertFalse(blocked.html().contains("tracker.example"), blocked.html());
    assertFalse(blocked.html().contains("/portal/"), blocked.html());
    assertFalse(blocked.html().toLowerCase(Locale.ROOT).contains("srcset"), blocked.html());
    assertTrue(blocked.html().contains("src=\"data:image/png;base64,iVBORw0KGgo=\""), blocked.html());
    assertTrue(blocked.html().contains("src=\"cid:part1@example\""), blocked.html());
    assertFalse(blocked.html().contains("data:text/html"), blocked.html());

    SanitizedEmailBody shown = EmailHtmlSanitizer.sanitize(html, true);
    assertFalse(shown.remoteContentBlocked());
    assertTrue(shown.html().toLowerCase(Locale.ROOT).contains("http://tracker.example/p.gif"), shown.html());
    assertTrue(shown.html().contains("https://tracker.example/q.png"), shown.html());
    assertFalse(shown.html().toLowerCase(Locale.ROOT).contains("srcset"), shown.html());
  }

  /**
   * A body that fetches nothing is not reported as blocked, so the reader offers no
   * "Show images" for it.
   */
  @Test
  void aBodyWithNothingRemoteIsNotBlocked() {
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize("<p style=\"color:red\">Hi <img src=\"data:image/gif;base64,R0lGOD==\"></p>",
                                                             false);
    assertFalse(result.remoteContentBlocked());
    assertTrue(result.html().contains("color:red"), result.html());
  }

  /**
   * The {@code background} attribute of tables and cells is remote content too.
   */
  @Test
  void backgroundAttributesWaitForConsent() {
    String html = "<TABLE BACKGROUND=\"https://tracker.example/t.png\"><tr background=\"http://tracker.example/r.png\">"
        + "<td Background=\"https://tracker.example/c.png\">x</td></tr></TABLE>";
    SanitizedEmailBody blocked = EmailHtmlSanitizer.sanitize(html, false);
    assertTrue(blocked.remoteContentBlocked());
    assertFalse(blocked.html().contains("tracker.example"), blocked.html());
    SanitizedEmailBody shown = EmailHtmlSanitizer.sanitize(html, true);
    assertTrue(shown.html().contains("background=\"https://tracker.example/c.png\""), shown.html());
  }

  /**
   * CSS cannot fetch anything either, however the address is spelt: escapes in the
   * function name or the address, an entity in the attribute, a protocol-relative
   * address, quotes or none, {@code src()}, the string-taking image functions,
   * {@code @import} and {@code @font-face}.
   */
  @Test
  void cssFetchesNothingBeforeConsent() {
    String[] declarations = { "background:url(http://tracker.example/a.png)",
        "background:URL( 'https://tracker.example/b.png' )",
        "background:u\\72l(http://tracker.example/c.png)",
        "background:\\75 rl(\"http://tracker.example/d.png\")",
        "background:ur\\l(http://tracker.example/e.png)",
        "background:url(\\68 ttp://tracker.example/f.png)",
        "background:url(&#104;ttp://tracker.example/g.png)",
        "background:url(//tracker.example/h.png)",
        "background-image:image-set(\"https://tracker.example/i.png\" 1x)",
        "background-image:-webkit-image-set('https://tracker.example/j.png' 1x)",
        "list-style-image:src(\"https://tracker.example/k.png\")",
        "background:url(\"https://tracker.example/l.png" };
    for (String declaration : declarations) {
      SanitizedEmailBody result = EmailHtmlSanitizer.sanitize("<div style=\"" + declaration.replace("\"", "&quot;") + "\">x</div>",
                                                               false);
      String style = Jsoup.parse(result.html()).select("div").attr("style");
      assertFalse(REMOTE_CSS.matcher(EmailHtmlSanitizer.decodeCssEscapes(style)).find(), declaration + " -> " + style);
      // Recognised as an image once decoded, so the reader offers to load it -- all but
      // the string-taking functions and the malformed call, which are never kept.
      boolean neverKept = declaration.contains("image-set") || declaration.endsWith(".png");
      assertEquals(!neverKept, result.remoteContentBlocked(), declaration);
    }
    String sheet = "<html><head><style>@import url(https://tracker.example/s.css); @IMPORT 'https://tracker.example/t.css';"
        + "@font-face{font-family:x;src:url(https://tracker.example/f.woff)}"
        + "body{background:url(\"https://tracker.example/b.png\")} p{color:red}</style></head><body><p>x</p></body></html>";
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize(sheet, false);
    assertTrue(result.remoteContentBlocked());
    assertFalse(result.html().contains("tracker.example"), result.html());
    assertFalse(result.html().toLowerCase(Locale.ROOT).contains("@import"), result.html());
    assertTrue(result.html().contains("p{color:red}"), result.html());
  }

  /**
   * Once the user agreed, a web address in CSS is kept, quoted afresh; an address that
   * would resolve against the portal still is not, and {@code @import} never is.
   */
  @Test
  void cssKeepsWebAddressesOnceAllowed() {
    String html = "<style>@import url(https://cdn.example/s.css); td{background:url(https://cdn.example/b.png)}"
        + " p{background:url(/portal/rest/x)} h1{background:url(//cdn.example/h.png)}</style><p>x</p>";
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize(html, true);
    assertFalse(result.remoteContentBlocked());
    assertTrue(result.html().contains("url(\"https://cdn.example/b.png\")"), result.html());
    assertTrue(result.html().contains("url(\"https://cdn.example/h.png\")"), result.html());
    assertFalse(result.html().contains("/portal/"), result.html());
    assertFalse(result.html().contains("s.css"), result.html());
  }

  /**
   * An escaped spelling of an address the user agreed to load is the address: it is
   * decoded, kept, and quoted afresh, so the image shows once asked for.
   */
  @Test
  void anEscapedAddressLoadsOnceAllowed() {
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize("<div style=\"background:u\\72l(\\68 ttps://cdn.example/a.png)\">x</div>",
                                                             true);
    assertTrue(result.html().contains("url(&quot;https://cdn.example/a.png&quot;)"), result.html());
  }

  /**
   * Decoding cannot be turned against the check: a decoded backslash is re-escaped, so
   * {@code \5c 72} does not become a second escape the browser decodes into
   * {@code url(}; a decoded {@code <} is re-escaped, so a stylesheet cannot close its
   * own element and open a script.
   */
  @Test
  void decodedCssCannotEscapeTheCheckOrTheElement() {
    String html = "<style>p{background:u\\5c 72l(http://tracker.example/a.png)}"
        + " q{content:\"\\3c /style>\\3c script>alert(1)\\3c /script>\"}</style><p>x</p>";
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize(html, false);
    Document served = Jsoup.parse(result.html());
    assertTrue(served.select("script").isEmpty(), result.html());
    assertEquals(1, served.select("style").size(), result.html());
    String css = served.select("style").first().data();
    assertFalse(REMOTE_CSS.matcher(EmailHtmlSanitizer.decodeCssEscapes(css)).find(), css);
    assertFalse(css.contains("<"), css);
  }

  /**
   * IE's {@code expression()}, behaviours and XBL bindings are renamed out of existence.
   */
  @Test
  void cssCannotBindCode() {
    String html = "<p style=\"width:expression(alert(1));behavior:url(#default#x);-moz-binding:url(https://evil.example/x.xml#b)\">x</p>";
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize(html, true);
    String style = Jsoup.parse(result.html()).select("p").attr("style").toLowerCase(Locale.ROOT);
    assertFalse(style.contains("expression("), style);
    assertFalse(style.contains("behavior:"), style);
    assertFalse(style.contains("-moz-binding:"), style);
  }

  /**
   * SVG and MathML go with everything inside them: an {@code <image>}, a {@code <use>},
   * a script, an event handler.
   */
  @Test
  void svgAndMathMlAreDropped() {
    String html = "<svg onload=\"alert(1)\"><image href=\"https://tracker.example/i.png\"/>"
        + "<use xlink:href=\"https://tracker.example/u.svg#x\"/><script>alert(2)</script>"
        + "<foreignObject><img src=\"https://tracker.example/f.png\"></foreignObject></svg>"
        + "<math><mtext><table><mglyph><style><img src=x onerror=alert(3)></style></mglyph></table></mtext></math><p>after</p>";
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize(html, false);
    Document served = Jsoup.parse(result.html());
    assertTrue(served.select("svg, math, image, use, script, foreignObject").isEmpty(), result.html());
    assertFalse(result.html().contains("tracker.example"), result.html());
    // The math branch's <style> is kept as a stylesheet, its "<img" re-escaped: text, not an element.
    assertTrue(served.select("[onerror]").isEmpty(), result.html());
    assertTrue(served.text().contains("after"));
  }

  /**
   * What makes a mail look as written survives: the stylesheet of its head, a linked
   * table, and a linked image with more than 75 characters of text in the same link,
   * which the platform's shared purifier flattens. The body's own colours move to a
   * wrapper.
   */
  @Test
  void mailLayoutSurvives() {
    String longText = "Read the full announcement of our quarterly results and the roadmap for next year now";
    String html = "<html><head><style>.hero{color:#123456}</style></head><body bgcolor=\"#eeeeee\" style=\"margin:0\">"
        + "<a href=\"https://news.example/a\"><table class=\"hero\"><tr><td>Cell</td></tr></table></a>"
        + "<a href=\"https://news.example/b\"><img src=\"data:image/png;base64,iVBORw0KGgo=\" alt=\"Banner\">" + longText + "</a>"
        + "<div class=\"gmail_quote\"><blockquote type=\"cite\">old</blockquote></div><div id=\"divRplyFwdMsg\">x</div>"
        + "</body></html>";
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize(html, false);
    Document served = Jsoup.parse(result.html());
    assertTrue(served.select("style").first().data().contains(".hero{color:#123456}"), result.html());
    assertEquals(1, served.select("a > table.hero td").size(), result.html());
    Element linkedImage = served.select("a[href=https://news.example/b]").first();
    assertEquals(1, linkedImage.select("img[alt=Banner]").size(), result.html());
    assertTrue(linkedImage.text().contains(longText), result.html());
    assertEquals(1, served.select(".gmail_quote > blockquote[type=cite]").size(), result.html());
    assertEquals(1, served.select("#divRplyFwdMsg").size(), result.html());
    String wrapper = served.body().child(0).attr("style");
    assertTrue(wrapper.contains("background-color:#eeeeee") && wrapper.contains("margin:0"), result.html());
  }

  /**
   * The line structure of typed text in a wrapper, which the reader keeps from the raw
   * newlines, is not reformatted.
   */
  @Test
  void whiteSpaceIsKept() {
    String html = "<div>First line\n  indented line\nlast line</div>";
    assertEquals(html, EmailHtmlSanitizer.sanitize(html, false).html());
  }

  /**
   * A blank body is an empty one, with nothing blocked.
   */
  @Test
  void aBlankBodyIsEmpty() {
    SanitizedEmailBody result = EmailHtmlSanitizer.sanitize("  ", false);
    assertEquals("", result.html());
    assertFalse(result.remoteContentBlocked());
    assertTrue(result.links().isEmpty());
  }
}
