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

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailThreadAiSummaryEntity;

public interface EmailThreadAiSummaryDAO extends JpaRepository<EmailThreadAiSummaryEntity, Long> {

  /**
   * The summary of one conversation, if one has been written.
   * <p>
   * Returns a list rather than a single row, the same shape and for the same reason
   * as {@link EmailBoxDAO#findByUserIdAndDraftLocalIdWithAttachments}: the pair is
   * unique in the database (index {@code UQ_EMAIL_THREAD_AI_SUMMARY}), and a query
   * should still not be the thing that throws when the unexpected happens — an
   * index created but not yet applied on an upgraded schema, or a vendor that let a
   * concurrent double-insert through. The caller takes the first.
   *
   * @param userId the mailbox owner
   * @param threadId the conversation id
   * @return the matching rows, normally exactly one, never null
   */
  @Query("SELECT summary FROM EmailThreadAiSummaryEntity summary WHERE summary.userId = :userId AND summary.threadId = :threadId")
  List<EmailThreadAiSummaryEntity> findByUserIdAndThreadId(@Param("userId")
  String userId, @Param("threadId")
  String threadId);

  /**
   * Drops every summary of a mailbox. Used wherever the mail those summaries
   * describe is itself being thrown away — a mailbox disconnected or rebound, a
   * cache reset — because a summary of mail that is gone describes nothing, and the
   * thread ids it is keyed by are re-minted by the resync that follows.
   *
   * @param userId the mailbox owner
   */
  @Transactional
  @Modifying
  @Query("DELETE FROM EmailThreadAiSummaryEntity summary WHERE summary.userId = :userId")
  void deleteByUserId(@Param("userId")
  String userId);

  /**
   * Drops the summaries of named conversations.
   * <p>
   * This is what a thread MERGE calls. Two conversations found to be one are
   * collapsed onto the oldest of their ids, and every row that moves takes its
   * messages with it — so a summary left behind under a merged-away id would be
   * unreachable at best, and at worst would be served for a conversation that has
   * since gained everything the other one held.
   *
   * @param userId the mailbox owner
   * @param threadIds the conversation ids whose summaries must go
   */
  @Transactional
  @Modifying
  @Query("DELETE FROM EmailThreadAiSummaryEntity summary WHERE summary.userId = :userId AND summary.threadId IN :threadIds")
  void deleteByUserIdAndThreadIds(@Param("userId")
  String userId, @Param("threadIds")
  List<String> threadIds);
}
