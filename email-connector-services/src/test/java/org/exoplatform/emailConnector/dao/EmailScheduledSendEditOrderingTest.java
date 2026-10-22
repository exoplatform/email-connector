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
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
 * EXO-91996 -- what orders an assistant's checked cancel or reschedule after an edit of
 * the scheduled mail in the Email app, on a real engine.
 * <p>
 * The edit ({@code EmailBoxService.updateScheduledDraft}) takes the schedule row with a
 * conditional UPDATE, writes the draft, and commits; the draft lock it also holds is
 * released before that commit, so it orders nothing. The checked change
 * ({@code EmailBoxService.changeScheduleIfDraftSays}) issues its conditional UPDATE on
 * the same row first, then reads the draft. The guarantee rests on the engine: that
 * UPDATE waits for the edit's row lock, so the draft read after it is the edit's
 * committed text, and the assistant's check refuses a mail edited since the user
 * approved it. A mock cannot show it.
 * <p>
 * The test commits for real ({@link Propagation#NOT_SUPPORTED}, as
 * {@link EmailSyncStateClaimConcurrencyTest} does) and cleans up after itself. It runs
 * HSQLDB in MVCC mode, read committed, where a read never waits on a writer, as on
 * PostgreSQL and MySQL: in HSQLDB's default two-phase locking the read waits as well,
 * so a change that read the draft before its UPDATE would pass there too. MySQL's
 * repeatable read also holds because the UPDATE is the transaction's first statement,
 * so its snapshot is taken after the wait; neither engine is run here.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none",
    "spring.test.database.replace=none",
    "spring.datasource.url=jdbc:hsqldb:mem:edit-ordering;hsqldb.tx=mvcc",
    "spring.datasource.username=sa",
    "spring.datasource.password=" })
public class EmailScheduledSendEditOrderingTest {

  private static final String         USER     = "ordering";

  private static final String         LOCAL_ID = "ordered-draft";

  private static final Date           NOW      = new Date(1_800_000_000_000L);

  @Autowired
  private EmailScheduledSendDAO       dao;

  @Autowired
  private EmailBoxDAO                 emailBoxDAO;

  @Autowired
  private PlatformTransactionManager  transactionManager;

  /**
   * The minimal Spring slice: this add-on's entities and repositories, with Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * An edit holds the schedule row and has written a new subject, not committed yet; a
   * checked cancel started then waits on the row, and once the edit commits it reads the
   * edited subject: the check sees what the user's edit says, never the text it replaced.
   *
   * @throws Exception if a transaction thread does
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void aCheckedCancelWaitsForAnOpenEditAndReadsWhatItCommitted() throws Exception {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    Long[] ids = transaction.execute(status -> {
      EmailBoxEntity draft = draft();
      EmailScheduledSendEntity row = dao.saveAndFlush(schedule(draft));
      return new Long[] { draft.getId(), row.getId() };
    });
    CountDownLatch edited = new CountDownLatch(1);
    CountDownLatch commitEdit = new CountDownLatch(1);
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> edit = pool.submit(() -> transaction.executeWithoutResult(status -> {
        assertEquals(1, dao.takeForEdit(USER, LOCAL_ID, NOW, Set.of(ScheduledSendStatus.SCHEDULED, ScheduledSendStatus.FAILED)));
        EmailBoxEntity draft = emailBoxDAO.findById(ids[0]).orElseThrow();
        draft.setSubject("Edited in the Email app");
        emailBoxDAO.saveAndFlush(draft);
        edited.countDown();
        await(commitEdit);
      }));
      await(edited);
      Future<String> checkedCancel = pool.submit(() -> transaction.execute(status -> {
        assertEquals(1, dao.cancel(USER, LOCAL_ID, Set.of(ScheduledSendStatus.SENDING, ScheduledSendStatus.SENT)));
        return emailBoxDAO.findById(ids[0]).orElseThrow().getSubject();
      }));
      // The cancel is parked on the edit's row lock: it has not read anything yet.
      Thread.sleep(500);
      assertFalse(checkedCancel.isDone(), "the checked cancel waits for the open edit");

      commitEdit.countDown();
      edit.get(30, TimeUnit.SECONDS);
      assertEquals("Edited in the Email app", checkedCancel.get(30, TimeUnit.SECONDS));
    } finally {
      commitEdit.countDown();
      pool.shutdownNow();
      transaction.executeWithoutResult(status -> {
        dao.findById(ids[1]).ifPresent(dao::delete);
        emailBoxDAO.deleteById(ids[0]);
      });
    }
  }

  /**
   * A check that refuses the mail, thrown inside the transaction that cancelled or
   * rescheduled it, leaves the schedule as it was on the database: the cancelled row is
   * back, a failed mail keeps its status and its date. The transaction is the one a
   * {@code @Transactional(rollbackFor = Exception.class)} method opens, and the DAO's own
   * writes join it.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void aRefusedCheckLeavesTheScheduleAsItWas() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    Long[] ids = transaction.execute(status -> {
      EmailBoxEntity draft = draft();
      EmailScheduledSendEntity row = schedule(draft);
      row.setStatus(ScheduledSendStatus.FAILED);
      return new Long[] { draft.getId(), dao.saveAndFlush(row).getId() };
    });
    Date originalDate = new Date(NOW.getTime() + 3_600_000L);
    try {
      IllegalArgumentException refused = new IllegalArgumentException("refused");
      assertSame(refused, assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(status -> {
        assertEquals(1,
                     dao.reschedule(USER,
                                    LOCAL_ID,
                                    new Date(NOW.getTime() + 7_200_000L),
                                    "Europe/Paris",
                                    NOW,
                                    Set.of(ScheduledSendStatus.SCHEDULED, ScheduledSendStatus.FAILED),
                                    ScheduledSendStatus.SCHEDULED));
        throw refused;
      })));
      EmailScheduledSendEntity kept = dao.findById(ids[1]).orElseThrow();
      assertEquals(ScheduledSendStatus.FAILED, kept.getStatus());
      assertEquals(originalDate, kept.getScheduledDate());
      assertEquals("UTC", kept.getTimeZone());

      assertThrows(IllegalArgumentException.class, () -> transaction.executeWithoutResult(status -> {
        assertEquals(1, dao.cancel(USER, LOCAL_ID, Set.of(ScheduledSendStatus.SENDING, ScheduledSendStatus.SENT)));
        throw refused;
      }));
      assertTrue(dao.findById(ids[1]).isPresent(), "the cancelled schedule is back");
    } finally {
      transaction.executeWithoutResult(status -> {
        dao.findById(ids[1]).ifPresent(dao::delete);
        emailBoxDAO.deleteById(ids[0]);
      });
    }
  }

  /**
   * The UNCERTAIN-only removal takes a mail whose sending could not be confirmed and no
   * other, and the confirmed removal takes neither it nor a mail being sent or sent, run
   * by the engine against the status sets the storage passes.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void theUncertainOnlyRemovalTakesAnUncertainMailAndNoOther() {
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    org.exoplatform.emailConnector.storage.EmailScheduledSendStorage storage =
                                                                         new org.exoplatform.emailConnector.storage.EmailScheduledSendStorage();
    org.springframework.test.util.ReflectionTestUtils.setField(storage, "emailScheduledSendDAO", dao);
    Long[] ids = transaction.execute(status -> {
      EmailBoxEntity draft = draft();
      return new Long[] { draft.getId(), dao.saveAndFlush(schedule(draft)).getId() };
    });
    try {
      for (ScheduledSendStatus other : ScheduledSendStatus.values()) {
        if (other == ScheduledSendStatus.UNCERTAIN) {
          continue;
        }
        transaction.executeWithoutResult(status -> {
          EmailScheduledSendEntity row = dao.findById(ids[1]).orElseThrow();
          row.setStatus(other);
          dao.saveAndFlush(row);
        });
        assertFalse(Boolean.TRUE.equals(transaction.execute(status -> storage.cancelUncertain(USER, LOCAL_ID))), "a " + other + " mail is kept");
        assertTrue(dao.findById(ids[1]).isPresent());
        if (other == ScheduledSendStatus.SENDING || other == ScheduledSendStatus.SENT) {
          assertFalse(Boolean.TRUE.equals(transaction.execute(status -> storage.cancelConfirmed(USER, LOCAL_ID))),
                      "a " + other + " mail is not removed as confirmed");
        }
      }
      transaction.executeWithoutResult(status -> {
        EmailScheduledSendEntity row = dao.findById(ids[1]).orElseThrow();
        row.setStatus(ScheduledSendStatus.UNCERTAIN);
        dao.saveAndFlush(row);
      });
      assertFalse(Boolean.TRUE.equals(transaction.execute(status -> storage.cancelConfirmed(USER, LOCAL_ID))),
                  "an uncertain mail is not removed as confirmed");
      assertTrue(Boolean.TRUE.equals(transaction.execute(status -> storage.cancelUncertain(USER, LOCAL_ID))));
      assertFalse(dao.findById(ids[1]).isPresent(), "the uncertain mail is taken back");
    } finally {
      transaction.executeWithoutResult(status -> {
        dao.findById(ids[1]).ifPresent(dao::delete);
        emailBoxDAO.deleteById(ids[0]);
      });
    }
  }

  /**
   * Waits on a latch, as a test thread may.
   *
   * @param latch the latch
   */
  private static void await(CountDownLatch latch) {
    try {
      if (!latch.await(30, TimeUnit.SECONDS)) {
        throw new IllegalStateException("timed out");
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new IllegalStateException(e);
    }
  }

  /**
   * A stored local draft of the user.
   *
   * @return the draft
   */
  private EmailBoxEntity draft() {
    EmailBoxEntity draft = new EmailBoxEntity();
    draft.setUserId(USER);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setSender("Alice,alice@example.org");
    draft.setTo("Bob,bob@example.org");
    draft.setSubject("As the user approved it");
    draft.setBody("<p>at eight</p>");
    draft.setReceivedDate(NOW);
    draft.setRead(true);
    draft.setMailHeaderId("<" + LOCAL_ID + "@example.org>");
    draft.setDraftLocalId(LOCAL_ID);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft.setDraftRevision(1L);
    draft.setDraftUpdatedDate(NOW);
    return emailBoxDAO.saveAndFlush(draft);
  }

  /**
   * The draft's schedule, waiting to be sent.
   *
   * @param draft the draft
   * @return the schedule row
   */
  private static EmailScheduledSendEntity schedule(EmailBoxEntity draft) {
    EmailScheduledSendEntity row = new EmailScheduledSendEntity();
    row.setEmailId(draft.getId());
    row.setUserId(USER);
    row.setDraftLocalId(LOCAL_ID);
    row.setScheduledDate(new Date(NOW.getTime() + 3_600_000L));
    row.setNextAttemptDate(new Date(NOW.getTime() + 3_600_000L));
    row.setTimeZone("UTC");
    row.setStatus(ScheduledSendStatus.SCHEDULED);
    row.setCreatedDate(NOW);
    row.setUpdatedDate(NOW);
    return row;
  }
}
