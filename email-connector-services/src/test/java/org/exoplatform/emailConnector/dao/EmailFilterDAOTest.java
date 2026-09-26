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
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailFilterEntity;
import org.exoplatform.emailConnector.entity.EmailFilterMatchEntity;

import jakarta.persistence.PersistenceException;

/**
 * The mail filters' queries, executed by the engine on in-memory HSQLDB through the real
 * repository proxies: every read scoped to its owner, the keyword lookup, the counters
 * written in SQL, the match key and the queue's reads.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=false", "spring.jpa.hibernate.ddl-auto=create-drop" })
public class EmailFilterDAOTest {

  private static final String OWNER = "alice";

  private static final String OTHER = "bob";

  @Autowired
  private TestEntityManager   entityManager;

  @Autowired
  private EmailFilterDAO      emailFilterDAO;

  @Autowired
  private EmailFilterMatchDAO emailFilterMatchDAO;

  /**
   * The minimal Spring slice: the entities and their repositories.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailFilterEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailFilterDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * A rule resolves with its owner only, the listing is in order, and the keyword lookup
   * is scoped to the owner: a keyword naming another user's rule finds nothing.
   */
  @Test
  void rulesAreScopedToTheirOwner() {
    Long second = persistRule(OWNER, 1, null, true);
    Long first = persistRule(OWNER, 0, "exo-filter-9", true);
    persistRule(OTHER, 0, "exo-filter-10", false);
    entityManager.clear();

    assertEquals(List.of(first, second), emailFilterDAO.findByUserId(OWNER).stream().map(EmailFilterEntity::getId).toList());
    assertEquals(1, emailFilterDAO.findByIdAndUserId(first, OWNER).size());
    assertTrue(emailFilterDAO.findByIdAndUserId(first, OTHER).isEmpty(), "someone else's id");
    assertEquals(1, emailFilterDAO.findByUserIdAndTagKeyword(OWNER, "exo-filter-9").size());
    assertTrue(emailFilterDAO.findByUserIdAndTagKeyword(OWNER, "exo-filter-10").isEmpty(), "another user's keyword");
    assertEquals(2, emailFilterDAO.countEnabledByUserId(OWNER));
    assertEquals(0, emailFilterDAO.countEnabledByUserId(OTHER));
    assertEquals(1, emailFilterDAO.findMaxPosition(OWNER));
    assertNull(emailFilterDAO.findMaxPosition("nobody"));
  }

  /**
   * The counters and the switch-off are written in SQL, on the owner's row only.
   */
  @Test
  void theSyncWritesItsColumnsAlone() {
    Long id = persistRule(OWNER, 0, null, true);
    entityManager.clear();

    assertEquals(0, emailFilterDAO.addMatches(id, OTHER, 3, new Date(5_000L)), "not someone else's");
    assertEquals(1, emailFilterDAO.addMatches(id, OWNER, 3, new Date(5_000L)));
    assertEquals(1, emailFilterDAO.addMatches(id, OWNER, 2, new Date(6_000L)));
    assertEquals(1, emailFilterDAO.disableWithError(id, OWNER, "emailConnector.filters.unreadable", new Date(7_000L)));
    entityManager.clear();
    EmailFilterEntity row = emailFilterDAO.findById(id).orElseThrow();
    assertEquals(5, row.getMatchCount());
    assertEquals(6_000L, row.getLastMatchDate().getTime());
    assertFalse(row.isEnabled());
    assertEquals("emailConnector.filters.unreadable", row.getLastError());
  }

  /**
   * One match per user, rule and mail; the reads by mail, by rule and by status; the
   * caps' counts; the retention.
   */
  @Test
  void matchesAreKeyedAndRead() {
    persistMatch(OWNER, 7L, "h1", "PENDING", 1_000L);
    persistMatch(OWNER, 7L, "h2", "DONE", 2_000L);
    persistMatch(OWNER, 8L, "h1", "NONE", 3_000L);
    persistMatch(OTHER, 7L, "h1", "PENDING", 4_000L);
    assertThrows(PersistenceException.class, () -> persistMatch(OWNER, 7L, "h1", "NONE", 5_000L), "one match per rule and mail");
    entityManager.clear();

    assertEquals(1, emailFilterMatchDAO.findByFilterAndMails(OWNER, 7L, List.of("h1", "h3")).size());
    List<EmailFilterMatchEntity> ofMail = emailFilterMatchDAO.findByMail(OWNER, "h1");
    assertEquals(List.of(8L, 7L), ofMail.stream().map(EmailFilterMatchEntity::getFilterId).toList(), "newest first, the owner's only");
    assertEquals(2, emailFilterMatchDAO.findByFilter(OWNER, 7L, PageRequest.of(0, 10)).size());
    assertEquals(1, emailFilterMatchDAO.findByAgentStatus(OWNER, "PENDING", PageRequest.of(0, 10)).size());
    assertEquals(1, emailFilterMatchDAO.countByAgentStatus(OWNER, "PENDING"));
    assertEquals(2, emailFilterMatchDAO.countQueuedSince(OWNER, List.of("PENDING", "DONE"), new Date(0)));
    assertEquals(1, emailFilterMatchDAO.countQueuedSince(OWNER, List.of("PENDING", "DONE"), new Date(1_500L)));
    Long id = emailFilterMatchDAO.findByMail(OTHER, "h1").get(0).getId();
    assertTrue(emailFilterMatchDAO.findByIdAndUserId(id, OWNER).isEmpty(), "someone else's match");
    assertEquals(2, emailFilterMatchDAO.deleteOlderThan(OWNER, new Date(2_500L)));
    assertEquals(1, emailFilterMatchDAO.findByMail(OTHER, "h1").size(), "another user's log is not pruned");
  }

  /**
   * The handler's read takes the waiting matches and the running ones a dead run left,
   * never a running one still being written, nor another status or another user's.
   */
  @Test
  void theHandlerTakesUpWaitingAndAbandonedRuns() {
    persistMatch(OWNER, 7L, "h1", "PENDING", 1_000L);
    Long abandoned = persistMatch(OWNER, 7L, "h2", "RUNNING", 2_000L, 10_000L);
    persistMatch(OWNER, 7L, "h3", "RUNNING", 3_000L, 50_000L);
    Long neverWritten = persistMatch(OWNER, 7L, "h4", "RUNNING", 4_000L, null);
    persistMatch(OWNER, 7L, "h5", "FAILED", 500L);
    persistMatch(OTHER, 7L, "h1", "PENDING", 100L);
    entityManager.clear();

    List<EmailFilterMatchEntity> due = emailFilterMatchDAO.findDueForAgent(OWNER,
                                                                          "PENDING",
                                                                          "RUNNING",
                                                                          new Date(30_000L),
                                                                          PageRequest.of(0, 10));

    assertEquals(List.of("h1", "h2", "h4"), due.stream().map(EmailFilterMatchEntity::getMailHeaderHash).toList(),
                 "oldest first; the live run and the finished one are left alone");
    assertEquals(List.of(abandoned, neverWritten), due.subList(1, 3).stream().map(EmailFilterMatchEntity::getId).toList());
    assertEquals(1,
                 emailFilterMatchDAO.findDueForAgent(OWNER, "PENDING", "RUNNING", new Date(30_000L), PageRequest.of(0, 1)).size(),
                 "paged");
  }

  /**
   * Stores a rule.
   *
   * @param userId the owner
   * @param position its place
   * @param tag its keyword, or null
   * @param enabled whether it runs
   * @return its id
   */
  private Long persistRule(String userId, int position, String tag, boolean enabled) {
    EmailFilterEntity entity = new EmailFilterEntity();
    entity.setUserId(userId);
    entity.setMailboxScope("OWN");
    entity.setName("rule");
    entity.setEnabled(enabled);
    entity.setPosition(position);
    entity.setKind(tag == null ? "EXO" : "HOP");
    entity.setMatchMode("ALL");
    entity.setConditions("[]");
    entity.setActions("[]");
    entity.setTagKeyword(tag);
    entity.setCreatedDate(new Date());
    entity.setUpdatedDate(new Date());
    return entityManager.persistAndFlush(entity).getId();
  }

  /**
   * Stores a match.
   *
   * @param userId the owner
   * @param filterId the rule
   * @param hash the mail's hash
   * @param status the assistant's status
   * @param date when it matched
   */
  private void persistMatch(String userId, Long filterId, String hash, String status, long date) {
    persistMatch(userId, filterId, hash, status, date, null);
  }

  /**
   * Stores a match with the date of its assistant's last write.
   *
   * @param userId the owner
   * @param filterId the rule
   * @param hash the mail's hash
   * @param status the assistant's status
   * @param date when it matched
   * @param agentDate when the assistant last wrote it, or null
   * @return its id
   */
  private Long persistMatch(String userId, Long filterId, String hash, String status, long date, Long agentDate) {
    EmailFilterMatchEntity entity = new EmailFilterMatchEntity();
    entity.setAgentDate(agentDate == null ? null : new Date(agentDate));
    entity.setUserId(userId);
    entity.setFilterId(filterId);
    entity.setMailHeaderId("<" + hash + "@x>");
    entity.setMailHeaderHash(hash);
    entity.setMatchedDate(new Date(date));
    entity.setAgentStatus(status);
    entity.setCreatedDate(new Date(date));
    return entityManager.persistAndFlush(entity).getId();
  }
}
