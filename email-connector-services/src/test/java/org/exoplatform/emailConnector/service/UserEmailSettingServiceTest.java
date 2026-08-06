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
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.ApplicationEventPublisher;

import org.exoplatform.commons.api.settings.ExoFeatureService;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ContactSyncState;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.web.security.codec.AbstractCodec;
import org.exoplatform.web.security.codec.CodecInitializer;
import org.exoplatform.web.security.security.TokenServiceInitializationException;

import io.meeds.social.translation.service.TranslationService;
import lombok.SneakyThrows;

@SpringBootTest(classes = { UserEmailSettingService.class })
@ExtendWith(MockitoExtension.class)
public class UserEmailSettingServiceTest {

  private static final String       TEST_USER = "testuser";

  @MockBean
  private SettingService            settingService;

  @MockBean
  private CodecInitializer          codecInitializer;

  @MockBean
  private TranslationService        translationService;

  @MockBean
  private ExoFeatureService         featureService;

  @MockBean
  private EmailConnectorService     emailConnectorService;

  @MockBean
  private ApplicationEventPublisher eventPublisher;

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
  void theAddressBookPasswordIsEncodedLikeTheMailOne() {
    // It is a password in a settings store: whatever protects the mail one has to
    // protect this one, by the same codec, or the weaker of the two sets the bar.
    AbstractCodec codec = mock(AbstractCodec.class);
    when(codecInitializer.getCodec()).thenReturn(codec);
    // The mail password goes through the same codec on this path, so the stub has to
    // answer for both rather than only the one under test.
    when(codec.encode(anyString())).thenAnswer(invocation -> "carddav-secret".equals(invocation.getArgument(0)) ? "ENCODED"
                                                                                                               : "OTHER");
    UserEmailSetting userEmailSetting = userEmailSetting();
    userEmailSetting.setCarddavEnabled(true);
    userEmailSetting.setCarddavPassword("carddav-secret");

    userEmailSettingService.setUserEmailSetting(userEmailSetting, TEST_USER, false);

    ArgumentCaptor<SettingValue> stored = ArgumentCaptor.forClass(SettingValue.class);
    verify(settingService).set(any(Context.class), any(Scope.class), anyString(), stored.capture());
    String json = stored.getValue().getValue().toString();
    assertTrue("the encoded form is what reaches the store", json.contains("ENCODED"));
    assertFalse("and the clear one never does", json.contains("carddav-secret"));
  }

  @Test
  @SneakyThrows
  void aUserWithNoAddressBookPasswordIsNotAnError() {
    // The regression this exists for: reading the settings of any user who never
    // bound an address book handed a null to the codec and failed the whole read.
    // Mocked codecs hid it -- only a real one throws on null.
    // The codec is deliberately NOT stubbed: if this path still reached it, the
    // unstubbed mock would answer null and the call would fail exactly as it did in
    // production. Passing means the codec was never asked.
    UserEmailSetting userEmailSetting = userEmailSetting();
    userEmailSetting.setEmailPassword(null);
    userEmailSetting.setCarddavPassword(null);

    userEmailSettingService.setUserEmailSetting(userEmailSetting, TEST_USER, false);

    verify(settingService).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
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
  void deleteUserEmailSetting() {
    userEmailSettingService.deleteUserEmailSetting(TEST_USER);
    verify(settingService).remove(any(Context.class), any(Scope.class), anyString());
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
