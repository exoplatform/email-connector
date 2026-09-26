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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.model.EmailFilter;
import org.exoplatform.emailConnector.model.FilterAction;
import org.exoplatform.emailConnector.model.HopRef;
import org.exoplatform.emailConnector.model.ReconcileReport;
import org.exoplatform.emailConnector.model.ServerRule;
import org.exoplatform.emailConnector.model.ServerRule.Condition;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities.ElementSupport;
import org.exoplatform.emailConnector.model.ServerRuleCapabilities.VocabularySource;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.rules.sieve.SieveRuleEngine;
import org.exoplatform.emailConnector.storage.EmailFilterStorage;
import org.exoplatform.services.listener.ListenerService;

/**
 * The drawer's one list of filters: where a filter runs is eXo's decision, from what the
 * mail server can do, and a filter that changes where it runs moves server first -- the
 * server never holds both halves nor neither, and eXo never acts on a mail twice.
 */
@ExtendWith(MockitoExtension.class)
public class EmailFilterRoutingTest {

  private static final String          USERNAME     = "alice";

  private static final long            CONNECTOR_ID = 5L;

  private static final long            NOW          = 1_790_000_000_000L;

  private static final Condition       FROM_ACME    = new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com");

  private static final Condition       BODY_INVOICE = new Condition("BODY", "CONTAINS", null, "invoice");

  private static final Condition       SIZE_BIG     = new Condition("MESSAGE_SIZE", "GT", null, "5000");

  @Mock
  private EmailFilterStorage           emailFilterStorage;

  @Mock
  private EmailServerRuleService       emailServerRuleService;

  @Mock
  private EmailBoxService              emailBoxService;

  @Mock
  private EmailFolderService           emailFolderService;

  @Mock
  private UserEmailSettingService      userEmailSettingService;

  @Mock
  private ListenerService              listenerService;

  @InjectMocks
  private EmailFilterService           service;

  private final Map<Long, EmailFilter> filters      = new TreeMap<>();

  private final AtomicLong             ids          = new AtomicLong(41);

  /**
   * A connected owner whose server runs every rule element, and a storage in a map.
   *
   * @throws Exception never
   */
  @BeforeEach
  void setUp() throws Exception {
    service.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId(String.valueOf(CONNECTOR_ID));
    lenient().when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(setting);
    lenient().when(userEmailSettingService.canConnect(CONNECTOR_ID, USERNAME)).thenReturn(true);
    lenient().when(emailServerRuleService.getCapabilities(USERNAME, null)).thenReturn(capabilities(Set.of()));
    lenient().when(emailServerRuleService.reconcileHops(eq(USERNAME), any(), any(), any(), anyBoolean(), anyBoolean(), anyBoolean()))
             .thenReturn(new ReconcileReport(List.of(), List.of(), null));
    lenient().when(emailServerRuleService.reconcileHops(eq(USERNAME), any(), anyBoolean(), anyBoolean(), anyBoolean()))
             .thenReturn(new ReconcileReport(List.of(), List.of(), null));
    fakeStorage();
  }

  /**
   * Clears the properties a test set.
   */
  @AfterEach
  void tearDown() {
    System.clearProperty(EmailFilterService.ENABLED_PROPERTY);
  }

  /**
   * The routing table: the server runs what it can run whole; with the server's conditions
   * and an action it cannot run, it marks and eXo acts; a condition only eXo reads, or a
   * server without rules, makes an eXo filter.
   */
  @Test
  void aFilterRunsWhereItsConditionsAndActionsCanRun() {
    ServerRuleCapabilities all = capabilities(Set.of());
    assertEquals(EmailFilter.KIND_SERVER, EmailFilterService.route(filter(List.of(FROM_ACME), action(FilterAction.STAR)), all));
    assertEquals(EmailFilter.KIND_SERVER,
                 EmailFilterService.route(filter(List.of(FROM_ACME, SIZE_BIG), move("CUSTOM:1"), action(FilterAction.MARK_READ)), all));
    assertEquals(EmailFilter.KIND_HOP,
                 EmailFilterService.route(filter(List.of(FROM_ACME), action(FilterAction.STAR), action(FilterAction.NOTIFY)), all));
    assertEquals(EmailFilter.KIND_HOP, EmailFilterService.route(filter(List.of(FROM_ACME), category()), all));
    assertEquals(EmailFilter.KIND_HOP,
                 EmailFilterService.route(filter(List.of(FROM_ACME), action(FilterAction.STAR)), capabilities(Set.of(FilterAction.STAR))),
                 "a flag the server cannot set is set by eXo");
    assertEquals(EmailFilter.KIND_EXO, EmailFilterService.route(filter(List.of(FROM_ACME, BODY_INVOICE), action(FilterAction.STAR)), all));
    assertEquals(EmailFilter.KIND_EXO,
                 EmailFilterService.route(filter(List.of(FROM_ACME), action(FilterAction.STAR)), capabilities(Set.of(ServerRuleCapabilities.FROM))),
                 "a condition the server cannot test");
    assertEquals(EmailFilter.KIND_EXO,
                 EmailFilterService.route(filter(List.of(FROM_ACME), action(FilterAction.NOTIFY)), capabilities(Set.of(ServerRuleCapabilities.TAG))),
                 "no keyword on the server: no hop");
    assertEquals(EmailFilter.KIND_EXO, EmailFilterService.route(filter(List.of(FROM_ACME), action(FilterAction.STAR)), null));
    assertEquals(EmailFilter.KIND_EXO,
                 EmailFilterService.route(filter(List.of(FROM_ACME), action(FilterAction.STAR)),
                                          ServerRuleCapabilities.unsupported("emailConnector.rules.unsupported", VocabularySource.FIXED)),
                 "the none engine");
  }

  /**
   * A new filter the server can run whole is a server rule: nothing is stored in eXo.
   *
   * @throws Exception never
   */
  @Test
  void aNewServerFilterIsWrittenOnTheServerOnly() throws Exception {
    EmailFilter saved = service.saveRouted(USERNAME, null, filter(List.of(FROM_ACME), action(FilterAction.STAR)), null, null, true, false);

    assertEquals(EmailFilter.KIND_SERVER, saved.getKind());
    assertNull(saved.getId());
    ArgumentCaptor<ServerRule> rule = ArgumentCaptor.forClass(ServerRule.class);
    verify(emailServerRuleService).saveRule(eq(USERNAME), isNull(), isNull(), rule.capture(), eq(false), eq(true));
    assertEquals(List.of(FROM_ACME), rule.getValue().conditions());
    assertEquals(List.of(FilterAction.STAR), rule.getValue().actions().stream().map(ServerRule.Action::type).toList());
    assertTrue(filters.isEmpty(), "eXo keeps no copy of a server rule");
  }

  /**
   * A server rule that gains an action only eXo runs becomes a hop in one server write:
   * the write that publishes its hop removes the rule it was; the eXo row is switched on
   * only then.
   *
   * @throws Exception never
   */
  @Test
  void aServerFilterThatGainsAnExoActionIsSwappedForItsHopInOneWrite() throws Exception {
    EmailFilter input = filter(List.of(FROM_ACME), action(FilterAction.STAR), action(FilterAction.NOTIFY));
    input.setStopProcessing(true);
    EmailFilter saved = service.saveRouted(USERNAME,
                                           null,
                                           input,
                                           "3",
                                           null,
                                           true,
                                           false);

    assertEquals(EmailFilter.KIND_HOP, saved.getKind());
    assertTrue(filters.get(saved.getId()).isEnabled(), "switched on once the server took it");
    List<HopRef> hops = swappedHops(null, "3", true);
    assertEquals(List.of("hop-" + saved.getId()), hops.stream().map(HopRef::ref).toList());
    assertFalse(hops.get(0).stop(), "a hop never stops the server's filters");
    assertTrue(filters.get(saved.getId()).isStopProcessing(), "eXo stops the eXo filters after it");
    verify(emailServerRuleService, never()).deleteRule(anyString(), any(), anyString(), anyBoolean());
    verify(emailServerRuleService, never()).saveRule(anyString(), any(), any(), any(), anyBoolean(), anyBoolean());
  }

  /**
   * When the server refuses the swap, the server rule stays and nothing is stored.
   *
   * @throws Exception never
   */
  @Test
  void aRefusedSwapLeavesTheServerRuleAndStoresNothing() throws Exception {
    when(emailServerRuleService.reconcileHops(eq(USERNAME), any(), any(), eq("3"), anyBoolean(), anyBoolean(), anyBoolean()))
                                                                                                                   .thenThrow(new ServerRuleUnavailableException("emailConnector.absence.serverUnreachable"));

    assertThrows(ServerRuleUnavailableException.class,
                 () -> service.saveRouted(USERNAME, null, filter(List.of(FROM_ACME), action(FilterAction.NOTIFY)), "3", null, true, false));

    assertTrue(filters.isEmpty(), "nothing stored: " + filters);
  }

  /**
   * A server rule that gains a condition only eXo reads is removed from the server first,
   * and its eXo row -- inserted switched off to get its id -- is switched on only after.
   *
   * @throws Exception never
   */
  @Test
  void aServerFilterThatGainsAnExoConditionLeavesTheServerFirst() throws Exception {
    EmailFilter saved = service.saveRouted(USERNAME,
                                           null,
                                           filter(List.of(FROM_ACME, BODY_INVOICE), action(FilterAction.STAR)),
                                           "3",
                                           null,
                                           false,
                                           false);

    assertEquals(EmailFilter.KIND_EXO, saved.getKind());
    InOrder order = inOrder(emailFilterStorage, emailServerRuleService);
    ArgumentCaptor<EmailFilter> rows = ArgumentCaptor.forClass(EmailFilter.class);
    order.verify(emailFilterStorage).save(eq(USERNAME), rows.capture(), any());
    order.verify(emailServerRuleService).deleteRule(USERNAME, null, "3", false);
    order.verify(emailFilterStorage).save(eq(USERNAME), rows.capture(), any());
    assertFalse(rows.getAllValues().get(0).isEnabled(), "off while the server still holds the rule");
    assertTrue(rows.getAllValues().get(1).isEnabled());
    assertTrue(filters.get(saved.getId()).isEnabled());
  }

  /**
   * A server rule someone already removed from the server is gone: the eXo filter is
   * created all the same.
   *
   * @throws Exception never
   */
  @Test
  void aServerRuleAlreadyGoneStillBecomesAnExoFilter() throws Exception {
    when(emailServerRuleService.deleteRule(USERNAME, null, "3", false)).thenThrow(new ObjectNotFoundException(SieveRuleEngine.RULE_NOT_FOUND));

    EmailFilter saved = service.saveRouted(USERNAME, null, filter(List.of(BODY_INVOICE), action(FilterAction.STAR)), "3", null, false, false);

    assertTrue(filters.get(saved.getId()).isEnabled());
  }

  /**
   * An eXo filter that the server can now run whole moves to the server: it is switched
   * off in eXo before the server takes it, so eXo never acts on the same mail, and
   * deleted once the server holds it.
   *
   * @throws Exception never
   */
  @Test
  void anExoFilterTheServerCanRunMovesToTheServer() throws Exception {
    EmailFilter existing = stored(rule(EmailFilter.KIND_EXO, List.of(BODY_INVOICE), action(FilterAction.STAR)));

    EmailFilter saved = service.saveRouted(USERNAME,
                                           null,
                                           filter(List.of(FROM_ACME), action(FilterAction.STAR)),
                                           null,
                                           existing.getId(),
                                           true,
                                           false);

    assertEquals(EmailFilter.KIND_SERVER, saved.getKind());
    InOrder order = inOrder(emailFilterStorage, emailServerRuleService);
    ArgumentCaptor<EmailFilter> off = ArgumentCaptor.forClass(EmailFilter.class);
    order.verify(emailFilterStorage).save(eq(USERNAME), off.capture(), any());
    order.verify(emailServerRuleService).saveRule(eq(USERNAME), isNull(), isNull(), any(), eq(false), eq(true));
    order.verify(emailFilterStorage).delete(existing.getId(), USERNAME);
    assertFalse(off.getValue().isEnabled(), "switched off before the server runs it");
    assertTrue(filters.isEmpty());
  }

  /**
   * When the server refuses the rule an eXo filter becomes, the eXo filter is put back as
   * it was, switched on.
   *
   * @throws Exception never
   */
  @Test
  void aRefusedMoveToTheServerKeepsTheExoFilter() throws Exception {
    EmailFilter existing = stored(rule(EmailFilter.KIND_EXO, List.of(BODY_INVOICE), action(FilterAction.STAR)));
    when(emailServerRuleService.saveRule(eq(USERNAME), any(), any(), any(), anyBoolean(), anyBoolean()))
                                                                                                     .thenThrow(new ServerRuleUnavailableException("emailConnector.absence.serverUnreachable"));

    assertThrows(ServerRuleUnavailableException.class,
                 () -> service.saveRouted(USERNAME, null, filter(List.of(FROM_ACME), action(FilterAction.STAR)), null, existing.getId(), true, false));

    assertTrue(filters.get(existing.getId()).isEnabled(), "back as it was");
    verify(emailFilterStorage, never()).delete(anyLong(), anyString());
  }

  /**
   * A hop filter that loses its eXo-only action becomes a server rule in one server write:
   * the write that adds the rule removes the hop; the eXo row goes after.
   *
   * @throws Exception never
   */
  @Test
  void aHopFilterTheServerCanRunIsSwappedForTheRuleInOneWrite() throws Exception {
    EmailFilter other = stored(rule(EmailFilter.KIND_HOP, List.of(FROM_ACME), action(FilterAction.NOTIFY)));
    EmailFilter existing = stored(rule(EmailFilter.KIND_HOP, List.of(FROM_ACME), action(FilterAction.NOTIFY)));

    EmailFilter saved = service.saveRouted(USERNAME,
                                           null,
                                           filter(List.of(FROM_ACME), action(FilterAction.STAR)),
                                           null,
                                           existing.getId(),
                                           true,
                                           false);

    assertEquals(EmailFilter.KIND_SERVER, saved.getKind());
    ArgumentCaptor<ServerRule> added = ArgumentCaptor.forClass(ServerRule.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<HopRef>> hops = ArgumentCaptor.forClass(List.class);
    verify(emailServerRuleService).reconcileHops(eq(USERNAME), hops.capture(), added.capture(), isNull(), eq(false), eq(true), eq(false));
    assertEquals(List.of(other.getServerRuleRef()), hops.getValue().stream().map(HopRef::ref).toList(), "its own hop left out");
    assertEquals(List.of(FilterAction.STAR), added.getValue().actions().stream().map(ServerRule.Action::type).toList());
    verify(emailServerRuleService, never()).saveRule(anyString(), any(), any(), any(), anyBoolean(), anyBoolean());
    assertFalse(filters.containsKey(existing.getId()));
  }

  /**
   * An eXo filter that stays one is saved in place, the server never asked about rules it
   * does not hold.
   *
   * @throws Exception never
   */
  @Test
  void anExoFilterThatStaysOneIsReplacedInPlace() throws Exception {
    EmailFilter existing = stored(rule(EmailFilter.KIND_EXO, List.of(BODY_INVOICE), action(FilterAction.STAR)));

    EmailFilter saved = service.saveRouted(USERNAME,
                                           null,
                                           filter(List.of(BODY_INVOICE), action(FilterAction.MARK_READ)),
                                           null,
                                           existing.getId(),
                                           false,
                                           false);

    assertEquals(existing.getId(), saved.getId());
    assertEquals(EmailFilter.KIND_EXO, saved.getKind());
    verify(emailServerRuleService, never()).getCapabilities(anyString(), any());
    verify(emailServerRuleService, never()).saveRule(anyString(), any(), any(), any(), anyBoolean(), anyBoolean());
  }

  /**
   * Server rules switched off on the deployment leave every filter to eXo.
   *
   * @throws Exception never
   */
  @Test
  void withoutServerRulesEveryFilterRunsInExo() throws Exception {
    when(emailServerRuleService.getCapabilities(USERNAME, null)).thenThrow(new ObjectNotFoundException(EmailServerRuleService.DISABLED));

    EmailFilter saved = service.saveRouted(USERNAME, null, filter(List.of(FROM_ACME), action(FilterAction.STAR)), null, null, false, false);

    assertEquals(EmailFilter.KIND_EXO, saved.getKind());
    assertTrue(filters.get(saved.getId()).isEnabled());
  }

  /**
   * Once the server dropped the rule a filter was, the eXo row is its only copy: when it
   * cannot be switched on, it stays, switched off, never deleted.
   *
   * @throws Exception never
   */
  @Test
  void aStorageFailureAfterTheSwapKeepsTheRowSwitchedOff() throws Exception {
    AtomicLong saves = new AtomicLong();
    when(emailFilterStorage.save(eq(USERNAME), any(), any())).thenAnswer(invocation -> {
      if (saves.incrementAndGet() == 2) {
        throw new IllegalStateException("database down");
      }
      EmailFilter filter = copy(invocation.getArgument(1));
      filter.setId(ids.incrementAndGet());
      filters.put(filter.getId(), filter);
      return copy(filter);
    });

    assertThrows(IllegalStateException.class,
                 () -> service.saveRouted(USERNAME, null, filter(List.of(FROM_ACME), action(FilterAction.NOTIFY)), "3", null, true, false));

    assertEquals(1, filters.size(), "the row stays: " + filters);
    assertFalse(filters.values().iterator().next().isEnabled());
    verify(emailFilterStorage, never()).delete(anyLong(), anyString());
  }

  /**
   * A mail server that cannot be reached leaves a new filter to eXo, and an eXo filter
   * is edited without it; a server filter, which the server must remove, waits for it.
   *
   * @throws Exception never
   */
  @Test
  void anUnreachableServerLeavesNewFiltersToExo() throws Exception {
    when(emailServerRuleService.getCapabilities(USERNAME, null)).thenThrow(new ServerRuleUnavailableException("emailConnector.absence.serverUnreachable"));

    EmailFilter saved = service.saveRouted(USERNAME, null, filter(List.of(FROM_ACME), action(FilterAction.NOTIFY)), null, null, false, false);
    assertEquals(EmailFilter.KIND_EXO, saved.getKind());
    EmailFilter edited = service.saveRouted(USERNAME, null, filter(List.of(FROM_ACME), action(FilterAction.STAR)), null, saved.getId(), false, false);
    assertEquals(EmailFilter.KIND_EXO, edited.getKind());

    assertThrows(ServerRuleUnavailableException.class,
                 () -> service.saveRouted(USERNAME, null, filter(List.of(BODY_INVOICE), action(FilterAction.STAR)), "3", null, false, false));
    verify(emailServerRuleService, never()).saveRule(anyString(), any(), any(), any(), anyBoolean(), anyBoolean());
  }

  /**
   * The entry point is the owner's own: a shared mailbox, or a reference and an id at
   * once, are refused.
   */
  @Test
  void theEntryPointRefusesASharedMailboxAndTwoOrigins() {
    EmailFilter filter = filter(List.of(FROM_ACME), action(FilterAction.STAR));
    assertThrows(IllegalAccessException.class, () -> service.saveRouted(USERNAME, 7L, filter, null, null, true, false));
    assertThrows(IllegalArgumentException.class, () -> service.saveRouted(USERNAME, null, filter, "3", 42L, true, false));
  }

  /**
   * The hops of the one swap write, and its checks.
   *
   * @param added the rule it was expected to add, or null
   * @param dropped the rule it was expected to remove
   * @param publishing whether it was expected to publish
   * @return the hops
   * @throws Exception never
   */
  @SuppressWarnings("unchecked")
  private List<HopRef> swappedHops(ServerRule added, String dropped, boolean publishing) throws Exception {
    ArgumentCaptor<List<HopRef>> hops = ArgumentCaptor.forClass(List.class);
    verify(emailServerRuleService).reconcileHops(eq(USERNAME),
                                                 hops.capture(),
                                                 added == null ? isNull() : eq(added),
                                                 eq(dropped),
                                                 eq(false),
                                                 eq(true),
                                                 eq(publishing));
    return hops.getValue();
  }

  /**
   * What a server can do: every rule element, but the given ones.
   *
   * @param unsupported the elements it cannot run
   * @return the capabilities
   */
  private static ServerRuleCapabilities capabilities(Set<String> unsupported) {
    Map<String, ElementSupport> elements = new HashMap<>();
    ServerRuleCapabilities.ELEMENTS.forEach(element -> elements.put(element, new ElementSupport(!unsupported.contains(element), null)));
    return new ServerRuleCapabilities(true, null, true, false, VocabularySource.FIXED, elements);
  }

  /**
   * A filter as the form sends it: enabled, all conditions, no kind.
   *
   * @param conditions its conditions
   * @param actions its actions
   * @return the filter
   */
  private static EmailFilter filter(List<Condition> conditions, FilterAction... actions) {
    EmailFilter filter = new EmailFilter();
    filter.setName("Acme");
    filter.setEnabled(true);
    filter.setMatchAll(true);
    filter.setConditions(conditions);
    filter.setActions(List.of(actions));
    return filter;
  }

  /**
   * A stored filter of a kind.
   *
   * @param kind its kind
   * @param conditions its conditions
   * @param actions its actions
   * @return the filter
   */
  private static EmailFilter rule(String kind, List<Condition> conditions, FilterAction... actions) {
    EmailFilter filter = filter(conditions, actions);
    filter.setKind(kind);
    filter.setMailboxScope(EmailFilter.SCOPE_OWN);
    return filter;
  }

  /**
   * An action without parameters.
   *
   * @param type its type
   * @return the action
   */
  private static FilterAction action(String type) {
    return new FilterAction(type, null, null, null, null, null, null);
  }

  /**
   * A move.
   *
   * @param key the folder
   * @return the action
   */
  private static FilterAction move(String key) {
    return new FilterAction(FilterAction.MOVE_TO_FOLDER, key, null, null, null, null, null);
  }

  /**
   * A category.
   *
   * @return the action
   */
  private static FilterAction category() {
    return new FilterAction(FilterAction.ADD_CATEGORY, null, 9L, null, null, null, null);
  }

  /**
   * Stores a filter as a save would, a hop named after its id.
   *
   * @param filter the filter
   * @return the filter as stored
   */
  private EmailFilter stored(EmailFilter filter) {
    EmailFilter copy = copy(filter);
    copy.setId(ids.incrementAndGet());
    copy.setPosition(filters.size());
    if (EmailFilter.KIND_HOP.equals(copy.getKind())) {
      EmailFilterService.withHopNames(copy);
    }
    filters.put(copy.getId(), copy);
    return copy(copy);
  }

  /**
   * The storage, as a map behind the mock.
   */
  private void fakeStorage() {
    lenient().when(emailFilterStorage.getFilters(USERNAME)).thenAnswer(invocation -> new ArrayList<>(filters.values().stream().map(this::copy).toList()));
    lenient().when(emailFilterStorage.getFilter(anyLong(), eq(USERNAME)))
             .thenAnswer(invocation -> Optional.ofNullable(filters.get(invocation.<Long> getArgument(0))).map(this::copy));
    lenient().when(emailFilterStorage.nextPosition(USERNAME)).thenAnswer(invocation -> filters.size());
    lenient().when(emailFilterStorage.save(eq(USERNAME), any(), any())).thenAnswer(invocation -> {
      EmailFilter filter = copy(invocation.getArgument(1));
      if (filter.getId() == null) {
        filter.setId(ids.incrementAndGet());
      }
      filters.put(filter.getId(), filter);
      return copy(filter);
    });
    lenient().when(emailFilterStorage.delete(anyLong(), eq(USERNAME)))
             .thenAnswer(invocation -> filters.remove(invocation.<Long> getArgument(0)) != null);
  }

  /**
   * A copy of a filter, so the fake storage never shares an instance with the service.
   *
   * @param filter the filter
   * @return the copy
   */
  private EmailFilter copy(EmailFilter filter) {
    return new EmailFilter(filter.getId(),
                           filter.getName(),
                           filter.isEnabled(),
                           filter.getPosition(),
                           filter.getKind(),
                           filter.getMailboxScope(),
                           filter.isMatchAll(),
                           filter.getConditions(),
                           filter.getActions(),
                           filter.isStopProcessing(),
                           filter.getTagKeyword(),
                           filter.getServerRuleRef(),
                           filter.getAgentNameId(),
                           filter.getMatchCount(),
                           filter.getLastMatchDate(),
                           filter.getLastError(),
                           filter.getActiveSince(),
                           filter.getCreatedDate(),
                           filter.getUpdatedDate());
  }
}
