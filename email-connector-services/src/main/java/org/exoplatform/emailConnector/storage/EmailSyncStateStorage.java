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
package org.exoplatform.emailConnector.storage;

import java.util.Date;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailSyncStateDAO;
import org.exoplatform.emailConnector.entity.EmailSyncStateEntity;
import org.exoplatform.emailConnector.model.EmailSyncState;

/**
 * The sync-state table's persistence: entity to {@link EmailSyncState} and back,
 * and one narrow method per writer, each a single statement in
 * {@link EmailSyncStateDAO} that names the columns that writer owns. Nothing here
 * decides anything; the claim's row count is handed back as a boolean and the
 * service draws the conclusion.
 */
@Component
public class EmailSyncStateStorage {

  @Autowired
  private EmailSyncStateDAO emailSyncStateDAO;

  /**
   * One mailbox's state.
   *
   * @param userId the mailbox owner
   * @return the state, or null when the mailbox has no row
   */
  public EmailSyncState get(String userId) {
    return emailSyncStateDAO.findById(userId).map(this::fromEntity).orElse(null);
  }

  /**
   * Creates the row of a mailbox, or resets the schedule columns of the row it
   * already has. Either way the claim columns are untouched: a mailbox being
   * synchronized at this very moment keeps its claim, and the release at the end of
   * that run stamps the cadence over what is set here.
   * <p>
   * Two callers can race to create the same row (the connect listener and the boot
   * reconciliation, say); the primary key refuses the second, which then falls back
   * to the update it would have done had it read the row a moment later.
   *
   * @param userId the mailbox owner
   * @param lastSyncDate the cadence, null for "due at once"
   * @param lastActivityDate the activity stamp, null for "unknown"
   */
  public void upsert(String userId, Date lastSyncDate, Date lastActivityDate) {
    if (emailSyncStateDAO.existsById(userId)) {
      emailSyncStateDAO.resetSchedule(userId, lastSyncDate, lastActivityDate);
      return;
    }
    try {
      emailSyncStateDAO.saveAndFlush(new EmailSyncStateEntity(userId, null, null, lastSyncDate, lastActivityDate, new Date()));
    } catch (DataIntegrityViolationException e) {
      emailSyncStateDAO.resetSchedule(userId, lastSyncDate, lastActivityDate);
    }
  }

  /**
   * Drops a mailbox's row.
   *
   * @param userId the mailbox owner
   */
  public void delete(String userId) {
    if (emailSyncStateDAO.existsById(userId)) {
      emailSyncStateDAO.deleteById(userId);
    }
  }

  /**
   * Takes the claim on a mailbox, if nobody holds a live one.
   *
   * @param userId the mailbox owner
   * @param now the claim's stamp
   * @param node the claiming node's identity
   * @param staleBefore a claim taken strictly before this instant may be taken over
   * @return true when the caller now holds the claim
   */
  public boolean claim(String userId, Date now, String node, Date staleBefore) {
    return emailSyncStateDAO.claim(userId, now, node, staleBefore) == 1;
  }

  /**
   * Releases a node's claim on a mailbox and stamps the cadence with the run's
   * start.
   *
   * @param userId the mailbox owner
   * @param node the releasing node's identity
   * @param startedAt the run's start
   * @return true when the claim was this node's and is now released
   */
  public boolean release(String userId, String node, Date startedAt) {
    return emailSyncStateDAO.release(userId, node, startedAt) == 1;
  }

  /**
   * Releases every claim a node holds, the cadence left as it was.
   *
   * @param node the node's identity
   * @return how many claims were released
   */
  public int releaseClaimsOf(String node) {
    return emailSyncStateDAO.releaseClaimsOf(node);
  }

  /**
   * Stamps a mailbox's activity, unless the stamp it has is recent enough.
   *
   * @param userId the mailbox owner
   * @param now the stamp
   * @param throttleBefore a stamp at or after this instant is kept as is
   * @return true when the stamp was written
   */
  public boolean touchActivity(String userId, Date now, Date throttleBefore) {
    return emailSyncStateDAO.touchActivity(userId, now, throttleBefore) == 1;
  }

  /**
   * The mailboxes due for a synchronization, oldest last sync first, at most
   * {@code limit} of them.
   *
   * @param activeSince an activity stamp at or after this instant makes the owner active
   * @param activeDueBefore an active mailbox last synchronized strictly before this is due
   * @param inactiveDueBefore an inactive mailbox last synchronized strictly before this is due
   * @param staleBefore a claim taken strictly before this instant no longer counts
   * @param limit how many at most
   * @return the due user ids, never null
   */
  public List<String> findDue(Date activeSince, Date activeDueBefore, Date inactiveDueBefore, Date staleBefore, int limit) {
    return emailSyncStateDAO.findDue(activeSince, activeDueBefore, inactiveDueBefore, staleBefore, PageRequest.of(0, limit));
  }

  /**
   * How many mailboxes are due right now.
   *
   * @param activeSince as for {@link #findDue}
   * @param activeDueBefore as for {@link #findDue}
   * @param inactiveDueBefore as for {@link #findDue}
   * @param staleBefore as for {@link #findDue}
   * @return the due count
   */
  public long countDue(Date activeSince, Date activeDueBefore, Date inactiveDueBefore, Date staleBefore) {
    return emailSyncStateDAO.countDue(activeSince, activeDueBefore, inactiveDueBefore, staleBefore);
  }

  /**
   * The oldest last sync (or creation, for a never-synchronized row) among the due
   * mailboxes.
   *
   * @param activeSince as for {@link #findDue}
   * @param activeDueBefore as for {@link #findDue}
   * @param inactiveDueBefore as for {@link #findDue}
   * @param staleBefore as for {@link #findDue}
   * @return the oldest instant, or null when nothing is due
   */
  public Date findOldestDue(Date activeSince, Date activeDueBefore, Date inactiveDueBefore, Date staleBefore) {
    return emailSyncStateDAO.findOldestDue(activeSince, activeDueBefore, inactiveDueBefore, staleBefore);
  }

  /**
   * How many mailboxes hold a live claim, cluster-wide.
   *
   * @param staleBefore a claim taken strictly before this instant no longer counts
   * @return the live-claim count
   */
  public long countClaimed(Date staleBefore) {
    return emailSyncStateDAO.countClaimed(staleBefore);
  }

  /**
   * How many mailboxes have a row: the connected mailboxes the dispatcher knows.
   *
   * @return the row count
   */
  public long count() {
    return emailSyncStateDAO.count();
  }

  /**
   * Entity to DTO.
   *
   * @param entity the row
   * @return the DTO
   */
  private EmailSyncState fromEntity(EmailSyncStateEntity entity) {
    return new EmailSyncState(entity.getUserId(),
                              entity.getSyncStartedDate(),
                              entity.getClaimedBy(),
                              entity.getLastSyncDate(),
                              entity.getLastActivityDate(),
                              entity.getCreatedDate());
  }
}
