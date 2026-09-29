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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.model.EmailFilterMatch;
import org.exoplatform.emailConnector.model.EmailFilterProposal;
import org.exoplatform.emailConnector.model.EmailFilterSuggestionCounts;
import org.exoplatform.emailConnector.plugin.EmailFilterAgentHandler;
import org.exoplatform.emailConnector.storage.EmailFilterProposalStorage;
import org.exoplatform.emailConnector.storage.EmailFilterStorage;

/**
 * The proposals' rules: recorded only while the match's assistant runs, once per call,
 * within the caps; decided by the owner only, once, before the expiry; an approval runs
 * through the handler as the owner and records how the tool ended; a handed-over or
 * rejected proposal never runs. The storage is a map, its conditional UPDATEs written
 * as the database runs them.
 */
@ExtendWith(MockitoExtension.class)
public class EmailFilterProposalServiceTest {

  private static final String                     OWNER    = "alice";

  private static final String                     OTHER    = "bob";

  private static final long                       NOW      = 1_790_000_000_000L;

  private static final long                       MATCH_ID = 7L;

  private static final String                     RUN      = "run-1";

  @Mock
  private EmailFilterProposalStorage              storage;

  @Mock
  private EmailFilterStorage                      emailFilterStorage;

  @Mock
  private EmailFilterService                      emailFilterService;

  @Mock
  private ObjectProvider<EmailFilterAgentHandler> agentHandlers;

  @Mock
  private EmailFilterAgentHandler                 handler;

  @Mock
  private EmailFilterSuggestionDigest             suggestionDigest;

  @InjectMocks
  private EmailFilterProposalService              service;

  /** The rows, by id; each keeps its owner in {@link #owners}. */
  private final Map<Long, EmailFilterProposal>    rows     = new TreeMap<>();

  private final Map<Long, String>                 owners   = new TreeMap<>();

  private final AtomicLong                        ids      = new AtomicLong(100);

  private EmailFilterMatch                        match;

  /**
   * A running match of the owner's, a storage in a map, a handler present.
   */
  @BeforeEach
  void setUp() {
    service.setClock(Clock.fixed(Instant.ofEpochMilli(NOW), ZoneOffset.UTC));
    match = new EmailFilterMatch();
    match.setId(MATCH_ID);
    match.setFilterId(3L);
    match.setAgentStatus(EmailFilterMatch.AGENT_RUNNING);
    lenient().when(emailFilterStorage.getMatch(MATCH_ID, OWNER)).thenReturn(Optional.of(match));
    lenient().when(emailFilterStorage.getMatch(MATCH_ID, OTHER)).thenReturn(Optional.empty());
    lenient().when(agentHandlers.stream()).thenAnswer(invocation -> Stream.of(handler));
    lenient().when(storage.create(any(), anyString())).thenAnswer(invocation -> {
      EmailFilterProposal proposal = copy(invocation.getArgument(0));
      if (rows.values().stream().anyMatch(row -> row.getMatchId().equals(proposal.getMatchId()) && sameCall(row, proposal))) {
        return Optional.empty();
      }
      proposal.setId(ids.incrementAndGet());
      rows.put(proposal.getId(), proposal);
      owners.put(proposal.getId(), invocation.getArgument(1));
      return Optional.of(copy(proposal));
    });
    lenient().when(storage.update(any(), anyString())).thenAnswer(invocation -> {
      EmailFilterProposal proposal = copy(invocation.getArgument(0));
      if (!invocation.getArgument(1).equals(owners.get(proposal.getId()))) {
        return Optional.empty();
      }
      rows.put(proposal.getId(), proposal);
      return Optional.of(copy(proposal));
    });
    lenient().when(storage.get(anyLong(), anyString())).thenAnswer(invocation -> Optional.ofNullable(owned(invocation.getArgument(0),
                                                                                                         invocation.getArgument(1)))
                                                                                         .map(EmailFilterProposalServiceTest::copy));
    lenient().when(storage.exists(anyLong())).thenAnswer(invocation -> rows.containsKey(invocation.<Long> getArgument(0)));
    lenient().when(storage.getByCall(anyString(), anyLong(), anyString(), anyString())).thenAnswer(invocation -> {
      EmailFilterProposal probe = new EmailFilterProposal();
      probe.setToolName(invocation.getArgument(2));
      probe.setArguments(invocation.getArgument(3));
      return rows.values()
                 .stream()
                 .filter(row -> invocation.getArgument(0).equals(owners.get(row.getId())))
                 .filter(row -> row.getMatchId() == (long) invocation.getArgument(1) && sameCall(row, probe))
                 .findFirst()
                 .map(EmailFilterProposalServiceTest::copy);
    });
    lenient().when(storage.countByRun(anyString(), anyLong(), anyString()))
             .thenAnswer(invocation -> rows.values()
                                           .stream()
                                           .filter(row -> invocation.getArgument(0).equals(owners.get(row.getId())))
                                           .filter(row -> row.getMatchId() == (long) invocation.getArgument(1))
                                           .filter(row -> invocation.getArgument(2).equals(row.getConversationId()))
                                           .count());
    lenient().when(storage.countByStatus(anyString(), anyString()))
             .thenAnswer(invocation -> rows.values()
                                           .stream()
                                           .filter(row -> invocation.getArgument(0).equals(owners.get(row.getId())))
                                           .filter(row -> invocation.getArgument(1).equals(row.getStatus()))
                                           .count());
    lenient().when(storage.claim(anyLong(), anyString(), anyString(), anyString(), any())).thenAnswer(invocation -> {
      EmailFilterProposal row = owned(invocation.getArgument(0), invocation.getArgument(1));
      Date now = invocation.getArgument(4);
      if (row == null || !invocation.getArgument(2).equals(row.getStatus()) || row.getExpiresDate() <= now.getTime()) {
        return false;
      }
      row.setStatus(invocation.getArgument(3));
      row.setDecidedDate(now.getTime());
      return true;
    });
    lenient().when(storage.finish(anyLong(), anyString(), anyString(), any(), any())).thenAnswer(invocation -> {
      EmailFilterProposal row = owned(invocation.getArgument(0), invocation.getArgument(1));
      if (row == null || !EmailFilterProposal.RUNNING.equals(row.getStatus())) {
        return false;
      }
      row.setStatus(invocation.getArgument(2));
      row.setResult(invocation.getArgument(3));
      row.setLastError(invocation.getArgument(4));
      return true;
    });
    lenient().when(storage.expireDue(anyString(), any())).thenAnswer(invocation -> {
      Date now = invocation.getArgument(1);
      int count = 0;
      for (EmailFilterProposal row : rows.values()) {
        if (invocation.getArgument(0).equals(owners.get(row.getId())) && EmailFilterProposal.PROPOSED.equals(row.getStatus())
            && row.getExpiresDate() <= now.getTime()) {
          row.setStatus(EmailFilterProposal.EXPIRED);
          count++;
        }
      }
      return count;
    });
    lenient().when(storage.setRationale(anyLong(), anyString(), anyLong(), anyString())).thenAnswer(invocation -> {
      EmailFilterProposal row = owned(invocation.getArgument(0), invocation.getArgument(1));
      if (row == null || row.getMatchId() != (long) invocation.getArgument(2) || !EmailFilterProposal.PROPOSED.equals(row.getStatus())) {
        return false;
      }
      row.setRationale(invocation.getArgument(3));
      return true;
    });
    lenient().when(storage.expireOfMatch(anyString(), anyLong(), anyString(), any())).thenAnswer(invocation -> {
      int count = 0;
      for (EmailFilterProposal row : rows.values()) {
        if (invocation.getArgument(0).equals(owners.get(row.getId())) && row.getMatchId() == (long) invocation.getArgument(1)
            && EmailFilterProposal.PROPOSED.equals(row.getStatus())) {
          row.setStatus(EmailFilterProposal.EXPIRED);
          row.setLastError(invocation.getArgument(2));
          count++;
        }
      }
      return count;
    });
  }

  /**
   * Clears the caps' properties a test set.
   */
  @AfterEach
  void tearDown() {
    System.clearProperty(EmailFilterProposalService.MAX_PER_RUN_PROPERTY);
    System.clearProperty(EmailFilterProposalService.MAX_PENDING_PROPERTY);
    System.clearProperty(EmailFilterProposalService.TTL_DAYS_PROPERTY);
  }

  /**
   * A call is recorded as the model gave it, waiting, for the owner's match, with the
   * tool's own title and description, and the expiry of the default fourteen days.
   *
   * @throws Exception never
   */
  @Test
  void aCallIsRecordedAsGivenAndWaits() throws Exception {
    EmailFilterProposal proposal = propose("create_task_in_project", "{\"title\":\"Pay\",\"project_id\":42}");

    assertEquals(EmailFilterProposal.PROPOSED, proposal.getStatus());
    assertEquals("{\"title\":\"Pay\",\"project_id\":42}", proposal.getArguments(), "never rewritten");
    assertEquals("Create task", proposal.getToolTitle());
    assertEquals("Creates a task in a project", proposal.getToolDescription());
    assertEquals(3L, proposal.getFilterId());
    assertEquals(RUN, proposal.getConversationId());
    assertEquals(NOW + 14L * 24 * 3600 * 1000, proposal.getExpiresDate());
  }

  /**
   * Only a match whose assistant runs records a call, and only its owner's: a call for
   * a finished run, or for someone else's match, is refused.
   */
  @Test
  void onlyARunningAssistantOfTheOwnerRecords() {
    match.setAgentStatus(EmailFilterMatch.AGENT_DONE);
    assertEquals(EmailFilterProposalService.NOT_RUNNING,
                 assertThrows(IllegalStateException.class, () -> propose("create_task_in_project", "{}")).getMessage());
    match.setAgentStatus(EmailFilterMatch.AGENT_RUNNING);
    assertThrows(ObjectNotFoundException.class,
                 () -> service.createProposal(OTHER, MATCH_ID, RUN, "create_task_in_project", null, null, "{}"));
    assertThrows(IllegalArgumentException.class, () -> propose("not a tool", "{}"));
    assertEquals(0, rows.size());
  }

  /**
   * One call is recorded once per match, whatever the order of its keys; a call a later
   * run superseded is put back to wait, not duplicated.
   *
   * @throws Exception never
   */
  @Test
  void oneCallIsRecordedOnceAndASupersededOneComesBack() throws Exception {
    EmailFilterProposal first = propose("create_task_in_project", "{\"a\":1,\"b\":2}");
    EmailFilterProposal again = propose("create_task_in_project", "{\"a\":1,\"b\":2}");
    assertEquals(first.getId(), again.getId());
    assertEquals(1, rows.size());

    assertEquals(1, service.supersede(OWNER, MATCH_ID));
    assertEquals(EmailFilterProposal.EXPIRED, rows.get(first.getId()).getStatus());
    EmailFilterProposal back = service.createProposal(OWNER, MATCH_ID, "run-2", "create_task_in_project", null, null, "{\"a\":1,\"b\":2}");
    assertEquals(first.getId(), back.getId());
    assertEquals(EmailFilterProposal.PROPOSED, back.getStatus());
    assertNull(back.getLastError());
    assertEquals("run-2", back.getConversationId());
  }

  /**
   * A run records at most three calls, the fourth refused; and a mailbox holds at most
   * its pending cap of waiting ones.
   *
   * @throws Exception never
   */
  @Test
  void theCapsRefuseTheCallPastThem() throws Exception {
    propose("t1", "{}");
    propose("t2", "{}");
    propose("t3", "{}");
    assertEquals(EmailFilterProposalService.RUN_CAP, assertThrows(IllegalStateException.class, () -> propose("t4", "{}")).getMessage());
    assertEquals(3, rows.size());

    System.setProperty(EmailFilterProposalService.MAX_PENDING_PROPERTY, "3");
    assertEquals(EmailFilterProposalService.PENDING_CAP,
                 assertThrows(IllegalStateException.class,
                              () -> service.createProposal(OWNER, MATCH_ID, "run-2", "t5", null, null, "{}")).getMessage());
    assertEquals(3, rows.size());
  }

  /**
   * Approving runs the call once, through the handler, as the owner, and records the
   * tool's answer; a second approval is refused and runs nothing.
   *
   * @throws Exception never
   */
  @Test
  void anApprovalRunsTheCallOnce() throws Exception {
    EmailFilterProposal proposal = propose("create_task_in_project", "{\"title\":\"Pay\"}");
    when(handler.executeProposal(eq(OWNER), any())).thenReturn("{\"id\":\"task-9\"}");

    EmailFilterProposal done = service.approve(OWNER, null, proposal.getId());

    assertEquals(EmailFilterProposal.DONE, done.getStatus());
    assertEquals("{\"id\":\"task-9\"}", done.getResult());
    assertEquals(NOW, done.getDecidedDate());
    verify(emailFilterService).checkOwnMailbox(OWNER, null);
    assertEquals(EmailFilterProposalService.NOT_PENDING,
                 assertThrows(IllegalStateException.class, () -> service.approve(OWNER, null, proposal.getId())).getMessage());
    verify(handler).executeProposal(eq(OWNER), any());
  }

  /**
   * The mails the list marks are read for the caller, at the service's clock, once their
   * own mailbox is checked; a delegation is refused before anything is read.
   *
   * @throws Exception never
   */
  @Test
  void theWaitingMailsAreTheCallersOwn() throws Exception {
    when(storage.getWaitingMailHeaderIds(OWNER, new Date(NOW))).thenReturn(List.of("<a@x>"));

    assertEquals(List.of("<a@x>"), service.getWaitingMails(OWNER, null));
    verify(emailFilterService).checkOwnMailbox(OWNER, null);

    doThrow(new IllegalAccessException("emailConnector.rules.ownMailboxOnly")).when(emailFilterService).checkOwnMailbox(OWNER, 12L);
    assertThrows(IllegalAccessException.class, () -> service.getWaitingMails(OWNER, 12L));
    verify(storage).getWaitingMailHeaderIds(anyString(), any());
  }

  /**
   * Someone else's approval is refused with the refusal, 403 at the REST, and runs
   * nothing; a missing proposal is not found; a delegation's refusal comes first.
   *
   * @throws Exception never
   */
  @Test
  void onlyTheOwnerApproves() throws Exception {
    EmailFilterProposal proposal = propose("create_task_in_project", "{}");

    assertEquals(EmailFilterProposalService.NOT_YOURS,
                 assertThrows(IllegalAccessException.class, () -> service.approve(OTHER, null, proposal.getId())).getMessage());
    assertThrows(ObjectNotFoundException.class, () -> service.approve(OWNER, null, 999L));
    doThrow(new IllegalAccessException("emailConnector.rules.ownMailboxOnly")).when(emailFilterService).checkOwnMailbox(OWNER, 12L);
    assertThrows(IllegalAccessException.class, () -> service.approve(OWNER, 12L, proposal.getId()));
    assertEquals(EmailFilterProposal.PROPOSED, rows.get(proposal.getId()).getStatus());
    verify(handler, never()).executeProposal(anyString(), any());
  }

  /**
   * A proposal past its expiry cannot be approved: it is marked expired and nothing runs.
   *
   * @throws Exception never
   */
  @Test
  void anExpiredProposalIsNeverApproved() throws Exception {
    EmailFilterProposal proposal = propose("create_task_in_project", "{}");
    rows.get(proposal.getId()).setExpiresDate(NOW);

    assertEquals(EmailFilterProposalService.EXPIRED,
                 assertThrows(IllegalStateException.class, () -> service.approve(OWNER, null, proposal.getId())).getMessage());
    assertEquals(EmailFilterProposal.EXPIRED, rows.get(proposal.getId()).getStatus());
    verify(handler, never()).executeProposal(anyString(), any());
  }

  /**
   * The waiting suggestions are told as one digest (EXO-90668): recording a run's calls
   * notifies nothing, the run's end publishes the one digest with the count that waits;
   * each decision brings it to the new count, down to none.
   *
   * @throws Exception never
   */
  @Test
  void theWaitingSuggestionsAreToldAsOneDigestAndEachDecisionUpdatesIt() throws Exception {
    EmailFilterProposal first = propose("t1", "{}");
    EmailFilterProposal second = propose("t2", "{}");
    EmailFilterProposal third = propose("t3", "{}");
    verifyNoInteractions(suggestionDigest);

    service.notifyWaiting(OWNER);

    verify(suggestionDigest).publish(OWNER, 3L);
    service.reject(OWNER, null, first.getId());
    verify(suggestionDigest).refresh(OWNER, 2L);
    service.approve(OWNER, null, second.getId());
    verify(suggestionDigest).refresh(OWNER, 1L);
    service.handOver(OWNER, null, third.getId());
    verify(suggestionDigest).refresh(OWNER, 0L);
    verify(suggestionDigest, times(1)).publish(anyString(), anyLong());
  }

  /**
   * The counts per rule (EXO-90668) are the caller's own mailbox's, read once the due
   * proposals are marked expired so an unanswered one counts as expired.
   *
   * @throws Exception never
   */
  @Test
  void theCountsPerRuleAreTheCallersOnceTheDueOnesExpired() throws Exception {
    List<EmailFilterSuggestionCounts> counts = List.of(new EmailFilterSuggestionCounts(3L, 2, 1, 1, 0, 4));
    when(storage.countByFilter(OWNER)).thenReturn(counts);

    assertEquals(counts, service.getSuggestionCounts(OWNER, null));

    InOrder order = inOrder(emailFilterService, storage);
    order.verify(emailFilterService).checkOwnMailbox(OWNER, null);
    order.verify(storage).expireDue(eq(OWNER), any());
    order.verify(storage).countByFilter(OWNER);
    doThrow(new IllegalAccessException("emailConnector.rules.ownMailboxOnly")).when(emailFilterService).checkOwnMailbox(OWNER, 9L);
    assertThrows(IllegalAccessException.class, () -> service.getSuggestionCounts(OWNER, 9L));
  }

  /**
   * A handed-over proposal can never be approved nor rejected afterwards; a rejected one
   * neither.
   *
   * @throws Exception never
   */
  @Test
  void aHandedOverOrRejectedProposalNeverRuns() throws Exception {
    EmailFilterProposal handed = propose("t1", "{}");
    EmailFilterProposal rejected = propose("t2", "{}");

    assertEquals(EmailFilterProposal.HANDED_OVER, service.handOver(OWNER, null, handed.getId()).getStatus());
    assertEquals(EmailFilterProposal.REJECTED, service.reject(OWNER, null, rejected.getId()).getStatus());
    for (Long id : List.of(handed.getId(), rejected.getId())) {
      assertThrows(IllegalStateException.class, () -> service.approve(OWNER, null, id));
      assertThrows(IllegalStateException.class, () -> service.handOver(OWNER, null, id));
    }
    assertEquals(EmailFilterProposal.HANDED_OVER, rows.get(handed.getId()).getStatus());
    verify(handler, never()).executeProposal(anyString(), any());
  }

  /**
   * A tool that refuses or fails leaves the proposal failed with its message; without a
   * handler the proposal fails with the code that says nothing runs tools here.
   *
   * @throws Exception never
   */
  @Test
  void aFailedToolIsRecordedWithItsMessage() throws Exception {
    EmailFilterProposal refused = propose("t1", "{}");
    when(handler.executeProposal(eq(OWNER), any())).thenThrow(new IllegalStateException("You can no longer create tasks in Accounting"));
    EmailFilterProposal failed = service.approve(OWNER, null, refused.getId());
    assertEquals(EmailFilterProposal.FAILED, failed.getStatus());
    assertEquals("You can no longer create tasks in Accounting", failed.getLastError());

    EmailFilterProposal orphan = propose("t2", "{}");
    when(agentHandlers.stream()).thenAnswer(invocation -> Stream.empty());
    assertEquals(EmailFilterProposalService.UNAVAILABLE, service.approve(OWNER, null, orphan.getId()).getLastError());
  }

  /**
   * An Error out of the handler, not an Exception, still ends the call: the row never
   * stays RUNNING, so the card never hangs on "Running...".
   *
   * @throws Exception never
   */
  @Test
  void anErrorOutOfTheToolStillEndsTheCall() throws Exception {
    EmailFilterProposal proposal = propose("t1", "{}");
    when(handler.executeProposal(eq(OWNER), any())).thenThrow(new NoClassDefFoundError("half-deployed"));

    assertThrows(NoClassDefFoundError.class, () -> service.approve(OWNER, null, proposal.getId()));
    assertEquals(EmailFilterProposal.FAILED, rows.get(proposal.getId()).getStatus());
    assertEquals(EmailFilterProposalService.INTERRUPTED, rows.get(proposal.getId()).getLastError());
  }

  /**
   * The reasons land on the run's waiting proposals of that match only.
   *
   * @throws Exception never
   */
  @Test
  void theReasonsLandOnTheirOwnProposals() throws Exception {
    EmailFilterProposal proposal = propose("t1", "{}");
    EmailFilterProposal decided = propose("t2", "{}");
    service.reject(OWNER, null, decided.getId());

    assertEquals(1, service.setRationales(OWNER, MATCH_ID, Map.of(proposal.getId(), "The instruction asks for a task",
                                                                  decided.getId(), "too late",
                                                                  999L, "nobody")));
    assertEquals("The instruction asks for a task", rows.get(proposal.getId()).getRationale());
    assertNull(rows.get(decided.getId()).getRationale());
    assertEquals(0, service.setRationales(OTHER, MATCH_ID, Map.of(proposal.getId(), "stolen")));
  }

  /**
   * Writing no reasons, or an empty map of them, writes nothing and touches the storage
   * for nothing.
   */
  @Test
  void writingNoReasonsWritesNothing() {
    assertEquals(0, service.setRationales(OWNER, MATCH_ID, null));
    assertEquals(0, service.setRationales(OWNER, MATCH_ID, Map.of()));
  }

  /**
   * Reading the proposals of no match, or of none named, answers none without reaching the
   * storage.
   */
  @Test
  void readingTheProposalsOfNoMatchAnswersNoneWithoutAQuery() {
    assertEquals(List.of(), service.getProposalsOfMatches(OWNER, null));
    assertEquals(List.of(), service.getProposalsOfMatches(OWNER, List.of()));
  }

  /**
   * Putting proposals on no match, or on none named, answers the list itself, untouched.
   */
  @Test
  void puttingProposalsOnNoMatchAnswersTheListItself() {
    assertNull(service.withProposals(OWNER, null));
    List<EmailFilterMatch> empty = new ArrayList<>();
    assertEquals(empty, service.withProposals(OWNER, empty));
  }

  /**
   * A proposal that vanishes between the claim and the re-read that answers the decision is
   * itself answered as not found.
   *
   * @throws Exception never
   */
  @Test
  void aProposalVanishingBetweenTheClaimAndTheRereadIsNotFound() throws Exception {
    EmailFilterProposal proposal = propose("t1", "{}");
    // First answer: the claim's own ownership check: still there. Second: the re-read
    // after the decision, gone.
    when(storage.get(eq(proposal.getId()), eq(OWNER))).thenReturn(Optional.of(proposal), Optional.empty());

    assertThrows(ObjectNotFoundException.class, () -> service.reject(OWNER, null, proposal.getId()));
  }

  /**
   * An unreadable value of a cap property is ignored, the default applying as if it were
   * never set.
   *
   * @throws Exception never
   */
  @Test
  void anUnreadableCapPropertyFallsBackToItsDefault() throws Exception {
    System.setProperty(EmailFilterProposalService.MAX_PER_RUN_PROPERTY, "not-a-number");
    propose("t1", "{}");
    propose("t2", "{}");
    propose("t3", "{}");
    assertEquals(EmailFilterProposalService.RUN_CAP, assertThrows(IllegalStateException.class, () -> propose("t4", "{}")).getMessage(),
                 "the default of three still applies");
  }

  /**
   * The panel's read puts each match's proposals on it, the expired ones marked first.
   *
   * @throws Exception never
   */
  @Test
  void thePanelReadsEachMatchsProposals() throws Exception {
    EmailFilterProposal proposal = propose("t1", "{}");
    rows.get(proposal.getId()).setExpiresDate(NOW - 1);
    when(storage.getByMatches(eq(OWNER), any())).thenAnswer(invocation -> new ArrayList<>(rows.values()));
    EmailFilterMatch other = new EmailFilterMatch();
    other.setId(8L);

    List<EmailFilterMatch> matches = service.withProposals(OWNER, new ArrayList<>(List.of(match, other)));

    assertEquals(1, matches.get(0).getProposals().size());
    assertEquals(EmailFilterProposal.EXPIRED, matches.get(0).getProposals().get(0).getStatus());
    assertEquals(List.of(), matches.get(1).getProposals());
    verify(storage).getByMatches(eq(OWNER), (Collection<Long>) eq(List.of(MATCH_ID, 8L)));
  }

  /**
   * Records a call of the running match, as its owner.
   *
   * @param toolName the tool
   * @param arguments the arguments
   * @return the proposal
   * @throws ObjectNotFoundException never here
   */
  private EmailFilterProposal propose(String toolName, String arguments) throws ObjectNotFoundException {
    return service.createProposal(OWNER, MATCH_ID, RUN, toolName, "Create task", "Creates a task in a project", arguments);
  }

  /**
   * A row of an owner.
   *
   * @param id the row
   * @param userId the owner asked for
   * @return the row, or null when it is not theirs
   */
  private EmailFilterProposal owned(long id, String userId) {
    return userId.equals(owners.get(id)) ? rows.get(id) : null;
  }

  /**
   * Whether two proposals are one call, as the key hashes them.
   *
   * @param first a proposal
   * @param second another
   * @return true for the same call
   */
  private static boolean sameCall(EmailFilterProposal first, EmailFilterProposal second) {
    return EmailFilterProposalStorage.callHash(first.getToolName(), first.getArguments())
                                     .equals(EmailFilterProposalStorage.callHash(second.getToolName(), second.getArguments()));
  }

  /**
   * A copy, so the map's rows change only through the stubs.
   *
   * @param proposal the proposal
   * @return its copy
   */
  private static EmailFilterProposal copy(EmailFilterProposal proposal) {
    return new EmailFilterProposal(proposal.getId(),
                                   proposal.getMatchId(),
                                   proposal.getFilterId(),
                                   proposal.getToolName(),
                                   proposal.getToolTitle(),
                                   proposal.getToolDescription(),
                                   proposal.getArguments(),
                                   proposal.getRationale(),
                                   proposal.getStatus(),
                                   proposal.getCreatedDate(),
                                   proposal.getExpiresDate(),
                                   proposal.getDecidedDate(),
                                   proposal.getConversationId(),
                                   proposal.getResult(),
                                   proposal.getLastError());
  }
}
