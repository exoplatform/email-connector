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

import java.util.Collection;
import java.util.Date;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailFilterProposalEntity;

/**
 * The tool calls mail filters' assistants recorded instead of running them (changeset
 * 1.0.0-95). Every read and write is scoped to the owner; a status only moves by a
 * conditional UPDATE on the status it is expected to be in, so that two clicks, two tabs
 * or two nodes never both win it.
 */
public interface EmailFilterProposalDAO extends JpaRepository<EmailFilterProposalEntity, Long> {

  /**
   * One proposal of its owner.
   *
   * @param id the proposal
   * @param userId the owner
   * @return the proposal, or nothing when it is not this user's
   */
  @Query("SELECT p FROM EmailFilterProposalEntity p WHERE p.id = :id AND p.userId = :userId")
  List<EmailFilterProposalEntity> findByIdAndUserId(@Param("id")
  long id, @Param("userId")
  String userId);

  /**
   * The proposal of one match for one call: what the unique key holds.
   *
   * @param userId the owner
   * @param matchId the match
   * @param callHash the call's hash
   * @return the proposal, or nothing
   */
  @Query("SELECT p FROM EmailFilterProposalEntity p WHERE p.userId = :userId AND p.matchId = :matchId AND p.callHash = :callHash")
  List<EmailFilterProposalEntity> findByCall(@Param("userId")
  String userId, @Param("matchId")
  long matchId, @Param("callHash")
  String callHash);

  /**
   * The proposals of some matches of their owner: the cards of a mail's panel.
   *
   * @param userId the owner
   * @param matchIds the matches, not empty
   * @return the proposals, oldest first
   */
  @Query("SELECT p FROM EmailFilterProposalEntity p WHERE p.userId = :userId AND p.matchId IN :matchIds"
      + " ORDER BY p.createdDate ASC, p.id ASC")
  List<EmailFilterProposalEntity> findByMatches(@Param("userId")
  String userId, @Param("matchIds")
  Collection<Long> matchIds);

  /**
   * How many calls one run of a match's assistant recorded: the per-mail cap.
   *
   * @param userId the owner
   * @param matchId the match
   * @param conversationId the run's conversation
   * @return the count
   */
  @Query("SELECT COUNT(p) FROM EmailFilterProposalEntity p WHERE p.userId = :userId AND p.matchId = :matchId"
      + " AND p.conversationId = :conversationId")
  long countByRun(@Param("userId")
  String userId, @Param("matchId")
  long matchId, @Param("conversationId")
  String conversationId);

  /**
   * How many of a user's proposals are in a status: the pending cap of the mailbox.
   *
   * @param userId the owner
   * @param status the status, {@code PROPOSED}
   * @return the count
   */
  @Query("SELECT COUNT(p) FROM EmailFilterProposalEntity p WHERE p.userId = :userId AND p.status = :status")
  long countByStatus(@Param("userId")
  String userId, @Param("status")
  String status);

  /**
   * Moves a proposal of its owner from a status to another, only if it is still in the
   * first and, when {@code notExpiredAt} is given, not past its expiry at that time: the
   * claim that makes one click, and one only, act on it.
   *
   * @param id the proposal
   * @param userId the owner
   * @param from the status it must be in
   * @param to the status it is moved to
   * @param decidedDate the decision's date
   * @param notExpiredAt the time it must not have expired at
   * @return 1 when moved, 0 otherwise
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE EmailFilterProposalEntity p SET p.status = :to, p.decidedDate = :decidedDate"
      + " WHERE p.id = :id AND p.userId = :userId AND p.status = :from AND p.expiresDate > :notExpiredAt")
  int claim(@Param("id")
  long id, @Param("userId")
  String userId, @Param("from")
  String from, @Param("to")
  String to, @Param("decidedDate")
  Date decidedDate, @Param("notExpiredAt")
  Date notExpiredAt);

  /**
   * Records how an approved call ended, only if it is still running.
   *
   * @param id the proposal
   * @param userId the owner
   * @param running the running status
   * @param to {@code DONE} or {@code FAILED}
   * @param result what the tool answered, or null
   * @param lastError why it failed, or null
   * @return 1 when written, 0 otherwise
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE EmailFilterProposalEntity p SET p.status = :to, p.result = :result, p.lastError = :lastError"
      + " WHERE p.id = :id AND p.userId = :userId AND p.status = :running")
  int finish(@Param("id")
  long id, @Param("userId")
  String userId, @Param("running")
  String running, @Param("to")
  String to, @Param("result")
  String result, @Param("lastError")
  String lastError);

  /**
   * Writes the assistant's reason on a proposal of its owner's match, only while it still
   * waits: never a write of the whole row, which could put back a status an approval
   * moved in between.
   *
   * @param id the proposal
   * @param userId the owner
   * @param matchId the match it must belong to
   * @param proposed the waiting status
   * @param rationale the reason
   * @return 1 when written, 0 otherwise
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE EmailFilterProposalEntity p SET p.rationale = :rationale"
      + " WHERE p.id = :id AND p.userId = :userId AND p.matchId = :matchId AND p.status = :proposed")
  int setRationale(@Param("id")
  long id, @Param("userId")
  String userId, @Param("matchId")
  long matchId, @Param("proposed")
  String proposed, @Param("rationale")
  String rationale);

  /**
   * Fails a user's approved calls still running since before a date: their node died,
   * or their call never came back.
   *
   * @param userId the owner
   * @param running the running status
   * @param failed the failed status
   * @param reason the reason recorded
   * @param before a call decided before this is abandoned
   * @return how many
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE EmailFilterProposalEntity p SET p.status = :failed, p.lastError = :reason"
      + " WHERE p.userId = :userId AND p.status = :running AND p.decidedDate < :before")
  int failStaleRunning(@Param("userId")
  String userId, @Param("running")
  String running, @Param("failed")
  String failed, @Param("reason")
  String reason, @Param("before")
  Date before);

  /**
   * Expires a user's proposals still waiting past their expiry.
   *
   * @param userId the owner
   * @param proposed the waiting status
   * @param expired the expired status
   * @param now the time
   * @return how many
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE EmailFilterProposalEntity p SET p.status = :expired, p.decidedDate = :now"
      + " WHERE p.userId = :userId AND p.status = :proposed AND p.expiresDate <= :now")
  int expireDue(@Param("userId")
  String userId, @Param("proposed")
  String proposed, @Param("expired")
  String expired, @Param("now")
  Date now);

  /**
   * Expires the proposals of one match still waiting, with a reason: a new run of its
   * assistant supersedes them.
   *
   * @param userId the owner
   * @param matchId the match
   * @param proposed the waiting status
   * @param expired the expired status
   * @param reason the reason recorded
   * @param now the time
   * @return how many
   */
  @Transactional
  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query("UPDATE EmailFilterProposalEntity p SET p.status = :expired, p.decidedDate = :now, p.lastError = :reason"
      + " WHERE p.userId = :userId AND p.matchId = :matchId AND p.status = :proposed")
  int expireOfMatch(@Param("userId")
  String userId, @Param("matchId")
  long matchId, @Param("proposed")
  String proposed, @Param("expired")
  String expired, @Param("reason")
  String reason, @Param("now")
  Date now);
}
