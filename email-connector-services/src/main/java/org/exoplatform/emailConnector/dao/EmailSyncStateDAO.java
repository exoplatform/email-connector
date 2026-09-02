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

import java.util.Date;
import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailSyncStateEntity;

/**
 * The mailbox sync-state table: one row per connected mailbox, and the statements
 * that make a dispatcher firing on every node agree, through the database, on who
 * synchronizes what.
 * <p>
 * Every write is a targeted UPDATE naming the columns its writer owns, for the
 * reason {@link EmailFolderDAO} gives for its own: three writers share the row (the
 * claim, the release, the activity stamp) and a read-modify-save from any of them
 * would put back what the others committed since the read. The claim and the release
 * go further and carry their correctness in the WHERE clause: a claim only lands on
 * an unclaimed or stale row, a release only on the row its own node claimed, and the
 * row count each returns is the answer, not a side effect.
 */
public interface EmailSyncStateDAO extends JpaRepository<EmailSyncStateEntity, String> {

  /**
   * The due predicate, shared by the selection, its count and its oldest-row read
   * so the three never disagree. A row is due when nobody holds a live claim on it
   * and either it was never synchronized, or its owner is active (an activity stamp
   * at or after {@code activeSince}) and its last sync is older than the active
   * period, or its owner is inactive (no stamp, or one before {@code activeSince})
   * and its last sync is older than the inactive period. The tier is decided inside
   * the query, on precomputed thresholds, so no date arithmetic runs in JPQL.
   */
  String DUE_PREDICATE = "(s.syncStartedDate IS NULL OR s.syncStartedDate < :staleBefore)"
      + " AND (s.lastSyncDate IS NULL"
      + " OR (s.lastActivityDate IS NOT NULL AND s.lastActivityDate >= :activeSince AND s.lastSyncDate < :activeDueBefore)"
      + " OR ((s.lastActivityDate IS NULL OR s.lastActivityDate < :activeSince) AND s.lastSyncDate < :inactiveDueBefore))";

  /**
   * The mailboxes due for a synchronization, the one waiting longest first, bounded
   * to what the caller can run now.
   * <p>
   * One row per USER, which is what makes a batch of ten mean ten users: the
   * caldav-integration sweep learnt the hard way that paging the per-row unit (a
   * user's collections there) lets one user's forty rows fill every batch while
   * everyone else starves, and its log read "swept 1 account" like throughput. This
   * table is per mailbox by construction, so the lesson costs nothing here, but it
   * is the reason the unit of this page is never anything finer.
   * <p>
   * Never-synchronized rows first, through a CASE rather than {@code NULLS FIRST},
   * which MySQL does not have: a fresh connection's first download must not queue
   * behind the routine cadence on any vendor. The index on LAST_SYNC_DATE serves
   * the second key; that it does on MySQL and PostgreSQL is not something the HSQLDB
   * run of {@code EmailSyncStateDAOTest} can prove, and it does not claim to.
   *
   * @param activeSince an activity stamp at or after this instant makes the owner active
   * @param activeDueBefore an active mailbox last synchronized strictly before this is due
   * @param inactiveDueBefore an inactive mailbox last synchronized strictly before this is due
   * @param staleBefore a claim taken strictly before this instant no longer counts
   * @param pageable the bound; required, this set spans every mailbox
   * @return the due user ids, oldest last sync first, never null
   */
  @Query("SELECT s.userId FROM EmailSyncStateEntity s WHERE " + DUE_PREDICATE
      + " ORDER BY CASE WHEN s.lastSyncDate IS NULL THEN 0 ELSE 1 END ASC, s.lastSyncDate ASC")
  List<String> findDue(@Param("activeSince")
  Date activeSince, @Param("activeDueBefore")
  Date activeDueBefore, @Param("inactiveDueBefore")
  Date inactiveDueBefore, @Param("staleBefore")
  Date staleBefore, Pageable pageable);

  /**
   * How many mailboxes are due right now: the backlog the status line shows.
   *
   * @param activeSince as for {@link #findDue}
   * @param activeDueBefore as for {@link #findDue}
   * @param inactiveDueBefore as for {@link #findDue}
   * @param staleBefore as for {@link #findDue}
   * @return the due count
   */
  @Query("SELECT COUNT(s) FROM EmailSyncStateEntity s WHERE " + DUE_PREDICATE)
  long countDue(@Param("activeSince")
  Date activeSince, @Param("activeDueBefore")
  Date activeDueBefore, @Param("inactiveDueBefore")
  Date inactiveDueBefore, @Param("staleBefore")
  Date staleBefore);

  /**
   * The oldest last sync among the due mailboxes, a never-synchronized row counting
   * from its creation: what "longest wait" means on the status line.
   *
   * @param activeSince as for {@link #findDue}
   * @param activeDueBefore as for {@link #findDue}
   * @param inactiveDueBefore as for {@link #findDue}
   * @param staleBefore as for {@link #findDue}
   * @return the oldest instant, or null when nothing is due
   */
  @Query("SELECT MIN(COALESCE(s.lastSyncDate, s.createdDate)) FROM EmailSyncStateEntity s WHERE " + DUE_PREDICATE)
  Date findOldestDue(@Param("activeSince")
  Date activeSince, @Param("activeDueBefore")
  Date activeDueBefore, @Param("inactiveDueBefore")
  Date inactiveDueBefore, @Param("staleBefore")
  Date staleBefore);

  /**
   * The claim: the conditional UPDATE that decides who synchronizes a mailbox.
   * Lands only on a row nobody holds, or whose holder's claim has gone stale, so of
   * every node (and every request thread) trying at once exactly one sees a row
   * count of one. The others see zero, and zero is an answer, not an error.
   *
   * @param userId the mailbox owner
   * @param now the claim's stamp, and the run's start
   * @param node the claiming node's identity
   * @param staleBefore a claim taken strictly before this instant may be taken over
   * @return one when the caller now holds the claim, zero when somebody else does
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailSyncStateEntity s SET s.syncStartedDate = :now, s.claimedBy = :node"
      + " WHERE s.userId = :userId AND (s.syncStartedDate IS NULL OR s.syncStartedDate < :staleBefore)")
  int claim(@Param("userId")
  String userId, @Param("now")
  Date now, @Param("node")
  String node, @Param("staleBefore")
  Date staleBefore);

  /**
   * The release, at the end of every run, success or failure: the claim cleared and
   * the cadence stamped with the run's START. Only by the node that holds the claim:
   * a run that outlived the stale timeout and finds its mailbox re-claimed by
   * another node must not clear that node's claim on its way out.
   *
   * @param userId the mailbox owner
   * @param node the releasing node's identity
   * @param startedAt the run's start, which becomes the last sync date
   * @return one when the claim was this node's and is now released, zero otherwise
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailSyncStateEntity s SET s.syncStartedDate = NULL, s.claimedBy = NULL, s.lastSyncDate = :startedAt"
      + " WHERE s.userId = :userId AND s.claimedBy = :node")
  int release(@Param("userId")
  String userId, @Param("node")
  String node, @Param("startedAt")
  Date startedAt);

  /**
   * A restarted node's recovery: every claim it held is cleared, the cadence left as
   * it was, so its half-done mailboxes are due again at the next tick rather than
   * in an hour.
   *
   * @param node the node's identity
   * @return how many claims were released
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailSyncStateEntity s SET s.syncStartedDate = NULL, s.claimedBy = NULL"
      + " WHERE s.claimedBy = :node AND s.syncStartedDate IS NOT NULL")
  int releaseClaimsOf(@Param("node")
  String node);

  /**
   * The activity stamp, throttled in SQL rather than in a JVM map so it is exact
   * under clustering: a stamp newer than {@code throttleBefore} is left alone, and
   * the statement costs one no-op UPDATE.
   *
   * @param userId the mailbox owner
   * @param now the stamp
   * @param throttleBefore a stamp at or after this instant is recent enough to keep
   * @return one when the stamp was written, zero when it was recent enough already
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailSyncStateEntity s SET s.lastActivityDate = :now"
      + " WHERE s.userId = :userId AND (s.lastActivityDate IS NULL OR s.lastActivityDate < :throttleBefore)")
  int touchActivity(@Param("userId")
  String userId, @Param("now")
  Date now, @Param("throttleBefore")
  Date throttleBefore);

  /**
   * The schedule columns of an existing row reset, and nothing else: the claim
   * columns are not named, so a row being synchronized at that very moment keeps
   * its claim.
   *
   * @param userId the mailbox owner
   * @param lastSyncDate the cadence to set, null for "due at once"
   * @param lastActivityDate the activity stamp to set
   * @return one when the row exists, zero otherwise
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailSyncStateEntity s SET s.lastSyncDate = :lastSyncDate, s.lastActivityDate = :lastActivityDate"
      + " WHERE s.userId = :userId")
  int resetSchedule(@Param("userId")
  String userId, @Param("lastSyncDate")
  Date lastSyncDate, @Param("lastActivityDate")
  Date lastActivityDate);

  /**
   * How many mailboxes are being synchronized right now, cluster-wide: the rows
   * with a live claim.
   *
   * @param staleBefore a claim taken strictly before this instant no longer counts
   * @return the live-claim count
   */
  @Query("SELECT COUNT(s) FROM EmailSyncStateEntity s WHERE s.syncStartedDate IS NOT NULL AND s.syncStartedDate >= :staleBefore")
  long countClaimed(@Param("staleBefore")
  Date staleBefore);
}
