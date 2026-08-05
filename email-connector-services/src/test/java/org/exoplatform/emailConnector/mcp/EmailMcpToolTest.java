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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
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
    when(emailBoxService.getEmailById(eq(EMAIL_ID), eq(USERNAME))).thenReturn(buildEmail(EMAIL_ID));

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
    when(emailBoxService.getEmailById(eq(EMAIL_ID), eq(USERNAME))).thenReturn(null);
    assertThrows(ObjectNotFoundException.class, () -> emailMcpTool.getEmailById(EMAIL_ID));
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
  void searchEmailsFiltersInMemory() throws Exception {
    Email read = buildEmail(1L);
    read.setRead(true);
    read.setSubject("Weekly report");
    Email unread = buildEmail(2L);
    unread.setRead(false);
    unread.setSubject("Invoice due");
    unread.setSender(new EmailSender("Alice", "alice@example.com", null, null));
    EmailBox emailBox = new EmailBox();
    emailBox.setEmails(List.of(read, unread));
    when(emailBoxService.getEmailBox(eq(USERNAME))).thenReturn(emailBox);

    // unread only
    List<EmailModel> unreadOnly = emailMcpTool.searchEmails(null, true, null);
    assertEquals(1, unreadOnly.size());
    assertEquals("Invoice due", unreadOnly.get(0).getSubject());

    // query over subject
    List<EmailModel> byQuery = emailMcpTool.searchEmails("weekly", null, null);
    assertEquals(1, byQuery.size());
    assertEquals("Weekly report", byQuery.get(0).getSubject());

    // from filter over sender address
    List<EmailModel> byFrom = emailMcpTool.searchEmails(null, null, "alice@");
    assertEquals(1, byFrom.size());
    assertEquals("Invoice due", byFrom.get(0).getSubject());
  }

  // --- list_attachments ----------------------------------------------------

  @Test
  void listAttachmentsReturnsMetadataAndDownloadUrlWithoutBytes() throws Exception {
    Email email = buildEmail(EMAIL_ID);
    EmailAttachment attachment = new EmailAttachment(1L, REMOTE_ID, "1.2", "invoice.pdf", "application/pdf", new byte[] { 1, 2, 3 });
    email.getContent().setAttachments(List.of(attachment));
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(REMOTE_ID), eq(USERNAME), eq(true), eq(false), eq(false), eq(false))).thenReturn(email);

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
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(REMOTE_ID), eq(USERNAME), eq(true), eq(false), eq(false), eq(false))).thenReturn(email);

    assertTrue(emailMcpTool.listAttachments(REMOTE_ID).isEmpty());
  }

  // --- mark_read / mark_unread ---------------------------------------------

  @Test
  void markReadDelegatesToService() throws Exception {
    when(emailBoxService.updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(true), eq(true))).thenReturn(0);
    String message = emailMcpTool.markRead(List.of(REMOTE_ID));
    verify(emailBoxService).updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(true), eq(true));
    assertEquals("Marked 1 email(s) as read.", message);
  }

  @Test
  void markUnreadDelegatesToService() throws Exception {
    when(emailBoxService.updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(false), eq(true))).thenReturn(0);
    String message = emailMcpTool.markUnread(List.of(REMOTE_ID));
    verify(emailBoxService).updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(false), eq(true));
    assertEquals("Marked 1 email(s) as unread.", message);
  }

  @Test
  void markReadReportsAllFailuresAsFailure() throws Exception {
    when(emailBoxService.updateEmailReadStatus(eq(List.of(REMOTE_ID)), eq(USERNAME), eq(true), eq(true))).thenReturn(1);
    String message = emailMcpTool.markRead(List.of(REMOTE_ID));
    // When every email fails, the message must be phrased as a clear failure and
    // must not claim any success.
    assertTrue(message.startsWith("Failed to mark 1 email(s) as read"), message);
    assertFalse(message.contains("Marked 1"), message);
  }

  @Test
  void markReadReportsPartialFailure() throws Exception {
    List<Long> ids = List.of(REMOTE_ID, 888L);
    when(emailBoxService.updateEmailReadStatus(eq(ids), eq(USERNAME), eq(true), eq(true))).thenReturn(1);
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
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(REMOTE_ID), eq(USERNAME), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(original);

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
    when(emailBoxService.getEmailByMailRemoteIdAndUserId(eq(REMOTE_ID), eq(USERNAME), anyBoolean(), anyBoolean(), anyBoolean(), anyBoolean())).thenReturn(original);

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

  // --- archive_email / delete_email ----------------------------------------

  @Test
  void archiveEmailDelegatesToService() throws Exception {
    when(emailBoxService.archiveEmail(eq(List.of(REMOTE_ID)), eq(USERNAME))).thenReturn(0);
    String result = emailMcpTool.archiveEmail(List.of(REMOTE_ID));
    verify(emailBoxService).archiveEmail(eq(List.of(REMOTE_ID)), eq(USERNAME));
    assertTrue(result.contains("Archived 1 of 1"));
  }

  @Test
  void deleteEmailDelegatesToService() throws Exception {
    when(emailBoxService.deleteEmail(eq(List.of(REMOTE_ID)), eq(USERNAME))).thenReturn(0);
    String result = emailMcpTool.deleteEmail(List.of(REMOTE_ID));
    verify(emailBoxService).deleteEmail(eq(List.of(REMOTE_ID)), eq(USERNAME));
    assertTrue(result.contains("Deleted 1 of 1"));
  }
}
