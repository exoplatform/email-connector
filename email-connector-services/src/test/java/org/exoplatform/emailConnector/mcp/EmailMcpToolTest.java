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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.mcp.model.EmailAccountModel;
import org.exoplatform.emailConnector.mcp.model.EmailAttachmentModel;
import org.exoplatform.emailConnector.mcp.model.EmailModel;
import org.exoplatform.emailConnector.mcp.model.EmailSearchHitModel;
import org.exoplatform.emailConnector.mcp.model.EmailSearchResultsModel;
import org.exoplatform.emailConnector.mcp.model.EmailThreadMessageModel;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSearchResult;
import org.exoplatform.emailConnector.model.EmailSearchResultPage;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.mcp.model.SharedMailboxModel;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.SharedMailboxEntry;
import org.exoplatform.emailConnector.exception.DelegationRevokedException;
import org.exoplatform.emailConnector.exception.MailboxRightMissingException;
import org.exoplatform.emailConnector.model.SharedMailboxFolder;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.EmailDelegationService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

class EmailMcpToolTest {

  private static final String     USERNAME  = "testuser1";

  private static final long       EMAIL_ID  = 42L;

  private static final long       REMOTE_ID = 777L;

  private EmailBoxService         emailBoxService;

  private UserEmailSettingService userEmailSettingService;

  private EmailDelegationService  emailDelegationService;

  private EmailMcpTool            emailMcpTool;

  @BeforeEach
  void setUp() {
    emailBoxService = Mockito.mock(EmailBoxService.class);
    userEmailSettingService = Mockito.mock(UserEmailSettingService.class);
    emailDelegationService = Mockito.mock(EmailDelegationService.class);
    emailMcpTool = new EmailMcpTool(emailBoxService, userEmailSettingService, emailDelegationService) {
      @Override
      public String getCurrentUserName() {
        return USERNAME;
      }
    };
  }

  private Email buildEmail(long id) {
    Email email = new Email();
    email.setId(id);
    email.setMailRemoteId(REMOTE_ID);
    email.setFolder(MailFolder.INBOX);
    email.setUserId(USERNAME);
    email.setUserEmail("testuser1@example.com");
    email.setSubject("Hello");
    email.setThreadId("thread-1");
    EmailContent content = new EmailContent();
    content.setBody("<p>Hello <b>world</b></p>");
    email.setContent(content);
    return email;
  }

  // --- get_email_by_id -----------------------------------------------------

  @Test
  void getEmailById() throws Exception {
    when(emailBoxService.getOwnMailboxEmailById(eq(EMAIL_ID), eq(USERNAME))).thenReturn(buildEmail(EMAIL_ID));

    EmailModel model = emailMcpTool.getEmailById(EMAIL_ID, null);

    assertNotNull(model);
    assertEquals(EMAIL_ID, model.getId());
    assertEquals("Hello", model.getSubject());
    // mailRemoteId is now surfaced so write tools can be chained
    assertEquals(REMOTE_ID, model.getMailRemoteId());
    // Body HTML is stripped down to plain text
    assertEquals("Hello world", model.getContent().getBody());
  }

  @Test
  void getEmailByIdNotFoundFails() throws Exception {
    when(emailBoxService.getOwnMailboxEmailById(eq(EMAIL_ID), eq(USERNAME))).thenReturn(null);
    assertThrows(ObjectNotFoundException.class, () -> emailMcpTool.getEmailById(EMAIL_ID, null));
  }

  /**
   * An id is guessable, and this one arrives from an agent rather than from a screen the
   * platform rendered. The read therefore has to be the owning one: another user's mail
   * is refused, not decorated with the caller's name and returned.
   */
  @Test
  void getEmailByIdRefusesSomebodyElsesEmail() throws Exception {
    when(emailBoxService.getOwnMailboxEmailById(eq(EMAIL_ID), eq(USERNAME))).thenThrow(new IllegalAccessException("not yours"));
    assertThrows(IllegalAccessException.class, () -> emailMcpTool.getEmailById(EMAIL_ID, null));
    verify(emailBoxService, never()).getEmailById(anyLong(), any());
  }

  /**
   * The conversation id the reader tool asks for has to come from somewhere, and this is
   * one of the three reads that carry it. It used to be dropped on the way out while the
   * conversation tool's description promised it (EXO-89372).
   */
  @Test
  void getEmailByIdCarriesTheThreadId() throws Exception {
    Email email = buildEmail(EMAIL_ID);
    email.setThreadId("thread-1");
    when(emailBoxService.getOwnMailboxEmailById(eq(EMAIL_ID), eq(USERNAME))).thenReturn(email);

    EmailModel model = emailMcpTool.getEmailById(EMAIL_ID, null);

    assertEquals("thread-1", model.getThreadId());
    // And it must reach the agent under the name the tool definition promises.
    assertTrue(new ObjectMapper().writeValueAsString(model).contains("\"thread_id\":\"thread-1\""));
  }

  // --- list_emails ---------------------------------------------------------

  @Test
  void listEmails() throws Exception {
    EmailBox emailBox = new EmailBox();
    emailBox.setEmails(List.of(buildEmail(1L), buildEmail(2L)));
    when(emailBoxService.getEmailBox(USERNAME, MailFolder.INBOX)).thenReturn(emailBox);

    List<EmailModel> emails = emailMcpTool.listEmails(null, null, null, null, null);

    assertNotNull(emails);
    assertEquals(2, emails.size());
    assertEquals("Hello world", emails.get(0).getContent().getBody());
    // list_emails does not expose the user email address
    assertEquals(null, emails.get(0).getUserEmail());
    // but it does expose the conversation id, which is where get_email_thread's own
    // argument comes from
    assertEquals("thread-1", emails.get(0).getThreadId());
  }

  // --- get_my_email_account ------------------------------------------------

  @Test
  void getMyEmailAccountNeverExposesPassword() throws Exception {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId("1");
    setting.setEmailAddress("testuser1@example.com");
    setting.setEmailPassword("super-secret-password");
    setting.setEmailConnectorName("Gmail");
    setting.setEmailConnectorWebmailUrl("https://mail.example.com");
    setting.setEmailSyncStatus(SyncStatus.SUCCESS);
    setting.setConnected(true);
    when(userEmailSettingService.getUserEmailSetting(eq(USERNAME))).thenReturn(setting);

    EmailAccountModel account = emailMcpTool.getMyEmailAccount();

    assertNotNull(account);
    assertEquals("testuser1@example.com", account.getEmailAddress());
    assertEquals("Gmail", account.getConnectorName());
    assertEquals("SUCCESS", account.getSyncStatus());
    assertTrue(account.isConnected());
    // The serialized account must never carry the stored password
    String json = new ObjectMapper().writeValueAsString(account);
    assertFalse(json.contains("super-secret-password"), "Account payload must not leak the password");
    assertFalse(json.toLowerCase().contains("password"), "Account payload must not have any password field");
  }

  @Test
  void getMyEmailAccountFailsWhenNoAccountConnected() {
    when(userEmailSettingService.getUserEmailSetting(eq(USERNAME))).thenReturn(new UserEmailSetting());
    assertThrows(IllegalStateException.class, () -> emailMcpTool.getMyEmailAccount());
  }

  // --- search_emails -------------------------------------------------------

  @Test
  void searchEmailsAsksTheServerAndReportsTheTotal() throws Exception {
    EmailSearchResult hit = new EmailSearchResult(42L,
                                                  MailFolder.INBOX,
                                                  "Invoice due",
                                                  new EmailSender("Alice", "alice@example.com", null, null),
                                                  new Date(),
                                                  false,
                                                  true,
                                                  false,
                                                  null);
    // A second hit with starred and cached CROSSED the other way: the two booleans are
    // adjacent in both constructors, so a page where they agree would let a
    // transposition through unnoticed.
    EmailSearchResult cachedHit = new EmailSearchResult(43L,
                                                       MailFolder.INBOX,
                                                       "Receipt",
                                                       new EmailSender("Bob", "bob@example.com", null, null),
                                                       new Date(),
                                                       false,
                                                       false,
                                                       true,
                                                       null);
    when(emailBoxService.searchEmails(eq(USERNAME), eq("invoice"), isNull(), eq(false), isNull(), eq(MailFolder.INBOX), anyInt()))
                                                                                                                                 .thenReturn(new EmailSearchResultPage(List.of(hit,
                                                                                                                                                                               cachedHit),
                                                                                                                                                                       90));

    EmailSearchResultsModel results = emailMcpTool.searchEmails("invoice", null, null, null, null, null, null);

    // The count is what keeps the agent honest: it saw two of ninety.
    assertEquals(90, results.getTotalMatches());
    assertEquals(2, results.getResults().size());
    assertEquals(42L, results.getResults().get(0).getMailRemoteId());
    assertEquals("Invoice due", results.getResults().get(0).getSubject());
    // The favorite the server reported must reach the agent, not stop at the model —
    // here on a hit that is NOT cached, which is the case the whole flag exists for.
    assertTrue(results.getResults().get(0).isStarred());
    assertFalse(results.getResults().get(0).isCached());
    assertFalse(results.getResults().get(1).isStarred());
    assertTrue(results.getResults().get(1).isCached());
  }

  @Test
  void searchEmailsDefaultsToTheInboxAndUppercasesTheFolder() throws Exception {
    when(emailBoxService.searchEmails(eq(USERNAME), any(), any(), anyBoolean(), any(), eq(MailFolder.ARCHIVE), anyInt()))
                                                                                                                        .thenReturn(new EmailSearchResultPage(List.of(),
                                                                                                                                                              0));

    emailMcpTool.searchEmails("invoice", null, null, null, "archive", null, null);

    verify(emailBoxService).searchEmails(eq(USERNAME), eq("invoice"), isNull(), eq(false), isNull(), eq(MailFolder.ARCHIVE), anyInt());
  }

  @Test
  void searchEmailsTranslatesAMessageCodeIntoSomethingAModelCanAct() throws Exception {
    when(emailBoxService.searchEmails(eq(USERNAME), any(), any(), anyBoolean(), any(), any(), anyInt()))
                                                                                                       .thenThrow(new IllegalArgumentException("emailConnector.search.criteriaRequired"));

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                                                   () -> emailMcpTool.searchEmails(null, null, null, null, null, null, null));
    // A message code tells a model nothing about what to change.
    assertFalse(thrown.getMessage().contains("emailConnector."));
    assertTrue(thrown.getMessage().contains("query"));
  }

  /**
   * The own-folders guard (EXO-90457): a CUSTOM:<id> key names one of the user's
   * registered folders, which from now on include the INBOX of a mailbox somebody else
   * shared with them; the tool's description promises INBOX, SENT or ARCHIVE, so a
   * registry key is refused before the service is asked, in the words a model can act on.
   */
  @Test
  void searchEmailsRefusesARegistryFolderKey() throws Exception {
    // Were the key let through, the service would answer normally: the refusal below is
    // the guard's, not a downstream failure's.
    when(emailBoxService.searchEmails(eq(USERNAME), any(), any(), anyBoolean(), any(), eq("CUSTOM:42"), anyInt()))
                                                                                                                 .thenReturn(new EmailSearchResultPage(List.of(),
                                                                                                                                                       0));

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                                                   () -> emailMcpTool.searchEmails("invoice", null, null, null, "CUSTOM:42", null, null));

    assertTrue(thrown.getMessage().contains("INBOX, SENT or ARCHIVE"), thrown.getMessage());
    verify(emailBoxService, never()).searchEmails(any(), any(), any(), anyBoolean(), any(), any(), anyInt());
  }

  /**
   * A blank folder must fall back to the INBOX, which the uppercase test above
   * never exercises despite its name.
   */
  @Test
  void searchEmailsDefaultsToTheInboxWhenNoFolderIsGiven() throws Exception {
    when(emailBoxService.searchEmails(eq(USERNAME), any(), any(), anyBoolean(), any(), eq(MailFolder.INBOX), anyInt()))
                                                                                                                      .thenReturn(new EmailSearchResultPage(List.of(),
                                                                                                                                                            0));

    emailMcpTool.searchEmails("invoice", null, null, null, null, null, null);
    emailMcpTool.searchEmails("invoice", null, null, null, "  ", null, null);

    verify(emailBoxService, times(2)).searchEmails(eq(USERNAME),
                                                   eq("invoice"),
                                                   isNull(),
                                                   eq(false),
                                                   isNull(),
                                                   eq(MailFolder.INBOX),
                                                   anyInt());
  }

  /**
   * An argument failure the map does not know must not reach the model raw, and
   * must not be swallowed by an NPE: {@code Map.of} rejects a null key, so a
   * message-less exception used to break the catch block itself. The cause is
   * chained either way, so the stack trace survives.
   */
  @Test
  void searchEmailsKeepsAnUnmappedArgumentFailureGeneric() throws Exception {
    when(emailBoxService.searchEmails(eq(USERNAME), any(), any(), anyBoolean(), any(), any(), anyInt()))
                                                                                                       .thenThrow(new NumberFormatException("For input string: \"abc\""));

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                                                   () -> emailMcpTool.searchEmails("invoice", null, null, null, null, null, null));
    assertFalse(thrown.getMessage().contains("For input string"));
    assertNotNull(thrown.getCause());

    // A cause-only exception carries a null message: the old getOrDefault(null, null)
    // threw NPE here instead of reporting anything.
    when(emailBoxService.searchEmails(eq(USERNAME), any(), any(), anyBoolean(), any(), any(), anyInt()))
                                                                                                       .thenThrow(new IllegalArgumentException(new IllegalStateException("boom")));
    IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.searchEmails("invoice", null, null, null, null, null, null));
    assertNotNull(blank.getMessage());
  }

  /**
   * The mailbox being unreachable is not the mailbox being synchronized: that code
   * comes from fetchSearchedEmail, which this tool never calls. Telling the model to
   * retry shortly would loop it on a call that keeps failing.
   */
  @Test
  void searchEmailsReportsAConnectionFailureRatherThanASyncInProgress() throws Exception {
    when(emailBoxService.searchEmails(eq(USERNAME), any(), any(), anyBoolean(), any(), any(), anyInt()))
                                                                                                       .thenThrow(new IllegalStateException("Error when searching mailbox of user "
                                                                                                           + USERNAME));

    IllegalStateException thrown = assertThrows(IllegalStateException.class,
                                                () -> emailMcpTool.searchEmails("invoice", null, null, null, null, null, null));
    assertFalse(thrown.getMessage().contains("synchroniz"));
    assertTrue(thrown.getMessage().contains("could not be reached"));
    assertNotNull(thrown.getCause());
  }

  // --- list_attachments ----------------------------------------------------

  @Test
  void listAttachmentsReturnsMetadataAndDownloadUrlWithoutBytes() throws Exception {
    Email email = buildEmail(EMAIL_ID);
    EmailAttachment attachment = new EmailAttachment(1L, REMOTE_ID, "1.2", "invoice.pdf", "application/pdf", new byte[] { 1, 2, 3 }, MailFolder.INBOX, null, null, null);
    email.getContent().setAttachments(List.of(attachment));
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(REMOTE_ID), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(false), eq(false), eq(false))).thenReturn(email);

    List<EmailAttachmentModel> attachments = emailMcpTool.listAttachments(REMOTE_ID, null, null);

    assertEquals(1, attachments.size());
    EmailAttachmentModel model = attachments.get(0);
    assertEquals("invoice.pdf", model.getName());
    assertEquals("application/pdf", model.getMimeType());
    assertEquals("1.2", model.getAttachmentId());
    // download_url is the existing authenticated EmailBoxRest endpoint, under the add-on's
    // own REST context: /portal/rest never served it (EXO-90555 review of the tool)
    assertEquals("/email-connector/rest/email-box/attachments/" + REMOTE_ID + "/1.2", model.getDownloadUrl());
    // No attachment bytes ever leave the tool: the model must have no data field
    String json = new ObjectMapper().writeValueAsString(model);
    assertFalse(json.toLowerCase().contains("data"), "Attachment payload must not carry bytes");
  }

  @Test
  void listAttachmentsReturnsEmptyWhenNone() throws Exception {
    Email email = buildEmail(EMAIL_ID);
    email.getContent().setAttachments(null);
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(REMOTE_ID), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(false), eq(false), eq(false))).thenReturn(email);

    assertTrue(emailMcpTool.listAttachments(REMOTE_ID, null, null).isEmpty());
  }

  // --- mark_read / mark_unread ---------------------------------------------

  @Test
  void markReadDelegatesToService() throws Exception {
    when(emailBoxService.updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(true))).thenReturn(0);
    String message = emailMcpTool.markRead(List.of(REMOTE_ID), null, null);
    verify(emailBoxService).updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(true));
    assertEquals("Marked 1 email(s) as read.", message);
  }

  @Test
  void markUnreadDelegatesToService() throws Exception {
    when(emailBoxService.updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX), eq(false), eq(true))).thenReturn(0);
    String message = emailMcpTool.markUnread(List.of(REMOTE_ID), null, null);
    verify(emailBoxService).updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX), eq(false), eq(true));
    assertEquals("Marked 1 email(s) as unread.", message);
  }

  @Test
  void markReadReportsAllFailuresAsFailure() throws Exception {
    when(emailBoxService.updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(true))).thenReturn(1);
    String message = emailMcpTool.markRead(List.of(REMOTE_ID), null, null);
    // When every email fails, the message must be phrased as a clear failure and
    // must not claim any success.
    assertTrue(message.startsWith("Failed to mark 1 email(s) as read"), message);
    assertFalse(message.contains("Marked 1"), message);
  }

  @Test
  void markReadReportsPartialFailure() throws Exception {
    List<Long> ids = List.of(REMOTE_ID, 888L);
    when(emailBoxService.updateEmailReadStatus(eq(ids), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(true))).thenReturn(1);
    String message = emailMcpTool.markRead(ids, null, null);
    assertEquals("Marked 1 of 2 email(s) as read; 1 failed (message not found on server or IMAP write denied).", message);
  }

  // --- send_email ----------------------------------------------------------

  @Test
  void sendEmailBuildsMessageAndSends() throws Exception {
    emailMcpTool.sendEmail(List.of("bob@example.com"),
                           "Hi",
                           "<p>Body</p>",
                           List.of("carol@example.com"),
                           List.of("dan@example.com"), null);

    ArgumentCaptor<Email> captor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxService).sendEmail(captor.capture(), eq(USERNAME));
    Email sent = captor.getValue();
    assertEquals("Hi", sent.getSubject());
    assertEquals("bob@example.com", sent.getTo().get(0).getAddress());
    assertEquals("carol@example.com", sent.getCc().get(0).getAddress());
    assertEquals("dan@example.com", sent.getBcc().get(0).getAddress());
    assertTrue(sent.getContent().isHtml());
  }

  @Test
  void sendEmailFailsWithoutRecipient() {
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.sendEmail(List.of(), "Hi", "<p>Body</p>", null, null, null));
  }

  // --- reply_email ---------------------------------------------------------

  @Test
  void replyEmailThreadsAndTargetsSender() throws Exception {
    Email original = buildEmail(EMAIL_ID);
    original.setMailHeaderId("<original-message-id@server>");
    original.setSubject("Question");
    original.setSender(new EmailSender("Alice", "alice@example.com", null, null));
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(REMOTE_ID), eq(USERNAME), eq(MailFolder.INBOX), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(original);

    emailMcpTool.replyEmail(REMOTE_ID, "<p>My answer</p>", null, null);

    ArgumentCaptor<Email> captor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxService).sendEmail(captor.capture(), eq(USERNAME));
    Email reply = captor.getValue();
    // The reply carries the original Message-ID so the service sets In-Reply-To/References
    assertEquals("<original-message-id@server>", reply.getMailHeaderId());
    assertEquals("Re: Question", reply.getSubject());
    assertEquals("alice@example.com", reply.getTo().get(0).getAddress());
  }

  // --- reply_all -----------------------------------------------------------

  @Test
  void replyAllCcsOthersButNotSelf() throws Exception {
    Email original = buildEmail(EMAIL_ID);
    original.setMailHeaderId("<mid@server>");
    original.setSubject("Re: Team sync");
    original.setSender(new EmailSender("Alice", "alice@example.com", null, null));
    original.setTo(List.of(new EmailRecipient(null, "testuser1@example.com", null, true),
                           new EmailRecipient(null, "dave@example.com", null, false)));
    original.setCc(List.of(new EmailRecipient(null, "erin@example.com", null, false)));
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(REMOTE_ID), eq(USERNAME), eq(MailFolder.INBOX), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(original);

    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailAddress("testuser1@example.com");
    when(userEmailSettingService.getUserEmailSetting(eq(USERNAME))).thenReturn(setting);

    emailMcpTool.replyAll(REMOTE_ID, "<p>Reply all body</p>", null, null);

    ArgumentCaptor<Email> captor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxService).sendEmail(captor.capture(), eq(USERNAME));
    Email reply = captor.getValue();
    assertEquals("alice@example.com", reply.getTo().get(0).getAddress());
    // Subject already starts with Re: so it is not doubled
    assertEquals("Re: Team sync", reply.getSubject());
    List<String> ccAddresses = reply.getCc().stream().map(EmailRecipient::getAddress).toList();
    assertTrue(ccAddresses.contains("dave@example.com"));
    assertTrue(ccAddresses.contains("erin@example.com"));
    // The current user must not be CC'd back on their own reply-all
    assertFalse(ccAddresses.contains("testuser1@example.com"));
  }

  // --- get_email_thread ----------------------------------------------------

  /**
   * The conversation comes back oldest first, attributed, with what each message said
   * and what came with it.
   */
  @Test
  void getEmailThreadReadsTheWholeConversationOldestFirst() throws Exception {
    when(emailBoxService.getThread(eq("thread-1"), eq(USERNAME))).thenReturn(List.of(threadMessage("<one@server>",
                                                                                                   "Véronika",
                                                                                                   "veronika@example.org",
                                                                                                   "<p>The <b>contract</b></p>"),
                                                                                     threadMessage("<two@server>",
                                                                                                   "Gianni",
                                                                                                   "gianni@example.org",
                                                                                                   "<p>Thursday?</p>")));

    List<EmailThreadMessageModel> thread = emailMcpTool.getEmailThread("thread-1", null);

    assertEquals(2, thread.size());
    assertEquals("Véronika", thread.get(0).getSenderName());
    assertEquals("veronika@example.org", thread.get(0).getSenderAddress());
    assertEquals("The contract", thread.get(0).getBody(), "the body is readable text, not markup");
    assertEquals("Thursday?", thread.get(1).getBody());
  }

  /**
   * A reply the user has not sent is not part of the conversation anyone means to have
   * read back to them — and its text changes every time they type.
   */
  @Test
  void getEmailThreadLeavesOutTheUnsentDraft() throws Exception {
    Email draft = threadMessage("<draft@server>", "Me", "testuser1@example.com", "<p>half a sentence</p>");
    draft.setDraftLocalId("draft-1");
    when(emailBoxService.getThread(eq("thread-1"), eq(USERNAME)))
                                                                .thenReturn(List.of(threadMessage("<one@server>",
                                                                                                  "Véronika",
                                                                                                  "veronika@example.org",
                                                                                                  "<p>The contract</p>"),
                                                                                    draft));

    List<EmailThreadMessageModel> thread = emailMcpTool.getEmailThread("thread-1", null);

    assertEquals(1, thread.size(), "the draft is not one of the conversation's messages");
    assertEquals("Véronika", thread.get(0).getSenderName());
  }

  /**
   * A long conversation answers its most recent messages, still in reading order. The
   * cap is what keeps a two-hundred-message thread from being answered in full; keeping
   * the RECENT end of it is what keeps the answer useful.
   */
  @Test
  void getEmailThreadAnswersTheMostRecentMessagesInReadingOrder() throws Exception {
    List<Email> longThread = new java.util.ArrayList<>();
    for (int index = 0; index < 40; index++) {
      longThread.add(threadMessage("<m" + index + "@server>", "Véronika", "veronika@example.org", "<p>message " + index + "</p>"));
    }
    when(emailBoxService.getThread(eq("thread-1"), eq(USERNAME))).thenReturn(longThread);

    List<EmailThreadMessageModel> thread = emailMcpTool.getEmailThread("thread-1", null);

    assertEquals(25, thread.size());
    assertEquals("message 15", thread.get(0).getBody(), "the kept slice starts where the last twenty-five begin");
    assertEquals("message 39", thread.get(24).getBody(), "and ends at the newest, so the conversation still reads forwards");
  }

  /**
   * A cut body says so. A reader that cannot see where a message stopped will summarise
   * the half it was given with the confidence of the whole.
   */
  @Test
  void getEmailThreadMarksABodyItHadToCut() throws Exception {
    String longBody = "sentence. ".repeat(400);
    when(emailBoxService.getThread(eq("thread-1"),
                                   eq(USERNAME))).thenReturn(List.of(threadMessage("<one@server>",
                                                                                   "Véronika",
                                                                                   "veronika@example.org",
                                                                                   "<p>" + longBody + "</p>")));

    String body = emailMcpTool.getEmailThread("thread-1", null).get(0).getBody();

    assertTrue(body.length() < longBody.length(), "a whole thread of whole bodies is not what this tool answers");
    assertTrue(body.endsWith("[truncated]"), "a message the reader has only partly been given must say so");
  }

  /**
   * Attachments come back as names: what the conversation is about, not a way into it.
   */
  @Test
  void getEmailThreadNamesTheAttachments() throws Exception {
    Email message = threadMessage("<one@server>", "Véronika", "veronika@example.org", "<p>Attached</p>");
    EmailAttachment attachment = new EmailAttachment();
    attachment.setName("contract.pdf");
    message.getContent().setAttachments(List.of(attachment));
    when(emailBoxService.getThread(eq("thread-1"), eq(USERNAME))).thenReturn(List.of(message));

    assertEquals(List.of("contract.pdf"), emailMcpTool.getEmailThread("thread-1", null).get(0).getAttachmentNames());
  }

  /**
   * A call with no conversation named is refused in words the caller can act on, since
   * the caller is the one that has to correct it.
   */
  @Test
  void getEmailThreadWithoutAThreadIdIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.getEmailThread(" ", null));
  }

  /**
   * Every message of the conversation says which message it is. Without that, the one
   * thing a reader most often wants next — reply to THIS one, read the body that was
   * cut, list what came attached — can only be attempted by searching the mailbox again
   * for a subject and a sender and hoping the newest hit is the same message
   * (EXO-89372).
   */
  @Test
  void getEmailThreadIdentifiesEachMessage() throws Exception {
    Email message = threadMessage("<one@server>", "Véronika", "veronika@example.org", "<p>The contract</p>");
    message.setId(EMAIL_ID);
    message.setMailRemoteId(REMOTE_ID);
    message.setFolder(MailFolder.INBOX);
    when(emailBoxService.getThread(eq("thread-1"), eq(USERNAME))).thenReturn(List.of(message));

    EmailThreadMessageModel model = emailMcpTool.getEmailThread("thread-1", null).get(0);

    assertEquals(EMAIL_ID, model.getEmailId(), "the handle get_email_by_id takes");
    assertEquals(REMOTE_ID, model.getMailRemoteId(), "the handle reply_email and the other write tools take");
    assertEquals(MailFolder.INBOX, model.getFolder());
    // The ids are only usable if they arrive under the names the tool definition
    // promises: a handle nobody can name is a handle nobody can chain.
    String json = new ObjectMapper().writeValueAsString(model);
    assertTrue(json.contains("\"email_id\":" + EMAIL_ID), json);
    assertTrue(json.contains("\"mail_remote_id\":" + REMOTE_ID), json);
    assertTrue(json.contains("\"folder\":\"INBOX\""), json);
  }

  /**
   * A conversation reliably mixes received and sent mail, and an IMAP UID numbers a
   * message within ONE folder. A sent message that reported no folder — or reported
   * INBOX — would hand out a number that names a different message in the INBOX-scoped
   * write tools, which is precisely the bug EXO-89367 fixed.
   */
  @Test
  void getEmailThreadSaysWhichFolderEachMessageCameFrom() throws Exception {
    Email received = threadMessage("<one@server>", "Véronika", "veronika@example.org", "<p>The contract</p>");
    received.setMailRemoteId(101L);
    received.setFolder(MailFolder.INBOX);
    Email sent = threadMessage("<two@server>", "Me", "testuser1@example.com", "<p>Thursday works</p>");
    sent.setMailRemoteId(101L);
    sent.setFolder(MailFolder.SENT);
    when(emailBoxService.getThread(eq("thread-1"), eq(USERNAME))).thenReturn(List.of(received, sent));

    List<EmailThreadMessageModel> thread = emailMcpTool.getEmailThread("thread-1", null);

    assertEquals(MailFolder.INBOX, thread.get(0).getFolder());
    assertEquals(MailFolder.SENT, thread.get(1).getFolder(), "a sent message must report SENT, not the reader's default");
    // Same number, two different messages: only the INBOX one hands its UID out
    // (EXO-90555), so the sent one can only be named by its email_id and never reaches
    // the inbox message numbered like it.
    assertEquals(101L, thread.get(0).getMailRemoteId());
    assertNull(thread.get(1).getMailRemoteId(), "a UID outside the own INBOX is never handed out");
  }

  /**
   * A message that does not know where it lives says nothing rather than claiming the
   * inbox. Defaulting a missing folder to INBOX is the same silent assumption EXO-89367
   * was about, and the caller is told not to act on a UID that arrives without one.
   */
  @Test
  void getEmailThreadDoesNotInventAFolderItWasNotGiven() throws Exception {
    Email message = threadMessage("<one@server>", "Véronika", "veronika@example.org", "<p>The contract</p>");
    message.setMailRemoteId(REMOTE_ID);
    message.setFolder(null);
    when(emailBoxService.getThread(eq("thread-1"), eq(USERNAME))).thenReturn(List.of(message));

    EmailThreadMessageModel model = emailMcpTool.getEmailThread("thread-1", null).get(0);

    assertNull(model.getFolder());
    assertFalse(new ObjectMapper().writeValueAsString(model).contains("folder"));
  }

  /**
   * One message of a conversation, as the storage layer hands it over.
   *
   * @param messageId its Message-ID
   * @param senderName the sender's display name
   * @param senderAddress the sender's address
   * @param bodyHtml its stored body
   * @return the message
   */
  private Email threadMessage(String messageId, String senderName, String senderAddress, String bodyHtml) {
    Email email = new Email();
    email.setMailHeaderId(messageId);
    email.setSubject("The contract");
    email.setSender(new EmailSender(senderName, senderAddress, null, null));
    email.setReceivedDate(new Date());
    EmailContent content = new EmailContent();
    content.setBody(bodyHtml);
    email.setContent(content);
    return email;
  }

  // --- archive_email / delete_email ----------------------------------------

  @Test
  void archiveEmailDelegatesToService() throws Exception {
    when(emailBoxService.archiveEmail(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX))).thenReturn(0);
    String result = emailMcpTool.archiveEmail(List.of(REMOTE_ID), null, null);
    verify(emailBoxService).archiveEmail(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX));
    assertTrue(result.contains("Archived 1 of 1"));
  }

  @Test
  void deleteEmailDelegatesToService() throws Exception {
    when(emailBoxService.deleteEmail(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX))).thenReturn(0);
    String result = emailMcpTool.deleteEmail(List.of(REMOTE_ID), null, null);
    verify(emailBoxService).deleteEmail(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX));
    assertTrue(result.contains("Deleted 1 of 1"));
  }

  // --- shared mailboxes (EXO-90555) -----------------------------------------

  /** The shared mailbox's address, as an agent names it. */
  private static final String OWNER_MAILBOX = "alice@acme.com";

  /** The shared mailbox's registered inbox. */
  private static final String SHARED_INBOX  = "CUSTOM:5";

  /**
   * An accepted Editor share of Alice's mailbox, as the switcher reads it.
   *
   * @return the entry
   */
  private SharedMailboxEntry aliceShare() {
    return new SharedMailboxEntry(100L,
                                  "alice",
                                  "Alice Martin",
                                  OWNER_MAILBOX,
                                  DelegationPreset.EDITOR,
                                  "lrswite",
                                  Map.of(),
                                  SHARED_INBOX,
                                  3,
                                  List.of(),
                                  false,
                                  true);
  }

  /**
   * Alice's mailbox resolves for the caller.
   *
   * @return the entry
   * @throws Exception never
   */
  private SharedMailboxEntry givenAliceShares() throws Exception {
    SharedMailboxEntry share = aliceShare();
    when(emailDelegationService.getSharedMailbox(USERNAME, OWNER_MAILBOX)).thenReturn(share);
    return share;
  }

  /**
   * The shared mailboxes are listed from the mirror alone: no mailbox service call, so
   * no mail server is reached, however many shares there are.
   */
  @Test
  void listSharedMailboxesReadsTheMirrorOnly() {
    SharedMailboxEntry share = aliceShare();
    when(emailDelegationService.getUsableSharedMailboxes(USERNAME)).thenReturn(List.of(share));
    when(emailDelegationService.getMirroredFolders(USERNAME, share)).thenReturn(List.of("INBOX", "SENT"));

    List<SharedMailboxModel> mailboxes = emailMcpTool.listSharedMailboxes();

    assertEquals(1, mailboxes.size());
    SharedMailboxModel alice = mailboxes.get(0);
    assertEquals("Alice Martin", alice.getOwnerName());
    assertEquals(OWNER_MAILBOX, alice.getMailbox());
    assertEquals("alice", alice.getOwnerUsername());
    assertEquals("EDITOR", alice.getAccess());
    assertEquals(3, alice.getUnreadCount());
    assertEquals(List.of("INBOX", "SENT"), alice.getFolders());
    assertTrue(alice.isSentCopy());
    Mockito.verifyNoInteractions(emailBoxService);
  }

  /**
   * A mailbox argument that resolves to nothing is not found, on every tool that takes
   * one -- and never falls back to the user's own mailbox: nothing is read, sent or
   * changed anywhere.
   */
  @Test
  void aMailboxThatIsNotSharedIsNotFoundAndNeverTheUsersOwn() throws Exception {
    when(emailDelegationService.getSharedMailbox(eq(USERNAME), any())).thenThrow(new ObjectNotFoundException("emailConnector.delegation.sharedMailboxNotFound"));
    String nobody = "carol@acme.com";
    List<org.junit.jupiter.api.function.Executable> calls =
                                                          List.of(() -> emailMcpTool.getEmailById(EMAIL_ID, nobody),
                                                                  () -> emailMcpTool.listEmails(null, null, null, null, nobody),
                                                                  () -> emailMcpTool.getUnreadCount(nobody),
                                                                  () -> emailMcpTool.searchEmails("x", null, null, null, null, null, nobody),
                                                                  () -> emailMcpTool.getEmailFull(REMOTE_ID, null, nobody),
                                                                  () -> emailMcpTool.listAttachments(REMOTE_ID, null, nobody),
                                                                  () -> emailMcpTool.getEmailThread("thread-1", nobody),
                                                                  () -> emailMcpTool.markRead(List.of(REMOTE_ID), null, nobody),
                                                                  () -> emailMcpTool.markUnread(List.of(REMOTE_ID), null, nobody),
                                                                  () -> emailMcpTool.sendEmail(List.of("bob@acme.com"), "Hi", "<p>x</p>", null, null, nobody),
                                                                  () -> emailMcpTool.replyEmail(REMOTE_ID, "<p>x</p>", nobody, null),
                                                                  () -> emailMcpTool.replyAll(REMOTE_ID, "<p>x</p>", nobody, null),
                                                                  () -> emailMcpTool.forwardEmail(REMOTE_ID, List.of("bob@acme.com"), "<p>x</p>", null, nobody, null),
                                                                  () -> emailMcpTool.archiveEmail(List.of(REMOTE_ID), nobody, null),
                                                                  () -> emailMcpTool.deleteEmail(List.of(REMOTE_ID), nobody, null));
    for (org.junit.jupiter.api.function.Executable call : calls) {
      ObjectNotFoundException refused = assertThrows(ObjectNotFoundException.class, call);
      assertTrue(refused.getMessage().contains(nobody), refused.getMessage());
    }
    Mockito.verifyNoInteractions(emailBoxService);
  }

  /** A blank mailbox is the user's own, without asking the delegation service anything. */
  @Test
  void aBlankMailboxIsTheUsersOwn() throws Exception {
    when(emailBoxService.getOwnMailboxEmailById(EMAIL_ID, USERNAME)).thenReturn(buildEmail(EMAIL_ID));

    assertNotNull(emailMcpTool.getEmailById(EMAIL_ID, "  "));

    verify(emailBoxService, never()).getSharedMailboxEmailById(anyLong(), any(), anyLong());
    Mockito.verifyNoInteractions(emailDelegationService);
  }

  /**
   * An id read with a mailbox is read in that shared mailbox only: a row the service
   * does not place in that share is not found, and the user's own mailbox is not asked.
   */
  @Test
  void anEmailIdIsReadInTheMailboxNamed() throws Exception {
    givenAliceShares();
    when(emailBoxService.getSharedMailboxEmailById(EMAIL_ID, USERNAME, 100L)).thenReturn(buildEmail(EMAIL_ID));

    assertEquals(EMAIL_ID, emailMcpTool.getEmailById(EMAIL_ID, OWNER_MAILBOX).getId());
    assertThrows(ObjectNotFoundException.class, () -> emailMcpTool.getEmailById(43L, OWNER_MAILBOX), "not in that share");
    verify(emailBoxService, never()).getOwnMailboxEmailById(anyLong(), any());
  }

  /**
   * A shared mailbox's folder that is not in the mirror is said, never listed or
   * searched as an empty one; one that is, is read by its key, and a hit names the
   * folder, not the key.
   */
  @Test
  void aSharedFolderOutsideTheMirrorIsSaidNotAnsweredEmpty() throws Exception {
    SharedMailboxEntry share = givenAliceShares();
    when(emailDelegationService.getMirroredFolderKey(USERNAME, share, "SENT")).thenReturn(null);

    IllegalArgumentException listed = assertThrows(IllegalArgumentException.class,
                                                   () -> emailMcpTool.listEmails(null, null, null, "sent", OWNER_MAILBOX));
    assertTrue(listed.getMessage().contains("not available") && listed.getMessage().contains("Alice Martin"), listed.getMessage());
    IllegalArgumentException searched = assertThrows(IllegalArgumentException.class,
                                                     () -> emailMcpTool.searchEmails("x", null, null, null, "SENT", null, OWNER_MAILBOX));
    assertTrue(searched.getMessage().contains("not an empty folder"), searched.getMessage());
    Mockito.verifyNoInteractions(emailBoxService);

    when(emailDelegationService.getMirroredFolderKey(USERNAME, share, "ARCHIVE")).thenReturn("CUSTOM:7");
    EmailBox archive = new EmailBox();
    archive.setEmails(List.of());
    when(emailBoxService.getEmailBox(USERNAME, "CUSTOM:7")).thenReturn(archive);
    assertTrue(emailMcpTool.listEmails(null, null, null, "ARCHIVE", OWNER_MAILBOX).isEmpty());

    EmailSearchResult hit = new EmailSearchResult(9L, "CUSTOM:7", "Budget", null, new Date(), false, false, true, null);
    when(emailBoxService.searchSharedMailboxMirror(USERNAME, "CUSTOM:7", "budget", null, false, null, 20))
                                                                                                          .thenReturn(new EmailSearchResultPage(List.of(hit), 1));
    EmailSearchResultsModel results = emailMcpTool.searchEmails("budget", null, null, null, "ARCHIVE", null, OWNER_MAILBOX);
    assertEquals(1, results.getTotalMatches());
    assertEquals("ARCHIVE", results.getResults().get(0).getFolder(), "the folder's name, never a shared folder's key");
    verify(emailBoxService, never()).searchEmails(any(), any(), any(), anyBoolean(), any(), any(), anyInt());
  }

  /** The unread count of a shared inbox is the mirror's, reaching no mail server. */
  @Test
  void aSharedUnreadCountReadsTheMirror() throws Exception {
    SharedMailboxEntry share = givenAliceShares();
    when(emailDelegationService.getMirroredFolderKey(USERNAME, share, MailFolder.INBOX)).thenReturn(SHARED_INBOX);

    String count = emailMcpTool.getUnreadCount(OWNER_MAILBOX);

    assertTrue(count.startsWith("3 unread") && count.contains("Alice Martin (alice@acme.com)"), count);
    Mockito.verifyNoInteractions(emailBoxService);
  }

  /** A shared inbox not synced yet has no count: said, never "0 unread". */
  @Test
  void aSharedUnreadCountOfAnInboxNotSyncedYetIsSaid() throws Exception {
    SharedMailboxEntry share = givenAliceShares();
    when(emailDelegationService.getMirroredFolderKey(USERNAME, share, MailFolder.INBOX)).thenReturn(null);

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> emailMcpTool.getUnreadCount(OWNER_MAILBOX));
    assertTrue(refused.getMessage().contains("not available"), refused.getMessage());
  }

  /**
   * A conversation read in a shared mailbox names each message's folder -- INBOX, SENT,
   * ARCHIVE -- through the share's roles, never as a shared folder's key, so the
   * "chain only from INBOX" rule applies there too.
   */
  @Test
  void aSharedConversationNamesItsFoldersNeverTheirKeys() throws Exception {
    SharedMailboxEntry share = new SharedMailboxEntry(100L,
                                                      "alice",
                                                      "Alice Martin",
                                                      OWNER_MAILBOX,
                                                      DelegationPreset.EDITOR,
                                                      "lrswite",
                                                      Map.of(),
                                                      SHARED_INBOX,
                                                      3,
                                                      List.of(new SharedMailboxFolder("CUSTOM:6", FolderRole.SENT, "Sent", "lrs", Map.of(), true),
                                                              new SharedMailboxFolder("CUSTOM:7", FolderRole.TRASH, "Trash", "lrs", Map.of(), true)),
                                                      false,
                                                      true);
    when(emailDelegationService.getSharedMailbox(USERNAME, OWNER_MAILBOX)).thenReturn(share);
    Email received = threadMessage("<1@x>", "Carol", "carol@acme.com", "<p>Hi</p>");
    received.setFolder(SHARED_INBOX);
    Email answered = threadMessage("<2@x>", "Alice", OWNER_MAILBOX, "<p>Hello</p>");
    answered.setFolder("CUSTOM:6");
    Email trashed = threadMessage("<3@x>", "Carol", "carol@acme.com", "<p>Again</p>");
    trashed.setFolder("CUSTOM:7");
    when(emailBoxService.getThread("thread-1", USERNAME, SHARED_INBOX)).thenReturn(List.of(received, answered, trashed));

    List<EmailThreadMessageModel> thread = emailMcpTool.getEmailThread("thread-1", OWNER_MAILBOX);

    assertEquals(java.util.Arrays.asList("INBOX", "SENT", "TRASH"), thread.stream().map(EmailThreadMessageModel::getFolder).toList());
    assertTrue(thread.stream().allMatch(message -> message.getMailRemoteId() == null), "no UID is handed out for a shared mailbox's mail");
  }

  /** The user's own Sent is listed by name, with no shared mailbox involved. */
  @Test
  void theUsersOwnSentIsListedByName() throws Exception {
    EmailBox sent = new EmailBox();
    sent.setEmails(List.of(buildEmail(1L)));
    when(emailBoxService.getEmailBox(USERNAME, MailFolder.SENT)).thenReturn(sent);

    assertEquals(1, emailMcpTool.listEmails(null, null, null, "sent", null).size());
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.listEmails(null, null, null, "TRASH", null));
    Mockito.verifyNoInteractions(emailDelegationService);
  }

  /** sync_now refuses a mailbox rather than syncing the user's own instead. */
  @Test
  void syncNowRefusesAMailbox() throws Exception {
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.syncNow(OWNER_MAILBOX));
    Mockito.verifyNoInteractions(emailBoxService);
  }

  /**
   * A reply-all from a shared mailbox reads the original in that mailbox's inbox, sends
   * from the share, leaves both the user and the owner out of the Cc, and says what
   * became of the owner's copy.
   */
  @Test
  void aReplyAllFromASharedMailboxReadsTheOriginalThere() throws Exception {
    givenAliceShares();
    Email original = buildEmail(EMAIL_ID);
    original.setSender(new EmailSender("Carol", "carol@acme.com", null, null));
    original.setTo(List.of(new EmailRecipient(null, OWNER_MAILBOX, null, false), new EmailRecipient(null, "dave@acme.com", null, false)));
    original.setCc(List.of(new EmailRecipient(null, "testuser1@example.com", null, false)));
    original.setFolder(SHARED_INBOX);
    when(emailBoxService.getSharedMailboxEmailById(EMAIL_ID, USERNAME, 100L)).thenReturn(original);
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(REMOTE_ID, USERNAME, SHARED_INBOX, false, true, false, false)).thenReturn(original);
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailAddress("testuser1@example.com");
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(setting);
    when(emailBoxService.sendEmail(any(Email.class), eq(USERNAME), eq(100L))).thenReturn(EmailBoxService.OwnerCopy.FILED);

    String result = emailMcpTool.replyAll(null, "<p>Noted</p>", OWNER_MAILBOX, EMAIL_ID);

    ArgumentCaptor<Email> sent = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxService).sendEmail(sent.capture(), eq(USERNAME), eq(100L));
    assertEquals(List.of("dave@acme.com"), sent.getValue().getCc().stream().map(EmailRecipient::getAddress).toList());
    assertTrue(result.contains("from the mailbox of Alice Martin") && result.contains("A copy was filed"), result);
    verify(emailBoxService, never()).getEmailByMailRemoteIdAndUserId(anyLong(), any(), eq(MailFolder.INBOX), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
    verify(emailBoxService, never()).sendEmail(any(Email.class), eq(USERNAME));
  }

  /**
   * A forward from a shared mailbox reads the original in that mailbox's inbox too, and
   * an original that is not there is not found -- never looked up in the user's own.
   */
  @Test
  void aForwardFromASharedMailboxReadsTheOriginalThere() throws Exception {
    givenAliceShares();
    Email original = buildEmail(EMAIL_ID);
    original.setFolder(SHARED_INBOX);
    when(emailBoxService.getSharedMailboxEmailById(EMAIL_ID, USERNAME, 100L)).thenReturn(original);
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(REMOTE_ID, USERNAME, SHARED_INBOX, false, true, false, false)).thenReturn(original);
    when(emailBoxService.sendEmail(any(Email.class), eq(USERNAME), eq(100L))).thenReturn(EmailBoxService.OwnerCopy.SKIPPED);

    String result = emailMcpTool.forwardEmail(null, List.of("bob@acme.com"), null, null, OWNER_MAILBOX, EMAIL_ID);

    assertTrue(result.contains("No copy was filed"), result);
    assertThrows(ObjectNotFoundException.class, () -> emailMcpTool.forwardEmail(null, List.of("bob@acme.com"), null, null, OWNER_MAILBOX, 888L));
    verify(emailBoxService, never()).getEmailByMailRemoteIdAndUserId(anyLong(), any(), eq(MailFolder.INBOX), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  /** A send from a shared mailbox whose owner's copy failed says so; the mail went out. */
  @Test
  void aSendFromASharedMailboxSaysTheOwnersCopyFailed() throws Exception {
    givenAliceShares();
    when(emailBoxService.sendEmail(any(Email.class), eq(USERNAME), eq(100L))).thenReturn(EmailBoxService.OwnerCopy.FAILED);

    String result = emailMcpTool.sendEmail(List.of("bob@acme.com"), "Hi", "<p>x</p>", null, null, OWNER_MAILBOX);

    assertTrue(result.startsWith("Email sent to bob@acme.com") && result.contains("could not be filed"), result);
  }

  /**
   * A write the share does not allow, or a share withdrawn meanwhile, is refused in
   * words that name the owner.
   */
  @Test
  void aRefusedWriteInASharedMailboxNamesTheOwner() throws Exception {
    givenAliceShares();
    Email inShare = buildEmail(EMAIL_ID);
    inShare.setFolder(SHARED_INBOX);
    when(emailBoxService.getSharedMailboxEmailById(EMAIL_ID, USERNAME, 100L)).thenReturn(inShare);
    when(emailBoxService.updateEmailReadStatus(List.of(REMOTE_ID), USERNAME, SHARED_INBOX, true, true)).thenThrow(new MailboxRightMissingException('s'));
    when(emailBoxService.archiveEmail(List.of(REMOTE_ID), USERNAME, SHARED_INBOX)).thenThrow(new DelegationRevokedException(DelegationRevokedException.REVOKED));
    when(emailBoxService.deleteEmail(List.of(REMOTE_ID), USERNAME, SHARED_INBOX)).thenReturn(0);

    IllegalAccessException marked = assertThrows(IllegalAccessException.class, () -> emailMcpTool.markRead(null, List.of(EMAIL_ID), OWNER_MAILBOX));
    assertTrue(marked.getMessage().contains("does not allow you to change the read state") && marked.getMessage().contains("Alice Martin"),
               marked.getMessage());
    IllegalAccessException archived = assertThrows(IllegalAccessException.class, () -> emailMcpTool.archiveEmail(null, OWNER_MAILBOX, List.of(EMAIL_ID)));
    assertTrue(archived.getMessage().contains("no longer shared with you"), archived.getMessage());
    assertTrue(emailMcpTool.deleteEmail(null, OWNER_MAILBOX, List.of(EMAIL_ID)).contains("in the mailbox of Alice Martin"));
  }

  /**
   * A conversation and an attachment read with a mailbox are read in that shared
   * mailbox: the thread from its inbox, the download address naming its folder.
   */
  @Test
  void aThreadAndAttachmentsAreReadInTheMailboxNamed() throws Exception {
    givenAliceShares();
    when(emailBoxService.getThread("thread-1", USERNAME, SHARED_INBOX)).thenReturn(List.of());
    Email withAttachment = buildEmail(EMAIL_ID);
    EmailAttachment attachment = new EmailAttachment();
    attachment.setName("invoice.pdf");
    attachment.setAttachmentRemoteId("1.2");
    withAttachment.getContent().setAttachments(List.of(attachment));
    withAttachment.setFolder(SHARED_INBOX);
    when(emailBoxService.getSharedMailboxEmailById(EMAIL_ID, USERNAME, 100L)).thenReturn(withAttachment);
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(REMOTE_ID, USERNAME, SHARED_INBOX, true, false, false, false)).thenReturn(withAttachment);

    assertTrue(emailMcpTool.getEmailThread("thread-1", OWNER_MAILBOX).isEmpty());
    verify(emailBoxService, never()).getThread("thread-1", USERNAME);
    assertEquals("/email-connector/rest/email-box/attachments/" + REMOTE_ID + "/1.2?folder=CUSTOM%3A5",
                 emailMcpTool.listAttachments(null, EMAIL_ID, OWNER_MAILBOX).get(0).getDownloadUrl());
  }

  // --- one mail, never another folder's mail with the same number (EXO-90555) ------

  /** The local id of a mail of Sent, whose UID is also the UID of an INBOX mail. */
  private static final long SENT_EMAIL_ID = 50L;

  /**
   * A mail of a folder other than the inbox, numbered like the inbox mail REMOTE_ID.
   *
   * @param folder the folder key it is cached under
   * @return the row
   */
  private Email sentMail(String folder) {
    Email sent = buildEmail(SENT_EMAIL_ID);
    sent.setFolder(folder);
    sent.setSubject("Our offer");
    sent.setSender(new EmailSender("Me", "testuser1@example.com", null, null));
    sent.setTo(List.of(new EmailRecipient(null, "client@acme.com", null, false)));
    return sent;
  }

  /**
   * A Sent mail named by its email_id is replied to and forwarded as itself -- read in
   * Sent -- and the INBOX mail with the same UID is never read, let alone answered.
   */
  @Test
  void aSentMailIsAnsweredAsItselfNeverAsTheInboxMailNumberedLikeIt() throws Exception {
    Email sent = sentMail(MailFolder.SENT);
    when(emailBoxService.getOwnMailboxEmailById(SENT_EMAIL_ID, USERNAME)).thenReturn(sent);
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(REMOTE_ID, USERNAME, MailFolder.SENT, false, true, false, false)).thenReturn(sent);

    String replied = emailMcpTool.replyEmail(null, "<p>Any news?</p>", null, SENT_EMAIL_ID);
    String forwarded = emailMcpTool.forwardEmail(null, List.of("boss@acme.com"), null, null, null, SENT_EMAIL_ID);

    ArgumentCaptor<Email> out = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxService, times(2)).sendEmail(out.capture(), eq(USERNAME));
    assertEquals("Re: Our offer", out.getAllValues().get(0).getSubject(), replied);
    assertEquals("Fwd: Our offer", out.getAllValues().get(1).getSubject(), forwarded);
    verify(emailBoxService, never()).getEmailByMailRemoteIdAndUserId(anyLong(), any(), eq(MailFolder.INBOX), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  /**
   * The same in a shared mailbox: its Sent mail is read in its Sent, never in its inbox
   * nor in the user's own.
   */
  @Test
  void aSharedSentMailIsAnsweredAsItself() throws Exception {
    givenAliceShares();
    Email sent = sentMail("CUSTOM:6");
    when(emailBoxService.getSharedMailboxEmailById(SENT_EMAIL_ID, USERNAME, 100L)).thenReturn(sent);
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(REMOTE_ID, USERNAME, "CUSTOM:6", false, true, false, false)).thenReturn(sent);
    when(emailBoxService.sendEmail(any(Email.class), eq(USERNAME), eq(100L))).thenReturn(EmailBoxService.OwnerCopy.FILED);

    emailMcpTool.replyEmail(null, "<p>Any news?</p>", OWNER_MAILBOX, SENT_EMAIL_ID);

    verify(emailBoxService).sendEmail(any(Email.class), eq(USERNAME), eq(100L));
    verify(emailBoxService, never()).getEmailByMailRemoteIdAndUserId(anyLong(), any(), eq(SHARED_INBOX), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
    verify(emailBoxService, never()).getEmailByMailRemoteIdAndUserId(anyLong(), any(), eq(MailFolder.INBOX), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean());
  }

  /**
   * An inbox tool given a Sent mail refuses and does nothing -- own or shared -- rather
   * than archiving, deleting or marking the inbox mail numbered like it.
   */
  @Test
  void anInboxToolGivenASentMailRefusesAndDoesNothing() throws Exception {
    when(emailBoxService.getOwnMailboxEmailById(SENT_EMAIL_ID, USERNAME)).thenReturn(sentMail(MailFolder.SENT));
    SharedMailboxEntry share = givenAliceShares();
    when(emailBoxService.getSharedMailboxEmailById(SENT_EMAIL_ID, USERNAME, share.delegationId())).thenReturn(sentMail("CUSTOM:6"));

    IllegalArgumentException archived = assertThrows(IllegalArgumentException.class,
                                                     () -> emailMcpTool.archiveEmail(null, null, List.of(SENT_EMAIL_ID)));
    assertTrue(archived.getMessage().contains("SENT folder") && archived.getMessage().contains("nothing was done"), archived.getMessage());
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.deleteEmail(null, null, List.of(SENT_EMAIL_ID)));
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.markRead(null, List.of(SENT_EMAIL_ID), null));
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.addEmailCategory(null, 3L, List.of(SENT_EMAIL_ID)));
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.archiveEmail(null, OWNER_MAILBOX, List.of(SENT_EMAIL_ID)));
    verify(emailBoxService, never()).archiveEmail(any(), any(), any());
    verify(emailBoxService, never()).deleteEmail(any(), any(), any());
    verify(emailBoxService, never()).updateEmailReadStatus(any(), any(), any(), anyBoolean(), anyBoolean());
    verify(emailBoxService, never()).linkEmailsToCategory(any(), anyLong(), any());
  }

  /**
   * A mail_remote_id that is not the email_id's, a UID alone in a shared mailbox, and a
   * row that is no longer where it was read are all refused, and nothing is sent.
   */
  @Test
  void aMismatchIsRefusedAndNothingIsSent() throws Exception {
    when(emailBoxService.getOwnMailboxEmailById(SENT_EMAIL_ID, USERNAME)).thenReturn(sentMail(MailFolder.SENT));
    Email inbox = buildEmail(EMAIL_ID);
    when(emailBoxService.getOwnMailboxEmailById(EMAIL_ID, USERNAME)).thenReturn(inbox);

    IllegalArgumentException mismatch = assertThrows(IllegalArgumentException.class,
                                                     () -> emailMcpTool.replyEmail(888L, "<p>x</p>", null, SENT_EMAIL_ID));
    assertTrue(mismatch.getMessage().contains("not the same mail"), mismatch.getMessage());
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.archiveEmail(List.of(888L), null, List.of(EMAIL_ID)));
    givenAliceShares();
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.replyAll(REMOTE_ID, "<p>x</p>", OWNER_MAILBOX, null));
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.deleteEmail(List.of(REMOTE_ID), OWNER_MAILBOX, null));
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.replyEmail(null, "<p>x</p>", null, null), "a mail must be named");
    // The row found at that folder and number is another mail than the one named.
    Email another = sentMail(MailFolder.SENT);
    another.setId(99L);
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(REMOTE_ID, USERNAME, MailFolder.SENT, false, true, false, false)).thenReturn(another);
    assertThrows(IllegalStateException.class, () -> emailMcpTool.forwardEmail(null, List.of("x@acme.com"), null, null, null, SENT_EMAIL_ID));

    verify(emailBoxService, never()).sendEmail(any(Email.class), any());
    verify(emailBoxService, never()).sendEmail(any(Email.class), any(), any());
    verify(emailBoxService, never()).archiveEmail(any(), any(), any());
    verify(emailBoxService, never()).deleteEmail(any(), any(), any());
  }

  /**
   * An email names its folder, and hands out its UID only when it is a mail of the
   * user's own INBOX: a Sent mail's model carries its email_id and folder, no UID; a
   * mail of an own folder without a role names no folder.
   */
  @Test
  void theModelCarriesTheFolderAndAUidOnlyForTheOwnInbox() throws Exception {
    when(emailBoxService.getOwnMailboxEmailById(SENT_EMAIL_ID, USERNAME)).thenReturn(sentMail(MailFolder.SENT));
    when(emailBoxService.getOwnMailboxEmailById(EMAIL_ID, USERNAME)).thenReturn(buildEmail(EMAIL_ID));
    Email custom = buildEmail(51L);
    custom.setFolder("CUSTOM:12");
    when(emailBoxService.getOwnMailboxEmailById(51L, USERNAME)).thenReturn(custom);

    EmailModel sent = emailMcpTool.getEmailById(SENT_EMAIL_ID, null);
    assertEquals("SENT", sent.getFolder());
    assertNull(sent.getMailRemoteId());
    assertEquals(SENT_EMAIL_ID, sent.getId());
    String json = new ObjectMapper().writeValueAsString(sent);
    assertTrue(json.contains("\"folder\":\"SENT\"") && json.contains("\"email_id\":" + SENT_EMAIL_ID), json);
    EmailModel inbox = emailMcpTool.getEmailById(EMAIL_ID, null);
    assertEquals("INBOX", inbox.getFolder());
    assertEquals(REMOTE_ID, inbox.getMailRemoteId());
    assertNull(emailMcpTool.getEmailById(51L, null).getFolder(), "never an internal folder key");

    SharedMailboxEntry share = givenAliceShares();
    Email sharedInbox = buildEmail(EMAIL_ID);
    sharedInbox.setFolder(SHARED_INBOX);
    when(emailBoxService.getSharedMailboxEmailById(EMAIL_ID, USERNAME, share.delegationId())).thenReturn(sharedInbox);
    EmailModel shared = emailMcpTool.getEmailById(EMAIL_ID, OWNER_MAILBOX);
    assertEquals("INBOX", shared.getFolder());
    assertNull(shared.getMailRemoteId(), "a shared mailbox's mail is named by email_id");
  }

  /** A search hit hands out a UID only for the user's own INBOX, and a shared hit its email_id. */
  @Test
  void aSearchHitHandsOutAUidOnlyForTheOwnInbox() throws Exception {
    EmailSearchResult sentHit = new EmailSearchResult(REMOTE_ID, MailFolder.SENT, "Our offer", null, new Date(), true, false, true, null);
    when(emailBoxService.searchEmails(USERNAME, "offer", null, false, null, MailFolder.SENT, 20)).thenReturn(new EmailSearchResultPage(List.of(sentHit), 1));
    assertNull(emailMcpTool.searchEmails("offer", null, null, null, "SENT", null, null).getResults().get(0).getMailRemoteId());

    SharedMailboxEntry share = givenAliceShares();
    when(emailDelegationService.getMirroredFolderKey(USERNAME, share, "INBOX")).thenReturn(SHARED_INBOX);
    EmailSearchResult sharedHit = new EmailSearchResult(REMOTE_ID, SHARED_INBOX, "Budget", null, new Date(), false, false, true, null, EMAIL_ID);
    when(emailBoxService.searchSharedMailboxMirror(USERNAME, SHARED_INBOX, "budget", null, false, null, 20)).thenReturn(new EmailSearchResultPage(List.of(sharedHit), 1));
    EmailSearchHitModel hit = emailMcpTool.searchEmails("budget", null, null, null, null, null, OWNER_MAILBOX).getResults().get(0);
    assertNull(hit.getMailRemoteId());
    assertEquals(EMAIL_ID, hit.getEmailId());
  }
}
