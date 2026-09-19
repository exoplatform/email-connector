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
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailReadReceiptAnswerEntity;

/**
 * The durable read-receipt answers (EXO-90435). Inserted through {@code saveAndFlush},
 * whose refusal by the unique index (USER_ID, MESSAGE_ID_HASH) is the at-most-once
 * decision; read by user and hashes; deleted only to give back a claim whose receipt
 * could not leave.
 */
public interface EmailReadReceiptAnswerDAO extends JpaRepository<EmailReadReceiptAnswerEntity, Long> {

  /**
   * The answers a user gave to the messages of the given Message-ID hashes. The list is
   * bounded by the caller -- the messages of one page, or of one folder window.
   *
   * @param userId the user
   * @param messageIdHashes the hashes, not empty
   * @return the answers found, at most one per hash
   */
  @Query("SELECT answer FROM EmailReadReceiptAnswerEntity answer WHERE answer.userId = :userId AND answer.messageIdHash IN :messageIdHashes")
  List<EmailReadReceiptAnswerEntity> findByUserIdAndMessageIdHashes(@Param("userId")
  String userId, @Param("messageIdHashes")
  Collection<String> messageIdHashes);

  /**
   * Deletes one answer of a user, by its id: the claim the caller took and gives back.
   * Addressed by id, so an answer anybody else recorded is never touched.
   *
   * @param userId the user
   * @param id the answer's id
   * @return 1 when deleted, 0 when there was none
   */
  @Transactional
  @Modifying
  @Query("DELETE FROM EmailReadReceiptAnswerEntity answer WHERE answer.userId = :userId AND answer.id = :id")
  int deleteByUserIdAndId(@Param("userId")
  String userId, @Param("id")
  long id);
}
