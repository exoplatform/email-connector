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
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.Store;
import javax.mail.UIDFolder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.scheduler.JobInfo;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.services.scheduler.PeriodInfo;

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
    Message message1 = mock(Message.class);
    when(message1.getSubject()).thenReturn("message1Subject");
    Message message2 = mock(Message.class);
    when(message2.getSubject()).thenReturn("message2Subject");
    Message[] messages = { message1, message2 };
    when((inbox).getMessages(anyInt(), anyInt())).thenReturn(messages);
    when(emailBoxStorage.getEmails(anyString())).thenReturn(new ArrayList<Email>());
    emailBoxService.synchronize(TEST_USER);
    verify(userEmailSettingService, times(2)).setUserEmailSetting(any(UserEmailSetting.class), anyString(), anyBoolean());
    when(inbox.getMessageCount()).thenReturn(1000);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage, times(2)).createEmail(any(Email.class));
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
    verify(emailBoxStorage).deleteEmailsByUserId(TEST_USER);
  }

  @Test
  void scheduleEmailBoxUserSyncJob() throws Exception {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    emailBoxService.scheduleEmailBoxUserSyncJob(TEST_USER);
    verify(jobSchedulerService).removeJob(any(JobInfo.class));
    verify(jobSchedulerService).addPeriodJob(any(JobInfo.class), any(PeriodInfo.class));
  }

  @Test
  void broadcastEvent() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    emailBoxService.broadcastEvent("operation", TEST_USER);
    verify(listenerService).broadcast("operation", TEST_USER, "connector");
  }

  @Test
  void getEmailByMailRemoteIdAndUserId() throws IllegalAccessException {
    emailBoxService.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, false, false, false, false);
    verify(emailBoxStorage).getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, false, false, false);
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
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    emailBoxService.updateEmailReadStatus(mailRemoteIds, TEST_USER, false, true);
    verify(emailBoxStorage).updateEmailReadStatusByMailRemoteIds(mailRemoteIds, TEST_USER, false);
    verify(inbox).open(Folder.READ_WRITE);
    verify(message).setFlag(Flags.Flag.SEEN, false);
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
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, true, true, false)).thenReturn(email);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    IMAPFolder inbox = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    IMAPFolder trashFolder = mock(IMAPFolder.class);
    when(trashFolder.getFullName()).thenReturn("trash");
    Folder[] folders = new Folder[] { trashFolder };
    when(folder.listSubscribed("*")).thenReturn(folders);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    emailBoxService.deleteEmail(emailIds, TEST_USER);
    verify(emailBoxStorage).deleteEmailsByIds(anyList());
    verify(inbox).open(Folder.READ_WRITE);
    verify(message).setFlag(Flags.Flag.DELETED, true);
    verify(inbox).moveMessages(any(Message[].class), any(Folder.class));
  }

  @Test
  void archiveEmailByMailRemoteIdAndUserId() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    List<Long> emailIds = List.of(1212l);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.archiveEmail(emailIds, TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email email = email(TEST_USER);
    when(emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, TEST_USER, true, true, false)).thenReturn(email);
    IMAPStore store = mock(IMAPStore.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    IMAPFolder inbox = mock(IMAPFolder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    Folder folder = mock(Folder.class);
    when(store.getDefaultFolder()).thenReturn(folder);
    IMAPFolder archiveFolder = mock(IMAPFolder.class);
    when(archiveFolder.getFullName()).thenReturn("archive");
    Folder[] folders = new Folder[] { archiveFolder };
    when(folder.listSubscribed("*")).thenReturn(folders);
    Message message = mock(Message.class);
    when(((UIDFolder) inbox).getMessageByUID(1212l)).thenReturn(message);
    emailBoxService.archiveEmail(emailIds, TEST_USER);
    verify(emailBoxStorage).deleteEmailsByIds(anyList());
    verify(inbox).open(Folder.READ_WRITE);
    verify(inbox).moveMessages(any(Message[].class), any(Folder.class));
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
                     username,
                     "subject",
                     new EmailContent("excerpt"),
                     new Date(),
                     new EmailSender("sender", null, null, null),
                     false,
                     null,
                     null,
                     null);
  }
}
