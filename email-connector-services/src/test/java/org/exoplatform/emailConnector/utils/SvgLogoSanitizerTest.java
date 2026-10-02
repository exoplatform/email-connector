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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * The SVG logo cleaning (EXO-90893): what draws stays, what runs, links out or reads
 * anything external goes, and a document that cannot be made safe is refused whole.
 */
class SvgLogoSanitizerTest {

  private static final String OPEN  = "<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\""
      + " version=\"1.2\" baseProfile=\"tiny-ps\" viewBox=\"0 0 100 100\">";

  private static final String CLOSE = "</svg>";

  /**
   * A BIMI-style logo keeps its drawing: title, shapes, gradient, the local reference
   * to the gradient and the local use.
   */
  @Test
  void theDrawingIsKept() {
    String clean = sanitize(OPEN + "<title>Brand</title><defs><linearGradient id=\"g\"><stop offset=\"0\" stop-color=\"#f00\"/>"
        + "</linearGradient><path id=\"p\" d=\"M0 0h10v10z\"/></defs><rect width=\"100\" height=\"100\" fill=\"url(#g)\"/>"
        + "<use xlink:href=\"#p\"/><circle cx=\"50\" cy=\"50\" r=\"20\" style=\"fill:#fff\"/>" + CLOSE);
    assertTrue(clean.contains("<title>Brand</title>"), clean);
    assertTrue(clean.contains("fill=\"url(#g)\""), clean);
    assertTrue(clean.contains("href=\"#p\""), clean);
    assertTrue(clean.contains("d=\"M0 0h10v10z\""), clean);
    assertTrue(clean.contains("style=\"fill:#fff\""), clean);
    assertTrue(clean.contains("viewBox=\"0 0 100 100\""), clean);
  }

  /**
   * Scripts, event handlers, foreign objects, embedded images, links and animations go,
   * with their whole subtree.
   */
  @Test
  void whatRunsOrLinksOutGoes() {
    String clean = sanitize(OPEN.replace(">", " onload=\"alert(1)\">") + "<script>alert(2)</script>"
        + "<foreignObject><div xmlns=\"http://www.w3.org/1999/xhtml\">x<script>alert(3)</script></div></foreignObject>"
        + "<image href=\"https://tracker.example/p.png\"/><a href=\"https://evil.example\"><rect width=\"1\" height=\"1\"/></a>"
        + "<animate attributeName=\"href\" to=\"javascript:alert(4)\"/><set attributeName=\"onclick\" to=\"alert(5)\"/>"
        + "<rect width=\"10\" height=\"10\" onclick=\"alert(6)\" onmouseover=\"alert(7)\"/>" + CLOSE);
    for (String gone : new String[] { "alert", "script", "foreignObject", "image", "tracker.example", "evil.example", "animate",
        "<set", "onload", "onclick", "<a" }) {
      assertFalse(clean.contains(gone), gone + " in " + clean);
    }
    assertTrue(clean.contains("<rect height=\"10\" width=\"10\"/>") || clean.contains("<rect width=\"10\" height=\"10\"/>"), clean);
  }

  /**
   * Every reference that leaves the document goes: an external href, a url() to a
   * server in an attribute or a style, an @import, an escape that could spell one, a
   * script scheme.
   */
  @Test
  void everyExternalReferenceGoes() {
    String clean = sanitize(OPEN + "<use href=\"https://evil.example/sprite.svg#a\"/><use xlink:href=\"data:image/svg+xml,x\"/>"
        + "<rect width=\"1\" height=\"1\" fill=\"url(https://evil.example/f)\"/>"
        + "<rect width=\"2\" height=\"2\" style=\"fill:url('https://evil.example/s')\"/>"
        + "<rect width=\"3\" height=\"3\" style=\"fill:u\\72l(//evil.example)\"/>"
        + "<rect width=\"4\" height=\"4\" fill=\"javascript:alert(1)\"/>"
        + "<style>@import url(https://evil.example/a.css); .a{fill:red}</style><style>.b{fill:blue}</style>" + CLOSE);
    assertFalse(clean.contains("evil.example"), clean);
    assertFalse(clean.contains("data:"), clean);
    assertFalse(clean.contains("javascript"), clean);
    assertFalse(clean.contains("\\72"), clean);
    assertFalse(clean.contains("@import"), clean);
    assertTrue(clean.contains(".b{fill:blue}"), clean);
  }

  /**
   * Comments, processing instructions and attributes of other namespaces go.
   */
  @Test
  void commentsAndForeignAttributesGo() {
    String clean = sanitize(OPEN.replace(">", " xmlns:ev=\"http://www.w3.org/2001/xml-events\" ev:event=\"click\">")
        + "<!-- secret --><?php echo 1 ?><rect width=\"1\" height=\"1\"/>" + CLOSE);
    assertFalse(clean.contains("secret"), clean);
    assertFalse(clean.contains("php"), clean);
    assertFalse(clean.contains("ev:event"), clean);
  }

  /**
   * A document type declaration refuses the whole document, so no entity is ever
   * expanded and no external one read (XXE); so does a document that is not SVG, or
   * not well-formed.
   */
  @Test
  void whatCannotBeMadeSafeIsRefused() {
    assertNull(SvgLogoSanitizer.sanitize(utf8("<?xml version=\"1.0\"?><!DOCTYPE svg [<!ENTITY x SYSTEM \"file:///etc/passwd\">]>"
        + OPEN + "<text>&x;</text>" + CLOSE)));
    assertNull(SvgLogoSanitizer.sanitize(utf8("<!DOCTYPE svg PUBLIC \"-//W3C//DTD SVG 1.1//EN\" \"http://127.0.0.1/svg11.dtd\">"
        + OPEN + CLOSE)));
    assertNull(SvgLogoSanitizer.sanitize(utf8("<html xmlns=\"http://www.w3.org/1999/xhtml\"><body/></html>")));
    assertNull(SvgLogoSanitizer.sanitize(utf8("<svg><rect/></svg>")), "an svg element outside the SVG namespace");
    assertNull(SvgLogoSanitizer.sanitize(utf8(OPEN + "<rect>")));
    assertNull(SvgLogoSanitizer.sanitize(new byte[0]));
    assertNull(SvgLogoSanitizer.sanitize(null));
    assertNotNull(SvgLogoSanitizer.sanitize(utf8(OPEN + CLOSE)));
  }

  /**
   * Cleans a document given as text.
   *
   * @param svg the document
   * @return the cleaned document as text
   */
  private static String sanitize(String svg) {
    byte[] clean = SvgLogoSanitizer.sanitize(utf8(svg));
    assertNotNull(clean, svg);
    return new String(clean, StandardCharsets.UTF_8);
  }

  /**
   * A text's UTF-8 bytes.
   *
   * @param text the text
   * @return the bytes
   */
  private static byte[] utf8(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }
}
