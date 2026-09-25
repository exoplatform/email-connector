/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.model.AbsenceSettings;
import org.exoplatform.emailConnector.model.AbsenceStatus;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.ForwardingState;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerVacation;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.model.VacationSetting;
import org.exoplatform.emailConnector.model.VacationState;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngine;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngineRegistry;

import io.meeds.social.util.JsonUtils;

/**
 * The automatic reply's rules: own mailbox only, the checks before any server call, the
 * validation codes, the hash comparison, and the two user settings written only after the
 * server accepted a write.
 */
@ExtendWith(MockitoExtension.class)
public class EmailAbsenceServiceTest {

  private static final String      USERNAME     = "alice";

  private static final long        CONNECTOR_ID = 5L;

  private static final long        NOW          = 1_790_000_000_000L;

  @Mock
  private UserEmailSettingService  userEmailSettingService;

  @Mock
  private EmailConnectorService    emailConnectorService;

  @Mock
  private EmailDelegationService   emailDelegationService;

  @Mock
  private ServerRuleEngineRegistry serverRuleEngineRegistry;

  @Mock
  private SettingService           settingService;

  @Mock
  private ServerRuleEngine         engine;

  @InjectMocks
  private EmailAbsenceService      service;

  private final Map<String, String> settings = new HashMap<>();

  private MailboxAclSession        session;

  /**
   * A connected caller on a preset whose engine is the mock, and a setting store in a map.
   *
   * @throws Exception on failure
   */
  @BeforeEach
  public void setUp() throws Exception {
    service.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId(String.valueOf(CONNECTOR_ID));
    setting.setEmailAddress("alice@example.org");
    EmailConnector connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    session = new MailboxAclSession(connector, USERNAME, "alice@example.org", null, null, null);
    lenient().when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(setting);
    lenient().when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);
    lenient().when(userEmailSettingService.canConnect(CONNECTOR_ID, USERNAME)).thenReturn(true);
    lenient().when(serverRuleEngineRegistry.engineFor(connector)).thenReturn(engine);
    lenient().when(emailDelegationService.openOwnSession(USERNAME)).thenReturn(session);
    lenient().when(engine.getName()).thenReturn("sieve");
    lenient().when(settingService.get(any(Context.class), any(Scope.class), anyString())).thenAnswer(invocation -> {
      String value = settings.get(invocation.getArgument(2, String.class));
      return value == null ? null : SettingValue.create(value);
    });
    lenient().doAnswer(invocation -> {
      settings.put(invocation.getArgument(2, String.class), invocation.getArgument(3, SettingValue.class).getValue().toString());
      return null;
    }).when(settingService).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
  }

  /**
   * Clears the properties.
   */
  @AfterEach
  public void tearDown() {
    System.clearProperty(EmailAbsenceService.ENABLED_PROPERTY);
    System.clearProperty(EmailAbsenceService.DAYS_PROPERTY);
    System.clearProperty(EmailAbsenceService.TTL_PROPERTY);
    System.clearProperty(EmailAbsenceService.FORWARDING_DISPLAY_PROPERTY);
  }

  /**
   * A request made from someone else's mailbox is refused by every verb, before anything
   * is asked of anyone.
   */
  @Test
  public void testASharedMailboxIsRefusedEverywhere() {
    assertEquals(EmailAbsenceService.OWN_MAILBOX_ONLY,
                 assertThrows(IllegalAccessException.class, () -> service.getAbsence(USERNAME, 12L)).getMessage());
    assertEquals(EmailAbsenceService.OWN_MAILBOX_ONLY,
                 assertThrows(IllegalAccessException.class,
                              () -> service.setVacation(USERNAME, 12L, reply(true), false)).getMessage());
    assertEquals(EmailAbsenceService.OWN_MAILBOX_ONLY,
                 assertThrows(IllegalAccessException.class, () -> service.disableVacation(USERNAME, 12L)).getMessage());
    assertEquals(EmailAbsenceService.OWN_MAILBOX_ONLY,
                 assertThrows(IllegalAccessException.class, () -> service.getStatus(USERNAME, 12L)).getMessage());
    verifyNoInteractions(engine, emailDelegationService, settingService);
  }

  /**
   * The caller's own mailbox is served: the same request without a share goes through.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheOwnMailboxIsServed() throws Exception {
    when(engine.probe(session)).thenReturn(ServerRuleCapabilities.unsupported("x", ServerRuleCapabilities.VocabularySource.NONE));
    AbsenceSettings absence = service.getAbsence(USERNAME, null);
    assertEquals("sieve", absence.getEngine());
    assertEquals(VacationState.NONE, absence.getVacationState());
  }

  /**
   * The feature switched off answers "not found", whoever asks.
   */
  @Test
  public void testTheFeatureOffIsNotFound() {
    System.setProperty(EmailAbsenceService.ENABLED_PROPERTY, "false");
    assertThrows(ObjectNotFoundException.class, () -> service.getAbsence(USERNAME, null));
    assertThrows(ObjectNotFoundException.class, () -> service.getStatus(USERNAME, null));
    verifyNoInteractions(engine);
  }

  /**
   * No connected mailbox is "not found"; a connector the caller may not use is refused.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoMailboxOrNoRightToTheConnector() throws Exception {
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(new UserEmailSetting());
    assertThrows(ObjectNotFoundException.class, () -> service.getAbsence(USERNAME, null));
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId(String.valueOf(CONNECTOR_ID));
    setting.setEmailAddress("alice@example.org");
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(setting);
    when(userEmailSettingService.canConnect(CONNECTOR_ID, USERNAME)).thenReturn(false);
    assertEquals(EmailAbsenceService.NOT_ALLOWED,
                 assertThrows(IllegalAccessException.class, () -> service.getAbsence(USERNAME, null)).getMessage());
    verifyNoInteractions(engine);
  }

  /**
   * Every invalid value is refused with its code before the server is asked.
   */
  @Test
  public void testInvalidValuesAreRefusedBeforeTheServer() {
    VacationSetting twoLines = reply(true);
    twoLines.setSubject("Out\r\nBcc: x@evil.example");
    assertCode(EmailAbsenceService.INVALID_SUBJECT, twoLines);
    VacationSetting longSubject = reply(true);
    longSubject.setSubject("s".repeat(201));
    assertCode(EmailAbsenceService.INVALID_SUBJECT, longSubject);
    VacationSetting noText = reply(true);
    noText.setText(" ");
    assertCode(EmailAbsenceService.INVALID_TEXT, noText);
    VacationSetting longText = reply(true);
    longText.setText("t".repeat(4001));
    assertCode(EmailAbsenceService.INVALID_TEXT, longText);
    VacationSetting longOnceStored = reply(true);
    longOnceStored.setText("t\n".repeat(1500));
    assertCode(EmailAbsenceService.INVALID_TEXT, longOnceStored);
    VacationSetting backwards = reply(true);
    backwards.setStart("2026-10-15");
    backwards.setEnd("2026-10-01");
    assertCode(EmailAbsenceService.INVALID_WINDOW, backwards);
    VacationSetting notADay = reply(true);
    notADay.setStart("2026-13-01");
    assertCode(EmailAbsenceService.INVALID_WINDOW, notADay);
    VacationSetting noZone = reply(true);
    noZone.setTimeZone(null);
    assertCode(EmailAbsenceService.INVALID_TIME_ZONE, noZone);
    VacationSetting badZone = reply(true);
    badZone.setTimeZone("Mars/Olympus");
    assertCode(EmailAbsenceService.INVALID_TIME_ZONE, badZone);
    verifyNoInteractions(engine);
  }

  /**
   * A reply with neither day needs no zone.
   *
   * @throws Exception on failure
   */
  @Test
  public void testNoWindowNeedsNoZone() throws Exception {
    VacationSetting open = reply(true);
    open.setStart(null);
    open.setEnd(null);
    open.setTimeZone(null);
    when(engine.writeVacation(eq(session), any(), anyInt(), isNull())).thenReturn(own(true, "h1"));
    service.setVacation(USERNAME, null, open, false);
    verify(engine).writeVacation(eq(session), any(), eq(EmailAbsenceService.DEFAULT_DAYS), isNull());
  }

  /**
   * A write passes the hash eXo stored, then stores the new hash and a summary saying on,
   * with the dates and never the text.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAWriteStoresTheHashAndTheSummaryAfterTheServerAccepted() throws Exception {
    settings.put(EmailAbsenceService.SCRIPT_SETTING_KEY, "{\"hash\":\"h0\"}");
    System.setProperty(EmailAbsenceService.DAYS_PROPERTY, "10");
    when(engine.writeVacation(eq(session), any(), eq(10), eq("h0"))).thenReturn(own(true, "h1"));
    AbsenceSettings written = service.setVacation(USERNAME, null, reply(true), false);
    assertEquals(VacationState.OWN, written.getVacationState());
    assertEquals("h1", service.storedHash(USERNAME));
    AbsenceStatus status = JsonUtils.fromJsonString(settings.get(EmailAbsenceService.ABSENCE_SETTING_KEY), AbsenceStatus.class);
    assertTrue(status.isEnabled());
    assertEquals("2026-10-01", status.getStart());
    assertEquals("2026-10-15", status.getEnd());
    assertEquals(NOW, status.getUpdatedDate());
    assertFalse(settings.get(EmailAbsenceService.ABSENCE_SETTING_KEY).contains("Back on Monday"));
  }

  /**
   * "Re-publish" passes no hash, so eXo's own script is overwritten whatever it holds.
   *
   * @throws Exception on failure
   */
  @Test
  public void testRepublishPassesNoHash() throws Exception {
    settings.put(EmailAbsenceService.SCRIPT_SETTING_KEY, "{\"hash\":\"h0\"}");
    when(engine.writeVacation(eq(session), any(), anyInt(), isNull())).thenReturn(own(true, "h1"));
    service.setVacation(USERNAME, null, reply(true), true);
    verify(engine).writeVacation(eq(session), any(), anyInt(), isNull());
  }

  /**
   * A write the server refused writes no setting at all.
   *
   * @throws Exception on failure
   */
  @Test
  public void testARefusedWriteStoresNothing() throws Exception {
    when(engine.writeVacation(eq(session), any(), anyInt(), any())).thenThrow(new ServerRuleConflictException(ServerRuleConflictException.MANAGED_ELSEWHERE,
                                                                                                             "roundcube"));
    assertThrows(ServerRuleConflictException.class, () -> service.setVacation(USERNAME, null, reply(true), false));
    verify(settingService, never()).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
  }

  /**
   * eXo's script whose hash is not the one eXo stored reads as modified outside eXo; the
   * same hash reads as eXo's own.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheStoredHashDecidesModified() throws Exception {
    when(engine.probe(session)).thenReturn(supported());
    when(engine.readVacation(eq(session), any())).thenReturn(own(true, "h2"));
    settings.put(EmailAbsenceService.SCRIPT_SETTING_KEY, "{\"hash\":\"h1\"}");
    assertEquals(VacationState.MODIFIED, service.getAbsence(USERNAME, null).getVacationState());
    AbsenceStatus status = JsonUtils.fromJsonString(settings.get(EmailAbsenceService.ABSENCE_SETTING_KEY), AbsenceStatus.class);
    assertFalse(status.isEnabled());
    settings.put(EmailAbsenceService.SCRIPT_SETTING_KEY, "{\"hash\":\"h2\"}");
    assertEquals(VacationState.OWN, service.getAbsence(USERNAME, null).getVacationState());
  }

  /**
   * Switching off when nothing of eXo's is on writes nothing on the server; when it is
   * on, the reply is written back switched off with its text.
   *
   * @throws Exception on failure
   */
  @Test
  public void testSwitchingOff() throws Exception {
    when(engine.readVacation(eq(session), any())).thenReturn(ServerVacation.none());
    service.disableVacation(USERNAME, null);
    verify(engine, never()).writeVacation(any(), any(), anyInt(), any());
    when(engine.readVacation(eq(session), any())).thenReturn(own(true, "h1"));
    when(engine.writeVacation(eq(session), any(), anyInt(), any())).thenReturn(own(false, "h2"));
    service.disableVacation(USERNAME, null);
    ArgumentCaptor<VacationSetting> written = ArgumentCaptor.forClass(VacationSetting.class);
    verify(engine).writeVacation(eq(session), written.capture(), anyInt(), any());
    assertFalse(written.getValue().isEnabled());
    assertEquals("Back on Monday", written.getValue().getText());
    assertEquals("h2", service.storedHash(USERNAME));
  }

  /**
   * A fresh summary is answered from the cache; a stale one is read again from the
   * server; a server that cannot be read keeps the cached one and is not asked again
   * before the TTL.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheStatusCache() throws Exception {
    AbsenceStatus fresh = new AbsenceStatus(true, "2026-10-01", "2026-10-15", "Europe/Paris", "EXO", NOW - 5000, NOW - 1000);
    settings.put(EmailAbsenceService.ABSENCE_SETTING_KEY, JsonUtils.toJsonString(fresh));
    assertTrue(service.getStatus(USERNAME, null).isEnabled());
    verifyNoInteractions(engine);

    AbsenceStatus stale = new AbsenceStatus(true, "2026-10-01", "2026-10-15", "Europe/Paris", "EXO", NOW - 5000, NOW - 901_000);
    settings.put(EmailAbsenceService.ABSENCE_SETTING_KEY, JsonUtils.toJsonString(stale));
    when(engine.readVacation(eq(session), any())).thenReturn(own(false, "h1"));
    AbsenceStatus refreshed = service.getStatus(USERNAME, null);
    assertFalse(refreshed.isEnabled());
    assertEquals(NOW, refreshed.getLastServerReadDate());

    settings.put(EmailAbsenceService.ABSENCE_SETTING_KEY, JsonUtils.toJsonString(stale));
    when(engine.readVacation(eq(session), any())).thenThrow(new ServerRuleUnavailableException(ServerRuleUnavailableException.SERVER_UNREACHABLE));
    AbsenceStatus kept = service.getStatus(USERNAME, null);
    assertTrue(kept.isEnabled());
    assertEquals(NOW, kept.getLastServerReadDate());
  }

  /**
   * The zone a read states the reply's days in: the caller's own when sent, else the one
   * of the last reply eXo stored, else none; an unknown zone is not used. The status and
   * the switch-off, which have no browser zone, use the stored one.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheReadZoneHint() throws Exception {
    when(engine.probe(session)).thenReturn(supported());
    when(engine.readVacation(eq(session), any())).thenReturn(ServerVacation.none());
    service.getAbsence(USERNAME, null, "America/New_York");
    verify(engine).readVacation(session, ZoneId.of("America/New_York"));
    service.getAbsence(USERNAME, null, "Not/AZone");
    verify(engine).readVacation(session, null);

    AbsenceStatus stale = new AbsenceStatus(true, "2026-10-01", "2026-10-15", "Asia/Tokyo", "SERVER", NOW - 5000, NOW - 901_000);
    settings.put(EmailAbsenceService.ABSENCE_SETTING_KEY, JsonUtils.toJsonString(stale));
    service.getAbsence(USERNAME, null, null);
    verify(engine).readVacation(session, ZoneId.of("Asia/Tokyo"));
    settings.put(EmailAbsenceService.ABSENCE_SETTING_KEY, JsonUtils.toJsonString(stale));
    service.getStatus(USERNAME, null);
    settings.put(EmailAbsenceService.ABSENCE_SETTING_KEY, JsonUtils.toJsonString(stale));
    service.disableVacation(USERNAME, null);
    verify(engine, org.mockito.Mockito.times(3)).readVacation(session, ZoneId.of("Asia/Tokyo"));
  }

  /**
   * A summary the server did not change keeps the date it last changed.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAnUnchangedReplyKeepsItsChangeDate() throws Exception {
    AbsenceStatus stale = new AbsenceStatus(true, "2026-10-01", "2026-10-15", "Europe/Paris", "EXO", NOW - 5000, NOW - 901_000);
    settings.put(EmailAbsenceService.ABSENCE_SETTING_KEY, JsonUtils.toJsonString(stale));
    when(engine.readVacation(eq(session), any())).thenReturn(own(true, null));
    AbsenceStatus refreshed = service.getStatus(USERNAME, null);
    assertEquals(NOW - 5000, refreshed.getUpdatedDate());
  }

  /**
   * The section carries the mailbox's forward as the engine reads it, with the
   * connector's webmail to manage it; reading the section writes nothing on the server.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheForwardIsShownReadOnly() throws Exception {
    session.connector().setWebmailUrl("https://webmail.example.org/");
    when(engine.probe(session)).thenReturn(supportedWithForwarding());
    when(engine.readVacation(eq(session), any())).thenReturn(ServerVacation.none());
    when(engine.readForwarding(session)).thenReturn(ForwardingSetting.mayForwardByScript("roundcube"));
    ForwardingSetting forwarding = service.getAbsence(USERNAME, null).getForwarding();
    assertEquals(ForwardingState.MAY_FORWARD_BY_SCRIPT, forwarding.state());
    assertEquals("roundcube", forwarding.scriptName());
    assertEquals("https://webmail.example.org/", forwarding.manageUrl());
    verify(engine).probe(session);
    verify(engine).readVacation(eq(session), any());
    verify(engine).readForwarding(session);
    verify(engine).getName();
    verifyNoMoreInteractions(engine);
  }

  /**
   * A view that does not show the forward asks for the section without it: the forward
   * is not read.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheForwardIsReadOnlyWhenAskedFor() throws Exception {
    when(engine.probe(session)).thenReturn(supportedWithForwarding());
    when(engine.readVacation(eq(session), any())).thenReturn(ServerVacation.none());
    assertNull(service.getAbsence(USERNAME, null, null, false).getForwarding());
    verify(engine, never()).readForwarding(any());
  }

  /**
   * The display switched off reads nothing and shows nothing; switched on again, the
   * forward is read.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheDisplayKillSwitchReadsNothing() throws Exception {
    when(engine.probe(session)).thenReturn(supportedWithForwarding());
    when(engine.readVacation(eq(session), any())).thenReturn(ServerVacation.none());
    System.setProperty(EmailAbsenceService.FORWARDING_DISPLAY_PROPERTY, "false");
    assertNull(service.getAbsence(USERNAME, null).getForwarding());
    verify(engine, never()).readForwarding(any());
    System.setProperty(EmailAbsenceService.FORWARDING_DISPLAY_PROPERTY, "true");
    when(engine.readForwarding(session)).thenReturn(ForwardingSetting.none());
    assertEquals(ForwardingState.NONE, service.getAbsence(USERNAME, null).getForwarding().state());
    verify(engine).readForwarding(session);
  }

  /**
   * A forward is only ever about the caller's own mailbox: from a share the section is
   * refused before any forward is read.
   */
  @Test
  public void testASharedMailboxReadsNoForward() {
    assertEquals(EmailAbsenceService.OWN_MAILBOX_ONLY,
                 assertThrows(IllegalAccessException.class, () -> service.getAbsence(USERNAME, 12L, null)).getMessage());
    verifyNoInteractions(engine, emailDelegationService);
  }

  /**
   * An engine that cannot read a forward is not asked; a forward that cannot be read is
   * unknown, and the section is still served.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAnUnreadableForwardIsUnknown() throws Exception {
    when(engine.probe(session)).thenReturn(supported());
    when(engine.readVacation(eq(session), any())).thenReturn(ServerVacation.none());
    assertEquals(ForwardingState.UNKNOWN, service.getAbsence(USERNAME, null).getForwarding().state());
    verify(engine, never()).readForwarding(any());
    when(engine.probe(session)).thenReturn(supportedWithForwarding());
    when(engine.readForwarding(session)).thenThrow(new ServerRuleUnavailableException(ServerRuleUnavailableException.SERVER_UNREACHABLE));
    AbsenceSettings absence = service.getAbsence(USERNAME, null);
    assertEquals(ForwardingState.UNKNOWN, absence.getForwarding().state());
    assertEquals(VacationState.NONE, absence.getVacationState());
  }

  /**
   * Only an http or https webmail is offered as the place to manage a forward.
   */
  @Test
  public void testOnlyAWebAddressIsOffered() {
    EmailConnector connector = new EmailConnector();
    assertNull(EmailAbsenceService.webmailUrl(connector));
    connector.setWebmailUrl("javascript:alert(1)");
    assertNull(EmailAbsenceService.webmailUrl(connector));
    connector.setWebmailUrl(" HTTP://mail.example.org ");
    assertEquals("HTTP://mail.example.org", EmailAbsenceService.webmailUrl(connector));
  }

  /**
   * The capability answer for a server that holds replies.
   *
   * @return the capabilities
   */
  private static ServerRuleCapabilities supported() {
    return new ServerRuleCapabilities(true,
                                      null,
                                      false,
                                      false,
                                      ServerRuleCapabilities.VocabularySource.DYNAMIC,
                                      Map.of(ServerRuleCapabilities.VACATION, ServerRuleCapabilities.ElementSupport.SUPPORTED));
  }

  /**
   * The capability answer for a server that holds replies and reads a forward.
   *
   * @return the capabilities
   */
  private static ServerRuleCapabilities supportedWithForwarding() {
    return new ServerRuleCapabilities(true,
                                      null,
                                      false,
                                      false,
                                      ServerRuleCapabilities.VocabularySource.DYNAMIC,
                                      Map.of(ServerRuleCapabilities.VACATION,
                                             ServerRuleCapabilities.ElementSupport.SUPPORTED,
                                             ServerRuleCapabilities.FORWARDING_READ,
                                             ServerRuleCapabilities.ElementSupport.SUPPORTED));
  }

  /**
   * eXo's own reply as an engine reads it.
   *
   * @param enabled whether on
   * @param hash the script's hash
   * @return the read
   */
  private static ServerVacation own(boolean enabled, String hash) {
    VacationSetting reply = reply(enabled);
    reply.setSource(VacationSetting.Source.EXO);
    return new ServerVacation(VacationState.OWN, reply, null, hash);
  }

  /**
   * A valid reply.
   *
   * @param enabled whether on
   * @return the reply
   */
  private static VacationSetting reply(boolean enabled) {
    return new VacationSetting(enabled, "2026-10-01", "2026-10-15", "Europe/Paris", "Out of office", "Back on Monday", 0, null);
  }

  /**
   * Asserts a value is refused with a code.
   *
   * @param code the code
   * @param vacation the value
   */
  private void assertCode(String code, VacationSetting vacation) {
    IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                                              () -> service.setVacation(USERNAME, null, vacation, false));
    assertEquals(code, e.getMessage());
  }
}
