/**
 * Copyright (C) 2026 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.dao;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.entity.EmailScheduledSendEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ScheduledSendStatus;

/**
 * The scheduled-send transitions under real contention: two nodes' dispatchers, and
 * the owner's own actions, issuing their conditional statements on one row at the same
 * instant. {@link EmailScheduledSendDAOTest} proves each predicate one call at a time;
 * what only the engine can prove is that two transactions racing on one row cannot
 * both be told they changed it -- the row lock an UPDATE or a DELETE takes, and the
 * re-evaluation of the predicate once the other has committed.
 * <p>
 * Every write here is committed ({@link Propagation#NOT_SUPPORTED}), since uncommitted
 * work is invisible to another thread and the race would be staged rather than real;
 * the rows are removed after each test for the same reason.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
public class EmailScheduledSendClaimConcurrencyTest {

  private static final String            USER      = "contended";

  private static final int               CLAIMANTS = 8;

  private static final Date              NOW       = new Date(1_800_000_000_000L);

  private static final Set<ScheduledSendStatus> EDITABLE     = Set.of(ScheduledSendStatus.SCHEDULED,
                                                                        ScheduledSendStatus.FAILED);

  private static final Set<ScheduledSendStatus> SENDABLE_NOW =
                                                           Set.of(ScheduledSendStatus.SCHEDULED,
                                                                  ScheduledSendStatus.FAILED,
                                                                  ScheduledSendStatus.UNCERTAIN);

  @Autowired
  private EmailScheduledSendDAO          dao;

  @Autowired
  private EmailBoxDAO                    emailBoxDAO;

  @Autowired
  private PlatformTransactionManager     transactionManager;

  private final List<Long>               drafts    = new ArrayList<>();

  /**
   * The minimal Spring slice over the changelog-built database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * Removes the committed drafts, and their schedules with them through the cascade.
   */
  @AfterEach
  void removeTheCommittedRows() {
    if (!drafts.isEmpty()) {
      emailBoxDAO.deleteEmailsByIds(drafts);
      drafts.clear();
    }
  }

  /**
   * Eight nodes' dispatchers claim one due mail at once: exactly one is told it took
   * it, so exactly one transmits it.
   *
   * @throws Exception if a claimant does
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void exactlyOneOfManySimultaneousDispatchersClaimsTheMail() throws Exception {
    long id = scheduledRow("race");
    List<Callable<Integer>> claimants = new ArrayList<>();
    for (int i = 0; i < CLAIMANTS; i++) {
      String node = "node-" + i;
      claimants.add(() -> dao.claim(id, node, NOW, ScheduledSendStatus.SCHEDULED, ScheduledSendStatus.SENDING));
    }
    List<Integer> results = race(claimants);

    assertEquals(1, results.stream().mapToInt(Integer::intValue).sum(), "exactly one claim lands");
    EmailScheduledSendEntity row = dao.findById(id).orElseThrow();
    assertEquals(ScheduledSendStatus.SENDING, row.getStatus());
    assertEquals(1, row.getAttempts(), "one attempt counted, not eight");
    assertTrue(row.getClaimedBy().startsWith("node-"));
  }

  /**
   * The dispatcher's claim against the owner's cancel: one of them wins, never both --
   * a mail cancelled back to Drafts is never also on its way, and a mail on its way is
   * never also cancelled.
   *
   * @throws Exception if a racer does
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void theClaimAndACancelNeverBothWin() throws Exception {
    for (int round = 0; round < 20; round++) {
      long id = scheduledRow("cancel-" + round);
      String localId = "cancel-" + round;
      List<Integer> results = race(List.of(() -> dao.claim(id, "node-a", NOW, ScheduledSendStatus.SCHEDULED,
                                                           ScheduledSendStatus.SENDING),
                                           () -> dao.cancel(USER,
                                                            localId,
                                                            Set.of(ScheduledSendStatus.SENDING, ScheduledSendStatus.SENT))));
      assertEquals(1, results.get(0) + results.get(1), "round " + round + ": exactly one of claim and cancel lands");
      assertEquals(results.get(0) == 1, dao.findById(id).isPresent(), "round " + round + ": the row agrees with the winner");
    }
  }

  /**
   * The dispatcher's claim against the owner's "send now": one of them sends it, never
   * both.
   *
   * @throws Exception if a racer does
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void theClaimAndASendNowNeverBothWin() throws Exception {
    for (int round = 0; round < 20; round++) {
      long id = scheduledRow("now-" + round);
      String localId = "now-" + round;
      List<Integer> results = race(List.of(() -> dao.claim(id, "node-a", NOW, ScheduledSendStatus.SCHEDULED,
                                                           ScheduledSendStatus.SENDING),
                                           () -> dao.claimNow(USER, localId, "node-b", NOW, SENDABLE_NOW,
                                                              ScheduledSendStatus.SENDING)));
      assertEquals(1, results.get(0) + results.get(1), "round " + round + ": exactly one of the two claims lands");
      assertEquals(results.get(0) == 1 ? "node-a" : "node-b", dao.findById(id).orElseThrow().getClaimedBy());
    }
  }

  /**
   * An edit of a scheduled mail's content in flight holds its row (EXO-90434): the
   * dispatcher's claim waits for the edit's commit, then claims, and the draft it then
   * reads is the edited one -- the mail is never sent half-edited.
   *
   * @throws Exception if the edit or the claim does
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void aClaimWaitsForAnEditInFlightThenReadsTheEditedDraft() throws Exception {
    long id = scheduledRow("edit");
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    CountDownLatch held = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<Integer> edit = pool.submit(() -> transaction.execute(status -> {
        int taken = dao.takeForEdit(USER, "edit", NOW, EDITABLE);
        EmailBoxEntity draft = emailBoxDAO.findById(drafts.get(drafts.size() - 1)).orElseThrow();
        draft.setBody("<p>edited</p>");
        emailBoxDAO.saveAndFlush(draft);
        held.countDown();
        try {
          release.await(30, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
        }
        return taken;
      }));
      assertTrue(held.await(30, TimeUnit.SECONDS));
      Future<Integer> claim = pool.submit(() -> dao.claim(id, "node-a", NOW, ScheduledSendStatus.SCHEDULED,
                                                          ScheduledSendStatus.SENDING));
      Thread.sleep(500);
      assertFalse(claim.isDone(), "the claim waits for the edit in flight");
      release.countDown();
      assertEquals(1, edit.get(30, TimeUnit.SECONDS), "the edit took the row");
      assertEquals(1, claim.get(30, TimeUnit.SECONDS), "then the claim lands");
      assertEquals("<p>edited</p>", emailBoxDAO.findById(drafts.get(drafts.size() - 1)).orElseThrow().getBody());
      assertEquals(ScheduledSendStatus.SENDING, dao.findById(id).orElseThrow().getStatus());
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  /**
   * An edit that comes after the claim takes nothing: the mail is on its way.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void anEditAfterTheClaimTakesNothing() {
    long id = scheduledRow("late-edit");
    assertEquals(1, dao.claim(id, "node-a", NOW, ScheduledSendStatus.SCHEDULED, ScheduledSendStatus.SENDING));
    assertEquals(0, dao.takeForEdit(USER, "late-edit", NOW, EDITABLE));
  }

  /**
   * Starts every racer at once and collects what each was told.
   *
   * @param racers the statements to race
   * @return each racer's row count, in order
   * @throws Exception if a racer does
   */
  private List<Integer> race(List<Callable<Integer>> racers) throws Exception {
    CountDownLatch startLine = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(racers.size());
    try {
      List<Future<Integer>> futures = new ArrayList<>();
      for (Callable<Integer> racer : racers) {
        futures.add(pool.submit(() -> {
          startLine.await();
          return racer.call();
        }));
      }
      startLine.countDown();
      List<Integer> results = new ArrayList<>();
      for (Future<Integer> future : futures) {
        results.add(future.get(30, TimeUnit.SECONDS));
      }
      return results;
    } finally {
      pool.shutdownNow();
    }
  }

  /**
   * Commits a draft and a due schedule for it.
   *
   * @param draftLocalId the draft's handle
   * @return the schedule row id
   */
  private long scheduledRow(String draftLocalId) {
    EmailBoxEntity draft = new EmailBoxEntity();
    draft.setUserId(USER);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setSender("Alice,alice@example.org");
    draft.setSubject("Contended");
    draft.setReceivedDate(NOW);
    draft.setDraftLocalId(draftLocalId);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft = emailBoxDAO.saveAndFlush(draft);
    drafts.add(draft.getId());
    EmailScheduledSendEntity row = new EmailScheduledSendEntity();
    row.setEmailId(draft.getId());
    row.setUserId(USER);
    row.setDraftLocalId(draftLocalId);
    row.setScheduledDate(new Date(NOW.getTime() - 1000));
    row.setNextAttemptDate(new Date(NOW.getTime() - 1000));
    row.setStatus(ScheduledSendStatus.SCHEDULED);
    row.setCreatedDate(NOW);
    return dao.saveAndFlush(row).getId();
  }
}
