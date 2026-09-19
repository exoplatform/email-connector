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

import java.util.Collection;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailScheduledSendEntity;
import org.exoplatform.emailConnector.model.ScheduledSendStatus;

/**
 * The schedule table, and the statements that make a dispatcher firing on every node
 * and the owner's own actions agree, through the database, on who sends what.
 * <p>
 * Every transition is a conditional UPDATE (or DELETE) whose WHERE clause carries the
 * state it leaves, so of two callers racing on one row exactly one sees a row count of
 * one; the count is the answer, not a side effect. Every write of a RUN is further
 * conditioned on the claim that run holds -- the node that took it and the instant it
 * took it at -- so a run whose row was recovered and claimed again can never write
 * over the new run. That instant is always a WHOLE SECOND (the service truncates it):
 * a TIMESTAMP column may not keep milliseconds (MySQL rounds them away), and an
 * equality on a value it rounded would silently match nothing there.
 * <p>
 * Statuses are parameters, not JPQL enum literals, so each statement reads as the
 * transition it is at the call site. Every instant is a parameter too, taken from the
 * clock the service decides on ({@link #currentTimestamp()}, guarded): no date
 * arithmetic in JPQL, which is not portable across the supported vendors.
 */
public interface EmailScheduledSendDAO extends JpaRepository<EmailScheduledSendEntity, Long> {

  /**
   * The guard every write of a run carries: the row is still being sent, by the node
   * and under the claim instant that run's claim stamped.
   */
  String RUN_GUARD = "s.id = :id AND s.status = :sending AND s.claimedBy = :node AND s.claimedDate = :claimedDate";

  /**
   * The database clock, the instant every node compares against.
   *
   * @return the database's current timestamp
   */
  @Query("SELECT CURRENT_TIMESTAMP")
  Date currentTimestamp();

  /**
   * The schedule of one draft of one user.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the draft's handle
   * @return the row, if the draft is scheduled
   */
  Optional<EmailScheduledSendEntity> findByUserIdAndDraftLocalId(String userId, String draftLocalId);

  /**
   * How many schedule rows a user holds in states other than the given ones: the
   * per-user limit's count.
   *
   * @param userId the mailbox owner
   * @param excluded the statuses not counted
   * @return the count
   */
  @Query("SELECT COUNT(s) FROM EmailScheduledSendEntity s WHERE s.userId = :userId AND s.status NOT IN :excluded")
  long countByUserIdExcluding(@Param("userId")
  String userId, @Param("excluded")
  Collection<ScheduledSendStatus> excluded);

  /**
   * The "Scheduled" view's badge in one read: how many rows are listed, and how many
   * of them need the owner's attention.
   *
   * @param userId the mailbox owner
   * @param excluded the statuses not listed
   * @param attention the statuses that need the owner
   * @return one row: the listed count and the attention count (the latter null on an
   *         empty set, on some vendors)
   */
  @Query("SELECT COUNT(s), SUM(CASE WHEN s.status IN :attention THEN 1 ELSE 0 END) FROM EmailScheduledSendEntity s"
      + " WHERE s.userId = :userId AND s.status NOT IN :excluded")
  List<Object[]> countListedAndAttention(@Param("userId")
  String userId, @Param("excluded")
  Collection<ScheduledSendStatus> excluded, @Param("attention")
  Collection<ScheduledSendStatus> attention);

  /**
   * A page of a user's schedule, soonest first.
   *
   * @param userId the mailbox owner
   * @param excluded the statuses not listed
   * @param pageable the page; required
   * @return the rows
   */
  @Query("SELECT s FROM EmailScheduledSendEntity s WHERE s.userId = :userId AND s.status NOT IN :excluded"
      + " ORDER BY s.scheduledDate ASC, s.id ASC")
  List<EmailScheduledSendEntity> findListed(@Param("userId")
  String userId, @Param("excluded")
  Collection<ScheduledSendStatus> excluded, Pageable pageable);

  /**
   * The rows in a status whose next step is due, the longest waiting first: SCHEDULED
   * rows to send, UNCERTAIN rows whose Sent-folder check is pending. Served by the
   * (STATUS, NEXT_ATTEMPT_DATE) index.
   *
   * @param status the status
   * @param now the reference instant
   * @param pageable the bound; required
   * @return the due ids
   */
  @Query("SELECT s.id FROM EmailScheduledSendEntity s WHERE s.status = :status AND s.nextAttemptDate <= :now"
      + " ORDER BY s.nextAttemptDate ASC, s.id ASC")
  List<Long> findDueIds(@Param("status")
  ScheduledSendStatus status, @Param("now")
  Date now, Pageable pageable);

  /**
   * SENT rows last written before an instant: transmitted mails whose draft row a run
   * did not get to remove (its node stopped in between).
   *
   * @param sent the SENT status
   * @param before rows updated strictly before this instant
   * @param pageable the bound; required
   * @return the rows
   */
  @Query("SELECT s FROM EmailScheduledSendEntity s WHERE s.status = :sent AND s.updatedDate < :before ORDER BY s.id ASC")
  List<EmailScheduledSendEntity> findSentBefore(@Param("sent")
  ScheduledSendStatus sent, @Param("before")
  Date before, Pageable pageable);

  /**
   * The dispatcher's claim: a due SCHEDULED row becomes SENDING, stamped with this
   * node, this instant and the next attempt number.
   *
   * @param id the row id
   * @param node the claiming node
   * @param now the claim instant
   * @param scheduled the SCHEDULED status
   * @param sending the SENDING status
   * @return one when the caller now holds the claim, zero otherwise
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailScheduledSendEntity s SET s.status = :sending, s.claimedBy = :node, s.claimedDate = :now,"
      + " s.attempts = s.attempts + 1, s.updatedDate = :now"
      + " WHERE s.id = :id AND s.status = :scheduled AND s.nextAttemptDate <= :now")
  int claim(@Param("id")
  long id, @Param("node")
  String node, @Param("now")
  Date now, @Param("scheduled")
  ScheduledSendStatus scheduled, @Param("sending")
  ScheduledSendStatus sending);

  /**
   * The owner's "send now" (and "retry"): the same claim, from any state the owner may
   * send from, whatever the date. It shares the row with the dispatcher, so of the two
   * racing, one wins and the other is told zero. The attempt count starts again at one:
   * the automatic retries a network failure earns are counted from the owner's action.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the draft's handle
   * @param node the claiming node
   * @param now the claim instant
   * @param from the statuses a send now may start from
   * @param sending the SENDING status
   * @return one when the caller now holds the claim, zero otherwise
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailScheduledSendEntity s SET s.status = :sending, s.claimedBy = :node, s.claimedDate = :now,"
      + " s.attempts = 1, s.updatedDate = :now"
      + " WHERE s.userId = :userId AND s.draftLocalId = :draftLocalId AND s.status IN :from")
  int claimNow(@Param("userId")
  String userId, @Param("draftLocalId")
  String draftLocalId, @Param("node")
  String node, @Param("now")
  Date now, @Param("from")
  Collection<ScheduledSendStatus> from, @Param("sending")
  ScheduledSendStatus sending);

  /**
   * A new date for a mail that is not being sent: back to SCHEDULED, due at that date,
   * with a fresh error slate and a fresh retry budget.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the draft's handle
   * @param scheduledDate the new instant
   * @param timeZone the zone it was chosen in
   * @param now the write instant
   * @param from the statuses a reschedule may start from
   * @param scheduled the SCHEDULED status
   * @return one when rescheduled, zero when the row is absent or in another state
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailScheduledSendEntity s SET s.status = :scheduled, s.scheduledDate = :scheduledDate,"
      + " s.nextAttemptDate = :scheduledDate, s.timeZone = :timeZone, s.lastError = NULL, s.claimedBy = NULL,"
      + " s.claimedDate = NULL, s.attempts = 0, s.updatedDate = :now"
      + " WHERE s.userId = :userId AND s.draftLocalId = :draftLocalId AND s.status IN :from")
  int reschedule(@Param("userId")
  String userId, @Param("draftLocalId")
  String draftLocalId, @Param("scheduledDate")
  Date scheduledDate, @Param("timeZone")
  String timeZone, @Param("now")
  Date now, @Param("from")
  Collection<ScheduledSendStatus> from, @Param("scheduled")
  ScheduledSendStatus scheduled);

  /**
   * Removes a schedule, which puts its draft back in Drafts -- unless it is being sent
   * or already sent, which are exactly the states the caller must be told about.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the draft's handle
   * @param kept the statuses a cancel may not remove
   * @return one when removed, zero otherwise
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM EmailScheduledSendEntity s WHERE s.userId = :userId AND s.draftLocalId = :draftLocalId"
      + " AND s.status NOT IN :kept")
  int cancel(@Param("userId")
  String userId, @Param("draftLocalId")
  String draftLocalId, @Param("kept")
  Collection<ScheduledSendStatus> kept);

  /**
   * The mail server accepted the message: SENDING becomes SENT, for this run only.
   *
   * @param id the row id
   * @param node the run's node
   * @param claimedDate the run's claim instant
   * @param now the write instant
   * @param sending the SENDING status
   * @param sent the SENT status
   * @return one when recorded, zero when the row is no longer this run's
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailScheduledSendEntity s SET s.status = :sent, s.lastError = NULL, s.updatedDate = :now WHERE "
      + RUN_GUARD)
  int markSent(@Param("id")
  long id, @Param("node")
  String node, @Param("claimedDate")
  Date claimedDate, @Param("now")
  Date now, @Param("sending")
  ScheduledSendStatus sending, @Param("sent")
  ScheduledSendStatus sent);

  /**
   * The run ends in a state of the caller's choosing (FAILED, UNCERTAIN, or SCHEDULED
   * after a failure to even connect), with its error code and its next step's instant,
   * for this run only. The claim columns are left as they are: they say who tried last.
   *
   * @param id the row id
   * @param node the run's node
   * @param claimedDate the run's claim instant
   * @param status the state the run ends in
   * @param error the error code
   * @param nextAttemptDate the next step's instant, or null for none
   * @param now the write instant
   * @param sending the SENDING status
   * @return one when recorded, zero when the row is no longer this run's
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailScheduledSendEntity s SET s.status = :status, s.lastError = :error,"
      + " s.nextAttemptDate = :nextAttemptDate, s.updatedDate = :now WHERE " + RUN_GUARD)
  int endRun(@Param("id")
  long id, @Param("node")
  String node, @Param("claimedDate")
  Date claimedDate, @Param("status")
  ScheduledSendStatus status, @Param("error")
  String error, @Param("nextAttemptDate")
  Date nextAttemptDate, @Param("now")
  Date now, @Param("sending")
  ScheduledSendStatus sending);

  /**
   * A restarted node's recovery: every row it was sending when it stopped, except the
   * runs it has started since, becomes UNCERTAIN -- never SCHEDULED, since the message
   * may have gone out -- with its Sent-folder check due at once.
   *
   * @param node this node
   * @param inFlight ids of rows this node is sending right now (never empty: pass a
   *          sentinel)
   * @param error the error code
   * @param now the write instant, also the check's due instant
   * @param sending the SENDING status
   * @param uncertain the UNCERTAIN status
   * @return how many rows were recovered
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailScheduledSendEntity s SET s.status = :uncertain, s.lastError = :error, s.nextAttemptDate = :now,"
      + " s.updatedDate = :now WHERE s.status = :sending AND s.claimedBy = :node AND s.id NOT IN :inFlight")
  int markUncertainOf(@Param("node")
  String node, @Param("inFlight")
  Collection<Long> inFlight, @Param("error")
  String error, @Param("now")
  Date now, @Param("sending")
  ScheduledSendStatus sending, @Param("uncertain")
  ScheduledSendStatus uncertain);

  /**
   * Any node's recovery of a claim held for longer than a send can take: its node
   * stopped (or never came back), so the row becomes UNCERTAIN, never SCHEDULED.
   *
   * @param staleBefore claims taken strictly before this instant
   * @param inFlight ids of rows this node is sending right now (never empty: pass a
   *          sentinel)
   * @param error the error code
   * @param now the write instant, also the check's due instant
   * @param sending the SENDING status
   * @param uncertain the UNCERTAIN status
   * @return how many rows were recovered
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailScheduledSendEntity s SET s.status = :uncertain, s.lastError = :error, s.nextAttemptDate = :now,"
      + " s.updatedDate = :now WHERE s.status = :sending AND s.claimedDate < :staleBefore AND s.id NOT IN :inFlight")
  int markStaleUncertain(@Param("staleBefore")
  Date staleBefore, @Param("inFlight")
  Collection<Long> inFlight, @Param("error")
  String error, @Param("now")
  Date now, @Param("sending")
  ScheduledSendStatus sending, @Param("uncertain")
  ScheduledSendStatus uncertain);

  /**
   * Claims the one Sent-folder check an UNCERTAIN row gets: the due instant is cleared
   * (so no other node checks it again) and the claim columns name the checker.
   *
   * @param id the row id
   * @param node the checking node
   * @param now the claim instant
   * @param uncertain the UNCERTAIN status
   * @return one when the caller now owns the check, zero otherwise
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailScheduledSendEntity s SET s.nextAttemptDate = NULL, s.claimedBy = :node, s.claimedDate = :now,"
      + " s.updatedDate = :now WHERE s.id = :id AND s.status = :uncertain AND s.nextAttemptDate <= :now")
  int claimCheck(@Param("id")
  long id, @Param("node")
  String node, @Param("now")
  Date now, @Param("uncertain")
  ScheduledSendStatus uncertain);

  /**
   * The check found the message in the Sent folder: UNCERTAIN becomes SENT, unless the
   * owner acted on the row in the meantime.
   *
   * @param id the row id
   * @param node the checking node
   * @param claimedDate the check's claim instant
   * @param now the write instant
   * @param uncertain the UNCERTAIN status
   * @param sent the SENT status
   * @return one when recorded, zero otherwise
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailScheduledSendEntity s SET s.status = :sent, s.lastError = NULL, s.updatedDate = :now"
      + " WHERE s.id = :id AND s.status = :uncertain AND s.claimedBy = :node AND s.claimedDate = :claimedDate"
      + " AND s.nextAttemptDate IS NULL")
  int markCheckedSent(@Param("id")
  long id, @Param("node")
  String node, @Param("claimedDate")
  Date claimedDate, @Param("now")
  Date now, @Param("uncertain")
  ScheduledSendStatus uncertain, @Param("sent")
  ScheduledSendStatus sent);
}
