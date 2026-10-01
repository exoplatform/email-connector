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
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.EmailLink;
import org.exoplatform.emailConnector.model.EmailSecurityWarning;
import org.exoplatform.emailConnector.model.EmailSecurityWarningType;

/**
 * The message-only phishing checks of the reader's banner (EXO-90841): the receiving
 * server's authentication verdict and links whose text shows another domain.
 */
class EmailSecurityUtilsTest {

  /**
   * DMARC failing warns whatever else passed; DMARC passing, or any DKIM pass, keeps
   * quiet; SPF warns on a hard fail only, DKIM on a failure with SPF not passing.
   */
  @Test
  void authenticationVerdict() {
    assertEquals(EmailSecurityUtils.AUTH_DMARC,
                 verdict("mx.example.com; dkim=pass header.d=bank.example; spf=pass; dmarc=fail (p=REJECT) header.from=bank.example"));
    assertNull(verdict("mx.example.com; spf=fail smtp.mailfrom=list.example; dkim=pass header.d=news.example; dmarc=pass"));
    assertNull(verdict("mx.example.com; spf=fail smtp.mailfrom=list.example; dkim=pass header.d=news.example"));
    assertEquals(EmailSecurityUtils.AUTH_SPF, verdict("mx.example.com; spf=fail smtp.mailfrom=bank.example; dkim=none"));
    assertNull(verdict("mx.example.com; spf=softfail smtp.mailfrom=bank.example; dkim=none"));
    assertEquals(EmailSecurityUtils.AUTH_DKIM, verdict("mx.example.com; dkim=fail (bad signature) header.d=bank.example; spf=neutral"));
    assertNull(verdict("mx.example.com; dkim=fail header.d=x.example; spf=pass smtp.mailfrom=x.example"));
    assertNull(verdict("mx.example.com; dkim=fail header.d=x.example; dkim=pass header.d=y.example"));
    assertNull(verdict("mx.example.com; none"));
    assertNull(verdict("mx.example.com; dmarc=none; spf=neutral"));
    assertEquals(EmailSecurityUtils.AUTH_DMARC, verdict("MX.EXAMPLE.COM;\r\n\tDMARC=FAIL action=none header.from=bank.example"));
  }

  /**
   * A comment cannot carry a result: "(dmarc=fail)" inside parentheses, nested or not,
   * is no verdict, and neither is one smuggled into the authserv-id.
   */
  @Test
  void commentsAndTheServerIdCarryNoResult() {
    assertNull(verdict("mx.example.com; spf=pass (sender said (dmarc=fail) here) smtp.mailfrom=x.example"));
    assertNull(verdict("dmarc=fail; spf=pass"));
  }

  /**
   * Only the top header counts: one the sender wrote below the receiving server's
   * cannot raise a warning.
   */
  @Test
  void onlyTheReceivingServersHeaderCounts() {
    assertNull(EmailSecurityUtils.authenticationFailure(new String[] { "mx.example.com; dmarc=pass",
        "forged.example; dmarc=fail" }));
    assertEquals(EmailSecurityUtils.AUTH_DMARC,
                 EmailSecurityUtils.authenticationFailure(new String[] { "mx.example.com; dmarc=fail", "other; dmarc=pass" }));
    assertNull(EmailSecurityUtils.authenticationFailure(null));
    assertNull(EmailSecurityUtils.authenticationFailure(new String[0]));
    assertNull(EmailSecurityUtils.authenticationFailure(new String[] { " " }));
  }

  /**
   * A link reading as an address on one domain while leading to another is deceptive,
   * with or without scheme, path or port, and whatever the case.
   */
  @Test
  void aLinkShowingAnotherDomainIsDeceptive() {
    EmailSecurityWarning warning = EmailSecurityUtils.deceptiveLink(List.of(new EmailLink("Click here", "https://evil.example/"),
                                                                            new EmailLink("www.MyBank.com",
                                                                                          "https://login.evil.example/mybank")));
    assertEquals(new EmailSecurityWarning(EmailSecurityWarningType.DECEPTIVE_LINK, "www.mybank.com", "login.evil.example"), warning);
    assertEquals("evil.example",
                 EmailSecurityUtils.deceptiveLink(List.of(new EmailLink("https://mybank.com:443/account?id=1", "http://evil.example")))
                                   .getActual());
    assertEquals("paypal.com",
                 EmailSecurityUtils.deceptiveLink(List.of(new EmailLink("paypal.com", "https://paypal.com.evil.example/")))
                                   .getShown());
  }

  /**
   * Another host of the same registrable domain is not deceptive, under a plain or a
   * country-code registry; nor is a text that is not an address, a file name, a mail
   * address, or a link that does not lead to the web.
   */
  @Test
  void anHonestLinkIsNot() {
    assertNull(EmailSecurityUtils.deceptiveLink(List.of(new EmailLink("www.example.com", "https://login.example.com/x"),
                                                        new EmailLink("shop.example.co.uk", "https://www.example.co.uk/"),
                                                        new EmailLink("Click here", "https://evil.example/"),
                                                        new EmailLink("Read the news.", "https://news.example/"),
                                                        new EmailLink("report.pdf", "https://files.example/report.pdf"),
                                                        new EmailLink("someone@bank.example", "mailto:someone@bank.example"),
                                                        new EmailLink("bank.example", "mailto:support@evil.example"),
                                                        new EmailLink("", "https://evil.example/"),
                                                        new EmailLink("bank.example", "not a uri at all"))));
    assertNull(EmailSecurityUtils.deceptiveLink(null));
  }

  /**
   * A file name with a scheme or {@code www.} is a site, and is judged.
   */
  @Test
  void aFileExtensionWithASchemeIsASite() {
    assertEquals("www.photos.zip", EmailSecurityUtils.hostOfText("www.photos.zip"));
    assertEquals("photos.zip", EmailSecurityUtils.hostOfText("https://photos.zip/"));
    assertNull(EmailSecurityUtils.hostOfText("photos.zip"));
  }

  /**
   * Internationalised names compare in their ASCII form.
   */
  @Test
  void internationalisedHostsCompareInTheirAsciiForm() {
    assertNull(EmailSecurityUtils.deceptiveLink(List.of(new EmailLink("bücher.example", "https://xn--bcher-kva.example/"))));
    assertEquals("xn--pypal-4ve.com", EmailSecurityUtils.hostOfText("pаypal.com"));
  }

  /**
   * The registrable domain: two labels, three under a country-code registry category.
   */
  @Test
  void registrableDomain() {
    assertEquals("example.com", EmailSecurityUtils.registrableDomain("a.b.example.com"));
    assertEquals("example.co.uk", EmailSecurityUtils.registrableDomain("www.example.co.uk"));
    assertEquals("example.fr", EmailSecurityUtils.registrableDomain("example.fr"));
    assertEquals("impots.gouv.fr", EmailSecurityUtils.registrableDomain("www.impots.gouv.fr"));
  }

  /**
   * Names compare without accents, case, punctuation and order of "Last, First".
   */
  @Test
  void normaliseName() {
    assertEquals("jean pierre leveque", EmailSecurityUtils.normaliseName("  \"Jean-Pierre  Lévêque\" "));
    assertEquals("jane doe", EmailSecurityUtils.normaliseName("Doe, Jane"));
    assertEquals("", EmailSecurityUtils.normaliseName(null));
  }

  /**
   * The verdict of one header.
   *
   * @param header the header's value
   * @return the failure named, or null
   */
  private static String verdict(String header) {
    return EmailSecurityUtils.authenticationFailure(new String[] { header });
  }
}
