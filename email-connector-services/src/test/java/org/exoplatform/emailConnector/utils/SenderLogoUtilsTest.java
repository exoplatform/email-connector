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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The text and byte reading of the sender brand logo (EXO-90893): the domain looked up,
 * the BIMI and DMARC records, the image types.
 */
class SenderLogoUtilsTest {

  /**
   * A domain is cached and served under one form only, and only a domain name a mail
   * could come from is one: no address literal, no single label, no path or port, no
   * underscore, no numeric top level.
   */
  @Test
  void onlyAMailDomainNameIsADomain() {
    assertEquals("brand.example", SenderLogoUtils.normaliseDomain(" Brand.Example. "));
    assertEquals("xn--bcher-kva.example", SenderLogoUtils.normaliseDomain("bücher.example"));
    assertEquals("news.brand.co.uk", SenderLogoUtils.normaliseDomain("NEWS.brand.co.uk"));
    for (String refused : new String[] { null, "", " ", "localhost", "127.0.0.1", "[::1]", "brand.example/x", "brand.example:443",
        "a_b.example", "-brand.example", "brand-.example", "brand..example", "brand.123", "user@brand.example",
        "a".repeat(64) + ".example", "x".repeat(250) + ".example" }) {
      assertNull(SenderLogoUtils.normaliseDomain(refused), String.valueOf(refused));
    }
    assertEquals("brand.example", SenderLogoUtils.domainOfAddress(" news@Brand.Example "));
    assertNull(SenderLogoUtils.domainOfAddress("no-domain"));
    assertNull(SenderLogoUtils.domainOfAddress(null));
    assertEquals("brand.example", SenderLogoUtils.organisationalDomain("news.mail.brand.example"));
    assertEquals("brand.co.uk", SenderLogoUtils.organisationalDomain("news.brand.co.uk"));
    assertEquals("tenant.herokuapp.com", SenderLogoUtils.organisationalDomain("tenant.herokuapp.com"), "a private suffix's tenant is its own");
    assertEquals("tenant.herokuapp.com", SenderLogoUtils.organisationalDomain("mail.tenant.herokuapp.com"));
    assertEquals("brand.com.au", SenderLogoUtils.organisationalDomain("news.brand.com.au"));
    assertEquals("co.uk", SenderLogoUtils.organisationalDomain("co.uk"), "a public suffix is its own");
  }

  /**
   * A TXT record answered as several quoted strings is one value, joined without
   * separator, escapes read; an unquoted one is kept.
   */
  @Test
  void aTxtRecordIsOneValue() {
    assertEquals("v=BIMI1; l=https://brand.example/logo.svg", SenderLogoUtils.txtValue("\"v=BIMI1; l=https://brand\" \".example/logo.svg\""));
    assertEquals("a\"b", SenderLogoUtils.txtValue("\"a\\\"b\""));
    assertEquals("v=DMARC1; p=reject", SenderLogoUtils.txtValue(" v=DMARC1; p=reject "));
    assertEquals("", SenderLogoUtils.txtValue(null));
  }

  /**
   * The one BIMI record gives the logo's location: its first entry when a list is
   * given; an empty l= declines; no record, two records, or v= not first is no BIMI.
   */
  @Test
  void theBimiRecordGivesTheLocation() {
    assertEquals("https://brand.example/logo.svg",
                 SenderLogoUtils.bimiLocation(List.of("v=spf1 -all", "v=BIMI1; l=https://brand.example/logo.svg; a=https://brand.example/vmc.pem")));
    assertEquals("https://brand.example/a.svg", SenderLogoUtils.bimiLocation(List.of("v=bimi1;l= https://brand.example/a.svg , https://b.example/b.svg")));
    assertEquals("", SenderLogoUtils.bimiLocation(List.of("v=BIMI1; l=; a=;")));
    assertEquals("", SenderLogoUtils.bimiLocation(List.of("v=BIMI1;")));
    assertNull(SenderLogoUtils.bimiLocation(List.of()));
    assertNull(SenderLogoUtils.bimiLocation(null));
    assertNull(SenderLogoUtils.bimiLocation(List.of("l=https://brand.example/logo.svg; v=BIMI1")));
    assertNull(SenderLogoUtils.bimiLocation(List.of("v=BIMI1; l=https://a.example/a.svg", "v=BIMI1; l=https://b.example/b.svg")));
    assertNull(SenderLogoUtils.bimiLocation(List.of("v=BIMI2; l=https://a.example/a.svg")));
  }

  /**
   * BIMI needs an enforced DMARC policy: quarantine or reject, on every message; for a
   * subdomain read from its organisational domain's record, sp= when it is set.
   */
  @Test
  void theDmarcPolicyMustBeEnforced() {
    assertTrue(SenderLogoUtils.dmarcEnforced(List.of("v=DMARC1; p=reject; rua=mailto:d@brand.example"), false));
    assertTrue(SenderLogoUtils.dmarcEnforced(List.of("v=DMARC1; p=Quarantine; pct=100"), false));
    assertFalse(SenderLogoUtils.dmarcEnforced(List.of("v=DMARC1; p=none"), false));
    assertFalse(SenderLogoUtils.dmarcEnforced(List.of("v=DMARC1; p=reject; pct=50"), false));
    assertFalse(SenderLogoUtils.dmarcEnforced(List.of("v=DMARC1; p=reject", "v=DMARC1; p=reject"), false));
    assertFalse(SenderLogoUtils.dmarcEnforced(List.of(), false));
    assertFalse(SenderLogoUtils.dmarcEnforced(List.of("v=DMARC1; p=reject; sp=none"), true));
    assertTrue(SenderLogoUtils.dmarcEnforced(List.of("v=DMARC1; p=none; sp=reject"), true));
    assertTrue(SenderLogoUtils.dmarcEnforced(List.of("v=DMARC1; p=reject"), true));
    assertTrue(SenderLogoUtils.hasDmarcRecord(List.of("other", "v=DMARC1; p=none")));
    assertFalse(SenderLogoUtils.hasDmarcRecord(List.of("v=spf1 -all")));
    assertFalse(SenderLogoUtils.hasDmarcRecord(null));
  }

  /**
   * The declared type must be an image type a logo may be, parameters ignored; an
   * HTML page, a script or no type at all is refused.
   */
  @Test
  void onlyImageTypesMayBeDeclared() {
    for (String allowed : new String[] { "image/svg+xml", "IMAGE/PNG", "image/x-icon", "image/vnd.microsoft.icon", "image/ico",
        "image/jpeg", "image/webp; charset=binary" }) {
      assertTrue(SenderLogoUtils.isAllowedDeclaredType(allowed), allowed);
    }
    for (String refused : new String[] { null, "", "text/html", "application/octet-stream", "image/gif", "text/xml",
        "application/javascript" }) {
      assertFalse(SenderLogoUtils.isAllowedDeclaredType(refused), String.valueOf(refused));
    }
  }

  /**
   * The served type comes from the bytes: each image kind by its signature, SVG by its
   * opening, and anything else -- an HTML page, a short body -- is none.
   */
  @Test
  void theTypeComesFromTheBytes() {
    assertEquals(SenderLogoUtils.PNG, SenderLogoUtils.sniffImageType(bytes(0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0)));
    assertEquals(SenderLogoUtils.JPEG, SenderLogoUtils.sniffImageType(bytes(0xFF, 0xD8, 0xFF, 0xE0)));
    assertEquals(SenderLogoUtils.WEBP, SenderLogoUtils.sniffImageType(bytes('R', 'I', 'F', 'F', 1, 2, 3, 4, 'W', 'E', 'B', 'P')));
    assertEquals(SenderLogoUtils.ICO, SenderLogoUtils.sniffImageType(bytes(0, 0, 1, 0, 1, 0)));
    assertNull(SenderLogoUtils.sniffImageType(bytes(0, 0, 1, 0, 0, 0)), "an icon of no image");
    assertEquals(SenderLogoUtils.SVG, SenderLogoUtils.sniffImageType(utf8("﻿  <?xml version=\"1.0\"?>\n<svg xmlns=\"http://www.w3.org/2000/svg\"/>")));
    assertEquals(SenderLogoUtils.SVG, SenderLogoUtils.sniffImageType(utf8("<svg xmlns=\"http://www.w3.org/2000/svg\"/>")));
    assertNull(SenderLogoUtils.sniffImageType(utf8("<!DOCTYPE html><html><body><svg/></body></html>")));
    assertNull(SenderLogoUtils.sniffImageType(utf8("<?xml version=\"1.0\"?><feed/>")));
    assertNull(SenderLogoUtils.sniffImageType(bytes(1, 2)));
    assertNull(SenderLogoUtils.sniffImageType(null));
  }

  /**
   * Bytes from values 0 to 255.
   *
   * @param values the values
   * @return the bytes
   */
  private static byte[] bytes(int... values) {
    byte[] bytes = new byte[values.length];
    for (int i = 0; i < values.length; i++) {
      bytes[i] = (byte) values[i];
    }
    return bytes;
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
