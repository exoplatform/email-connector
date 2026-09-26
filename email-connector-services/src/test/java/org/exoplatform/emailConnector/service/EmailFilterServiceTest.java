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
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
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
import java.util.Date;
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
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

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
import org.exoplatform.emailConnector.service.filters.FilterRunContext;
import org.exoplatform.emailConnector.storage.EmailFilterStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.ListenerService;

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

  @Spy
  @InjectMocks
  private EmailFilterService           service;

  private final Map<Long, EmailFilter> filters      = new TreeMap<>();

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
    fakeStorage();
  }

  /**
   * Clears the properties a test set.
   */
  @AfterEach
  void tearDown() {
    System.clearProperty(EmailFilterService.AGENT_ENABLED_PROPERTY);
    System.clearProperty(EmailFilterService.MAX_PENDING_PROPERTY);
    System.clearProperty(EmailFilterService.ENABLED_PROPERTY);
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
    verify(listenerService, never()).broadcast(eq(EmailConnectorUtils.FILTER_AGENT_REQUESTED), any(), any());
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
                           filter.getUpdatedDate());
  }
}
