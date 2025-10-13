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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.Store;
import javax.mail.UIDFolder;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;

import lombok.SneakyThrows;

@SpringBootTest(classes = { EmailBoxService.class })
@ExtendWith(MockitoExtension.class)
public class EmailBoxServiceTest {

  private static final String   TEST_USER = "testuser";

  @MockBean
  private EmailConnectorService emailConnectorService;

  @MockBean
  private EmailBoxStorage       emailBoxStorage;

  @Autowired
  private EmailBoxService       emailBoxService;

  @Test
  @SneakyThrows
  void synchronize() {
    UserEmailSetting userEmailSetting = mock(UserEmailSetting.class);
    when(emailConnectorService.getUserEmailSetting(TEST_USER)).thenReturn(userEmailSetting);
    when(emailConnectorService.canConnect(userEmailSetting)).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> emailBoxService.synchronize(TEST_USER));
    when(emailConnectorService.canConnect(userEmailSetting)).thenReturn(true);
    when(userEmailSetting.getEmailSyncStatus()).thenReturn(null);
    Store store = mock(Store.class);
    when(emailConnectorService.connect(userEmailSetting)).thenReturn(store);
    Folder inbox = mock(Folder.class, withSettings().extraInterfaces(UIDFolder.class));
    when(store.getFolder("INBOX")).thenReturn(inbox);
    Message message1 = mock(Message.class);
    when(message1.getSubject()).thenReturn("message1Subject");
    Message message2 = mock(Message.class);
    when(message2.getSubject()).thenReturn("message2Subject");
    Message[] messages = { message1, message2 };
    when(inbox.getMessages()).thenReturn(messages);
    emailBoxService.synchronize(TEST_USER);
    verify(emailBoxStorage, times(2)).createEmail(any(Email.class));
    verify(emailConnectorService, times(2)).setUserEmailSetting(any(UserEmailSetting.class), anyString());

  }

  @Test
  void getEmails() {
    emailBoxService.getEmails(TEST_USER);
    verify(emailBoxStorage).getEmails(TEST_USER);
  }

  @Test
  void deleteUserEmails() {
    emailBoxService.deleteUserEmails(TEST_USER);
    verify(emailBoxStorage).deleteUserEmails(TEST_USER);
  }
}
