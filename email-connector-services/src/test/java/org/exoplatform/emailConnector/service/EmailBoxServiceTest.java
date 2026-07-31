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
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessageRemovedException;
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
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.SyncStatus;
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
    when(emailBoxStorage.getEmails(anyString(), anyString())).thenReturn(new ArrayList<Email>());
    // No subscribed Sent/Archive folders in the test store, so those syncs are no-ops.
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
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
    verify(emailBoxStorage).getEmails(TEST_USER, "INBOX");
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
    verify(emailBoxStorage).getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, "INBOX", false, false, false);
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
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(mailRemoteIds, TEST_USER, true, "INBOX");
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
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(mailRemoteIds, TEST_USER, false, "INBOX");
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
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(mailRemoteIds, TEST_USER, true, "INBOX");
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(List.of(1212l), TEST_USER, false, "INBOX");
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
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, "INBOX", true, true, false)).thenReturn(email);
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
  void deleteEmailToleratesGmailTrashExpunge() throws Exception {
    // On Gmail, copying into [Gmail]/Trash moves the message and expunges the source,
    // so the following setFlag(DELETED) throws MessageRemovedException. The delete has
    // in fact succeeded, so it must NOT be counted as a failure nor re-insert the row.
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email email = email(TEST_USER);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, "INBOX", true, true, false)).thenReturn(email);
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
    when(folder.listSubscribed("*")).thenReturn(new Folder[] { trashFolder });
    when(trashFolder.exists()).thenReturn(true);
    when(trashFolder.getAttributes()).thenReturn(ArrayUtils.EMPTY_STRING_ARRAY);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    doThrow(new MessageRemovedException()).when(message).setFlag(Flags.Flag.DELETED, true);

    emailBoxService.deleteEmail(List.of(1212l), TEST_USER);

    verify(inbox).copyMessages(any(Message[].class), any(Folder.class));
    verify(emailBoxStorage).deleteEmailsByIds(anyList());
    // The delete succeeded on the server, so the compensating re-insert must NOT fire.
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
  }

  @Test
  void getThreadReadsCacheWithoutImap() throws Exception {
    // getThread is the fast path: a pure cache read, never an IMAP connection, so the
    // reader can render the conversation instantly on open.
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email cached = email(TEST_USER);
    cached.setMailHeaderId("<self@host>");
    cached.setThreadId("<self@host>");
    when(emailBoxStorage.getEmailsByThreadId(anyString(), anyString(), anyString())).thenReturn(List.of(cached));

    List<Email> thread = emailBoxService.getThread("<self@host>", TEST_USER);

    assertEquals(1, thread.size());
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  @Test
  void completeThreadSkipsArchiveLookupWhenNothingMissing() throws Exception {
    // A conversation whose cached messages reference nothing external needs no archive
    // lookup — completeThread must not open an IMAP connection (keeps repeat opens fast).
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email cached = email(TEST_USER);
    cached.setMailHeaderId("<self@host>");
    cached.setThreadId("<self@host>");
    when(emailBoxStorage.getEmailsByThreadId(anyString(), anyString(), anyString())).thenReturn(List.of(cached));

    List<Email> thread = emailBoxService.completeThread("<self@host>", TEST_USER);

    assertEquals(1, thread.size());
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  @Test
  void completeThreadRecoversArchivedAncestorFromAllMail() throws Exception {
    // The cached message references an ancestor we never synced (archived in Gmail).
    // completeThread must fetch it from All Mail, cache it under ALL_MAIL, and unify
    // the fragments INTO the opened thread id (not flip to the older root id).
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email cached = email(TEST_USER);
    cached.setMailHeaderId("<reply@host>");
    cached.setThreadId("<reply@host>");
    cached.setMailReferences("<root@host>");
    when(emailBoxStorage.getEmailsByThreadId(anyString(), anyString(), anyString())).thenReturn(List.of(cached));

    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    IMAPFolder allMail = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(allMail.exists()).thenReturn(true);
    when(allMail.getAttributes()).thenReturn(new String[] { "\\All" });
    when(allMail.isOpen()).thenReturn(true);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[] { allMail });
    MimeMessage archived = mock(MimeMessage.class);
    when(archived.getMessageID()).thenReturn("<root@host>");
    when(archived.getSubject()).thenReturn("root subject");
    when(allMail.search(any())).thenReturn(new Message[] { archived });
    when(((UIDFolder) allMail).getUID(archived)).thenReturn(999l);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(999l, TEST_USER, null, "ALL_MAIL", false, false, false)).thenReturn(null);
    when(emailBoxStorage.getSiblingThreadIds(anyString(), anyList())).thenReturn(List.of("<reply@host>", "<root@host>"));

    emailBoxService.completeThread("<reply@host>", TEST_USER);

    verify(allMail).search(any());
    // The archived ancestor is persisted under ALL_MAIL and the fragments are unified
    // into the opened id "<reply@host>".
    verify(emailBoxStorage).createEmail(any(Email.class));
    verify(emailBoxStorage).mergeThreads(TEST_USER, "<reply@host>", List.of("<root@host>"));
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
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, null, "INBOX", true, true, false)).thenReturn(email);
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

  @Test
  void shouldNotifyForNewEmailNotifyAllTrue() {
    UserEmailSetting setting = userEmailSetting();
    setting.setNotifyAllCategories(Boolean.TRUE);
    setting.setNotifyCategories(List.of(1L));
    // Notify-all wins even when the email's category is not among the selected ones.
    assertEquals(true, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(List.of(99L)), setting));
  }

  @Test
  void shouldNotifyForNewEmailNotifyAllNull() {
    UserEmailSetting setting = userEmailSetting();
    setting.setNotifyAllCategories(null);
    setting.setNotifyCategories(List.of(1L));
    // Null resolves to "notify for everything" (the default).
    assertEquals(true, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(List.of(99L)), setting));
  }

  @Test
  void shouldNotifyForNewEmailSelectedCategoriesMatch() {
    UserEmailSetting setting = userEmailSetting();
    setting.setNotifyAllCategories(Boolean.FALSE);
    setting.setNotifyCategories(List.of(1L, 2L));
    assertEquals(true, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(List.of(2L, 5L)), setting));
  }

  @Test
  void shouldNotifyForNewEmailSelectedCategoriesNoMatch() {
    UserEmailSetting setting = userEmailSetting();
    setting.setNotifyAllCategories(Boolean.FALSE);
    setting.setNotifyCategories(List.of(1L, 2L));
    assertEquals(false, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(List.of(5L)), setting));
  }

  @Test
  void shouldNotifyForNewEmailUncategorizedFallback() {
    UserEmailSetting setting = userEmailSetting();
    setting.setNotifyAllCategories(Boolean.FALSE);
    setting.setNotifyCategories(List.of(1L, 2L));
    // Fallback: an uncategorized email (also the AI-off case) always notifies.
    assertEquals(true, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(null), setting));
    assertEquals(true, emailBoxService.shouldNotifyForNewEmail(emailWithCategories(List.of()), setting));
  }

  @Test
  void countedDeferralFiresOnlyOnTheLastRelease() {
    // Distinct user: the notification scheduler is shared across the test class, and a
    // stray timer from another test must not perturb the per-user verifications below.
    String user = "counteduser";
    // A streamed sync: window opened, three groups claimed by the classifier, window
    // completed -- the notification belongs to whichever release empties the window.
    emailBoxService.openNotificationWindow(user, List.of());
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.completeNotificationWindow(user, List.of());
    emailBoxService.notifyNewEmailsClassified(user);
    emailBoxService.notifyNewEmailsClassified(user);
    // Two of three groups classified: the messages of the third are still uncategorized,
    // so the per-category preference cannot be applied yet and nothing may fire.
    verify(emailBoxStorage, never()).getEmails(user, MailFolder.INBOX);
    emailBoxService.notifyNewEmailsClassified(user);
    // The last release empties the completed window: the send runs (observed through its
    // mailbox re-read), exactly once.
    verify(emailBoxStorage, times(1)).getEmails(user, MailFolder.INBOX);
  }

  @Test
  void countedDeferralIgnoresTransientZeroWhileSyncStillRuns() {
    String user = "earlyzerouser";
    emailBoxService.openNotificationWindow(user, List.of());
    // The classifier finishes the first group before the second is even broadcast: the
    // claim count touches zero while the download is still running. Firing here is the
    // bug streaming introduces -- the window being open must hold the notification.
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.notifyNewEmailsClassified(user);
    verify(emailBoxStorage, never()).getEmails(user, MailFolder.INBOX);
    // The next group arrives, the sync ends, and only then does its release fire.
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.completeNotificationWindow(user, List.of());
    verify(emailBoxStorage, never()).getEmails(user, MailFolder.INBOX);
    emailBoxService.notifyNewEmailsClassified(user);
    verify(emailBoxStorage, times(1)).getEmails(user, MailFolder.INBOX);
  }

  @Test
  void partitionUidsBalancesContiguousChunks() {
    List<Long> uids = List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L);
    List<long[]> chunks = EmailBoxService.partitionUids(uids, 3);
    assertEquals(3, chunks.size());
    // Balanced sizes, and the concatenation preserves the mailbox order exactly.
    assertArrayEquals(new long[] { 1L, 2L, 3L, 4L }, chunks.get(0));
    assertArrayEquals(new long[] { 5L, 6L, 7L }, chunks.get(1));
    assertArrayEquals(new long[] { 8L, 9L, 10L }, chunks.get(2));
  }

  @Test
  void partitionUidsWithFewerUidsThanChunks() {
    List<long[]> chunks = EmailBoxService.partitionUids(List.of(7L, 9L), 5);
    assertEquals(2, chunks.size());
    assertArrayEquals(new long[] { 7L }, chunks.get(0));
    assertArrayEquals(new long[] { 9L }, chunks.get(1));
    assertTrue(EmailBoxService.partitionUids(List.of(), 5).isEmpty());
    assertTrue(EmailBoxService.partitionUids(List.of(1L), 0).isEmpty());
  }

  @Test
  @SneakyThrows
  void synchronizeSkipsBodyPrefetchForSmallSync() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    mockInboxForSync(userEmailSetting, 3);
    emailBoxService.synchronize(TEST_USER);
    // Below the minimum batch the extra connections cost more than they save: the
    // parallel path must not run, and the bodies come from the serial loop as before.
    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class), any(EmailConnector.class));
    verify(emailBoxStorage, times(3)).createEmail(any(Email.class));
    // The serial path streams nothing: one NEW_EMAILS_SYNCED with the whole sync's new
    // messages, then the completion event closing the run.
    verify(listenerService).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNCED),
                                      eq(TEST_USER),
                                      argThat((List<Long> group) -> group.size() == 3));
    verify(listenerService).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED),
                                      eq(TEST_USER),
                                      argThat((List<Long> all) -> all.size() == 3));
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void synchronizeSkipsBodyPrefetchWhenSingleWorkerConfigured() {
    System.setProperty("email.connector.sync.body.fetch.threads", "1");
    try {
      UserEmailSetting userEmailSetting = userEmailSetting();
      mockInboxForSync(userEmailSetting, 12);
      emailBoxService.synchronize(TEST_USER);
      verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class), any(EmailConnector.class));
      verify(emailBoxStorage, times(12)).createEmail(any(Email.class));
    } finally {
      System.clearProperty("email.connector.sync.body.fetch.threads");
    }
  }

  @Test
  @SneakyThrows
  void synchronizePrefetchesBodiesOverParallelConnections() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    mockInboxForSync(userEmailSetting, 100);
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector);
    // The workers' own connection: same folder re-opened by full name, its messages
    // resolved by UID. Every worker message carries a distinguishable body, while the
    // main connection's messages have none — so a created email carrying a
    // "prefetched-<uid>" body proves the parallel map was used, not the serial fetch.
    Store workerStore = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting, emailConnector)).thenReturn(workerStore);
    when(workerStore.isConnected()).thenReturn(true);
    Folder workerFolder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(workerStore.getFolder("INBOX")).thenReturn(workerFolder);
    when(workerFolder.isOpen()).thenReturn(true);
    Map<Long, Message> workerMessages = new HashMap<>();
    for (long uid = 1; uid <= 100; uid++) {
      MimeMessage workerMessage = mock(MimeMessage.class);
      when(workerMessage.isMimeType("text/*")).thenReturn(true);
      when(workerMessage.getContent()).thenReturn("prefetched-" + uid);
      when(((UIDFolder) workerFolder).getUID(workerMessage)).thenReturn(uid);
      workerMessages.put(uid, workerMessage);
    }
    // Read-only answer (all stubbing done above) so concurrent workers never stub.
    when(((UIDFolder) workerFolder).getMessagesByUID(any(long[].class))).thenAnswer(invocation -> {
      long[] uids = invocation.getArgument(0);
      Message[] result = new Message[uids.length];
      for (int i = 0; i < uids.length; i++) {
        result[i] = workerMessages.get(uids[i]);
      }
      return result;
    });
    emailBoxService.synchronize(TEST_USER);
    // 100 new UIDs cut into slices of 20 = 5 slices, each fetched on its own connection
    // and every one closed when its worker finished.
    verify(userEmailSettingService, times(5)).connect(userEmailSetting, emailConnector);
    verify(workerFolder, times(5)).close(false);
    verify(workerStore, times(5)).close();
    ArgumentCaptor<Email> emailCaptor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage, times(100)).createEmail(emailCaptor.capture());
    // Bodies come from the workers, and -- the property that matters for threading --
    // the rows are still cached oldest-first even though the slices finish in any order:
    // computeThreadId attaches a reply by looking up the messages already stored, so a
    // reply cached before its parent would silently start a second conversation.
    long previousUid = 0;
    for (Email created : emailCaptor.getAllValues()) {
      assertEquals("prefetched-" + created.getMailRemoteId(), created.getContent().getBody());
      assertTrue(created.getMailRemoteId() > previousUid, "emails must be cached in ascending UID order");
      previousUid = created.getMailRemoteId();
    }
    // The new-emails events stream out during the download, one group every three slices
    // (3 x 20 = 60, then the remaining 40), so the AI categorization can start on the
    // first messages while the later ones are still being fetched...
    @SuppressWarnings({ "unchecked", "rawtypes" })
    ArgumentCaptor<List<Long>> groupCaptor = ArgumentCaptor.forClass((Class) List.class);
    verify(listenerService, times(2)).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNCED), eq(TEST_USER), groupCaptor.capture());
    List<List<Long>> groups = groupCaptor.getAllValues();
    assertEquals(60, groups.get(0).size());
    assertEquals(40, groups.get(1).size());
    assertEquals(1L, groups.get(0).get(0));
    assertEquals(100L, groups.get(1).get(39));
    // ...and the completion event closes the run with every id, which is what whole-run
    // consumers (conversation alignment) key off.
    verify(listenerService).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED),
                                      eq(TEST_USER),
                                      argThat((List<Long> all) -> all.size() == 100));
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void synchronizeSurvivesPrefetchWorkerFailure() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    mockInboxForSync(userEmailSetting, 12);
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector);
    // Every worker connection fails outright: the sync must neither fail nor lose
    // messages — the serial loop fetches every body itself, as before the prefetch.
    when(userEmailSettingService.connect(userEmailSetting, emailConnector)).thenThrow(new javax.mail.AuthenticationFailedException("Too many simultaneous connections"));
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage, times(12)).createEmail(any(Email.class));
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  /**
   * Wires the mocks for a {@code synchronize()} run over an inbox of {@code count}
   * brand-new messages with UIDs {@code 1..count} (nothing cached locally, no
   * Sent/Archive folders on the store).
   */
  @SneakyThrows
  private Folder mockInboxForSync(UserEmailSetting userEmailSetting, int count) {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    // Only the parallel-prefetch tests reach getFullName; lenient so the skip-path
    // tests sharing this fixture do not trip strict-stubbing.
    lenient().when(inbox.getFullName()).thenReturn("INBOX");
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(100);
    when(inbox.getMessageCount()).thenReturn(count);
    MimeMessage[] messages = new MimeMessage[count];
    for (int i = 0; i < count; i++) {
      messages[i] = mock(MimeMessage.class);
      when(((UIDFolder) inbox).getUID(messages[i])).thenReturn((long) (i + 1));
    }
    when(inbox.getMessages(anyInt(), anyInt())).thenReturn(messages);
    when(emailBoxStorage.getEmails(anyString(), anyString())).thenReturn(new ArrayList<>());
    // No subscribed Sent/Archive folders in the test store, so those syncs are no-ops.
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
    return inbox;
  }

  private Email emailWithCategories(List<Long> categoryIds) {
    Email email = email(TEST_USER);
    email.setCategoryIds(categoryIds);
    return email;
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
                     "INBOX",
                     null,
                     false,
                     false,
                     false,
                     false,
                     null);
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
