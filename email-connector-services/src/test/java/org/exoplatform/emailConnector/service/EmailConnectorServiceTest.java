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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.mail.Session;
import javax.mail.Store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailConnectorStorage;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;
import org.exoplatform.web.security.codec.AbstractCodec;
import org.exoplatform.web.security.codec.CodecInitializer;

import io.meeds.appcenter.model.ApplicationList;
import io.meeds.appcenter.service.ApplicationCenterService;
import io.meeds.social.translation.service.TranslationService;
import io.meeds.social.util.JsonUtils;
import lombok.SneakyThrows;

@SpringBootTest(classes = { EmailConnectorService.class })
@ExtendWith(MockitoExtension.class)
public class EmailConnectorServiceTest {

  private static final String      TEST_USER = "testuser";

  @MockBean
  private UserACL                  userAcl;

  @MockBean
  private FileService              fileService;

  @MockBean
  private TranslationService       translationService;

  @MockBean
  private EmailConnectorStorage    emailConnectorStorage;

  @MockBean
  private SettingService           settingService;

  @MockBean
  private ApplicationCenterService applicationCenterService;

  @MockBean
  private CodecInitializer         codecInitializer;

  @MockBean
  private ExoFeatureService        featureService;

  @Autowired
  private EmailConnectorService    emailConnectorService;

  @Test
  @SneakyThrows
  void activateEmailFeature() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.activateEmailFeature(null, TEST_USER));
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.activateEmailFeature("true", TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    ApplicationList applicationList = mock(ApplicationList.class);
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(applicationList);
    emailConnectorService.activateEmailFeature("true", TEST_USER);
    verify(featureService).saveActiveFeature(EmailConnectorService.EMAIL_FEATURE, true);
    verify(applicationCenterService).getApplications(0, 0, null);
  }

  @Test
  @SneakyThrows
  void createEmailConnector() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.createEmailConnector(null, TEST_USER));
    EmailConnector emailConnector = emailConnector();
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
    EmailConnector emailConnector = emailConnector();
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.updateEmailConnector(emailConnector, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    emailConnectorService.createEmailConnector(emailConnector, TEST_USER);
    verify(emailConnectorStorage).createEmailConnector(emailConnector);
  }

  @Test
  @SneakyThrows
  void activateEmailConnector() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.activateEmailConnector(null, "true", TEST_USER));
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.activateEmailConnector(1L, "true", TEST_USER));
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorStorage.getEmailConnector(1L)).thenReturn(emailConnector);
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.activateEmailConnector(1L, "true", TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    emailConnectorService.activateEmailConnector(1L, "true", TEST_USER);
    verify(emailConnectorStorage).activateEmailConnector(1L, "true");
  }

  @Test
  @SneakyThrows
  void deleteEmailConnector() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.deleteEmailConnector(null, TEST_USER));
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.deleteEmailConnector(1L, TEST_USER));
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorStorage.getEmailConnector(1L)).thenReturn(emailConnector);
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.deleteEmailConnector(1L, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    emailConnectorService.deleteEmailConnector(1L, TEST_USER);
    verify(emailConnectorStorage).deleteEmailConnector(1L);
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

  @Test
  void getActiveEmailConnectors() {
    Locale frLocale = mock(Locale.class);
    List<EmailConnector> list = List.of(mock(EmailConnector.class));
    when(emailConnectorStorage.getActiveEmailConnectors()).thenReturn(list);
    emailConnectorService.getActiveEmailConnectors(frLocale, TEST_USER);
    verify(emailConnectorStorage).getActiveEmailConnectors();
  }

  @Test
  @SneakyThrows
  void setUserEmailSetting() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.setUserEmailSetting(null, TEST_USER));
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorStorage.getEmailConnector(1L)).thenReturn(emailConnector);
    Session session = mock(Session.class);
    MockedStatic<Session> mockedSession = mockStatic(Session.class);
    mockedSession.when(() -> Session.getDefaultInstance(any(Properties.class))).thenReturn(session);
    Store store = mock(Store.class);
    when(session.getStore()).thenReturn(store);
    when(store.isConnected()).thenReturn(true);
    when(codecInitializer.getCodec()).thenReturn(mock(AbstractCodec.class));
    UserEmailSetting userEmailSetting = userEmailSetting();
    emailConnectorService.setUserEmailSetting(userEmailSetting, TEST_USER);
    verify(store).connect(anyString(), anyInt(), anyString(), anyString());
    verify(settingService).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
  }

  @Test
  void getUserEmailSetting() {
    SettingValue userEmailSettingValue = mock(SettingValue.class);
    when(settingService.get(any(Context.class), any(Scope.class), anyString())).thenReturn(userEmailSettingValue);
    String userEmailObject =
                           "{\"emailConnectorId\":\"1\",\"emailConnectorImageUrl\":null,\"emailConnectorIcon\":null,\"emailAddress\":\"testEmail\",\"emailPassword\":\"testPassword\"}";
    when(userEmailSettingValue.getValue()).thenReturn(userEmailObject);
    MockedStatic<JsonUtils> mockedJsonUtils = mockStatic(JsonUtils.class);
    mockedJsonUtils.when(() -> JsonUtils.fromJsonString(userEmailObject, UserEmailSetting.class)).thenReturn(userEmailSetting());
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorStorage.getEmailConnector(1L)).thenReturn(emailConnector);
    emailConnectorService.getUserEmailSetting(TEST_USER);
    verify(settingService).get(any(Context.class), any(Scope.class), anyString());
    verify(emailConnectorStorage).getEmailConnector(1L);
  }

  @Test
  void deleteUserEmailSetting() {
    emailConnectorService.deleteUserEmailSetting(TEST_USER);
    verify(settingService).remove(any(Context.class), any(Scope.class), anyString());
  }

  private EmailConnector emailConnector() {
    return new EmailConnector(null, "testName", null, null, null, "testImapUrl", "8000", false, false, true, "testUploadId");
  }

  private UserEmailSetting userEmailSetting() {
    return new UserEmailSetting("1", null, null, "testEmail", "testPassword");
  }
}
