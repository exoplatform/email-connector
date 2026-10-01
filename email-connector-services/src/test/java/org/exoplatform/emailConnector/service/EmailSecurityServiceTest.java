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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailSecurityWarning;
import org.exoplatform.emailConnector.model.EmailSecurityWarningType;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.RemoteContentSettings;
import org.exoplatform.emailConnector.utils.EmailSecurityUtils;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.social.core.identity.provider.OrganizationIdentityProvider;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.social.core.profile.ProfileFilter;

import io.meeds.social.util.JsonUtils;

/**
 * The reader's remote-content holding and phishing warnings (EXO-90841), with the
 * setting store and the directory mocked: the stored setting is kept in memory so a
 * write is read back as the service would read it.
 */
@ExtendWith(MockitoExtension.class)
class EmailSecurityServiceTest {

  private static final String         USER          = "jane";

  private static final String         MAILBOX       = "jane@acme.example";

  private static final String         TRACKED_BODY  = "<p>Hi</p><img src=\"https://tracker.example/p.gif\">";

  @Mock
  private SettingService              settingService;

  @Mock
  private IdentityManager             identityManager;

  @InjectMocks
  private EmailSecurityService        service;

  private final AtomicReference<String> storedSetting = new AtomicReference<>();

  /**
   * Keeps the setting in memory: what the service writes is what it reads next.
   */
  @BeforeEach
  void storeSettingsInMemory() {
    lenient().when(settingService.get(eq(Context.USER.id(USER)), any(), eq(EmailSecurityService.SETTINGS_KEY)))
             .thenAnswer(invocation -> storedSetting.get() == null ? null : SettingValue.create(storedSetting.get()));
    lenient().doAnswer(invocation -> {
      storedSetting.set((String) ((SettingValue<?>) invocation.getArgument(3)).getValue());
      return null;
    }).when(settingService).set(eq(Context.USER.id(USER)), any(), eq(EmailSecurityService.SETTINGS_KEY), any());
  }

  /**
   * Clears the organisation-domains property a test may have set.
   */
  @AfterEach
  void clearProperty() {
    System.clearProperty(EmailSecurityService.ORGANISATION_DOMAINS_PROPERTY);
  }

  /**
   * By default remote content waits for consent: the body is cleaned and blocked, and
   * the reader is told so; asking for it, trusting the sender or switching blocking off
   * each lets it through, and the switch and the trust are both remembered.
   */
  @Test
  void remoteContentWaitsForConsentUnlessGiven() {
    Email email = received("News", "news@shop.example", TRACKED_BODY);
    service.decorate(email, USER, false);
    assertTrue(email.getContent().isRemoteContentBlocked());
    assertFalse(email.getContent().getBody().contains("tracker.example"));

    email = received("News", "news@shop.example", TRACKED_BODY);
    service.decorate(email, USER, true);
    assertFalse(email.getContent().isRemoteContentBlocked());
    assertTrue(email.getContent().getBody().contains("https://tracker.example/p.gif"));

    service.trustSender(USER, " News@Shop.Example ");
    email = received("News", "NEWS@shop.example", TRACKED_BODY);
    service.decorate(email, USER, false);
    assertFalse(email.getContent().isRemoteContentBlocked());
    assertTrue(email.getContent().getBody().contains("https://tracker.example/p.gif"));

    email = received("Other", "other@shop.example", TRACKED_BODY);
    service.decorate(email, USER, false);
    assertTrue(email.getContent().isRemoteContentBlocked());

    service.setBlockRemoteContent(USER, false);
    assertEquals(List.of("news@shop.example"), service.getSettings(USER).getTrustedSenders());
    email = received("Other", "other@shop.example", TRACKED_BODY);
    service.decorate(email, USER, false);
    assertFalse(email.getContent().isRemoteContentBlocked());
  }

  /**
   * A From naming the reader's own address is no proof the reader wrote it: in the
   * inbox, or failing its sender authentication even in Sent, the message is held back
   * and warned about like any other.
   */
  @Test
  void aForgedOwnAddressBorrowsNoTrust() {
    Email spoofed = received("Jane Doe", MAILBOX, TRACKED_BODY);
    spoofed.setFolder(MailFolder.INBOX);
    spoofed.getContent().setAuthFailure(EmailSecurityUtils.AUTH_DMARC);
    Email inbox = received("Jane Doe", MAILBOX, TRACKED_BODY + "<a href=\"https://evil.example/\">bank.example</a>");
    inbox.setFolder(MailFolder.INBOX);
    Email failingSent = received("Jane Doe", MAILBOX, TRACKED_BODY);
    failingSent.setFolder(MailFolder.SENT);
    failingSent.getContent().setAuthFailure(EmailSecurityUtils.AUTH_SPF);
    service.decorate(List.of(spoofed, inbox, failingSent), USER, false);
    assertTrue(spoofed.getContent().isRemoteContentBlocked());
    assertEquals(EmailSecurityWarningType.AUTHENTICATION_FAILED, spoofed.getContent().getSecurityWarnings().get(0).getType());
    assertTrue(inbox.getContent().isRemoteContentBlocked());
    assertEquals(EmailSecurityWarningType.DECEPTIVE_LINK, inbox.getContent().getSecurityWarnings().get(0).getType());
    assertTrue(failingSent.getContent().isRemoteContentBlocked());
    assertEquals(EmailSecurityWarningType.AUTHENTICATION_FAILED, failingSent.getContent().getSecurityWarnings().get(0).getType());
  }

  /**
   * Trusting a sender does not cover a message that gives a reason for doubt: a forged
   * trusted From failing DMARC stays held back. The user's own explicit choices -- the
   * click, blocking switched off -- still hold.
   */
  @Test
  void aTrustedSenderWithAWarningStaysHeldBack() {
    service.trustSender(USER, "news@shop.example");
    Email forged = received("News", "news@shop.example", TRACKED_BODY);
    forged.getContent().setAuthFailure(EmailSecurityUtils.AUTH_DMARC);
    service.decorate(forged, USER, false);
    assertTrue(forged.getContent().isRemoteContentBlocked());
    Email clicked = received("News", "news@shop.example", TRACKED_BODY);
    clicked.getContent().setAuthFailure(EmailSecurityUtils.AUTH_DMARC);
    service.decorate(clicked, USER, true);
    assertFalse(clicked.getContent().isRemoteContentBlocked());
    assertTrue(clicked.getContent().getBody().contains("https://tracker.example/p.gif"));
    assertEquals(1, clicked.getContent().getSecurityWarnings().size());
  }

  /**
   * A body is cleaned whatever the consent: a script never reaches the reader.
   */
  @Test
  void theBodyIsAlwaysCleaned() {
    service.setBlockRemoteContent(USER, false);
    Email email = received("Someone", "someone@shop.example", "<p>x</p><script>alert(1)</script><img src=x onerror=alert(2)>");
    service.decorate(List.of(email), USER, true);
    assertFalse(email.getContent().getBody().contains("script"));
    assertFalse(email.getContent().getBody().contains("onerror"));
  }

  /**
   * A message of the mailbox's Sent folder loads its content and carries no warning; a
   * draft, the user's own text read back by the composer, is left exactly as stored;
   * a plain-text body is not touched.
   */
  @Test
  void ownMailDraftsAndPlainTextAreLeftAlone() {
    Email sent = received("Jane Doe", MAILBOX.toUpperCase(), TRACKED_BODY + "<a href=\"https://evil.example\">bank.example</a>");
    sent.setFolder(MailFolder.SENT);
    Email draft = received("Jane Doe", MAILBOX, TRACKED_BODY);
    draft.setDraftLocalId("d-1");
    Email text = received("Someone", "someone@shop.example", "<b>not markup</b>");
    text.getContent().setHtml(false);
    text.getContent().setAuthFailure(EmailSecurityUtils.AUTH_SPF);
    service.decorate(List.of(sent, draft, text), USER, false);
    assertFalse(sent.getContent().isRemoteContentBlocked());
    assertTrue(sent.getContent().getSecurityWarnings().isEmpty());
    assertEquals(TRACKED_BODY, draft.getContent().getBody());
    assertEquals(null, draft.getContent().getSecurityWarnings());
    assertEquals("<b>not markup</b>", text.getContent().getBody());
    assertEquals(List.of(new EmailSecurityWarning(EmailSecurityWarningType.AUTHENTICATION_FAILED, EmailSecurityUtils.AUTH_SPF, null)),
                 text.getContent().getSecurityWarnings());
  }

  /**
   * A sender borrowing a colleague's full name from an outside address is flagged;
   * the lookup is made once per name for the whole conversation.
   */
  @Test
  void aColleaguesNameFromOutsideIsImpersonation() throws Exception {
    directoryHas("John Smith", "john.smith@acme.example");
    Email first = received("John Smith", "john.smith.ceo@gmail.com", "<p>Urgent</p>");
    Email second = received("Smith, John", "john.smith.ceo@gmail.com", "<p>Again</p>");
    service.decorate(List.of(first, second), USER, false);
    EmailSecurityWarning expected = new EmailSecurityWarning(EmailSecurityWarningType.IMPERSONATION,
                                                             "John Smith",
                                                             "john.smith.ceo@gmail.com");
    assertEquals(List.of(expected), first.getContent().getSecurityWarnings());
    assertEquals(List.of(expected), second.getContent().getSecurityWarnings());
    verify(identityManager, times(1)).getIdentitiesByProfileFilter(eq(OrganizationIdentityProvider.NAME),
                                                                   any(ProfileFilter.class),
                                                                   anyLong(),
                                                                   anyLong());
  }

  /**
   * A free-mail domain is never the organisation's: Jane's colleague registered on the
   * platform with a Gmail address is still impersonated from another Gmail address.
   */
  @Test
  void aFreeMailDomainIsNeverTheOrganisations() throws Exception {
    directoryHas("John Smith", "jsmith@gmail.com");
    Email email = received("John Smith", "john.smith.ceo@gmail.com", "<p>Urgent</p>");
    service.decorate(email, USER, false);
    assertEquals(EmailSecurityWarningType.IMPERSONATION, email.getContent().getSecurityWarnings().get(0).getType());
  }

  /**
   * No impersonation when the address is the colleague's own, a platform user's, on the
   * organisation's domains (the reader's mailbox, the colleague's, the configured ones),
   * or when the name is a single word or nobody's.
   */
  @Test
  void anInsiderOrAnUnknownNameIsNotImpersonation() throws Exception {
    directoryHas("John Smith", "john.smith@acme.example");
    System.setProperty(EmailSecurityService.ORGANISATION_DOMAINS_PROPERTY, "acme-group.example, @partner.example");
    List<Email> emails = new ArrayList<>();
    emails.add(received("John Smith", "John.Smith@acme.example", "x"));
    emails.add(received("John Smith", "j.smith@acme.example", "x"));
    emails.add(received("John Smith", "john@acme-group.example", "x"));
    emails.add(received("John Smith", "john@partner.example", "x"));
    Email platformUser = received("John Smith", "jsmith@gmail.com", "x");
    platformUser.getSender().setProfileUrl("/portal/dw/profile/jsmith");
    emails.add(platformUser);
    emails.add(received("Support", "support@gmail.com", "x"));
    emails.add(received("Ann Other", "ann@gmail.com", "x"));
    service.decorate(emails, USER, false);
    for (Email email : emails) {
      assertTrue(email.getContent().getSecurityWarnings().isEmpty(), email.getSender().getAddress());
    }
    // The organisation's own domains are excused before the directory is asked.
    Email insider = received("Ann Insider", "ann@acme-group.example", "x");
    org.mockito.Mockito.clearInvocations(identityManager);
    service.decorate(insider, USER, false);
    verify(identityManager, never()).getIdentitiesByProfileFilter(anyString(), any(ProfileFilter.class), anyLong(), anyLong());
  }

  /**
   * A failing directory gives no warning rather than a failed read.
   */
  @Test
  void aFailingDirectoryGivesNoWarning() throws Exception {
    when(identityManager.getIdentitiesByProfileFilter(anyString(), any(ProfileFilter.class), anyLong(), anyLong()))
      .thenThrow(new IllegalStateException("search down"));
    Email email = received("John Smith", "john.smith.ceo@gmail.com", "<p>Urgent</p>");
    service.decorate(email, USER, false);
    assertTrue(email.getContent().getSecurityWarnings().isEmpty());
  }

  /**
   * A deceptive link is flagged on a personal mail, not on bulk or automated mail nor
   * on a forward, where click tracking makes every displayed address "deceptive".
   */
  @Test
  void deceptiveLinksAreJudgedOnPersonalMailOnly() {
    String body = "<a href=\"https://login.evil.example/\">www.mybank.com</a>";
    Email personal = received("Bank", "alerts@mybank.example", body);
    Email newsletter = received("Bank", "alerts@mybank.example", body);
    newsletter.setHasListUnsubscribe(true);
    Email automated = received("Bank", "alerts@mybank.example", body);
    automated.setAutoSubmitted(true);
    Email forward = received("Bank", "alerts@mybank.example", body);
    forward.setSubject("Fwd: your account");
    service.decorate(List.of(personal, newsletter, automated, forward), USER, false);
    assertEquals(List.of(new EmailSecurityWarning(EmailSecurityWarningType.DECEPTIVE_LINK, "www.mybank.com", "login.evil.example")),
                 personal.getContent().getSecurityWarnings());
    assertTrue(newsletter.getContent().getSecurityWarnings().isEmpty());
    assertTrue(automated.getContent().getSecurityWarnings().isEmpty());
    assertTrue(forward.getContent().getSecurityWarnings().isEmpty());
  }

  /**
   * The bulk and forward exemptions rest on headers and a subject the sender writes:
   * on a message failing its sender authentication, the link is judged anyway.
   */
  @Test
  void theExemptionsDoNotHoldForAFailingMessage() {
    String body = "<a href=\"https://login.evil.example/\">www.mybank.com</a>";
    Email newsletter = received("Bank", "alerts@mybank.example", body);
    newsletter.setHasListUnsubscribe(true);
    newsletter.getContent().setAuthFailure(EmailSecurityUtils.AUTH_SPF);
    Email forward = received("Bank", "alerts@mybank.example", body);
    forward.setSubject("Fwd: your account");
    forward.getContent().setAuthFailure(EmailSecurityUtils.AUTH_DKIM);
    service.decorate(List.of(newsletter, forward), USER, false);
    assertEquals(EmailSecurityWarningType.DECEPTIVE_LINK, newsletter.getContent().getSecurityWarnings().get(1).getType());
    assertEquals(EmailSecurityWarningType.DECEPTIVE_LINK, forward.getContent().getSecurityWarnings().get(1).getType());
  }

  /**
   * The stored authentication failure is reported, first among the warnings.
   */
  @Test
  void anAuthenticationFailureIsReported() {
    Email email = received("Bank", "alerts@mybank.example", "<a href=\"https://evil.example/\">mybank.example</a>");
    email.getContent().setAuthFailure(EmailSecurityUtils.AUTH_DMARC);
    service.decorate(email, USER, false);
    List<EmailSecurityWarning> warnings = email.getContent().getSecurityWarnings();
    assertEquals(2, warnings.size());
    assertEquals(new EmailSecurityWarning(EmailSecurityWarningType.AUTHENTICATION_FAILED, EmailSecurityUtils.AUTH_DMARC, null),
                 warnings.get(0));
  }

  /**
   * Trusting validates the address, keeps each sender once, the most recent last, and
   * at most the cap; forgetting removes it; the fingerprint follows every change.
   */
  @Test
  void trustedSenders() {
    int initial = service.settingsFingerprint(USER);
    assertEquals(new RemoteContentSettings(true, List.of()), service.getSettings(USER));
    for (String invalid : new String[] { null, " ", "no-at-sign", "@example.com", "a@", "a b@example.com",
        "\"x\"@example.com", "a@example.com,b@example.com" }) {
      IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class, () -> service.trustSender(USER, invalid));
      assertEquals(EmailSecurityService.INVALID_SENDER, refusal.getMessage());
    }
    verify(settingService, never()).set(any(), any(), anyString(), any());
    service.trustSender(USER, "a@example.com");
    service.trustSender(USER, "b@example.com");
    service.trustSender(USER, "A@example.com");
    assertEquals(List.of("b@example.com", "a@example.com"), service.getSettings(USER).getTrustedSenders());
    assertNotEquals(initial, service.settingsFingerprint(USER));
    int trusted = service.settingsFingerprint(USER);
    service.forgetSender(USER, "B@example.com");
    assertEquals(List.of("a@example.com"), service.getSettings(USER).getTrustedSenders());
    assertNotEquals(trusted, service.settingsFingerprint(USER));
    assertThrows(IllegalArgumentException.class, () -> service.forgetSender(USER, "nobody"));
    for (int i = 0; i < EmailSecurityService.MAX_TRUSTED_SENDERS + 5; i++) {
      service.trustSender(USER, "s" + i + "@example.com");
    }
    List<String> senders = service.getSettings(USER).getTrustedSenders();
    assertEquals(EmailSecurityService.MAX_TRUSTED_SENDERS, senders.size());
    assertEquals("s" + (EmailSecurityService.MAX_TRUSTED_SENDERS + 4) + "@example.com", senders.get(senders.size() - 1));
  }

  /**
   * Unreadable stored settings fall back on the defaults, which block.
   */
  @Test
  void unreadableSettingsBlock() {
    storedSetting.set("{not json");
    assertEquals(new RemoteContentSettings(true, List.of()), service.getSettings(USER));
    storedSetting.set(JsonUtils.toJsonString(new RemoteContentSettings(false, null)));
    assertEquals(new RemoteContentSettings(false, List.of()), service.getSettings(USER));
  }

  /**
   * A received HTML message to the mailbox.
   *
   * @param name the sender's display name
   * @param address the sender's address
   * @param body the HTML body
   * @return the message
   */
  private static Email received(String name, String address, String body) {
    Email email = new Email();
    email.setUserEmail(MAILBOX);
    email.setSubject("Subject");
    email.setSender(new EmailSender(name, address, null, null));
    EmailContent content = new EmailContent(body);
    content.setHtml(true);
    email.setContent(content);
    return email;
  }

  /**
   * Makes the directory answer one user to any name search.
   *
   * @param fullName the user's full name
   * @param address the user's address
   * @throws Exception never, the directory being mocked
   */
  private void directoryHas(String fullName, String address) throws Exception {
    Identity identity = new Identity(OrganizationIdentityProvider.NAME, "jsmith");
    Profile profile = new Profile(identity);
    profile.setProperty(Profile.FULL_NAME, fullName);
    profile.setProperty(Profile.EMAIL, address);
    identity.setProfile(profile);
    lenient().when(identityManager.getIdentitiesByProfileFilter(eq(OrganizationIdentityProvider.NAME),
                                                                any(ProfileFilter.class),
                                                                anyLong(),
                                                                anyLong()))
             .thenReturn(List.of(identity));
  }
}
