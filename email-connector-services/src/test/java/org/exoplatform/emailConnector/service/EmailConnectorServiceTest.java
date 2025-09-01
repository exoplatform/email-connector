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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.storage.EmailConnectorStorage;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;

import io.meeds.social.translation.service.TranslationService;
import lombok.SneakyThrows;

@SpringBootTest(classes = { EmailConnectorService.class })
@ExtendWith(MockitoExtension.class)
public class EmailConnectorServiceTest {

  private static final String   TEST_USER = "testuser";

  @MockBean
  private UserACL               userAcl;

  @MockBean
  private FileService           fileService;

  @MockBean
  private TranslationService    translationService;

  @MockBean
  private EmailConnectorStorage emailConnectorStorage;

  @Autowired
  private EmailConnectorService emailConnectorService;

  @Test
  @SneakyThrows
  void createEmailConnector() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.createEmailConnector(null, TEST_USER));
    EmailConnector emailConnector = emailConnector(null);
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.createEmailConnector(emailConnector, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    emailConnectorService.createEmailConnector(emailConnector, TEST_USER);
    verify(emailConnectorStorage).createEmailConnector(emailConnector);
  }

  @Test
  @SneakyThrows
  void updateEmailConnector() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.updateEmailConnector(null, TEST_USER));
    EmailConnector emailConnector = emailConnector(null);
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.updateEmailConnector(emailConnector, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    emailConnectorService.createEmailConnector(emailConnector, TEST_USER);
    verify(emailConnectorStorage).createEmailConnector(emailConnector);
  }

  @Test
  void getEmailConnector() {
    emailConnectorService.getEmailConnector(1L);
    verify(emailConnectorStorage).getEmailConnector(1L);
  }

  @Test
  void getEmailConnectors() {
    Locale frLocale = mock(Locale.class);
    List<EmailConnector> list = List.of(mock(EmailConnector.class));
    when(emailConnectorStorage.getEmailConnectors()).thenReturn(list);
    emailConnectorService.getEmailConnectors(frLocale);
    verify(emailConnectorStorage).getEmailConnectors();
    verify(translationService).getTranslationLabelOrDefault(anyString(), anyLong(), anyString(), any(Locale.class));
  }

  private EmailConnector emailConnector(Long id) {
    return new EmailConnector(id, "testName", null, null, null, "testImapUrl", "testPort", false, "testUploadId");
  }
}
