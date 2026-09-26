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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
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
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.ReconcileReport;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRule.Action;
import org.exoplatform.emailConnector.model.ServerRule.Condition;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleSet;
import org.exoplatform.emailConnector.model.ServerRulesSettings;
import org.exoplatform.emailConnector.model.ServerRulesState;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.acl.MailboxAclSession;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngine;
import org.exoplatform.emailConnector.service.rules.ServerRuleEngineRegistry;
import org.exoplatform.emailConnector.service.rules.sieve.ExoSieveScript;

import io.meeds.social.util.JsonUtils;

/**
 * The server rules service: own mailbox only, the one-time consent, folders resolved
 * from the caller's own mirrored folders and never taken as text, the keyword refused
 * from a user, and the script hash shared with the automatic reply.
 */
@ExtendWith(MockitoExtension.class)
public class EmailServerRuleServiceTest {

  private static final String      USERNAME     = "alice";

  private static final long        CONNECTOR_ID = 5L;

  private static final long        NOW          = 1_790_000_000_000L;

  private static final Condition   FROM_ACME    = new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com");

  @Mock
  private UserEmailSettingService  userEmailSettingService;

  @Mock
  private EmailConnectorService    emailConnectorService;

  @Mock
  private EmailDelegationService   emailDelegationService;

  @Mock
  private EmailFolderService       emailFolderService;

  @Mock
  private EmailBoxService          emailBoxService;

  @Mock
  private ServerRuleEngineRegistry serverRuleEngineRegistry;

  @Mock
  private SettingService           settingService;

  @Mock
  private ServerRuleEngine         engine;

  @InjectMocks
  private EmailServerRuleService   service;

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
    System.clearProperty(EmailServerRuleService.ENABLED_PROPERTY);
  }

  /**
   * A request made from someone else's mailbox is refused by every verb, before anything
   * is asked of anyone; the feature switched off answers "not found".
   */
  @Test
  public void testOwnMailboxOnlyAndTheSwitch() {
    ServerRule rule = rule(List.of(new Action("STAR", null, null, null)));
    assertEquals(EmailServerRuleService.OWN_MAILBOX_ONLY,
                 assertThrows(IllegalAccessException.class, () -> service.getServerRules(USERNAME, 12L)).getMessage());
    assertThrows(IllegalAccessException.class, () -> service.getCapabilities(USERNAME, 12L));
    assertThrows(IllegalAccessException.class, () -> service.saveRule(USERNAME, 12L, null, rule, false, true));
    assertThrows(IllegalAccessException.class, () -> service.deleteRule(USERNAME, 12L, "1", false));
    assertThrows(IllegalAccessException.class, () -> service.publishRules(USERNAME, 12L, false));
    verifyNoInteractions(engine, emailDelegationService, settingService);
    System.setProperty(EmailServerRuleService.ENABLED_PROPERTY, "false");
    assertEquals(EmailServerRuleService.DISABLED,
                 assertThrows(ObjectNotFoundException.class, () -> service.getServerRules(USERNAME, null)).getMessage());
  }

  /**
   * The first write needs the caller's consent: refused without it and nothing written,
   * recorded with it, and not asked again.
   *
   * @throws Exception on failure
   */
  @Test
  public void testConsentIsAskedOnce() throws Exception {
    ServerRule rule = rule(List.of(new Action("STAR", null, null, null)));
    assertEquals(EmailServerRuleService.CONSENT_REQUIRED,
                 assertThrows(IllegalArgumentException.class, () -> service.saveRule(USERNAME, null, null, rule, false, false))
                                                                                                                              .getMessage());
    verify(engine, never()).saveRule(any(), any(), any());
    when(engine.saveRule(eq(session), any(), isNull())).thenReturn(written("h1"));
    ServerRulesSettings first = service.saveRule(USERNAME, null, null, rule, false, true);
    assertTrue(first.isConsented());
    assertEquals(String.valueOf(NOW), settings.get(EmailServerRuleService.CONSENT_SETTING_KEY));
    when(engine.saveRule(eq(session), any(), eq("h1"))).thenReturn(written("h2"));
    service.saveRule(USERNAME, null, null, rule, false, false);
  }

  /**
   * A swap -- a user's rule added or removed in the write that reconciles the hops --
   * needs the consent when it adds a rule, hands the engine the rule validated with its
   * folders resolved, refuses a malformed reference, and a plain reconciliation stays the
   * engine's plain one.
   *
   * @throws Exception on failure
   */
  @Test
  public void testASwapAddsAValidatedRuleWithTheConsent() throws Exception {
    ServerRule rule = rule(List.of(new Action("STAR", null, null, null)));
    assertEquals(EmailServerRuleService.CONSENT_REQUIRED,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.reconcileHops(USERNAME, List.of(), rule, null, false, false, false)).getMessage());
    assertThrows(IllegalArgumentException.class, () -> service.reconcileHops(USERNAME, List.of(), null, "no ref", false, true, false));
    verifyNoInteractions(engine);
    ReconcileReport report = new ReconcileReport(List.of("2"), List.of("hop-1"), written("h1"));
    when(engine.reconcile(eq(session), eq(List.of()), any(ServerRule.class), isNull(), isNull())).thenReturn(report);
    assertEquals(report, service.reconcileHops(USERNAME, List.of(), rule, null, false, true, false));
    ArgumentCaptor<ServerRule> added = ArgumentCaptor.forClass(ServerRule.class);
    verify(engine).reconcile(eq(session), eq(List.of()), added.capture(), isNull(), isNull());
    assertEquals(rule.validated(), added.getValue(), "validated, without a reference");
    when(engine.reconcile(eq(session), eq(List.of()), eq("h1"))).thenReturn(new ReconcileReport(List.of(), List.of(), written("h1")));
    service.reconcileHops(USERNAME, List.of(), false, false, false);
    verify(engine).reconcile(eq(session), eq(List.of()), eq("h1"));
  }

  /**
   * A move names one of the caller's own mirrored folders by its key, and the engine gets
   * the folder's name on the server; Junk and Trash are the ones the sync resolved; a
   * folder not mirrored, somebody else's, unknown or unresolved is refused before any
   * write, and so is the keyword.
   *
   * @throws Exception on failure
   */
  @Test
  public void testFoldersAreResolvedFromTheCallersOwnMirroredFolders() throws Exception {
    settings.put(EmailServerRuleService.CONSENT_SETTING_KEY, "1");
    EmailFolder accounting = folder(12L, "Accounting/2026", true, null);
    when(emailFolderService.getFolderByKey(USERNAME, "CUSTOM:12")).thenReturn(accounting);
    lenient().when(emailBoxService.getRememberedFolderName(USERNAME, "JUNK")).thenReturn("Junk Mail");
    ArgumentCaptor<ServerRule> saved = ArgumentCaptor.forClass(ServerRule.class);
    when(engine.saveRule(eq(session), saved.capture(), any())).thenReturn(written("h"));
    service.saveRule(USERNAME,
                     null,
                     null,
                     rule(List.of(new Action("MOVE_TO_FOLDER", "CUSTOM:12", "/etc/evil", "x"), new Action("MARK_READ", null, null, null))),
                     false,
                     false);
    assertEquals(new Action("MOVE_TO_FOLDER", "CUSTOM:12", "Accounting/2026", null), saved.getValue().actions().get(0));
    service.saveRule(USERNAME, null, "3", rule(List.of(new Action("MARK_JUNK", null, "Inbox", null))), false, false);
    assertEquals(new Action("MARK_JUNK", "JUNK", "Junk Mail", null), saved.getValue().actions().get(0));
    assertEquals("3", saved.getValue().ref());

    when(emailFolderService.getFolderByKey(USERNAME, "CUSTOM:13")).thenReturn(folder(13L, "Old", false, null));
    when(emailFolderService.getFolderByKey(USERNAME, "CUSTOM:14")).thenReturn(folder(14L, "Bob/Inbox", true, 7L));
    when(emailBoxService.getRememberedFolderName(USERNAME, "TRASH")).thenReturn(null);
    assertRefused(EmailServerRuleService.FOLDER_NOT_MIRRORED, new Action("MOVE_TO_FOLDER", "CUSTOM:13", null, null));
    assertRefused(EmailFolderService.UNKNOWN_FOLDER_MESSAGE, new Action("MOVE_TO_FOLDER", "CUSTOM:14", null, null));
    assertRefused(EmailFolderService.UNKNOWN_FOLDER_MESSAGE, new Action("MOVE_TO_FOLDER", "Accounting", "Accounting", null));
    assertRefused(EmailFolderService.UNKNOWN_FOLDER_MESSAGE, new Action("MOVE_TO_FOLDER", "INBOX", null, null));
    assertRefused(EmailServerRuleService.FOLDER_UNRESOLVED, new Action("DELETE", null, null, null));
    assertRefused(ServerRule.INVALID_ACTION, new Action("TAG", null, null, "exo-filter-1"));
    assertRefused(ServerRule.INVALID_ACTION, new Action(" tag ", null, null, "exo-filter-1"));
    assertRefused(ServerRule.INVALID_ACTION, new Action(null, null, null, null));
  }

  /**
   * The read compares eXo's script with the hash either feature stored: another hash
   * reads "modified"; a write stores the new hash, where the automatic reply reads it
   * too, and passes the stored one to the engine unless re-publishing.
   *
   * @throws Exception on failure
   */
  @Test
  public void testTheHashIsSharedWithTheAutomaticReply() throws Exception {
    settings.put(ExoSieveScript.HASH_SETTING_KEY, JsonUtils.toJsonString(Map.of("hash", "old")));
    when(engine.probe(session)).thenReturn(new ServerRuleCapabilities(true, null, false, false, null, Map.of()));
    when(engine.listRules(session)).thenReturn(new ServerRuleSet(List.of(), ServerRulesState.OWN, null, "other"));
    assertEquals(ServerRulesState.MODIFIED, service.getServerRules(USERNAME, null).getState());
    when(engine.publishRules(session, "old")).thenReturn(written("new"));
    service.publishRules(USERNAME, null, false);
    assertEquals("new", service.storedHash(USERNAME));
    when(engine.deleteRule(session, "1", null)).thenReturn(written("newer"));
    service.deleteRule(USERNAME, null, "1", true);
    assertEquals("newer", service.storedHash(USERNAME));
    assertEquals(ServerRulesState.INACTIVE,
                 EmailServerRuleService.compared(new ServerRuleSet(List.of(), ServerRulesState.INACTIVE, null, "newer"), "newer").state());
  }

  /**
   * An engine that cannot hold rules answers an empty group with its reason, and nothing
   * is listed.
   *
   * @throws Exception on failure
   */
  @Test
  public void testAnEngineWithoutRulesAnswersAnEmptyGroup() throws Exception {
    when(engine.probe(session)).thenReturn(ServerRuleCapabilities.unsupported("x", ServerRuleCapabilities.VocabularySource.NONE));
    ServerRulesSettings group = service.getServerRules(USERNAME, null);
    assertEquals(List.of(), group.getRules());
    assertEquals(ServerRulesState.NONE, group.getState());
    assertFalse(group.getCapabilities().supported());
    assertFalse(group.isConsented());
    verify(engine, never()).listRules(any());
    assertNull(group.getForeignScriptName());
  }

  /**
   * Asserts that saving a rule with one action is refused with a code, before the engine
   * is asked.
   *
   * @param code the code
   * @param action the action
   * @throws Exception on failure
   */
  private void assertRefused(String code, Action action) throws Exception {
    assertEquals(code,
                 assertThrows(IllegalArgumentException.class,
                              () -> service.saveRule(USERNAME, null, null, rule(List.of(action)), false, true)).getMessage(),
                 String.valueOf(action));
  }

  /**
   * A rule from acme.com with the given actions.
   *
   * @param actions the actions
   * @return the rule
   */
  private static ServerRule rule(List<Action> actions) {
    return new ServerRule(null, "Acme", true, true, List.of(FROM_ACME), actions, false);
  }

  /**
   * What the engine holds after a write.
   *
   * @param hash the script's hash
   * @return the set
   */
  private static ServerRuleSet written(String hash) {
    return new ServerRuleSet(List.of(), ServerRulesState.OWN, null, hash);
  }

  /**
   * One registered folder.
   *
   * @param id the id
   * @param remoteName the name on the server
   * @param mirrored whether the user mirrors it
   * @param delegationId the share it belongs to, null for the user's own
   * @return the folder
   */
  private static EmailFolder folder(long id, String remoteName, boolean mirrored, Long delegationId) {
    EmailFolder folder = new EmailFolder();
    folder.setId(id);
    folder.setRemoteName(remoteName);
    folder.setSyncEnabled(mirrored);
    folder.setDelegationId(delegationId);
    return folder;
  }
}
