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

import org.exoplatform.emailConnector.entity.EmailFilterProposalEntity;

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
