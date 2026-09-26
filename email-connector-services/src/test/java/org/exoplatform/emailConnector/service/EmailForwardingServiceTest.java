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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.ForwardingDestination;
import org.exoplatform.emailConnector.model.ForwardingSetting;
import org.exoplatform.emailConnector.model.ForwardingState;
import org.exoplatform.emailConnector.model.ForwardingStatus;
import org.exoplatform.emailConnector.model.ServerForwarding;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRule.Action;
import org.exoplatform.emailConnector.model.ServerRule.Condition;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.notification.plugin.EmailForwardingNotificationPlugin.Change;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.rules.ForwardingGuard;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngine;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngineRegistry;
import org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript;
import org.exoplatform.services.mail.MailService;
import org.exoplatform.services.mail.Message;
import org.exoplatform.services.resources.ResourceBundleService;

/**
 * The forwarding safeguards (EXO-90656), each on its own: the deployment's switch and its
 * kill switch, the allowed domains, the confirmation code -- sent by the platform, kept
 * hashed, limited, single use, bound to its destination, expiring -- the notification and
 * the mailbox's mail on every change and on a forward eXo did not set, the band's status,
 * and every state kept where the user's own settings REST cannot write it.
 */
@ExtendWith(MockitoExtension.class)
public class EmailForwardingServiceTest {

  private static final String      USERNAME     = "alice";

  private static final long        CONNECTOR_ID = 7L;

  private static final String      MAILBOX      = "alice@example.org";

  private static final String      BOB          = "bob@example.org";

  private static final String      CAROL        = "carol@example.org";

  private static final String      SENDER       = "eXo<noreply@exo.example>";

  private static final long        NOW          = 1_790_000_000_000L;

  private static final Pattern     CODE         = Pattern.compile("CODE=([0-9]{6})");

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
  private MailService              mailService;

  @Mock
  private ResourceBundleService    resourceBundleService;

  @Mock
  private ServerRuleEngine         engine;

  @Spy
  @InjectMocks
  private EmailForwardingService   service;

  /** Every setting, by context id ({@code GLOBAL} for the global one), then key. */
  private final Map<String, Map<String, String>> settings = new HashMap<>();

  private final ForwardingGuard    guard        = new ForwardingGuard();

  private MailboxAclSession        session;

  private long                     now          = NOW;

  /**
   * A connected caller whose connector allows forwarding, a real guard over the same
   * setting store, the platform's statics stubbed.
   *
   * @throws Exception on failure
   */
  @BeforeEach
  public void setUp() throws Exception {
    System.setProperty(ForwardingGuard.AUTHORING_PROPERTY + "." + CONNECTOR_ID, "true");
    service.setClock(new Clock() {
      /**
       * UTC.
       *
       * @return UTC
       */
      @Override
      public ZoneOffset getZone() {
        return ZoneOffset.UTC;
      }

      /**
       * This clock.
       *
       * @param zone ignored
       * @return this clock
       */
      @Override
      public Clock withZone(java.time.ZoneId zone) {
        return this;
      }

      /**
       * The test's instant.
       *
       * @return the instant
       */
      @Override
      public Instant instant() {
        return Instant.ofEpochMilli(now);
      }
    });
    ReflectionTestUtils.setField(guard, "settingService", settingService);
    ReflectionTestUtils.setField(guard, "userEmailSettingService", userEmailSettingService);
    ReflectionTestUtils.setField(service, "forwardingGuard", guard);
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId(String.valueOf(CONNECTOR_ID));
    setting.setEmailAddress(MAILBOX);
    EmailConnector connector = new EmailConnector();
    connector.setId(CONNECTOR_ID);
    session = new MailboxAclSession(connector, USERNAME, MAILBOX, null, null, null);
    lenient().when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(setting);
    lenient().when(emailConnectorService.getEmailConnector(CONNECTOR_ID)).thenReturn(connector);
    lenient().when(userEmailSettingService.canConnect(CONNECTOR_ID, USERNAME)).thenReturn(true);
    lenient().when(serverRuleEngineRegistry.engineFor(connector)).thenReturn(engine);
    lenient().when(emailDelegationService.openOwnSession(USERNAME)).thenReturn(session);
    lenient().when(resourceBundleService.getSharedString(anyString(), any(Locale.class)))
             .thenAnswer(invocation -> invocation.getArgument(0, String.class) + " CODE={0} TO={0} BY={1}");
    lenient().when(settingService.get(any(Context.class), any(Scope.class), anyString())).thenAnswer(invocation -> {
      String value = store(invocation.getArgument(0, Context.class)).get(invocation.getArgument(2, String.class));
      return value == null ? null : SettingValue.create(value);
    });
    lenient().doAnswer(invocation -> {
      store(invocation.getArgument(0, Context.class)).put(invocation.getArgument(2, String.class),
                                                          invocation.getArgument(3, SettingValue.class).getValue().toString());
      return null;
    }).when(settingService).set(any(Context.class), any(Scope.class), anyString(), any(SettingValue.class));
    lenient().doReturn(SENDER).when(service).platformSender();
    lenient().doReturn("Alice Liddell").when(service).displayName(USERNAME);
    lenient().doReturn(Locale.ENGLISH).when(service).localeOf(anyString());
    lenient().doNothing().when(service).notifyWeb(anyString(), any(), any(), any());
  }

  /**
   * Clears the properties.
   */
  @AfterEach
  public void tearDown() {
    System.clearProperty(ForwardingGuard.AUTHORING_PROPERTY);
    System.clearProperty(ForwardingGuard.AUTHORING_PROPERTY + "." + CONNECTOR_ID);
    System.clearProperty(ForwardingGuard.ALLOWED_DOMAINS_PROPERTY);
    System.clearProperty(ForwardingGuard.ALLOWED_DOMAINS_PROPERTY + "." + CONNECTOR_ID);
    System.clearProperty(EmailForwardingService.CODE_MAX_SENDS_PROPERTY);
  }

  // ---------------------------------------------------------------------------------
  // (c) the deployment's switch, and the kill switch
  // ---------------------------------------------------------------------------------

  /**
   * Off for the connector -- the default -- nothing can be authored: no code is sent, no
   * destination confirmed, no forward written, no rule that forwards accepted.
   *
   * @throws Exception on failure
   */
  @Test
  public void testSwitchedOffNothingIsAuthored() throws Exception {
    System.clearProperty(ForwardingGuard.AUTHORING_PROPERTY + "." + CONNECTOR_ID);
    assertRefused(ForwardingGuard.DISABLED, () -> service.sendCode(USERNAME, null, BOB));
    assertRefused(ForwardingGuard.DISABLED, () -> service.confirm(USERNAME, null, BOB, "123456"));
    guard.confirm(USERNAME, BOB);
    assertRefused(ForwardingGuard.DISABLED, () -> service.setForwarding(USERNAME, null, BOB, null, false));
    assertRefused(ForwardingGuard.DISABLED, () -> service.requireRuleForwardsAllowed(USERNAME, List.of(forward(BOB))));
    assertFalse(service.getAuthoring(USERNAME, null).enabled());
    assertEquals(ForwardingGuard.DISABLED, service.getAuthoring(USERNAME, null).reasonCode());
    verify(mailService, never()).sendMessage(any(Message.class));
    verify(engine, never()).writeForwarding(any(), any(), any());
  }

  /**
   * The deployment-wide key set to false switches forwarding off everywhere at once,
   * whatever a connector's own key says.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheKillSwitchWins() throws Exception {
    System.setProperty(ForwardingGuard.AUTHORING_PROPERTY, "false");
    assertRefused(ForwardingGuard.DISABLED, () -> service.sendCode(USERNAME, null, BOB));
    System.setProperty(ForwardingGuard.AUTHORING_PROPERTY, "true");
    System.clearProperty(ForwardingGuard.AUTHORING_PROPERTY + "." + CONNECTOR_ID);
    assertTrue(service.getAuthoring(USERNAME, null).enabled());
  }

  // ---------------------------------------------------------------------------------
  // (b) the allowed domains
  // ---------------------------------------------------------------------------------

  /**
   * By default only the mailbox's own domain is allowed; a configured list replaces it;
   * the mailbox itself is never a destination.
   *
   * @throws Exception on failure
   */
  @Test
  public void testOnlyAllowedDomains() throws Exception {
    assertEquals(List.of("example.org"), service.getAuthoring(USERNAME, null).allowedDomains());
    assertRefused(ForwardingGuard.DOMAIN_NOT_ALLOWED, () -> service.sendCode(USERNAME, null, "bob@evil.example"));
    assertRefused(ForwardingGuard.DOMAIN_NOT_ALLOWED, () -> service.sendCode(USERNAME, null, "bob@sub.example.org"));
    assertEquals(ForwardingGuard.OWN_ADDRESS,
                 assertThrows(IllegalArgumentException.class, () -> service.sendCode(USERNAME, null, " Alice@Example.org ")).getMessage());
    assertEquals(ForwardingDestination.INVALID,
                 assertThrows(IllegalArgumentException.class, () -> service.sendCode(USERNAME, null, "Bob <bob@example.org>")).getMessage());
    System.setProperty(ForwardingGuard.ALLOWED_DOMAINS_PROPERTY + "." + CONNECTOR_ID, "partner.com, @Other.org");
    assertEquals(List.of("partner.com", "other.org"), service.getAuthoring(USERNAME, null).allowedDomains());
    assertRefused(ForwardingGuard.DOMAIN_NOT_ALLOWED, () -> service.sendCode(USERNAME, null, BOB));
    assertEquals("bob@partner.com", service.sendCode(USERNAME, null, "Bob@Partner.com").destination());
    verify(mailService, times(1)).sendMessage(any(Message.class));
  }

  /**
   * A destination that was confirmed but whose domain is no longer allowed is no longer
   * authorized for any write: the generators check against this.
   */
  @Test
  public void testAConfirmedDestinationLeavesWithItsDomain() {
    guard.confirm(USERNAME, BOB);
    assertEquals(java.util.Set.of(BOB), guard.authorizedDestinations(session));
    System.setProperty(ForwardingGuard.ALLOWED_DOMAINS_PROPERTY, "partner.com");
    assertTrue(guard.authorizedDestinations(session).isEmpty());
  }

  // ---------------------------------------------------------------------------------
  // (d) the confirmation code
  // ---------------------------------------------------------------------------------

  /**
   * The code goes to the destination from the platform's sender, never the mailbox; only
   * its salted hash is kept, with its expiry, in the global context.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheCodeIsSentByThePlatformAndKeptHashed() throws Exception {
    service.sendCode(USERNAME, null, BOB);
    Message sent = sentCodeMail();
    assertEquals(SENDER, sent.getFrom());
    assertEquals(BOB, sent.getTo());
    String code = codeOf(sent);
    String pending = store(Context.GLOBAL).get("pending." + USERNAME);
    assertNotNull(pending);
    assertFalse(pending.contains(code), pending);
    assertTrue(pending.contains("\"hash\""), pending);
    assertTrue(pending.contains("\"expiresAt\":" + (NOW + EmailForwardingService.DEFAULT_CODE_TTL * 1000)), pending);
    assertFalse(settings.containsKey(USERNAME), "nothing of forwarding in the user's own context: " + settings.get(USERNAME));
  }

  /**
   * Codes are limited: one a minute, a few an hour, a failing relay counting too.
   *
   * @throws Exception on failure
   */
  @Test
  public void testCodesAreRateLimited() throws Exception {
    service.sendCode(USERNAME, null, BOB);
    assertRefused(EmailForwardingService.CODE_TOO_MANY_SENDS, () -> service.sendCode(USERNAME, null, BOB));
    now += 61_000;
    service.sendCode(USERNAME, null, BOB);
    now += 61_000;
    doThrow(new IllegalStateException("relay down")).when(mailService).sendMessage(any(Message.class));
    assertEquals(EmailForwardingService.CODE_NOT_SENT,
                 assertThrows(ServerRuleUnavailableException.class, () -> service.sendCode(USERNAME, null, BOB)).getMessage());
    now += 61_000;
    assertRefused(EmailForwardingService.CODE_TOO_MANY_SENDS, () -> service.sendCode(USERNAME, null, BOB));
    now += 3_600_000;
    doNothing().when(mailService).sendMessage(any(Message.class));
    service.sendCode(USERNAME, null, BOB);
  }

  /**
   * The right code confirms its destination, once: the same code again is refused, and
   * a code sent to one destination does not confirm another.
   *
   * @throws Exception on failure
   */
  @Test
  public void testACodeConfirmsItsDestinationOnce() throws Exception {
    service.sendCode(USERNAME, null, BOB);
    String code = codeOf(sentCodeMail());
    assertEquals(EmailForwardingService.CODE_INVALID,
                 assertThrows(IllegalArgumentException.class, () -> service.confirm(USERNAME, null, CAROL, code)).getMessage());
    assertEquals(BOB, service.confirm(USERNAME, null, " BOB@example.org", code));
    assertTrue(guard.confirmedDestinations(USERNAME).contains(BOB));
    assertEquals(EmailForwardingService.CODE_INVALID,
                 assertThrows(IllegalArgumentException.class, () -> service.confirm(USERNAME, null, BOB, code)).getMessage());
    assertTrue(store(Context.GLOBAL).get("confirmed." + USERNAME).contains(BOB));
  }

  /**
   * A wrong code counts a try; the last allowed wrong try throws the code away, and the
   * right code is refused afterwards.
   *
   * @throws Exception on failure
   */
  @Test
  public void testWrongCodesAreLimited() throws Exception {
    service.sendCode(USERNAME, null, BOB);
    String code = codeOf(sentCodeMail());
    String wrong = code.equals("000000") ? "111111" : "000000";
    for (int i = 1; i < EmailForwardingService.DEFAULT_MAX_TRIES; i++) {
      assertEquals(EmailForwardingService.CODE_INVALID,
                   assertThrows(IllegalArgumentException.class, () -> service.confirm(USERNAME, null, BOB, wrong)).getMessage());
    }
    assertRefused(EmailForwardingService.CODE_TOO_MANY_TRIES, () -> service.confirm(USERNAME, null, BOB, wrong));
    assertEquals(EmailForwardingService.CODE_INVALID,
                 assertThrows(IllegalArgumentException.class, () -> service.confirm(USERNAME, null, BOB, code)).getMessage());
    assertFalse(guard.confirmedDestinations(USERNAME).contains(BOB));
  }

  /**
   * An expired code confirms nothing, and is thrown away.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAnExpiredCodeConfirmsNothing() throws Exception {
    service.sendCode(USERNAME, null, BOB);
    String code = codeOf(sentCodeMail());
    now += EmailForwardingService.DEFAULT_CODE_TTL * 1000 + 1;
    assertEquals(EmailForwardingService.CODE_EXPIRED,
                 assertThrows(IllegalArgumentException.class, () -> service.confirm(USERNAME, null, BOB, code)).getMessage());
    assertEquals(EmailForwardingService.CODE_INVALID,
                 assertThrows(IllegalArgumentException.class, () -> service.confirm(USERNAME, null, BOB, code)).getMessage());
    assertFalse(guard.confirmedDestinations(USERNAME).contains(BOB));
  }

  /**
   * A forward to a destination not confirmed is refused before anything is written; the
   * code sent to it, carried by the request, confirms it and the forward is written.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAForwardNeedsItsDestinationConfirmed() throws Exception {
    assertRefused(ForwardingGuard.NOT_CONFIRMED, () -> service.setForwarding(USERNAME, null, BOB, null, false));
    verify(engine, never()).writeForwarding(any(), any(), any());
    service.sendCode(USERNAME, null, BOB);
    String code = codeOf(sentCodeMail());
    when(engine.readForwarding(session)).thenReturn(ForwardingSetting.none());
    when(engine.writeForwarding(session, BOB, null)).thenReturn(new ServerForwarding(ForwardingSetting.exoForward(BOB, null), "h1"));
    ForwardingSetting written = service.setForwarding(USERNAME, null, BOB, code, false);
    assertTrue(written.managedByExo());
    verify(engine).writeForwarding(session, BOB, null);
    assertTrue(settings.get(USERNAME).get(ExoSieveScript.HASH_SETTING_KEY).contains("h1"));
  }

  // ---------------------------------------------------------------------------------
  // (f) the notification and the mailbox's mail, on every change
  // ---------------------------------------------------------------------------------

  /**
   * Set, changed and removed are each told to the owner: an eXo notification and a mail
   * into the mailbox, from the platform's sender. Removing is allowed with forwarding
   * switched off.
   *
   * @throws Exception on failure
   */
  @Test
  public void testEveryChangeIsTold() throws Exception {
    guard.confirm(USERNAME, BOB);
    guard.confirm(USERNAME, CAROL);
    when(engine.readForwarding(session)).thenReturn(ForwardingSetting.none(),
                                                    ForwardingSetting.exoForward(BOB, null),
                                                    ForwardingSetting.exoForward(CAROL, null));
    when(engine.writeForwarding(eq(session), any(), any())).thenAnswer(invocation -> new ServerForwarding(invocation.getArgument(1) == null
        ? ForwardingSetting.none()
        : ForwardingSetting.exoForward(invocation.getArgument(1), null), "h"));
    service.setForwarding(USERNAME, null, BOB, null, false);
    verify(service).notifyWeb(USERNAME, Change.SET, BOB, null);
    service.setForwarding(USERNAME, null, CAROL, null, false);
    verify(service).notifyWeb(USERNAME, Change.CHANGED, CAROL, null);
    System.clearProperty(ForwardingGuard.AUTHORING_PROPERTY + "." + CONNECTOR_ID);
    service.removeForwarding(USERNAME, null, false);
    verify(service).notifyWeb(USERNAME, Change.REMOVED, CAROL, null);
    ArgumentCaptor<Message> mails = ArgumentCaptor.forClass(Message.class);
    verify(mailService, times(3)).sendMessageInFuture(mails.capture());
    for (Message mail : mails.getAllValues()) {
      assertEquals(MAILBOX, mail.getTo());
      assertEquals(SENDER, mail.getFrom());
    }
  }

  /**
   * A forward eXo did not set is told once when first seen, and again only when it
   * changes; a read that established nothing changes nothing.
   */
  @Test
  public void testAForeignForwardIsToldOnce() {
    service.observe(USERNAME, MAILBOX, ForwardingSetting.mayForwardByScript("roundcube"));
    service.observe(USERNAME, MAILBOX, ForwardingSetting.mayForwardByScript("roundcube"));
    service.observe(USERNAME, MAILBOX, ForwardingSetting.unknown());
    verify(service, times(1)).notifyWeb(USERNAME, Change.FOREIGN_SCRIPT, null, "roundcube");
    service.observe(USERNAME, MAILBOX, ForwardingSetting.serverForward(List.of("x@elsewhere.example"), true));
    verify(service).notifyWeb(USERNAME, Change.FOREIGN, "x@elsewhere.example", null);
    service.observe(USERNAME, MAILBOX, ForwardingSetting.none());
    service.observe(USERNAME, MAILBOX, ForwardingSetting.mayForwardByScript("roundcube"));
    verify(service, times(2)).notifyWeb(USERNAME, Change.FOREIGN_SCRIPT, null, "roundcube");
    // eXo's own forward is not foreign.
    service.observe(USERNAME, MAILBOX, ForwardingSetting.exoForward(BOB, null));
    verify(service, never()).notifyWeb(eq(USERNAME), eq(Change.FOREIGN), eq(BOB), any());
    verify(mailService, times(3)).sendMessageInFuture(any(Message.class));
  }

  /**
   * A rule that starts forwarding, forwards elsewhere, or stops, is told once each.
   */
  @Test
  public void testRulesThatForwardAreTold() {
    service.recordRuleForwards(USERNAME, List.of(forwardRule("1", "Acme", BOB)));
    service.recordRuleForwards(USERNAME, List.of(forwardRule("1", "Acme", BOB)));
    verify(service, times(1)).notifyWeb(USERNAME, Change.RULE_SET, BOB, "Acme");
    service.recordRuleForwards(USERNAME, List.of(forwardRule("1", "Acme", CAROL)));
    verify(service).notifyWeb(USERNAME, Change.RULE_CHANGED, CAROL, "Acme");
    service.recordRuleForwards(USERNAME, List.of());
    verify(service).notifyWeb(USERNAME, Change.RULE_REMOVED, CAROL, "Acme");
  }

  /**
   * A rule that forwards must pass the forward's own checks: an allowed domain, a
   * confirmed destination.
   *
   * @throws Exception on failure
   */
  @Test
  public void testARuleForwardPassesTheSameChecks() throws Exception {
    assertRefused(ForwardingGuard.NOT_CONFIRMED, () -> service.requireRuleForwardsAllowed(USERNAME, List.of(forward(BOB))));
    assertRefused(ForwardingGuard.DOMAIN_NOT_ALLOWED,
                  () -> service.requireRuleForwardsAllowed(USERNAME, List.of(forward("bob@evil.example"))));
    guard.confirm(USERNAME, BOB);
    service.requireRuleForwardsAllowed(USERNAME, List.of(forward(BOB)));
    service.requireRuleForwardsAllowed(USERNAME, List.of(new Action(ServerRule.STAR, null, null, null)));
  }

  /**
   * On an engine without a script (BlueMind), the forward eXo set reads back as eXo's --
   * no "not set in eXo" notice for it -- while one set in the webmail is another client's,
   * which removing eXo's forward never switches off.
   *
   * @throws Exception on failure
   */
  @Test
  public void testABlueMindForwardIsRecognisedAsEXos() throws Exception {
    guard.confirm(USERNAME, BOB);
    when(engine.readForwarding(session)).thenReturn(ForwardingSetting.none());
    when(engine.writeForwarding(session, BOB, null)).thenReturn(new ServerForwarding(ForwardingSetting.serverForward(List.of(BOB), true), null));
    assertTrue(service.setForwarding(USERNAME, null, BOB, null, false).managedByExo());
    service.observe(USERNAME, MAILBOX, ForwardingSetting.serverForward(List.of(BOB), true));
    verify(service, never()).notifyWeb(eq(USERNAME), eq(Change.FOREIGN), any(), any());
    ForwardingSetting webmail = ForwardingSetting.serverForward(List.of(CAROL), true);
    assertFalse(service.recognise(USERNAME, webmail).managedByExo());
    when(engine.readForwarding(session)).thenReturn(webmail);
    service.removeForwarding(USERNAME, null, false);
    verify(engine, never()).writeForwarding(session, null, null);
  }

  /**
   * A BlueMind forward edited in the webmail not to keep a copy is not eXo's any more,
   * even to eXo's destination.
   */
  @Test
  public void testAForwardWithoutCopyIsNotEXos() {
    store(Context.GLOBAL).put("written." + USERNAME, BOB);
    assertTrue(service.recognise(USERNAME, ForwardingSetting.serverForward(List.of(BOB), true)).managedByExo());
    assertFalse(service.recognise(USERNAME, ForwardingSetting.serverForward(List.of(BOB), false)).managedByExo());
  }

  /**
   * Where forwards eXo did not set are not shown -- display and authoring both off --
   * none is told or put on the band, and it is told once they are shown; eXo's own
   * forward stays on the band.
   */
  @Test
  public void testAHiddenForeignForwardIsNotTold() {
    service.observe(USERNAME, MAILBOX, ForwardingSetting.mayForwardByScript("roundcube"), false);
    verify(service, never()).notifyWeb(eq(USERNAME), any(), any(), any());
    assertEquals(ForwardingState.NONE,
                 EmailForwardingService.visible(ForwardingStatus.of(ForwardingSetting.mayForwardByScript("roundcube"), NOW), false)
                                       .getState());
    assertTrue(EmailForwardingService.visible(ForwardingStatus.of(ForwardingSetting.exoForward(BOB, null), NOW), false)
                                     .isManagedByExo());
    service.observe(USERNAME, MAILBOX, ForwardingSetting.mayForwardByScript("roundcube"), true);
    verify(service).notifyWeb(USERNAME, Change.FOREIGN_SCRIPT, null, "roundcube");    // eXo's own forward edited outside eXo is told whatever the switches.
    service.observe(USERNAME, MAILBOX, ForwardingSetting.exoForward(BOB, "exo-rules"), false);
    verify(service).notifyWeb(USERNAME, Change.FOREIGN_SCRIPT, null, "exo-rules");
  }

  /**
   * The hash of the script a forward's write left is kept where the user cannot write
   * it, beside the one the reply and the rules compare with.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheWrittenScriptHashIsKeptGlobally() throws Exception {
    guard.confirm(USERNAME, BOB);
    when(engine.readForwarding(session)).thenReturn(ForwardingSetting.none());
    when(engine.writeForwarding(session, BOB, null)).thenReturn(new ServerForwarding(ForwardingSetting.exoForward(BOB, null), "h9"));
    service.setForwarding(USERNAME, null, BOB, null, false);
    assertEquals("h9", guard.lastWrittenScriptHash(USERNAME));
  }

  /**
   * A rule that starts or stops forwarding is on the band at once, not after the status'
   * lifetime.
   */
  @Test
  public void testTheBandFollowsTheRulesAtOnce() {
    service.recordRuleForwards(USERNAME, List.of(forwardRule("1", "Acme", BOB)));
    assertTrue(store(Context.GLOBAL).get("status." + USERNAME).contains(BOB));
    service.recordRuleForwards(USERNAME, List.of());
    assertFalse(store(Context.GLOBAL).get("status." + USERNAME).contains(BOB));
  }

  /**
   * With the display and authoring both switched off, a forward eXo set is still read
   * and shown on the band while it runs.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheBandKeepsEXosForwardWhateverTheSwitches() throws Exception {
    System.clearProperty(ForwardingGuard.AUTHORING_PROPERTY + "." + CONNECTOR_ID);
    System.setProperty(EmailAbsenceService.FORWARDING_DISPLAY_PROPERTY, "false");
    try {
      service.observe(USERNAME, MAILBOX, ForwardingSetting.exoForward(BOB, null));
      now += EmailAbsenceService.DEFAULT_TTL_SECONDS * 1000 + 1;
      when(engine.readForwarding(session)).thenReturn(ForwardingSetting.exoForward(BOB, null));
      assertTrue(service.getStatus(USERNAME, null).isManagedByExo());
      verify(engine).readForwarding(session);
    } finally {
      System.clearProperty(EmailAbsenceService.FORWARDING_DISPLAY_PROPERTY);
    }
  }

  // ---------------------------------------------------------------------------------
  // (h) the band's status, and (g)'s hook
  // ---------------------------------------------------------------------------------

  /**
   * The band's status is read from the server when stale, cached otherwise, and kept
   * where the user's own settings REST cannot write it.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheBandsStatusIsCachedGlobally() throws Exception {
    when(engine.readForwarding(session)).thenReturn(ForwardingSetting.exoForward(BOB, null));
    ForwardingStatus status = service.getStatus(USERNAME, null);
    assertEquals(ForwardingState.SERVER_FORWARD, status.getState());
    assertTrue(status.isManagedByExo());
    assertEquals(List.of(BOB), status.getDestinations());
    service.getStatus(USERNAME, null);
    verify(engine, times(1)).readForwarding(session);
    assertNotNull(store(Context.GLOBAL).get("status." + USERNAME));
    assertFalse(settings.containsKey(USERNAME));
    assertThrows(IllegalAccessException.class, () -> service.getStatus(USERNAME, 3L));
  }

  /**
   * A second proof of identity, when one is configured, is asked before any change.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheStepUpHookIsAsked() throws Exception {
    ForwardingStepUp stepUp = username -> {
      throw new IllegalAccessException("stepUp.required");
    };
    ReflectionTestUtils.setField(service, "stepUp", stepUp);
    assertRefused("stepUp.required", () -> service.sendCode(USERNAME, null, BOB));
    assertRefused("stepUp.required", () -> service.setForwarding(USERNAME, null, BOB, "123456", false));
    verify(mailService, never()).sendMessage(any(Message.class));
  }

  /**
   * Asserts an action is refused with a code, as a 403.
   *
   * @param code the code
   * @param action the action
   */
  private static void assertRefused(String code, org.junit.jupiter.api.function.Executable action) {
    assertEquals(code, assertThrows(IllegalAccessException.class, action).getMessage());
  }

  /**
   * The last confirmation mail the platform sent.
   *
   * @return the mail
   * @throws Exception on failure
   */
  private Message sentCodeMail() throws Exception {
    ArgumentCaptor<Message> sent = ArgumentCaptor.forClass(Message.class);
    verify(mailService, org.mockito.Mockito.atLeastOnce()).sendMessage(sent.capture());
    return sent.getValue();
  }

  /**
   * The code a confirmation mail carries.
   *
   * @param mail the mail
   * @return the code
   */
  private static String codeOf(Message mail) {
    Matcher matcher = CODE.matcher(mail.getBody());
    assertTrue(matcher.find(), mail.getBody());
    return matcher.group(1);
  }

  /**
   * A forward action.
   *
   * @param destination where
   * @return the action
   */
  private static Action forward(String destination) {
    return new Action(ServerRule.FORWARD, null, null, null, destination);
  }

  /**
   * A rule that forwards.
   *
   * @param ref its reference
   * @param name its name
   * @param destination where
   * @return the rule
   */
  private static ServerRule forwardRule(String ref, String name, String destination) {
    return new ServerRule(ref,
                          name,
                          true,
                          true,
                          List.of(new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com")),
                          List.of(forward(destination)),
                          false);
  }

  /**
   * A context's settings.
   *
   * @param context the context
   * @return the map
   */
  private Map<String, String> store(Context context) {
    String id = context.getId() == null ? "GLOBAL" : context.getId();
    return settings.computeIfAbsent(id, key -> new HashMap<>());
  }
}
