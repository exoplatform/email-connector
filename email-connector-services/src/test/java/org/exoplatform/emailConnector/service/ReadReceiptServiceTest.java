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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ReadReceiptConflictException;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ReadReceiptAction;
import org.exoplatform.emailConnector.model.ReadReceiptAnswerOrigin;
import org.exoplatform.emailConnector.model.ReadReceiptPolicy;
import org.exoplatform.emailConnector.model.ReadReceiptPrompt;
import org.exoplatform.emailConnector.model.ReadReceiptSettings;
import org.exoplatform.emailConnector.model.ReadReceiptState;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailReadReceiptAnswerStorage;

import io.meeds.social.util.JsonUtils;

/**
 * Read receipts, phase 1 (EXO-90435): the receipt itself (RFC 8098), the rule deciding
 * what the reader is told, the answer's checks and its at-most-once claim, and the
 * preferences.
 */
@ExtendWith(MockitoExtension.class)
class ReadReceiptServiceTest {

  private static final String     USER        = "alice";

  private static final String     OWN_ADDRESS = "alice@corp.example";

  private static final String     SENDER      = "bob@partner.example";

  private static final long       EMAIL_ID    = 12L;

  private static final long       ANSWER_ID   = 34L;

  private static final String     MESSAGE_ID  = "<original@partner.example>";

  @Mock
  private EmailBoxService         emailBoxService;

  @Mock
  private EmailBoxStorage         emailBoxStorage;

  @Mock
  private UserEmailSettingService userEmailSettingService;

  @Mock
  private SettingService          settingService;

  @Mock
  private EmailReadReceiptAnswerStorage answerStorage;

  @InjectMocks
  private ReadReceiptService      readReceiptService;

  /** The user's mailbox address, which every rule compares against. */
  @BeforeEach
  void theUserHasAMailbox() {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailAddress(OWN_ADDRESS);
    lenient().when(userEmailSettingService.getUserEmailSetting(USER)).thenReturn(setting);
    // The answer store lets the first answer of a message through, as it does on a
    // message nobody answered yet.
    lenient().when(answerStorage.claim(eq(USER), anyString(), any(), any(), any())).thenReturn(ANSWER_ID);
  }

  /** The administrator switch goes back to its default after each test. */
  @AfterEach
  void restoreTheAdministratorSwitch() {
    System.clearProperty(ReadReceiptService.ALLOW_ALWAYS_PROPERTY);
  }

  // ---------------------------------------------------------------------------------
  // The receipt (RFC 8098)
  // ---------------------------------------------------------------------------------

  /**
   * A manual receipt is a multipart/report of report-type disposition-notification: a
   * text part, then the machine-readable part with the reporting agent, the final
   * recipient, the original's Message-ID and "manual-action/MDN-sent-manually;
   * displayed" -- addressed to the requested address, subject "Read: ", threaded on the
   * original, and not Auto-Submitted.
   *
   * @throws Exception when the message cannot be built or read
   */
  @Test
  void aManualReceiptIsAnRfc8098Report() throws Exception {
    MimeMessage receipt = ReadReceiptService.buildReceipt(session(),
                                                          new InternetAddress(OWN_ADDRESS, "Alice"),
                                                          new InternetAddress[] { new InternetAddress(SENDER) },
                                                          incoming(),
                                                          OWN_ADDRESS,
                                                          null,
                                                          false);

    String contentType = receipt.getContentType().replaceAll("\\s+", " ");
    assertTrue(contentType.startsWith("multipart/report"), contentType);
    assertTrue(contentType.contains("report-type=disposition-notification"), contentType);
    assertEquals(SENDER, ((InternetAddress) receipt.getRecipients(Message.RecipientType.TO)[0]).getAddress());
    assertEquals(1, receipt.getAllRecipients().length, "to the requested address only");
    assertEquals(OWN_ADDRESS, ((InternetAddress) receipt.getFrom()[0]).getAddress());
    assertEquals("Read: Quarterly figures", receipt.getSubject());
    assertEquals("<original@partner.example>", receipt.getHeader("In-Reply-To")[0]);
    assertEquals("<root@partner.example> <original@partner.example>", receipt.getHeader("References")[0]);
    assertNull(receipt.getHeader("Auto-Submitted"), "a receipt the user chose to send is not automatic");
    assertNull(receipt.getHeader("Disposition-Notification-To"), "a receipt never asks for a receipt");

    Multipart report = (Multipart) receipt.getContent();
    assertEquals(2, report.getCount(), "the text, then the notification; no copy of the original's headers");
    assertTrue(report.getBodyPart(0).isMimeType("text/plain"));
    assertTrue(((String) report.getBodyPart(0).getContent()).contains("Quarterly figures"));
    assertTrue(report.getBodyPart(1).isMimeType("message/disposition-notification"));
    assertEquals("7bit", report.getBodyPart(1).getHeader("Content-Transfer-Encoding")[0]);
    String fields = read(report.getBodyPart(1).getInputStream());
    assertTrue(fields.contains("Reporting-UA: corp.example; eXo Email Connector\r\n"), fields);
    assertTrue(fields.contains("Final-Recipient: rfc822;" + OWN_ADDRESS + "\r\n"), fields);
    assertTrue(fields.contains("Original-Message-ID: <original@partner.example>\r\n"), fields);
    assertTrue(fields.contains("Disposition: manual-action/MDN-sent-manually; displayed\r\n"), fields);
    assertFalse(fields.contains("Original-Recipient"), "none on the original, none here");
    assertFalse(fields.contains("denied"), "never denied");
  }

  /**
   * A receipt sent without asking says so, in the disposition and with
   * Auto-Submitted: auto-replied.
   *
   * @throws Exception when the message cannot be built or read
   */
  @Test
  void anAutomaticReceiptSaysSoAndIsAutoSubmitted() throws Exception {
    MimeMessage receipt = ReadReceiptService.buildReceipt(session(),
                                                          new InternetAddress(OWN_ADDRESS),
                                                          new InternetAddress[] { new InternetAddress(SENDER) },
                                                          incoming(),
                                                          OWN_ADDRESS,
                                                          "rfc822;alias@corp.example",
                                                          true);

    assertEquals("auto-replied", receipt.getHeader("Auto-Submitted")[0]);
    String fields = read(((Multipart) receipt.getContent()).getBodyPart(1).getInputStream());
    assertTrue(fields.contains("Disposition: automatic-action/MDN-sent-automatically; displayed\r\n"), fields);
    assertTrue(fields.contains("Original-Recipient: rfc822;alias@corp.example\r\n"), "copied from the original: " + fields);
  }

  /**
   * Nothing read from the original can start a line of the receipt: a subject or an
   * Original-Recipient carrying line breaks is flattened or dropped, and the Message-ID
   * this add-on synthesizes for a message that had none is never quoted back.
   *
   * @throws Exception when the message cannot be built or read
   */
  @Test
  void nothingFromTheOriginalStartsALineOfTheReceipt() throws Exception {
    Email original = incoming();
    original.setSubject("Hello\r\nBcc: victim@example.org");
    original.setMailHeaderId("<12.alice@email-connector.local>");
    MimeMessage receipt = ReadReceiptService.buildReceipt(session(),
                                                          new InternetAddress(OWN_ADDRESS),
                                                          new InternetAddress[] { new InternetAddress(SENDER) },
                                                          original,
                                                          OWN_ADDRESS,
                                                          "rfc822;x@y\r\nDisposition: manual-action/MDN-sent-manually; deleted",
                                                          false);

    assertNull(receipt.getHeader("Bcc"));
    assertEquals(1, receipt.getAllRecipients().length);
    // JavaMail would encode the break anyway; the receipt goes further and flattens it,
    // so the subject reads back as the one line it is shown as, here and in the text.
    assertEquals("Read: Hello Bcc: victim@example.org", receipt.getSubject());
    assertTrue(((String) ((Multipart) receipt.getContent()).getBodyPart(0).getContent()).contains("\"Hello Bcc: victim@example.org\""));
    assertNull(receipt.getHeader("In-Reply-To"), "no id to thread on");
    String fields = read(((Multipart) receipt.getContent()).getBodyPart(1).getInputStream());
    assertFalse(fields.contains("Original-Message-ID"), fields);
    assertFalse(fields.contains("Original-Recipient"), "a value spanning lines is dropped: " + fields);
    assertFalse(fields.contains("deleted"), fields);
    assertEquals(1, fields.split("Disposition: ", -1).length - 1, "one disposition line: " + fields);
  }

  // ---------------------------------------------------------------------------------
  // What the reader is told
  // ---------------------------------------------------------------------------------

  /**
   * ASK asks; NEVER shows nothing; ALWAYS sends without asking only when everything
   * vouches for the request.
   */
  @Test
  void thePolicyDecidesThePromptOfASafeRequest() {
    Email safe = incoming();
    assertEquals(ReadReceiptPrompt.ASK, prompt(safe, ReadReceiptPolicy.ASK));
    assertEquals(ReadReceiptPrompt.NONE, prompt(safe, ReadReceiptPolicy.NEVER));
    assertEquals(ReadReceiptPrompt.AUTO, prompt(safe, ReadReceiptPolicy.ALWAYS));
  }

  /**
   * ALWAYS still asks whenever a safety condition fails: a Return-Path that does not
   * vouch for the address, a mailing list (List-Id or List-Post),
   * the user reached only in Bcc or through a list (not in To/Cc), or a
   * machine-generated message.
   */
  @Test
  void alwaysStillAsksInEveryUnsafeCase() {
    Email mismatch = incoming();
    mismatch.setReadReceiptReturnPathMatch(false);
    Email listId = incoming();
    listId.setHasListId(true);
    Email listPost = incoming();
    listPost.setHasListPost(true);
    Email bccOnly = incoming();
    bccOnly.setTo(List.of(recipient("carol@corp.example")));
    bccOnly.setCc(null);
    Email robot = incoming();
    robot.setAutoSubmitted(true);
    for (Email unsafe : List.of(mismatch, listId, listPost, bccOnly, robot)) {
      assertEquals(ReadReceiptPrompt.ASK, prompt(unsafe, ReadReceiptPolicy.ALWAYS));
    }
    Email cc = incoming();
    cc.setTo(List.of(recipient("carol@corp.example")));
    cc.setCc(List.of(recipient(OWN_ADDRESS.toUpperCase())));
    assertEquals(ReadReceiptPrompt.AUTO, prompt(cc, ReadReceiptPolicy.ALWAYS), "Cc is a direct recipient too");
  }

  /**
   * A request naming several addresses is never offered, under any policy: the header
   * is the sender's, and each address would receive mail from the user's own account.
   * One address that is not the sender's is still offered; the banner names it.
   */
  @Test
  void aRequestNamingSeveralAddressesIsNeverOffered() {
    Email several = incoming();
    several.setReadReceiptTo(SENDER + ", tracker@elsewhere.example");
    for (ReadReceiptPolicy policy : ReadReceiptPolicy.values()) {
      assertEquals(ReadReceiptPrompt.NONE, prompt(several, policy), "several addresses under " + policy);
    }
    Email elsewhere = incoming();
    elsewhere.setReadReceiptTo("tracker@elsewhere.example");
    elsewhere.setReadReceiptReturnPathMatch(false);
    assertEquals(ReadReceiptPrompt.ASK, prompt(elsewhere, ReadReceiptPolicy.ASK), "one address, not the sender's");
  }

  /**
   * Some requests are never offered, whatever the policy: Junk, Trash, Sent, Drafts,
   * the user's own mail, a request naming no usable address, one already answered,
   * and a message asking for nothing.
   */
  @Test
  void someRequestsAreNeverOffered() {
    List<Email> never = new ArrayList<>();
    for (String folder : List.of(MailFolder.JUNK, MailFolder.TRASH, MailFolder.SENT, MailFolder.DRAFTS)) {
      Email email = incoming();
      email.setFolder(folder);
      never.add(email);
    }
    Email own = incoming();
    own.setSender(new EmailSender("Alice", OWN_ADDRESS, null, null));
    Email noAddress = incoming();
    noAddress.setReadReceiptTo("undisclosed-recipients:;");
    Email answered = incoming();
    answered.setReadReceiptState(ReadReceiptState.IGNORED);
    Email notAsked = incoming();
    notAsked.setReadReceiptRequested(false);
    never.addAll(List.of(own, noAddress, answered, notAsked));
    for (Email email : never) {
      for (ReadReceiptPolicy policy : ReadReceiptPolicy.values()) {
        assertEquals(ReadReceiptPrompt.NONE, prompt(email, policy), email.getFolder() + " / " + policy);
      }
    }
  }

  /**
   * With the administrator switch off, a stored ALWAYS reads as ASK, and the safe
   * request that ALWAYS would have answered on its own is asked about.
   */
  @Test
  void theAdministratorSwitchTurnsAlwaysIntoAsk() {
    storedSettings(new ReadReceiptSettings(true, ReadReceiptPolicy.ALWAYS, false));
    System.setProperty(ReadReceiptService.ALLOW_ALWAYS_PROPERTY, "false");

    ReadReceiptSettings settings = readReceiptService.getSettings(USER);
    assertEquals(ReadReceiptPolicy.ASK, settings.getResponsePolicy());
    assertFalse(settings.isAlwaysAllowed());
    assertTrue(settings.isRequestByDefault());

    Email safe = incoming();
    readReceiptService.decorate(safe, USER);
    assertEquals(ReadReceiptPrompt.ASK, safe.getReadReceiptPrompt());
  }

  /** Decorating reads the preferences once, and neither them nor the answer store when nothing asks. */
  @Test
  void decoratingAThreadReadsThePreferencesOnce() {
    Email plain = incoming();
    plain.setReadReceiptRequested(false);
    readReceiptService.decorate(List.of(plain), USER);
    assertEquals(ReadReceiptPrompt.NONE, plain.getReadReceiptPrompt());
    verify(settingService, never()).get(any(), any(), anyString());
    verify(answerStorage, never()).findAnswers(anyString(), any());

    Email first = incoming();
    Email second = incoming();
    readReceiptService.decorate(List.of(first, second), USER);
    assertEquals(ReadReceiptPrompt.ASK, first.getReadReceiptPrompt());
    assertEquals(ReadReceiptPrompt.ASK, second.getReadReceiptPrompt());
    verify(settingService).get(any(), any(), eq(ReadReceiptService.SETTINGS_KEY));
  }

  // ---------------------------------------------------------------------------------
  // The answer
  // ---------------------------------------------------------------------------------

  /**
   * SEND claims the answer, transmits the receipt through the one "as the user" door
   * -- nothing else of the mailbox is touched, so there is no Sent copy, no send
   * broadcast and no contact collection -- then sets $MDNSent on the server copy.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void sendingAReceiptClaimsTransmitsAndMarksTheServerCopy() throws Exception {
    Email email = incoming();
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    when(emailBoxStorage.claimReadReceipt(USER, email, ReadReceiptState.SENT)).thenReturn(true);
    EmailBoxService.ServerCopy serverCopy = serverCopy();
    when(serverCopy.header("Original-Recipient")).thenReturn("rfc822;alice@corp.example");
    List<MimeMessage> transmitted = captureTransmissions();

    readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, false);

    assertEquals(1, transmitted.size());
    String fields = read(((Multipart) transmitted.get(0).getContent()).getBodyPart(1).getInputStream());
    assertTrue(fields.contains("Disposition: manual-action/MDN-sent-manually; displayed"), "the user clicked: " + fields);
    assertTrue(fields.contains("Original-Recipient: rfc822;alice@corp.example"), fields);
    verify(serverCopy).addKeyword("$MDNSent");
    verify(serverCopy).close();
    verify(emailBoxService).getOwnedEmailById(EMAIL_ID, USER);
    verify(emailBoxService).openServerCopy(USER, email);
    verify(emailBoxService).transmitAsUser(eq(USER), any());
    verifyNoMoreInteractions(emailBoxService);
  }

  /**
   * Under ALWAYS a safe request is answered in automatic mode.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void alwaysAnswersASafeRequestAutomatically() throws Exception {
    storedSettings(new ReadReceiptSettings(false, ReadReceiptPolicy.ALWAYS, false));
    Email email = incoming();
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    when(emailBoxStorage.claimReadReceipt(USER, email, ReadReceiptState.SENT)).thenReturn(true);
    serverCopy();
    List<MimeMessage> transmitted = captureTransmissions();

    readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, true);

    assertEquals("auto-replied", transmitted.get(0).getHeader("Auto-Submitted")[0]);
  }

  /**
   * The automatic answer is decided now, never on what the reader was told: once the
   * request must be asked about (the policy was changed, the administrator switched
   * ALWAYS off, the message is unsafe), an automatic SEND is refused before anything
   * is claimed, and the reader shows the banner. A click under ALWAYS sends a receipt
   * that says it was sent manually.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void anAutomaticAnswerIsOnlyAcceptedWhileTheServerDecidesAuto() throws Exception {
    Email email = incoming();
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    assertEquals(ReadReceiptService.ASK_FIRST,
                 assertThrows(IllegalArgumentException.class,
                              () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, true)).getMessage(),
                 "the policy is ASK now");
    storedSettings(new ReadReceiptSettings(false, ReadReceiptPolicy.ALWAYS, false));
    System.setProperty(ReadReceiptService.ALLOW_ALWAYS_PROPERTY, "false");
    assertThrows(IllegalArgumentException.class, () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, true),
                 "the administrator switched ALWAYS off");
    System.clearProperty(ReadReceiptService.ALLOW_ALWAYS_PROPERTY);
    email.setReadReceiptReturnPathMatch(false);
    assertThrows(IllegalArgumentException.class, () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, true),
                 "the message is not safe to answer on its own");
    verify(emailBoxStorage, never()).claimReadReceipt(anyString(), any(), any());

    email.setReadReceiptReturnPathMatch(true);
    when(emailBoxStorage.claimReadReceipt(USER, email, ReadReceiptState.SENT)).thenReturn(true);
    serverCopy();
    List<MimeMessage> transmitted = captureTransmissions();
    readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, false);
    assertNull(transmitted.get(0).getHeader("Auto-Submitted"), "a click is never automatic, whatever the policy");
  }

  /**
   * IGNORE sends nothing and records the answer, on the server too -- even under
   * NEVER, whose point is that nothing is ever sent.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void ignoringSendsNothingAndRecordsTheAnswer() throws Exception {
    storedSettings(new ReadReceiptSettings(false, ReadReceiptPolicy.NEVER, false));
    Email email = incoming();
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    when(emailBoxStorage.claimReadReceipt(USER, email, ReadReceiptState.IGNORED)).thenReturn(true);
    EmailBoxService.ServerCopy serverCopy = serverCopy();

    readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.IGNORE, false);

    verify(emailBoxService, never()).transmitAsUser(anyString(), any());
    verify(serverCopy).addKeyword("$MDNSent");
  }

  /**
   * The answer is refused, with its REST code, whenever the reader should not have
   * offered it: unknown or somebody else's message, no request, already answered
   * (here, or lost to a concurrent claim), a message that is never answered, SEND
   * under NEVER, no action.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void everyRefusalCarriesItsCode() throws Exception {
    assertThrows(ObjectNotFoundException.class, () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, false));
    doThrow(IllegalAccessException.class).when(emailBoxService).getOwnedEmailById(99L, USER);
    assertThrows(ObjectNotFoundException.class, () -> readReceiptService.respond(99L, USER, ReadReceiptAction.SEND, false));

    Email notAsked = incoming();
    notAsked.setReadReceiptRequested(false);
    assertRefused(notAsked, ReadReceiptAction.SEND, ReadReceiptService.NOT_REQUESTED);

    Email answered = incoming();
    answered.setReadReceiptState(ReadReceiptState.SENT);
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(answered);
    assertEquals(ReadReceiptConflictException.ALREADY_HANDLED,
                 assertThrows(ReadReceiptConflictException.class,
                              () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.IGNORE, false)).getMessage());

    Email junk = incoming();
    junk.setFolder(MailFolder.JUNK);
    assertRefused(junk, ReadReceiptAction.IGNORE, ReadReceiptService.NOT_ALLOWED);
    Email own = incoming();
    own.setSender(new EmailSender(null, OWN_ADDRESS, null, null));
    assertRefused(own, ReadReceiptAction.SEND, ReadReceiptService.NOT_ALLOWED);

    // A request naming several addresses is never answered, by hand either: every one
    // would receive mail from the user's own account (review of #434, F1)
    Email several = incoming();
    several.setReadReceiptTo(SENDER + ", tracker@elsewhere.example");
    assertRefused(several, ReadReceiptAction.SEND, ReadReceiptService.NOT_ALLOWED);

    storedSettings(new ReadReceiptSettings(false, ReadReceiptPolicy.NEVER, false));
    assertRefused(incoming(), ReadReceiptAction.SEND, ReadReceiptService.NOT_ALLOWED);

    assertEquals(ReadReceiptService.INVALID_ACTION,
                 assertThrows(IllegalArgumentException.class, () -> readReceiptService.respond(EMAIL_ID, USER, null, false)).getMessage());
    verify(emailBoxStorage, never()).claimReadReceipt(anyString(), any(), any());
    verify(emailBoxService, never()).transmitAsUser(anyString(), any());
  }

  /**
   * Two answers racing: the one whose claim fails sends nothing and reports the
   * conflict.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void aLostClaimSendsNothing() throws Exception {
    Email email = incoming();
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    when(emailBoxStorage.claimReadReceipt(USER, email, ReadReceiptState.SENT)).thenReturn(false);

    assertThrows(ReadReceiptConflictException.class, () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, false));
    verify(emailBoxService, never()).transmitAsUser(anyString(), any());
    verify(emailBoxService, never()).openServerCopy(anyString(), any());
  }

  /**
   * A receipt that could not leave -- the transmitter failed before the server took it,
   * the connector is unusable, a lookup threw -- gives its claim back, so the user can
   * try again, and writes no keyword; one that may have left keeps its claim and sets
   * $MDNSent, so no second receipt follows from here or from another client.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void aFailedReceiptGivesItsClaimBackOnlyWhenNothingLeft() throws Exception {
    Email email = incoming();
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    when(emailBoxStorage.claimReadReceipt(USER, email, ReadReceiptState.SENT)).thenReturn(true);
    EmailBoxService.ServerCopy serverCopy = serverCopy();

    doThrow(new SmtpTransmitter.TransmissionException(SmtpTransmitter.Phase.CONNECT, new Exception("down"))).when(emailBoxService)
                                                                                                         .transmitAsUser(eq(USER), any());
    assertEquals(ReadReceiptService.SEND_FAILED,
                 assertThrows(IllegalStateException.class,
                              () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, false)).getMessage());
    verify(emailBoxStorage).releaseReadReceipt(USER, email, ReadReceiptState.SENT);

    doThrow(new SmtpTransmitter.TransmissionException(SmtpTransmitter.Phase.SEND, new Exception("lost"))).when(emailBoxService)
                                                                                                        .transmitAsUser(eq(USER), any());
    assertEquals(ReadReceiptService.UNCONFIRMED,
                 assertThrows(IllegalStateException.class,
                              () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, false)).getMessage());
    verify(emailBoxStorage).releaseReadReceipt(USER, email, ReadReceiptState.SENT);
    verify(serverCopy).addKeyword("$MDNSent");

    doThrow(IllegalAccessException.class).when(emailBoxService).transmitAsUser(eq(USER), any());
    assertThrows(IllegalAccessException.class, () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, false));
    verify(emailBoxStorage, org.mockito.Mockito.times(2)).releaseReadReceipt(USER, email, ReadReceiptState.SENT);

    doThrow(new NumberFormatException("connector id")).when(emailBoxService).transmitAsUser(eq(USER), any());
    assertEquals(ReadReceiptService.SEND_FAILED,
                 assertThrows(IllegalStateException.class,
                              () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, false)).getMessage());
    verify(emailBoxStorage, org.mockito.Mockito.times(3)).releaseReadReceipt(USER, email, ReadReceiptState.SENT);
    verify(serverCopy, org.mockito.Mockito.times(1)).addKeyword(anyString());
    // The answer store follows the rows: given back three times, kept for UNCONFIRMED.
    verify(answerStorage, org.mockito.Mockito.times(3)).release(USER, ANSWER_ID);
  }

  // ---------------------------------------------------------------------------------
  // The answer store (phase 2): an answer outlives the cached rows
  // ---------------------------------------------------------------------------------

  /**
   * The answer is decided by the answer store, keyed by user and Message-ID, before the
   * cached copies follow: the store's insert first, as a LOCAL answer, then the rows.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void theAnswerStoreDecidesBeforeTheRowsFollow() throws Exception {
    Email email = incoming();
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    when(emailBoxStorage.claimReadReceipt(USER, email, ReadReceiptState.IGNORED)).thenReturn(true);
    serverCopy();

    readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.IGNORE, false);

    InOrder order = org.mockito.Mockito.inOrder(answerStorage, emailBoxStorage);
    order.verify(answerStorage).claim(eq(USER), eq(MESSAGE_ID), eq(ReadReceiptState.IGNORED), eq(ReadReceiptAnswerOrigin.LOCAL), any());
    order.verify(emailBoxStorage).claimReadReceipt(USER, email, ReadReceiptState.IGNORED);
  }

  /**
   * The case the store exists for: the message's rows were re-created (a move, an
   * archive, a reset) on a mailbox that stores no keywords, so the row reads pending,
   * but the user answered before. Nothing is sent, the conflict is reported, and the
   * pending copies are brought in line with the stored answer.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void aStoredAnswerOutlivesItsRows() throws Exception {
    storedSettings(new ReadReceiptSettings(false, ReadReceiptPolicy.ALWAYS, false));
    Email email = incoming();
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    when(answerStorage.claim(eq(USER), eq(MESSAGE_ID), any(), any(), any())).thenReturn(null);
    when(answerStorage.findAnswers(USER, List.of(MESSAGE_ID))).thenReturn(Map.of(EmailReadReceiptAnswerStorage.messageIdHash(MESSAGE_ID),
                                                                                  ReadReceiptState.IGNORED));

    assertEquals(ReadReceiptConflictException.ALREADY_HANDLED,
                 assertThrows(ReadReceiptConflictException.class,
                              () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, true)).getMessage());
    verify(emailBoxStorage).claimReadReceipt(USER, email, ReadReceiptState.IGNORED);
    verify(emailBoxStorage, never()).claimReadReceipt(USER, email, ReadReceiptState.SENT);
    verify(emailBoxService, never()).openServerCopy(anyString(), any());
    verify(emailBoxService, never()).transmitAsUser(anyString(), any());
  }

  /**
   * The store took the answer, but a cached copy says the request was answered a moment
   * ago (the sync mirrored another client's $MDNSent): nothing is sent, and the store's
   * record stays -- the request is answered, whoever answered it.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void anAnsweredCopyStopsTheReceiptEvenWhenTheStoreLetItThrough() throws Exception {
    Email email = incoming();
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    when(emailBoxStorage.claimReadReceipt(USER, email, ReadReceiptState.SENT)).thenReturn(false);
    // Stubbed leniently although this call must never reach it: without the stub,
    // dropping the guard below fails on the mock's null server copy instead of on the
    // receipt that left, and the pin would be killing its mutant for the wrong reason.
    lenient().when(emailBoxService.openServerCopy(eq(USER), any(Email.class)))
             .thenReturn(mock(EmailBoxService.ServerCopy.class));

    assertThrows(ReadReceiptConflictException.class, () -> readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.SEND, false));
    verify(emailBoxService, never()).transmitAsUser(anyString(), any());
    verify(answerStorage, never()).release(anyString(), org.mockito.ArgumentMatchers.anyLong());
  }

  /**
   * A message that came with no Message-ID has nothing to be recognised by once its row
   * is gone: the store is not consulted, and its cached copies decide, as before the
   * store.
   *
   * @throws Exception when the mocked plumbing misbehaves
   */
  @Test
  void aMessageWithoutItsOwnMessageIdIsDecidedByItsRows() throws Exception {
    for (String messageId : java.util.Arrays.asList(null, " ")) {
      Email email = incoming();
      email.setMailHeaderId(messageId);
      when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
      when(emailBoxStorage.claimReadReceipt(USER, email, ReadReceiptState.IGNORED)).thenReturn(true);
      serverCopy();

      readReceiptService.respond(EMAIL_ID, USER, ReadReceiptAction.IGNORE, false);

      verify(emailBoxStorage).claimReadReceipt(USER, email, ReadReceiptState.IGNORED);
    }
    verify(answerStorage, never()).claim(anyString(), any(), any(), any(), any());
  }

  /**
   * The prompt reads the store too, once for the pending requests of a page: a request
   * answered before its row was re-created is not offered again, not even under
   * ALWAYS; one the store does not know is offered as before.
   */
  @Test
  void thePromptReadsTheAnswerStore() {
    storedSettings(new ReadReceiptSettings(false, ReadReceiptPolicy.ALWAYS, false));
    Email answered = incoming();
    Email fresh = incoming();
    fresh.setMailHeaderId("<fresh@partner.example>");
    Email plain = incoming();
    plain.setReadReceiptRequested(false);
    when(answerStorage.findAnswers(eq(USER), any())).thenReturn(Map.of(EmailReadReceiptAnswerStorage.messageIdHash(MESSAGE_ID),
                                                                       ReadReceiptState.SENT));

    readReceiptService.decorate(List.of(answered, fresh, plain), USER);

    assertEquals(ReadReceiptPrompt.NONE, answered.getReadReceiptPrompt());
    assertEquals(ReadReceiptPrompt.AUTO, fresh.getReadReceiptPrompt());
    assertEquals(ReadReceiptPrompt.NONE, plain.getReadReceiptPrompt());
    verify(answerStorage).findAnswers(USER, List.of(MESSAGE_ID, "<fresh@partner.example>"));
  }

  // ---------------------------------------------------------------------------------
  // What the reader says was done
  // ---------------------------------------------------------------------------------

  /**
   * A message whose copy carries the answer says which answer it was, and asks nothing
   * more: the line the reader shows where the banner was, for a receipt sent and for one
   * refused alike. Nothing is read for it -- the copy is the answer.
   */
  @Test
  void anAnswerOnTheRowIsSaidAsItStands() {
    Email sent = incoming();
    sent.setReadReceiptState(ReadReceiptState.SENT);
    Email ignored = incoming();
    ignored.setReadReceiptState(ReadReceiptState.IGNORED);

    readReceiptService.decorate(List.of(sent, ignored), USER);

    assertEquals(ReadReceiptPrompt.NONE, sent.getReadReceiptPrompt());
    assertEquals(ReadReceiptState.SENT, sent.getReadReceiptAnswer());
    assertEquals(ReadReceiptPrompt.NONE, ignored.getReadReceiptPrompt());
    assertEquals(ReadReceiptState.IGNORED, ignored.getReadReceiptAnswer());
    verify(answerStorage, never()).findAnswers(anyString(), any());
  }

  /**
   * The answer outlives the copy that carried it: a row the sync deleted and re-created
   * -- a move, an archive, a reset -- comes back with nothing on it, and the answer store
   * is what still says the request was answered, and how. That is the whole point of the
   * store, and the one thing a mailbox storing no keyword (Exchange) has.
   */
  @Test
  void anAnswerTheRowLostIsStillSaidByTheStore() {
    Email reborn = incoming();
    Email refused = incoming();
    refused.setMailHeaderId("<refused@partner.example>");
    Email pending = incoming();
    pending.setMailHeaderId("<pending@partner.example>");
    when(answerStorage.findAnswers(eq(USER), any())).thenReturn(Map.of(EmailReadReceiptAnswerStorage.messageIdHash(MESSAGE_ID),
                                                                       ReadReceiptState.SENT,
                                                                       EmailReadReceiptAnswerStorage.messageIdHash("<refused@partner.example>"),
                                                                       ReadReceiptState.IGNORED));

    readReceiptService.decorate(List.of(reborn, refused, pending), USER);

    assertNull(reborn.getReadReceiptState(), "the row lost it");
    assertEquals(ReadReceiptState.SENT, reborn.getReadReceiptAnswer());
    assertEquals(ReadReceiptState.IGNORED, refused.getReadReceiptAnswer());
    assertNull(pending.getReadReceiptAnswer(), "nothing was answered yet");
    assertEquals(ReadReceiptPrompt.ASK, pending.getReadReceiptPrompt());
  }

  /**
   * Nothing is said about a message that never carried a request, nor about this user's
   * own outgoing copies -- Sent, Drafts, the Scheduled view, a draft being written --
   * whose {@code readReceiptRequested} means "I asked", not "they ask". Not even when the
   * store holds an answer under that Message-ID, which a user who mails themselves has.
   */
  @Test
  void nothingIsSaidAboutAMessageThatAskedForNothingNorAboutOnesOwnMail() {
    lenient().when(answerStorage.findAnswers(eq(USER), any()))
             .thenReturn(Map.of(EmailReadReceiptAnswerStorage.messageIdHash(MESSAGE_ID), ReadReceiptState.SENT));
    Email plain = incoming();
    plain.setReadReceiptRequested(false);
    plain.setReadReceiptState(ReadReceiptState.SENT);
    List<Email> silent = new ArrayList<>(List.of(plain));
    for (String folder : List.of(MailFolder.SENT, MailFolder.DRAFTS, MailFolder.SCHEDULED)) {
      Email own = incoming();
      own.setFolder(folder);
      silent.add(own);
      Email answeredCopy = incoming();
      answeredCopy.setFolder(folder);
      answeredCopy.setReadReceiptState(ReadReceiptState.SENT);
      silent.add(answeredCopy);
    }
    Email draft = incoming();
    draft.setDraftLocalId("draft-1");
    silent.add(draft);

    readReceiptService.decorate(silent, USER);

    for (Email email : silent) {
      assertNull(email.getReadReceiptAnswer(), "nothing to say about " + email.getFolder() + "/" + email.getDraftLocalId());
      assertEquals(ReadReceiptPrompt.NONE, email.getReadReceiptPrompt());
    }
  }

  /**
   * A request answered before the message was moved to Junk or Trash still says what was
   * done: it is never offered there, but the answer travels with the message.
   */
  @Test
  void anAnswerFollowsAMessageIntoJunkAndTrash() {
    List<Email> moved = new ArrayList<>();
    for (String folder : List.of(MailFolder.JUNK, MailFolder.TRASH)) {
      Email email = incoming();
      email.setFolder(folder);
      email.setReadReceiptState(ReadReceiptState.IGNORED);
      moved.add(email);
    }

    readReceiptService.decorate(moved, USER);

    for (Email email : moved) {
      assertEquals(ReadReceiptState.IGNORED, email.getReadReceiptAnswer());
      assertEquals(ReadReceiptPrompt.NONE, email.getReadReceiptPrompt());
    }
  }

  /**
   * Whatever a caller left on the message before the read, the decoration is what stands:
   * an answer nobody gave is cleared, so a stale or forged value cannot survive a read.
   */
  @Test
  void aDecoratedMessageNeverKeepsAnAnswerItWasHandedIn() {
    Email plain = incoming();
    plain.setReadReceiptRequested(false);
    plain.setReadReceiptAnswer(ReadReceiptState.SENT);
    Email pending = incoming();
    pending.setReadReceiptAnswer(ReadReceiptState.IGNORED);
    when(answerStorage.findAnswers(eq(USER), any())).thenReturn(Map.of());

    readReceiptService.decorate(List.of(plain, pending), USER);

    assertNull(plain.getReadReceiptAnswer());
    assertNull(pending.getReadReceiptAnswer());
    assertEquals(ReadReceiptPrompt.ASK, pending.getReadReceiptPrompt());
  }

  // ---------------------------------------------------------------------------------
  // Preferences
  // ---------------------------------------------------------------------------------

  /** Never chosen: no request by default, ASK, ALWAYS allowed. */
  @Test
  void thePreferencesDefaultToTheCautiousOnes() {
    ReadReceiptSettings settings = readReceiptService.getSettings(USER);
    assertFalse(settings.isRequestByDefault());
    assertEquals(ReadReceiptPolicy.ASK, settings.getResponsePolicy());
    assertTrue(settings.isAlwaysAllowed());
  }

  /**
   * The preferences are stored as their own document (never alwaysAllowed), a missing
   * policy as ASK, and ALWAYS is refused while the administrator disables it.
   */
  @Test
  void thePreferencesAreStoredApartAndAlwaysCanBeRefused() {
    readReceiptService.saveSettings(USER, new ReadReceiptSettings(true, null, true));
    ArgumentCaptor<SettingValue<?>> stored = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(eq(Context.USER.id(USER)),
                               eq(UserEmailSettingService.EMAIL_CONNECTOR_SCOPE),
                               eq(ReadReceiptService.SETTINGS_KEY),
                               stored.capture());
    ReadReceiptSettings written = JsonUtils.fromJsonString(stored.getValue().getValue().toString(), ReadReceiptSettings.class);
    assertTrue(written.isRequestByDefault());
    assertEquals(ReadReceiptPolicy.ASK, written.getResponsePolicy());
    assertFalse(written.isAlwaysAllowed(), "the administrator's answer is never stored");

    System.setProperty(ReadReceiptService.ALLOW_ALWAYS_PROPERTY, "false");
    assertEquals(ReadReceiptService.NOT_ALLOWED,
                 assertThrows(IllegalArgumentException.class,
                              () -> readReceiptService.saveSettings(USER,
                                                                    new ReadReceiptSettings(false, ReadReceiptPolicy.ALWAYS, true))).getMessage());
    assertThrows(IllegalArgumentException.class, () -> readReceiptService.saveSettings(USER, null));
  }

  // ---------------------------------------------------------------------------------
  // Fixtures
  // ---------------------------------------------------------------------------------

  /**
   * A received message whose request everything vouches for: one address, matching
   * its Return-Path, the user in To, no list, typed by a person.
   *
   * @return the message
   */
  private Email incoming() {
    Email email = new Email();
    email.setId(EMAIL_ID);
    email.setMailRemoteId(34L);
    email.setUserId(USER);
    email.setFolder(MailFolder.INBOX);
    email.setMailHeaderId("<original@partner.example>");
    email.setMailReferences("<root@partner.example>");
    email.setSubject("Quarterly figures");
    email.setSender(new EmailSender("Bob", SENDER, null, null));
    email.setTo(List.of(recipient(OWN_ADDRESS)));
    email.setReadReceiptRequested(true);
    email.setReadReceiptTo("Bob <" + SENDER + ">");
    email.setReadReceiptReturnPathMatch(true);
    return email;
  }

  /**
   * A recipient.
   *
   * @param address the address
   * @return the recipient
   */
  private EmailRecipient recipient(String address) {
    return new EmailRecipient(null, address, null, false);
  }

  /**
   * The prompt of a message under a policy, ALWAYS allowed.
   *
   * @param email the message
   * @param policy the policy
   * @return the prompt
   */
  private ReadReceiptPrompt prompt(Email email, ReadReceiptPolicy policy) {
    return readReceiptService.promptFor(email, OWN_ADDRESS, new ReadReceiptSettings(false, policy, true));
  }

  /**
   * Stores preferences in the mocked setting service.
   *
   * @param settings the preferences
   */
  private void storedSettings(ReadReceiptSettings settings) {
    lenient().when(settingService.get(Context.USER.id(USER), UserEmailSettingService.EMAIL_CONNECTOR_SCOPE, ReadReceiptService.SETTINGS_KEY))
             .thenAnswer(invocation -> SettingValue.create(JsonUtils.toJsonString(settings)));
  }

  /**
   * Refusal of an answer on a message, with its code.
   *
   * @param email the message the service loads
   * @param action the answer
   * @param code the expected code
   * @throws Exception when the mocked plumbing misbehaves
   */
  private void assertRefused(Email email, ReadReceiptAction action, String code) throws Exception {
    when(emailBoxService.getOwnedEmailById(EMAIL_ID, USER)).thenReturn(email);
    assertEquals(code,
                 assertThrows(IllegalArgumentException.class, () -> readReceiptService.respond(EMAIL_ID, USER, action, false)).getMessage());
  }

  /**
   * A server copy the service may open.
   *
   * @return the mocked copy
   */
  private EmailBoxService.ServerCopy serverCopy() {
    EmailBoxService.ServerCopy serverCopy = mock(EmailBoxService.ServerCopy.class);
    when(emailBoxService.openServerCopy(eq(USER), any(Email.class))).thenReturn(serverCopy);
    return serverCopy;
  }

  /**
   * Runs the factory handed to the transmitting door on a real session and keeps what
   * it built.
   *
   * @return the messages built, in order
   * @throws Exception when the mocked plumbing misbehaves
   */
  private List<MimeMessage> captureTransmissions() throws Exception {
    List<MimeMessage> transmitted = new ArrayList<>();
    doAnswer(invocation -> {
      EmailBoxService.OutgoingMessageFactory factory = invocation.getArgument(1);
      transmitted.add(factory.build(session(), new InternetAddress(OWN_ADDRESS)));
      return null;
    }).when(emailBoxService).transmitAsUser(eq(USER), any());
    return transmitted;
  }

  /**
   * A session to build messages on.
   *
   * @return the session
   */
  private static Session session() {
    return Session.getInstance(new Properties());
  }

  /**
   * Reads a stream as ASCII text.
   *
   * @param stream the stream
   * @return the text
   * @throws Exception when it cannot be read
   */
  private static String read(InputStream stream) throws Exception {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    stream.transferTo(bytes);
    assertNotNull(bytes);
    return bytes.toString(StandardCharsets.US_ASCII);
  }
}
