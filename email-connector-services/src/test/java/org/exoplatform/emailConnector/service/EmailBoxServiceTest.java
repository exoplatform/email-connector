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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import java.util.ArrayList;

import javax.mail.Address;
import javax.mail.Folder;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.MimeMessage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
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
    MimeMessage message1 = mock(MimeMessage.class);
    when(message1.getSubject()).thenReturn("message1Subject");
    MimeMessage message2 = mock(MimeMessage.class);
    when(message2.getSubject()).thenReturn("message2Subject");
    MimeMessage[] messages = { message1, message2 };
    when((inbox).getMessages(anyInt(), anyInt())).thenReturn(messages);
    when(emailBoxStorage.getEmails(anyString())).thenReturn(new ArrayList<Email>());
    emailBoxService.synchronize(TEST_USER);
    verify(userEmailSettingService, times(2)).setUserEmailSetting(any(UserEmailSetting.class), anyString(), anyBoolean());
    when(inbox.getMessageCount()).thenReturn(1000);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage, times(2)).createEmail(any(Email.class));
  }

  @Test
  void getEmailBox() {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    emailBoxService.getEmailBox(TEST_USER);
    verify(userEmailSettingService).getUserEmailSetting(TEST_USER);
    verify(emailBoxStorage).getEmails(TEST_USER);

  }

  @Test
  void deleteUserEmails() {
    emailBoxService.deleteUserEmails(TEST_USER);
    verify(emailBoxStorage).deleteUserEmails(TEST_USER);
  }

  @Test
  void scheduleEmailBoxUserSyncJob() throws Exception {
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting());
    emailBoxService.scheduleEmailBoxUserSyncJob(TEST_USER);
    verify(jobSchedulerService).removeJob(any(JobInfo.class));
    verify(jobSchedulerService).addPeriodJob(any(JobInfo.class), any(PeriodInfo.class));
  }

  @Test
  void getEmailById() throws Exception {
    UserEmailSetting userEmailSetting = userEmailSetting();
    when(userEmailSettingService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Store store = mock(Store.class);
    when(userEmailSettingService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    MimeMessage message1 = mock(MimeMessage.class);
    when(((UIDFolder) inbox).getMessageByUID(2121212L)).thenReturn(message1);
    try (MockedStatic<EmailConnectorUtils> mockedUtils = mockStatic(EmailConnectorUtils.class)) {
      emailBoxService.getEmailById(2121212L, TEST_USER);
      mockedUtils.verify(() -> EmailConnectorUtils.getEmailSender(nullable(Address[].class)));
      mockedUtils.verify(() -> EmailConnectorUtils.getEmailRecipients(nullable(Address[].class), anyString()), times(3));
    }
  }

  private UserEmailSetting userEmailSetting() {
    return new UserEmailSetting("1", "testEmail", "testPassword", null, null, 0, 0L, null, null, null, true);
  }
}
