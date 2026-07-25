/**
 * Copyright (C) 2025 eXo Platform SAS
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

import org.exoplatform.emailConnector.entity.EmailBoxEntity;

public interface EmailBoxDAO extends JpaRepository<EmailBoxEntity, Long> {

  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId ORDER BY email.receivedDate DESC")
  List<EmailBoxEntity> findByUserIdWithAttachments(@Param("userId")
  String userId);

  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId AND email.mailRemoteId = :mailRemoteId ORDER BY email.receivedDate DESC")
  EmailBoxEntity findByMailRemoteIdAndUserId(@Param("mailRemoteId")
  long mailRemoteId, @Param("userId")
  String userId);

  void deleteByUserId(String userId);

  @Transactional
  @Modifying
  @Query("DELETE FROM EmailBoxEntity email WHERE email.id IN :ids")
  void deleteEmailsByIds(@Param("ids")
  List<Long> ids);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.read = :readStatus WHERE email.mailRemoteId IN :mailRemoteIds AND email.userId = :userId AND (email.read IS NULL OR email.read <> :readStatus)")
  void updateReadStatusByMailRemoteIds(@Param("mailRemoteIds")
  List<Long> mailRemoteIds, @Param("userId")
  String userId, @Param("readStatus")
  boolean readStatus);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.recent = false WHERE email.mailRemoteId = :mailRemoteId AND email.userId = :userId")
  void markEmailAsNotRecent(@Param("mailRemoteId")
  Long mailRemoteId, @Param("userId")
  String userId);

  @Query("SELECT email FROM EmailBoxEntity email WHERE email.userId = :userId AND email.mailHeaderId = :mailHeaderId ORDER BY email.receivedDate DESC")
  List<EmailBoxEntity> findByMailHeaderIdAndUserId(@Param("mailHeaderId")
  String mailHeaderId, @Param("userId")
  String userId);

  @Query("SELECT DISTINCT email.threadId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.mailHeaderId IN :mailHeaderIds AND email.threadId IS NOT NULL")
  List<String> findDistinctThreadIdsByMailHeaderIds(@Param("userId")
  String userId, @Param("mailHeaderIds")
  List<String> mailHeaderIds);

  @Query("SELECT email.threadId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.threadId IN :threadIds ORDER BY email.receivedDate ASC")
  List<String> findThreadIdsOrderedByAge(@Param("userId")
  String userId, @Param("threadIds")
  List<String> threadIds);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.threadId = :canonicalThreadId WHERE email.userId = :userId AND email.threadId IN :threadIds")
  void mergeThreads(@Param("userId")
  String userId, @Param("canonicalThreadId")
  String canonicalThreadId, @Param("threadIds")
  List<String> threadIds);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.threadId = :threadId, email.inReplyTo = :inReplyTo, email.mailReferences = :mailReferences WHERE email.userId = :userId AND email.mailRemoteId = :mailRemoteId")
  void updateThreadInfo(@Param("userId")
  String userId, @Param("mailRemoteId")
  Long mailRemoteId, @Param("threadId")
  String threadId, @Param("inReplyTo")
  String inReplyTo, @Param("mailReferences")
  String mailReferences);

}
