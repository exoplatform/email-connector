/**
 * Copyright (C) 2025 eXo Platform SAS
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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.UIDFolder;
import javax.mail.internet.MimeMessage;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.scheduler.JobInfo;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.services.scheduler.PeriodInfo;

import io.meeds.social.category.service.CategoryLinkService;
import io.meeds.social.category.service.CategoryService;
import lombok.SneakyThrows;

@SpringBootTest(classes = { EmailBoxService.class })
@ExtendWith(MockitoExtension.class)
public class EmailBoxServiceTest {

  private static final String     TEST_USER = "testuser";

  @MockBean
  private UserEmailSettingService userEmailSettingService;

  @MockBean
  private EmailBoxStorage         emailBoxStorage;

  @MockBean
  private SettingService          settingService;

  @MockBean
  private JobSchedulerService     jobSchedulerService;

  @MockBean
  private ListenerService         listenerService;

  @MockBean
  private EmailConnectorService   emailConnectorService;

  @MockBean
  private CategoryLinkService     categoryLinkService;

  @MockBean
  private CategoryService         categoryService;

  @Autowired
  private EmailBoxService         emailBoxService;

  @Test
  @SneakyThrows
  void synchronize() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    MimeMessage message1 = mock(MimeMessage.class);
    when(message1.getSubject()).thenReturn("message1Subject");
    MimeMessage message2 = mock(MimeMessage.class);
    when(message2.getSubject()).thenReturn("message2Subject");
    MimeMessage[] messages = { message1, message2 };
    when((inbox).getMessages(anyInt(), anyInt())).thenReturn(messages);
    when(emailBoxStorage.getEmails(anyString())).thenReturn(new ArrayList<Email>());
    emailBoxService.synchronize(TEST_USER);
    verify(userEmailSettingService, times(2)).setUserEmailSetting(any(UserEmailSetting.class), anyString(), anyBoolean());
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(100);
    when(inbox.getMessageCount()).thenReturn(1000);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage, times(2)).createEmail(any(Email.class));
    verify(inbox, times(2)).close(false);
    verify(store, times(2)).close();
  }

  @Test
  void getEmailBox() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.getEmailBox(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.getEmailBox(TEST_USER);
    verify(userEmailSettingService, times(2)).getUserEmailSetting(TEST_USER);
    verify(emailBoxStorage).getEmails(TEST_USER);
  }

  @Test
  void deleteUserEmails() {
    emailBoxService.deleteUserEmails(TEST_USER);
    verify(emailBoxStorage).getEmails(TEST_USER);
    verify(emailBoxStorage).deleteEmailsByIds(anyList());
  }

  @Test
  void scheduleEmailBoxUserSyncJob() throws Exception {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    emailBoxService.scheduleEmailBoxUserSyncJob(TEST_USER);
    verify(jobSchedulerService).removeJob(any(JobInfo.class));
    verify(jobSchedulerService).addPeriodJob(any(JobInfo.class), any(PeriodInfo.class));
  }

  @Test
  void broadcastOpenEmail() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.broadcastOpenEmail(TEST_USER);
    verify(listenerService).broadcast(EmailConnectorUtils.OPEN_EMAIL, TEST_USER, "connector");
  }

  @Test
  void broadcastAccessWebmail() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.broadcastAccessWebmail(TEST_USER);
    verify(listenerService).broadcast(EmailConnectorUtils.ACCESS_WEBMAIL, TEST_USER, "connector");
  }

  @Test
  void getEmailByMailRemoteIdAndUserId() throws IllegalAccessException {
    emailBoxService.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, false, false, false, false);
    verify(emailBoxStorage).getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, false, false, false);
  }

  @Test
  void getEmailById() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    emailBoxService.getEmailById(121l, TEST_USER);
    verify(emailBoxStorage).getEmailById(121l, TEST_USER, "testEmail");
  }

  @Test
  void updateEmailReadStatus() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    List<Long> mailRemoteIds = List.of(1212l);
    assertThrows(IllegalAccessException.class,
                 () -> emailBoxService.updateEmailReadStatus(mailRemoteIds, TEST_USER, true, false));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.updateEmailReadStatus(mailRemoteIds, TEST_USER, true, false);
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(mailRemoteIds, TEST_USER, true);
    reset(emailBoxStorage);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    int failed = emailBoxService.updateEmailReadStatus(mailRemoteIds, TEST_USER, false, true);
    org.junit.jupiter.api.Assertions.assertEquals(0, failed);
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(mailRemoteIds, TEST_USER, false);
    verify(inbox).open(Folder.READ_WRITE);
    verify(message).setFlag(Flags.Flag.SEEN, false);
    verify(inbox).close(false);
    verify(store).close();

    // getMessageByUID returns null (UID unknown to the server): the remote update
    // must be counted as a failure and the optimistic local change reverted.
    reset(emailBoxStorage);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(null);
    int failedWhenNotFound = emailBoxService.updateEmailReadStatus(mailRemoteIds, TEST_USER, true, true);
    org.junit.jupiter.api.Assertions.assertEquals(1, failedWhenNotFound);
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(mailRemoteIds, TEST_USER, true);
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(List.of(1212l), TEST_USER, false);
  }

  @Test
  void deleteEmail() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    List<Long> emailIds = List.of(1212l);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.deleteEmail(emailIds, TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email email = email(TEST_USER);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, true, true, false)).thenReturn(email);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    IMAPFolder inbox = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    when(store.isConnected()).thenReturn(true);
    IMAPFolder trashFolder = mock(IMAPFolder.class);
    when(trashFolder.getFullName()).thenReturn("trash");
    Folder[] folders = new Folder[] { trashFolder };
    when(folder.listSubscribed("*")).thenReturn(folders);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    when(trashFolder.exists()).thenReturn(true);
    when(trashFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    emailBoxService.deleteEmail(emailIds, TEST_USER);
    verify(emailBoxStorage).deleteEmailsByIds(anyList());
    verify(inbox).open(Folder.READ_WRITE);
    verify(message).setFlag(Flags.Flag.DELETED, true);
    verify(inbox).copyMessages(any(Message[].class), any(Folder.class));
    verify(inbox).close(true);
    verify(store).close();
  }

  @Test
  void archiveEmail() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    List<Long> emailIds = List.of(1212l);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.archiveEmail(emailIds, TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email email = email(TEST_USER);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, true, true, false)).thenReturn(email);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    IMAPFolder inbox = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    when(store.isConnected()).thenReturn(true);
    IMAPFolder archiveFolder = mock(IMAPFolder.class);
    when(archiveFolder.getFullName()).thenReturn("archive");
    Folder[] folders = new Folder[] { archiveFolder };
    when(folder.listSubscribed("*")).thenReturn(folders);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    when(archiveFolder.exists()).thenReturn(true);
    when(archiveFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    emailBoxService.archiveEmail(emailIds, TEST_USER);
    verify(emailBoxStorage).deleteEmailsByIds(anyList());
    verify(inbox).open(Folder.READ_WRITE);
    verify(inbox).copyMessages(any(Message[].class), any(Folder.class));
    verify(inbox).close(true);
    verify(store).close();
  }

  @Test
  void sendEmail() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    Email email = email(TEST_USER);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.sendEmail(email, TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    Session session = mock(Session.class);
    when(session.getProperties()).thenReturn(new Properties());
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    when(store.isConnected()).thenReturn(true);
    IMAPFolder sentFolder = mock(IMAPFolder.class);
    when(sentFolder.getFullName()).thenReturn("sent");
    Folder[] folders = new Folder[] { sentFolder };
    when(folder.listSubscribed("*")).thenReturn(folders);
    when(sentFolder.exists()).thenReturn(true);
    when(sentFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    when(sentFolder.isOpen()).thenReturn(true);
    try (MockedStatic<Session> sessionMock = mockStatic(Session.class);
        MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      sessionMock.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class))).thenReturn(session);
      emailBoxService.sendEmail(email, TEST_USER);
      transportMock.verify(() -> Transport.send(any(Message.class)));
      verify(sentFolder).open(Folder.READ_WRITE);
      verify(sentFolder).appendMessages(any(Message[].class));
      verify(sentFolder).close(false);
      verify(store).close();
    }
  }

  @Test
  void sendReplyBuildsFullReferencesChain() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    when(emailConnectorService.getEmailConnector(anyLong())).thenReturn(emailConnector());
    // a reply whose parent already referenced <root@host>
    Email reply = email(TEST_USER);
    reply.setMailHeaderId("<parent@host>");
    when(emailBoxStorage.getMailReferencesByMailHeaderId("<parent@host>", TEST_USER)).thenReturn("<root@host>");
    Session session = mock(Session.class);
    when(session.getProperties()).thenReturn(new Properties());
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    when(store.isConnected()).thenReturn(true);
    IMAPFolder sentFolder = mock(IMAPFolder.class);
    when(sentFolder.getFullName()).thenReturn("sent");
    when(folder.listSubscribed("*")).thenReturn(new Folder[] { sentFolder });
    when(sentFolder.exists()).thenReturn(true);
    when(sentFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    when(sentFolder.isOpen()).thenReturn(true);
    try (MockedStatic<Session> sessionMock = mockStatic(Session.class);
        MockedStatic<Transport> transportMock = mockStatic(Transport.class)) {
      sessionMock.when(() -> Session.getInstance(any(Properties.class), any(Authenticator.class))).thenReturn(session);
      emailBoxService.sendEmail(reply, TEST_USER);
      ArgumentCaptor<Message> sentCaptor = ArgumentCaptor.forClass(Message.class);
      transportMock.verify(() -> Transport.send(sentCaptor.capture()));
      Message sent = sentCaptor.getValue();
      // In-Reply-To is the parent; References is parent's chain + parent id (RFC 5322 §3.6.4)
      org.junit.jupiter.api.Assertions.assertEquals("<parent@host>", sent.getHeader("In-Reply-To")[0]);
      org.junit.jupiter.api.Assertions.assertEquals("<root@host> <parent@host>", sent.getHeader("References")[0]);
    }
  }

  @Test
  void getAttachmentByMailRemoteIdAnId() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    assertThrows(IllegalStateException.class,
                 () -> emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER));
    when(message.isMimeType("multipart/*")).thenReturn(true);
    Multipart multipart = mock(Multipart.class);
    when(message.getContent()).thenReturn(multipart);
    when(multipart.getCount()).thenReturn(1);
    assertThrows(IllegalStateException.class,
                 () -> emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER));
    when(multipart.getCount()).thenReturn(2);
    BodyPart bodyPart = mock(BodyPart.class);
    when(multipart.getBodyPart(anyInt())).thenReturn(bodyPart);
    InputStream is = mock(InputStream.class);
    when(bodyPart.getInputStream()).thenReturn(is);
    when(bodyPart.getContentType()).thenReturn("application/pdf");
    when(bodyPart.getFileName()).thenReturn("attachment.pdf");
    when(is.read(any(byte[].class))).thenReturn(1024).thenReturn(-1);
    EmailAttachment emailAtatchment = mock(EmailAttachment.class);
    when(emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER)).thenReturn(emailAtatchment);
    emailBoxService.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER);
    verify(emailBoxStorage, times(3)).getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", TEST_USER);
    verify(emailAtatchment).setName("attachment.pdf");
    verify(emailAtatchment).setMimeType("application/pdf");
  }

  private UserEmailSetting userEmailSetting() {
    return new UserEmailSetting("1", "testEmail", "testPassword", null, null, 0, 0L, null, null, "connector", true);
  }

  private Email email(String username) {
    return new Email(null,
                     1212l,
                     null,
                     username,
                     null,
                     "subject",
                     new EmailContent("excerpt"),
                     new Date(),
                     new EmailSender("sender", null, null, null),
                     false,
                     false,
                     List.of(new EmailRecipient()),
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     "INBOX");
  }

  private EmailConnector emailConnector() {
    return new EmailConnector(null,
                              "testName",
                              null,
                              1L,
                              null,
                              "testImapUrl",
                              "8000",
                              "testSmtpUrl",
                              "9000",
                              "STARTTLS",
                              true,
                              false,
                              true,
                              "testUploadId",
                              "");
  }
}
