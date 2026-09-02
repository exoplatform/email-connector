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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Store;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.entity.UserEmailSettingEntity;
import org.exoplatform.emailConnector.event.ContactBookReleaseEvent;
import org.exoplatform.emailConnector.event.EmailNotificationPreferencesChangedEvent;
import org.exoplatform.emailConnector.model.ContactPublishQueue;
import org.exoplatform.emailConnector.model.ContactPublishQueueEntry;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.web.security.codec.AbstractCodec;
import org.exoplatform.web.security.codec.CodecInitializer;
import org.exoplatform.web.security.security.TokenServiceInitializationException;

import io.meeds.social.translation.service.TranslationService;
import io.meeds.social.util.JsonUtils;
import lombok.SneakyThrows;

@SpringBootTest(classes = { UserEmailSettingService.class })
@ExtendWith(MockitoExtension.class)
public class UserEmailSettingServiceTest {

  private static final String       TEST_USER = "testuser";

  @MockitoBean
  private SettingService            settingService;

  @MockitoBean
  private CodecInitializer          codecInitializer;

  @MockitoBean
  private TranslationService        translationService;

  @MockitoBean
  private ExoFeatureService         featureService;

  @MockitoBean
  private EmailConnectorService     emailConnectorService;

  @MockitoBean
  private ApplicationEventPublisher eventPublisher;

  @MockitoBean
  private EmailSignatureService     emailSignatureService;

  @Autowired
  private UserEmailSettingService   userEmailSettingService;

  @Test
  @SneakyThrows
  void connectUserEmailSetting() {
    when(featureService.isActiveFeature(EmailConnectorUtils.EMAIL_FEATURE)).thenReturn(false);
    UserEmailSetting userEmailSetting = userEmailSetting();
    assertThrows(IllegalAccessException.class,
                 () -> userEmailSettingService.connectUserEmailSetting(userEmailSetting, TEST_USER, false));
    when(featureService.isActiveFeature(EmailConnectorUtils.EMAIL_FEATURE)).thenReturn(true);
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector);
    Session session = mock(Session.class);
    try (MockedStatic<Session> mockedSession = mockStatic(Session.class)) {
      mockedSession.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);
      Store store = mock(Store.class);
      when(session.getStore()).thenReturn(store);
      when(store.isConnected()).thenReturn(true);
      when(codecInitializer.getCodec()).thenReturn(mock(AbstractCodec.class));
      userEmailSettingService.connectUserEmailSetting(userEmailSetting, TEST_USER, false);
      verify(store).connect(anyString(), anyInt(), anyString(), anyString());
      verify(settingService).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
    }
  }

  @Test
  @SneakyThrows
  void setUserEmailSetting() {
    when(codecInitializer.getCodec()).thenReturn(mock(AbstractCodec.class));
    UserEmailSetting userEmailSetting = userEmailSetting();
    userEmailSettingService.setUserEmailSetting(userEmailSetting, TEST_USER, false);
    verify(settingService).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
  }

  /**
   * Pins the fix for the per-user sync period injection: nothing in the platform
   * rejects a client-supplied {@code emailBoxUserSyncPeriod}, and
   * {@code EmailConnectorUtils#getEmailBoxUserSyncPeriod} prefers a stored
   * per-user value over the administration-wide one — so a crafted
   * {@code PUT /user-email-setting} could otherwise schedule a 1-minute sync for
   * that user, undercutting whatever floor an administrator set. The field must
   * never survive into what gets persisted, whatever the caller sent.
   */
  @Test
  @SneakyThrows
  void aClientSuppliedSyncPeriodIsNeverPersisted() {
    when(codecInitializer.getCodec()).thenReturn(mock(AbstractCodec.class));
    UserEmailSetting userEmailSetting = userEmailSetting();
    userEmailSetting.setEmailBoxUserSyncPeriod(1);

    userEmailSettingService.setUserEmailSetting(userEmailSetting, TEST_USER, false);

    ArgumentCaptor<SettingValue> stored = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class), any(Scope.class), anyString(), stored.capture());
    UserEmailSettingEntity persisted = JsonUtils.fromJsonString(stored.getValue().getValue().toString(), UserEmailSettingEntity.class);
    assertNull(persisted.getEmailBoxUserSyncPeriod(),
               "a client-supplied sync period must never reach persistence — it would undercut the admin-set floor");
  }

  @Test
  void getUserEmailSetting() throws TokenServiceInitializationException {
    SettingValue userEmailSettingValue = mock(SettingValue.class);
    when(settingService.get(any(Context.class), any(Scope.class), anyString())).thenReturn(userEmailSettingValue);
    String userEmailSettingJsonObject =
                                      "{\"emailConnectorId\":\"1\",\"emailConnectorImageUrl\":null,\"emailConnectorIcon\":null,\"emailAddress\":\"testEmail\",\"emailPassword\":\"testPassword\"}";
    when(userEmailSettingValue.getValue()).thenReturn(userEmailSettingJsonObject);
    EmailConnector emailConnector = emailConnector();
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector);
    when(codecInitializer.getCodec()).thenReturn(mock(AbstractCodec.class));
    userEmailSettingService.getUserEmailSetting(TEST_USER);
    verify(settingService).get(any(Context.class), any(Scope.class), anyString());
    verify(emailConnectorService).getEmailConnector(1L);
  }

  @Test
  @SneakyThrows
  void aPasswordThatIsNotSetNeverReachesTheCodec() {
    // A password that is not set must not reach the codec: it answered a null and
    // the whole settings read failed. The codec is deliberately NOT stubbed here,
    // so a path that still reaches it fails on the null mock exactly as production
    // did.
    UserEmailSetting userEmailSetting = userEmailSetting();
    userEmailSetting.setEmailPassword(null);

    userEmailSettingService.setUserEmailSetting(userEmailSetting, TEST_USER, false);

    verify(settingService).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
  }

  /**
   * The automatic-push preference rides the settings document, and its writer
   * must not do anything ELSE. Turning the binding on or off releases the
   * contacts of the book being left; a preference about future saves has no
   * business setting that in motion, which is why it has its own method and
   * its own endpoint rather than a second field on the binding.
   */
  @Test
  @SneakyThrows
  void theAutoPublishPreferenceIsStoredWithoutReleasingAnything() {
    // The publisher mock is pinned in by hand: for ApplicationEventPublisher the
    // context registers ITSELF as a resolvable dependency and can win the
    // @Autowired resolution, which would make the "never published" assertion
    // below pass without ever having watched the real one.
    ReflectionTestUtils.setField(userEmailSettingService, "eventPublisher", eventPublisher);
    SettingValue storedSetting = mock(SettingValue.class);
    when(settingService.get(any(Context.class), any(Scope.class), eq(UserEmailSettingService.USER_EMAIL_SETTING_KEY)))
                       .thenReturn(storedSetting);
    when(storedSetting.getValue()).thenReturn("{\"emailConnectorId\":\"1\",\"emailAddress\":\"testEmail\",\"carddavEnabled\":true}");
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector());
    when(codecInitializer.getCodec()).thenReturn(mock(AbstractCodec.class));

    userEmailSettingService.updateAddressBookAutoPublish(TEST_USER, true);

    ArgumentCaptor<SettingValue> written = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class),
                               any(Scope.class),
                               eq(UserEmailSettingService.USER_EMAIL_SETTING_KEY),
                               written.capture());
    assertTrue(written.getValue().getValue().toString().contains("\"carddavAutoPublish\":true"));
    // The binding itself is untouched, so nothing lets go of any contacts.
    assertTrue(written.getValue().getValue().toString().contains("\"carddavEnabled\":true"));
    verify(eventPublisher, never()).publishEvent(any(ContactBookReleaseEvent.class));
  }

  /**
   * The badge counts by the notification preference, so saving it has to reach
   * the badge — through an event, since this service cannot call the mail
   * service without closing a bean cycle. The stored preference is "all"; the
   * user narrows it to two categories, and the badge is told.
   */
  @Test
  void savingTheNotificationPreferenceTellsTheBadge() {
    ReflectionTestUtils.setField(userEmailSettingService, "eventPublisher", eventPublisher);
    storedSetting("{\"emailConnectorId\":\"1\",\"emailAddress\":\"testEmail\"}");

    userEmailSettingService.updateEmailPreferences(TEST_USER, Boolean.FALSE, List.of(1L, 2L), null);

    verify(settingService).set(any(Context.class),
                               any(Scope.class),
                               eq(UserEmailSettingService.USER_EMAIL_SETTING_KEY),
                               any(SettingValue.class));
    verify(eventPublisher).publishEvent(any(EmailNotificationPreferencesChangedEvent.class));
  }

  /**
   * The default-view toggle shares the same write and does not move the count. A
   * save that changed only that — the notification half exactly as stored — is
   * written, and announces nothing: an unchanged preference must not cost the
   * badge an eviction, a frame and a re-fetch.
   */
  @Test
  void savingOnlyTheDefaultViewLeavesTheBadgeAlone() {
    ReflectionTestUtils.setField(userEmailSettingService, "eventPublisher", eventPublisher);
    storedSetting("{\"emailConnectorId\":\"1\",\"emailAddress\":\"testEmail\",\"notifyAllCategories\":false,\"notifyCategories\":[1,2]}");

    userEmailSettingService.updateEmailPreferences(TEST_USER, Boolean.FALSE, List.of(1L, 2L), 5L);

    verify(settingService).set(any(Context.class),
                               any(Scope.class),
                               eq(UserEmailSettingService.USER_EMAIL_SETTING_KEY),
                               any(SettingValue.class));
    verify(eventPublisher, never()).publishEvent(any(EmailNotificationPreferencesChangedEvent.class));
  }

  /**
   * The same selection spelled differently — the ids in another order, as a client
   * that rebuilds its chips will post them — is not a change. An event here would
   * cost the badge an eviction for a preference that reads exactly as before.
   */
  @Test
  void savingTheSameSelectionInAnotherOrderLeavesTheBadgeAlone() {
    ReflectionTestUtils.setField(userEmailSettingService, "eventPublisher", eventPublisher);
    storedSetting("{\"emailConnectorId\":\"1\",\"emailAddress\":\"testEmail\",\"notifyAllCategories\":false,\"notifyCategories\":[1,2]}");

    userEmailSettingService.updateEmailPreferences(TEST_USER, Boolean.FALSE, List.of(2L, 1L), null);

    verify(eventPublisher, never()).publishEvent(any(EmailNotificationPreferencesChangedEvent.class));
  }

  /**
   * And the ids are irrelevant while the switch is on: a user notified for
   * everything who posts a different (unused) selection has changed nothing the
   * badge reads.
   */
  @Test
  void savingUnusedCategoryIdsUnderNotifyAllLeavesTheBadgeAlone() {
    ReflectionTestUtils.setField(userEmailSettingService, "eventPublisher", eventPublisher);
    storedSetting("{\"emailConnectorId\":\"1\",\"emailAddress\":\"testEmail\",\"notifyAllCategories\":true,\"notifyCategories\":[1]}");

    userEmailSettingService.updateEmailPreferences(TEST_USER, Boolean.TRUE, List.of(1L, 2L, 3L), null);

    verify(eventPublisher, never()).publishEvent(any(EmailNotificationPreferencesChangedEvent.class));
  }

  /**
   * A first save of "notify me for all" over a setting that never stored the switch
   * is not a change: the badge reads null and true as the same "all", and the
   * detector reads them the same way.
   */
  @Test
  void aFirstSaveOfNotifyAllLeavesTheBadgeAlone() {
    ReflectionTestUtils.setField(userEmailSettingService, "eventPublisher", eventPublisher);
    storedSetting("{\"emailConnectorId\":\"1\",\"emailAddress\":\"testEmail\"}");

    userEmailSettingService.updateEmailPreferences(TEST_USER, Boolean.TRUE, List.of(), null);

    verify(eventPublisher, never()).publishEvent(any(EmailNotificationPreferencesChangedEvent.class));
  }

  /**
   * A null id inside the posted selection — reachable by any direct caller of the
   * endpoint, if not by the client — is ignored, not a failed save: the rule that
   * reads the selection has always tolerated one. The real change beside it still
   * tells the badge.
   */
  @Test
  void aNullIdInTheSelectionIsIgnoredNotRefused() {
    ReflectionTestUtils.setField(userEmailSettingService, "eventPublisher", eventPublisher);
    storedSetting("{\"emailConnectorId\":\"1\",\"emailAddress\":\"testEmail\",\"notifyAllCategories\":false,\"notifyCategories\":[1]}");

    userEmailSettingService.updateEmailPreferences(TEST_USER, Boolean.FALSE, Arrays.asList(2L, null), null);

    verify(settingService).set(any(Context.class),
                               any(Scope.class),
                               eq(UserEmailSettingService.USER_EMAIL_SETTING_KEY),
                               any(SettingValue.class));
    verify(eventPublisher).publishEvent(any(EmailNotificationPreferencesChangedEvent.class));
  }

  /**
   * A stored settings document for the test user, with the connector it names
   * resolving, so the read comes back as a connected mailbox.
   *
   * @param json the stored document
   */
  @SneakyThrows
  private void storedSetting(String json) {
    SettingValue storedSetting = mock(SettingValue.class);
    when(settingService.get(any(Context.class), any(Scope.class), eq(UserEmailSettingService.USER_EMAIL_SETTING_KEY)))
                       .thenReturn(storedSetting);
    when(storedSetting.getValue()).thenReturn(json);
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector());
    when(codecInitializer.getCodec()).thenReturn(mock(AbstractCodec.class));
  }

  /**
   * With no connected mailbox there is no settings document to write into, and
   * inventing one would store a preference about an account that does not
   * exist.
   */
  @Test
  void theAutoPublishPreferenceIsNotStoredWithoutAMailbox() {
    when(settingService.get(any(Context.class), any(Scope.class), eq(UserEmailSettingService.USER_EMAIL_SETTING_KEY)))
                       .thenReturn(null);

    userEmailSettingService.updateAddressBookAutoPublish(TEST_USER, true);

    verify(settingService, never()).set(any(Context.class),
                                        any(Scope.class),
                                        eq(UserEmailSettingService.USER_EMAIL_SETTING_KEY),
                                        any(SettingValue.class));
  }

  @Test
  void theSyncStateLivesUnderItsOwnKey() {
    // Not inside userEmailSetting: the mailbox sync rewrites that whole document on
    // every status update, so sharing the key would have the two syncs overwriting
    // each other's fields.
    ContactSyncState state = new ContactSyncState();
    state.setCtag("ctag-1");
    state.setStatus(SyncStatus.SUCCESS);

    userEmailSettingService.setContactSyncState(state, TEST_USER);

    verify(settingService).set(any(Context.class),
                               any(Scope.class),
                               eq(UserEmailSettingService.CONTACT_SYNC_STATE_KEY),
                               any(SettingValue.class));
  }

  @Test
  void anAbsentSyncStateReadsAsNeverHavingRun() {
    when(settingService.get(any(Context.class), any(Scope.class), eq(UserEmailSettingService.CONTACT_SYNC_STATE_KEY))).thenReturn(null);

    ContactSyncState state = userEmailSettingService.getContactSyncState(TEST_USER);

    assertNotNull(state);
    assertNull(state.getCtag());
  }

  @Test
  void thePublishQueueSurvivesItsTripThroughSettingsJson() {
    // The queue's whole persistence is one JSON string in settings; this pins
    // that a parked entry comes back exactly as it went in -- id, attempts,
    // parked flag AND the reason, which is the part "never silent" rides on.
    ContactPublishQueue queue = new ContactPublishQueue();
    queue.getEntries().add(new ContactPublishQueueEntry(42L, 1721000000000L, 2, true, "server said no", "boom", 1721000060000L));
    queue.getEntries().add(new ContactPublishQueueEntry(43L, 1721000001000L, 0, false, null, null, null));

    userEmailSettingService.setContactPublishQueue(queue, TEST_USER);

    ArgumentCaptor<SettingValue> stored = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class),
                               any(Scope.class),
                               eq(UserEmailSettingService.CONTACT_PUBLISH_QUEUE_KEY),
                               stored.capture());
    when(settingService.get(any(Context.class), any(Scope.class), eq(UserEmailSettingService.CONTACT_PUBLISH_QUEUE_KEY)))
                       .thenReturn((SettingValue) SettingValue.create(stored.getValue().getValue().toString()));

    ContactPublishQueue reread = userEmailSettingService.getContactPublishQueue(TEST_USER);

    assertEquals(2, reread.getEntries().size());
    ContactPublishQueueEntry parked = reread.getEntries().get(0);
    assertEquals(42L, parked.getContactId());
    assertEquals(2, parked.getAttempts());
    assertTrue(parked.isParked());
    assertEquals("server said no", parked.getParkedReason());
    assertEquals("boom", parked.getLastError());
    ContactPublishQueueEntry pending = reread.getEntries().get(1);
    assertEquals(43L, pending.getContactId());
    assertFalse(pending.isParked());
    assertNull(pending.getParkedReason());
  }

  @Test
  void anAbsentPublishQueueReadsAsEmptyNeverNull() {
    when(settingService.get(any(Context.class), any(Scope.class), eq(UserEmailSettingService.CONTACT_PUBLISH_QUEUE_KEY)))
                       .thenReturn(null);

    ContactPublishQueue queue = userEmailSettingService.getContactPublishQueue(TEST_USER);

    assertNotNull(queue);
    assertNotNull(queue.getEntries());
    assertTrue(queue.getEntries().isEmpty());
  }

  @Test
  void anUnreadableStoredQueueReadsAsEmptyRatherThanFailing() {
    // Losing the stored document loses reminders, never contacts -- the
    // contacts are rows of the store. Failing the read would lose the drain too.
    SettingValue<?> broken = mock(SettingValue.class);
    when(settingService.get(any(Context.class), any(Scope.class), eq(UserEmailSettingService.CONTACT_PUBLISH_QUEUE_KEY)))
                       .thenReturn((SettingValue) broken);
    when(broken.getValue()).thenReturn((Object) "this is not json");

    ContactPublishQueue queue = userEmailSettingService.getContactPublishQueue(TEST_USER);

    assertNotNull(queue);
    assertTrue(queue.getEntries().isEmpty());
  }

  @Test
  void deleteUserEmailSetting() {
    userEmailSettingService.deleteUserEmailSetting(TEST_USER);
    verify(settingService).remove(any(Context.class), any(Scope.class), anyString());
    // Disconnecting takes the signature along -- its own settings document and its
    // uploaded image file, which nothing else would ever clean up.
    verify(emailSignatureService).deleteEmailSignature(TEST_USER);
  }

  @Test
  void getUserEmailSettingsByEmailConnectorId() throws TokenServiceInitializationException {
    Context context1 = mock(Context.class);
    when(context1.getId()).thenReturn("user1");
    Context context2 = mock(Context.class);
    when(context2.getId()).thenReturn("user2");
    List<Context> contexts = List.of(context1, context2);
    when(settingService.getContextsByTypeAndScopeAndSettingName(anyString(),
                                                                anyString(),
                                                                anyString(),
                                                                anyString(),
                                                                anyInt(),
                                                                anyInt())).thenReturn(contexts);
    SettingValue userEmailSettingValue1 = mock(SettingValue.class);
    when(userEmailSettingValue1.getValue()).thenReturn("{\"emailConnectorId\":\"1\",\"emailAddress\":\"testEmail1\",\"emailPassword\":\"testPassword1\"}");
    SettingValue userEmailSettingValue2 = mock(SettingValue.class);
    when(userEmailSettingValue2.getValue()).thenReturn("{\"emailConnectorId\":\"2\",\"emailAddress\":\"testEmail2\",\"emailPassword\":\"testPassword2\"}");
    when(settingService.get(Context.USER.id("user1"),
                            UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                            UserEmailSettingService.USER_EMAIL_SETTING_KEY)).thenReturn(userEmailSettingValue1);
    when(settingService.get(Context.USER.id("user2"),
                            UserEmailSettingService.EMAIL_CONNECTOR_SCOPE,
                            UserEmailSettingService.USER_EMAIL_SETTING_KEY)).thenReturn(userEmailSettingValue2);
    when(codecInitializer.getCodec()).thenReturn(mock(AbstractCodec.class));
    List<String> users = userEmailSettingService.getUserEmailSettingsByEmailConnectorId(1L);
    assertEquals(1, users.size());

  }

  @Test
  void connect() throws MessagingException {
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector());
    Session session = mock(Session.class);
    try (MockedStatic<Session> mockedSession = mockStatic(Session.class)) {
      mockedSession.when(() -> Session.getInstance(any(Properties.class))).thenReturn(session);
      Store store = mock(Store.class);
      when(session.getStore()).thenReturn(store);
      userEmailSettingService.connect(userEmailSetting());
      verify(store).connect(anyString(), anyInt(), anyString(), anyString());
    }
  }

  @Test
  void canConnect() throws TokenServiceInitializationException {
    SettingValue userEmailSettingValue = mock(SettingValue.class);
    when(settingService.get(any(Context.class), any(Scope.class), anyString())).thenReturn(userEmailSettingValue);
    when(userEmailSettingValue.getValue()).thenReturn("{}");
    when(codecInitializer.getCodec()).thenReturn(mock(AbstractCodec.class));
    when(featureService.isActiveFeature(EmailConnectorUtils.EMAIL_FEATURE)).thenReturn(false);
    boolean canConnect = userEmailSettingService.canConnect(1L, TEST_USER);
    assertFalse(canConnect);
    when(featureService.isActiveFeature(EmailConnectorUtils.EMAIL_FEATURE)).thenReturn(true);
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(null);
    canConnect = userEmailSettingService.canConnect(1L, TEST_USER);
    assertFalse(canConnect);
    EmailConnector emailConnector1 = emailConnector();
    emailConnector1.setActive(false);
    when(emailConnectorService.getEmailConnector(1L)).thenReturn(emailConnector1);
    canConnect = userEmailSettingService.canConnect(1L, TEST_USER);
    assertFalse(canConnect);
    emailConnector1.setActive(true);
    canConnect = userEmailSettingService.canConnect(1L, TEST_USER);
    assertTrue(canConnect);
    when(userEmailSettingValue.getValue()).thenReturn("{\"emailConnectorId\":\"2\",\"emailAddress\":\"testEmail2\",\"emailPassword\":\"testPassword2\"}");
    EmailConnector emailConnector2 = emailConnector();
    when(emailConnectorService.getEmailConnector(2L)).thenReturn(emailConnector2);
    emailConnector2.setActive(false);
    canConnect = userEmailSettingService.canConnect(1L, TEST_USER);
    assertTrue(canConnect);
    emailConnector2.setActive(true);
    canConnect = userEmailSettingService.canConnect(1L, TEST_USER);
    assertFalse(canConnect);
    when(userEmailSettingValue.getValue()).thenReturn("{\"emailConnectorId\":\"1\",\"emailAddress\":\"testEmail1\",\"emailPassword\":\"testPassword1\"}");
    canConnect = userEmailSettingService.canConnect(1L, TEST_USER);
    assertTrue(canConnect);
  }

  @Test
  void getUserEmailConnectors() {
    Locale frLocale = mock(Locale.class);
    List<EmailConnector> list = List.of(mock(EmailConnector.class));
    when(emailConnectorService.getActiveEmailConnectors()).thenReturn(list);
    userEmailSettingService.getUserEmailConnectors(frLocale, TEST_USER);
    verify(translationService).getTranslationLabelOrDefault(anyString(), anyLong(), anyString(), any(Locale.class));
  }

  private EmailConnector emailConnector() {
    return new EmailConnector(null,
                              "testName",
                              null,
                              null,
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

  private UserEmailSetting userEmailSetting() {
    return new UserEmailSetting("1", "testEmail", "testPassword", null, null, 0, 0L, null, null, null, true);
  }
}
