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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;

import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.entity.EmailScheduledSendEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ScheduledSendStatus;

/**
 * Every statement of {@link EmailScheduledSendDAO} (and the three reads added to
 * {@link EmailBoxDAO} for the feature), run by the real engine over the SHIPPED
 * changelog -- in-memory HSQLDB, liquibase on, {@code ddl-auto=none} -- because two
 * of the properties this feature rests on exist only in the database: the unique
 * EMAIL_ID and the ON DELETE CASCADE that removes a schedule with its draft when a
 * bulk JPQL DELETE, which no JPA cascade sees, removes the draft row.
 * <p>
 * Each transition is asserted from both sides: it lands from the state it leaves, and
 * it does NOT land from any other, since the row count of a conditional UPDATE is what
 * the service reads as "who won". The contention itself (several threads at once) is
 * {@code EmailScheduledSendClaimConcurrencyTest}'s.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
public class EmailScheduledSendDAOTest {

  private static final String USER  = "alice";

  private static final String OTHER = "bob";

  private static final String NODE  = "node-a";

  // Whole seconds, as the service writes every claim instant.
  private static final Date   NOW   = new Date(1_800_000_000_000L);

  @Autowired
  private TestEntityManager    entityManager;

  @Autowired
  private EmailScheduledSendDAO dao;

  @Autowired
  private EmailBoxDAO          emailBoxDAO;

  /**
   * The minimal Spring slice: the add-on's entities and repositories, over Boot's
   * auto-configured in-memory database built by the changelog.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * The database clock answers, and answers an instant close to this JVM's: the
   * FROM-less SELECT is something every dialect has to render its own way, and HSQLDB
   * has no FROM-less SELECT at all.
   */
  @Test
  void theDatabaseClockAnswers() {
    Date dbNow = dao.currentTimestamp();
    assertNotNull(dbNow);
    assertTrue(Math.abs(dbNow.getTime() - System.currentTimeMillis()) < 60_000L, "the database clock is the database's now");
  }

  /** A draft has one schedule at most: the database refuses a second. */
  @Test
  void aDraftCannotBeScheduledTwice() {
    EmailBoxEntity draft = draft(USER, "d1");
    dao.saveAndFlush(row(draft, ScheduledSendStatus.SCHEDULED, NOW));
    EmailScheduledSendEntity second = row(draft, ScheduledSendStatus.SCHEDULED, NOW);
    assertThrows(DataIntegrityViolationException.class, () -> dao.saveAndFlush(second));
  }

  /**
   * The schedule goes with its draft even when the draft goes through a bulk JPQL
   * DELETE: the cascade is the database's, declared by 1.0.0-65.
   */
  @Test
  void aBulkDeleteOfTheDraftRemovesItsSchedule() {
    EmailBoxEntity draft = draft(USER, "d1");
    EmailScheduledSendEntity row = dao.saveAndFlush(row(draft, ScheduledSendStatus.SCHEDULED, NOW));
    entityManager.clear();

    emailBoxDAO.deleteEmailsByIds(List.of(draft.getId()));
    entityManager.flush();
    entityManager.clear();

    assertFalse(dao.findById(row.getId()).isPresent(), "the schedule row is gone with its draft");
  }

  /** Only due SCHEDULED rows are selected, the longest waiting first. */
  @Test
  void theDueScanReturnsDueScheduledRowsOldestFirst() {
    EmailScheduledSendEntity later = dao.saveAndFlush(row(draft(USER, "d1"), ScheduledSendStatus.SCHEDULED, ago(60)));
    EmailScheduledSendEntity earlier = dao.saveAndFlush(row(draft(USER, "d2"), ScheduledSendStatus.SCHEDULED, ago(120)));
    dao.saveAndFlush(row(draft(USER, "d3"), ScheduledSendStatus.SCHEDULED, in(60)));
    dao.saveAndFlush(row(draft(USER, "d4"), ScheduledSendStatus.FAILED, ago(300)));
    EmailScheduledSendEntity uncertain = dao.saveAndFlush(row(draft(USER, "d5"), ScheduledSendStatus.UNCERTAIN, ago(10)));

    assertEquals(List.of(earlier.getId(), later.getId()),
                 dao.findDueIds(ScheduledSendStatus.SCHEDULED, NOW, PageRequest.of(0, 10)));
    assertEquals(List.of(earlier.getId()), dao.findDueIds(ScheduledSendStatus.SCHEDULED, NOW, PageRequest.of(0, 1)));
    assertEquals(List.of(uncertain.getId()), dao.findDueIds(ScheduledSendStatus.UNCERTAIN, NOW, PageRequest.of(0, 10)));
  }

  /**
   * The claim lands once, on a due SCHEDULED row only, and stamps the run: this node,
   * this instant, one more attempt.
   */
  @Test
  void theClaimLandsOnceAndOnlyOnADueRow() {
    EmailScheduledSendEntity due = dao.saveAndFlush(row(draft(USER, "d1"), ScheduledSendStatus.SCHEDULED, ago(1)));
    EmailScheduledSendEntity notYet = dao.saveAndFlush(row(draft(USER, "d2"), ScheduledSendStatus.SCHEDULED, in(1)));

    assertEquals(1, claim(due.getId()));
    assertEquals(0, claim(due.getId()), "a SENDING row is not claimed again");
    assertEquals(0, claim(notYet.getId()), "a row not yet due is not claimed");

    EmailScheduledSendEntity claimed = dao.findById(due.getId()).orElseThrow();
    assertEquals(ScheduledSendStatus.SENDING, claimed.getStatus());
    assertEquals(NODE, claimed.getClaimedBy());
    assertEquals(NOW, claimed.getClaimedDate());
    assertEquals(1, claimed.getAttempts());
  }

  /** "Send now" starts from SCHEDULED (whatever the date), FAILED or UNCERTAIN only. */
  @Test
  void sendNowStartsOnlyFromAStateTheOwnerMaySendFrom() {
    for (ScheduledSendStatus from : ScheduledSendStatus.values()) {
      String localId = "now-" + from;
      EmailScheduledSendEntity row = row(draft(USER, localId), from, in(600));
      row.setAttempts(4);
      dao.saveAndFlush(row);
      boolean expected = Set.of(ScheduledSendStatus.SCHEDULED, ScheduledSendStatus.FAILED, ScheduledSendStatus.UNCERTAIN)
                            .contains(from);
      assertEquals(expected ? 1 : 0,
                   dao.claimNow(USER, localId, NODE, NOW, Set.of(ScheduledSendStatus.SCHEDULED,
                                                                 ScheduledSendStatus.FAILED,
                                                                 ScheduledSendStatus.UNCERTAIN),
                                ScheduledSendStatus.SENDING),
                   "send now from " + from);
      EmailScheduledSendEntity after = dao.findByUserIdAndDraftLocalId(USER, localId).orElseThrow();
      assertEquals(expected ? ScheduledSendStatus.SENDING : from, after.getStatus());
      assertEquals(expected ? 1 : 4, after.getAttempts(), "the owner's action starts a fresh retry budget");
    }
    assertEquals(0,
                 dao.claimNow(OTHER, "now-SCHEDULED", NODE, NOW, Set.of(ScheduledSendStatus.SCHEDULED), ScheduledSendStatus.SENDING),
                 "another user's send now never lands on this user's draft");
  }

  /** A new date is taken from SCHEDULED or FAILED only, and resets the run columns. */
  @Test
  void aRescheduleLandsOnlyOnAMailThatIsNotBeingSent() {
    for (ScheduledSendStatus from : ScheduledSendStatus.values()) {
      String localId = "re-" + from;
      EmailScheduledSendEntity row = row(draft(USER, localId), from, ago(5));
      row.setAttempts(3);
      row.setLastError("NETWORK");
      row.setClaimedBy(NODE);
      row.setClaimedDate(ago(5));
      dao.saveAndFlush(row);
      boolean expected = from == ScheduledSendStatus.SCHEDULED || from == ScheduledSendStatus.FAILED;
      assertEquals(expected ? 1 : 0,
                   dao.reschedule(USER, localId, in(90), "Europe/Paris", NOW,
                                  Set.of(ScheduledSendStatus.SCHEDULED, ScheduledSendStatus.FAILED),
                                  ScheduledSendStatus.SCHEDULED),
                   "reschedule from " + from);
      EmailScheduledSendEntity after = dao.findByUserIdAndDraftLocalId(USER, localId).orElseThrow();
      if (expected) {
        assertEquals(ScheduledSendStatus.SCHEDULED, after.getStatus());
        assertEquals(in(90), after.getScheduledDate());
        assertEquals(in(90), after.getNextAttemptDate());
        assertEquals("Europe/Paris", after.getTimeZone());
        assertEquals(0, after.getAttempts());
        assertNull(after.getLastError());
        assertNull(after.getClaimedBy());
      } else {
        assertEquals(from, after.getStatus());
      }
    }
  }

  /** A cancel removes the row unless it is being sent or already sent. */
  @Test
  void aCancelNeverRemovesAMailBeingSentOrSent() {
    for (ScheduledSendStatus from : ScheduledSendStatus.values()) {
      String localId = "cancel-" + from;
      dao.saveAndFlush(row(draft(USER, localId), from, NOW));
      boolean expected = from != ScheduledSendStatus.SENDING && from != ScheduledSendStatus.SENT;
      assertEquals(expected ? 1 : 0,
                   dao.cancel(USER, localId, Set.of(ScheduledSendStatus.SENDING, ScheduledSendStatus.SENT)),
                   "cancel from " + from);
      assertEquals(!expected, dao.findByUserIdAndDraftLocalId(USER, localId).isPresent());
    }
  }

  /**
   * The writes of a run land only for that run: the node and the claim instant it
   * stamped. A run whose row was taken over (another node, or a later claim) writes
   * nothing.
   */
  @Test
  void aRunWritesOnlyUnderItsOwnClaim() {
    EmailScheduledSendEntity row = dao.saveAndFlush(row(draft(USER, "d1"), ScheduledSendStatus.SCHEDULED, ago(1)));
    assertEquals(1, claim(row.getId()));

    assertEquals(0, dao.markSent(row.getId(), "node-b", NOW, NOW, ScheduledSendStatus.SENDING, ScheduledSendStatus.SENT));
    assertEquals(0,
                 dao.markSent(row.getId(), NODE, new Date(NOW.getTime() - 1000), NOW, ScheduledSendStatus.SENDING,
                              ScheduledSendStatus.SENT));
    assertEquals(0,
                 dao.endRun(row.getId(), "node-b", NOW, ScheduledSendStatus.FAILED, "REFUSED", null, NOW,
                            ScheduledSendStatus.SENDING));
    assertEquals(1,
                 dao.endRun(row.getId(), NODE, NOW, ScheduledSendStatus.SCHEDULED, "NETWORK", in(1), NOW,
                            ScheduledSendStatus.SENDING));
    EmailScheduledSendEntity retried = dao.findById(row.getId()).orElseThrow();
    assertEquals(ScheduledSendStatus.SCHEDULED, retried.getStatus());
    assertEquals("NETWORK", retried.getLastError());
    assertEquals(in(1), retried.getNextAttemptDate());
    assertEquals(0,
                 dao.markSent(row.getId(), NODE, NOW, NOW, ScheduledSendStatus.SENDING, ScheduledSendStatus.SENT),
                 "an ended run writes nothing more");
  }

  /** The mail server accepted it: SENDING becomes SENT for the run that holds it. */
  @Test
  void anAcceptedMessageIsMarkedSent() {
    EmailScheduledSendEntity row = dao.saveAndFlush(row(draft(USER, "d1"), ScheduledSendStatus.SCHEDULED, ago(1)));
    assertEquals(1, claim(row.getId()));
    assertEquals(1, dao.markSent(row.getId(), NODE, NOW, NOW, ScheduledSendStatus.SENDING, ScheduledSendStatus.SENT));
    assertEquals(ScheduledSendStatus.SENT, dao.findById(row.getId()).orElseThrow().getStatus());
    assertEquals(List.of(row.getId()),
                 dao.findSentBefore(ScheduledSendStatus.SENT, in(1), PageRequest.of(0, 10))
                    .stream()
                    .map(EmailScheduledSendEntity::getId)
                    .toList());
    assertTrue(dao.findSentBefore(ScheduledSendStatus.SENT, NOW, PageRequest.of(0, 10)).isEmpty(),
               "a row written at the instant is not before it");
  }

  /**
   * A restarted node's recovery makes ITS interrupted sends UNCERTAIN, never
   * SCHEDULED, leaving alone another node's and the ones it is running now.
   */
  @Test
  void aRestartedNodeMakesItsInterruptedSendsUncertain() {
    EmailScheduledSendEntity mine = sending(draft(USER, "mine"), NODE, ago(1));
    EmailScheduledSendEntity running = sending(draft(USER, "running"), NODE, ago(1));
    EmailScheduledSendEntity theirs = sending(draft(USER, "theirs"), "node-b", ago(1));

    assertEquals(1,
                 dao.markUncertainOf(NODE, List.of(running.getId()), "INTERRUPTED", NOW, ScheduledSendStatus.SENDING,
                                     ScheduledSendStatus.UNCERTAIN));
    EmailScheduledSendEntity recovered = dao.findById(mine.getId()).orElseThrow();
    assertEquals(ScheduledSendStatus.UNCERTAIN, recovered.getStatus());
    assertEquals("INTERRUPTED", recovered.getLastError());
    assertEquals(NOW, recovered.getNextAttemptDate(), "its Sent-folder check is due at once");
    assertEquals(ScheduledSendStatus.SENDING, dao.findById(running.getId()).orElseThrow().getStatus());
    assertEquals(ScheduledSendStatus.SENDING, dao.findById(theirs.getId()).orElseThrow().getStatus());
    assertEquals(0,
                 dao.markUncertainOf("node-c", List.of(-1L), "INTERRUPTED", NOW, ScheduledSendStatus.SENDING,
                                     ScheduledSendStatus.UNCERTAIN),
                 "a node with nothing of its own recovers nothing");
  }

  /** Only a claim older than the stuck timeout is recovered by another node. */
  @Test
  void onlyAStaleClaimIsRecoveredByAnyNode() {
    EmailScheduledSendEntity stale = sending(draft(USER, "stale"), "node-b", ago(31 * 60));
    EmailScheduledSendEntity fresh = sending(draft(USER, "fresh"), "node-b", ago(29 * 60));

    assertEquals(1,
                 dao.markStaleUncertain(ago(30 * 60), List.of(-1L), "INTERRUPTED", NOW, ScheduledSendStatus.SENDING,
                                        ScheduledSendStatus.UNCERTAIN));
    assertEquals(ScheduledSendStatus.UNCERTAIN, dao.findById(stale.getId()).orElseThrow().getStatus());
    assertEquals(ScheduledSendStatus.SENDING, dao.findById(fresh.getId()).orElseThrow().getStatus());
  }

  /**
   * An UNCERTAIN row gets one Sent-folder check, claimed once; a found message makes it
   * SENT under that check's claim only.
   */
  @Test
  void anUncertainRowIsCheckedOnceAndResolvedByItsChecker() {
    EmailScheduledSendEntity row = row(draft(USER, "d1"), ScheduledSendStatus.UNCERTAIN, ago(1));
    dao.saveAndFlush(row);

    assertEquals(1, dao.claimCheck(row.getId(), NODE, NOW, ScheduledSendStatus.UNCERTAIN));
    assertEquals(0, dao.claimCheck(row.getId(), "node-b", NOW, ScheduledSendStatus.UNCERTAIN), "checked once");
    assertTrue(dao.findDueIds(ScheduledSendStatus.UNCERTAIN, in(600), PageRequest.of(0, 10)).isEmpty(),
               "no longer due for a check");
    assertEquals(0,
                 dao.markCheckedSent(row.getId(), "node-b", NOW, NOW, ScheduledSendStatus.UNCERTAIN, ScheduledSendStatus.SENT));
    assertEquals(1,
                 dao.markCheckedSent(row.getId(), NODE, NOW, NOW, ScheduledSendStatus.UNCERTAIN, ScheduledSendStatus.SENT));
    assertEquals(ScheduledSendStatus.SENT, dao.findById(row.getId()).orElseThrow().getStatus());
  }

  /**
   * The owner's list, count, badge and limit: SENT rows are never counted nor listed,
   * another user's rows never are, and the list is soonest first.
   */
  @Test
  void theOwnersListAndCountsLeaveOutSentRowsAndOtherUsers() {
    EmailScheduledSendEntity second = dao.saveAndFlush(row(draft(USER, "d1"), ScheduledSendStatus.SCHEDULED, in(120)));
    EmailScheduledSendEntity first = dao.saveAndFlush(row(draft(USER, "d2"), ScheduledSendStatus.FAILED, in(60)));
    EmailScheduledSendEntity third = dao.saveAndFlush(row(draft(USER, "d3"), ScheduledSendStatus.UNCERTAIN, in(180)));
    dao.saveAndFlush(row(draft(USER, "d4"), ScheduledSendStatus.SENT, in(30)));
    dao.saveAndFlush(row(draft(OTHER, "d5"), ScheduledSendStatus.FAILED, in(30)));

    Set<ScheduledSendStatus> notListed = Set.of(ScheduledSendStatus.SENT);
    assertEquals(List.of(first.getId(), second.getId(), third.getId()),
                 dao.findListed(USER, notListed, PageRequest.of(0, 10)).stream().map(EmailScheduledSendEntity::getId).toList());
    assertEquals(List.of(third.getId()),
                 dao.findListed(USER, notListed, PageRequest.of(1, 2)).stream().map(EmailScheduledSendEntity::getId).toList());
    assertEquals(3, dao.countByUserIdExcluding(USER, notListed));
    Object[] badge = dao.countListedAndAttention(USER,
                                                 notListed,
                                                 Set.of(ScheduledSendStatus.FAILED, ScheduledSendStatus.UNCERTAIN))
                        .get(0);
    assertEquals(3L, ((Number) badge[0]).longValue());
    assertEquals(2L, ((Number) badge[1]).longValue());
    Object[] empty = dao.countListedAndAttention("nobody", notListed, Set.of(ScheduledSendStatus.FAILED)).get(0);
    assertEquals(0L, ((Number) empty[0]).longValue());
  }

  /**
   * The Drafts folder leaves scheduled drafts out, in its list and in its count, and
   * the "Scheduled" view reads its drafts by id, the owner's only.
   */
  @Test
  void theDraftsFolderLeavesScheduledDraftsOut() {
    EmailBoxEntity plain = draft(USER, "plain");
    EmailBoxEntity scheduled = draft(USER, "scheduled");
    EmailBoxEntity theirs = draft(OTHER, "theirs");
    dao.saveAndFlush(row(scheduled, ScheduledSendStatus.SCHEDULED, in(60)));
    entityManager.clear();

    assertEquals(List.of(plain.getId()),
                 emailBoxDAO.findUnscheduledByUserIdAndFolderWithAttachments(USER, MailFolder.DRAFTS)
                            .stream()
                            .map(EmailBoxEntity::getId)
                            .toList());
    assertEquals(1, emailBoxDAO.countUnscheduledByUserIdAndFolder(USER, MailFolder.DRAFTS));
    assertEquals(List.of(scheduled.getId()),
                 emailBoxDAO.findByUserIdAndIds(USER, List.of(scheduled.getId(), theirs.getId()))
                            .stream()
                            .map(EmailBoxEntity::getId)
                            .toList());
  }

  /**
   * Claims a row for this node at {@link #NOW}.
   *
   * @param id the row id
   * @return the row count
   */
  private int claim(long id) {
    return dao.claim(id, NODE, NOW, ScheduledSendStatus.SCHEDULED, ScheduledSendStatus.SENDING);
  }

  /**
   * Stores a row already being sent by a node since an instant.
   *
   * @param draft its draft
   * @param node the sending node
   * @param claimedDate the claim instant
   * @return the stored row
   */
  private EmailScheduledSendEntity sending(EmailBoxEntity draft, String node, Date claimedDate) {
    EmailScheduledSendEntity row = row(draft, ScheduledSendStatus.SENDING, claimedDate);
    row.setClaimedBy(node);
    row.setClaimedDate(claimedDate);
    row.setAttempts(1);
    return dao.saveAndFlush(row);
  }

  /**
   * A schedule row for a draft, due at an instant.
   *
   * @param draft the draft
   * @param status its status
   * @param due its scheduled and next-attempt instant
   * @return the row, not stored
   */
  private EmailScheduledSendEntity row(EmailBoxEntity draft, ScheduledSendStatus status, Date due) {
    EmailScheduledSendEntity row = new EmailScheduledSendEntity();
    row.setEmailId(draft.getId());
    row.setUserId(draft.getUserId());
    row.setDraftLocalId(draft.getDraftLocalId());
    row.setScheduledDate(due);
    row.setNextAttemptDate(due);
    row.setTimeZone("UTC");
    row.setStatus(status);
    row.setCreatedDate(ago(3600));
    row.setUpdatedDate(ago(3600));
    return row;
  }

  /**
   * A stored local draft.
   *
   * @param userId the owner
   * @param draftLocalId its handle
   * @return the stored row
   */
  private EmailBoxEntity draft(String userId, String draftLocalId) {
    EmailBoxEntity draft = new EmailBoxEntity();
    draft.setUserId(userId);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setSender("Alice,alice@example.org");
    draft.setTo("Bob,bob@example.org");
    draft.setSubject("See you tomorrow");
    draft.setBody("<p>at eight</p>");
    draft.setReceivedDate(NOW);
    draft.setRead(true);
    draft.setMailHeaderId("<" + draftLocalId + "@example.org>");
    draft.setDraftLocalId(draftLocalId);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft.setDraftRevision(1L);
    draft.setDraftUpdatedDate(NOW);
    return emailBoxDAO.saveAndFlush(draft);
  }

  /**
   * An instant before {@link #NOW}.
   *
   * @param seconds how long before
   * @return the instant
   */
  private static Date ago(long seconds) {
    return new Date(NOW.getTime() - seconds * 1000L);
  }

  /**
   * An instant after {@link #NOW}.
   *
   * @param seconds how long after
   * @return the instant
   */
  private static Date in(long seconds) {
    return new Date(NOW.getTime() + seconds * 1000L);
  }
}
