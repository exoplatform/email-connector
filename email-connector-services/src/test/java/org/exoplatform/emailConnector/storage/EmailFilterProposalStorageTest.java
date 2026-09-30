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
package org.exoplatform.emailConnector.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.dao.EmailFilterProposalDAO;
import org.exoplatform.emailConnector.entity.EmailFilterProposalEntity;
import org.exoplatform.emailConnector.model.EmailFilterProposal;

/**
 * The proposals' storage on a real engine: mapping to and from the entity, the
 * at-most-once key a duplicate call answers empty, and the conditional writes every
 * decision rides on, run through the real repository proxy. Run outside a test
 * transaction, as the assistant's handler runs it -- each call commits on its own -- with
 * the table cleared between tests since the at-most-once key spans every owner.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailFilterProposalStorageTest {

  private static final long      NOW = 1_790_000_000_000L;

  @Autowired
  private EmailFilterProposalStorage emailFilterProposalStorage;

  @Autowired
  private EmailFilterProposalDAO emailFilterProposalDAO;

  /**
   * The minimal Spring slice: the entity, its repository and the storage.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailFilterProposalEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailFilterProposalDAO.class)
  @Import(EmailFilterProposalStorage.class)
  static class JpaSliceConfiguration {
  }

  /**
   * Clears the table: the unique key is {@code MATCH_ID, CALL_HASH} alone, so a row left
   * by one test would collide with the next one's.
   */
  @AfterEach
  void cleanUp() {
    emailFilterProposalDAO.deleteAll();
  }

  /**
   * A call is recorded with an id and its owner; the same call recorded again on the same
   * match is refused, empty, and never a second row. Arguments given as null are stored as
   * an empty object.
   */
  @Test
  void createRecordsOnceAndAnswersEmptyForADuplicateCall() {
    EmailFilterProposal first = emailFilterProposalStorage.create(proposal(7L, "create_task_in_project", "{\"a\":1}", NOW + 100_000),
                                                                   "alice")
                                                          .orElseThrow();
    assertTrue(first.getId() > 0, "assigned an id");
    assertEquals(EmailFilterProposal.PROPOSED, first.getStatus());
    assertEquals("create_task_in_project", emailFilterProposalStorage.get(first.getId(), "alice").orElseThrow().getToolName());

    Optional<EmailFilterProposal> duplicate = emailFilterProposalStorage.create(proposal(7L,
                                                                                          "create_task_in_project",
                                                                                          "{\"a\":1}",
                                                                                          NOW + 100_000),
                                                                                 "alice");
    assertTrue(duplicate.isEmpty(), "the same call on the same match is already handled");
    assertEquals(1, emailFilterProposalStorage.getByMatches("alice", List.of(7L)).size(), "no second row");

    EmailFilterProposal noArguments = proposal(8L, "another_tool", null, NOW + 100_000);
    EmailFilterProposal stored = emailFilterProposalStorage.create(noArguments, "alice").orElseThrow();
    assertEquals("{}", emailFilterProposalStorage.get(stored.getId(), "alice").orElseThrow().getArguments(),
                 "null arguments stored as an empty object");
  }

  /**
   * An update writes the mutable fields of its owner's row; another owner's id, or one
   * naming nobody's row, is refused, empty.
   */
  @Test
  void updateWritesTheOwnersRowAndRefusesAnyoneElse() {
    EmailFilterProposal created = emailFilterProposalStorage.create(proposal(7L, "t1", "{}", NOW + 100_000), "bob").orElseThrow();

    created.setStatus(EmailFilterProposal.RUNNING);
    created.setRationale("the instruction asks for it");
    assertTrue(emailFilterProposalStorage.update(created, "somebody-else").isEmpty(), "not this owner's row");

    EmailFilterProposal updated = emailFilterProposalStorage.update(created, "bob").orElseThrow();
    assertEquals(EmailFilterProposal.RUNNING, updated.getStatus());
    assertEquals("the instruction asks for it", updated.getRationale());
    assertEquals(EmailFilterProposal.RUNNING, emailFilterProposalStorage.get(created.getId(), "bob").orElseThrow().getStatus(),
                 "written through");

    EmailFilterProposal unknown = proposal(7L, "t2", "{}", NOW + 100_000);
    unknown.setId(999L);
    assertTrue(emailFilterProposalStorage.update(unknown, "bob").isEmpty(), "no such row");
  }

  /**
   * {@code exists} tells a missing proposal from someone else's; the single-call and the
   * batch reads answer only their owner's rows, oldest first, and answer nothing, without a
   * query, for no match asked.
   */
  @Test
  void existsGetGetByCallAndGetByMatchesReadTheOwnersRows() {
    EmailFilterProposal older = emailFilterProposalStorage.create(proposal(7L, "t1", "{}", NOW + 100_000), "carl").orElseThrow();
    EmailFilterProposal newer = emailFilterProposalStorage.create(proposal(8L, "t2", "{}", NOW + 100_000), "carl").orElseThrow();

    assertTrue(emailFilterProposalStorage.exists(older.getId()));
    assertFalse(emailFilterProposalStorage.exists(999L));

    assertEquals("t1", emailFilterProposalStorage.get(older.getId(), "carl").orElseThrow().getToolName());
    assertTrue(emailFilterProposalStorage.get(older.getId(), "dave").isEmpty(), "not this owner's proposal");

    assertEquals(older.getId(), emailFilterProposalStorage.getByCall("carl", 7L, "t1", "{}").orElseThrow().getId());
    assertTrue(emailFilterProposalStorage.getByCall("carl", 7L, "no-such-tool", "{}").isEmpty());

    assertEquals(List.of(older.getId(), newer.getId()),
                 emailFilterProposalStorage.getByMatches("carl", List.of(7L, 8L)).stream().map(EmailFilterProposal::getId).toList(),
                 "oldest first");
    assertTrue(emailFilterProposalStorage.getByMatches("carl", List.of()).isEmpty(), "no match named");
    assertTrue(emailFilterProposalStorage.getByMatches("carl", null).isEmpty(), "no matches asked, none named");
  }

  /**
   * {@code countByRun} counts one run's calls of one match; {@code countByStatus} counts a
   * user's calls in a status -- both scoped to their owner.
   */
  @Test
  void countByRunAndCountByStatusCountTheirOwnersRows() {
    emailFilterProposalStorage.create(withRun(proposal(7L, "t1", "{}", NOW + 100_000), "run-1"), "eve");
    EmailFilterProposal secondOfRun = withRun(proposal(7L, "t2", "{}", NOW + 100_000), "run-1");
    emailFilterProposalStorage.create(secondOfRun, "eve");
    EmailFilterProposal otherRun = withRun(proposal(7L, "t3", "{}", NOW + 100_000), "run-2");
    EmailFilterProposal decided = emailFilterProposalStorage.create(otherRun, "eve").orElseThrow();
    decided.setStatus(EmailFilterProposal.REJECTED);
    emailFilterProposalStorage.update(decided, "eve");
    emailFilterProposalStorage.create(withRun(proposal(7L, "t4", "{}", NOW + 100_000), "run-1"), "frank");

    assertEquals(2, emailFilterProposalStorage.countByRun("eve", 7L, "run-1"));
    assertEquals(1, emailFilterProposalStorage.countByRun("eve", 7L, "run-2"));
    assertEquals(2, emailFilterProposalStorage.countByStatus("eve", EmailFilterProposal.PROPOSED), "the rejected one excluded");
    assertEquals(1, emailFilterProposalStorage.countByStatus("frank", EmailFilterProposal.PROPOSED));
  }

  /**
   * A claim moves a waiting, unexpired row of its owner once: never someone else's, never
   * twice, never past its expiry; and only a running row is finished, with the tool's
   * answer or its failure.
   */
  @Test
  void claimMovesOnceAndFinishOnlyARunningRow() {
    EmailFilterProposal live = emailFilterProposalStorage.create(proposal(7L, "t1", "{}", NOW + 100_000), "gina").orElseThrow();
    EmailFilterProposal expired = emailFilterProposalStorage.create(proposal(7L, "t2", "{}", NOW - 1), "gina").orElseThrow();

    assertFalse(emailFilterProposalStorage.claim(live.getId(), "somebody-else", EmailFilterProposal.PROPOSED,
                                                 EmailFilterProposal.RUNNING, new Date(NOW)),
               "someone else's");
    assertFalse(emailFilterProposalStorage.claim(expired.getId(), "gina", EmailFilterProposal.PROPOSED, EmailFilterProposal.RUNNING,
                                                 new Date(NOW)),
               "past its expiry");
    assertTrue(emailFilterProposalStorage.claim(live.getId(), "gina", EmailFilterProposal.PROPOSED, EmailFilterProposal.RUNNING,
                                                new Date(NOW)));
    assertFalse(emailFilterProposalStorage.claim(live.getId(), "gina", EmailFilterProposal.PROPOSED, EmailFilterProposal.RUNNING,
                                                 new Date(NOW)),
               "a second click");
    assertEquals(EmailFilterProposal.RUNNING, emailFilterProposalStorage.get(live.getId(), "gina").orElseThrow().getStatus());

    assertFalse(emailFilterProposalStorage.finish(expired.getId(), "gina", EmailFilterProposal.DONE, "{}", null), "not running");
    assertFalse(emailFilterProposalStorage.finish(live.getId(), "somebody-else", EmailFilterProposal.DONE, "{}", null),
               "someone else's");
    assertTrue(emailFilterProposalStorage.finish(live.getId(), "gina", EmailFilterProposal.DONE, "{\"id\":42}", null));
    EmailFilterProposal done = emailFilterProposalStorage.get(live.getId(), "gina").orElseThrow();
    assertEquals(EmailFilterProposal.DONE, done.getStatus());
    assertEquals("{\"id\":42}", done.getResult());
    assertFalse(emailFilterProposalStorage.finish(live.getId(), "gina", EmailFilterProposal.FAILED, null, "too late"),
               "already finished");
  }

  /**
   * The reason is written on a waiting row of its owner's match only: someone else's,
   * another match's, or one no longer waiting, is left untouched.
   */
  @Test
  void setRationaleWritesOnlyAWaitingRowOfItsOwnersMatch() {
    EmailFilterProposal waiting = emailFilterProposalStorage.create(proposal(7L, "t1", "{}", NOW + 100_000), "henry").orElseThrow();

    assertFalse(emailFilterProposalStorage.setRationale(waiting.getId(), "somebody-else", 7L, "stolen"), "someone else's");
    assertFalse(emailFilterProposalStorage.setRationale(waiting.getId(), "henry", 8L, "wrong match"), "another match's");
    assertTrue(emailFilterProposalStorage.setRationale(waiting.getId(), "henry", 7L, "the instruction asks for it"));
    assertEquals("the instruction asks for it", emailFilterProposalStorage.get(waiting.getId(), "henry").orElseThrow().getRationale());

    assertTrue(emailFilterProposalStorage.claim(waiting.getId(), "henry", EmailFilterProposal.PROPOSED, EmailFilterProposal.RUNNING,
                                                new Date(NOW)));
    assertFalse(emailFilterProposalStorage.setRationale(waiting.getId(), "henry", 7L, "too late"), "no longer waiting");
    assertEquals("the instruction asks for it", emailFilterProposalStorage.get(waiting.getId(), "henry").orElseThrow().getRationale(),
                 "the claim is never undone by a reason written after it");
  }

  /**
   * An approved call running since before the ceiling is failed, with the reason; the
   * expiry marks the owner's waiting rows past their date, and a match's supersede expires
   * its waiting rows, with a reason.
   */
  @Test
  void failStaleRunningExpireDueAndExpireOfMatchMoveOnlyTheExpectedRows() {
    EmailFilterProposal stale = emailFilterProposalStorage.create(proposal(7L, "t1", "{}", NOW + 100_000), "iris").orElseThrow();
    assertTrue(emailFilterProposalStorage.claim(stale.getId(), "iris", EmailFilterProposal.PROPOSED, EmailFilterProposal.RUNNING,
                                                new Date(NOW)));
    EmailFilterProposalEntity row = emailFilterProposalDAO.findById(stale.getId()).orElseThrow();
    row.setDecidedDate(new Date(NOW - 60_000));
    emailFilterProposalDAO.saveAndFlush(row);

    assertEquals(1, emailFilterProposalStorage.failStaleRunning("iris", "interrupted", new Date(NOW - 1_000)));
    EmailFilterProposal failed = emailFilterProposalStorage.get(stale.getId(), "iris").orElseThrow();
    assertEquals(EmailFilterProposal.FAILED, failed.getStatus());
    assertEquals("interrupted", failed.getLastError());
    assertEquals(0, emailFilterProposalStorage.failStaleRunning("iris", "interrupted", new Date(NOW - 1_000)), "already moved");

    EmailFilterProposal due = emailFilterProposalStorage.create(proposal(8L, "t2", "{}", NOW), "iris").orElseThrow();
    EmailFilterProposal live = emailFilterProposalStorage.create(proposal(8L, "t3", "{}", NOW + 100_000), "iris").orElseThrow();
    assertEquals(1, emailFilterProposalStorage.expireDue("iris", new Date(NOW)));
    assertEquals(EmailFilterProposal.EXPIRED, emailFilterProposalStorage.get(due.getId(), "iris").orElseThrow().getStatus());
    assertEquals(EmailFilterProposal.PROPOSED, emailFilterProposalStorage.get(live.getId(), "iris").orElseThrow().getStatus());

    assertEquals(1, emailFilterProposalStorage.expireOfMatch("iris", 8L, "superseded", new Date(NOW)));
    EmailFilterProposal superseded = emailFilterProposalStorage.get(live.getId(), "iris").orElseThrow();
    assertEquals(EmailFilterProposal.EXPIRED, superseded.getStatus());
    assertEquals("superseded", superseded.getLastError());
  }

  /**
   * The key hashes the same call the same way whatever its arguments' keys' order or
   * spacing, a different tool or different arguments to another hash, and arguments that
   * are not JSON as they are, stably.
   */
  @Test
  void callHashIsStableForTheSameCallWhateverTheArgumentsSpelling() {
    String canonical = "{\"a\":1,\"b\":2}";
    String reorderedWithSpaces = "{ \"b\":   2,\n\"a\": 1 }";

    assertEquals(EmailFilterProposalStorage.callHash("create_task_in_project", canonical),
                 EmailFilterProposalStorage.callHash("create_task_in_project", reorderedWithSpaces),
                 "the same call, its keys in another order and spacing");
    assertNotEquals(EmailFilterProposalStorage.callHash("create_task_in_project", canonical),
                    EmailFilterProposalStorage.callHash("another_tool", canonical),
                    "another tool");
    assertNotEquals(EmailFilterProposalStorage.callHash("create_task_in_project", canonical),
                    EmailFilterProposalStorage.callHash("create_task_in_project", "{\"a\":1,\"b\":3}"),
                    "different arguments");

    String notJson = "not-json-at-all";
    assertEquals(EmailFilterProposalStorage.callHash("t", notJson), EmailFilterProposalStorage.callHash("t", notJson),
                 "hashed as-is, stably, when the arguments are not JSON");
    assertNotEquals(EmailFilterProposalStorage.callHash("t", canonical), EmailFilterProposalStorage.callHash("t", notJson));
  }

  /**
   * A title longer than its cap is cut to it; the description, the rationale and the
   * error, all null here, are stored as null, never as an exception.
   */
  @Test
  void aTitleLongerThanItsCapIsCutAndANullFieldStaysNull() {
    EmailFilterProposal proposal = proposal(7L, "t1", "{}", NOW + 100_000);
    proposal.setToolTitle("T".repeat(EmailFilterProposalStorage.MAX_TITLE_LENGTH + 50));
    proposal.setToolDescription(null);
    proposal.setRationale(null);

    EmailFilterProposal stored = emailFilterProposalStorage.create(proposal, "jill").orElseThrow();

    assertEquals(EmailFilterProposalStorage.MAX_TITLE_LENGTH, stored.getToolTitle().length(), "cut to the cap");
    assertEquals("T".repeat(EmailFilterProposalStorage.MAX_TITLE_LENGTH), stored.getToolTitle());
    assertNull(stored.getToolDescription());
    assertNull(stored.getRationale());
  }

  /**
   * A proposal of a match, waiting, expiring at a given time, as the service would build
   * it.
   *
   * @param matchId the match
   * @param toolName the tool
   * @param arguments the arguments, or null
   * @param expiresDate when it expires, in milliseconds
   * @return the proposal
   */
  private static EmailFilterProposal proposal(long matchId, String toolName, String arguments, long expiresDate) {
    EmailFilterProposal proposal = new EmailFilterProposal();
    proposal.setMatchId(matchId);
    proposal.setFilterId(3L);
    proposal.setToolName(toolName);
    proposal.setToolTitle("Create task");
    proposal.setToolDescription("Creates a task in a project");
    proposal.setArguments(arguments);
    proposal.setStatus(EmailFilterProposal.PROPOSED);
    proposal.setCreatedDate(NOW);
    proposal.setExpiresDate(expiresDate);
    return withRun(proposal, "run-1");
  }

  /**
   * The same proposal, its run replaced.
   *
   * @param proposal the proposal
   * @param conversationId the run
   * @return the proposal
   */
  private static EmailFilterProposal withRun(EmailFilterProposal proposal, String conversationId) {
    proposal.setConversationId(conversationId);
    return proposal;
  }
}
