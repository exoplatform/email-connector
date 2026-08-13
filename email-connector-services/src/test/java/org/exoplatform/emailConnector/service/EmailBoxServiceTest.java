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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.LongStream;

import javax.mail.Address;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessageRemovedException;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeMessage;
import javax.mail.search.AndTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.FlagTerm;
import javax.mail.search.FromStringTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;

import org.apache.commons.lang3.ArrayUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.util.ReflectionTestUtils;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.ResyncData;

import org.exoplatform.commons.ObjectAlreadyExistsException;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.FolderSyncSnapshot;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailboxSyncState;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSearchResult;
import org.exoplatform.emailConnector.model.EmailSearchResultPage;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.plugin.EmailCategoryPlugin;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.scheduler.JobInfo;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.services.scheduler.PeriodInfo;

import io.meeds.social.category.model.Category;
import io.meeds.social.category.model.CategoryObject;
import io.meeds.social.category.model.CategoryWithName;
import io.meeds.social.category.service.CategoryLinkService;
import io.meeds.social.category.service.CategoryService;
import io.meeds.social.util.JsonUtils;
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
    when(emailBoxStorage.getSyncEmails(anyString(), anyString())).thenReturn(new ArrayList<Email>());
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
  void getDefaultEmailCategoryIds() {
    // The importer has not run yet: no nameId -> id mapping is stored at all.
    assertTrue(emailBoxService.getDefaultEmailCategoryIds().isEmpty());

    // A stored id is returned, a missing one is skipped, and a malformed one is
    // ignored instead of propagating a NumberFormatException.
    mockCategoryIdSetting("emailImportantCategory", "11");
    mockCategoryIdSetting("emailInvitationCategory", null);
    mockCategoryIdSetting("emailNotificationCategory", "notANumber");
    mockCategoryIdSetting("emailToReviewCategory", "44");
    assertEquals(List.of(11L, 44L), emailBoxService.getDefaultEmailCategoryIds());
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
  void getAvailableEmailCategories() {
    mockCategoryIdSetting("emailImportantCategory", "11");
    mockCategoryIdSetting("emailInvitationCategory", null);
    mockCategoryIdSetting("emailNotificationCategory", null);
    mockCategoryIdSetting("emailToReviewCategory", "44");
    when(categoryService.getCategory(11L, TEST_USER, Locale.ENGLISH)).thenReturn(new CategoryWithName(11L,
                                                                                                     0L,
                                                                                                     "Important",
                                                                                                     null,
                                                                                                     0L,
                                                                                                     0L,
                                                                                                     null));
    // A default category the user cannot see is skipped rather than failing the listing.
    when(categoryService.getCategory(44L, TEST_USER, Locale.ENGLISH)).thenThrow(ObjectNotFoundException.class);

    List<EmailCategory> categories = emailBoxService.getAvailableEmailCategories(TEST_USER, Locale.ENGLISH);
    assertEquals(1, categories.size());
    assertEquals(11L, categories.get(0).getId());
    assertEquals("Important", categories.get(0).getName());
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
  void linkEmailsToCategory() {
    assertEquals(0, emailBoxService.linkEmailsToCategory(List.of(), 5L, TEST_USER));
    verify(categoryLinkService, never()).link(anyLong(), any(CategoryObject.class), anyString());

    // Unknown category: rejected up front, before any email is looked up.
    when(categoryService.getCategory(5L)).thenReturn(null);
    assertThrows(IllegalArgumentException.class, () -> emailBoxService.linkEmailsToCategory(List.of(1212l), 5L, TEST_USER));

    when(categoryService.getCategory(5L)).thenReturn(new Category());
    // An email that is not the user's own (or no longer cached) is skipped, not linked.
    assertEquals(0, emailBoxService.linkEmailsToCategory(List.of(1212l), 5L, TEST_USER));
    verify(categoryLinkService, never()).link(anyLong(), any(CategoryObject.class), anyString());

    mockOwnedEmail();
    assertEquals(1, emailBoxService.linkEmailsToCategory(List.of(1212l), 5L, TEST_USER));
    ArgumentCaptor<CategoryObject> objectCaptor = ArgumentCaptor.forClass(CategoryObject.class);
    verify(categoryLinkService).link(eq(5L), objectCaptor.capture(), eq(TEST_USER));
    assertEquals(EmailCategoryPlugin.OBJECT_TYPE, objectCaptor.getValue().getType());
    assertEquals("7", objectCaptor.getValue().getId());

    // Already in that category: idempotent, not counted as newly linked.
    doThrow(ObjectAlreadyExistsException.class).when(categoryLinkService)
                                               .link(anyLong(), any(CategoryObject.class), anyString());
    assertEquals(0, emailBoxService.linkEmailsToCategory(List.of(1212l), 5L, TEST_USER));

    // The category disappeared in between: surfaced as a 400, not a 500.
    doThrow(ObjectNotFoundException.class).when(categoryLinkService)
                                          .link(anyLong(), any(CategoryObject.class), anyString());
    assertThrows(IllegalArgumentException.class, () -> emailBoxService.linkEmailsToCategory(List.of(1212l), 5L, TEST_USER));
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
    // Bodies come from the workers. The rows land in slice-COMPLETION order, newest
    // slices submitted first -- deliberately NOT in ascending UID order. The ascending
    // assertion that used to live here encoded the forward-only threading lookup (a
    // parent had to be cached before its replies); computeThreadId now links
    // conversations in both directions, and the order-independence itself is proven by
    // the *StillFormsOneThread tests. What must hold here is that every message is
    // cached exactly once, with the body its worker prefetched.
    Set<Long> cachedUids = new HashSet<>();
    for (Email created : emailCaptor.getAllValues()) {
      assertEquals("prefetched-" + created.getMailRemoteId(), created.getContent().getBody());
      assertTrue(cachedUids.add(created.getMailRemoteId()), "every message must be cached exactly once");
    }
    assertEquals(100, cachedUids.size());
    // The new-emails events stream out during the download, one group every three slices
    // (3 x 20 = 60, then the remaining 40), so the AI categorization can start on the
    // first messages while the later ones are still being fetched. Which UIDs land in
    // which group depends on completion order; together they must cover the whole sync.
    @SuppressWarnings({ "unchecked", "rawtypes" })
    ArgumentCaptor<List<Long>> groupCaptor = ArgumentCaptor.forClass((Class) List.class);
    verify(listenerService, times(2)).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNCED), eq(TEST_USER), groupCaptor.capture());
    List<List<Long>> groups = groupCaptor.getAllValues();
    assertEquals(60, groups.get(0).size());
    assertEquals(40, groups.get(1).size());
    Set<Long> broadcastUids = new HashSet<>(groups.get(0));
    broadcastUids.addAll(groups.get(1));
    assertEquals(cachedUids, broadcastUids);
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

  @Test
  @SneakyThrows
  void replyCachedBeforeParentStillFormsOneThread() {
    // Order-independence, direction one: the reply lands FIRST (newest-first caching
    // does exactly this on every reset), its parent second. The forward References
    // lookup finds nothing when the reply is cached, so only the reverse lookup run
    // when the parent lands can reunite them -- before it existed this conversation
    // silently split in two.
    List<Email> rows = stubStatefulThreadingStorage();
    MimeMessage reply = threadedMessage("<reply@host>", "<parent@host>", "<parent@host>", null, new Date(2000));
    MimeMessage parent = threadedMessage("<parent@host>", null, null, null, new Date(1000));
    mockInboxWithMessages(userEmailSetting(), new MimeMessage[] { reply, parent });
    emailBoxService.synchronize(TEST_USER);
    assertEquals(2, rows.size());
    assertEquals(rows.get(0).getThreadId(), rows.get(1).getThreadId(), "reply and parent must share one thread id");
  }

  @Test
  @SneakyThrows
  void lateMessageReferencingTwoThreadsMergesThemIntoTheOldest() {
    // Two fragments exist before the linking message arrives: A alone, and C whose only
    // reference points at a not-yet-cached B. When B finally lands it references A (the
    // forward lookup finds A's thread) while C references B (the reverse lookup finds
    // C's thread): one message bridges two conversations, and the merge collapses them
    // into the oldest thread whatever order the three were cached in.
    List<Email> rows = stubStatefulThreadingStorage();
    MimeMessage a = threadedMessage("<a@host>", null, null, null, new Date(1000));
    MimeMessage c = threadedMessage("<c@host>", "<b@host>", "<b@host>", null, new Date(3000));
    MimeMessage b = threadedMessage("<b@host>", "<a@host>", "<a@host>", null, new Date(2000));
    mockInboxWithMessages(userEmailSetting(), new MimeMessage[] { a, c, b });
    emailBoxService.synchronize(TEST_USER);
    assertEquals(3, rows.size());
    for (Email row : rows) {
      // A is the oldest message, so its thread id is the canonical one everything
      // collapses into -- including C, whose row was already stored with its own id
      // and must have been rewritten by the merge.
      assertEquals("<a@host>", row.getThreadId(), "all three fragments must collapse into the oldest thread");
    }
  }

  @Test
  @SneakyThrows
  void aStaleReferencesHeaderMergesConversationsThatAreNotRelated() {
    // The other side of lateMessageReferencingTwoThreadsMergesThemIntoTheOldest: the merge
    // trusts the References header completely, so any message naming 2+ already-cached
    // Message-IDs collapses their threads. Here A and B are unrelated conversations and the
    // third message is what a forwarded digest -- or a client dumping a stale References
    // list -- produces: it belongs to neither, yet it merges both.
    //
    // Pinned deliberately rather than asserted as correct. The merge is one-way (threads are
    // never split again), so a false merge is irreversible, and whether a bare 2+ hit should
    // really be enough is a product call. If the rule is tightened to demand a real chain
    // through both threads, this is the test that must change -- on purpose, not by surprise.
    List<Email> rows = stubStatefulThreadingStorage();
    MimeMessage a = threadedMessage("<unrelated-a@host>", null, null, null, new Date(1000));
    MimeMessage b = threadedMessage("<unrelated-b@host>", null, null, null, new Date(2000));
    MimeMessage digest =
                       threadedMessage("<digest@host>", "<unrelated-a@host> <unrelated-b@host>", null, null, new Date(3000));
    mockInboxWithMessages(userEmailSetting(), new MimeMessage[] { a, b, digest });
    emailBoxService.synchronize(TEST_USER);
    assertEquals(3, rows.size());
    for (Email row : rows) {
      assertEquals("<unrelated-a@host>",
                   row.getThreadId(),
                   "current behavior: a multi-id References header merges the threads it names, related or not");
    }
  }

  @Test
  @SneakyThrows
  void threadIndexGroupingIsOrderIndependent() {
    // Exchange mail with a broken References chain: the only grouping signal is the
    // Thread-Index conversation root. The root lookup matches any cached row carrying
    // the same root, in either direction, so the pair must group even when the newer
    // message deliberately lands before the conversation starter.
    List<Email> rows = stubStatefulThreadingStorage();
    MimeMessage newer = threadedMessage("<ti-reply@host>", null, null, threadIndex(27), new Date(2000));
    MimeMessage older = threadedMessage("<ti-root@host>", null, null, threadIndex(22), new Date(1000));
    mockInboxWithMessages(userEmailSetting(), new MimeMessage[] { newer, older });
    emailBoxService.synchronize(TEST_USER);
    assertEquals(2, rows.size());
    assertEquals(rows.get(0).getThreadId(), rows.get(1).getThreadId(), "a shared Thread-Index root must group either way");
  }

  @Test
  @SneakyThrows
  void interleavedRepliesAcrossParallelSlicesStillFormOneThreadEach() {
    // The full pipeline: 40 messages = 20 parent/reply pairs deliberately split across
    // two prefetch slices (parents in UIDs 1..20, replies in 21..40). The slices are
    // submitted newest-first and drained in completion order, so the replies routinely
    // land before their parents -- every pair must still come out as one conversation,
    // and no two pairs may bleed into each other.
    List<Email> rows = stubStatefulThreadingStorage();
    MimeMessage[] messages = new MimeMessage[40];
    for (int i = 0; i < 20; i++) {
      messages[i] = threadedMessage("<m" + (i + 1) + "@host>", null, null, null, new Date((i + 1) * 1000L));
      messages[20 + i] = threadedMessage("<r" + (i + 1) + "@host>",
                                         "<m" + (i + 1) + "@host>",
                                         "<m" + (i + 1) + "@host>",
                                         null,
                                         new Date((21 + i) * 1000L));
    }
    mockInboxWithMessages(userEmailSetting(), messages);
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector);
    mockPrefetchWorkerConnection(emailConnector, 40);
    emailBoxService.synchronize(TEST_USER);
    assertEquals(40, rows.size());
    Map<String, String> threadIdByHeaderId = rows.stream().collect(Collectors.toMap(Email::getMailHeaderId, Email::getThreadId));
    for (int i = 1; i <= 20; i++) {
      assertEquals(threadIdByHeaderId.get("<m" + i + "@host>"),
                   threadIdByHeaderId.get("<r" + i + "@host>"),
                   "pair " + i + " must share one thread id whatever order its slices completed in");
    }
    assertEquals(20, rows.stream().map(Email::getThreadId).distinct().count(), "the 20 conversations must stay distinct");
  }

  @Test
  @SneakyThrows
  void slowSliceDoesNotHoldUpCompletedSlices() {
    // The incident behind completion-order draining: one message trickling in slowly
    // stalled a whole mailbox for ~4 minutes while four workers sat idle holding
    // finished data, because slices were consumed strictly in sequence. Here the
    // NEWEST slice (UIDs 81..100, submitted first) blocks until the other eighty
    // messages have been cached; with a sequential drain that is a deadlock broken
    // only by the slice timeout, with completion-order draining it just finishes last.
    UserEmailSetting userEmailSetting = userEmailSetting();
    mockInboxForSync(userEmailSetting, 100);
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector);
    CountDownLatch othersCached = new CountDownLatch(80);
    when(emailBoxStorage.createEmail(any(Email.class))).thenAnswer(invocation -> {
      othersCached.countDown();
      return invocation.getArgument(0);
    });
    mockPrefetchWorkerConnection(emailConnector, 100, uids -> {
      if (LongStream.of(uids).anyMatch(uid -> uid == 100L)) {
        // The slow slice: its FETCH does not return until every other slice's messages
        // have been cached by the sync thread -- which can only happen if the drain
        // consumes slices as they complete instead of waiting for this one.
        assertTrue(othersCached.await(30, TimeUnit.SECONDS),
                   "the other slices must be cached while the slow one is still fetching");
      }
      return null;
    });
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<Email> emailCaptor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage, times(100)).createEmail(emailCaptor.capture());
    List<Email> created = emailCaptor.getAllValues();
    // The blocked slice's messages are exactly the LAST twenty cached: nothing waited
    // for it, and nothing was lost to it.
    Set<Long> lastTwenty = created.subList(80, 100).stream().map(Email::getMailRemoteId).collect(Collectors.toSet());
    assertEquals(LongStream.rangeClosed(81, 100).boxed().collect(Collectors.toSet()), lastTwenty);
  }

  @Test
  @SneakyThrows
  void noOpSyncIssuesNoPerMessageStatements() {
    // The B1 regression guard. A sync that finds nothing new used to issue one SELECT
    // and two guarded UPDATEs per already-known message -- ~15,000 statements on a
    // 5000-message cache to discover nothing changed -- plus the full-entity folder
    // load with every body CLOB. It must now issue NO per-message statement and never
    // touch the full load: this is the regression that would silently return.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MimeMessage[] messages = new MimeMessage[12];
    List<Email> cached = new ArrayList<>();
    for (int i = 0; i < 12; i++) {
      messages[i] = flaggedMessage(false);
      cached.add(cachedEmail(i + 1, false, false));
    }
    mockInboxWithMessages(userEmailSetting, messages);
    when(emailBoxStorage.getSyncEmails(TEST_USER, "INBOX")).thenReturn(cached);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage, never()).getEmailByMailRemoteIdAndUserId(anyLong(),
                                                                     anyString(),
                                                                     any(),
                                                                     anyString(),
                                                                     anyBoolean(),
                                                                     anyBoolean(),
                                                                     anyBoolean());
    verify(emailBoxStorage, never()).updateEmailReadStatusByMailRemoteIds(anyList(), anyString(), anyBoolean(), anyString());
    verify(emailBoxStorage, never()).markEmailAsNotRecent(anyLong(), anyString(), anyString());
    verify(emailBoxStorage, never()).markEmailsAsNotRecent(anyList(), anyString(), anyString());
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
    verify(emailBoxStorage, never()).getEmails(anyString(), anyString());
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void flagChangesAreAppliedAsBulkUpdates() {
    // Read/unread changes made in another client must still land, but as at most
    // three bulk statements -- one per direction plus one recent-clear -- never as
    // per-message updates.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MimeMessage[] messages = new MimeMessage[6];
    List<Email> cached = new ArrayList<>();
    // UIDs 1-2: read on the server, unread in the cache -> one bulk mark-read.
    messages[0] = flaggedMessage(true);
    messages[1] = flaggedMessage(true);
    cached.add(cachedEmail(1, false, false));
    cached.add(cachedEmail(2, false, false));
    // UIDs 3-4: unread on the server, read in the cache -> one bulk mark-unread.
    messages[2] = flaggedMessage(false);
    messages[3] = flaggedMessage(false);
    cached.add(cachedEmail(3, true, false));
    cached.add(cachedEmail(4, true, false));
    // UID 5: still wearing the recent badge from its own sync -> one bulk clear.
    messages[4] = flaggedMessage(false);
    cached.add(cachedEmail(5, false, true));
    // UID 6: nothing changed -> touched by no statement at all.
    messages[5] = flaggedMessage(false);
    cached.add(cachedEmail(6, false, false));
    mockInboxWithMessages(userEmailSetting, messages);
    when(emailBoxStorage.getSyncEmails(TEST_USER, "INBOX")).thenReturn(cached);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(List.of(1L, 2L), TEST_USER, true, "INBOX");
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(List.of(3L, 4L), TEST_USER, false, "INBOX");
    verify(emailBoxStorage).markEmailsAsNotRecent(List.of(5L), TEST_USER, "INBOX");
    verify(emailBoxStorage, never()).markEmailAsNotRecent(anyLong(), anyString(), anyString());
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
  }

  @Test
  @SneakyThrows
  void mixedSyncCreatesNewAndReconcilesKnownInBulk() {
    // The general case: known-unchanged messages are untouched, a known flag change
    // goes through the bulk path, a lingering recent badge is cleared in bulk, and
    // the genuinely new messages are created -- the same end state the per-message
    // code produced, at O(changes) statements.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MimeMessage[] messages = new MimeMessage[5];
    List<Email> cached = new ArrayList<>();
    // UID 1: known, read on the server but not in the cache.
    messages[0] = flaggedMessage(true);
    cached.add(cachedEmail(1, false, false));
    // UID 2: known, recent badge to clear.
    messages[1] = flaggedMessage(false);
    cached.add(cachedEmail(2, false, true));
    // UID 3: known, nothing changed.
    messages[2] = flaggedMessage(false);
    cached.add(cachedEmail(3, false, false));
    // UIDs 4-5: new, a parent and its reply.
    messages[3] = threadedMessage("<new-parent@host>", null, null, null, new Date(4000));
    messages[4] = threadedMessage("<new-reply@host>", "<new-parent@host>", "<new-parent@host>", null, new Date(5000));
    mockInboxWithMessages(userEmailSetting, messages);
    when(emailBoxStorage.getSyncEmails(TEST_USER, "INBOX")).thenReturn(cached);
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<Email> emailCaptor = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage, times(2)).createEmail(emailCaptor.capture());
    assertEquals(List.of(4L, 5L), emailCaptor.getAllValues().stream().map(Email::getMailRemoteId).toList());
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(List.of(1L), TEST_USER, true, "INBOX");
    verify(emailBoxStorage).markEmailsAsNotRecent(List.of(2L), TEST_USER, "INBOX");
    verify(emailBoxStorage, never()).getEmailByMailRemoteIdAndUserId(anyLong(),
                                                                     anyString(),
                                                                     any(),
                                                                     anyString(),
                                                                     anyBoolean(),
                                                                     anyBoolean(),
                                                                     anyBoolean());
    verify(emailBoxStorage, never()).markEmailAsNotRecent(anyLong(), anyString(), anyString());
  }

  @Test
  @SneakyThrows
  void backfillStillRunsPerRowButOnlyForRowsThatNeedIt() {
    // The threading backfill keeps its per-row writes -- but ONLY for rows that
    // genuinely need them, which is what keeps it out of the steady-state cost.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MimeMessage[] messages = new MimeMessage[] { flaggedMessage(false), flaggedMessage(false), flaggedMessage(false) };
    // UID 1: cached before threading existed -- no thread id at all.
    Email unthreaded = cachedEmail(1, false, false);
    unthreaded.setThreadId("");
    unthreaded.setThreadIndexRoot(null);
    // UID 2: threaded, but its Thread-Index root was never captured.
    Email rootless = cachedEmail(2, false, false);
    rootless.setThreadIndexRoot(null);
    // UID 3: fully backfilled -- must not be written at all.
    Email complete = cachedEmail(3, false, false);
    mockInboxWithMessages(userEmailSetting, messages);
    when(emailBoxStorage.getSyncEmails(TEST_USER, "INBOX")).thenReturn(List.of(unthreaded, rootless, complete));
    emailBoxService.synchronize(TEST_USER);
    // Exactly one thread-info backfill (UID 1) and one root capture (UID 2); the
    // messages carry no Thread-Index header, so the root is stored as "".
    verify(emailBoxStorage, times(1)).updateThreadInfo(eq(TEST_USER), eq(1L), anyString(), any(), any(), eq("INBOX"), eq(""));
    verify(emailBoxStorage, times(1)).updateThreadIndexRoot(TEST_USER, 2L, "INBOX", "");
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
  }

  @Test
  @SneakyThrows
  void unchangedSnapshotSkipsTheFolderEntirely() {
    // The B2 payoff and its regression guard: when every change signal matches the
    // snapshot, the folder must never be opened, never window-FETCHed, never loaded
    // from the cache, never broadcast -- 98% of a measured no-op sync was the window
    // FETCH alone. A fully-skipped run must also write no sync state back.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 100));
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, state, 11L, 501L, 100, 777L, true);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox, never()).open(anyInt());
    verify(inbox, never()).open(anyInt(), any(ResyncData.class));
    verify(inbox, never()).getMessages(anyInt(), anyInt());
    verify(inbox, never()).fetch(any(), any());
    verify(emailBoxStorage, never()).getSyncEmails(anyString(), anyString());
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
    verify(listenerService, never()).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNCED), any(), any());
    verify(listenerService, never()).broadcast(eq(EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED), any(), any());
    verify(settingService, never()).set(any(Context.class), any(Scope.class), eq("emailBoxSyncState"), any());
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void newArrivalForcesTheFullPath() {
    // uidNext moved: at least one message arrived (even if deleted again since).
    assertFullSyncRunsWhenServerReports(11L, 502L, 100, 777L);
  }

  @Test
  @SneakyThrows
  void expungeForcesTheFullPath() {
    // uidNext unchanged but the count dropped: messages were deleted elsewhere.
    assertFullSyncRunsWhenServerReports(11L, 501L, 99, 777L);
  }

  @Test
  @SneakyThrows
  void remoteFlagChangeForcesTheFullPath() {
    // Only the mod-sequence moved: metadata changed -- a read/unread flip in
    // another client. This is the signal the whole CONDSTORE requirement exists for.
    assertFullSyncRunsWhenServerReports(11L, 501L, 100, 778L);
  }

  @Test
  @SneakyThrows
  void uidValidityChangeForcesTheFullPathWithoutTouchingTheCache() {
    // The server renumbered the folder (Gmail rebuild): every cached UID is
    // meaningless, so the ONLY acceptable reaction is the full path, which re-lists
    // the window and reconciles as it always did. Here the renumbered folder's
    // window is empty and the cache is empty, so the full path must run and destroy
    // nothing.
    IMAPFolder inbox = assertFullSyncRunsWhenServerReports(12L, 501L, 100, 777L);
    verify(inbox).open(Folder.READ_ONLY);
    verify(emailBoxStorage, never()).createEmail(any(Email.class));
    verify(emailBoxStorage, never()).deleteEmailsByIds(anyList());
  }

  @Test
  @SneakyThrows
  void missingSnapshotTakesTheFullPath() {
    // First sync ever (or a reset invalidated the snapshot): nothing to compare,
    // no STATUS worth issuing -- straight to the full path.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 100, 777L, true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
    verify(emailBoxStorage).getSyncEmails(TEST_USER, MailFolder.INBOX);
  }

  @Test
  @SneakyThrows
  void unparseableSyncStateTakesTheFullPathAndHeals() {
    // A corrupt state blob can never do worse than cost one full sync: it reads as
    // an empty state, the full path runs, and the fresh snapshot overwrites the
    // garbage.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 100, 777L, true);
    doReturn(SettingValue.create("this is not json")).when(settingService)
                                                     .get(any(Context.class), any(Scope.class), eq("emailBoxSyncState"));
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
    ArgumentCaptor<SettingValue> savedState = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class), any(Scope.class), eq("emailBoxSyncState"), savedState.capture());
    MailboxSyncState healed = JsonUtils.fromJsonString(savedState.getValue().getValue().toString(), MailboxSyncState.class);
    assertEquals(501L, healed.getSnapshot(MailFolder.INBOX).getUidNext());
  }

  @Test
  @SneakyThrows
  void windowSizeChangeForcesTheFullPath() {
    // The admin grew the cache: the server is unchanged, but the wider window has
    // never been downloaded -- skipping would leave the mailbox permanently short.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 50));
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, state, 11L, 501L, 100, 777L, true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
  }

  @Test
  @SneakyThrows
  void withoutCondstoreTheSkipIsNeverTaken() {
    // No CONDSTORE means an unchanged uidNext+messageCount still says NOTHING about
    // read/unread flags flipped in another client. Skipping would not make those
    // flags late -- it would make them stale forever. So on such servers the skip
    // is refused outright, even with every other signal matching.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 100));
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, state, 11L, 501L, 100, 777L, false);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
  }

  @Test
  @SneakyThrows
  void fullSyncCapturesTheSnapshotForTheNextRun() {
    // The other half of the contract: a full sync must leave behind the snapshot
    // that lets the NEXT run skip, with the SELECT-time signals and the window size.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 3, 777L, true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<SettingValue> savedState = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class), any(Scope.class), eq("emailBoxSyncState"), savedState.capture());
    FolderSyncSnapshot snapshot = JsonUtils.fromJsonString(savedState.getValue().getValue().toString(), MailboxSyncState.class)
                                           .getSnapshot(MailFolder.INBOX);
    assertEquals(11L, snapshot.getUidValidity());
    assertEquals(501L, snapshot.getUidNext());
    assertEquals(3L, snapshot.getMessageCount());
    assertEquals(777L, snapshot.getHighestModSeq());
    assertEquals(100, snapshot.getWindowSize());
  }

  @Test
  @SneakyThrows
  void cachedFolderNamesSkipTheDiscoveryScan() {
    // Sent/Archive used to be re-discovered with a LIST * over the whole subscribed
    // folder list on every sync; the remembered names replace that with one
    // single-folder exists() probe each.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 100));
    state.setSentFolderName("MySent");
    state.setArchiveFolderName("MyArchive");
    mockInboxForSkipCheck(userEmailSetting, state, 11L, 501L, 100, 777L, true);
    IMAPFolder sent = mock(IMAPFolder.class);
    when(sent.exists()).thenReturn(true);
    when(sent.getMessageCount()).thenReturn(0);
    IMAPFolder archive = mock(IMAPFolder.class);
    when(archive.exists()).thenReturn(true);
    when(archive.getMessageCount()).thenReturn(0);
    Store connectedStore = userEmailSettingService.connect(userEmailSetting);
    when(connectedStore.getFolder("MySent")).thenReturn(sent);
    when(connectedStore.getFolder("MyArchive")).thenReturn(archive);
    emailBoxService.synchronize(TEST_USER);
    // Both folders resolved by name (and INBOX skipped): the full-list scan never runs.
    verify(connectedStore.getDefaultFolder(), never()).listSubscribed("*");
    verify(sent).open(Folder.READ_ONLY);
    verify(archive).open(Folder.READ_ONLY);
  }

  @Test
  @SneakyThrows
  void clearingAFolderCacheDropsItsSnapshotSoTheResyncCannotSkip() {
    // The reset flow: the INBOX rows are wiped while the SERVER still matches the
    // old snapshot exactly. If the snapshot survived, the resync would conclude
    // "nothing changed" over an empty cache and the mailbox would come up blank --
    // the silent failure this feature must never cause.
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 100));
    state.setSnapshot(MailFolder.SENT, new FolderSyncSnapshot(21L, 61L, 60, 888L, 100));
    doReturn(SettingValue.create(JsonUtils.toJsonString(state))).when(settingService)
                                                                .get(any(Context.class),
                                                                     any(Scope.class),
                                                                     eq("emailBoxSyncState"));
    when(emailBoxStorage.getEmails(TEST_USER, MailFolder.INBOX)).thenReturn(new ArrayList<>());
    emailBoxService.deleteUserEmails(TEST_USER, MailFolder.INBOX);
    ArgumentCaptor<SettingValue> savedState = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class), any(Scope.class), eq("emailBoxSyncState"), savedState.capture());
    MailboxSyncState cleared = JsonUtils.fromJsonString(savedState.getValue().getValue().toString(), MailboxSyncState.class);
    assertNull(cleared.getSnapshot(MailFolder.INBOX), "the cleared folder's snapshot must be gone");
    assertEquals(888L, cleared.getSnapshot(MailFolder.SENT).getHighestModSeq(), "other folders' snapshots must survive");
  }

  @Test
  @SneakyThrows
  void deletingTheWholeMailboxRemovesTheSyncState() {
    when(emailBoxStorage.getEmails(TEST_USER)).thenReturn(new ArrayList<>());
    emailBoxService.deleteUserEmails(TEST_USER);
    verify(settingService).remove(any(Context.class), any(Scope.class), eq("emailBoxSyncState"));
  }

  @Test
  @SneakyThrows
  void condstoreServerOpensWithExplicitModSeqRequest() {
    // The Stalwart fix: a server may advertise CONDSTORE yet only send HIGHESTMODSEQ
    // when explicitly asked (RFC 7162 obliges it no further) -- observed live as
    // benjamin's folders looping on "snapshot incomplete" forever. On such servers
    // the sync must open with SELECT (CONDSTORE), never with a plain SELECT.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 100, 777L, true);
    Store connectedStore = userEmailSettingService.connect(userEmailSetting);
    lenient().when(inbox.getStore()).thenReturn(connectedStore);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY, ResyncData.CONDSTORE);
    verify(inbox, never()).open(anyInt());
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void resyncRejectionFallsBackToPlainOpen() {
    // A server that advertises CONDSTORE but rejects the SELECT parameter must cost
    // nothing: the open falls back to a plain SELECT and the sync completes -- the
    // skip simply stays off for that folder, exactly the pre-fix behavior.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 100, 777L, true);
    Store connectedStore = userEmailSettingService.connect(userEmailSetting);
    lenient().when(inbox.getStore()).thenReturn(connectedStore);
    when(inbox.open(Folder.READ_ONLY, ResyncData.CONDSTORE)).thenThrow(new MessagingException("CONDSTORE not supported"));
    lenient().when(inbox.isOpen()).thenReturn(false).thenReturn(true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
    verify(emailBoxStorage).getSyncEmails(TEST_USER, MailFolder.INBOX);
    assertEquals(SyncStatus.SUCCESS, userEmailSetting.getEmailSyncStatus());
  }

  @Test
  @SneakyThrows
  void serverWithholdingModSeqStoresIncompleteSnapshotAndStaysOnTheFullPath() {
    // A server that ACCEPTS the CONDSTORE parameter but still provides no
    // mod-sequence: the capture stores -1, the state persists it (the log then
    // shows exactly which signal is missing), and the skip must never fire.
    UserEmailSetting userEmailSetting = userEmailSetting();
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, null, 11L, 501L, 100, -1L, true);
    Store connectedStore = userEmailSettingService.connect(userEmailSetting);
    lenient().when(inbox.getStore()).thenReturn(connectedStore);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    ArgumentCaptor<SettingValue> savedState = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class), any(Scope.class), eq("emailBoxSyncState"), savedState.capture());
    MailboxSyncState savedSyncState = JsonUtils.fromJsonString(savedState.getValue().getValue().toString(),
                                                               MailboxSyncState.class);
    assertEquals(-1L, savedSyncState.getSnapshot(MailFolder.INBOX).getHighestModSeq());
  }

  @Test
  @SneakyThrows
  void incompleteSnapshotNeverSkips() {
    // The stored -1 mod-sequence (a capture the server left short) must keep forcing
    // the full path even when every other signal matches -- this is the exact state
    // benjamin's mailbox was stuck in, and it must stay a full sync, never a skip.
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, -1L, 100));
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, state, 11L, 501L, 100, -1L, true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
    verify(emailBoxStorage).getSyncEmails(TEST_USER, MailFolder.INBOX);
  }

  /**
   * Runs a sync whose INBOX snapshot is {@code (uidValidity 11, uidNext 501, 100
   * messages, highestModSeq 777, window 100)} against a CONDSTORE server reporting
   * the given signals, and asserts the FULL path ran (folder opened, cache view
   * loaded) -- the shape shared by every one-signal-changed test.
   *
   * @param uidValidity the server-side UIDVALIDITY
   * @param uidNext the server-side UIDNEXT
   * @param messageCount the server-side message count
   * @param highestModSeq the server-side HIGHESTMODSEQ
   * @return the INBOX mock, for extra assertions
   */
  @SneakyThrows
  private IMAPFolder assertFullSyncRunsWhenServerReports(long uidValidity, long uidNext, int messageCount, long highestModSeq) {
    UserEmailSetting userEmailSetting = userEmailSetting();
    MailboxSyncState state = new MailboxSyncState();
    state.setSnapshot(MailFolder.INBOX, new FolderSyncSnapshot(11L, 501L, 100, 777L, 100));
    IMAPFolder inbox = mockInboxForSkipCheck(userEmailSetting, state, uidValidity, uidNext, messageCount, highestModSeq, true);
    lenient().when(inbox.getMessages(anyInt(), anyInt())).thenReturn(new Message[0]);
    emailBoxService.synchronize(TEST_USER);
    verify(inbox).open(Folder.READ_ONLY);
    verify(emailBoxStorage).getSyncEmails(TEST_USER, MailFolder.INBOX);
    return inbox;
  }

  /**
   * Wires a mailbox for the skip-check tests: an IMAP store (CONDSTORE-capable or
   * not), an INBOX reporting the given change signals over STATUS/SELECT, and the
   * previous run's sync state persisted in the settings mock (none when null). No
   * subscribed folders, so Sent/Archive syncs are no-ops when the full path runs.
   *
   * @param userEmailSetting the user's connector binding
   * @param storedState the persisted sync state, or null for a first sync
   * @param uidValidity the server-side UIDVALIDITY
   * @param uidNext the server-side UIDNEXT
   * @param messageCount the server-side message count
   * @param highestModSeq the server-side HIGHESTMODSEQ
   * @param condstore whether the store advertises CONDSTORE
   * @return the INBOX mock
   */
  @SneakyThrows
  private IMAPFolder mockInboxForSkipCheck(UserEmailSetting userEmailSetting,
                                           MailboxSyncState storedState,
                                           long uidValidity,
                                           long uidNext,
                                           int messageCount,
                                           long highestModSeq,
                                           boolean condstore) {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    lenient().when(store.hasCapability("CONDSTORE")).thenReturn(condstore);
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(100);
    IMAPFolder inbox = mock(IMAPFolder.class);
    when(store.getFolder("INBOX")).thenReturn(inbox);
    lenient().when(inbox.getUIDValidity()).thenReturn(uidValidity);
    lenient().when(inbox.getUIDNext()).thenReturn(uidNext);
    lenient().when(inbox.getMessageCount()).thenReturn(messageCount);
    lenient().when(inbox.getHighestModSeq()).thenReturn(highestModSeq);
    lenient().when(inbox.isOpen()).thenReturn(true);
    lenient().when(inbox.getFullName()).thenReturn("INBOX");
    if (storedState != null) {
      doReturn(SettingValue.create(JsonUtils.toJsonString(storedState))).when(settingService)
                                                                        .get(any(Context.class),
                                                                             any(Scope.class),
                                                                             eq("emailBoxSyncState"));
    }
    Folder defaultFolder = mock(Folder.class);
    lenient().when(store.getDefaultFolder()).thenReturn(defaultFolder);
    lenient().when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
    return inbox;
  }

  /**
   * A mocked inbox message for the reconcile tests: only its SEEN flag matters, and
   * the flag comes off the batched window FETCH in production, so a bare mock with a
   * stubbed flag is the honest shape.
   *
   * @param seen the server-side SEEN flag
   * @return the mocked message
   */
  @SneakyThrows
  private MimeMessage flaggedMessage(boolean seen) {
    MimeMessage message = mock(MimeMessage.class);
    lenient().when(message.isSet(Flags.Flag.SEEN)).thenReturn(seen);
    lenient().when(message.getReceivedDate()).thenReturn(new Date());
    return message;
  }

  /**
   * A cached row as the light sync view returns it: ids, flags and threading state,
   * no body. Threading is already backfilled (non-empty thread id, captured empty
   * Thread-Index root) so the reconcile tests exercise pure flag logic; the backfill
   * test overrides those fields explicitly.
   *
   * @param uid the IMAP UID
   * @param read the cached read flag
   * @param recent the cached recent flag
   * @return the light row
   */
  private Email cachedEmail(long uid, boolean read, boolean recent) {
    Email email = new Email();
    email.setId(uid * 100);
    email.setMailRemoteId(uid);
    email.setRead(read);
    email.setRecent(recent);
    email.setThreadId("<m" + uid + "@host>");
    email.setThreadIndexRoot("");
    email.setUserId(TEST_USER);
    email.setFolder("INBOX");
    return email;
  }

  /**
   * Turns the mocked storage into a small in-memory mailbox for the threading tests:
   * created rows are remembered, and every thread lookup computeThreadId relies on
   * (forward References, reverse References, Thread-Index root, oldest-thread
   * canonicalization, merge) answers from what has actually been stored so far --
   * exactly what the service sees in production. Without this, the order-independence
   * tests would only prove that the service believes whatever a stubbed list says.
   *
   * @return the live list of stored rows, in creation order
   */
  private List<Email> stubStatefulThreadingStorage() {
    List<Email> rows = Collections.synchronizedList(new ArrayList<>());
    when(emailBoxStorage.createEmail(any(Email.class))).thenAnswer(invocation -> {
      Email email = invocation.getArgument(0);
      rows.add(email);
      return email;
    });
    when(emailBoxStorage.getSiblingThreadIds(anyString(), anyList())).thenAnswer(invocation -> {
      List<String> mailHeaderIds = invocation.getArgument(1);
      return rows.stream()
                 .filter(row -> row.getMailHeaderId() != null && mailHeaderIds.contains(row.getMailHeaderId()))
                 .map(Email::getThreadId)
                 .filter(Objects::nonNull)
                 .distinct()
                 .toList();
    });
    when(emailBoxStorage.getThreadIdsReferencingMessageId(anyString(), anyString())).thenAnswer(invocation -> {
      String messageId = invocation.getArgument(1);
      return rows.stream()
                 .filter(row -> (row.getMailReferences() != null && row.getMailReferences().contains(messageId))
                     || (row.getInReplyTo() != null && row.getInReplyTo().contains(messageId)))
                 .map(Email::getThreadId)
                 .filter(Objects::nonNull)
                 .distinct()
                 .toList();
    });
    when(emailBoxStorage.getThreadIdsByThreadIndexRoot(anyString(), anyString())).thenAnswer(invocation -> {
      String threadIndexRoot = invocation.getArgument(1);
      return rows.stream()
                 .filter(row -> threadIndexRoot.equals(row.getThreadIndexRoot()))
                 .map(Email::getThreadId)
                 .filter(Objects::nonNull)
                 .distinct()
                 .toList();
    });
    when(emailBoxStorage.getOldestThreadId(anyString(), anyList())).thenAnswer(invocation -> {
      List<String> threadIds = invocation.getArgument(1);
      return rows.stream()
                 .filter(row -> threadIds.contains(row.getThreadId()))
                 .sorted(Comparator.comparing(Email::getReceivedDate))
                 .map(Email::getThreadId)
                 .findFirst()
                 .orElse(null);
    });
    doAnswer(invocation -> {
      String canonicalThreadId = invocation.getArgument(1);
      List<String> threadIds = invocation.getArgument(2);
      rows.stream().filter(row -> threadIds.contains(row.getThreadId())).forEach(row -> row.setThreadId(canonicalThreadId));
      return null;
    }).when(emailBoxStorage).mergeThreads(anyString(), anyString(), anyList());
    return rows;
  }

  /**
   * A mocked inbox message carrying real threading headers, as the sync connection's
   * batched fetch would expose them. Only non-null headers are stubbed, so an absent
   * header behaves exactly like production (getHeader returns null).
   *
   * @param messageId the Message-ID header (angle-bracketed), may be null
   * @param references the raw References header, may be null
   * @param inReplyTo the raw In-Reply-To header, may be null
   * @param threadIndex the raw Exchange Thread-Index header, may be null
   * @param receivedDate the received date, which drives oldest-thread canonicalization
   * @return the mocked message
   */
  @SneakyThrows
  private MimeMessage threadedMessage(String messageId, String references, String inReplyTo, String threadIndex, Date receivedDate) {
    MimeMessage message = mock(MimeMessage.class);
    when(message.getMessageID()).thenReturn(messageId);
    // lenient: the service also probes getHeader for headers this message does not
    // carry (delivery headers, the absent ones of the three below), and strict
    // stubbing would report those probes as argument mismatches.
    if (references != null) {
      lenient().when(message.getHeader("References")).thenReturn(new String[] { references });
    }
    if (inReplyTo != null) {
      lenient().when(message.getHeader("In-Reply-To")).thenReturn(new String[] { inReplyTo });
    }
    if (threadIndex != null) {
      lenient().when(message.getHeader("Thread-Index")).thenReturn(new String[] { threadIndex });
    }
    when(message.getReceivedDate()).thenReturn(receivedDate);
    return message;
  }

  /**
   * A synthetic MS-OXOMSG {@code Thread-Index} whose 16-byte conversation GUID
   * (bytes 6..21) is a fixed constant, so indexes of different lengths -- the
   * conversation starter at 22 bytes, each reply 5 bytes longer -- decode to the
   * same conversation root.
   *
   * @param length the raw byte length before base64 encoding, at least 22
   * @return the base64-encoded header value
   */
  private String threadIndex(int length) {
    byte[] raw = new byte[length];
    for (int i = 6; i < 22; i++) {
      raw[i] = (byte) 0x42;
    }
    return Base64.getEncoder().encodeToString(raw);
  }

  /**
   * Wires the mocks for a {@code synchronize()} run over an inbox containing exactly
   * the given messages, with UIDs {@code 1..n} in array order (nothing cached
   * locally, no Sent/Archive folders on the store). Unlike {@link #mockInboxForSync}
   * the caller controls each message's headers, which is what the threading
   * order-independence tests manipulate.
   *
   * @param userEmailSetting the user's connector binding
   * @param messages the inbox content, oldest first (UID = index + 1)
   * @return the mocked INBOX folder
   */
  @SneakyThrows
  private Folder mockInboxWithMessages(UserEmailSetting userEmailSetting, MimeMessage[] messages) {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    // Only the parallel-prefetch tests reach getFullName; lenient so the serial-path
    // tests sharing this fixture do not trip strict-stubbing.
    lenient().when(inbox.getFullName()).thenReturn("INBOX");
    when(emailConnectorService.getEmailBoxCacheSize()).thenReturn(100);
    when(inbox.getMessageCount()).thenReturn(messages.length);
    for (int i = 0; i < messages.length; i++) {
      when(((UIDFolder) inbox).getUID(messages[i])).thenReturn((long) (i + 1));
    }
    when(inbox.getMessages(anyInt(), anyInt())).thenReturn(messages);
    when(emailBoxStorage.getSyncEmails(anyString(), anyString())).thenReturn(new ArrayList<>());
    // No subscribed Sent/Archive folders in the test store, so those syncs are no-ops.
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
    return inbox;
  }

  /**
   * Wires the extra IMAP connection the body prefetch workers open: same folder
   * re-opened by full name, messages resolved by UID, each carrying a
   * {@code prefetched-<uid>} body.
   *
   * @param emailConnector the resolved connector the workers connect with
   * @param count the number of messages, UIDs {@code 1..count}
   * @return the mocked worker-side folder
   */
  @SneakyThrows
  private Folder mockPrefetchWorkerConnection(EmailConnector emailConnector, int count) {
    return mockPrefetchWorkerConnection(emailConnector, count, uids -> null);
  }

  /**
   * Same as {@link #mockPrefetchWorkerConnection(EmailConnector, int)}, with a hook
   * invoked on the worker thread before a slice's messages are returned -- used to
   * make one slice deliberately slow.
   *
   * @param emailConnector the resolved connector the workers connect with
   * @param count the number of messages, UIDs {@code 1..count}
   * @param beforeSlice called with the slice's UIDs before they resolve; may block
   * @return the mocked worker-side folder
   */
  @SneakyThrows
  private Folder mockPrefetchWorkerConnection(EmailConnector emailConnector, int count, SliceHook beforeSlice) {
    Store workerStore = mock(Store.class);
    when(userEmailSettingService.connect(any(UserEmailSetting.class), eq(emailConnector))).thenReturn(workerStore);
    when(workerStore.isConnected()).thenReturn(true);
    Folder workerFolder = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(workerStore.getFolder("INBOX")).thenReturn(workerFolder);
    when(workerFolder.isOpen()).thenReturn(true);
    Map<Long, Message> workerMessages = new HashMap<>();
    for (long uid = 1; uid <= count; uid++) {
      MimeMessage workerMessage = mock(MimeMessage.class);
      when(workerMessage.isMimeType("text/*")).thenReturn(true);
      when(workerMessage.getContent()).thenReturn("prefetched-" + uid);
      when(((UIDFolder) workerFolder).getUID(workerMessage)).thenReturn(uid);
      workerMessages.put(uid, workerMessage);
    }
    // Read-only answer (all stubbing done above) so concurrent workers never stub.
    when(((UIDFolder) workerFolder).getMessagesByUID(any(long[].class))).thenAnswer(invocation -> {
      long[] uids = invocation.getArgument(0);
      beforeSlice.beforeSlice(uids);
      Message[] result = new Message[uids.length];
      for (int i = 0; i < uids.length; i++) {
        result[i] = workerMessages.get(uids[i]);
      }
      return result;
    });
    return workerFolder;
  }

  /**
   * A hook run on a prefetch worker thread just before its slice resolves, allowed to
   * block or throw -- how the slow-slice test freezes exactly one slice.
   */
  @FunctionalInterface
  private interface SliceHook {
    /**
     * Invoked with the slice's UIDs on the worker thread.
     *
     * @param uids the slice's UIDs
     * @return ignored, present so blocking lambdas may end with a return
     * @throws Exception to fail the slice like a dead connection would
     */
    Object beforeSlice(long[] uids) throws Exception;
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
    when(emailBoxStorage.getSyncEmails(anyString(), anyString())).thenReturn(new ArrayList<>());
    // No subscribed Sent/Archive folders in the test store, so those syncs are no-ops.
    Folder defaultFolder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(defaultFolder);
    when(defaultFolder.listSubscribed("*")).thenReturn(new Folder[0]);
    return inbox;
  }

  /**
   * The IMAP criteria the search endpoint builds: null when nothing at all was
   * asked (an empty SEARCH would match the whole mailbox), a bare term for a
   * single criterion, an AND of all of them otherwise — with the free-text query
   * expanding to subject OR sender.
   */
  @Test
  void buildEmailSearchTerm() {
    // No criterion at all must yield null, not an empty AND.
    assertNull(EmailBoxService.buildEmailSearchTerm(null, " ", false, null));

    // Free text alone: subject OR sender, trimmed.
    SearchTerm queryTerm = EmailBoxService.buildEmailSearchTerm(" weekly report ", null, false, null);
    assertTrue(queryTerm instanceof OrTerm);
    SearchTerm[] alternatives = ((OrTerm) queryTerm).getTerms();
    assertEquals(2, alternatives.length);
    assertEquals("weekly report", ((SubjectTerm) alternatives[0]).getPattern());
    assertEquals("weekly report", ((FromStringTerm) alternatives[1]).getPattern());

    // Sender alone: a single FROM term, no needless AND wrapper.
    SearchTerm fromTerm = EmailBoxService.buildEmailSearchTerm(null, "alice@example.com", false, null);
    assertTrue(fromTerm instanceof FromStringTerm);
    assertEquals("alice@example.com", ((FromStringTerm) fromTerm).getPattern());

    // Unread alone: messages WITHOUT the \Seen flag.
    SearchTerm unreadTerm = EmailBoxService.buildEmailSearchTerm(null, null, true, null);
    assertTrue(unreadTerm instanceof FlagTerm);
    assertTrue(((FlagTerm) unreadTerm).getFlags().contains(Flags.Flag.SEEN));
    assertFalse(((FlagTerm) unreadTerm).getTestSet());

    // Date bound alone: received on or after the bound.
    Date since = new Date();
    SearchTerm sinceTerm = EmailBoxService.buildEmailSearchTerm(null, null, false, since);
    assertTrue(sinceTerm instanceof ReceivedDateTerm);
    assertEquals(ComparisonTerm.GE, ((ReceivedDateTerm) sinceTerm).getComparison());

    // Everything at once: the four criteria ANDed together.
    SearchTerm combined = EmailBoxService.buildEmailSearchTerm("weekly", "alice@", true, since);
    assertTrue(combined instanceof AndTerm);
    assertEquals(4, ((AndTerm) combined).getTerms().length);
  }

  /**
   * The search happy path: ACL and validation first, then one IMAP SEARCH, ONE
   * batched fetch for the whole hit page (the per-message round-trip regression
   * this verification exists to catch), one bulk cached-flag lookup, and hits
   * returned newest first with the folder's read/cached flags filled in.
   */
  @Test
  @SneakyThrows
  void searchEmails() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class,
                 () -> emailBoxService.searchEmails(TEST_USER, "weekly", null, false, null, "INBOX", 20));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    // Unknown folders and criterion-less searches are refused before any IMAP work.
    assertThrows(IllegalArgumentException.class,
                 () -> emailBoxService.searchEmails(TEST_USER, "weekly", null, false, null, "TRASH", 20));
    assertThrows(IllegalArgumentException.class,
                 () -> emailBoxService.searchEmails(TEST_USER, " ", null, false, null, "INBOX", 20));

    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    Date now = new Date();
    // The server returns matches oldest first; UID 12 is the one already cached.
    MimeMessage oldest = searchHit("weekly report 1", "alice@example.com", new Date(now.getTime() - 2000), true);
    MimeMessage middle = searchHit("weekly report 2", "bob@example.com", new Date(now.getTime() - 1000), false);
    MimeMessage newest = searchHit("weekly report 3", null, now, true);
    when(inbox.search(any(SearchTerm.class))).thenReturn(new Message[] { oldest, middle, newest });
    when(((UIDFolder) inbox).getUID(oldest)).thenReturn(11L);
    when(((UIDFolder) inbox).getUID(middle)).thenReturn(12L);
    when(((UIDFolder) inbox).getUID(newest)).thenReturn(13L);
    when(emailBoxStorage.getCachedMailRemoteIds(TEST_USER, "INBOX", List.of(11L, 12L, 13L))).thenReturn(List.of(12L));

    EmailSearchResultPage page = emailBoxService.searchEmails(TEST_USER, "weekly", null, false, null, "INBOX", 20);

    assertEquals(3, page.getTotalMatches());
    assertEquals(List.of(13L, 12L, 11L),
                 page.getResults().stream().map(EmailSearchResult::getMailRemoteId).collect(Collectors.toList()));
    EmailSearchResult first = page.getResults().get(0);
    assertEquals("weekly report 3", first.getSubject());
    assertNull(first.getSender());
    assertTrue(first.isRead());
    assertFalse(first.isCached());
    EmailSearchResult second = page.getResults().get(1);
    assertFalse(second.isRead());
    assertTrue(second.isCached());
    assertEquals("bob@example.com", second.getSender().getAddress());
    // ONE batched fetch for the whole page, never one FETCH per hit.
    verify(inbox, times(1)).fetch(any(Message[].class), any(FetchProfile.class));
    verify(inbox).open(Folder.READ_ONLY);
    // The search connection is its own and short-lived: closed before returning.
    verify(inbox).close(false);
    verify(store).close();
  }

  /**
   * When the SEARCH matches more than the requested limit, only the newest tail of
   * the match list is fetched — the batched FETCH must stay bounded by the limit,
   * not by the match count — while the total still reports every match.
   */
  @Test
  @SneakyThrows
  void searchEmailsCapsThePageToTheNewestHits() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    MimeMessage[] matches = new MimeMessage[5];
    for (int i = 0; i < matches.length; i++) {
      matches[i] = searchHit("hit " + i, "alice@example.com", new Date(), false);
    }
    when(inbox.search(any(SearchTerm.class))).thenReturn(matches);
    // Only the two newest ever get their UID read: the older three are never fetched.
    when(((UIDFolder) inbox).getUID(matches[3])).thenReturn(14L);
    when(((UIDFolder) inbox).getUID(matches[4])).thenReturn(15L);
    when(emailBoxStorage.getCachedMailRemoteIds(TEST_USER, "INBOX", List.of(14L, 15L))).thenReturn(List.of());

    EmailSearchResultPage page = emailBoxService.searchEmails(TEST_USER, null, "alice", false, 30, "INBOX", 2);

    assertEquals(5, page.getTotalMatches());
    assertEquals(List.of(15L, 14L),
                 page.getResults().stream().map(EmailSearchResult::getMailRemoteId).collect(Collectors.toList()));
    ArgumentCaptor<Message[]> fetchedPage = ArgumentCaptor.forClass(Message[].class);
    verify(inbox, times(1)).fetch(fetchedPage.capture(), any(FetchProfile.class));
    assertArrayEquals(new Message[] { matches[3], matches[4] }, fetchedPage.getValue());
  }

  /**
   * A search with no match costs nothing beyond the SEARCH itself: no batched
   * fetch, no cached-flag query, an empty page with a zero total.
   */
  @Test
  @SneakyThrows
  void searchEmailsReturnsEmptyPageWithoutFetching() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    when(inbox.search(any(SearchTerm.class))).thenReturn(new Message[0]);

    EmailSearchResultPage page = emailBoxService.searchEmails(TEST_USER, "nothing", null, false, null, "INBOX", 20);

    assertEquals(0, page.getTotalMatches());
    assertTrue(page.getResults().isEmpty());
    verify(inbox, never()).fetch(any(Message[].class), any(FetchProfile.class));
    verify(emailBoxStorage, never()).getCachedMailRemoteIds(anyString(), anyString(), anyList());
    verify(inbox).close(false);
    verify(store).close();
  }

  /**
   * Opening a search hit that is already in the local cache must not touch the
   * server at all — the row comes straight from the database.
   */
  @Test
  @SneakyThrows
  void fetchSearchedEmailReturnsCachedRowWithoutImap() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email email = email(TEST_USER);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212L, TEST_USER, "testEmail", "INBOX", true, true, true))
                                                                                                                  .thenReturn(email);

    assertSame(email, emailBoxService.fetchSearchedEmail(1212L, "INBOX", TEST_USER));

    verify(userEmailSettingService, never()).connect(any(UserEmailSetting.class));
  }

  /**
   * The not-held-locally path: a hit outside the cache window is fetched from the
   * server in one batched call and cached through the ordinary createEmails
   * pipeline, then served from the database; the sync mutex is released afterwards
   * (the second call must not be refused as sync-in-progress).
   */
  @Test
  @SneakyThrows
  void fetchSearchedEmailCachesAnOutOfWindowMessage() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email cachedAfterCreate = email(TEST_USER);
    // Miss on the pre-check, miss again on the under-mutex re-check, hit once created.
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(9999L, TEST_USER, "testEmail", "INBOX", true, true, true))
                                                                                                                  .thenReturn(null,
                                                                                                                              null,
                                                                                                                              cachedAfterCreate);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    MimeMessage message = mock(MimeMessage.class);
    when(((UIDFolder) inbox).getMessageByUID(9999L)).thenReturn(message);
    when(((UIDFolder) inbox).getUID(message)).thenReturn(9999L);

    Email fetched = emailBoxService.fetchSearchedEmail(9999L, "INBOX", TEST_USER);

    assertSame(cachedAfterCreate, fetched);
    // The single message is prefetched in one batched call before createEmails
    // reads its headers -- never one round-trip per header.
    verify(inbox, times(1)).fetch(any(Message[].class), any(FetchProfile.class));
    verify(emailBoxStorage).createEmail(any(Email.class));
    verify(inbox).open(Folder.READ_ONLY);
    verify(inbox).close(false);
    verify(store).close();
    // The mutex is released: this second call short-circuits on the cache.
    assertSame(cachedAfterCreate, emailBoxService.fetchSearchedEmail(9999L, "INBOX", TEST_USER));
    verify(userEmailSettingService, times(1)).connect(userEmailSetting);
  }

  /**
   * A hit deleted from the server between the search and the open: null, and
   * nothing is written to the cache.
   */
  @Test
  @SneakyThrows
  void fetchSearchedEmailReturnsNullWhenGoneFromServer() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    when(inbox.isOpen()).thenReturn(true);
    when(store.isConnected()).thenReturn(true);
    when(((UIDFolder) inbox).getMessageByUID(4242L)).thenReturn(null);

    assertNull(emailBoxService.fetchSearchedEmail(4242L, "INBOX", TEST_USER));

    verify(emailBoxStorage, never()).createEmail(any(Email.class));
  }

  /**
   * While a synchronization holds the {@code syncingUsers} mutex, the on-demand
   * fetch must refuse with the retryable {@code syncInProgress} code instead of
   * racing the sync's known-UIDs snapshot into duplicate (user, folder, UID) rows.
   */
  @Test
  @SneakyThrows
  void fetchSearchedEmailRefusedWhileASyncIsRunning() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    CountDownLatch syncStarted = new CountDownLatch(1);
    CountDownLatch releaseSync = new CountDownLatch(1);
    // The sync thread parks inside connect() -- i.e. AFTER it acquired the mutex --
    // until the assertion below has run, then aborts (the sync outcome is irrelevant).
    when(userEmailSettingService.connect(userEmailSetting)).thenAnswer(invocation -> {
      syncStarted.countDown();
      releaseSync.await(10, TimeUnit.SECONDS);
      throw new MessagingException("test: abort the sync");
    });
    Thread sync = new Thread(() -> {
      try {
        emailBoxService.synchronize(TEST_USER);
      } catch (Exception e) {
        // The aborted connect is the expected way out.
      }
    });
    sync.start();
    try {
      assertTrue(syncStarted.await(10, TimeUnit.SECONDS));
      IllegalStateException refusal = assertThrows(IllegalStateException.class,
                                                   () -> emailBoxService.fetchSearchedEmail(4242L, "INBOX", TEST_USER));
      assertEquals("emailConnector.search.syncInProgress", refusal.getMessage());
    } finally {
      releaseSync.countDown();
      sync.join(10000);
    }
  }

  /**
   * A stubbed search hit carrying just what a result row renders: subject, sender,
   * received date and read flag. Lenient because the capped-page test deliberately
   * never reads the hits that fall outside the returned tail.
   *
   * @param subject the message subject
   * @param fromAddress the sender address, null for a sender-less message
   * @param receivedDate the received date
   * @param seen whether the message carries the \Seen flag
   * @return the mocked message
   */
  @SneakyThrows
  private MimeMessage searchHit(String subject, String fromAddress, Date receivedDate, boolean seen) {
    MimeMessage message = mock(MimeMessage.class);
    lenient().when(message.getSubject()).thenReturn(subject);
    lenient().when(message.getFrom())
             .thenReturn(fromAddress == null ? null : new Address[] { new InternetAddress(fromAddress) });
    lenient().when(message.getReceivedDate()).thenReturn(receivedDate);
    lenient().when(message.isSet(Flags.Flag.SEEN)).thenReturn(seen);
    return message;
  }

  private Email emailWithCategories(List<Long> categoryIds) {
    Email email = email(TEST_USER);
    email.setCategoryIds(categoryIds);
    return email;
  }

  /**
   * A mocked message carrying the bulk-mail classification headers. Only non-null values
   * are stubbed, so an absent header behaves exactly as in production.
   */
  @SneakyThrows
  private MimeMessage bulkSignalMessage(String autoSubmitted, String precedence, String listPost) {
    MimeMessage message = mock(MimeMessage.class);
    lenient().when(message.getHeader("Auto-Submitted"))
             .thenReturn(autoSubmitted == null ? null : new String[] { autoSubmitted });
    lenient().when(message.getHeader("Precedence")).thenReturn(precedence == null ? null : new String[] { precedence });
    lenient().when(message.getHeader("List-Post")).thenReturn(listPost == null ? null : new String[] { listPost });
    return message;
  }

  @Test
  @SneakyThrows
  void isAutoSubmittedReadsTheDeliveryHeaders() {
    // No headers at all: a message somebody typed.
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage(null, null, null)));
    // RFC 3834: any value other than "no" declares the message machine-generated, and the
    // comparison is case-insensitive -- providers stamp "Auto-Replied" and "AUTO-GENERATED".
    assertTrue(EmailBoxService.isAutoSubmitted(bulkSignalMessage("auto-generated", null, null)));
    assertTrue(EmailBoxService.isAutoSubmitted(bulkSignalMessage("Auto-Replied", null, null)));
    assertTrue(EmailBoxService.isAutoSubmitted(bulkSignalMessage("AUTO-NOTIFIED", null, null)));
    // "no" is the explicit opposite and must NOT be read as automated, whatever its case
    // or surrounding whitespace.
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage("no", null, null)));
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage("NO", null, null)));
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage("  no  ", null, null)));
    // A blank header is not a declaration either.
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage("   ", null, null)));
    // Legacy Precedence, again case-insensitively.
    assertTrue(EmailBoxService.isAutoSubmitted(bulkSignalMessage(null, "bulk", null)));
    assertTrue(EmailBoxService.isAutoSubmitted(bulkSignalMessage(null, "Junk", null)));
    // "list" is deliberately NOT automated: a mailing list stamps it on every message it
    // relays, including one a colleague typed by hand.
    assertFalse(EmailBoxService.isAutoSubmitted(bulkSignalMessage(null, "list", null)));
  }

  @Test
  @SneakyThrows
  void isPostableListNeedsAnActualMailtoAddress() {
    assertFalse(EmailBoxService.isPostableList(bulkSignalMessage(null, null, null)));
    assertTrue(EmailBoxService.isPostableList(bulkSignalMessage(null, null, "<mailto:team@example.com>")));
    assertTrue(EmailBoxService.isPostableList(bulkSignalMessage(null, null, "<MAILTO:team@example.com>")));
    // A list that explicitly refuses posting, and a malformed value: neither is postable,
    // which is what separates a discussion list from a one-way blast.
    assertFalse(EmailBoxService.isPostableList(bulkSignalMessage(null, null, "NO")));
    assertFalse(EmailBoxService.isPostableList(bulkSignalMessage(null, null, "team@example.com")));
    assertFalse(EmailBoxService.isPostableList(bulkSignalMessage(null, null, "")));
  }

  @Test
  @SneakyThrows
  void synchronizePersistsTheBulkMailSignals() {
    UserEmailSetting userEmailSetting = userEmailSetting();
    Folder inbox = mockInboxForSync(userEmailSetting, 1);
    MimeMessage message = (MimeMessage) inbox.getMessages(1, 1)[0];
    lenient().when(message.getHeader("Auto-Submitted")).thenReturn(new String[] { "auto-generated" });
    lenient().when(message.getHeader("List-Id")).thenReturn(new String[] { "<team.example.com>" });
    lenient().when(message.getHeader("List-Post")).thenReturn(new String[] { "<mailto:team@example.com>" });
    lenient().when(message.getHeader("List-Unsubscribe")).thenReturn(new String[] { "<mailto:bye@example.com>" });
    lenient().when(message.getHeader("X-Original-Sender")).thenReturn(new String[] { "author@example.com" });

    emailBoxService.synchronize(TEST_USER);

    // The five signals must survive onto the cached row: they are the whole point of this
    // groundwork, and a downstream categorizer only ever sees the row, never the message.
    ArgumentCaptor<Email> created = ArgumentCaptor.forClass(Email.class);
    verify(emailBoxStorage).createEmail(created.capture());
    Email cached = created.getValue();
    assertTrue(cached.isAutoSubmitted());
    assertTrue(cached.isHasListId());
    assertTrue(cached.isHasListPost());
    assertTrue(cached.isHasListUnsubscribe());
    assertEquals("author@example.com", cached.getOriginalSender());
  }

  @Test
  @SneakyThrows
  void notificationBackstopFiresWhenAClaimIsNeverReleased() {
    String user = "backstopuser";
    // The backstop exists for the consumer that claims the wait and then dies. Shortened so
    // the deadline can actually elapse here, which is the only way to exercise it at all.
    ReflectionTestUtils.setField(emailBoxService, "notificationMaxWaitMs", 150L);
    emailBoxService.openNotificationWindow(user, List.of());
    emailBoxService.deferNewEmailsNotification(user);
    emailBoxService.completeNotificationWindow(user, List.of());
    // Nobody ever releases the claim: without the timer the notification would be lost.
    verify(emailBoxStorage, never()).getEmails(user, MailFolder.INBOX);
    verify(emailBoxStorage, timeout(3000)).getEmails(user, MailFolder.INBOX);
  }

  @Test
  @SneakyThrows
  void notificationGraceTimerFiresOnAWindowWithNoClaim() {
    String user = "graceuser";
    ReflectionTestUtils.setField(emailBoxService, "notificationGraceMs", 150L);
    // A sync nobody classifies: the window completes with no claim, so the short grace
    // delay owns the send.
    emailBoxService.openNotificationWindow(user, List.of());
    emailBoxService.completeNotificationWindow(user, List.of());
    verify(emailBoxStorage, timeout(3000)).getEmails(user, MailFolder.INBOX);
  }

  @Test
  @SneakyThrows
  void concurrentClaimsAndReleasesSendExactlyOnce() {
    String user = "raceuser";
    // A long backstop, so anything that fires here is the real release path rather than a
    // timer -- the point is that racing threads cannot produce two sends, nor zero.
    ReflectionTestUtils.setField(emailBoxService, "notificationMaxWaitMs", 60000L);
    int groups = 32;
    emailBoxService.openNotificationWindow(user, List.of());
    CountDownLatch start = new CountDownLatch(1);
    CountDownLatch done = new CountDownLatch(groups);
    List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < groups; i++) {
      Thread thread = new Thread(() -> {
        try {
          emailBoxService.deferNewEmailsNotification(user);
          start.await();
          emailBoxService.notifyNewEmailsClassified(user);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        } finally {
          done.countDown();
        }
      });
      threads.add(thread);
      thread.start();
    }
    // Every claim is taken before any release, so the window can only empty after the
    // sync is marked complete below.
    emailBoxService.completeNotificationWindow(user, List.of());
    start.countDown();
    assertTrue(done.await(10, TimeUnit.SECONDS));
    for (Thread thread : threads) {
      thread.join(10000);
    }
    // Exactly one send, whatever order the releases landed in: the claim count and the
    // window's completion flag are read and written inside the same compute().
    verify(emailBoxStorage, times(1)).getEmails(user, MailFolder.INBOX);
  }

  @Test
  @SneakyThrows
  void unlinkEmailsFromCategory() {
    assertEquals(0, emailBoxService.unlinkEmailsFromCategory(List.of(), 5L, TEST_USER));
    verify(categoryLinkService, never()).unlink(anyLong(), any(CategoryObject.class), anyString());

    // An email that is not the user's own (or no longer cached) is skipped.
    assertEquals(0, emailBoxService.unlinkEmailsFromCategory(List.of(1212l), 5L, TEST_USER));
    verify(categoryLinkService, never()).unlink(anyLong(), any(CategoryObject.class), anyString());

    mockOwnedEmail();
    assertEquals(1, emailBoxService.unlinkEmailsFromCategory(List.of(1212l), 5L, TEST_USER));
    ArgumentCaptor<CategoryObject> objectCaptor = ArgumentCaptor.forClass(CategoryObject.class);
    verify(categoryLinkService).unlink(eq(5L), objectCaptor.capture(), eq(TEST_USER));
    assertEquals(EmailCategoryPlugin.OBJECT_TYPE, objectCaptor.getValue().getType());
    assertEquals("7", objectCaptor.getValue().getId());

    // Not linked to that category: idempotent, not counted as unlinked.
    doThrow(ObjectNotFoundException.class).when(categoryLinkService)
                                          .unlink(anyLong(), any(CategoryObject.class), anyString());
    assertEquals(0, emailBoxService.unlinkEmailsFromCategory(List.of(1212l), 5L, TEST_USER));
  }

  private void mockCategoryIdSetting(String nameId, String storedId) {
    doReturn(storedId == null ? null : SettingValue.create(storedId)).when(settingService)
                                                                    .get(any(Context.class), any(Scope.class), eq(nameId));
  }

  @SneakyThrows
  private void mockOwnedEmail() {
    Email email = email(TEST_USER);
    email.setId(7l);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(eq(1212l),
                                                         eq(TEST_USER),
                                                         any(),
                                                         anyString(),
                                                         anyBoolean(),
                                                         anyBoolean(),
                                                         anyBoolean())).thenReturn(email);
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
