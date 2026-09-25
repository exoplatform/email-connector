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

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailFilterMatchEntity;

/**
 * The matches of eXo rules (changeset 1.0.0-74). Inserted through {@code saveAndFlush},
 * whose refusal by the unique key (USER_ID, FILTER_ID, MAIL_HEADER_HASH) is the
 * at-most-once decision; every read is scoped to the owner.
 */
public interface EmailFilterMatchDAO extends JpaRepository<EmailFilterMatchEntity, Long> {

  /**
   * One match of its owner.
   *
   * @param id the match's id
   * @param userId the owner
   * @return the match, or nothing when it is not this user's
   */
  @Query("SELECT fm FROM EmailFilterMatchEntity fm WHERE fm.id = :id AND fm.userId = :userId")
  List<EmailFilterMatchEntity> findByIdAndUserId(@Param("id")
  long id, @Param("userId")
  String userId);

  /**
   * The matches of one rule on some mails: which of them the rule already handled.
   *
   * @param userId the owner
   * @param filterId the rule
   * @param mailHeaderHashes the mails' Message-ID hashes, not empty
   * @return the matches found
   */
  @Query("SELECT fm FROM EmailFilterMatchEntity fm WHERE fm.userId = :userId AND fm.filterId = :filterId"
      + " AND fm.mailHeaderHash IN :mailHeaderHashes")
  List<EmailFilterMatchEntity> findByFilterAndMails(@Param("userId")
  String userId, @Param("filterId")
  long filterId, @Param("mailHeaderHashes")
  Collection<String> mailHeaderHashes);

  /**
   * The matches on one mail, whatever rule: the mail's Automations panel.
   *
   * @param userId the owner
   * @param mailHeaderHash the mail's Message-ID hash
   * @return the matches, newest first
   */
  @Query("SELECT fm FROM EmailFilterMatchEntity fm WHERE fm.userId = :userId AND fm.mailHeaderHash = :mailHeaderHash"
      + " ORDER BY fm.matchedDate DESC, fm.id DESC")
  List<EmailFilterMatchEntity> findByMail(@Param("userId")
  String userId, @Param("mailHeaderHash")
  String mailHeaderHash);

  /**
   * The log of one rule, newest first.
   *
   * @param userId the owner
   * @param filterId the rule
   * @param pageable the page, the log shows the newest hundred
   * @return the matches
   */
  @Query("SELECT fm FROM EmailFilterMatchEntity fm WHERE fm.userId = :userId AND fm.filterId = :filterId"
      + " ORDER BY fm.matchedDate DESC, fm.id DESC")
  List<EmailFilterMatchEntity> findByFilter(@Param("userId")
  String userId, @Param("filterId")
  long filterId, Pageable pageable);

  /**
   * A user's matches waiting for the assistant, oldest first: the queue handler's read.
   *
   * @param userId the owner
   * @param agentStatus the status, {@code PENDING}
   * @param pageable how many
   * @return the matches
   */
  @Query("SELECT fm FROM EmailFilterMatchEntity fm WHERE fm.userId = :userId AND fm.agentStatus = :agentStatus"
      + " ORDER BY fm.matchedDate ASC, fm.id ASC")
  List<EmailFilterMatchEntity> findByAgentStatus(@Param("userId")
  String userId, @Param("agentStatus")
  String agentStatus, Pageable pageable);

  /**
   * How many of a user's matches are in an assistant status: the pending cap.
   *
   * @param userId the owner
   * @param agentStatus the status
   * @return the count
   */
  @Query("SELECT COUNT(fm) FROM EmailFilterMatchEntity fm WHERE fm.userId = :userId AND fm.agentStatus = :agentStatus")
  long countByAgentStatus(@Param("userId")
  String userId, @Param("agentStatus")
  String agentStatus);

  /**
   * How many assistant runs a user's matches were queued for since a date: the daily
   * cap. A match counts from the moment it is queued, whatever became of the run.
   *
   * @param userId the owner
   * @param statuses the statuses of a queued run
   * @param since the start of the day
   * @return the count
   */
  @Query("SELECT COUNT(fm) FROM EmailFilterMatchEntity fm WHERE fm.userId = :userId AND fm.agentStatus IN :statuses"
      + " AND fm.matchedDate >= :since")
  long countQueuedSince(@Param("userId")
  String userId, @Param("statuses")
  Collection<String> statuses, @Param("since")
  Date since);

  /**
   * Deletes a user's matches older than a date: the log's retention.
   *
   * @param userId the owner
   * @param before the oldest date kept
   * @return how many were deleted
   */
  @Transactional
  @Modifying
  @Query("DELETE FROM EmailFilterMatchEntity fm WHERE fm.userId = :userId AND fm.matchedDate < :before")
  int deleteOlderThan(@Param("userId")
  String userId, @Param("before")
  Date before);
}
