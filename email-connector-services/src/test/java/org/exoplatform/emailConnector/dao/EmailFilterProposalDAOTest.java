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
package org.exoplatform.emailConnector.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.emailConnector.entity.EmailFilterMatchEntity;
import org.exoplatform.emailConnector.entity.EmailFilterProposalEntity;
import org.exoplatform.emailConnector.model.EmailFilterSuggestionCounts;
import org.exoplatform.emailConnector.storage.EmailFilterProposalStorage;

import jakarta.persistence.PersistenceException;

/**
 * The proposals' queries, executed by the engine on in-memory HSQLDB through the real
 * repository proxy: every read and write scoped to its owner, one call per match, and
 * the conditional UPDATEs every decision rides on -- a second claim, a claim past the
 * expiry, and someone else's claim all move nothing.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailFilterProposalDAOTest {

  private static final String    OWNER = "alice";

  private static final String    OTHER = "bob";

  private static final long      NOW   = 1_790_000_000_000L;

  @Autowired
  private TestEntityManager      entityManager;

  @Autowired
  private EmailFilterProposalDAO emailFilterProposalDAO;

  /**
   * The minimal Spring slice: the entities and their repositories.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailFilterProposalEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailFilterProposalDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * One call is recorded once per match -- the same hash on another match is another
   * proposal -- and every read is its owner's.
   */
  @Test
  void oneCallPerMatchAndReadsAreTheOwners() {
    Long first = persist(OWNER, 7L, "hash-a", "PROPOSED", "run-1", NOW + 1_000);
    persist(OWNER, 8L, "hash-a", "PROPOSED", "run-2", NOW + 1_000);
    entityManager.flush();
    assertThrows(PersistenceException.class, () -> {
      persist(OWNER, 7L, "hash-a", "PROPOSED", "run-3", NOW + 1_000);
      entityManager.flush();
    }, "the same call twice on one match");
    entityManager.clear();

    assertEquals(1, emailFilterProposalDAO.findByIdAndUserId(first, OWNER).size());
    assertTrue(emailFilterProposalDAO.findByIdAndUserId(first, OTHER).isEmpty(), "someone else's id");
    assertEquals(1, emailFilterProposalDAO.findByCall(OWNER, 7L, "hash-a").size());
    assertTrue(emailFilterProposalDAO.findByCall(OTHER, 7L, "hash-a").isEmpty());
    assertEquals(2, emailFilterProposalDAO.findByMatches(OWNER, List.of(7L, 8L)).size());
    assertTrue(emailFilterProposalDAO.findByMatches(OTHER, List.of(7L, 8L)).isEmpty());
  }

  /**
   * The counts of the caps: per run of a match, and the mailbox's waiting ones.
   */
  @Test
  void theCapsCountTheirOwnRows() {
    persist(OWNER, 7L, "h1", "PROPOSED", "run-1", NOW + 1_000);
    persist(OWNER, 7L, "h2", "REJECTED", "run-1", NOW + 1_000);
    persist(OWNER, 7L, "h3", "PROPOSED", "run-2", NOW + 1_000);
    persist(OTHER, 7L, "h4", "PROPOSED", "run-1", NOW + 1_000);
    entityManager.flush();
    entityManager.clear();

    assertEquals(2, emailFilterProposalDAO.countByRun(OWNER, 7L, "run-1"));
    assertEquals(1, emailFilterProposalDAO.countByRun(OWNER, 7L, "run-2"));
    assertEquals(2, emailFilterProposalDAO.countByStatus(OWNER, "PROPOSED"));
    assertEquals(1, emailFilterProposalDAO.countByStatus(OTHER, "PROPOSED"));
  }

  /**
   * A claim moves a waiting, unexpired proposal of its owner once: never someone else's,
   * never twice, never past its expiry; and only a running one is finished.
   */
  @Test
  void aClaimMovesOnceAndOnlyTheOwnersUnexpiredRow() {
    Long id = persist(OWNER, 7L, "h1", "PROPOSED", "run-1", NOW + 1_000);
    Long expired = persist(OWNER, 7L, "h2", "PROPOSED", "run-1", NOW - 1);
    entityManager.flush();
    entityManager.clear();

    Date now = new Date(NOW);
    assertEquals(0, emailFilterProposalDAO.claim(id, OTHER, "PROPOSED", "RUNNING", now, now), "someone else's");
    assertEquals(0, emailFilterProposalDAO.claim(expired, OWNER, "PROPOSED", "RUNNING", now, now), "past its expiry");
    assertEquals(1, emailFilterProposalDAO.claim(id, OWNER, "PROPOSED", "RUNNING", now, now));
    assertEquals(0, emailFilterProposalDAO.claim(id, OWNER, "PROPOSED", "RUNNING", now, now), "a second click");
    assertEquals(0, emailFilterProposalDAO.finish(expired, OWNER, "RUNNING", "DONE", "ok", null), "not running");
    assertEquals(0, emailFilterProposalDAO.finish(id, OTHER, "RUNNING", "DONE", "ok", null), "someone else's");
    assertEquals(1, emailFilterProposalDAO.finish(id, OWNER, "RUNNING", "DONE", "{\"id\":42}", null));
    entityManager.clear();
    EmailFilterProposalEntity done = emailFilterProposalDAO.findById(id).orElseThrow();
    assertEquals("DONE", done.getStatus());
    assertEquals("{\"id\":42}", done.getResult());
    assertEquals(NOW, done.getDecidedDate().getTime());
    assertEquals("PROPOSED", emailFilterProposalDAO.findById(expired).orElseThrow().getStatus());
  }

  /**
   * The reason is written on a waiting proposal of the owner's match only -- and a claim
   * made between the run's read and its write is never undone by it: the write touches
   * the reason alone, under the waiting status.
   */
  @Test
  void aReasonNeverUndoesAClaim() {
    Long id = persist(OWNER, 7L, "h1", "PROPOSED", "run-1", NOW + 1_000);
    entityManager.flush();
    entityManager.clear();
    emailFilterProposalDAO.findById(id).orElseThrow(); // the run reads it waiting

    Date now = new Date(NOW);
    assertEquals(1, emailFilterProposalDAO.claim(id, OWNER, "PROPOSED", "RUNNING", now, now)); // the owner approves
    assertEquals(0, emailFilterProposalDAO.setRationale(id, OWNER, 7L, "PROPOSED", "too late"));
    entityManager.clear();
    EmailFilterProposalEntity row = emailFilterProposalDAO.findById(id).orElseThrow();
    assertEquals("RUNNING", row.getStatus());
    assertNull(row.getRationale());

    Long waiting = persist(OWNER, 7L, "h2", "PROPOSED", "run-1", NOW + 1_000);
    entityManager.flush();
    assertEquals(0, emailFilterProposalDAO.setRationale(waiting, OTHER, 7L, "PROPOSED", "x"), "someone else's");
    assertEquals(0, emailFilterProposalDAO.setRationale(waiting, OWNER, 8L, "PROPOSED", "x"), "another match's");
    assertEquals(1, emailFilterProposalDAO.setRationale(waiting, OWNER, 7L, "PROPOSED", "asked"));
    entityManager.clear();
    assertEquals("asked", emailFilterProposalDAO.findById(waiting).orElseThrow().getRationale());
    assertEquals("PROPOSED", emailFilterProposalDAO.findById(waiting).orElseThrow().getStatus());
  }

  /**
   * An approved call running since before the ceiling is failed, with the reason; a
   * recent one, a waiting one and someone else's stay.
   */
  @Test
  void aStaleRunningCallIsFailed() {
    Long stale = persist(OWNER, 7L, "h1", "RUNNING", "run-1", NOW + 1_000);
    Long recent = persist(OWNER, 7L, "h2", "RUNNING", "run-1", NOW + 1_000);
    Long others = persist(OTHER, 7L, "h3", "RUNNING", "run-1", NOW + 1_000);
    entityManager.flush();
    entityManager.clear();
    for (Long id : List.of(stale, others)) {
      EmailFilterProposalEntity row = emailFilterProposalDAO.findById(id).orElseThrow();
      row.setDecidedDate(new Date(NOW - 60_000));
      emailFilterProposalDAO.saveAndFlush(row);
    }
    EmailFilterProposalEntity row = emailFilterProposalDAO.findById(recent).orElseThrow();
    row.setDecidedDate(new Date(NOW));
    emailFilterProposalDAO.saveAndFlush(row);

    assertEquals(1, emailFilterProposalDAO.failStaleRunning(OWNER, "RUNNING", "FAILED", "interrupted", new Date(NOW - 1_000)));
    entityManager.clear();
    assertEquals("FAILED", emailFilterProposalDAO.findById(stale).orElseThrow().getStatus());
    assertEquals("interrupted", emailFilterProposalDAO.findById(stale).orElseThrow().getLastError());
    assertEquals("RUNNING", emailFilterProposalDAO.findById(recent).orElseThrow().getStatus());
    assertEquals("RUNNING", emailFilterProposalDAO.findById(others).orElseThrow().getStatus());
  }

  /**
   * The expiry marks the owner's waiting rows past their date, and the supersede the
   * waiting rows of one match, with the reason; decided rows stay as they are.
   */
  @Test
  void theExpiryAndTheSupersedeMoveOnlyWaitingRows() {
    Long due = persist(OWNER, 7L, "h1", "PROPOSED", "run-1", NOW);
    Long live = persist(OWNER, 7L, "h2", "PROPOSED", "run-1", NOW + 1_000);
    Long decided = persist(OWNER, 7L, "h3", "REJECTED", "run-1", NOW - 1_000);
    Long othersDue = persist(OTHER, 7L, "h4", "PROPOSED", "run-1", NOW - 1_000);
    Long otherMatch = persist(OWNER, 8L, "h5", "PROPOSED", "run-9", NOW + 1_000);
    entityManager.flush();
    entityManager.clear();

    assertEquals(1, emailFilterProposalDAO.expireDue(OWNER, "PROPOSED", "EXPIRED", new Date(NOW)));
    entityManager.clear();
    assertEquals("EXPIRED", emailFilterProposalDAO.findById(due).orElseThrow().getStatus());
    assertEquals("PROPOSED", emailFilterProposalDAO.findById(live).orElseThrow().getStatus());
    assertEquals("REJECTED", emailFilterProposalDAO.findById(decided).orElseThrow().getStatus());
    assertEquals("PROPOSED", emailFilterProposalDAO.findById(othersDue).orElseThrow().getStatus(), "someone else's");

    assertEquals(1, emailFilterProposalDAO.expireOfMatch(OWNER, 7L, "PROPOSED", "EXPIRED", "superseded", new Date(NOW)));
    entityManager.clear();
    EmailFilterProposalEntity superseded = emailFilterProposalDAO.findById(live).orElseThrow();
    assertEquals("EXPIRED", superseded.getStatus());
    assertEquals("superseded", superseded.getLastError());
    assertNull(emailFilterProposalDAO.findById(due).orElseThrow().getLastError(), "an expiry names no reason");
    assertEquals("PROPOSED", emailFilterProposalDAO.findById(otherMatch).orElseThrow().getStatus(), "another match");
  }

  /**
   * The mails with a suggestion waiting are the owner's, read through their own matches:
   * each waiting, unexpired proposal marks its match's mail once, so the list can count them; a
   * decided or expired one, someone else's proposal, and a proposal on someone else's
   * match do not.
   */
  @Test
  void theWaitingMailsAreTheOwnersWaitingUnexpiredOnes() {
    Long waitingMatch = persistMatch(OWNER, "waiting");
    Long decidedMatch = persistMatch(OWNER, "decided");
    Long expiredMatch = persistMatch(OWNER, "expired");
    Long othersMatch = persistMatch(OTHER, "others");
    persist(OWNER, waitingMatch, "h1", "PROPOSED", "run-1", NOW + 1_000);
    persist(OWNER, waitingMatch, "h2", "PROPOSED", "run-1", NOW + 1_000);
    persist(OWNER, waitingMatch, "h3", "REJECTED", "run-1", NOW + 1_000);
    persist(OWNER, decidedMatch, "h4", "DONE", "run-1", NOW + 1_000);
    persist(OWNER, expiredMatch, "h5", "PROPOSED", "run-1", NOW);
    persist(OTHER, othersMatch, "h6", "PROPOSED", "run-1", NOW + 1_000);
    // The owner's proposal on someone else's match: the join is scoped on both sides.
    persist(OWNER, othersMatch, "h7", "PROPOSED", "run-1", NOW + 1_000);
    entityManager.flush();
    entityManager.clear();

    assertEquals(List.of("<waiting@x>", "<waiting@x>"), emailFilterProposalDAO.findWaitingMailHeaderIds(OWNER, "PROPOSED", new Date(NOW)));
    assertEquals(List.of("<others@x>"), emailFilterProposalDAO.findWaitingMailHeaderIds(OTHER, "PROPOSED", new Date(NOW)));
    assertTrue(emailFilterProposalDAO.findWaitingMailHeaderIds(OWNER, "PROPOSED", new Date(NOW + 1_000)).isEmpty(),
               "every one expired by then");
  }

  /**
   * What the owner decided per rule (EXO-90668), run by the engine through the storage:
   * approved counts every call once approved (running, done, failed), an expired call a
   * later run set aside is not counted as expired, the waiting ones are counted, and
   * another user's are not.
   */
  @Test
  void theDecisionsAreCountedPerRule() {
    Long match = persistMatch(OWNER, "m1");
    Long otherMatch = persistMatch(OTHER, "m2");
    decided(persist(OWNER, match, "a", "DONE", "r1", NOW + 1_000), 3L, null);
    decided(persist(OWNER, match, "b", "FAILED", "r1", NOW + 1_000), 3L, "The tool refused");
    decided(persist(OWNER, match, "c", "RUNNING", "r1", NOW + 1_000), 3L, null);
    decided(persist(OWNER, match, "d", "REJECTED", "r1", NOW + 1_000), 3L, null);
    decided(persist(OWNER, match, "e", "EXPIRED", "r1", NOW - 1_000), 3L, null);
    decided(persist(OWNER, match, "f", "EXPIRED", "r1", NOW + 1_000), 3L, "emailConnector.filters.proposal.superseded");
    decided(persist(OWNER, match, "g", "HANDED_OVER", "r1", NOW + 1_000), 3L, null);
    decided(persist(OWNER, match, "h", "PROPOSED", "r2", NOW + 1_000), 3L, null);
    decided(persist(OWNER, match, "i", "REJECTED", "r2", NOW + 1_000), 4L, null);
    decided(persist(OTHER, otherMatch, "j", "DONE", "r3", NOW + 1_000), 3L, null);
    entityManager.flush();
    entityManager.clear();
    EmailFilterProposalStorage storage = new EmailFilterProposalStorage();
    ReflectionTestUtils.setField(storage, "emailFilterProposalDAO", emailFilterProposalDAO);

    List<EmailFilterSuggestionCounts> counts = storage.countByFilter(OWNER);

    assertEquals(List.of(new EmailFilterSuggestionCounts(3L, 3, 1, 1, 1, 1), new EmailFilterSuggestionCounts(4L, 0, 1, 0, 0, 0)),
                 counts);
    assertEquals(List.of(new EmailFilterSuggestionCounts(3L, 1, 0, 0, 0, 0)), storage.countByFilter(OTHER));
  }

  /**
   * Sets a proposal's rule and last error.
   *
   * @param id the proposal
   * @param filterId the rule
   * @param lastError the last error, or null
   */
  private void decided(Long id, long filterId, String lastError) {
    EmailFilterProposalEntity entity = entityManager.find(EmailFilterProposalEntity.class, id);
    entity.setFilterId(filterId);
    entity.setLastError(lastError);
    entityManager.persist(entity);
  }

  /**
   * Persists a match of a mail.
   *
   * @param userId the owner
   * @param mail the mail's Message-ID, without its brackets and domain
   * @return its id
   */
  private Long persistMatch(String userId, String mail) {
    EmailFilterMatchEntity entity = new EmailFilterMatchEntity();
    entity.setUserId(userId);
    entity.setFilterId(3L);
    entity.setMailHeaderId("<" + mail + "@x>");
    entity.setMailHeaderHash(mail);
    entity.setMatchedDate(new Date(NOW - 10_000));
    entity.setAgentStatus("DONE");
    entity.setCreatedDate(new Date(NOW - 10_000));
    return entityManager.persist(entity).getId();
  }

  /**
   * Persists a proposal.
   *
   * @param userId the owner
   * @param matchId the match
   * @param hash the call's hash
   * @param status the status
   * @param conversationId the run
   * @param expires when it expires
   * @return its id
   */
  private Long persist(String userId, long matchId, String hash, String status, String conversationId, long expires) {
    EmailFilterProposalEntity entity = new EmailFilterProposalEntity();
    entity.setUserId(userId);
    entity.setMatchId(matchId);
    entity.setFilterId(3L);
    entity.setToolName("create_task_in_project");
    entity.setToolTitle("Create task");
    entity.setArguments("{\"title\":\"Pay\"}");
    entity.setCallHash(hash);
    entity.setStatus(status);
    entity.setCreatedDate(new Date(NOW - 5_000));
    entity.setExpiresDate(new Date(expires));
    entity.setConversationId(conversationId);
    return entityManager.persist(entity).getId();
  }
}
