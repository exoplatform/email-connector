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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.ListResourceBundle;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.event.NewInboxMailEvent.InboxMail;
import org.exoplatform.emailConnector.exception.ServerRuleConflictException;
import org.exoplatform.emailConnector.exception.ServerRuleUnavailableException;
import org.exoplatform.emailConnector.exception.ServerRuleUnsupportedException;
import org.exoplatform.emailConnector.model.AppliedAction;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailFilter;
import org.exoplatform.emailConnector.model.EmailFilterMatch;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.FilterAction;
import org.exoplatform.emailConnector.model.FilterPreview;
import org.exoplatform.emailConnector.model.HopRef;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ReconcileReport;
import org.exoplatform.emailConnector.model.ServerRule.Condition;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.plugin.EmailFilterAgentHandler;
import org.exoplatform.emailConnector.plugin.EmailFilterProposalProvider;
import org.exoplatform.emailConnector.service.filters.FilterRunContext;
import org.exoplatform.emailConnector.storage.EmailFilterStorage;
import org.exoplatform.emailConnector.storage.EmailFilterStorage.OwnedMatch;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.resources.ResourceBundleService;

/**
 * The eXo rules service: the combined save with the server first, the hop's names from
 * the rule's id and the whole set of hops reconciled, the run on new mail scoped to the
 * owner's own inbox, the keyword as trigger and the conditions as match, the post-actions
 * batched and deferred for the assistant, the at-most-once match, the caps, the undo and
 * the validation. The storage is an in-memory fake behind the mock, so each test reads
 * the state the service left.
 */
@ExtendWith(MockitoExtension.class)
public class EmailFilterServiceTest {

  private static final String          USERNAME     = "alice";

  private static final long            CONNECTOR_ID = 5L;

  private static final long            NOW          = 1_790_000_000_000L;

  private static final Condition       FROM_ACME    = new Condition("FROM", "MATCHES_DOMAIN", null, "acme.com");

  private static final Condition       IMPORTANT    = new Condition("CATEGORY", "EQUALS", null, "emailImportantCategory");

  private static final long            IMPORTANT_ID = 17L;

  private static final long            NOTIFICATION_ID = 18L;

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

  @Mock
  private ObjectProvider<EmailFilterAgentHandler> agentHandlers;

  @Mock
  private ObjectProvider<EmailFilterProposalProvider> proposalProviders;

  @Mock
  private EmailFilterProposalProvider  proposalProvider;

  @Mock
  private SettingService               settingService;

  @Mock
  private ResourceBundleService        resourceBundleService;

  @Spy
  @InjectMocks
  private EmailFilterService           service;

  private final Map<Long, EmailFilter> filters      = new TreeMap<>();

  /** The owners' settings, as one map behind the mock: {@code context/scope/key} to value. */
  private final Map<String, String>    settings     = new ConcurrentHashMap<>();

  private final Map<Long, EmailFilterMatch> matches = new TreeMap<>();

  private final AtomicLong             ids          = new AtomicLong(41);

  /**
   * A connected owner, a storage in two maps, a mailbox whose methods succeed, and no
   * notification actually sent.
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
    lenient().doNothing().when(service).notifyOwner(anyString(), anyString(), anyInt(), any());
    lenient().doNothing().when(service).notifyOwner(anyString(), anyString(), anyInt(), any(), any(), any());
    fakeStorage();
    fakeSettings();
    lenient().when(agentHandlers.stream()).thenAnswer(invocation -> Stream.of(new EmailFilterAgentHandler() {
    }));
    lenient().when(proposalProviders.orderedStream()).thenAnswer(invocation -> Stream.of(proposalProvider));
    lenient().when(emailBoxService.getDefaultEmailCategoryId(EmailFilterService.SEED_IMPORTANT_CATEGORY)).thenReturn(IMPORTANT_ID);
    lenient().doReturn(Locale.ENGLISH).when(service).seedLocale(USERNAME);
  }

  /**
   * A deployment where nothing runs the assistant: no AI add-on, or its profile off.
   */
  private void noAgentHandler() {
    when(agentHandlers.stream()).thenAnswer(invocation -> Stream.empty());
  }

  /**
   * Clears the properties a test set.
   */
  @AfterEach
  void tearDown() {
    System.clearProperty(EmailFilterService.AGENT_ENABLED_PROPERTY);
    System.clearProperty(EmailFilterService.MAX_PENDING_PROPERTY);
    System.clearProperty(EmailFilterService.DAILY_CAP_PROPERTY);
    System.clearProperty(EmailFilterService.ENABLED_PROPERTY);
    System.clearProperty(EmailFilterService.SEED_IMPORTANT_PROPERTY);
  }

  /**
   * A rule that also runs at delivery: its server half is written first, named after the
   * rule's id -- {@code hop-<id>}, keyword {@code exo-filter-<id>} -- beside the owner's
   * other live hops, and the rule is enabled only once the server took it.
   *
   * @throws Exception never
   */
  @Test
  void aHopRuleIsPublishedUnderItsIdBesideTheOtherHops() throws Exception {
    EmailFilter existing = stored(hop("Invoices", FROM_ACME));
    when(emailServerRuleService.reconcileHops(eq(USERNAME), any(), eq(false), eq(true), eq(true))).thenReturn(report());

    EmailFilter created = service.createFilter(USERNAME, null, hop("Acme", FROM_ACME), true, false);

    long id = created.getId();
    assertEquals("hop-" + id, created.getServerRuleRef());
    assertEquals("exo-filter-" + id, created.getTagKeyword());
    assertTrue(created.isEnabled(), "enabled once the server took its half");
    List<HopRef> hops = publishedHops();
    assertEquals(List.of(existing.getServerRuleRef(), "hop-" + id), hops.stream().map(HopRef::ref).toList(), "every live hop");
    HopRef hop = hops.get(1);
    assertEquals("exo-filter-" + id, hop.keyword());
    assertEquals(List.of(FROM_ACME), hop.conditions(), "the rule's own conditions");
    assertEquals("Acme", hop.name());
  }

  /**
   * The combined save is server first: when the server refuses the new rule's half,
   * nothing is left in eXo -- the row inserted switched off to get its id is deleted,
   * and it was never enabled.
   *
   * @throws Exception never
   */
  @Test
  void aServerRefusalStoresNothingOfANewHopRule() throws Exception {
    when(emailServerRuleService.reconcileHops(eq(USERNAME), any(), anyBoolean(), anyBoolean(), eq(true)))
                                                                                                             .thenThrow(new ServerRuleUnavailableException("emailConnector.absence.serverUnreachable"));

    assertThrows(ServerRuleUnavailableException.class, () -> service.createFilter(USERNAME, null, hop("Acme", FROM_ACME), true, false));

    assertTrue(filters.isEmpty(), "nothing stored: " + filters);
    verify(emailFilterStorage).delete(anyLong(), eq(USERNAME));
    ArgumentCaptor<EmailFilter> saved = ArgumentCaptor.forClass(EmailFilter.class);
    verify(emailFilterStorage).save(eq(USERNAME), saved.capture(), any());
    assertFalse(saved.getValue().isEnabled(), "the one row ever written was switched off");
  }

  /**
   * Changing a hop rule writes the server first; a conflict leaves the stored rule as it
   * was.
   *
   * @throws Exception never
   */
  @Test
  void aServerConflictLeavesAChangedHopRuleAsItWas() throws Exception {
    EmailFilter existing = stored(hop("Acme", FROM_ACME));
    when(emailServerRuleService.reconcileHops(eq(USERNAME), any(), anyBoolean(), anyBoolean(), eq(true)))
                                                                                                             .thenThrow(new ServerRuleConflictException("emailConnector.absence.modifiedOutside",
                                                                                                                                                        null));
    EmailFilter changed = hop("Acme and friends", new Condition("FROM", "MATCHES_DOMAIN", null, "friends.org"));

    assertThrows(ServerRuleConflictException.class,
                 () -> service.updateFilter(USERNAME, null, existing.getId(), changed, false, false));

    verify(emailFilterStorage, never()).save(any(), any(), any());
    assertEquals("Acme", filters.get(existing.getId()).getName());
  }

  /**
   * A hop rule turned into one eXo alone runs removes its hop -- a write that needs no
   * consent -- and then drops its names; deleting one removes it first too, and a
   * server refusal keeps the rule.
   *
   * @throws Exception never
   */
  @Test
  void aHopLeavesTheServerBeforeTheRuleChangesOrGoes() throws Exception {
    EmailFilter first = stored(hop("Acme", FROM_ACME));
    EmailFilter second = stored(hop("Other", FROM_ACME));
    when(emailServerRuleService.reconcileHops(eq(USERNAME), any(), eq(false), eq(false), eq(false))).thenReturn(report());
    EmailFilter exoOnly = rule("Acme", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR)));

    EmailFilter updated = service.updateFilter(USERNAME, null, first.getId(), exoOnly, false, false);

    assertNull(updated.getTagKeyword());
    assertNull(updated.getServerRuleRef());
    assertEquals(List.of(second.getServerRuleRef()), publishedHops().stream().map(HopRef::ref).toList(), "the other hop stays");

    when(emailServerRuleService.reconcileHops(eq(USERNAME), any(), eq(false), eq(false), eq(false)))
                                                                                                      .thenThrow(new ServerRuleUnavailableException("emailConnector.absence.serverUnreachable"));
    assertThrows(ServerRuleUnavailableException.class, () -> service.deleteFilter(USERNAME, null, second.getId(), false));
    assertTrue(filters.containsKey(second.getId()), "kept while its hop is on the server");
  }

  /**
   * A connector that holds no rules holds no hop to remove: the eXo rule is deleted
   * anyway.
   *
   * @throws Exception never
   */
  @Test
  void aHopRuleIsDeletedWhereTheServerHoldsNoRules() throws Exception {
    EmailFilter existing = stored(hop("Acme", FROM_ACME));
    when(emailServerRuleService.reconcileHops(eq(USERNAME), any(), anyBoolean(), anyBoolean(), eq(false)))
                                                                                                              .thenThrow(new ServerRuleUnsupportedException("emailConnector.rules.unsupported"));

    service.deleteFilter(USERNAME, null, existing.getId(), false);

    assertFalse(filters.containsKey(existing.getId()));
  }

  /**
   * The AI add-on's side hears of a rule its owner saves, with the rule as it was and as
   * saved, and of a rule its owner deletes (EXO-90956): a rule its owner switched off
   * runs nothing without asking any more. A failure there never fails the write.
   *
   * @throws Exception never
   */
  @Test
  void theAiSideHearsOfTheRulesTheOwnerSavesAndDeletes() throws Exception {
    EmailFilter existing = stored(rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));
    EmailFilter off = rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR)));
    off.setEnabled(false);

    EmailFilter saved = service.updateFilter(USERNAME, null, existing.getId(), off, false, false);

    ArgumentCaptor<EmailFilter> previous = ArgumentCaptor.forClass(EmailFilter.class);
    ArgumentCaptor<EmailFilter> after = ArgumentCaptor.forClass(EmailFilter.class);
    verify(proposalProvider).onFilterSaved(eq(USERNAME), previous.capture(), after.capture());
    assertTrue(previous.getValue().isEnabled(), "the rule as it was");
    assertFalse(after.getValue().isEnabled(), "the rule as its owner saved it");
    assertEquals(saved.getId(), after.getValue().getId());

    doThrow(new IllegalStateException("down")).when(proposalProvider).onFilterDeleted(USERNAME, existing.getId());
    service.deleteFilter(USERNAME, null, existing.getId(), false);
    verify(proposalProvider).onFilterDeleted(USERNAME, existing.getId());
    assertFalse(filters.containsKey(existing.getId()), "the write stands");
  }

  /**
   * A rule eXo switches off because it can no longer read it is not saved by its owner:
   * the AI side hears nothing of it, so the rule keeps its standing approvals, as a
   * suspended scheduled agent does (EXO-90956, Q6). Mutant: the switch-off through the
   * owner's save path, which tells it.
   */
  @Test
  void aRuleEXoSwitchesOffIsNotToldAsTheOwnersSave() {
    stored(rule("Broken", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of()));
    givenNewMail(mail(1L, "boss@acme.com", "Hello"));

    service.applyToNewMail(USERNAME, List.of(inbox(1L)), new FilterRunContext(EmailFilter.SCOPE_OWN, MailFolder.INBOX));

    verify(emailFilterStorage).disableWithError(anyLong(), eq(USERNAME), eq(EmailFilterService.UNREADABLE), any());
    verify(proposalProvider, never()).onFilterSaved(any(), any(), any());
  }

  /**
   * The matches the retention deletes are told to the AI side, their decided
   * suggestions going with them (EXO-90956, Q9); nothing deleted, nothing told.
   */
  @Test
  void thePrunedMatchesAreTold() {
    stored(rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));
    when(emailFilterStorage.getMatchIdsOlderThan(eq(USERNAME), any())).thenReturn(List.of(4L, 5L));
    givenNewMail(mail(1L, "boss@acme.com", "Hello"));

    service.applyToNewMail(USERNAME, List.of(inbox(1L)), new FilterRunContext(EmailFilter.SCOPE_OWN, MailFolder.INBOX));

    verify(emailFilterStorage).pruneMatches(eq(USERNAME), any());
    verify(proposalProvider).onMatchesPurged(USERNAME, List.of(4L, 5L));

    when(emailFilterStorage.getMatchIdsOlderThan(eq(USERNAME), any())).thenReturn(List.of());
    service.applyToNewMail(USERNAME, List.of(inbox(1L)), new FilterRunContext(EmailFilter.SCOPE_OWN, MailFolder.INBOX));
    verify(proposalProvider).onMatchesPurged(eq(USERNAME), any());
  }

  /**
   * A disconnected mailbox is told to the AI side; a blank user tells nothing.
   */
  @Test
  void aDisconnectedMailboxIsTold() {
    service.onMailboxDisconnected(USERNAME);
    verify(proposalProvider).onMailboxDisconnected(USERNAME);
    service.onMailboxDisconnected(" ");
    verify(proposalProvider).onMailboxDisconnected(any());
  }

  /**
   * Rules run on the owner's own inbox and nowhere else: a pass over another mailbox runs
   * nothing, and a rule scoped to a shared mailbox never runs on the owner's.
   */
  @Test
  void rulesRunOnTheOwnersOwnInboxOnly() {
    stored(rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));
    EmailFilter shared = rule("Shared", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.MARK_READ)));
    shared.setMailboxScope("DELEGATION:9");
    stored(shared);
    givenNewMail(mail(1L, "boss@acme.com", "Hello"));

    assertEquals(Set.of(), service.applyToNewMail(USERNAME, List.of(inbox(1L)), new FilterRunContext("DELEGATION:9", MailFolder.INBOX)));
    assertEquals(Set.of(), service.applyToNewMail(USERNAME, List.of(inbox(1L)), new FilterRunContext(EmailFilter.SCOPE_OWN, MailFolder.SENT)));
    assertTrue(matches.isEmpty(), "nothing ran on another mailbox");

    service.applyToNewMail(USERNAME, List.of(inbox(1L)), FilterRunContext.OWN_INBOX);

    assertEquals(1, matches.size(), "the owner's rule ran, the shared mailbox's did not: " + matches);
    assertEquals("Star", filters.get(matches.values().iterator().next().getFilterId()).getName());
  }

  /**
   * A hop rule is triggered by its keyword and still checks its conditions: no keyword,
   * no run; the keyword on a mail its conditions do not match -- set by someone else --
   * no run either.
   *
   * @throws Exception never
   */
  @Test
  void theKeywordTriggersAndTheConditionsMatch() throws Exception {
    EmailFilter hop = stored(hop("Acme", FROM_ACME));
    String keyword = hop.getTagKeyword();
    givenNewMail(mail(1L, "boss@acme.com", "Invoice"), mail(2L, "boss@acme.com", "Invoice"), mail(3L, "mallory@evil.org", "Invoice"));

    service.applyToNewMail(USERNAME,
                           List.of(inbox(1L), inbox(2L, keyword), inbox(3L, keyword.toUpperCase())),
                           FilterRunContext.OWN_INBOX);

    assertEquals(List.of(2L), matches.values().stream().map(EmailFilterMatch::getMailRemoteId).toList());
    verify(emailBoxService).updateEmailStarredStatus(List.of(2L), USERNAME, MailFolder.INBOX, true, true);
  }

  /**
   * The post-actions run in the pass, batched per rule -- one move for all its mails --
   * with what undoing them needs; a rule that files a mail stops the rules after it, and
   * the filed mails are handed back to be left out of the announcement.
   *
   * @throws Exception never
   */
  @Test
  void postActionsAreBatchedAndFilingStopsTheRulesAfter() throws Exception {
    EmailFilter move = stored(rule("File", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.MARK_READ), move("CUSTOM:3"))));
    stored(rule("Later", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));
    givenNewMail(mail(1L, "a@acme.com", "One"), mail(2L, "b@acme.com", "Two"));

    Set<Long> filed = service.applyToNewMail(USERNAME, List.of(inbox(1L), inbox(2L)), FilterRunContext.OWN_INBOX);

    assertEquals(Set.of(1L, 2L), filed);
    verify(emailBoxService, times(1)).moveToFolder(List.of(1L, 2L), USERNAME, MailFolder.INBOX, "CUSTOM:3");
    verify(emailBoxService, times(1)).updateEmailReadStatus(List.of(1L, 2L), USERNAME, MailFolder.INBOX, true, true);
    verify(emailBoxService, never()).updateEmailStarredStatus(any(), any(), any(), anyBoolean(), anyBoolean());
    assertEquals(2, matches.size(), "the later rule never matched");
    EmailFilterMatch match = matches.values().iterator().next();
    assertEquals(EmailFilterMatch.POST_DONE, match.getPostActionsState());
    AppliedAction moved = match.getActions().get(1);
    assertEquals(FilterAction.MOVE_TO_FOLDER, moved.type());
    assertEquals("CUSTOM:3", moved.folderKey());
    assertEquals(MailFolder.INBOX, moved.originFolder());
    assertEquals(Boolean.FALSE, match.getActions().get(0).wasRead());
    verify(emailFilterStorage).addMatches(move.getId(), USERNAME, 2L, new Date(NOW));
  }

  /**
   * A rule's notification names the mail a click opens: the most recent of the batch by
   * its inbox UID while it stays there; once a rule filed it, the folder it went to instead,
   * its inbox UID naming nothing any more.
   *
   * @throws Exception never
   */
  @Test
  void theNotificationNamesTheMostRecentMailOrWhereItWasFiled() throws Exception {
    Condition fromEvil = new Condition("FROM", "MATCHES_DOMAIN", null, "evil.org");
    stored(rule("Tell", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.NOTIFY))));
    stored(rule("Bin", EmailFilter.KIND_EXO, List.of(fromEvil), List.of(action(FilterAction.NOTIFY), action(FilterAction.MARK_JUNK))));
    givenNewMail(mail(1L, "a@acme.com", "One"), mail(3L, "b@acme.com", "Three"), mail(2L, "c@acme.com", "Two"),
                 mail(4L, "mallory@evil.org", "Spam"));

    service.applyToNewMail(USERNAME, List.of(inbox(1L), inbox(3L), inbox(2L), inbox(4L)), FilterRunContext.OWN_INBOX);

    verify(service).notifyOwner(USERNAME, "Tell", 3, null, 3L, null);
    verify(service).notifyOwner(USERNAME, "Bin", 1, null, null, MailFolder.JUNK);
  }

  /**
   * The sync also caches mail that is not new -- the whole window after a reset or a
   * reconnection: a rule acts at sync only on mail that arrived since it became active,
   * give or take the clocks' grace -- whatever edit or reorder came later.
   *
   * @throws Exception never
   */
  @Test
  void theSyncActsOnlyOnMailNewerThanTheRule() throws Exception {
    EmailFilter star = rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR)));
    star.setActiveSince(NOW);
    star.setUpdatedDate(NOW + 3_600_000L);
    stored(star);
    Email old = mail(1L, "a@acme.com", "Old");
    old.setReceivedDate(new Date(NOW - EmailFilterService.RECEIVED_GRACE_MS - 1));
    Email skewed = mail(2L, "a@acme.com", "Just before the save");
    skewed.setReceivedDate(new Date(NOW - 60_000L));
    givenNewMail(old, skewed);

    service.applyToNewMail(USERNAME, List.of(inbox(1L), inbox(2L)), FilterRunContext.OWN_INBOX);

    assertEquals(List.of(2L), matches.values().stream().map(EmailFilterMatch::getMailRemoteId).toList());
  }

  /**
   * A rule becomes active when it is created or switched back on; an edit keeps its
   * start, so a mailbox that syncs late still gets the mail delivered before the edit.
   *
   * @throws Exception never
   */
  @Test
  void onlyCreationAndReEnablingMoveTheStart() throws Exception {
    EmailFilter created = service.createFilter(USERNAME,
                                               null,
                                               rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))),
                                               false,
                                               false);
    assertEquals(NOW, created.getActiveSince());

    service.setClock(Clock.fixed(Instant.ofEpochMilli(NOW + 5_000_000L), ZoneOffset.UTC));
    EmailFilter renamed = rule("Star it", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR)));
    assertEquals(NOW, service.updateFilter(USERNAME, null, created.getId(), renamed, false, false).getActiveSince(), "an edit keeps it");

    renamed.setEnabled(false);
    service.updateFilter(USERNAME, null, created.getId(), renamed, false, false);
    renamed.setEnabled(true);
    assertEquals(NOW + 5_000_000L,
                 service.updateFilter(USERNAME, null, created.getId(), renamed, false, false).getActiveSince(),
                 "switched back on, it starts again");
  }

  /**
   * Undo of a rule that starred and filed a mail finds the star where the filing put the
   * mail; and when one action cannot be undone yet, the ones that were are recorded
   * before the refusal is reported.
   *
   * @throws Exception never
   */
  @Test
  void undoAllRecordsWhatItUndidBeforeItReportsTheRest() throws Exception {
    EmailFilterMatch match = storedMatch(new AppliedAction(FilterAction.STAR, true, null, null, null, null, null, false, null, false),
                                         new AppliedAction(FilterAction.MARK_JUNK,
                                                           true,
                                                           null,
                                                           MailFolder.JUNK,
                                                           MailFolder.INBOX,
                                                           null,
                                                           null,
                                                           null,
                                                           null,
                                                           false));
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, match.getMailHeaderId(), MailFolder.JUNK)).thenReturn(mail(9L, "a@acme.com", "x"))
                                                                                                           .thenReturn(null);

    IllegalArgumentException notYet = assertThrows(IllegalArgumentException.class, () -> service.undo(USERNAME, null, match.getId(), null));

    assertEquals(EmailFilterService.UNDO_NOT_YET, notYet.getMessage(), "Junk's refresh has not cached the mail yet");
    verify(emailBoxService).updateEmailStarredStatus(List.of(9L), USERNAME, MailFolder.JUNK, false, true);
    List<AppliedAction> recorded = matches.get(match.getId()).getActions();
    assertTrue(recorded.get(0).undone(), "the star was undone, and it is recorded");
    assertFalse(recorded.get(1).undone(), "the filing was not");
  }

  /**
   * A mail a rule already handled is not handled again: the match is refused by the key,
   * and nothing is applied.
   *
   * @throws Exception never
   */
  @Test
  void aMailIsHandledOncePerRule() throws Exception {
    stored(rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));
    givenNewMail(mail(1L, "a@acme.com", "One"));

    service.applyToNewMail(USERNAME, List.of(inbox(1L)), FilterRunContext.OWN_INBOX);
    service.applyToNewMail(USERNAME, List.of(inbox(1L)), FilterRunContext.OWN_INBOX);

    assertEquals(1, matches.size());
    verify(emailBoxService, times(1)).updateEmailStarredStatus(any(), any(), any(), anyBoolean(), anyBoolean());
  }

  /**
   * A rule with an assistant queues the match and asks for it; its post-actions wait for
   * the assistant, so the mail stays in the inbox and in the announcement. Once the
   * handler calls back, they run, once.
   *
   * @throws Exception never
   */
  @Test
  void theAssistantGoesFirstAndThePostActionsAfter() throws Exception {
    stored(rule("Invoices", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(agent(), move("CUSTOM:3"))));
    Email mail = mail(1L, "a@acme.com", "Invoice");
    givenNewMail(mail);

    Set<Long> filed = service.applyToNewMail(USERNAME, List.of(inbox(1L)), FilterRunContext.OWN_INBOX);

    assertEquals(Set.of(), filed, "the mail stays until the assistant is done");
    verify(emailBoxService, never()).moveToFolder(any(), any(), any(), any());
    EmailFilterMatch match = matches.values().iterator().next();
    assertEquals(EmailFilterMatch.AGENT_PENDING, match.getAgentStatus());
    assertEquals(EmailFilterMatch.POST_PENDING_AGENT, match.getPostActionsState());
    assertEquals("EMAIL_FILTER_ASSISTANT", match.getAgentNameId());
    verify(listenerService).broadcast(EmailConnectorUtils.FILTER_AGENT_REQUESTED, USERNAME, List.of(match.getId()));

    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, mail.getMailHeaderId(), MailFolder.INBOX)).thenReturn(mail);
    service.applyPostActions(match.getId(), USERNAME);
    service.applyPostActions(match.getId(), USERNAME);

    verify(emailBoxService, times(1)).moveToFolder(List.of(1L), USERNAME, MailFolder.INBOX, "CUSTOM:3");
    assertEquals(EmailFilterMatch.POST_DONE, matches.get(match.getId()).getPostActionsState());
  }

  /**
   * An assistant switched off, or an owner over the pending cap, never holds back a
   * rule's other actions: the match is skipped with the reason and they run in the
   * pass.
   *
   * @throws Exception never
   */
  @Test
  void aSkippedAssistantDoesNotHoldThePostActions() throws Exception {
    stored(rule("Invoices", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(agent(), move("CUSTOM:3"))));
    givenNewMail(mail(1L, "a@acme.com", "One"), mail(2L, "a@acme.com", "Two"));
    System.setProperty(EmailFilterService.AGENT_ENABLED_PROPERTY, "false");

    assertEquals(Set.of(1L), service.applyToNewMail(USERNAME, List.of(inbox(1L)), FilterRunContext.OWN_INBOX));
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_DISABLED, matches.values().iterator().next().getAgentStatus());

    System.clearProperty(EmailFilterService.AGENT_ENABLED_PROPERTY);
    System.setProperty(EmailFilterService.MAX_PENDING_PROPERTY, "3");
    when(emailFilterStorage.countByAgentStatus(USERNAME, EmailFilterMatch.AGENT_PENDING)).thenReturn(3L);

    assertEquals(Set.of(2L), service.applyToNewMail(USERNAME, List.of(inbox(2L)), FilterRunContext.OWN_INBOX));
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_CAP, new ArrayList<>(matches.values()).get(1).getAgentStatus());
    assertEquals(EmailFilterService.PENDING_LIMIT, new ArrayList<>(matches.values()).get(1).getLastError(), "told apart from the day's limit");
    verify(listenerService, never()).broadcast(eq(EmailConnectorUtils.FILTER_AGENT_REQUESTED), any(), any());
  }

  /**
   * The daily cap at sync (EXO-90668): the assistant's runs today and the mails already
   * waiting for it count, so a mail past the cap is skipped "daily limit" at once -- no
   * request, no attempt, its other actions run in the pass -- and one under it is queued.
   * Both sides of the guard.
   *
   * @throws Exception never
   */
  @Test
  void aMailPastTheDailyCapIsSkippedWithTheDailyLimitAndItsActionsRun() throws Exception {
    stored(rule("Invoices", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(agent(), move("CUSTOM:3"))));
    givenNewMail(mail(1L, "a@acme.com", "One"), mail(2L, "a@acme.com", "Two"));
    System.setProperty(EmailFilterService.DAILY_CAP_PROPERTY, "3");
    when(emailFilterStorage.countByAgentStatus(USERNAME, EmailFilterMatch.AGENT_PENDING)).thenReturn(1L);
    when(emailFilterStorage.countAgentRunsSince(eq(USERNAME), any(), eq(Date.from(Instant.ofEpochMilli(NOW).truncatedTo(java.time.temporal.ChronoUnit.DAYS)))))
                                                                                                                                  .thenReturn(1L);

    assertEquals(Set.of(), service.applyToNewMail(USERNAME, List.of(inbox(1L)), FilterRunContext.OWN_INBOX), "under the cap: queued");
    EmailFilterMatch queued = matches.values().iterator().next();
    assertEquals(EmailFilterMatch.AGENT_PENDING, queued.getAgentStatus());

    when(emailFilterStorage.countAgentRunsSince(eq(USERNAME), any(), any())).thenReturn(2L);
    assertEquals(Set.of(2L), service.applyToNewMail(USERNAME, List.of(inbox(2L)), FilterRunContext.OWN_INBOX), "the move ran in the pass");
    EmailFilterMatch skipped = new ArrayList<>(matches.values()).get(1);
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_CAP, skipped.getAgentStatus());
    assertEquals(EmailFilterService.DAILY_LIMIT, skipped.getLastError());
    assertEquals(0, skipped.getAgentAttempts(), "no attempt spent");
    assertEquals(EmailFilterMatch.POST_DONE, skipped.getPostActionsState());
    verify(listenerService, times(1)).broadcast(eq(EmailConnectorUtils.FILTER_AGENT_REQUESTED), any(), any());
  }

  /**
   * Never on spam, at sync (EXO-90668): a mail the server marked -- the {@code $Junk}
   * keyword, or an {@code X-Spam-Flag: YES} header -- is skipped "spam", nothing
   * requested, its other actions run; a {@code $NotJunk} mark from the user wins over the
   * header, and an unmarked mail is queued.
   *
   * @throws Exception never
   */
  @Test
  void aMailTheServerFlaggedAsSpamNeverQueuesTheAssistant() throws Exception {
    stored(rule("Invoices", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(agent(), move("CUSTOM:3"))));
    givenNewMail(mail(1L, "a@acme.com", "One"), mail(2L, "a@acme.com", "Two"), mail(3L, "a@acme.com", "Three"),
                 mail(4L, "a@acme.com", "Four"));
    InboxMail headerFlagged = new InboxMail(2L, Set.of(), name -> "X-Spam-Flag".equals(name) ? List.of("YES") : List.of(), () -> null);
    InboxMail notJunk = new InboxMail(3L, Set.of("$NotJunk"), name -> "X-Spam-Flag".equals(name) ? List.of("YES") : List.of(), () -> null);

    Set<Long> filed = service.applyToNewMail(USERNAME,
                                             List.of(inbox(1L, "$Junk"), headerFlagged, notJunk, inbox(4L)),
                                             FilterRunContext.OWN_INBOX);

    List<EmailFilterMatch> recorded = new ArrayList<>(matches.values());
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_SPAM, recorded.get(0).getAgentStatus(), "the keyword");
    assertEquals(EmailFilterService.SPAM, recorded.get(0).getLastError());
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_SPAM, recorded.get(1).getAgentStatus(), "the header");
    assertEquals(EmailFilterMatch.AGENT_PENDING, recorded.get(2).getAgentStatus(), "the user said not spam");
    assertEquals(EmailFilterMatch.AGENT_PENDING, recorded.get(3).getAgentStatus());
    assertEquals(Set.of(1L, 2L), filed, "the spam's other actions ran in the pass");
    verify(listenerService).broadcast(EmailConnectorUtils.FILTER_AGENT_REQUESTED,
                                      USERNAME,
                                      List.of(recorded.get(2).getId(), recorded.get(3).getId()));
  }

  /**
   * The handler's guard, spam first (EXO-90668): a mail the cache holds in Junk, or one
   * the server flags when read live, is skipped "spam" -- no attempt spent, its held
   * actions run --, whatever path queued it; an unflagged mail passes.
   *
   * @throws Exception never
   */
  @Test
  void theHandlersGuardSkipsAMailInJunkOrFlaggedAsSpam() throws Exception {
    EmailFilterMatch inJunk = waitingMatch(1L, EmailFilterMatch.AGENT_PENDING);
    when(emailBoxService.hasOwnEmailInFolder(USERNAME, inJunk.getMailHeaderId(), MailFolder.JUNK)).thenReturn(true);

    EmailFilterMatch skipped = service.skipIfGuarded(USERNAME, inJunk);
    assertNotNull(skipped, "a mail in Junk is not run");

    assertEquals(EmailFilterMatch.AGENT_SKIPPED_SPAM, skipped.getAgentStatus());
    assertEquals(0, skipped.getAgentAttempts(), "no attempt spent");
    verify(emailBoxService, never()).isFlaggedAsSpamOnServer(any(), anyLong(), any());

    EmailFilterMatch flagged = waitingMatch(2L, EmailFilterMatch.AGENT_PENDING);
    Email mail = mail(2L, "a@acme.com", "Two");
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, flagged.getMailHeaderId(), MailFolder.INBOX)).thenReturn(mail);
    when(emailBoxService.isFlaggedAsSpamOnServer(USERNAME, 2L, "<2@acme.com>")).thenReturn(Optional.of(true));

    skipped = service.skipIfGuarded(USERNAME, flagged);
    assertNotNull(skipped, "a mail the server flagged is not run");

    assertEquals(EmailFilterMatch.AGENT_SKIPPED_SPAM, skipped.getAgentStatus());
    assertEquals(EmailFilterMatch.POST_DONE, matches.get(flagged.getId()).getPostActionsState(), "its held actions ran");
    verify(emailBoxService).moveToFolder(List.of(2L), USERNAME, MailFolder.INBOX, "CUSTOM:3");

    EmailFilterMatch clean = waitingMatch(3L, EmailFilterMatch.AGENT_PENDING);
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, clean.getMailHeaderId(), MailFolder.INBOX)).thenReturn(mail(3L, "a@acme.com", "Three"));
    when(emailBoxService.isFlaggedAsSpamOnServer(USERNAME, 3L, "<3@acme.com>")).thenReturn(Optional.of(false));

    assertNull(service.skipIfGuarded(USERNAME, clean), "a clean mail runs");
    assertEquals(EmailFilterMatch.AGENT_PENDING, matches.get(clean.getId()).getAgentStatus());
  }

  /**
   * The handler's guard never runs on a mail whose marks are unknown (EXO-90668): when
   * the server cannot be read, the match is given back waiting, no attempt spent, its
   * held actions still held.
   *
   * @throws Exception never
   */
  @Test
  void theHandlersGuardHoldsAMailWhoseSpamMarksCannotBeRead() throws Exception {
    EmailFilterMatch match = waitingMatch(1L, EmailFilterMatch.AGENT_PENDING);
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, match.getMailHeaderId(), MailFolder.INBOX)).thenReturn(mail(1L, "a@acme.com", "One"));
    when(emailBoxService.isFlaggedAsSpamOnServer(USERNAME, 1L, "<1@acme.com>")).thenThrow(new IllegalStateException("unreachable"));

    EmailFilterMatch parked = service.skipIfGuarded(USERNAME, match);
    assertNotNull(parked, "a mail whose marks are unknown is not run");

    assertEquals(EmailFilterMatch.AGENT_PENDING, parked.getAgentStatus());
    assertEquals(EmailFilterService.SPAM_UNCHECKED, parked.getLastError());
    assertEquals(0, parked.getAgentAttempts(), "no attempt spent");
    assertEquals(EmailFilterMatch.POST_PENDING_AGENT, parked.getPostActionsState());
  }

  /**
   * The handler's daily cap (EXO-90668): once the assistant was called the cap's number
   * of times today, a match is skipped "daily limit" -- no attempt spent, its held
   * actions run -- and can be run again later by the owner; under the cap it passes, and
   * a match given back after a call today does not count twice.
   *
   * @throws Exception never
   */
  @Test
  void theHandlersGuardSkipsAMatchPastTheDailyCapWithoutSpendingAnAttempt() throws Exception {
    System.setProperty(EmailFilterService.DAILY_CAP_PROPERTY, "2");
    EmailFilterMatch match = waitingMatch(1L, EmailFilterMatch.AGENT_PENDING);
    Email mail = mail(1L, "a@acme.com", "One");
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, match.getMailHeaderId(), MailFolder.INBOX)).thenReturn(mail);
    when(emailBoxService.isFlaggedAsSpamOnServer(USERNAME, 1L, "<1@acme.com>")).thenReturn(Optional.of(false));
    Date startOfDay = Date.from(Instant.ofEpochMilli(NOW).truncatedTo(java.time.temporal.ChronoUnit.DAYS));
    when(emailFilterStorage.countAgentRunsSince(eq(USERNAME), any(), eq(startOfDay))).thenReturn(1L);

    assertNull(service.skipIfGuarded(USERNAME, match), "under the cap");

    when(emailFilterStorage.countAgentRunsSince(eq(USERNAME), any(), eq(startOfDay))).thenReturn(2L);
    EmailFilterMatch givenBack = copyOf(match);
    givenBack.setAgentConversationId("c1");
    givenBack.setAgentDate(NOW - 1_000L);
    givenBack.setAgentAttempts(1);
    assertNull(service.skipIfGuarded(USERNAME, givenBack), "a match given back today counts itself once");

    EmailFilterMatch skipped = service.skipIfGuarded(USERNAME, match);

    assertNotNull(skipped, "past the cap, the mail is not run");
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_CAP, skipped.getAgentStatus());
    assertEquals(EmailFilterService.DAILY_LIMIT, skipped.getLastError());
    assertEquals(0, skipped.getAgentAttempts(), "no attempt spent");
    assertEquals(EmailFilterMatch.POST_DONE, matches.get(match.getId()).getPostActionsState(), "its held actions ran");

    EmailFilterMatch retried = service.retry(USERNAME, null, match.getId());
    assertEquals(EmailFilterMatch.AGENT_PENDING, retried.getAgentStatus(), "the owner can run it again later");
  }

  /**
   * The daily cap at sync counts each waiting mail once (EXO-90668): a match given back
   * waiting after a run today is among the waiting ones, not also among those run. With a
   * cap of 100 and 60 matches given back, a new mail is queued, not skipped.
   *
   * @throws Exception never
   */
  @Test
  void aMatchGivenBackAfterARunTodayCountsOnceAtSync() throws Exception {
    stored(rule("Invoices", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(agent(), move("CUSTOM:3"))));
    givenNewMail(mail(1L, "a@acme.com", "One"));
    System.setProperty(EmailFilterService.DAILY_CAP_PROPERTY, "100");
    when(emailFilterStorage.countByAgentStatus(USERNAME, EmailFilterMatch.AGENT_PENDING)).thenReturn(60L);
    // The 60 given back hold today's conversation: a count that takes PENDING in says 60.
    lenient().when(emailFilterStorage.countAgentRunsSince(eq(USERNAME), argThat(statuses -> statuses != null
        && statuses.contains(EmailFilterMatch.AGENT_PENDING)), any())).thenReturn(60L);
    lenient().when(emailFilterStorage.countAgentRunsSince(eq(USERNAME), argThat(statuses -> statuses != null
        && !statuses.contains(EmailFilterMatch.AGENT_PENDING)), any())).thenReturn(0L);

    service.applyToNewMail(USERNAME, List.of(inbox(1L)), FilterRunContext.OWN_INBOX);

    EmailFilterMatch queued = matches.values().iterator().next();
    assertEquals(EmailFilterMatch.AGENT_PENDING, queued.getAgentStatus(), "60 waiting of 100: not past the cap");
    verify(listenerService).broadcast(EmailConnectorUtils.FILTER_AGENT_REQUESTED, USERNAME, List.of(queued.getId()));
  }

  /**
   * The handler's guard reads the cap before the mail server (EXO-90668): a match past
   * the cap is skipped without opening a connection to read its spam marks.
   *
   * @throws Exception never
   */
  @Test
  void theHandlersGuardChecksTheDailyCapBeforeReadingTheServer() throws Exception {
    System.setProperty(EmailFilterService.DAILY_CAP_PROPERTY, "1");
    EmailFilterMatch match = waitingMatch(1L, EmailFilterMatch.AGENT_PENDING);
    lenient().when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, match.getMailHeaderId(), MailFolder.INBOX))
             .thenReturn(mail(1L, "a@acme.com", "One"));
    when(emailFilterStorage.countAgentRunsSince(eq(USERNAME), any(), any())).thenReturn(1L);

    EmailFilterMatch skipped = service.skipIfGuarded(USERNAME, match);

    assertEquals(EmailFilterMatch.AGENT_SKIPPED_CAP, skipped.getAgentStatus());
    verify(emailBoxService, never()).isFlaggedAsSpamOnServer(any(), anyLong(), any());
  }

  /**
   * A skip is never undone by its held actions (EXO-90668): when they throw, the match
   * stays skipped -- spam from the cache, spam from the server, or past the cap --, it is
   * not given back to wait, and nothing escapes to the handler.
   *
   * @throws Exception never
   */
  @Test
  void aSkipWhoseHeldActionsFailStaysSkipped() throws Exception {
    doThrow(new IllegalStateException("the move failed")).when(service).applyPostActions(anyLong(), eq(USERNAME));

    EmailFilterMatch inJunk = waitingMatch(1L, EmailFilterMatch.AGENT_PENDING);
    when(emailBoxService.hasOwnEmailInFolder(USERNAME, inJunk.getMailHeaderId(), MailFolder.JUNK)).thenReturn(true);
    EmailFilterMatch skipped = service.skipIfGuarded(USERNAME, inJunk);
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_SPAM, skipped.getAgentStatus(), "in Junk");
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_SPAM, matches.get(inJunk.getId()).getAgentStatus(), "not given back");
    assertEquals(EmailFilterService.SPAM, matches.get(inJunk.getId()).getLastError());

    EmailFilterMatch flagged = waitingMatch(2L, EmailFilterMatch.AGENT_PENDING);
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, flagged.getMailHeaderId(), MailFolder.INBOX)).thenReturn(mail(2L, "a@acme.com", "Two"));
    when(emailBoxService.isFlaggedAsSpamOnServer(USERNAME, 2L, "<2@acme.com>")).thenReturn(Optional.of(true));
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_SPAM, service.skipIfGuarded(USERNAME, flagged).getAgentStatus(), "flagged");
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_SPAM, matches.get(flagged.getId()).getAgentStatus());

    System.setProperty(EmailFilterService.DAILY_CAP_PROPERTY, "0");
    EmailFilterMatch capped = waitingMatch(3L, EmailFilterMatch.AGENT_PENDING);
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_CAP, service.skipIfGuarded(USERNAME, capped).getAgentStatus(), "past the cap");
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_CAP, matches.get(capped.getId()).getAgentStatus());
  }

  /**
   * A mail whose spam marks keep failing to read cannot hold its mailbox for ever
   * (EXO-90668): it is given back {@value EmailFilterService#MAX_SPAM_UNCHECKED} times in
   * a row, each counted in its last error; the next time it is skipped as spam, fail-safe
   * -- no model call --, with its own reason and its held actions run.
   *
   * @throws Exception never
   */
  @Test
  void aMailWhoseSpamMarksKeepFailingIsSkippedAfterTheLastGiveBack() throws Exception {
    EmailFilterMatch match = waitingMatch(1L, EmailFilterMatch.AGENT_PENDING);
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, match.getMailHeaderId(), MailFolder.INBOX)).thenReturn(mail(1L, "a@acme.com", "One"));
    when(emailBoxService.isFlaggedAsSpamOnServer(USERNAME, 1L, "<1@acme.com>")).thenThrow(new IllegalStateException("unreadable"));

    for (int parks = 1; parks <= EmailFilterService.MAX_SPAM_UNCHECKED; parks++) {
      EmailFilterMatch parked = service.skipIfGuarded(USERNAME, asRead(match.getId()));
      assertEquals(EmailFilterMatch.AGENT_PENDING, parked.getAgentStatus(), "give-back " + parks);
      assertEquals(parks, EmailFilterService.spamUncheckedCount(parked.getLastError()), "counted in a row");
      assertEquals(0, parked.getAgentAttempts(), "no attempt spent");
      assertEquals(EmailFilterMatch.POST_PENDING_AGENT, parked.getPostActionsState());
    }

    EmailFilterMatch skipped = service.skipIfGuarded(USERNAME, asRead(match.getId()));

    assertEquals(EmailFilterMatch.AGENT_SKIPPED_SPAM, skipped.getAgentStatus());
    assertEquals(EmailFilterService.SPAM_UNCHECKED_LIMIT, matches.get(match.getId()).getLastError());
    assertEquals(0, skipped.getAgentAttempts(), "no attempt spent");
    assertEquals(EmailFilterMatch.POST_DONE, matches.get(match.getId()).getPostActionsState(), "its held actions ran");
    verify(emailBoxService).moveToFolder(List.of(1L), USERNAME, MailFolder.INBOX, "CUSTOM:3");
  }

  /**
   * A stored match as the handler reads it for a run, its last error with it.
   *
   * @param id the match
   * @return the copy
   */
  private EmailFilterMatch asRead(long id) {
    EmailFilterMatch stored = matches.get(id);
    EmailFilterMatch read = copyOf(stored);
    read.setLastError(stored.getLastError());
    read.setAgentAttempts(stored.getAgentAttempts());
    return read;
  }

  /**
   * The give-backs are counted in a row only: another reason in between starts again.
   */
  @Test
  void theSpamUncheckedCountReadsOnlyItsOwnCode() {
    assertEquals(0, EmailFilterService.spamUncheckedCount(null));
    assertEquals(0, EmailFilterService.spamUncheckedCount("emailConnector.filters.agent.unavailable"));
    assertEquals(1, EmailFilterService.spamUncheckedCount(EmailFilterService.SPAM_UNCHECKED));
    assertEquals(4, EmailFilterService.spamUncheckedCount(EmailFilterService.SPAM_UNCHECKED + ":4"));
    assertEquals(1, EmailFilterService.spamUncheckedCount(EmailFilterService.SPAM_UNCHECKED + ":x"));
  }

  /**
   * Without an assistant handler on the deployment, a rule with an assistant never holds
   * its other actions: the match is skipped as when the assistant is switched off, the
   * other actions run in the pass, and nothing is requested. With one, the match is
   * queued ({@link #theAssistantGoesFirstAndThePostActionsAfter()}).
   *
   * @throws Exception never
   */
  @Test
  void withoutAHandlerTheAssistantIsSkippedAndTheOtherActionsRun() throws Exception {
    noAgentHandler();
    stored(rule("Invoices", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(agent(), move("CUSTOM:3"))));
    givenNewMail(mail(1L, "a@acme.com", "Invoice"));

    assertFalse(service.isAgentHandled());
    Set<Long> filed = service.applyToNewMail(USERNAME, List.of(inbox(1L)), FilterRunContext.OWN_INBOX);

    assertEquals(Set.of(1L), filed, "the move ran in the pass");
    EmailFilterMatch match = matches.values().iterator().next();
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_DISABLED, match.getAgentStatus());
    assertEquals(EmailFilterMatch.POST_DONE, match.getPostActionsState());
    verify(emailBoxService).moveToFolder(List.of(1L), USERNAME, MailFolder.INBOX, "CUSTOM:3");
    verify(listenerService, never()).broadcast(eq(EmailConnectorUtils.FILTER_AGENT_REQUESTED), any(), any());
  }

  /**
   * The startup sweep, when nothing runs the assistant: every waiting match is claimed,
   * recorded skipped, and the actions it held run; one whose actions fail is put back in
   * its status and does not stop the others.
   *
   * @throws Exception never
   */
  @Test
  void theSweepReleasesTheMatchesLeftWaiting() throws Exception {
    noAgentHandler();
    EmailFilterMatch failing = waitingMatch(2L, EmailFilterMatch.AGENT_RUNNING);
    EmailFilterMatch waiting = waitingMatch(1L, EmailFilterMatch.AGENT_PENDING);
    when(emailFilterStorage.getMatchesByAgentStatuses(eq(List.of(EmailFilterMatch.AGENT_PENDING, EmailFilterMatch.AGENT_RUNNING)),
                                                      eq(0L),
                                                      anyInt())).thenReturn(List.of(new OwnedMatch(USERNAME, copyOf(failing)),
                                                                                    new OwnedMatch(USERNAME, copyOf(waiting))));
    when(emailFilterStorage.updateAgentStatusIf(anyLong(), anyString(), anyString())).thenAnswer(invocation -> {
      EmailFilterMatch match = matches.get(invocation.<Long> getArgument(0));
      if (!match.getAgentStatus().equals(invocation.getArgument(1))) {
        return false;
      }
      match.setAgentStatus(invocation.getArgument(2));
      return true;
    });
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, failing.getMailHeaderId(), MailFolder.INBOX))
                                                                                                          .thenThrow(new IllegalStateException("the database is away"));
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, waiting.getMailHeaderId(), MailFolder.INBOX))
                                                                                                          .thenReturn(mail(1L, "a@acme.com", "One"));

    assertEquals(1, service.releaseUnansweredAgentMatches());

    EmailFilterMatch released = matches.get(waiting.getId());
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_DISABLED, released.getAgentStatus());
    assertEquals(EmailFilterMatch.POST_DONE, released.getPostActionsState());
    verify(service).applyPostActions(waiting.getId(), USERNAME);
    verify(emailBoxService).moveToFolder(List.of(1L), USERNAME, MailFolder.INBOX, "CUSTOM:3");
    EmailFilterMatch putBack = matches.get(failing.getId());
    assertEquals(EmailFilterMatch.AGENT_RUNNING, putBack.getAgentStatus(), "put back for the next sweep");
    assertEquals(EmailFilterMatch.POST_PENDING_AGENT, putBack.getPostActionsState());
  }

  /**
   * A match whose owner may no longer read the mailbox is not put back: nothing will
   * change by the next boot, so its held actions end with the reason.
   *
   * @throws Exception never
   */
  @Test
  void theSweepEndsAMatchWhoseMailboxCannotBeRead() throws Exception {
    noAgentHandler();
    EmailFilterMatch unreadable = waitingMatch(3L, EmailFilterMatch.AGENT_PENDING);
    when(emailFilterStorage.getMatchesByAgentStatuses(eq(List.of(EmailFilterMatch.AGENT_PENDING, EmailFilterMatch.AGENT_RUNNING)),
                                                      eq(0L),
                                                      anyInt())).thenReturn(List.of(new OwnedMatch(USERNAME, copyOf(unreadable))));
    when(emailFilterStorage.updateAgentStatusIf(anyLong(), anyString(), anyString())).thenAnswer(invocation -> {
      EmailFilterMatch match = matches.get(invocation.<Long> getArgument(0));
      if (!match.getAgentStatus().equals(invocation.getArgument(1))) {
        return false;
      }
      match.setAgentStatus(invocation.getArgument(2));
      return true;
    });
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, unreadable.getMailHeaderId(), MailFolder.INBOX))
                                                                                                             .thenThrow(new IllegalAccessException("no connector"));

    assertEquals(1, service.releaseUnansweredAgentMatches());

    EmailFilterMatch ended = matches.get(unreadable.getId());
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_DISABLED, ended.getAgentStatus(), "not put back");
    assertEquals(EmailFilterMatch.POST_DONE, ended.getPostActionsState());
    assertEquals(EmailFilterService.UNREADABLE, ended.getLastError());
  }

  /**
   * With an assistant handler present, the sweep reads and touches nothing.
   */
  @Test
  void withAHandlerTheSweepTouchesNothing() {
    assertTrue(service.isAgentHandled());

    assertEquals(0, service.releaseUnansweredAgentMatches());

    verify(emailFilterStorage, never()).getMatchesByAgentStatuses(any(), anyLong(), anyInt());
    verify(emailFilterStorage, never()).updateAgentStatusIf(anyLong(), anyString(), anyString());
  }

  /**
   * "Run again" without an assistant handler does not queue a match that would wait for
   * ever: it is recorded skipped again, and actions it still held run.
   *
   * @throws Exception never
   */
  @Test
  void runAgainWithoutAHandlerDoesNotQueue() throws Exception {
    noAgentHandler();
    EmailFilterMatch match = waitingMatch(1L, EmailFilterMatch.AGENT_FAILED);
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, match.getMailHeaderId(), MailFolder.INBOX)).thenReturn(mail(1L, "a@acme.com", "One"));

    EmailFilterMatch retried = service.retry(USERNAME, null, match.getId());

    assertEquals(EmailFilterMatch.AGENT_SKIPPED_DISABLED, retried.getAgentStatus());
    assertEquals(EmailFilterMatch.POST_DONE, retried.getPostActionsState(), "the held actions ran");
    verify(emailBoxService).moveToFolder(List.of(1L), USERNAME, MailFolder.INBOX, "CUSTOM:3");
    verify(listenerService, never()).broadcast(eq(EmailConnectorUtils.FILTER_AGENT_REQUESTED), any(), any());
  }

  /**
   * The batch read for a backend caller: no Message-ID, no query; blank ones and
   * duplicates are not asked for.
   */
  @Test
  void theMatchesOfABatchOfMailsAskOnlyForRealIds() {
    assertTrue(service.getMatchesOfMails(USERNAME, List.of()).isEmpty());
    assertTrue(service.getMatchesOfMails(USERNAME, Arrays.asList(" ", null)).isEmpty());
    verify(emailFilterStorage, never()).getMatchesOfMails(anyString(), any());

    EmailFilterMatch match = storedMatch();
    when(emailFilterStorage.getMatchesOfMails(USERNAME, List.of("<one@acme.com>"))).thenReturn(List.of(match));

    assertEquals(List.of(match), service.getMatchesOfMails(USERNAME, List.of("<one@acme.com>", "<one@acme.com>", "")));
  }

  /**
   * Undo puts back what a match did, the flags first while the mail is where the filing
   * put it, the move last, and never twice.
   *
   * @throws Exception never
   */
  @Test
  void undoPutsBackWhatAMatchDid() throws Exception {
    EmailFilterMatch match = storedMatch(new AppliedAction(FilterAction.ADD_CATEGORY, true, null, null, null, 12L, null, null, null, false),
                                         new AppliedAction(FilterAction.MOVE_TO_FOLDER,
                                                           true,
                                                           null,
                                                           "CUSTOM:3",
                                                           MailFolder.INBOX,
                                                           null,
                                                           null,
                                                           null,
                                                           null,
                                                           false));
    Email moved = mail(77L, "a@acme.com", "One");
    when(emailBoxService.getOwnEmailByMailHeaderId(USERNAME, match.getMailHeaderId(), "CUSTOM:3")).thenReturn(moved);

    EmailFilterMatch undone = service.undo(USERNAME, null, match.getId(), null);

    verify(emailBoxService).unlinkEmailsFromCategory(List.of(77L), 12L, USERNAME, "CUSTOM:3");
    verify(emailBoxService).undoMove(List.of(match.getMailHeaderId()), USERNAME, "CUSTOM:3", MailFolder.INBOX);
    assertTrue(undone.getActions().stream().allMatch(AppliedAction::undone));

    service.undo(USERNAME, null, match.getId(), null);
    verify(emailBoxService, times(1)).undoMove(any(), any(), any(), any());
  }

  /**
   * An eXo rule takes the body and attachment conditions but not the size, which eXo
   * does not keep; a rule the server runs too takes the server's vocabulary only; a
   * folder of a shared mailbox, two filings or a scope other than the owner's are
   * refused.
   */
  @Test
  void theVocabularyFollowsTheKind() {
    Condition body = new Condition("BODY", "CONTAINS", null, "invoice");
    Condition size = new Condition("MESSAGE_SIZE", "GT", null, "100");
    List<FilterAction> star = List.of(action(FilterAction.STAR));
    assertEquals(List.of(body), service.validated(USERNAME, rule("R", EmailFilter.KIND_EXO, List.of(body), star)).getConditions());
    assertThrows(IllegalArgumentException.class, () -> service.validated(USERNAME, rule("R", EmailFilter.KIND_EXO, List.of(size), star)));
    assertThrows(IllegalArgumentException.class, () -> service.validated(USERNAME, rule("R", EmailFilter.KIND_HOP, List.of(body), star)));
    assertEquals(List.of(size), service.validated(USERNAME, rule("R", EmailFilter.KIND_HOP, List.of(size), star)).getConditions());

    EmailFolder shared = ownFolder();
    shared.setDelegationId(9L);
    when(emailFolderService.getFolderByKey(USERNAME, "CUSTOM:4")).thenReturn(shared);
    assertThrows(IllegalArgumentException.class,
                 () -> service.validated(USERNAME, rule("R", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(move("CUSTOM:4")))));
    assertThrows(IllegalArgumentException.class,
                 () -> service.validated(USERNAME,
                                         rule("R",
                                              EmailFilter.KIND_EXO,
                                              List.of(FROM_ACME),
                                              List.of(action(FilterAction.MARK_JUNK), action(FilterAction.DELETE)))));
    EmailFilter scoped = rule("R", EmailFilter.KIND_EXO, List.of(FROM_ACME), star);
    scoped.setMailboxScope("DELEGATION:9");
    assertThrows(IllegalArgumentException.class, () -> service.validated(USERNAME, scoped));
    EmailFilter withAgent = service.validated(USERNAME, rule("R", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(agent(), action(FilterAction.STAR))));
    assertEquals(FilterAction.AFTER_AGENT, withAgent.getActions().get(1).when(), "the post-actions wait for the assistant");
    assertEquals("EMAIL_FILTER_ASSISTANT", withAgent.getAgentNameId());
  }

  /**
   * A category must be one of the owner's.
   */
  @Test
  void aCategoryMustBeTheOwners() {
    EmailCategory category = new EmailCategory();
    category.setId(12L);
    when(emailBoxService.getAvailableEmailCategories(eq(USERNAME), any())).thenReturn(List.of(category));
    FilterAction mine = new FilterAction(FilterAction.ADD_CATEGORY, null, 12L, null, null, null, null);
    FilterAction theirs = new FilterAction(FilterAction.ADD_CATEGORY, null, 13L, null, null, null, null);

    assertEquals(12L, service.validated(USERNAME, rule("R", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(mine))).getActions().get(0).categoryId());
    assertThrows(IllegalArgumentException.class, () -> service.validated(USERNAME, rule("R", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(theirs))));
  }

  /**
   * Asked from a shared mailbox, every verb is refused before anything is read.
   */
  @Test
  void aSharedMailboxIsRefused() {
    assertThrows(IllegalAccessException.class, () -> service.getFilters(USERNAME, 9L));
    assertThrows(IllegalAccessException.class, () -> service.createFilter(USERNAME, 9L, hop("Acme", FROM_ACME), true, false));
    assertThrows(IllegalAccessException.class, () -> service.undo(USERNAME, 9L, 1L, null));
    verifyNoInteractions(emailFilterStorage, emailServerRuleService);
  }

  /**
   * The order is a permutation of the owner's rules, or refused.
   *
   * @throws Exception never
   */
  @Test
  void theOrderIsEveryRuleOnce() throws Exception {
    EmailFilter first = stored(rule("A", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));
    EmailFilter second = stored(rule("B", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));

    assertThrows(IllegalArgumentException.class, () -> service.reorder(USERNAME, null, List.of(first.getId())));
    assertThrows(IllegalArgumentException.class, () -> service.reorder(USERNAME, null, List.of(first.getId(), first.getId())));
    service.reorder(USERNAME, null, List.of(second.getId(), first.getId()));
    verify(emailFilterStorage).reorder(USERNAME, List.of(second.getId(), first.getId()), new Date(NOW));
  }

  /**
   * The preview counts the cached inbox's matches, loads a whole row only for a condition
   * that needs it, and says what it could not evaluate and when the count is the
   * server's approximation.
   *
   * @throws Exception never
   */
  @Test
  void thePreviewCountsTheCachedInbox() throws Exception {
    when(emailBoxService.getCachedInbox(USERNAME)).thenReturn(List.of(mail(1L, "a@acme.com", "One"),
                                                                      mail(2L, "b@other.org", "Two"),
                                                                      mail(3L, "c@sub.acme.com", "Three")));
    EmailFilter draft = rule("R", EmailFilter.KIND_SERVER, List.of(FROM_ACME, new Condition("HEADER", "CONTAINS", "X-Tag", "a")), null);
    draft.setMatchAll(false);

    FilterPreview preview = service.preview(USERNAME, null, draft);

    assertEquals(2, preview.total());
    assertEquals(3, preview.scanned());
    assertEquals(List.of("HEADER"), preview.notPreviewable());
    assertTrue(preview.approximate(), "a server rule's count is the server's approximation");
    verify(emailBoxService, never()).getEmailById(anyLong(), anyString());
  }

  /**
   * A rule on a category runs when a mail gets that category, whoever added it: the match
   * is recorded and its assistant queued, as at sync. Adding another category, or a mail
   * that lost the category before the rules ran, runs nothing.
   *
   * @throws Exception never
   */
  @Test
  void aCategoryRuleRunsWhenTheMailGetsTheCategory() throws Exception {
    stored(rule("Important", EmailFilter.KIND_EXO, List.of(IMPORTANT), List.of(agent())));
    givenCategories();
    Email mail = categorised(mail(1L, "a@acme.com", "Board meeting"), IMPORTANT_ID);
    Email uncategorised = mail(2L, "a@acme.com", "Board meeting");
    givenNewMail(mail, uncategorised);

    service.applyToCategorizedMail(USERNAME, NOTIFICATION_ID, List.of(1L));
    service.applyToCategorizedMail(USERNAME, IMPORTANT_ID, List.of(2L));
    assertTrue(matches.isEmpty(), "another category, or a mail that lost it: " + matches);

    service.applyToCategorizedMail(USERNAME, IMPORTANT_ID, List.of(1L));

    assertEquals(1, matches.size());
    EmailFilterMatch match = matches.values().iterator().next();
    assertEquals(1L, match.getMailRemoteId());
    assertEquals(EmailFilterMatch.AGENT_PENDING, match.getAgentStatus());
    verify(listenerService).broadcast(EmailConnectorUtils.FILTER_AGENT_REQUESTED, USERNAME, List.of(match.getId()));
    verify(emailFilterStorage).addMatches(eq(match.getFilterId()), eq(USERNAME), eq(1L), any());
  }

  /**
   * Only the category just added runs a rule: a mail that also carries Important, when it
   * gets Notification, does not run the rule on Important -- that one ran, or chose not
   * to, when Important was added. And a mail that lost the category before the rules ran
   * does not run it, even where another condition alone would do ("any").
   *
   * @throws Exception never
   */
  @Test
  void onlyTheCategoryJustAddedRunsARuleAndOnlyIfStillThere() throws Exception {
    EmailFilter important = rule("Important or Acme", EmailFilter.KIND_EXO, List.of(IMPORTANT, FROM_ACME), List.of(action(FilterAction.STAR)));
    important.setMatchAll(false);
    stored(important);
    EmailFilter notification = stored(rule("Notification",
                                           EmailFilter.KIND_EXO,
                                           List.of(new Condition("CATEGORY", "EQUALS", null, "emailNotificationCategory")),
                                           List.of(action(FilterAction.MARK_READ))));
    givenCategories();
    givenNewMail(categorised(mail(1L, "a@acme.com", "Hello"), IMPORTANT_ID, NOTIFICATION_ID), categorised(mail(2L, "a@acme.com", "Hello")));

    service.applyToCategorizedMail(USERNAME, NOTIFICATION_ID, List.of(1L));
    service.applyToCategorizedMail(USERNAME, IMPORTANT_ID, List.of(2L));

    assertEquals(List.of(notification.getId()), matches.values().stream().map(EmailFilterMatch::getFilterId).toList());
  }

  /**
   * A mail never runs a category rule twice: the category taken off and put back -- or put
   * back by the categorizer after the user -- finds the match of the first time.
   *
   * @throws Exception never
   */
  @Test
  void aCategoryAddedAgainDoesNotRunTheRuleAgain() throws Exception {
    stored(rule("Important", EmailFilter.KIND_EXO, List.of(IMPORTANT), List.of(action(FilterAction.STAR))));
    givenCategories();
    givenNewMail(categorised(mail(1L, "a@acme.com", "Board meeting"), IMPORTANT_ID));

    service.applyToCategorizedMail(USERNAME, IMPORTANT_ID, List.of(1L));
    service.applyToCategorizedMail(USERNAME, IMPORTANT_ID, List.of(1L));

    assertEquals(1, matches.size());
    verify(emailBoxService, times(1)).updateEmailStarredStatus(List.of(1L), USERNAME, MailFolder.INBOX, true, true);
  }

  /**
   * The rule's other conditions must hold too, joined as the rule says: "all" needs the
   * sender as well, "any" is satisfied by the category.
   *
   * @throws Exception never
   */
  @Test
  void aCategoryRuleStillNeedsItsOtherConditions() throws Exception {
    stored(rule("Important from Acme", EmailFilter.KIND_EXO, List.of(IMPORTANT, FROM_ACME), List.of(action(FilterAction.STAR))));
    givenCategories();
    givenNewMail(categorised(mail(1L, "a@other.org", "Hello"), IMPORTANT_ID), categorised(mail(2L, "a@acme.com", "Hello"), IMPORTANT_ID));

    service.applyToCategorizedMail(USERNAME, IMPORTANT_ID, List.of(1L, 2L));

    assertEquals(List.of(2L), matches.values().stream().map(EmailFilterMatch::getMailRemoteId).toList());

    EmailFilter any = rule("Important or Acme", EmailFilter.KIND_EXO, List.of(IMPORTANT, FROM_ACME), List.of(action(FilterAction.STAR)));
    any.setMatchAll(false);
    EmailFilter anyStored = stored(any);
    service.applyToCategorizedMail(USERNAME, IMPORTANT_ID, List.of(1L));

    assertTrue(matches.values().stream().anyMatch(match -> match.getFilterId().equals(anyStored.getId()) && match.getMailRemoteId() == 1L),
               "any: the category is enough");
  }

  /**
   * At sync, a rule on a category never runs -- its mail has no category yet, and it runs
   * when the mail gets one -- while the other rules run exactly as before, on the same
   * pass.
   *
   * @throws Exception never
   */
  @Test
  void theSyncLeavesCategoryRulesAndRunsTheOthers() throws Exception {
    EmailFilter onCategory = rule("Important or Acme", EmailFilter.KIND_EXO, List.of(IMPORTANT, FROM_ACME), List.of(action(FilterAction.MARK_READ)));
    onCategory.setMatchAll(false);
    stored(onCategory);
    EmailFilter star = stored(rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));
    givenNewMail(categorised(mail(1L, "a@acme.com", "Hello"), IMPORTANT_ID));

    service.applyToNewMail(USERNAME, List.of(inbox(1L)), FilterRunContext.OWN_INBOX);

    assertEquals(List.of(star.getId()), matches.values().stream().map(EmailFilterMatch::getFilterId).toList());
    verify(emailBoxService).updateEmailStarredStatus(List.of(1L), USERNAME, MailFolder.INBOX, true, true);
    verify(emailBoxService, never()).updateEmailReadStatus(any(), any(), any(), anyBoolean(), anyBoolean());
  }

  /**
   * A rule on a category that is switched off, or on another mailbox, never runs; nor does
   * any rule when the mail is not found in the inbox -- moved to Junk meanwhile.
   *
   * @throws Exception never
   */
  @Test
  void aCategoryRuleRunsOnlyEnabledAndOnTheInbox() throws Exception {
    EmailFilter off = rule("Off", EmailFilter.KIND_EXO, List.of(IMPORTANT), List.of(action(FilterAction.STAR)));
    off.setEnabled(false);
    stored(off);
    EmailFilter on = stored(rule("On", EmailFilter.KIND_EXO, List.of(IMPORTANT), List.of(action(FilterAction.STAR))));
    givenCategories();
    givenNewMail(categorised(mail(1L, "a@acme.com", "Hello"), IMPORTANT_ID));

    service.applyToCategorizedMail(USERNAME, IMPORTANT_ID, List.of(1L, 7L));

    assertEquals(List.of(on.getId()), matches.values().stream().map(EmailFilterMatch::getFilterId).toList());
  }

  /**
   * A category condition is stored by the category's stable key, one of the defaults, and
   * compared with "is" only; never by id or on a rule the server runs; and never beside a
   * header, which only the sync reads, long before a mail gets a category.
   */
  @Test
  void aCategoryConditionIsValidatedByItsKey() {
    List<FilterAction> star = List.of(action(FilterAction.STAR));
    assertEquals(List.of(IMPORTANT),
                 service.validated(USERNAME,
                                   rule("R", EmailFilter.KIND_EXO, List.of(new Condition("category", "equals", null, " emailImportantCategory ")), star))
                        .getConditions());
    for (Condition refused : List.of(new Condition("CATEGORY", "EQUALS", null, String.valueOf(IMPORTANT_ID)),
                                     new Condition("CATEGORY", "EQUALS", null, "Important"),
                                     new Condition("CATEGORY", "CONTAINS", null, "emailImportantCategory"))) {
      assertThrows(IllegalArgumentException.class,
                   () -> service.validated(USERNAME, rule("R", EmailFilter.KIND_EXO, List.of(refused), star)),
                   refused.toString());
    }
    assertThrows(IllegalArgumentException.class, () -> service.validated(USERNAME, rule("R", EmailFilter.KIND_HOP, List.of(IMPORTANT), star)));
    assertThrows(IllegalArgumentException.class,
                 () -> service.validated(USERNAME,
                                         rule("R", EmailFilter.KIND_EXO, List.of(IMPORTANT, new Condition("HEADER", "CONTAINS", "X-Tag", "a")), star)));
  }

  /**
   * Run once on the cached inbox, and previewed, a rule on a category matches the mails
   * that already carry it -- the one way it reaches mail categorised before it existed.
   *
   * @throws Exception never
   */
  @Test
  void runOnceAndThePreviewMatchTheMailsAlreadyCategorised() throws Exception {
    givenCategories();
    when(emailBoxService.getCachedInbox(USERNAME)).thenReturn(List.of(categorised(mail(1L, "a@acme.com", "One"), IMPORTANT_ID),
                                                                      categorised(mail(2L, "b@acme.com", "Two"), NOTIFICATION_ID),
                                                                      mail(3L, "c@acme.com", "Three")));

    FilterPreview preview = service.preview(USERNAME, null, rule("R", EmailFilter.KIND_EXO, List.of(IMPORTANT), null));
    assertEquals(1, preview.total());

    EmailFilter important = stored(rule("Important", EmailFilter.KIND_EXO, List.of(IMPORTANT), List.of(action(FilterAction.STAR))));
    service.applyOnce(USERNAME, null, important.getId(), false);
    assertEquals(List.of(1L), matches.values().stream().map(EmailFilterMatch::getMailRemoteId).toList());
  }

  /**
   * The owner's default categories: Important and Notification, by the ids the importer
   * gave them.
   */
  private void givenCategories() {
    lenient().when(emailBoxService.getDefaultEmailCategoryNameIds())
             .thenReturn(Map.of(IMPORTANT_ID, "emailImportantCategory", NOTIFICATION_ID, "emailNotificationCategory"));
  }

  /**
   * A mail with categories.
   *
   * @param email the mail
   * @param categoryIds its categories
   * @return the mail
   */
  private static Email categorised(Email email, Long... categoryIds) {
    email.setCategoryIds(List.of(categoryIds));
    return email;
  }

  /**
   * "Run again" queues the assistant again on a match whose run is over, and only then.
   *
   * @throws Exception never
   */
  @Test
  void runAgainQueuesTheAssistantOnceMore() throws Exception {
    EmailFilterMatch match = storedMatch();
    match.setAgentNameId("EMAIL_FILTER_ASSISTANT");
    match.setAgentStatus(EmailFilterMatch.AGENT_PENDING);
    assertThrows(IllegalArgumentException.class, () -> service.retry(USERNAME, null, match.getId()), "still due");

    match.setAgentStatus(EmailFilterMatch.AGENT_FAILED);
    match.setAgentAttempts(3);
    EmailFilterMatch queued = service.retry(USERNAME, null, match.getId());

    assertEquals(EmailFilterMatch.AGENT_PENDING, queued.getAgentStatus());
    assertEquals(0, queued.getAgentAttempts());
    verify(listenerService).broadcast(EmailConnectorUtils.FILTER_AGENT_REQUESTED, USERNAME, List.of(match.getId()));
  }

  /**
   * The first read of an owner's rules seeds "Important mail", switched off, as the
   * form would have sent it -- on the Important category, its assistant asked for a note
   * and at most two suggestions, last in the order -- and records it; the next read finds
   * that copy and makes no other.
   *
   * @throws Exception never
   */
  @Test
  void theImportantFilterIsSeededOnceOnTheFirstRead() throws Exception {
    List<EmailFilter> first = service.getFilters(USERNAME, null);

    assertEquals(1, first.size());
    EmailFilter seeded = first.get(0);
    assertFalse(seeded.isEnabled(), "switched off until the owner turns it on");
    assertEquals(EmailFilterService.SEED_IMPORTANT_NAME, seeded.getName());
    assertEquals(EmailFilter.KIND_EXO, seeded.getKind());
    assertEquals(EmailFilter.SCOPE_OWN, seeded.getMailboxScope());
    assertTrue(seeded.isMatchAll());
    assertFalse(seeded.isStopProcessing());
    assertEquals(0, seeded.getPosition());
    assertEquals(List.of(IMPORTANT), seeded.getConditions(), "the category by its stable key");
    assertEquals(1, seeded.getActions().size());
    FilterAction action = seeded.getActions().get(0);
    assertEquals(FilterAction.AGENT, action.type());
    assertEquals(EmailFilterService.SEED_IMPORTANT_INSTRUCTION, action.instruction());
    // Worded as actions, not as "suggest": a model told to suggest lists actions in its answer instead of calling their tools.
    assertTrue(action.instruction().contains("at most three actions"), action.instruction());
    // A task with no project named is a personal one: "a task" alone made the model pick the project tool and invent a project.
    assertTrue(action.instruction().contains("a personal task"), action.instruction());
    assertFalse(action.instruction().contains("suggest"), action.instruction());
    assertEquals(List.of("NOTE"), action.outputs());
    assertTrue(action.suggestActions());
    assertEquals(EmailFilterService.SEED_AGENT_MARKER, action.agentNameId());
    assertEquals(EmailFilterService.SEED_AGENT_MARKER, seeded.getAgentNameId(), "the log and the retry know the rule has an assistant");
    assertEquals(EmailFilterService.SEED_IMPORTANT_VERSION, seedMarker(), "recorded");

    List<EmailFilter> second = service.getFilters(USERNAME, null);

    assertEquals(1, second.size(), "the same copy");
    assertEquals(seeded.getId(), second.get(0).getId());
    verify(emailFilterStorage, times(1)).save(eq(USERNAME), any(), any());
  }

  /**
   * The seeded rule is named in the owner's language, from the settings bundle the
   * drawer reads; the English name only when the bundle cannot be read.
   *
   * @throws Exception never
   */
  @Test
  void theSeededFilterIsNamedInTheOwnersLanguage() throws Exception {
    doReturn(Locale.FRENCH).when(service).seedLocale(USERNAME);
    when(resourceBundleService.getResourceBundle(EmailFilterService.SEED_BUNDLE, Locale.FRENCH)).thenReturn(new ListResourceBundle() {
      @Override
      protected Object[][] getContents() {
        return new Object[][] { { EmailFilterService.SEED_IMPORTANT_NAME_KEY, "Courrier important : ce qu'il attend de moi" } };
      }
    });

    List<EmailFilter> read = service.getFilters(USERNAME, null);

    assertEquals("Courrier important : ce qu'il attend de moi", read.get(0).getName());
  }

  /**
   * A seeded rule gone from the owner's rules, deleted before it could no longer be,
   * never comes back: the marker stays.
   *
   * @throws Exception never
   */
  @Test
  void aDeletedSeedIsNeverRecreated() throws Exception {
    EmailFilter seeded = service.getFilters(USERNAME, null).get(0);

    filters.remove(seeded.getId());

    assertTrue(service.getFilters(USERNAME, null).isEmpty(), "gone for good");
    assertEquals(EmailFilterService.SEED_IMPORTANT_VERSION, seedMarker(), "the marker stays");
    verify(emailFilterStorage, times(1)).save(eq(USERNAME), any(), any());
  }

  /**
   * The seeded rule is the one the product provides: its id is recorded when it is
   * seeded, and the read marks it, and it alone, as provided.
   *
   * @throws Exception never
   */
  @Test
  void theSeededFilterIsMarkedAsProvided() throws Exception {
    assertTrue(service.ensureImportantFilter(USERNAME));
    EmailFilter seeded = filters.values().iterator().next();
    assertEquals(String.valueOf(seeded.getId()), providedMarker(), "recorded when seeded, before any read");
    EmailFilter own = stored(rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));

    List<EmailFilter> read = service.getFilters(USERNAME, null);

    assertTrue(read.stream().filter(filter -> seeded.getId().equals(filter.getId())).findFirst().orElseThrow().isProvided());
    assertFalse(read.stream().filter(filter -> own.getId().equals(filter.getId())).findFirst().orElseThrow().isProvided(),
                "the owner's own rule is not");
  }

  /**
   * The rule the product provides cannot be deleted: the owner could not get it back.
   * The owner's own rules still can.
   *
   * @throws Exception never
   */
  @Test
  void theProvidedFilterCannotBeDeleted() throws Exception {
    EmailFilter seeded = service.getFilters(USERNAME, null).get(0);
    EmailFilter own = stored(rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));

    IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                                                    () -> service.deleteFilter(USERNAME, null, seeded.getId(), false));

    assertEquals(EmailFilterService.PROVIDED, refused.getMessage());
    assertTrue(filters.containsKey(seeded.getId()), "kept");
    verify(emailFilterStorage, never()).delete(seeded.getId(), USERNAME);
    verify(proposalProvider, never()).onFilterDeleted(USERNAME, seeded.getId());

    service.deleteFilter(USERNAME, null, own.getId(), false);

    assertFalse(filters.containsKey(own.getId()), "the owner's own rule is deleted");
  }

  /**
   * An owner seeded before the id was recorded: the oldest of their rules that runs an
   * assistant with the seed's name or the seed's instruction is the provided one, found
   * on the next read and recorded. A rule with the seed's name and no assistant, or an
   * assistant asked something else under another name, is not.
   *
   * @throws Exception never
   */
  @Test
  void aSeedFromBeforeTheRecordedIdIsFoundAndRecorded() throws Exception {
    settings.put(settingKey(Context.USER.id(USERNAME), EmailFilterService.SEED_SCOPE, EmailFilterService.SEED_IMPORTANT_KEY),
                 EmailFilterService.SEED_IMPORTANT_VERSION);
    EmailFilter namedNoAgent = stored(rule(EmailFilterService.SEED_IMPORTANT_NAME,
                                           EmailFilter.KIND_EXO,
                                           List.of(FROM_ACME),
                                           List.of(action(FilterAction.STAR))));
    EmailFilter otherAgent = stored(rule("Invoices", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(agent())));
    EmailFilter renamedSeed = stored(rule("Mine now",
                                          EmailFilter.KIND_EXO,
                                          List.of(IMPORTANT),
                                          List.of(new FilterAction(FilterAction.AGENT,
                                                                   null,
                                                                   null,
                                                                   EmailFilterService.SEED_AGENT_MARKER,
                                                                   EmailFilterService.SEED_IMPORTANT_INSTRUCTION,
                                                                   List.of("NOTE"),
                                                                   null))));
    EmailFilter laterNamed = stored(rule(EmailFilterService.SEED_IMPORTANT_NAME, EmailFilter.KIND_EXO, List.of(IMPORTANT), List.of(agent())));

    List<EmailFilter> read = service.getFilters(USERNAME, null);

    assertEquals(List.of(renamedSeed.getId()), read.stream().filter(EmailFilter::isProvided).map(EmailFilter::getId).toList(),
                 "the oldest seed-shaped rule only, not " + List.of(namedNoAgent.getId(), otherAgent.getId(), laterNamed.getId()));
    assertEquals(String.valueOf(renamedSeed.getId()), providedMarker(), "recorded");
    assertThrows(IllegalArgumentException.class, () -> service.deleteFilter(USERNAME, null, renamedSeed.getId(), false));
  }

  /**
   * An owner seeded before the id was recorded, whose copy kept the seed's name and asks
   * its assistant something else: found by its name.
   *
   * @throws Exception never
   */
  @Test
  void aSeedFromBeforeTheRecordedIdIsFoundByItsName() throws Exception {
    settings.put(settingKey(Context.USER.id(USERNAME), EmailFilterService.SEED_SCOPE, EmailFilterService.SEED_IMPORTANT_KEY),
                 EmailFilterService.SEED_IMPORTANT_VERSION);
    EmailFilter edited = stored(rule(EmailFilterService.SEED_IMPORTANT_NAME, EmailFilter.KIND_EXO, List.of(IMPORTANT), List.of(agent())));

    assertTrue(service.getFilters(USERNAME, null).get(0).isProvided());
    assertEquals(String.valueOf(edited.getId()), providedMarker());
  }

  /**
   * An owner seeded before the id was recorded, whose copy kept the seed's name in their
   * language and asks its assistant something else: found by that name.
   *
   * @throws Exception never
   */
  @Test
  void aSeedFromBeforeTheRecordedIdIsFoundByItsNameInTheOwnersLanguage() throws Exception {
    doReturn(Locale.FRENCH).when(service).seedLocale(USERNAME);
    when(resourceBundleService.getResourceBundle(EmailFilterService.SEED_BUNDLE, Locale.FRENCH)).thenReturn(new ListResourceBundle() {
      @Override
      protected Object[][] getContents() {
        return new Object[][] { { EmailFilterService.SEED_IMPORTANT_NAME_KEY, "Courrier important : ce qu'il attend de moi" } };
      }
    });
    settings.put(settingKey(Context.USER.id(USERNAME), EmailFilterService.SEED_SCOPE, EmailFilterService.SEED_IMPORTANT_KEY),
                 EmailFilterService.SEED_IMPORTANT_VERSION);
    EmailFilter french = stored(rule("Courrier important : ce qu'il attend de moi",
                                     EmailFilter.KIND_EXO,
                                     List.of(IMPORTANT),
                                     List.of(agent())));

    assertTrue(service.getFilters(USERNAME, null).get(0).isProvided());
    assertEquals(String.valueOf(french.getId()), providedMarker());
  }

  /**
   * The reorder answers the rules as the read does: the provided one marked.
   *
   * @throws Exception never
   */
  @Test
  void theReorderAnswerMarksTheProvidedFilter() throws Exception {
    EmailFilter seeded = service.getFilters(USERNAME, null).get(0);
    EmailFilter own = stored(rule("Star", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));

    List<EmailFilter> ordered = service.reorder(USERNAME, null, List.of(own.getId(), seeded.getId()));

    assertEquals(List.of(seeded.getId()), ordered.stream().filter(EmailFilter::isProvided).map(EmailFilter::getId).toList());
  }

  /**
   * An owner never seeded has no provided rule, and nothing is recorded for them, even
   * with a rule shaped as the seed.
   *
   * @throws Exception never
   */
  @Test
  void anOwnerNeverSeededHasNoProvidedFilter() throws Exception {
    System.setProperty(EmailFilterService.SEED_IMPORTANT_PROPERTY, "false");
    EmailFilter named = stored(rule(EmailFilterService.SEED_IMPORTANT_NAME, EmailFilter.KIND_EXO, List.of(IMPORTANT), List.of(agent())));

    assertTrue(service.getFilters(USERNAME, null).stream().noneMatch(EmailFilter::isProvided));
    assertNull(providedMarker());

    service.deleteFilter(USERNAME, null, named.getId(), false);
    assertFalse(filters.containsKey(named.getId()));
  }

  /**
   * A deployment that switched the seed off seeds nothing, and records nothing: switched
   * back on, the owner gets their copy.
   *
   * @throws Exception never
   */
  @Test
  void noSeedWhenTheDeploymentSwitchedItOff() throws Exception {
    System.setProperty(EmailFilterService.SEED_IMPORTANT_PROPERTY, "false");

    assertTrue(service.getFilters(USERNAME, null).isEmpty());
    assertNull(seedMarker(), "nothing recorded");
    verify(emailFilterStorage, never()).save(eq(USERNAME), any(), any());

    System.setProperty(EmailFilterService.SEED_IMPORTANT_PROPERTY, "true");

    assertEquals(1, service.getFilters(USERNAME, null).size());
  }

  /**
   * While the Important category is not imported, the seed waits without a marker, and
   * the next read after the import makes it.
   *
   * @throws Exception never
   */
  @Test
  void theSeedWaitsForTheImportantCategory() throws Exception {
    when(emailBoxService.getDefaultEmailCategoryId(EmailFilterService.SEED_IMPORTANT_CATEGORY)).thenReturn(null);

    assertTrue(service.getFilters(USERNAME, null).isEmpty());
    assertNull(seedMarker(), "tried again next time");
    verify(emailFilterStorage, never()).save(eq(USERNAME), any(), any());

    when(emailBoxService.getDefaultEmailCategoryId(EmailFilterService.SEED_IMPORTANT_CATEGORY)).thenReturn(IMPORTANT_ID);

    assertEquals(1, service.getFilters(USERNAME, null).size());
    assertEquals(EmailFilterService.SEED_IMPORTANT_VERSION, seedMarker());
  }

  /**
   * An owner who already has as many rules as allowed forgoes the seed for good: no
   * rule, and the marker, so a later deletion of theirs never makes room for it.
   *
   * @throws Exception never
   */
  @Test
  void anOwnerAtTheCapForgoesTheSeed() throws Exception {
    for (int i = 0; i < EmailFilterService.MAX_FILTERS; i++) {
      stored(rule("Rule " + i, EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));
    }

    List<EmailFilter> read = service.getFilters(USERNAME, null);

    assertEquals(EmailFilterService.MAX_FILTERS, read.size());
    assertTrue(read.stream().noneMatch(filter -> EmailFilterService.SEED_IMPORTANT_NAME.equals(filter.getName())));
    assertEquals(EmailFilterService.SEED_IMPORTANT_VERSION, seedMarker(), "forgone, recorded");
    verify(emailFilterStorage, never()).save(eq(USERNAME), any(), any());
  }

  /**
   * Two first reads at once make one copy: the second waits for the first's insert and
   * finds its marker.
   *
   * @throws Exception never
   */
  @Test
  void twoFirstReadsAtOnceSeedOneFilter() throws Exception {
    CountDownLatch bothStarted = new CountDownLatch(2);
    ExecutorService readers = Executors.newFixedThreadPool(2);
    try {
      List<java.util.concurrent.Future<List<EmailFilter>>> reads = new ArrayList<>();
      for (int i = 0; i < 2; i++) {
        reads.add(readers.submit(() -> {
          bothStarted.countDown();
          bothStarted.await(5, TimeUnit.SECONDS);
          return service.getFilters(USERNAME, null);
        }));
      }
      for (java.util.concurrent.Future<List<EmailFilter>> read : reads) {
        assertEquals(1, read.get(5, TimeUnit.SECONDS).size(), "each read sees the one copy");
      }
    } finally {
      readers.shutdownNow();
    }
    assertEquals(1, filters.size());
    verify(emailFilterStorage, times(1)).save(eq(USERNAME), any(), any());
  }

  /**
   * An owner without a connected mailbox has no rules to read, and no seed: they get it
   * when they connect.
   */
  @Test
  void noSeedWithoutAConnectedMailbox() {
    when(userEmailSettingService.getUserEmailSetting(USERNAME)).thenReturn(null);

    assertThrows(ObjectNotFoundException.class, () -> service.ensureImportantFilter(USERNAME));

    assertNull(seedMarker());
    verify(emailFilterStorage, never()).save(eq(USERNAME), any(), any());
  }

  /**
   * The connection's seed, through {@link EmailFilterService#ensureImportantFilter}: made
   * once, and answered as such.
   *
   * @throws Exception never
   */
  @Test
  void theConnectionSeedsOnceToo() throws Exception {
    assertTrue(service.ensureImportantFilter(USERNAME), "created");
    assertFalse(service.ensureImportantFilter(USERNAME), "already there");

    assertEquals(1, service.getFilters(USERNAME, null).size());
  }

  /**
   * The owner's seed marker, as the settings hold it.
   *
   * @return the version recorded, or null
   */
  private String seedMarker() {
    return settings.get(settingKey(Context.USER.id(USERNAME), EmailFilterService.SEED_SCOPE, EmailFilterService.SEED_IMPORTANT_KEY));
  }

  /**
   * The recorded id of the owner's provided rule.
   *
   * @return the id, as stored; null when none
   */
  private String providedMarker() {
    return settings.get(settingKey(Context.USER.id(USERNAME), EmailFilterService.SEED_SCOPE, EmailFilterService.SEED_IMPORTANT_ID_KEY));
  }

  /**
   * The settings, as one map behind the mock.
   */
  private void fakeSettings() {
    lenient().when(settingService.get(any(Context.class), any(Scope.class), anyString())).thenAnswer(invocation -> {
      String value = settings.get(settingKey(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)));
      return value == null ? null : SettingValue.create(value);
    });
    lenient().doAnswer(invocation -> {
      SettingValue<?> value = invocation.getArgument(3);
      settings.put(settingKey(invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2)),
                   String.valueOf(value.getValue()));
      return null;
    }).when(settingService).set(any(Context.class), any(Scope.class), anyString(), any());
  }

  /**
   * One setting's key in the map.
   *
   * @param context its context
   * @param scope its scope
   * @param key its key
   * @return the map key
   */
  private static String settingKey(Context context, Scope scope, String key) {
    return context.getName() + "/" + context.getId() + "/" + scope.getName() + "/" + scope.getId() + "/" + key;
  }

  /**
   * The storage, as two maps behind the mock.
   */
  private void fakeStorage() {
    lenient().when(emailFilterStorage.getFilters(USERNAME)).thenAnswer(invocation -> new ArrayList<>(filters.values().stream().map(this::copy).toList()));
    lenient().when(emailFilterStorage.getFilter(anyLong(), eq(USERNAME)))
             .thenAnswer(invocation -> Optional.ofNullable(filters.get(invocation.<Long> getArgument(0))).map(this::copy));
    lenient().when(emailFilterStorage.countEnabled(USERNAME)).thenAnswer(invocation -> filters.values().stream().filter(EmailFilter::isEnabled).count());
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
    lenient().when(emailFilterStorage.createMatch(any(), eq(USERNAME))).thenAnswer(invocation -> {
      EmailFilterMatch match = invocation.getArgument(0);
      boolean known = matches.values()
                             .stream()
                             .anyMatch(other -> other.getFilterId().equals(match.getFilterId())
                                 && other.getMailHeaderId().equals(match.getMailHeaderId()));
      if (known) {
        return Optional.empty();
      }
      match.setId(ids.incrementAndGet());
      matches.put(match.getId(), match);
      return Optional.of(match);
    });
    lenient().when(emailFilterStorage.updateMatch(any(), eq(USERNAME))).thenAnswer(invocation -> {
      EmailFilterMatch match = invocation.getArgument(0);
      matches.put(match.getId(), match);
      return match;
    });
    lenient().when(emailFilterStorage.getMatch(anyLong(), eq(USERNAME)))
             .thenAnswer(invocation -> Optional.ofNullable(matches.get(invocation.<Long> getArgument(0))));
  }

  /**
   * Stores a rule, validated the way a save would, a hop named after its id.
   *
   * @param filter the rule
   * @return the rule as stored
   */
  private EmailFilter stored(EmailFilter filter) {
    EmailFilter copy = copy(filter);
    copy.setId(ids.incrementAndGet());
    copy.setPosition(filters.size());
    if (copy.getMailboxScope() == null) {
      copy.setMailboxScope(EmailFilter.SCOPE_OWN);
    }
    if (EmailFilter.KIND_HOP.equals(copy.getKind())) {
      EmailFilterService.withHopNames(copy);
    }
    copy.getActions()
        .stream()
        .filter(action -> FilterAction.AGENT.equals(action.type()))
        .findFirst()
        .ifPresent(action -> copy.setAgentNameId(action.agentNameId()));
    filters.put(copy.getId(), copy);
    return copy(copy);
  }

  /**
   * Stores a match of a rule with an assistant and a move, its move held for the
   * assistant.
   *
   * @param uid the mail's inbox UID
   * @param agentStatus the assistant's status
   * @return the match
   */
  private EmailFilterMatch waitingMatch(long uid, String agentStatus) {
    EmailFilter filter = stored(rule("Invoices", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(agent(), move("CUSTOM:3"))));
    EmailFilterMatch match = new EmailFilterMatch();
    match.setId(ids.incrementAndGet());
    match.setFilterId(filter.getId());
    match.setMailHeaderId("<" + uid + "@acme.com>");
    match.setMailRemoteId(uid);
    match.setMatchedDate(NOW);
    match.setActions(List.of());
    match.setAgentNameId("EMAIL_FILTER_ASSISTANT");
    match.setAgentStatus(agentStatus);
    match.setPostActionsState(EmailFilterMatch.POST_PENDING_AGENT);
    matches.put(match.getId(), match);
    return copyOf(match);
  }

  /**
   * A copy of a match, as a read from the storage returns one.
   *
   * @param match the match
   * @return the copy
   */
  private static EmailFilterMatch copyOf(EmailFilterMatch match) {
    EmailFilterMatch copy = new EmailFilterMatch();
    copy.setId(match.getId());
    copy.setFilterId(match.getFilterId());
    copy.setMailHeaderId(match.getMailHeaderId());
    copy.setMailRemoteId(match.getMailRemoteId());
    copy.setMatchedDate(match.getMatchedDate());
    copy.setActions(match.getActions());
    copy.setAgentNameId(match.getAgentNameId());
    copy.setAgentStatus(match.getAgentStatus());
    copy.setPostActionsState(match.getPostActionsState());
    return copy;
  }

  /**
   * The handler's writes: RUNNING counts nothing; a failed attempt given back as PENDING
   * counts one and keeps its error, and is not an end -- no output, post-actions still
   * held; SKIPPED_DISABLED ends the run like DONE; a status the handler never writes is
   * refused.
   *
   * @throws Exception never
   */
  @Test
  void theAssistantsAttemptsAreCountedAcrossRuns() throws Exception {
    EmailFilterMatch match = storedMatch();
    match.setAgentStatus(EmailFilterMatch.AGENT_PENDING);
    match.setPostActionsState(EmailFilterMatch.POST_PENDING_AGENT);

    EmailFilterMatch running = service.saveAgentOutcome(match.getId(), USERNAME, EmailFilterMatch.AGENT_RUNNING, "c-1", null, null);
    assertEquals(0, running.getAgentAttempts());
    assertEquals("c-1", running.getAgentConversationId());

    EmailFilterMatch givenBack = service.saveAgentOutcome(match.getId(), USERNAME, EmailFilterMatch.AGENT_PENDING, null, "{}", "timeout");
    assertEquals(EmailFilterMatch.AGENT_PENDING, givenBack.getAgentStatus());
    assertEquals(1, givenBack.getAgentAttempts());
    assertEquals("timeout", givenBack.getLastError());
    assertNull(givenBack.getAgentOutput(), "an attempt given back has no answer");
    assertEquals(EmailFilterMatch.POST_PENDING_AGENT, givenBack.getPostActionsState());

    EmailFilterMatch skipped = service.saveAgentOutcome(match.getId(),
                                                        USERNAME,
                                                        EmailFilterMatch.AGENT_SKIPPED_DISABLED,
                                                        null,
                                                        null,
                                                        "emailConnector.filters.agent.disabled");
    assertEquals(2, skipped.getAgentAttempts());
    assertEquals(EmailFilterMatch.AGENT_SKIPPED_DISABLED, skipped.getAgentStatus());

    assertThrows(IllegalArgumentException.class,
                 () -> service.saveAgentOutcome(match.getId(), USERNAME, EmailFilterMatch.AGENT_SKIPPED_CAP, null, null, null));
    assertThrows(IllegalArgumentException.class,
                 () -> service.saveAgentOutcome(match.getId(), USERNAME, EmailFilterMatch.AGENT_NONE, null, null, null));
  }

  /**
   * A match whose assistant could not be reached is parked: PENDING again, no attempt
   * counted, its post-actions still held.
   *
   * @throws Exception never
   */
  @Test
  void anUnreachableAssistantParksTheMatchWithoutCountingAnAttempt() throws Exception {
    EmailFilterMatch match = storedMatch();
    match.setAgentStatus(EmailFilterMatch.AGENT_RUNNING);
    match.setAgentAttempts(1);
    match.setPostActionsState(EmailFilterMatch.POST_PENDING_AGENT);

    EmailFilterMatch parked = service.parkAgentMatch(match.getId(), USERNAME, "emailConnector.filters.agent.unavailable");

    assertEquals(EmailFilterMatch.AGENT_PENDING, parked.getAgentStatus());
    assertEquals(1, parked.getAgentAttempts(), "no attempt counted");
    assertEquals("emailConnector.filters.agent.unavailable", parked.getLastError());
    assertEquals(EmailFilterMatch.POST_PENDING_AGENT, parked.getPostActionsState());
  }

  /**
   * The handler's read asks the storage for the waiting matches and the running ones
   * last written before the given date, bounded.
   */
  @Test
  void theHandlerReadsWaitingAndAbandonedMatches() {
    EmailFilterMatch due = storedMatch();
    when(emailFilterStorage.getMatchesDueForAgent(USERNAME, new Date(NOW - 1_800_000L), EmailFilterService.MAX_LOG)).thenReturn(List.of(due));

    assertEquals(List.of(due), service.listPendingAgentMatches(USERNAME, 1_000, NOW - 1_800_000L));
    verify(emailFilterStorage).getMatchesDueForAgent(USERNAME, new Date(NOW - 1_800_000L), EmailFilterService.MAX_LOG);
  }

  /**
   * Stores a match with actions, of a rule that still exists.
   *
   * @param actions what it did
   * @return the match
   */
  private EmailFilterMatch storedMatch(AppliedAction... actions) {
    EmailFilter filter = stored(rule("R", EmailFilter.KIND_EXO, List.of(FROM_ACME), List.of(action(FilterAction.STAR))));
    EmailFilterMatch match = new EmailFilterMatch();
    match.setId(ids.incrementAndGet());
    match.setFilterId(filter.getId());
    match.setMailHeaderId("<one@acme.com>");
    match.setMailRemoteId(1L);
    match.setMatchedDate(NOW);
    match.setActions(List.of(actions));
    match.setAgentStatus(EmailFilterMatch.AGENT_NONE);
    match.setPostActionsState(EmailFilterMatch.POST_DONE);
    matches.put(match.getId(), match);
    return match;
  }

  /**
   * An enabled rule of the owner's own mailbox.
   *
   * @param name its name
   * @param kind its kind
   * @param conditions its conditions
   * @param actions its actions
   * @return the rule
   */
  private static EmailFilter rule(String name, String kind, List<Condition> conditions, List<FilterAction> actions) {
    EmailFilter filter = new EmailFilter();
    filter.setName(name);
    filter.setKind(kind);
    filter.setEnabled(true);
    filter.setMatchAll(true);
    filter.setConditions(conditions);
    filter.setActions(actions);
    return filter;
  }

  /**
   * A rule that also runs at delivery, starring what it matches.
   *
   * @param name its name
   * @param condition its condition
   * @return the rule
   */
  private static EmailFilter hop(String name, Condition condition) {
    return rule(name, EmailFilter.KIND_HOP, List.of(condition), List.of(action(FilterAction.STAR)));
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
   * The assistant action.
   *
   * @return the action
   */
  private static FilterAction agent() {
    return new FilterAction(FilterAction.AGENT, null, null, "EMAIL_FILTER_ASSISTANT", "Extract the invoice.", List.of("NOTE", "STAR"), null);
  }

  /**
   * One of the owner's own mirrored folders.
   *
   * @return the folder
   */
  private static EmailFolder ownFolder() {
    EmailFolder folder = new EmailFolder();
    folder.setSyncEnabled(true);
    return folder;
  }

  /**
   * A cached inbox mail, unread and unstarred.
   *
   * @param uid its UID
   * @param from its sender
   * @param subject its subject
   * @return the mail
   */
  private static Email mail(long uid, String from, String subject) {
    Email email = new Email();
    email.setId(1000 + uid);
    email.setMailRemoteId(uid);
    email.setMailHeaderId("<" + uid + "@acme.com>");
    email.setUserId(USERNAME);
    email.setSubject(subject);
    email.setFolder(MailFolder.INBOX);
    EmailSender sender = new EmailSender();
    sender.setAddress(from);
    email.setSender(sender);
    email.setTo(List.of());
    email.setCc(List.of());
    return email;
  }

  /**
   * The mailbox answers these mails by UID, in the inbox.
   *
   * @param mails the mails
   */
  private void givenNewMail(Email... mails) {
    for (Email mail : mails) {
      try {
        lenient().when(emailBoxService.getEmailByMailRemoteIdAndUserId(mail.getMailRemoteId(), USERNAME, MailFolder.INBOX, true, true, false, false))
                 .thenReturn(mail);
      } catch (IllegalAccessException e) {
        throw new IllegalStateException(e);
      }
    }
  }

  /**
   * A new inbox mail as the sync reads it.
   *
   * @param uid its UID
   * @param keywords its keywords
   * @return the mail
   */
  private static InboxMail inbox(long uid, String... keywords) {
    return new InboxMail(uid, Set.of(keywords), name -> null, () -> null);
  }

  /**
   * The hops of the last reconciliation.
   *
   * @return the hops
   * @throws Exception never
   */
  @SuppressWarnings("unchecked")
  private List<HopRef> publishedHops() throws Exception {
    ArgumentCaptor<List<HopRef>> hops = ArgumentCaptor.forClass(List.class);
    verify(emailServerRuleService, org.mockito.Mockito.atLeastOnce()).reconcileHops(eq(USERNAME),
                                                                                    hops.capture(),
                                                                                    anyBoolean(),
                                                                                    anyBoolean(),
                                                                                    anyBoolean());
    return hops.getValue();
  }

  /**
   * An empty reconciliation report.
   *
   * @return the report
   */
  private static ReconcileReport report() {
    return new ReconcileReport(List.of(), List.of(), null);
  }

  /**
   * A copy of a rule, so the fake storage never shares an instance with the service.
   *
   * @param filter the rule
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
                           filter.getUpdatedDate(),
                           filter.isProvided());
  }
}
