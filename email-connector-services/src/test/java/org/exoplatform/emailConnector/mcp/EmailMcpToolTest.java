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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.fasterxml.jackson.databind.ObjectMapper;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.mcp.model.EmailAccountModel;
import org.exoplatform.emailConnector.mcp.model.EmailAttachmentModel;
import org.exoplatform.emailConnector.mcp.model.EmailModel;
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
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

class EmailMcpToolTest {

  private static final String     USERNAME  = "testuser1";

  private static final long       EMAIL_ID  = 42L;

  private static final long       REMOTE_ID = 777L;

  private EmailBoxService         emailBoxService;

  private UserEmailSettingService userEmailSettingService;

  private EmailMcpTool            emailMcpTool;

  @BeforeEach
  void setUp() {
    emailBoxService = Mockito.mock(EmailBoxService.class);
    userEmailSettingService = Mockito.mock(UserEmailSettingService.class);
    emailMcpTool = new EmailMcpTool(emailBoxService, userEmailSettingService) {
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
    email.setUserId(USERNAME);
    email.setUserEmail("testuser1@example.com");
    email.setSubject("Hello");
    EmailContent content = new EmailContent();
    content.setBody("<p>Hello <b>world</b></p>");
    email.setContent(content);
    return email;
  }

  // --- get_email_by_id -----------------------------------------------------

  @Test
  void getEmailById() throws Exception {
    when(emailBoxService.getOwnedEmailById(eq(EMAIL_ID), eq(USERNAME))).thenReturn(buildEmail(EMAIL_ID));

    EmailModel model = emailMcpTool.getEmailById(EMAIL_ID);

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
    when(emailBoxService.getOwnedEmailById(eq(EMAIL_ID), eq(USERNAME))).thenReturn(null);
    assertThrows(ObjectNotFoundException.class, () -> emailMcpTool.getEmailById(EMAIL_ID));
  }

  /**
   * An id is guessable, and this tool is reached from outside: an agent hands it a
   * number. The refusal has to come from the read itself, so the plain lookup -- which
   * finds a row by technical id alone -- must not be the one used. Asserting the refusal
   * alone would still pass if somebody restored the plain call, hence the never().
   *
   * @throws Exception never thrown; declared by the tool's own contract
   */
  @Test
  void getEmailByIdRefusesSomebodyElsesEmail() throws Exception {
    when(emailBoxService.getOwnedEmailById(eq(EMAIL_ID), eq(USERNAME))).thenThrow(new IllegalAccessException("not yours"));

    assertThrows(IllegalAccessException.class, () -> emailMcpTool.getEmailById(EMAIL_ID));

    verify(emailBoxService, never()).getEmailById(anyLong(), any());
  }

  // --- list_emails ---------------------------------------------------------

  @Test
  void listEmails() throws Exception {
    EmailBox emailBox = new EmailBox();
    emailBox.setEmails(List.of(buildEmail(1L), buildEmail(2L)));
    when(emailBoxService.getEmailBox(eq(USERNAME))).thenReturn(emailBox);

    List<EmailModel> emails = emailMcpTool.listEmails(null, null, null);

    assertNotNull(emails);
    assertEquals(2, emails.size());
    assertEquals("Hello world", emails.get(0).getContent().getBody());
    // list_emails does not expose the user email address
    assertEquals(null, emails.get(0).getUserEmail());
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

    EmailSearchResultsModel results = emailMcpTool.searchEmails("invoice", null, null, null, null, null);

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

    emailMcpTool.searchEmails("invoice", null, null, null, "archive", null);

    verify(emailBoxService).searchEmails(eq(USERNAME), eq("invoice"), isNull(), eq(false), isNull(), eq(MailFolder.ARCHIVE), anyInt());
  }

  @Test
  void searchEmailsTranslatesAMessageCodeIntoSomethingAModelCanAct() throws Exception {
    when(emailBoxService.searchEmails(eq(USERNAME), any(), any(), anyBoolean(), any(), any(), anyInt()))
                                                                                                       .thenThrow(new IllegalArgumentException("emailConnector.search.criteriaRequired"));

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                                                   () -> emailMcpTool.searchEmails(null, null, null, null, null, null));
    // A message code tells a model nothing about what to change.
    assertFalse(thrown.getMessage().contains("emailConnector."));
    assertTrue(thrown.getMessage().contains("query"));
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

    emailMcpTool.searchEmails("invoice", null, null, null, null, null);
    emailMcpTool.searchEmails("invoice", null, null, null, "  ", null);

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
                                                   () -> emailMcpTool.searchEmails("invoice", null, null, null, null, null));
    assertFalse(thrown.getMessage().contains("For input string"));
    assertNotNull(thrown.getCause());

    // A cause-only exception carries a null message: the old getOrDefault(null, null)
    // threw NPE here instead of reporting anything.
    when(emailBoxService.searchEmails(eq(USERNAME), any(), any(), anyBoolean(), any(), any(), anyInt()))
                                                                                                       .thenThrow(new IllegalArgumentException(new IllegalStateException("boom")));
    IllegalArgumentException blank = assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.searchEmails("invoice", null, null, null, null, null));
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
                                                () -> emailMcpTool.searchEmails("invoice", null, null, null, null, null));
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

    List<EmailAttachmentModel> attachments = emailMcpTool.listAttachments(REMOTE_ID);

    assertEquals(1, attachments.size());
    EmailAttachmentModel model = attachments.get(0);
    assertEquals("invoice.pdf", model.getName());
    assertEquals("application/pdf", model.getMimeType());
    assertEquals("1.2", model.getAttachmentId());
    // download_url is the existing authenticated EmailBoxRest endpoint
    assertEquals("/portal/rest/email-box/attachments/" + REMOTE_ID + "/1.2", model.getDownloadUrl());
    // No attachment bytes ever leave the tool: the model must have no data field
    String json = new ObjectMapper().writeValueAsString(model);
    assertFalse(json.toLowerCase().contains("data"), "Attachment payload must not carry bytes");
  }

  @Test
  void listAttachmentsReturnsEmptyWhenNone() throws Exception {
    Email email = buildEmail(EMAIL_ID);
    email.getContent().setAttachments(null);
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(REMOTE_ID), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(false), eq(false), eq(false))).thenReturn(email);

    assertTrue(emailMcpTool.listAttachments(REMOTE_ID).isEmpty());
  }

  // --- mark_read / mark_unread ---------------------------------------------

  @Test
  void markReadDelegatesToService() throws Exception {
    when(emailBoxService.updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(true))).thenReturn(0);
    String message = emailMcpTool.markRead(List.of(REMOTE_ID));
    verify(emailBoxService).updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(true));
    assertEquals("Marked 1 email(s) as read.", message);
  }

  @Test
  void markUnreadDelegatesToService() throws Exception {
    when(emailBoxService.updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX), eq(false), eq(true))).thenReturn(0);
    String message = emailMcpTool.markUnread(List.of(REMOTE_ID));
    verify(emailBoxService).updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX), eq(false), eq(true));
    assertEquals("Marked 1 email(s) as unread.", message);
  }

  @Test
  void markReadReportsAllFailuresAsFailure() throws Exception {
    when(emailBoxService.updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(true))).thenReturn(1);
    String message = emailMcpTool.markRead(List.of(REMOTE_ID));
    // When every email fails, the message must be phrased as a clear failure and
    // must not claim any success.
    assertTrue(message.startsWith("Failed to mark 1 email(s) as read"), message);
    assertFalse(message.contains("Marked 1"), message);
  }

  @Test
  void markReadReportsPartialFailure() throws Exception {
    List<Long> ids = List.of(REMOTE_ID, 888L);
    when(emailBoxService.updateEmailReadStatus(eq(ids), eq(USERNAME), eq(MailFolder.INBOX), eq(true), eq(true))).thenReturn(1);
    String message = emailMcpTool.markRead(ids);
    assertEquals("Marked 1 of 2 email(s) as read; 1 failed (message not found on server or IMAP write denied).", message);
  }

  // --- send_email ----------------------------------------------------------

  @Test
  void sendEmailBuildsMessageAndSends() throws Exception {
    emailMcpTool.sendEmail(List.of("bob@example.com"),
                           "Hi",
                           "<p>Body</p>",
                           List.of("carol@example.com"),
                           List.of("dan@example.com"));

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
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.sendEmail(List.of(), "Hi", "<p>Body</p>", null, null));
  }

  // --- reply_email ---------------------------------------------------------

  @Test
  void replyEmailThreadsAndTargetsSender() throws Exception {
    Email original = buildEmail(EMAIL_ID);
    original.setMailHeaderId("<original-message-id@server>");
    original.setSubject("Question");
    original.setSender(new EmailSender("Alice", "alice@example.com", null, null));
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(REMOTE_ID), eq(USERNAME), eq(MailFolder.INBOX), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(original);

    emailMcpTool.replyEmail(REMOTE_ID, "<p>My answer</p>");

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

    emailMcpTool.replyAll(REMOTE_ID, "<p>Reply all body</p>");

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

    List<EmailThreadMessageModel> thread = emailMcpTool.getEmailThread("thread-1");

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

    List<EmailThreadMessageModel> thread = emailMcpTool.getEmailThread("thread-1");

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

    List<EmailThreadMessageModel> thread = emailMcpTool.getEmailThread("thread-1");

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

    String body = emailMcpTool.getEmailThread("thread-1").get(0).getBody();

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

    assertEquals(List.of("contract.pdf"), emailMcpTool.getEmailThread("thread-1").get(0).getAttachmentNames());
  }

  /**
   * A call with no conversation named is refused in words the caller can act on, since
   * the caller is the one that has to correct it.
   */
  @Test
  void getEmailThreadWithoutAThreadIdIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.getEmailThread(" "));
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
    String result = emailMcpTool.archiveEmail(List.of(REMOTE_ID));
    verify(emailBoxService).archiveEmail(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX));
    assertTrue(result.contains("Archived 1 of 1"));
  }

  @Test
  void deleteEmailDelegatesToService() throws Exception {
    when(emailBoxService.deleteEmail(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX))).thenReturn(0);
    String result = emailMcpTool.deleteEmail(List.of(REMOTE_ID));
    verify(emailBoxService).deleteEmail(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(MailFolder.INBOX));
    assertTrue(result.contains("Deleted 1 of 1"));
  }
}
