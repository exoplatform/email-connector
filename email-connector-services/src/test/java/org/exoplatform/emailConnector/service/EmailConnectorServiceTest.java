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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.file.services.FileStorageException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.storage.EmailConnectorStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;

import io.meeds.appcenter.model.ApplicationList;
import io.meeds.appcenter.service.ApplicationCenterService;
import io.meeds.social.translation.service.TranslationService;

import lombok.SneakyThrows;

@SpringBootTest(classes = { EmailConnectorService.class })
@ExtendWith(MockitoExtension.class)
public class EmailConnectorServiceTest {

  private static final String      TEST_USER = "testuser";

  @MockitoBean
  private UserACL                  userAcl;

  @MockitoBean
  private TranslationService       translationService;

  @MockitoBean
  private ApplicationCenterService applicationCenterService;

  @MockitoBean
  private ExoFeatureService        featureService;

  @MockitoBean
  private EmailConnectorStorage    emailConnectorStorage;

  @MockitoBean
  private FileService              fileService;

  @MockitoBean
  private SettingService           settingService;

  @Autowired
  private EmailConnectorService    emailConnectorService;

  @Test
  @SneakyThrows
  void activateEmailFeature() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.activateEmailFeature(null, TEST_USER));
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.activateEmailFeature(true, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    ApplicationList applicationList = mock(ApplicationList.class);
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(applicationList);
    emailConnectorService.activateEmailFeature(true, TEST_USER);
    verify(featureService).saveActiveFeature(EmailConnectorUtils.EMAIL_FEATURE, true);
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
    ApplicationList applicationList = mock(ApplicationList.class);
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(applicationList);
    emailConnectorService.createEmailConnector(emailConnector, TEST_USER);
    verify(emailConnectorStorage).createEmailConnector(emailConnector);
    verify(applicationCenterService).getApplications(0, 0, null);
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
    emailConnectorService.updateEmailConnector(emailConnector, TEST_USER);
    verify(emailConnectorStorage).updateEmailConnector(emailConnector);
  }

  @Test
  @SneakyThrows
  void activateEmailConnector() {
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.activateEmailConnector(null, true, TEST_USER));
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.activateEmailConnector(1L, true, TEST_USER));
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorStorage.getEmailConnector(1L)).thenReturn(emailConnector);
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.activateEmailConnector(1L, true, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    ApplicationList applicationList = mock(ApplicationList.class);
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(applicationList);
    emailConnectorService.activateEmailConnector(1L, true, TEST_USER);
    verify(emailConnectorStorage).activateEmailConnector(1L, true);
    verify(applicationCenterService).getApplications(0, 0, null);
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
    ApplicationList applicationList = mock(ApplicationList.class);
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(applicationList);
    emailConnectorService.deleteEmailConnector(1L, TEST_USER);
    verify(emailConnectorStorage).deleteEmailConnector(1L);
    verify(applicationCenterService).getApplications(0, 0, null);
  }

  @Test
  void getEmailConnector() {
    emailConnectorService.getEmailConnector(1L);
    verify(emailConnectorStorage).getEmailConnector(1L);
  }

  @Test
  void getEmailBoxCacheSizeReturnsDefaultWhenUnset() {
    when(settingService.get(Context.GLOBAL,
                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                            EmailConnectorService.EMAIL_BOX_CACHE_SIZE_KEY)).thenReturn(null);
    assertEquals(EmailConnectorUtils.DEFAULT_EMAIL_BOX_CACHE_SIZE, emailConnectorService.getEmailBoxCacheSize());
  }

  @Test
  void getEmailBoxCacheSizeReturnsStoredValue() {
    doReturn(SettingValue.create("250")).when(settingService)
                                        .get(Context.GLOBAL,
                                             EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                             EmailConnectorService.EMAIL_BOX_CACHE_SIZE_KEY);
    assertEquals(250, emailConnectorService.getEmailBoxCacheSize());
  }

  @Test
  @SneakyThrows
  void saveEmailBoxCacheSize() {
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.saveEmailBoxCacheSize(500, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.saveEmailBoxCacheSize(0, TEST_USER));
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.saveEmailBoxCacheSize(5001, TEST_USER));
    emailConnectorService.saveEmailBoxCacheSize(500, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.EMAIL_BOX_CACHE_SIZE_KEY),
                               any(SettingValue.class));
  }

  @Test
  void getEmailBoxSyncPeriodReturnsDefaultWhenUnset() {
    when(settingService.get(Context.GLOBAL,
                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                            EmailConnectorService.EMAIL_BOX_SYNC_PERIOD_KEY)).thenReturn(null);
    assertEquals(10, emailConnectorService.getEmailBoxSyncPeriod());
  }

  @Test
  void getEmailBoxSyncPeriodReturnsStoredValue() {
    doReturn(SettingValue.create("30")).when(settingService)
                                       .get(Context.GLOBAL,
                                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                            EmailConnectorService.EMAIL_BOX_SYNC_PERIOD_KEY);
    assertEquals(30, emailConnectorService.getEmailBoxSyncPeriod());
  }

  @Test
  @SneakyThrows
  void saveEmailBoxSyncPeriod() {
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.saveEmailBoxSyncPeriod(15, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.saveEmailBoxSyncPeriod(4, TEST_USER));
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.saveEmailBoxSyncPeriod(1441, TEST_USER));
    emailConnectorService.saveEmailBoxSyncPeriod(15, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.EMAIL_BOX_SYNC_PERIOD_KEY),
                               any(SettingValue.class));
  }

  @Test
  void getEmailSyncThreadsReturnsDefaultWhenUnset() {
    when(settingService.get(Context.GLOBAL,
                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                            EmailConnectorService.EMAIL_SYNC_THREADS_KEY)).thenReturn(null);
    assertEquals(10, emailConnectorService.getEmailSyncThreads());
  }

  @Test
  void getEmailSyncThreadsReturnsStoredValue() {
    doReturn(SettingValue.create("24")).when(settingService)
                                       .get(Context.GLOBAL,
                                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                            EmailConnectorService.EMAIL_SYNC_THREADS_KEY);
    assertEquals(24, emailConnectorService.getEmailSyncThreads());
  }

  /**
   * The executor size is an administrator's to set, within the bounds a mail
   * server and a connection pool can take: zero threads would stop every mailbox
   * and sixty-five would be a connection problem before a throughput one.
   */
  @Test
  @SneakyThrows
  void saveEmailSyncThreads() {
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.saveEmailSyncThreads(16, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.saveEmailSyncThreads(0, TEST_USER));
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.saveEmailSyncThreads(65, TEST_USER));
    emailConnectorService.saveEmailSyncThreads(16, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.EMAIL_SYNC_THREADS_KEY),
                               any(SettingValue.class));
  }

  @Test
  void trashSyncEnabledDefaultsToTrueWhenUnset() {
    when(settingService.get(Context.GLOBAL,
                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                            EmailConnectorService.TRASH_SYNC_ENABLED_KEY)).thenReturn(null);
    assertEquals(true, emailConnectorService.isTrashSyncEnabled());
  }

  @Test
  @SneakyThrows
  void saveTrashSyncEnabled() {
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.saveTrashSyncEnabled(false, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    emailConnectorService.saveTrashSyncEnabled(false, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.TRASH_SYNC_ENABLED_KEY),
                               any(SettingValue.class));
  }

  @Test
  void junkSyncEnabledDefaultsToTrueWhenUnset() {
    when(settingService.get(Context.GLOBAL,
                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                            EmailConnectorService.JUNK_SYNC_ENABLED_KEY)).thenReturn(null);
    assertEquals(true, emailConnectorService.isJunkSyncEnabled());
  }

  @Test
  @SneakyThrows
  void saveJunkSyncEnabled() {
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.saveJunkSyncEnabled(false, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    emailConnectorService.saveJunkSyncEnabled(false, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.JUNK_SYNC_ENABLED_KEY),
                               any(SettingValue.class));
  }

  @Test
  void serverDraftsEnabledDefaultsToTrueWhenUnset() {
    when(settingService.get(Context.GLOBAL,
                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                            EmailConnectorService.DRAFTS_SERVER_ENABLED_KEY)).thenReturn(null);
    assertEquals(true, emailConnectorService.isServerDraftsEnabled());
  }

  @Test
  @SneakyThrows
  void saveServerDraftsEnabled() {
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.saveServerDraftsEnabled(false, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    emailConnectorService.saveServerDraftsEnabled(false, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.DRAFTS_SERVER_ENABLED_KEY),
                               any(SettingValue.class));
  }

  @Test
  void customFoldersEnabledDefaultsToTrueWhenUnset() {
    when(settingService.get(Context.GLOBAL,
                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                            EmailConnectorService.CUSTOM_FOLDERS_ENABLED_KEY)).thenReturn(null);
    assertEquals(true, emailConnectorService.isCustomFoldersEnabled());
  }

  @Test
  void customFoldersEnabledReturnsStoredValue() {
    doReturn(SettingValue.create("false")).when(settingService)
                                          .get(Context.GLOBAL,
                                               EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                               EmailConnectorService.CUSTOM_FOLDERS_ENABLED_KEY);
    assertEquals(false, emailConnectorService.isCustomFoldersEnabled());
  }

  /**
   * The switch's whole contract: an administrator's stored value must win over the
   * JVM property, in both directions -- otherwise the drawer would be a control an
   * operator's {@code exo.properties} could silently overrule.
   */
  @Test
  void theStoredValueOverridesTheJvmPropertyInBothDirections() {
    System.setProperty("email.connector.customFolders.enabled", "true");
    try {
      doReturn(SettingValue.create("false")).when(settingService)
                                            .get(Context.GLOBAL,
                                                 EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                                 EmailConnectorService.CUSTOM_FOLDERS_ENABLED_KEY);
      assertEquals(false, emailConnectorService.isCustomFoldersEnabled(),
                   "a stored 'false' must be refused even though the JVM property says true");
    } finally {
      System.clearProperty("email.connector.customFolders.enabled");
    }

    System.setProperty("email.connector.customFolders.enabled", "false");
    try {
      doReturn(SettingValue.create("true")).when(settingService)
                                           .get(Context.GLOBAL,
                                                EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                                EmailConnectorService.CUSTOM_FOLDERS_ENABLED_KEY);
      assertEquals(true, emailConnectorService.isCustomFoldersEnabled(),
                   "a stored 'true' must be honored even though the JVM property says false");
    } finally {
      System.clearProperty("email.connector.customFolders.enabled");
    }
  }

  @Test
  @SneakyThrows
  void saveCustomFoldersEnabled() {
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.saveCustomFoldersEnabled(false, TEST_USER));
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    emailConnectorService.saveCustomFoldersEnabled(false, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.CUSTOM_FOLDERS_ENABLED_KEY),
                               any(SettingValue.class));
  }

  @Test
  void getEmailConnectors() {
    Locale frLocale = mock(Locale.class);
    List<EmailConnector> list = List.of(mock(EmailConnector.class));
    when(emailConnectorStorage.getEmailConnectors()).thenReturn(list);
    emailConnectorService.getEmailConnectors(frLocale);
    verify(translationService).getTranslationLabelOrDefault(anyString(), anyLong(), anyString(), any(Locale.class));
  }

  @Test
  void getActiveEmailConnectors() {
    emailConnectorService.getActiveEmailConnectors();
    verify(emailConnectorStorage).getActiveEmailConnectors();
  }

  @Test
  void getEmailConnectorImageInputStream() throws FileStorageException {
    when(emailConnectorStorage.getEmailConnector(1L)).thenReturn(emailConnector());
    emailConnectorService.getEmailConnectorImageInputStream(1L);
    verify(fileService).getFile(1L);
  }

  @Test
  void canEdit() {
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(mock(Identity.class));
    emailConnectorService.canEdit(TEST_USER);
    verify(userAcl).isAdministrator(any(Identity.class));
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
                              "", null);
  }
}
