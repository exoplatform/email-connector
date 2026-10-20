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
package org.exoplatform.emailConnector.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ScheduledSendConflictException;
import org.exoplatform.emailConnector.mcp.model.EmailDraftModel;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.EmailSignature;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.EmailDelegationService;
import org.exoplatform.emailConnector.service.EmailSignatureService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

/**
 * The draft tools (EXO-91674): an assistant prepares mail as drafts of the user's own
 * mailbox, which the user reviews, edits and sends -- or has sent as they now stand.
 */
class EmailMcpToolDraftsTest {

  private static final String   USERNAME   = "testuser1";

  private static final String   DRAFT_ID   = "draft-1";

  /** A signature whose logo is a portal address, which the body cleaning would drop. */
  private static final String   SIGNATURE  = "<p>Jane Doe, eXo</p><img src=\"/email-connector/rest/user-email-setting/signature/image?v=1\">";

  private static final String   SIGNATURE_BLOCK = "<div class=\"ec-signature\">" + SIGNATURE + "</div>";

  private static final String   LINK       = "https://exo.example.com/portal/dw?openEmailBox=true&folder=DRAFTS";

  private EmailBoxService       emailBoxService;

  private EmailSignatureService emailSignatureService;

  private EmailMcpTool          emailMcpTool;

  /**
   * A tool over mocked services, acting as {@link #USERNAME}, with a fixed drafts link
   * and the user's signature switched on.
   */
  @BeforeEach
  void setUp() {
    emailBoxService = Mockito.mock(EmailBoxService.class);
    emailSignatureService = Mockito.mock(EmailSignatureService.class);
    emailMcpTool = new EmailMcpTool(emailBoxService,
                                    Mockito.mock(UserEmailSettingService.class),
                                    Mockito.mock(EmailDelegationService.class),
                                    emailSignatureService) {
      @Override
      public String getCurrentUserName() {
        return USERNAME;
      }

      @Override
      String draftsLink() {
        return LINK;
      }
    };
    when(emailSignatureService.getEmailSignature(USERNAME)).thenReturn(new EmailSignature(null, SIGNATURE, null, false, null));
  }

  /**
   * A stored draft of the user's own mailbox.
   *
   * @param subject its subject
   * @param body its HTML body
   * @param to its recipients
   * @return the draft
   */
  private static Email storedDraft(String subject, String body, String... to) {
    Email draft = new Email();
    draft.setDraftLocalId(DRAFT_ID);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setSubject(subject);
    draft.setContent(new EmailContent(body, null, null));
    draft.getContent().setHtml(true);
    draft.setTo(recipients(to));
    draft.setCc(List.of());
    draft.setBcc(List.of());
    draft.setMailHeaderId("<draft-mid@example.com>");
    draft.setThreadId("thread-9");
    draft.setDraftUpdatedDate(new Date());
    draft.setDraftRevision(1L);
    return draft;
  }

  /**
   * Recipients of the given addresses.
   *
   * @param addresses the addresses
   * @return the recipients
   */
  private static List<EmailRecipient> recipients(String... addresses) {
    return java.util.Arrays.stream(addresses).map(address -> new EmailRecipient(null, address, null, false)).toList();
  }

  /**
   * The draft saveDraft was handed, after checking it was pushed to the mail server.
   *
   * @return the saved draft
   * @throws Exception never, the service is mocked
   */
  private Email savedDraft() throws Exception {
    ArgumentCaptor<Email> captor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxService).saveDraft(captor.capture(), eq(USERNAME), eq(true));
    return captor.getValue();
  }

  /**
   * Makes saveDraft answer with a stored row saying what it was given, carrying a
   * minted Message-ID on a first save, as the service does.
   *
   * @throws Exception never, the service is mocked
   */
  private void saveDraftEchoes() throws Exception {
    when(emailBoxService.saveDraft(any(Email.class), eq(USERNAME), eq(true))).thenAnswer(invocation -> {
      Email draft = invocation.getArgument(0);
      Email stored = storedDraft(draft.getSubject(), draft.getContent().getBody());
      stored.setTo(draft.getTo());
      stored.setCc(draft.getCc());
      stored.setBcc(draft.getBcc());
      if (draft.getDraftLocalId() == null) {
        stored.setMailHeaderId("<minted@example.com>");
      }
      return stored;
    });
  }

  // --- create_email_draft ----------------------------------------------------

  /** A new draft is saved and pushed with the user's signature under the body. */
  @Test
  void createEmailDraftSavesASignedDraftAndReturnsItsHandles() throws Exception {
    saveDraftEchoes();

    EmailDraftModel model = emailMcpTool.createEmailDraft(List.of("bob@acme.com"),
                                                          "Hello",
                                                          "<p>Hi Bob</p>",
                                                          List.of("carol@acme.com"),
                                                          null,
                                                          null,
                                                          null);

    Email saved = savedDraft();
    assertEquals("Hello", saved.getSubject());
    assertEquals("bob@acme.com", saved.getTo().get(0).getAddress());
    assertEquals("carol@acme.com", saved.getCc().get(0).getAddress());
    assertEquals("<p>Hi Bob</p><br><br>" + SIGNATURE_BLOCK, saved.getContent().getBody());
    assertTrue(saved.getContent().isHtml());
    assertEquals(null, saved.getMailHeaderId(), "a new mail threads under nothing");
    assertEquals(DRAFT_ID, model.getDraftId());
    assertEquals("<minted@example.com>", model.getMessageId());
    assertEquals(LINK, model.getLink());
    String json = new ObjectMapper().writeValueAsString(model);
    assertTrue(json.contains("\"draft_id\":\"draft-1\"") && json.contains("\"message_id\""), json);
  }

  /** No signature when the caller asks for none, nor when the user turned theirs off. */
  @Test
  void createEmailDraftLeavesTheSignatureOutWhenAskedOrSwitchedOff() throws Exception {
    saveDraftEchoes();
    emailMcpTool.createEmailDraft(List.of("bob@acme.com"), "Hello", "<p>Hi</p>", null, null, null, false);
    assertEquals("<p>Hi</p>", savedDraft().getContent().getBody());

    Mockito.clearInvocations(emailBoxService);
    when(emailSignatureService.getEmailSignature(USERNAME)).thenReturn(new EmailSignature(false, SIGNATURE, null, false, null));
    emailMcpTool.createEmailDraft(List.of("bob@acme.com"), "Hello", "<p>Hi</p>", null, null, null, null);
    assertEquals("<p>Hi</p>", savedDraft().getContent().getBody());
  }

  /**
   * A reply draft hands the service the parent's Message-ID, which it turns into
   * In-Reply-To and References at the first save, and answers the parent's sender.
   */
  @Test
  void createEmailDraftAsAReplyThreadsUnderTheMailAndAnswersItsSender() throws Exception {
    saveDraftEchoes();
    Email row = new Email();
    row.setId(42L);
    row.setMailRemoteId(777L);
    row.setFolder(MailFolder.INBOX);
    when(emailBoxService.getOwnMailboxEmailById(42L, USERNAME)).thenReturn(row);
    Email original = new Email();
    original.setId(42L);
    original.setMailHeaderId("<parent@acme.com>");
    original.setSubject("Your offer");
    original.setSender(new EmailSender("Alice", "alice@acme.com", null, null));
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(777L),
                                                         eq(USERNAME),
                                                         eq(MailFolder.INBOX),
                                                         anyBoolean(),
                                                         anyBoolean(),
                                                         anyBoolean(),
                                                         anyBoolean())).thenReturn(original);

    emailMcpTool.createEmailDraft(null, null, "<p>Thanks</p>", null, null, 42L, false);

    Email saved = savedDraft();
    assertEquals("<parent@acme.com>", saved.getMailHeaderId());
    assertEquals("Re: Your offer", saved.getSubject());
    assertEquals(List.of("alice@acme.com"), saved.getTo().stream().map(EmailRecipient::getAddress).toList());
  }

  /** A new mail with no recipient, or a recipient that is not a bare address, is refused. */
  @Test
  void createEmailDraftRefusesAMailWithNoRecipientOrANamedAddress() throws Exception {
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.createEmailDraft(List.of(" "), "Hello", "<p>Hi</p>", null, null, null, null));
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.createEmailDraft(List.of("Bob <bob@acme.com>"), "Hello", "<p>Hi</p>", null, null, null, null));
    verify(emailBoxService, never()).saveDraft(any(), anyString(), anyBoolean());
  }

  /**
   * A body that would load anything from the internet when the user opens the draft --
   * an image, a background, a CSS address -- is refused and nothing is saved: a draft is
   * shown to its owner without the cleaning received mail gets.
   */
  @Test
  void createEmailDraftRefusesABodyThatLoadsRemoteContent() throws Exception {
    for (String body : List.of("<p>Hi</p><img src=\"https://evil.example/t.gif?d=secret\">",
                               "<p style=\"background:url(https://evil.example/t.gif)\">Hi</p>",
                               "<table background=\"https://evil.example/t.gif\"><tr><td>Hi</td></tr></table>")) {
      IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                      () -> emailMcpTool.createEmailDraft(List.of("bob@acme.com"), "Hello", body, null, null, null, null),
                                                      body);
      assertTrue(refusal.getMessage().contains("Nothing was saved"), refusal.getMessage());
    }
    verify(emailBoxService, never()).saveDraft(any(), anyString(), anyBoolean());
  }

  /** What the body may carry is kept; what would run is taken out. */
  @Test
  void createEmailDraftKeepsFormattingAndLinksAndDropsScripts() throws Exception {
    saveDraftEchoes();
    emailMcpTool.createEmailDraft(List.of("bob@acme.com"),
                                  "Hello",
                                  "<p><b>Hi</b> <a href=\"https://www.exoplatform.com\">eXo</a></p><script>alert(1)</script>",
                                  null,
                                  null,
                                  null,
                                  false);
    String body = savedDraft().getContent().getBody();
    assertTrue(body.contains("<b>Hi</b>") && body.contains("href=\"https://www.exoplatform.com\""), body);
    assertFalse(body.contains("script"), body);
  }

  // --- update_email_draft ----------------------------------------------------

  /** Only the fields given change: the user's own edits to the others are kept. */
  @Test
  void updateEmailDraftKeepsWhatTheUserEditedInTheFieldsNotGiven() throws Exception {
    saveDraftEchoes();
    Email stored = storedDraft("Hello", "<p>Edited by hand</p>", "bob@acme.com");
    stored.setCc(recipients("carol@acme.com"));
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(stored);

    emailMcpTool.updateEmailDraft(DRAFT_ID, null, "New subject", null, null, null, 1L);

    Email saved = savedDraft();
    assertEquals(DRAFT_ID, saved.getDraftLocalId());
    assertEquals("New subject", saved.getSubject());
    assertEquals("<p>Edited by hand</p>", saved.getContent().getBody());
    assertEquals("bob@acme.com", saved.getTo().get(0).getAddress());
    assertEquals("carol@acme.com", saved.getCc().get(0).getAddress());
  }

  /**
   * The save carries the revision after the one read, and a save the storage dropped --
   * the app saved the draft meanwhile -- is reported, never answered as done.
   */
  @Test
  void updateEmailDraftNeverPutsBackWhatTheUserChangedMeanwhile() throws Exception {
    Email stored = storedDraft("Hello", "<p>Hi</p>", "bob@acme.com");
    stored.setDraftRevision(4L);
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(stored);
    Email editedInTheApp = storedDraft("Hello", "<p>The user's newer words</p>", "bob@acme.com");
    when(emailBoxService.saveDraft(any(Email.class), eq(USERNAME), eq(true))).thenReturn(editedInTheApp);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailMcpTool.updateEmailDraft(DRAFT_ID, null, "New subject", null, null, null, 4L));
    assertTrue(refusal.getMessage().contains("nothing of your change was saved"), refusal.getMessage());
    assertEquals(5L, savedDraft().getDraftRevision());
  }

  /** A draft saved since the revision the agent read is refused, and nothing is saved. */
  @Test
  void updateEmailDraftRefusesADraftSavedSinceItWasRead() throws Exception {
    Email stored = storedDraft("Hello", "<p>The user's newer words</p>", "bob@acme.com");
    stored.setDraftRevision(7L);
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(stored);

    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.updateEmailDraft(DRAFT_ID, null, null, "<p>Built from an older read</p>", null, null, 6L));
    verify(emailBoxService, never()).saveDraft(any(), anyString(), anyBoolean());

    saveDraftEchoes();
    emailMcpTool.updateEmailDraft(DRAFT_ID, null, null, "<p>Built from this read</p>", null, null, 7L);
    assertEquals("<p>Built from this read</p>", savedDraft().getContent().getBody());
  }

  /**
   * A whole body given back keeps the user's signature block as it is, logo included,
   * while the text the model wrote around it is cleaned like a new body.
   */
  @Test
  void updateEmailDraftKeepsTheSignatureAndCleansTheRest() throws Exception {
    saveDraftEchoes();
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(storedDraft("Hello", "<p>Hi</p>", "bob@acme.com"));

    emailMcpTool.updateEmailDraft(DRAFT_ID, null, null, "<p>New words</p><br><br>" + SIGNATURE_BLOCK, null, null, 1L);
    assertEquals("<p>New words</p><br><br>" + SIGNATURE_BLOCK, savedDraft().getContent().getBody());

    Mockito.clearInvocations(emailBoxService);
    String embedded = "<p>Logo</p><img src=\"data:image/png;base64,AAAA\">";
    emailMcpTool.updateEmailDraft(DRAFT_ID, null, null, embedded, null, null, 1L);
    assertEquals(embedded, savedDraft().getContent().getBody(), "an image the cleaning keeps is kept");

    Mockito.clearInvocations(emailBoxService);
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.updateEmailDraft(DRAFT_ID, null, null, "<img src=\"https://evil.example/t.gif\">" + SIGNATURE_BLOCK, null, null, 1L));
    verify(emailBoxService, never()).saveDraft(any(), anyString(), anyBoolean());
  }

  /**
   * A body the user wrote in the Email app is never stored poorer than they left it: an
   * image they pasted, a quoted mail's remote image, make it refused, nothing saved.
   */
  @Test
  void updateEmailDraftRefusesABodyItWouldStripOfTheUsersContent() throws Exception {
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(storedDraft("Hello", "<p>Hi</p>", "bob@acme.com"));
    for (String body : List.of("<p>See below</p><img src=\"/email-connector/rest/email-box/drafts/draft-1/attachments/3\">",
                               "<table><tr><td background=\"/portal/rest/banner.png\">Hi</td></tr></table>",
                               "<table><tr><td background=\"cid:logo@acme.com\">Hi</td></tr></table>",
                               "<p>Hi</p><img src=\"data:application/octet-stream;base64,AAAA\">",
                               "<p>Hi</p><img src=\" cid:logo@acme.com\">",
                               "<p style=\"background-image:u\\72l('/portal/rest/banner.png')\">Hi</p>",
                               "<p style=\"background:image-set('/portal/rest/banner.png' 1x)\">Hi</p>",
                               "<p style=\"background-image:url('/portal/rest/banner.png')\">Hi</p>",
                               "<style>p { background: url(/portal/rest/banner.png) }</style><p>Hi</p>",
                               "<p>Thanks</p><blockquote><img src=\"https://news.acme.com/banner.png\"></blockquote>")) {
      IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                      () -> emailMcpTool.updateEmailDraft(DRAFT_ID, null, null, body, null, null, 1L),
                                                      body);
      assertTrue(refusal.getMessage().contains("Email app"), refusal.getMessage());
    }
    verify(emailBoxService, never()).saveDraft(any(), anyString(), anyBoolean());
  }

  /** A change without the revision it was read at changes nothing. */
  @Test
  void updateEmailDraftRequiresTheRevisionItWasReadAt() throws Exception {
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(storedDraft("Hello", "<p>Hi</p>", "bob@acme.com"));
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.updateEmailDraft(DRAFT_ID, null, "x", null, null, null, null));
    verify(emailBoxService, never()).saveDraft(any(), anyString(), anyBoolean());
  }

  /** One draft is answered with its whole HTML body, the base of the next change. */
  @Test
  void updateEmailDraftAnswersTheWholeHtmlBody() throws Exception {
    saveDraftEchoes();
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(storedDraft("Hello", "<p><b>Bold</b> words</p>", "bob@acme.com"));

    EmailDraftModel model = emailMcpTool.updateEmailDraft(DRAFT_ID, null, "New subject", null, null, null, 1L);

    assertEquals("<p><b>Bold</b> words</p>", model.getBodyHtml());
  }

  /**
   * A draft of somebody else is not found: the lookup is the caller's own; a scheduled
   * draft and a draft of a shared mailbox are refused; nothing is saved.
   */
  @Test
  void updateEmailDraftActsOnlyOnTheUsersOwnUnscheduledDrafts() throws Exception {
    assertThrows(ObjectNotFoundException.class, () -> emailMcpTool.updateEmailDraft("other", null, "x", null, null, null, 1L));
    verify(emailBoxService).getDraft("other", USERNAME);

    Email scheduled = storedDraft("Hello", "<p>Hi</p>", "bob@acme.com");
    scheduled.setScheduled(true);
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(scheduled);
    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailMcpTool.updateEmailDraft(DRAFT_ID, null, "x", null, null, null, 1L));
    assertTrue(refusal.getMessage().contains("scheduled"), refusal.getMessage());

    Email shared = storedDraft("Hello", "<p>Hi</p>", "bob@acme.com");
    shared.setSendDelegationId(5L);
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(shared);
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.updateEmailDraft(DRAFT_ID, null, "x", null, null, null, 1L));

    verify(emailBoxService, never()).saveDraft(any(), anyString(), anyBoolean());
  }

  /** A draft scheduled between the read and the save is refused in words. */
  @Test
  void updateEmailDraftExplainsAScheduleThatLandedMeanwhile() throws Exception {
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(storedDraft("Hello", "<p>Hi</p>", "bob@acme.com"));
    when(emailBoxService.saveDraft(any(), eq(USERNAME), eq(true))).thenThrow(new ScheduledSendConflictException(ScheduledSendConflictException.LOCKED));

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailMcpTool.updateEmailDraft(DRAFT_ID, null, "x", null, null, null, 1L));
    assertTrue(refusal.getMessage().contains("scheduled"), refusal.getMessage());
  }

  // --- list_email_drafts -----------------------------------------------------

  /**
   * The user's own drafts, newest first, filtered by subject, recipient and age; a draft
   * of a shared mailbox is never listed.
   */
  @Test
  void listEmailDraftsFiltersAndLeavesSharedMailboxDraftsOut() throws Exception {
    Email older = storedDraft("Offer for Acme", "<p>a</p>", "bob@acme.com");
    older.setDraftLocalId("older");
    older.setDraftUpdatedDate(new Date(System.currentTimeMillis() - 3L * 24 * 3600 * 1000));
    Email newer = storedDraft("Offer for Globex", "<p>b</p>", "dan@globex.com");
    newer.setDraftLocalId("newer");
    Email shared = storedDraft("Offer for Acme too", "<p>c</p>", "bob@acme.com");
    shared.setDraftLocalId("shared");
    shared.setSendDelegationId(5L);
    EmailBox box = new EmailBox();
    box.setEmails(List.of(older, shared, newer));
    when(emailBoxService.getEmailBox(USERNAME, MailFolder.DRAFTS)).thenReturn(box);

    assertEquals(List.of("newer", "older"),
                 emailMcpTool.listEmailDrafts(null, null, null, null, null).stream().map(EmailDraftModel::getDraftId).toList());
    assertEquals(List.of("older"),
                 emailMcpTool.listEmailDrafts("acme", null, null, null, null).stream().map(EmailDraftModel::getDraftId).toList());
    assertEquals(List.of("newer"),
                 emailMcpTool.listEmailDrafts(null, "GLOBEX", null, null, null).stream().map(EmailDraftModel::getDraftId).toList());
    assertEquals(List.of("newer"),
                 emailMcpTool.listEmailDrafts(null, null, 1, null, null).stream().map(EmailDraftModel::getDraftId).toList());
    assertEquals(1, emailMcpTool.listEmailDrafts(null, null, null, 1, null).size());
    assertEquals(null, emailMcpTool.listEmailDrafts(null, null, null, 1, null).get(0).getBodyHtml());
    assertEquals("<p>b</p>", emailMcpTool.listEmailDrafts(null, null, null, 1, true).get(0).getBodyHtml());
    newer.setDraftRevision(3L);
    EmailDraftModel listed = emailMcpTool.listEmailDrafts(null, null, null, 1, null).get(0);
    assertEquals(3L, listed.getRevision(), "the revision update_email_draft is given back");
    assertTrue(new ObjectMapper().writeValueAsString(listed).contains("\"revision\":3"));
  }

  // --- send_email_draft ------------------------------------------------------

  /**
   * The draft is sent as stored, through the service's send-as-stored, with the
   * arguments checked again on the row under its lock; the answer gives the sent mail's
   * Message-ID and conversation.
   */
  @Test
  void sendEmailDraftSendsTheStoredVersionAndNamesTheSentMail() throws Exception {
    Email stored = storedDraft("Hello", "<p>Edited by hand</p>", "bob@acme.com");
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(stored);
    when(emailBoxService.sendDraftAsStored(eq(DRAFT_ID), eq(USERNAME), any())).thenAnswer(invocation -> {
      Consumer<Email> check = invocation.getArgument(2);
      check.accept(stored);
      return stored;
    });

    String answer = emailMcpTool.sendEmailDraft(DRAFT_ID, List.of("BOB@acme.com"), "Hello", null, null);

    verify(emailBoxService).sendDraftAsStored(eq(DRAFT_ID), eq(USERNAME), any());
    verify(emailBoxService, never()).sendDraft(any(), anyString(), any(), any());
    assertTrue(answer.contains("<draft-mid@example.com>") && answer.contains("thread-9"), answer);
  }

  /**
   * Arguments that are not what the draft says -- before the send, or by the time the
   * draft's lock is held -- send nothing.
   */
  @Test
  void sendEmailDraftSendsNothingWhenTheDraftNoLongerSaysWhatWasApproved() throws Exception {
    Email stored = storedDraft("Hello", "<p>Hi</p>", "bob@acme.com");
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(stored);

    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.sendEmailDraft(DRAFT_ID, List.of("eve@evil.com"), "Hello", null, null));
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.sendEmailDraft(DRAFT_ID, List.of("bob@acme.com"), "Other subject", null, null));
    stored.setBcc(recipients("boss@acme.com"));
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.sendEmailDraft(DRAFT_ID, List.of("bob@acme.com"), "Hello", null, null));
    verify(emailBoxService, never()).sendDraftAsStored(anyString(), anyString(), any());

    Email edited = storedDraft("Hello", "<p>Hi</p>", "bob@acme.com", "new@acme.com");
    stored.setBcc(List.of());
    when(emailBoxService.sendDraftAsStored(eq(DRAFT_ID), eq(USERNAME), any())).thenAnswer(invocation -> {
      Consumer<Email> check = invocation.getArgument(2);
      check.accept(edited);
      return edited;
    });
    IllegalArgumentException refusal =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.sendEmailDraft(DRAFT_ID, List.of("bob@acme.com"), "Hello", null, null));
    assertTrue(refusal.getMessage().contains("new@acme.com"), refusal.getMessage());

    Email scheduledMeanwhile = storedDraft("Hello", "<p>Hi</p>", "bob@acme.com");
    scheduledMeanwhile.setScheduled(true);
    when(emailBoxService.sendDraftAsStored(eq(DRAFT_ID), eq(USERNAME), any())).thenAnswer(invocation -> {
      Consumer<Email> check = invocation.getArgument(2);
      check.accept(scheduledMeanwhile);
      return scheduledMeanwhile;
    });
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.sendEmailDraft(DRAFT_ID, List.of("bob@acme.com"), "Hello", null, null));
  }

  // --- delete_email_draft ----------------------------------------------------

  /** The draft is deleted when the subject named is its own, and not otherwise. */
  @Test
  void deleteEmailDraftDeletesOnlyTheDraftItNames() throws Exception {
    Email stored = storedDraft("Hello", "<p>Hi</p>", "bob@acme.com");
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(stored);
    deleteDraftChecks(stored);

    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.deleteEmailDraft(DRAFT_ID, "Another", List.of("bob@acme.com")));
    verify(emailBoxService, never()).deleteDraft(anyString(), anyString(), any());

    assertTrue(emailMcpTool.deleteEmailDraft(DRAFT_ID, " Hello ", List.of("bob@acme.com")).contains("deleted"));
    verify(emailBoxService).deleteDraft(eq(DRAFT_ID), eq(USERNAME), any());
  }

  /**
   * A draft scheduled, or retitled, in the Email app between the read and the delete is
   * refused on the row under the draft's lock: deleting it would cancel the user's
   * schedule, or delete a draft the user did not approve.
   */
  @Test
  void deleteEmailDraftChecksTheRowAgainUnderTheDraftsLock() throws Exception {
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(storedDraft("Hello", "<p>Hi</p>", "bob@acme.com"));
    Email scheduledMeanwhile = storedDraft("Hello", "<p>Hi</p>", "bob@acme.com");
    scheduledMeanwhile.setScheduled(true);
    deleteDraftChecks(scheduledMeanwhile);
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.deleteEmailDraft(DRAFT_ID, "Hello", List.of("bob@acme.com")));

    deleteDraftChecks(storedDraft("Retitled", "<p>Hi</p>", "bob@acme.com"));
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.deleteEmailDraft(DRAFT_ID, "Hello", List.of("bob@acme.com")));

    deleteDraftChecks(storedDraft("Hello", "<p>Hi</p>", "dan@globex.com"));
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.deleteEmailDraft(DRAFT_ID, "Hello", List.of("bob@acme.com")));
  }

  /**
   * Makes deleteDraft run the caller's check on the given row, as the service does under
   * the draft's lock, and delete when it passes.
   *
   * @param row the row as stored under the lock
   * @throws Exception never, the service is mocked
   */
  private void deleteDraftChecks(Email row) throws Exception {
    when(emailBoxService.deleteDraft(eq(DRAFT_ID), eq(USERNAME), any())).thenAnswer(invocation -> {
      Consumer<Email> check = invocation.getArgument(2);
      check.accept(row);
      return true;
    });
  }

  /** A scheduled draft is not deleted here, which would cancel the user's schedule. */
  @Test
  void deleteEmailDraftLeavesAScheduledDraftAlone() throws Exception {
    Email scheduled = storedDraft("Hello", "<p>Hi</p>", "bob@acme.com");
    scheduled.setScheduled(true);
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(scheduled);

    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.deleteEmailDraft(DRAFT_ID, "Hello", List.of("bob@acme.com")));
    verify(emailBoxService, never()).deleteDraft(anyString(), anyString(), any());
  }
}
