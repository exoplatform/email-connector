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

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Locale;

import org.springframework.test.util.ReflectionTestUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;
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
import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.emailConnector.storage.EmailConnectorStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.portal.config.UserACL;
import org.exoplatform.services.security.Identity;

import io.meeds.appcenter.model.ApplicationList;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;
import org.exoplatform.services.connector.credentials.ConnectorProviderConfigStorage;

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

  @MockitoBean
  private ConnectorProviderConfigStorage providerConfigStorage;

  @MockitoBean
  private EmailCredentialsResolver emailCredentialsResolver;

  @MockitoBean
  private EmailManagedModeService  emailManagedModeService;

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

  /**
   * EXO-90555 -- a preset is saved with its server fields trimmed, on create and on
   * update: a host typed " 127.0.0.1" was stored so, and every send through it failed.
   */
  @Test
  @SneakyThrows
  void aPresetIsSavedWithItsServerFieldsTrimmed() {
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    lenient().when(applicationCenterService.getApplications(0, 0, null)).thenReturn(mock(ApplicationList.class));
    ArgumentCaptor<EmailConnector> created = ArgumentCaptor.forClass(EmailConnector.class);
    ArgumentCaptor<EmailConnector> updated = ArgumentCaptor.forClass(EmailConnector.class);

    emailConnectorService.createEmailConnector(spacedPreset(), TEST_USER);
    emailConnectorService.updateEmailConnector(spacedPreset(), TEST_USER);

    verify(emailConnectorStorage).createEmailConnector(created.capture());
    verify(emailConnectorStorage).updateEmailConnector(updated.capture());
    for (EmailConnector saved : List.of(created.getValue(), updated.getValue())) {
      assertEquals("127.0.0.1", saved.getSmtpUrl());
      assertEquals("1465", saved.getSmtpPort());
      assertEquals("ssl", saved.getSmtpSecurityType());
      assertEquals("imap.example.org", saved.getImapUrl());
      assertEquals("993", saved.getImapPort());
    }
  }

  /**
   * A preset whose server fields were typed with stray spaces.
   *
   * @return the preset
   */
  private EmailConnector spacedPreset() {
    EmailConnector spaced = emailConnector();
    spaced.setId(null);
    spaced.setSmtpUrl(" 127.0.0.1");
    spaced.setSmtpPort("1465 ");
    spaced.setSmtpSecurityType(" ssl ");
    spaced.setImapUrl(" imap.example.org ");
    spaced.setImapPort(" 993");
    return spaced;
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

  /**
   * EXO-89652. Editing the managed connector asks the managed-mode guard about the
   * provider it would end up with - the effective one, a blank provider in the payload
   * keeping the stored one - and a refusal leaves the connector unwritten.
   */
  @Test
  @SneakyThrows
  void updatingTheManagedConnectorToAnIneligibleProviderIsRefused() {
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(stored);
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    doThrow(new IllegalArgumentException("emailConnector.managed.providerNotEligible")).when(emailManagedModeService)
                                                                                     .checkProviderChangeAllowed(7L, "personal");

    EmailConnector toPersonal = emailConnector();
    toPersonal.setId(7L);
    toPersonal.setAuthProviderName("personal");
    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailConnectorService.updateEmailConnector(toPersonal, TEST_USER));
    assertEquals("emailConnector.managed.providerNotEligible", refusal.getMessage());
    verify(emailConnectorStorage, never()).updateEmailConnector(any());

    EmailConnector renamedOnly = emailConnector();
    renamedOnly.setId(7L);
    renamedOnly.setAuthProviderName(null);
    emailConnectorService.updateEmailConnector(renamedOnly, TEST_USER);
    verify(emailManagedModeService).checkProviderChangeAllowed(7L, "bluemind-sudo");
    verify(emailConnectorStorage).updateEmailConnector(renamedOnly);
  }

  /**
   * EXO-89652 (review round 2). Editing the managed connector with {@code active=false}
   * is refused like the status toggle refuses it - the payload carries the flag and the
   * storage writes it, so the edit must not be the way around the guard. An edit that
   * keeps the connector active asks nothing of that guard.
   */
  @Test
  @SneakyThrows
  void updatingTheManagedConnectorToInactiveIsRefused() {
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(stored);
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    doThrow(new IllegalArgumentException("emailConnector.managed.connectorInUse")).when(emailManagedModeService)
                                                                                 .checkConnectorNotManaged(7L);

    EmailConnector deactivated = emailConnector();
    deactivated.setId(7L);
    deactivated.setAuthProviderName("bluemind-sudo");
    deactivated.setActive(false);
    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailConnectorService.updateEmailConnector(deactivated, TEST_USER));
    assertEquals("emailConnector.managed.connectorInUse", refusal.getMessage());
    verify(emailConnectorStorage, never()).updateEmailConnector(any());

    clearInvocations(emailManagedModeService);
    EmailConnector stillActive = emailConnector();
    stillActive.setId(7L);
    stillActive.setAuthProviderName("bluemind-sudo");
    emailConnectorService.updateEmailConnector(stillActive, TEST_USER);
    verify(emailManagedModeService, never()).checkConnectorNotManaged(anyLong());
    verify(emailConnectorStorage).updateEmailConnector(stillActive);
  }

  /**
   * EXO-89652. Deactivating the connector managed mode points at is refused
   * with the managed-mode code, before the storage is touched; activating it
   * is not guarded, and neither is any other connector.
   */
  @Test
  @SneakyThrows
  void deactivatingTheManagedConnectorIsRefused() {
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(emailConnector());
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    doThrow(new IllegalArgumentException("emailConnector.managed.connectorInUse")).when(emailManagedModeService)
                                                                                 .checkConnectorNotManaged(7L);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailConnectorService.activateEmailConnector(7L, false, TEST_USER));

    assertEquals("emailConnector.managed.connectorInUse", refusal.getMessage());
    verify(emailConnectorStorage, never()).activateEmailConnector(anyLong(), eq(false));

    ApplicationList applicationList = mock(ApplicationList.class);
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(applicationList);
    emailConnectorService.activateEmailConnector(7L, true, TEST_USER);
    verify(emailConnectorStorage).activateEmailConnector(7L, true);
    verify(emailManagedModeService).checkConnectorNotManaged(7L);
  }

  /** Deleting it is refused too, before the provider configuration or the row go. */
  @Test
  @SneakyThrows
  void deletingTheManagedConnectorIsRefused() {
    EmailConnector managed = emailConnector();
    managed.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(managed);
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    doThrow(new IllegalArgumentException("emailConnector.managed.connectorInUse")).when(emailManagedModeService)
                                                                                 .checkConnectorNotManaged(7L);

    IllegalArgumentException refusal = assertThrows(IllegalArgumentException.class,
                                                    () -> emailConnectorService.deleteEmailConnector(7L, TEST_USER));

    assertEquals("emailConnector.managed.connectorInUse", refusal.getMessage());
    verify(emailConnectorStorage, never()).deleteEmailConnector(anyLong());
    verify(providerConfigStorage, never()).delete(any());
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
  void getEmailBoxInactiveSyncPeriodReturnsDefaultWhenUnset() {
    when(settingService.get(Context.GLOBAL,
                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                            EmailConnectorService.EMAIL_BOX_INACTIVE_SYNC_PERIOD_KEY)).thenReturn(null);
    assertEquals(60, emailConnectorService.getEmailBoxInactiveSyncPeriod());
  }

  @Test
  void getEmailBoxInactiveSyncPeriodReturnsStoredValue() {
    doReturn(SettingValue.create("180")).when(settingService)
                                        .get(Context.GLOBAL,
                                             EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                             EmailConnectorService.EMAIL_BOX_INACTIVE_SYNC_PERIOD_KEY);
    assertEquals(180, emailConnectorService.getEmailBoxInactiveSyncPeriod());
  }

  /**
   * The read side floors the inactive period at the active one: an instance
   * upgraded with a stored active period above the inactive default has never
   * had a saver enforce the invariant, and the dispatcher selects on what this
   * getter answers. Mutation-verified: with the clamp removed, this fails.
   */
  @Test
  void getEmailBoxInactiveSyncPeriodNeverAnswersBelowTheActivePeriod() {
    doReturn(SettingValue.create("60")).when(settingService)
                                       .get(Context.GLOBAL,
                                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                            EmailConnectorService.EMAIL_BOX_INACTIVE_SYNC_PERIOD_KEY);
    doReturn(SettingValue.create("120")).when(settingService)
                                        .get(Context.GLOBAL,
                                             EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                             EmailConnectorService.EMAIL_BOX_SYNC_PERIOD_KEY);
    assertEquals(120, emailConnectorService.getEmailBoxInactiveSyncPeriod());
  }

  /**
   * The inactive period's floor is the active period as it stands when saving --
   * thirty here, not the five-minute constant the active period has -- because an
   * inactive mailbox checked more often than an active one is a contradiction;
   * its ceiling is the active period's day.
   */
  @Test
  @SneakyThrows
  void saveEmailBoxInactiveSyncPeriod() {
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.saveEmailBoxInactiveSyncPeriod(60, TEST_USER));
    grantAdministration();
    doReturn(SettingValue.create("30")).when(settingService)
                                       .get(Context.GLOBAL,
                                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                            EmailConnectorService.EMAIL_BOX_SYNC_PERIOD_KEY);
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.saveEmailBoxInactiveSyncPeriod(29, TEST_USER));
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.saveEmailBoxInactiveSyncPeriod(1441, TEST_USER));
    emailConnectorService.saveEmailBoxInactiveSyncPeriod(30, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.EMAIL_BOX_INACTIVE_SYNC_PERIOD_KEY),
                               argThat((SettingValue<?> value) -> "30".equals(value.getValue())));
  }

  /**
   * Saving an active period above the inactive one raises the inactive one to
   * match, since the inactive tier can never be checked more often than the active
   * one; lowering the active period leaves the inactive one alone.
   * Mutation-verified: with the raise removed from {@code saveEmailBoxSyncPeriod},
   * the second verify fails.
   */
  @Test
  @SneakyThrows
  void savingAnActivePeriodAboveTheInactiveOneRaisesTheInactiveOne() {
    grantAdministration();
    doReturn(SettingValue.create("60")).when(settingService)
                                       .get(Context.GLOBAL,
                                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                            EmailConnectorService.EMAIL_BOX_INACTIVE_SYNC_PERIOD_KEY);

    emailConnectorService.saveEmailBoxSyncPeriod(30, TEST_USER);
    verify(settingService, never()).set(eq(Context.GLOBAL),
                                        eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                                        eq(EmailConnectorService.EMAIL_BOX_INACTIVE_SYNC_PERIOD_KEY),
                                        any(SettingValue.class));

    emailConnectorService.saveEmailBoxSyncPeriod(120, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.EMAIL_BOX_SYNC_PERIOD_KEY),
                               argThat((SettingValue<?> value) -> "120".equals(value.getValue())));
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.EMAIL_BOX_INACTIVE_SYNC_PERIOD_KEY),
                               argThat((SettingValue<?> value) -> "120".equals(value.getValue())));
  }

  @Test
  void getEmailBoxActivityThresholdDaysReturnsDefaultWhenUnset() {
    when(settingService.get(Context.GLOBAL,
                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                            EmailConnectorService.EMAIL_BOX_ACTIVITY_THRESHOLD_DAYS_KEY)).thenReturn(null);
    assertEquals(14, emailConnectorService.getEmailBoxActivityThresholdDays());
  }

  @Test
  void getEmailBoxActivityThresholdDaysReturnsStoredValue() {
    doReturn(SettingValue.create("30")).when(settingService)
                                       .get(Context.GLOBAL,
                                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                            EmailConnectorService.EMAIL_BOX_ACTIVITY_THRESHOLD_DAYS_KEY);
    assertEquals(30, emailConnectorService.getEmailBoxActivityThresholdDays());
  }

  /**
   * The activity threshold is an administrator's to set, from a day to a year.
   */
  @Test
  @SneakyThrows
  void saveEmailBoxActivityThresholdDays() {
    assertThrows(IllegalAccessException.class, () -> emailConnectorService.saveEmailBoxActivityThresholdDays(30, TEST_USER));
    grantAdministration();
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.saveEmailBoxActivityThresholdDays(0, TEST_USER));
    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.saveEmailBoxActivityThresholdDays(366, TEST_USER));
    emailConnectorService.saveEmailBoxActivityThresholdDays(30, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.EMAIL_BOX_ACTIVITY_THRESHOLD_DAYS_KEY),
                               argThat((SettingValue<?> value) -> "30".equals(value.getValue())));
  }

  /**
   * Makes the test user an administrator, which is what every save above asks of
   * the ACL before it looks at the value.
   */
  private void grantAdministration() {
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
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

  /**
   * EXO-90551 -- the copy into a shared mailbox owner's Sent is on unless switched off:
   * unset reads true, a stored false wins, and only an administrator writes it.
   */
  @Test
  void theSharedMailboxSentCopySwitch() throws Exception {
    when(settingService.get(Context.GLOBAL,
                            EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                            EmailConnectorService.SHARED_MAILBOX_SENT_COPY_ENABLED_KEY)).thenReturn(null);
    assertEquals(true, emailConnectorService.isSharedMailboxSentCopyEnabled(), "on by default");
    doReturn(SettingValue.create("false")).when(settingService)
                                          .get(Context.GLOBAL,
                                               EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                               EmailConnectorService.SHARED_MAILBOX_SENT_COPY_ENABLED_KEY);
    assertEquals(false, emailConnectorService.isSharedMailboxSentCopyEnabled(), "an administrator's off wins");

    assertThrows(IllegalAccessException.class, () -> emailConnectorService.saveSharedMailboxSentCopyEnabled(false, TEST_USER));
    verify(settingService, never()).set(eq(Context.GLOBAL),
                                        eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                                        eq(EmailConnectorService.SHARED_MAILBOX_SENT_COPY_ENABLED_KEY),
                                        any());
    Identity identity = mock(Identity.class);
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(identity);
    when(userAcl.isAdministrator(identity)).thenReturn(true);
    emailConnectorService.saveSharedMailboxSentCopyEnabled(false, TEST_USER);
    verify(settingService).set(eq(Context.GLOBAL),
                               eq(EmailConnectorService.EMAIL_CONNECTOR_SCOPE),
                               eq(EmailConnectorService.SHARED_MAILBOX_SENT_COPY_ENABLED_KEY),
                               argThat(value -> "false".equals(String.valueOf(value.getValue()))));
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
                              "", null, null, null);
  }

  /**
   * The configuration can only be written once the connector has an id - it is part of
   * the setting key - so it is stored after the create, against the id the storage just
   * attributed, never against the null one the drawer posted.
   */
  @Test
  @SneakyThrows
  void createStoresTheProviderConfigurationUnderTheNewConnectorId() {
    grantAdministration();
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(mock(ApplicationList.class));
    EmailConnector posted = emailConnector();
    posted.setAuthProviderName("bluemind-sudo");
    posted.setProviderConfig(Map.of("technicalLogin", "svc", "technicalSecret", "s3cret"));
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.createEmailConnector(posted)).thenReturn(stored);

    emailConnectorService.createEmailConnector(posted, TEST_USER);

    verify(providerConfigStorage).store(argThat(context -> context.getConnectorId() == 7L
        && "bluemind-sudo".equals(context.getConnectorCredentialsProviderName())
        && "email".equals(context.getConnectorKind())),
                                        eq(Map.of("technicalLogin", "svc", "technicalSecret", "s3cret")));
  }

  /** The same write on the update path, against the id the drawer already knows. */
  @Test
  @SneakyThrows
  void updateStoresTheProviderConfiguration() {
    grantAdministration();
    EmailConnector posted = emailConnector();
    posted.setId(7L);
    posted.setAuthProviderName("bluemind-sudo");
    posted.setProviderConfig(Map.of("technicalLogin", "svc", "technicalSecret", "s3cret"));
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(posted);

    emailConnectorService.updateEmailConnector(posted, TEST_USER);

    verify(providerConfigStorage).store(argThat(context -> context.getConnectorId() == 7L
        && "bluemind-sudo".equals(context.getConnectorCredentialsProviderName())),
                                        eq(Map.of("technicalLogin", "svc", "technicalSecret", "s3cret")));
  }

  /**
   * Switching a connector back to the personal provider leaves the technical account of
   * the previous one stored under its own key: invisible in every screen, yet a login
   * and a secret still in the database, and they would come silently back into use the
   * day someone selects that provider again. So the configuration of the provider being
   * left is removed.
   */
  @Test
  @SneakyThrows
  void updateRemovesTheConfigurationOfTheProviderBeingLeft() {
    grantAdministration();
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(stored);
    EmailConnector posted = emailConnector();
    posted.setId(7L);
    posted.setAuthProviderName("personal");

    emailConnectorService.updateEmailConnector(posted, TEST_USER);

    verify(providerConfigStorage).delete(argThat(context -> context.getConnectorId() == 7L
        && "bluemind-sudo".equals(context.getConnectorCredentialsProviderName())));
  }

  /** The provider unchanged, nothing is removed - the update is not a reset. */
  @Test
  @SneakyThrows
  void updateKeepsTheConfigurationWhenTheProviderIsUnchanged() {
    grantAdministration();
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(stored);
    EmailConnector posted = emailConnector();
    posted.setId(7L);
    posted.setAuthProviderName("bluemind-sudo");

    emailConnectorService.updateEmailConnector(posted, TEST_USER);

    verify(providerConfigStorage, never()).delete(any());
  }

  /**
   * Deleting the connector takes its provider configuration with it. A technical secret
   * outliving the connector it authenticated is a credential with no owner and no screen.
   */
  @Test
  @SneakyThrows
  void deleteRemovesTheProviderConfiguration() {
    grantAdministration();
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(mock(ApplicationList.class));
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(stored);

    emailConnectorService.deleteEmailConnector(7L, TEST_USER);

    verify(providerConfigStorage).delete(argThat(context -> context.getConnectorId() == 7L
        && "bluemind-sudo".equals(context.getConnectorCredentialsProviderName())));
  }

  /**
   * A save that carries no configuration - an edit of the connector's own fields, a
   * provider that asks for nothing - writes nothing. Taking an absent map for an empty
   * one would erase a working technical account on every unrelated edit.
   */
  @Test
  @SneakyThrows
  void aSaveWithoutConfigurationWritesNothing() {
    grantAdministration();
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(mock(ApplicationList.class));
    EmailConnector posted = emailConnector();
    posted.setAuthProviderName("personal");
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    when(emailConnectorStorage.createEmailConnector(posted)).thenReturn(stored);

    emailConnectorService.createEmailConnector(posted, TEST_USER);

    verify(providerConfigStorage, never()).store(any(), any());
  }

  /**
   * The storage refuses a configuration its descriptor does not admit. Its message is a
   * code the drawer displays, so it must reach REST as an IllegalArgumentException -
   * which the controller already answers 400 with the code as the body.
   */
  @Test
  @SneakyThrows
  void aRefusedConfigurationBecomesABadRequestCarryingTheCode() {
    grantAdministration();
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(mock(ApplicationList.class));
    EmailConnector posted = emailConnector();
    posted.setAuthProviderName("bluemind-sudo");
    posted.setProviderConfig(Map.of("technicalLogin", "svc"));
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.createEmailConnector(posted)).thenReturn(stored);
    doThrow(new ConnectorCredentialsException("connector.credentials.missingConfigurationField")).when(providerConfigStorage)
                                                                                                 .store(any(), any());

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                                                    () -> emailConnectorService.createEmailConnector(posted, TEST_USER));

    assertEquals("connector.credentials.missingConfigurationField", thrown.getMessage());
  }

  /**
   * What the drawer reads back to repopulate its fields: everything but the secret, and
   * on a path that never decrypts one. A value the screen cannot receive is a value that
   * cannot leak through it.
   */
  @Test
  @SneakyThrows
  void providerConfigIsReadWithoutTheSecret() {
    grantAdministration();
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(stored);
    when(providerConfigStorage.readWithoutSecrets(argThat(context -> context.getConnectorId() == 7L
        && "bluemind-sudo".equals(context.getConnectorCredentialsProviderName()))))
                                                                                   .thenReturn(Map.of("technicalLogin",
                                                                                                      "svc",
                                                                                                      "targetLoginField",
                                                                                                      "email"));

    assertEquals(Map.of("technicalLogin", "svc", "targetLoginField", "email"),
                 emailConnectorService.getProviderConfig(7L, TEST_USER));
    verify(providerConfigStorage, never()).readDecrypted(any());
  }

  /** Reading a technical account is an administration act, ACL-checked like the writes. */
  @Test
  void providerConfigIsRefusedToANonAdministrator() {
    when(userAcl.getUserIdentity(TEST_USER)).thenReturn(mock(Identity.class));

    assertThrows(IllegalAccessException.class, () -> emailConnectorService.getProviderConfig(7L, TEST_USER));
  }

  /**
   * The configuration goes first, the connector second - and this order is the whole
   * guarantee, because the two writes do not share a transaction: the connector is
   * removed under Spring's @Transactional while the settings are written under the
   * kernel's own RequestLifeCycle, so a rollback on one does not replay the other. Taken
   * in this order a failure leaves the connector intact, which an administrator sees and
   * can retry; taken the other way it leaves a technical secret behind with no connector
   * and no screen to reach it from.
   */
  @Test
  @SneakyThrows
  void theProviderConfigurationIsRemovedBeforeTheConnectorItself() {
    grantAdministration();
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(mock(ApplicationList.class));
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(stored);

    emailConnectorService.deleteEmailConnector(7L, TEST_USER);

    InOrder order = inOrder(providerConfigStorage, emailConnectorStorage);
    order.verify(providerConfigStorage).delete(any());
    order.verify(emailConnectorStorage).deleteEmailConnector(7L);
  }

  /**
   * The configuration is checked before the connector is inserted, so a refused value
   * leaves nothing behind. Otherwise the administrator gets a 400 on a connector that
   * was in fact created, and answers it by creating a second one.
   */
  @Test
  @SneakyThrows
  void aRefusedConfigurationCreatesNoConnectorAtAll() {
    grantAdministration();
    EmailConnector posted = emailConnector();
    posted.setAuthProviderName("bluemind-sudo");
    posted.setProviderConfig(Map.of("technicalLogin", "svc"));
    doThrow(new ConnectorCredentialsException("connector.credentials.missingConfigurationField")).when(providerConfigStorage)
                                                                                                 .validate(any(), any());

    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                                                    () -> emailConnectorService.createEmailConnector(posted, TEST_USER));

    assertEquals("connector.credentials.missingConfigurationField", thrown.getMessage());
    verify(emailConnectorStorage, never()).createEmailConnector(any());
    verify(providerConfigStorage, never()).store(any(), any());
  }

  /** And the accepted case validates first, inserts second, writes third. */
  @Test
  @SneakyThrows
  void createValidatesBeforeInserting() {
    grantAdministration();
    when(applicationCenterService.getApplications(0, 0, null)).thenReturn(mock(ApplicationList.class));
    EmailConnector posted = emailConnector();
    posted.setAuthProviderName("bluemind-sudo");
    posted.setProviderConfig(Map.of("technicalLogin", "svc", "technicalSecret", "s3cret"));
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("bluemind-sudo");
    when(emailConnectorStorage.createEmailConnector(posted)).thenReturn(stored);

    emailConnectorService.createEmailConnector(posted, TEST_USER);

    InOrder order = inOrder(providerConfigStorage, emailConnectorStorage);
    order.verify(providerConfigStorage).validate(any(), any());
    order.verify(emailConnectorStorage).createEmailConnector(posted);
    order.verify(providerConfigStorage).store(any(), any());
  }

  /**
   * Same guarantee on the update path as on the create one: a refused configuration
   * leaves the connector untouched. Written first and validated after, the connector
   * kept a provider whose configuration was never stored.
   */
  @Test
  @SneakyThrows
  void aRefusedConfigurationLeavesTheConnectorUntouchedOnUpdate() {
    grantAdministration();
    EmailConnector stored = emailConnector();
    stored.setId(7L);
    stored.setAuthProviderName("personal");
    when(emailConnectorStorage.getEmailConnector(7L)).thenReturn(stored);
    EmailConnector posted = emailConnector();
    posted.setId(7L);
    posted.setAuthProviderName("bluemind-sudo");
    posted.setProviderConfig(Map.of("technicalLogin", "svc"));
    doThrow(new ConnectorCredentialsException("connector.credentials.missingConfigurationField")).when(providerConfigStorage)
                                                                                                 .validate(any(), any());

    assertThrows(IllegalArgumentException.class, () -> emailConnectorService.updateEmailConnector(posted, TEST_USER));

    verify(emailConnectorStorage, never()).updateEmailConnector(any());
    verify(providerConfigStorage, never()).store(any(), any());
    verify(providerConfigStorage, never()).delete(any());
  }

  /**
   * What a browser needs to decide whether its connect button shows a form or
   * connects outright: one answer per provider the declared connectors name.
   * <p>
   * Only the declared providers, never the registry of providers: a user is
   * entitled to know about the connectors offered to them, not about how the
   * instance is configured - that endpoint is administrators-only.
   */
  @Test
  @SneakyThrows
  public void tellsWhichDeclaredProvidersAskTheUserForSomething() {
    when(emailConnectorStorage.getEmailConnectors()).thenReturn(List.of(connectorWithProvider(1L, "personal"),
                                                                        connectorWithProvider(2L, "bluemind-sudo")));
    when(emailCredentialsResolver.requiresUserAction("personal")).thenReturn(true);
    when(emailCredentialsResolver.requiresUserAction("bluemind-sudo")).thenReturn(false);

    Map<String, Boolean> requirements = emailConnectorService.connectionRequirements();

    assertEquals(Boolean.TRUE, requirements.get("personal"));
    assertEquals(Boolean.FALSE, requirements.get("bluemind-sudo"));
  }

  /**
   * A connector naming no provider contributes nothing, and a seam that is absent
   * answers true: a list that cannot tell must send the user to the form, never
   * connect them silently.
   */
  @Test
  @SneakyThrows
  public void asksTheUserWhenNothingCanSayOtherwise() {
    when(emailConnectorStorage.getEmailConnectors()).thenReturn(List.of(connectorWithProvider(3L, null),
                                                                        connectorWithProvider(2L, "bluemind-sudo")));
    // Le contexte Spring est partage par toute la classe : un champ mis a null ici
    // le resterait pour les tests suivants, qui verraient une couture absente sans
    // l'avoir demande. D'ou la restauration, quoi qu'il arrive.
    ReflectionTestUtils.setField(emailConnectorService, "emailCredentialsResolver", null);
    try {
      Map<String, Boolean> requirements = emailConnectorService.connectionRequirements();

      assertFalse(requirements.containsKey(null));
      assertEquals(Boolean.TRUE, requirements.get("bluemind-sudo"));
    } finally {
      ReflectionTestUtils.setField(emailConnectorService, "emailCredentialsResolver", emailCredentialsResolver);
    }
  }

  private EmailConnector connectorWithProvider(long id, String providerName) {
    EmailConnector connector = new EmailConnector();
    connector.setId(id);
    connector.setAuthProviderName(providerName);
    return connector;
  }
}