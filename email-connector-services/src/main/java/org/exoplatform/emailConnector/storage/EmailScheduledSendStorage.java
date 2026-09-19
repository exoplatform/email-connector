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

import java.util.Collection;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailScheduledSendDAO;
import org.exoplatform.emailConnector.entity.EmailScheduledSendEntity;
import org.exoplatform.emailConnector.model.EmailScheduledSend;
import org.exoplatform.emailConnector.model.ScheduledSendError;
import org.exoplatform.emailConnector.model.ScheduledSendStatus;

/**
 * The schedule table's persistence: entity to {@link EmailScheduledSend} and back,
 * and one narrow method per transition, each a single conditional statement of
 * {@link EmailScheduledSendDAO}. Nothing here decides anything: a row count comes
 * back as a boolean (or a count) and the service draws the conclusion. No cache: the
 * rows are the cluster's coordination state, and a cached copy of a claim is a claim
 * nobody holds.
 */
@Component
public class EmailScheduledSendStorage {

  /** The statuses the owner never sees listed nor counted against the limit. */
  public static final Set<ScheduledSendStatus> NOT_LISTED        = Set.of(ScheduledSendStatus.SENT);

  /** The statuses that need the owner: the view's warning. */
  public static final Set<ScheduledSendStatus> ATTENTION         = Set.of(ScheduledSendStatus.FAILED,
                                                                          ScheduledSendStatus.UNCERTAIN);

  /** The statuses the owner's "send now" or "retry" may start from. */
  public static final Set<ScheduledSendStatus> SENDABLE_NOW      = Set.of(ScheduledSendStatus.SCHEDULED,
                                                                          ScheduledSendStatus.FAILED,
                                                                          ScheduledSendStatus.UNCERTAIN);

  /** The statuses a new date may be given in. */
  public static final Set<ScheduledSendStatus> RESCHEDULABLE     = Set.of(ScheduledSendStatus.SCHEDULED,
                                                                          ScheduledSendStatus.FAILED);

  /** The statuses a cancel may not remove: the mail is going, or gone. */
  public static final Set<ScheduledSendStatus> NOT_CANCELLABLE   = Set.of(ScheduledSendStatus.SENDING,
                                                                          ScheduledSendStatus.SENT);

  // An id no row has: the NOT IN of the recovery statements must never be given an
  // empty list, which some vendors reject as SQL.
  private static final List<Long>              NO_ROW            = List.of(-1L);

  @Autowired
  private EmailScheduledSendDAO                emailScheduledSendDAO;

  /**
   * The database clock.
   *
   * @return the database's current timestamp
   */
  public Date currentTimestamp() {
    return emailScheduledSendDAO.currentTimestamp();
  }

  /**
   * Inserts the schedule of a draft.
   *
   * @param scheduledSend the row to insert, without id
   * @return the row as stored, with its id
   */
  public EmailScheduledSend create(EmailScheduledSend scheduledSend) {
    EmailScheduledSendEntity entity = toEntity(scheduledSend);
    entity.setId(null);
    return fromEntity(emailScheduledSendDAO.saveAndFlush(entity));
  }

  /**
   * One row by id.
   *
   * @param id the row id
   * @return the row, or null
   */
  public EmailScheduledSend get(long id) {
    return emailScheduledSendDAO.findById(id).map(this::fromEntity).orElse(null);
  }

  /**
   * The schedule of one draft of one user.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the draft's handle
   * @return the row, or null when the draft is not scheduled
   */
  public EmailScheduledSend get(String userId, String draftLocalId) {
    return emailScheduledSendDAO.findByUserIdAndDraftLocalId(userId, draftLocalId).map(this::fromEntity).orElse(null);
  }

  /**
   * The schedules of some of a user's drafts, keyed by draft handle, in one read.
   *
   * @param userId the mailbox owner
   * @param draftLocalIds the drafts' handles
   * @return the scheduled ones, by handle; empty when none is
   */
  public Map<String, EmailScheduledSend> getByDraftLocalIds(String userId, Collection<String> draftLocalIds) {
    if (draftLocalIds == null || draftLocalIds.isEmpty()) {
      return Map.of();
    }
    Map<String, EmailScheduledSend> byDraft = new HashMap<>();
    for (EmailScheduledSendEntity entity : emailScheduledSendDAO.findByUserIdAndDraftLocalIds(userId, draftLocalIds)) {
      byDraft.put(entity.getDraftLocalId(), fromEntity(entity));
    }
    return byDraft;
  }

  /**
   * Whether a draft has a schedule row, in any state: what locks it against edits.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the draft's handle
   * @return true when it has one
   */
  public boolean isScheduled(String userId, String draftLocalId) {
    return emailScheduledSendDAO.findByUserIdAndDraftLocalId(userId, draftLocalId).isPresent();
  }

  /**
   * Removes one row by id: the compensation of a scheduling that could not complete.
   *
   * @param id the row id
   */
  public void delete(long id) {
    emailScheduledSendDAO.deleteById(id);
  }

  /**
   * How many of a user's mails count against the limit: every listed one.
   *
   * @param userId the mailbox owner
   * @return the count
   */
  public long countListed(String userId) {
    return emailScheduledSendDAO.countByUserIdExcluding(userId, NOT_LISTED);
  }

  /**
   * The "Scheduled" view's badge: the listed count and how many need the owner.
   *
   * @param userId the mailbox owner
   * @return two numbers: listed, attention
   */
  public long[] countListedAndAttention(String userId) {
    List<Object[]> rows = emailScheduledSendDAO.countListedAndAttention(userId, NOT_LISTED, ATTENTION);
    if (rows.isEmpty() || rows.get(0) == null) {
      return new long[] { 0, 0 };
    }
    Object[] row = rows.get(0);
    return new long[] { row[0] == null ? 0 : ((Number) row[0]).longValue(), row[1] == null ? 0 : ((Number) row[1]).longValue() };
  }

  /**
   * A page of a user's listed schedule, soonest first.
   *
   * @param userId the mailbox owner
   * @param offset the first row
   * @param limit the page size, at least one
   * @return the rows
   */
  public List<EmailScheduledSend> getListed(String userId, int offset, int limit) {
    return emailScheduledSendDAO.findListed(userId, NOT_LISTED, PageRequest.of(offset / limit, limit))
                                .stream()
                                .map(this::fromEntity)
                                .toList();
  }

  /**
   * SCHEDULED rows due at an instant, the longest waiting first.
   *
   * @param now the reference instant
   * @param limit the bound; nothing is read below one
   * @return the due ids
   */
  public List<Long> findDueToSend(Date now, int limit) {
    if (limit < 1) {
      // A full pool asks for nothing; a page of size zero is refused by Spring Data.
      return List.of();
    }
    return emailScheduledSendDAO.findDueIds(ScheduledSendStatus.SCHEDULED, now, PageRequest.of(0, limit));
  }

  /**
   * UNCERTAIN rows whose Sent-folder check is due.
   *
   * @param now the reference instant
   * @param limit the bound; nothing is read below one
   * @return the due ids
   */
  public List<Long> findDueToCheck(Date now, int limit) {
    if (limit < 1) {
      return List.of();
    }
    return emailScheduledSendDAO.findDueIds(ScheduledSendStatus.UNCERTAIN, now, PageRequest.of(0, limit));
  }

  /**
   * SENT rows left behind before an instant.
   *
   * @param before rows updated strictly before this instant
   * @param limit the bound
   * @return the rows
   */
  public List<EmailScheduledSend> findSentBefore(Date before, int limit) {
    return emailScheduledSendDAO.findSentBefore(ScheduledSendStatus.SENT, before, PageRequest.of(0, limit))
                                .stream()
                                .map(this::fromEntity)
                                .toList();
  }

  /**
   * The dispatcher's claim on a due row.
   *
   * @param id the row id
   * @param node the claiming node
   * @param now the claim instant, a whole second
   * @return true when the caller now holds the claim
   */
  public boolean claim(long id, String node, Date now) {
    return emailScheduledSendDAO.claim(id, node, now, ScheduledSendStatus.SCHEDULED, ScheduledSendStatus.SENDING) == 1;
  }

  /**
   * The owner's "send now" claim.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the draft's handle
   * @param node the claiming node
   * @param now the claim instant, a whole second
   * @return true when the caller now holds the claim
   */
  public boolean claimNow(String userId, String draftLocalId, String node, Date now) {
    return emailScheduledSendDAO.claimNow(userId, draftLocalId, node, now, SENDABLE_NOW, ScheduledSendStatus.SENDING) == 1;
  }

  /**
   * A new date for a mail that is not being sent.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the draft's handle
   * @param scheduledDate the new instant
   * @param timeZone the zone it was chosen in
   * @param now the write instant
   * @return true when rescheduled
   */
  public boolean reschedule(String userId, String draftLocalId, Date scheduledDate, String timeZone, Date now) {
    return emailScheduledSendDAO.reschedule(userId,
                                            draftLocalId,
                                            scheduledDate,
                                            timeZone,
                                            now,
                                            RESCHEDULABLE,
                                            ScheduledSendStatus.SCHEDULED) == 1;
  }

  /**
   * Removes a schedule that is neither being sent nor sent.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the draft's handle
   * @return true when removed
   */
  public boolean cancel(String userId, String draftLocalId) {
    return emailScheduledSendDAO.cancel(userId, draftLocalId, NOT_CANCELLABLE) == 1;
  }

  /**
   * Records that a run's message was accepted by the mail server.
   *
   * @param id the row id
   * @param node the run's node
   * @param claimedDate the run's claim instant
   * @param now the write instant
   * @return true when recorded for this run
   */
  public boolean markSent(long id, String node, Date claimedDate, Date now) {
    return emailScheduledSendDAO.markSent(id, node, claimedDate, now, ScheduledSendStatus.SENDING, ScheduledSendStatus.SENT) == 1;
  }

  /**
   * Ends a run in the given state, with its error code and next step.
   *
   * @param id the row id
   * @param node the run's node
   * @param claimedDate the run's claim instant
   * @param status FAILED, UNCERTAIN, or SCHEDULED after a failure to connect
   * @param error the error code
   * @param nextAttemptDate the next step's instant, or null
   * @param now the write instant
   * @return true when recorded for this run
   */
  public boolean endRun(long id,
                        String node,
                        Date claimedDate,
                        ScheduledSendStatus status,
                        ScheduledSendError error,
                        Date nextAttemptDate,
                        Date now) {
    return emailScheduledSendDAO.endRun(id,
                                        node,
                                        claimedDate,
                                        status,
                                        error == null ? null : error.name(),
                                        nextAttemptDate,
                                        now,
                                        ScheduledSendStatus.SENDING) == 1;
  }

  /**
   * A restarted node's recovery of the sends it was running when it stopped.
   *
   * @param node this node
   * @param inFlight ids this node is sending right now
   * @param now the write instant
   * @return how many rows became UNCERTAIN
   */
  public int markUncertainOf(String node, Collection<Long> inFlight, Date now) {
    return emailScheduledSendDAO.markUncertainOf(node,
                                                 inFlight.isEmpty() ? NO_ROW : inFlight,
                                                 ScheduledSendError.INTERRUPTED.name(),
                                                 now,
                                                 ScheduledSendStatus.SENDING,
                                                 ScheduledSendStatus.UNCERTAIN);
  }

  /**
   * Any node's recovery of claims older than a send can take.
   *
   * @param staleBefore claims taken strictly before this instant
   * @param inFlight ids this node is sending right now
   * @param now the write instant
   * @return how many rows became UNCERTAIN
   */
  public int markStaleUncertain(Date staleBefore, Collection<Long> inFlight, Date now) {
    return emailScheduledSendDAO.markStaleUncertain(staleBefore,
                                                    inFlight.isEmpty() ? NO_ROW : inFlight,
                                                    ScheduledSendError.INTERRUPTED.name(),
                                                    now,
                                                    ScheduledSendStatus.SENDING,
                                                    ScheduledSendStatus.UNCERTAIN);
  }

  /**
   * Claims the Sent-folder check of an UNCERTAIN row.
   *
   * @param id the row id
   * @param node the checking node
   * @param now the claim instant, a whole second
   * @return true when the caller owns the check
   */
  public boolean claimCheck(long id, String node, Date now) {
    return emailScheduledSendDAO.claimCheck(id, node, now, ScheduledSendStatus.UNCERTAIN) == 1;
  }

  /**
   * Records that a check found the message sent.
   *
   * @param id the row id
   * @param node the checking node
   * @param claimedDate the check's claim instant
   * @param now the write instant
   * @return true when recorded
   */
  public boolean markCheckedSent(long id, String node, Date claimedDate, Date now) {
    return emailScheduledSendDAO.markCheckedSent(id,
                                                 node,
                                                 claimedDate,
                                                 now,
                                                 ScheduledSendStatus.UNCERTAIN,
                                                 ScheduledSendStatus.SENT) == 1;
  }

  /**
   * Entity to DTO.
   *
   * @param entity the entity
   * @return the DTO
   */
  private EmailScheduledSend fromEntity(EmailScheduledSendEntity entity) {
    return new EmailScheduledSend(entity.getId(),
                                  entity.getEmailId(),
                                  entity.getUserId(),
                                  entity.getDraftLocalId(),
                                  entity.getScheduledDate(),
                                  entity.getTimeZone(),
                                  entity.getStatus(),
                                  entity.getNextAttemptDate(),
                                  entity.getAttempts(),
                                  entity.getClaimedBy(),
                                  entity.getClaimedDate(),
                                  entity.getLastError(),
                                  entity.getCreatedDate(),
                                  entity.getUpdatedDate());
  }

  /**
   * DTO to entity.
   *
   * @param scheduledSend the DTO
   * @return the entity
   */
  private EmailScheduledSendEntity toEntity(EmailScheduledSend scheduledSend) {
    return new EmailScheduledSendEntity(scheduledSend.getId(),
                                        scheduledSend.getEmailId(),
                                        scheduledSend.getUserId(),
                                        scheduledSend.getDraftLocalId(),
                                        scheduledSend.getScheduledDate(),
                                        scheduledSend.getTimeZone(),
                                        scheduledSend.getStatus(),
                                        scheduledSend.getNextAttemptDate(),
                                        scheduledSend.getAttempts(),
                                        scheduledSend.getClaimedBy(),
                                        scheduledSend.getClaimedDate(),
                                        scheduledSend.getLastError(),
                                        scheduledSend.getCreatedDate(),
                                        scheduledSend.getUpdatedDate());
  }
}
