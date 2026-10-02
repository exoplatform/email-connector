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
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.EmailLink;
import org.exoplatform.emailConnector.model.EmailSecurityWarning;
import org.exoplatform.emailConnector.model.EmailSecurityWarningType;

/**
 * The message-only phishing checks of the reader's banner (EXO-90841): the receiving
 * server's authentication verdict and links whose text shows another domain.
 */
class EmailSecurityUtilsTest {

  /** Every mail server's header believed. */
  private static final Set<String> ANY = Set.of(EmailSecurityUtils.ANY_AUTHSERV_ID);

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
    assertEquals(EmailSecurityUtils.AUTH_DKIM, verdict("mx.example.com; dkim=fail (bad signature) header.d=news.example; spf=neutral"));
    assertNull(verdict("mx.example.com; dkim=fail header.d=x.example; spf=pass smtp.mailfrom=x.example"));
    assertNull(verdict("mx.example.com; dkim=fail header.d=x.example; dkim=pass header.d=mail.news.example"));
    assertNull(verdict("mx.example.com; none"));
    assertNull(verdict("mx.example.com; dmarc=none; spf=neutral"));
    assertEquals(EmailSecurityUtils.AUTH_DMARC, verdict("MX.EXAMPLE.COM;\r\n\tDMARC=FAIL action=none header.from=bank.example"));
  }

  /**
   * Only a DKIM signature aligned with the {@code From} domain vouches for a message: a
   * sender signing with their own domain cannot cover an SPF failure of someone else's.
   * A signature naming no domain, or a message with no known {@code From}, counts as
   * aligned.
   */
  @Test
  void onlyAnAlignedSignatureVouches() {
    assertEquals(EmailSecurityUtils.AUTH_SPF, verdict("mx.example.com; spf=fail; dkim=pass header.d=evil.example"));
    assertNull(verdict("mx.example.com; spf=fail; dkim=pass header.i=@Mail.News.Example"));
    assertNull(verdict("mx.example.com; spf=fail; dkim=pass header.s=s1"));
    assertNull(EmailSecurityUtils.authenticationFailure(new String[] { "mx.example.com; spf=fail; dkim=pass header.d=evil.example" }, null));
    assertNull(verdict("mx.example.com; spf=neutral; dkim=fail header.d=evil.example"));
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
        "forged.example; dmarc=fail" }, null));
    assertEquals(EmailSecurityUtils.AUTH_DMARC,
                 EmailSecurityUtils.authenticationFailure(new String[] { "mx.example.com; dmarc=fail", "other; dmarc=pass" }, null));
    assertNull(EmailSecurityUtils.authenticationFailure(null, null));
    assertNull(EmailSecurityUtils.authenticationFailure(new String[0], null));
    assertNull(EmailSecurityUtils.authenticationFailure(new String[] { " " }, null));
  }

  /**
   * EXO-90893 -- a DMARC pass counts for the brand logo only when the top header says
   * so for the From domain itself: a pass for another domain, a pass beside a failure,
   * a pass only a lower header claims, a pass in a comment, no header or no From, none
   * of these is a pass. Case and folding do not matter, and a result naming no
   * header.from is the From's.
   */
  @Test
  void aDmarcPassCountsForTheFromDomainOnly() {
    assertTrue(passed("mx.example.com; spf=pass; dkim=pass header.d=brand.example; dmarc=pass header.from=brand.example"));
    assertTrue(passed("MX.EXAMPLE.COM;\r\n\tDMARC=PASS (p=REJECT) HEADER.FROM=Brand.Example"));
    assertTrue(passed("mx.example.com; dmarc=pass"));
    assertFalse(passed("mx.example.com; dmarc=pass header.from=other.example"));
    assertFalse(passed("mx.example.com; dmarc=pass header.from=news.brand.example"), "a subdomain's pass is not the domain's");
    assertFalse(passed("mx.example.com; dmarc=pass header.from=brand.example; dmarc=fail header.from=brand.example"));
    assertFalse(passed("mx.example.com; spf=pass (dmarc=pass header.from=brand.example)"));
    assertFalse(passed("mx.example.com; dmarc=none header.from=brand.example"));
    assertFalse(passed("mx.example.com; dkim=pass header.d=brand.example"));
    assertFalse(EmailSecurityUtils.dmarcPassed(new String[] { "mx.example.com; spf=pass", "forged.example; dmarc=pass" },
                                               "news@brand.example",
                                               ANY),
                "a lower header vouches for nothing");
    assertFalse(EmailSecurityUtils.dmarcPassed(null, "news@brand.example", ANY));
    assertFalse(EmailSecurityUtils.dmarcPassed(new String[] { " " }, "news@brand.example", ANY));
    assertFalse(EmailSecurityUtils.dmarcPassed(new String[] { "mx.example.com; dmarc=pass" }, null, ANY));
    assertFalse(EmailSecurityUtils.dmarcPassed(new String[] { "mx.example.com; dmarc=pass" }, "no-domain", ANY));
  }

  /**
   * EXO-90893 -- a pass is believed only from a mail server the deployment names: none
   * named believes none, a named one believes its own header only, and {@code *}
   * believes the top header whoever wrote it. A header naming no server (Microsoft 365
   * opens with a result) is believed under {@code *} only.
   */
  @Test
  void aDmarcPassNeedsATrustedServerWhenTheDeploymentNamesOne() {
    String[] header = { "mx.example.com 1; dmarc=pass header.from=brand.example" };
    assertTrue(EmailSecurityUtils.dmarcPassed(header, "news@brand.example", Set.of("mx.example.com")));
    assertFalse(EmailSecurityUtils.dmarcPassed(header, "news@brand.example", Set.of("imap.corp.example")));
    assertFalse(EmailSecurityUtils.dmarcPassed(header, "news@brand.example", Set.of()), "none named, none believed");
    assertFalse(EmailSecurityUtils.dmarcPassed(header, "news@brand.example", null));
    assertTrue(EmailSecurityUtils.dmarcPassed(header, "news@brand.example", ANY));
    String[] microsoft = { "spf=pass (sender IP is 1.2.3.4) smtp.mailfrom=brand.example; dkim=pass header.d=brand.example;"
        + "dmarc=pass action=none header.from=brand.example;compauth=pass reason=100" };
    assertTrue(EmailSecurityUtils.dmarcPassed(microsoft, "news@brand.example", ANY));
    assertFalse(EmailSecurityUtils.dmarcPassed(microsoft, "news@brand.example", Set.of("spf=pass")));
    System.setProperty(EmailSecurityUtils.TRUSTED_AUTHSERV_IDS_PROPERTY, " MX.example.com , imap.corp.example,");
    try {
      assertEquals(Set.of("mx.example.com", "imap.corp.example"), EmailSecurityUtils.trustedAuthservIds());
    } finally {
      System.clearProperty(EmailSecurityUtils.TRUSTED_AUTHSERV_IDS_PROPERTY);
    }
    assertEquals(Set.of(), EmailSecurityUtils.trustedAuthservIds());
  }

  /**
   * The DMARC pass verdict of a top header, for news@brand.example, any server trusted.
   *
   * @param header the top Authentication-Results header
   * @return whether DMARC passed for brand.example
   */
  private static boolean passed(String header) {
    return EmailSecurityUtils.dmarcPassed(new String[] { header }, "news@brand.example", ANY);
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
   * A crafted link text cannot make the check slow or overflow the stack: a host of half
   * a million labels, a near-address that fails at its very end, a long hyphen run, a
   * long path. Each is judged well inside a second; an expression that repeats a group
   * per label would overflow the stack on the first.
   */
  @Test
  void aCraftedLinkTextIsJudgedInLinearTime() {
    String[] hostile = { "a.".repeat(500_000) + "com",
        "a.".repeat(500_000) + "com x",
        "a" + "-".repeat(1_000_000) + "b.com",
        "a-".repeat(500_000) + ".com",
        "www.bank.com/" + "a".repeat(1_000_000) + " ",
        "https://" + "1.".repeat(500_000) };
    for (String text : hostile) {
      assertTimeoutPreemptively(Duration.ofSeconds(3),
                                () -> EmailSecurityUtils.deceptiveLink(List.of(new EmailLink(text, "https://evil.example/"))),
                                () -> "slow on a " + text.length() + "-character link text");
    }
    assertEquals("evil.example",
                 EmailSecurityUtils.deceptiveLink(List.of(new EmailLink("a.".repeat(200_000) + "com", "https://evil.example/")))
                                   .getActual());
  }

  /**
   * A crafted Authentication-Results header -- deeply nested comments, a long run of
   * signing identities with no {@code @} -- is judged in bounded time: only its first
   * 8192 characters are read, ample for a real one.
   */
  @Test
  void aCraftedHeaderIsJudgedInBoundedTime() {
    String[] hostile = { "mx.example.com; " + "(".repeat(200_000) + ")".repeat(200_000) + "; dmarc=fail",
        "mx.example.com; dkim=pass " + "header.i=x".repeat(200_000) + "; spf=fail" };
    for (String header : hostile) {
      assertTimeoutPreemptively(Duration.ofSeconds(3), () -> verdict(header), () -> "slow on a " + header.length() + "-character header");
    }
    assertEquals(EmailSecurityUtils.AUTH_DMARC, verdict("mx.example.com; " + "(c)".repeat(1_000) + " dmarc=fail"));
  }

  /**
   * The edges of what reads as an address: a trailing dot, a port, a path with no
   * white space and no {@code @}, and what does not.
   */
  @Test
  void whatReadsAsAnAddress() {
    assertEquals("bank.example", EmailSecurityUtils.hostOfText("HTTPS://Bank.Example./login?x=1#y"));
    assertEquals("bank.example", EmailSecurityUtils.hostOfText("bank.example:8443/a"));
    assertEquals("xn--bcher-kva.example", EmailSecurityUtils.hostOfText("b\u00fccher.example"));
    for (String text : new String[] { "bank", "bank.", ".bank.example", "bank..example", "-bank.example", "bank-.example",
        "bank.example:", "bank.example:123456", "bank.example:80x", "bank.example x", "bank.example/a b", "bank.example/a@b",
        "bank.e", "bank.c0m", "user@bank.example", "ftp://bank.example", "bank.example..", "www.bank.example#" }) {
      if ("www.bank.example#".equals(text)) {
        assertEquals("www.bank.example", EmailSecurityUtils.hostOfText(text), text);
      } else {
        assertNull(EmailSecurityUtils.hostOfText(text), text);
      }
    }
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
   * The verdict of one header, for a message from {@code news.example}.
   *
   * @param header the header's value
   * @return the failure named, or null
   */
  private static String verdict(String header) {
    return EmailSecurityUtils.authenticationFailure(new String[] { header }, "letters@news.example");
  }
}
