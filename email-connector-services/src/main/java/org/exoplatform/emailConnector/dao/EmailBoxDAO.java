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

  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId AND email.folder = :folder ORDER BY email.receivedDate DESC")
  List<EmailBoxEntity> findByUserIdAndFolderWithAttachments(@Param("userId")
  String userId, @Param("folder")
  String folder);

  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId AND email.threadId = :threadId ORDER BY email.receivedDate ASC")
  List<EmailBoxEntity> findByUserIdAndThreadIdWithAttachments(@Param("userId")
  String userId, @Param("threadId")
  String threadId);

  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId AND email.folder = :folder AND email.mailRemoteId = :mailRemoteId ORDER BY email.receivedDate DESC")
  EmailBoxEntity findByMailRemoteIdAndUserIdAndFolder(@Param("mailRemoteId")
  long mailRemoteId, @Param("userId")
  String userId, @Param("folder")
  String folder);

  void deleteByUserId(String userId);

  @Transactional
  @Modifying
  @Query("DELETE FROM EmailBoxEntity email WHERE email.id IN :ids")
  void deleteEmailsByIds(@Param("ids")
  List<Long> ids);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.read = :readStatus WHERE email.mailRemoteId IN :mailRemoteIds AND email.userId = :userId AND email.folder = :folder AND (email.read IS NULL OR email.read <> :readStatus)")
  void updateReadStatusByMailRemoteIds(@Param("mailRemoteIds")
  List<Long> mailRemoteIds, @Param("userId")
  String userId, @Param("readStatus")
  boolean readStatus, @Param("folder")
  String folder);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.recent = false WHERE email.mailRemoteId = :mailRemoteId AND email.userId = :userId AND email.folder = :folder")
  void markEmailAsNotRecent(@Param("mailRemoteId")
  Long mailRemoteId, @Param("userId")
  String userId, @Param("folder")
  String folder);

  /**
   * Bulk companion of {@link #markEmailAsNotRecent}: clears the recent flag of all
   * the given messages in ONE statement. The sync used to issue the single-row
   * version once per already-known message — 5000 statements per routine sync on a
   * 5000-message cache, almost all of them writing nothing. The caller passes only
   * the rows whose flag is actually set, so a steady-state sync issues no statement
   * at all.
   *
   * @param mailRemoteIds the IMAP UIDs whose recent flag must be cleared
   * @param userId the mailbox owner
   * @param folder the folder discriminator scoping the UIDs
   */
  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.recent = false WHERE email.mailRemoteId IN :mailRemoteIds AND email.userId = :userId AND email.folder = :folder")
  void markEmailsAsNotRecent(@Param("mailRemoteIds")
  List<Long> mailRemoteIds, @Param("userId")
  String userId, @Param("folder")
  String folder);

  /**
   * The light per-folder view the sync reconcile runs on: row id, IMAP UID,
   * threading state and the two flags — WITHOUT the body CLOB and without the
   * attachments join. The sync used to load the full entities through
   * {@link #findByUserIdAndFolderWithAttachments}: at 5000 cached messages that
   * dragged 5000 bodies out of the database every routine sync just to compare
   * UIDs and flags. Ordered newest-first because cleanupObsoleteEmails trims the
   * cache overflow off the END of the list, exactly as the full query did.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator
   * @return rows of {@code [id, mailRemoteId, threadId, threadIndexRoot, read, recent]},
   *         newest first
   */
  @Query("SELECT email.id, email.mailRemoteId, email.threadId, email.threadIndexRoot, email.read, email.recent FROM EmailBoxEntity email WHERE email.userId = :userId AND email.folder = :folder ORDER BY email.receivedDate DESC")
  List<Object[]> findSyncViewByUserIdAndFolder(@Param("userId")
  String userId, @Param("folder")
  String folder);

  @Query("SELECT email FROM EmailBoxEntity email WHERE email.userId = :userId AND email.mailHeaderId = :mailHeaderId ORDER BY email.receivedDate DESC")
  List<EmailBoxEntity> findByMailHeaderIdAndUserId(@Param("mailHeaderId")
  String mailHeaderId, @Param("userId")
  String userId);

  @Query("SELECT DISTINCT email.threadId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.mailHeaderId IN :mailHeaderIds AND email.threadId IS NOT NULL")
  List<String> findDistinctThreadIdsByMailHeaderIds(@Param("userId")
  String userId, @Param("mailHeaderIds")
  List<String> mailHeaderIds);

  // Count DISTINCT messages per thread (by Message-ID), matching the reader which
  // shows the same message once even when it is cached in several folders (e.g. INBOX
  // and ALL_MAIL). A raw row count would over-report by the cross-folder duplicates.
  @Query("SELECT email.threadId, COUNT(DISTINCT email.mailHeaderId) FROM EmailBoxEntity email WHERE email.userId = :userId AND email.threadId IS NOT NULL AND email.mailHeaderId IS NOT NULL GROUP BY email.threadId")
  List<Object[]> countMessagesByThread(@Param("userId")
  String userId);

  // Per-folder message counts, so the list's folder switch only offers folders that
  // actually have mail (e.g. no empty Archive tab on Gmail, which has no \Archive).
  @Query("SELECT email.folder, COUNT(email.id) FROM EmailBoxEntity email WHERE email.userId = :userId GROUP BY email.folder")
  List<Object[]> countMessagesByFolder(@Param("userId")
  String userId);

  @Query("SELECT DISTINCT email.threadId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.threadIndexRoot = :threadIndexRoot AND email.threadId IS NOT NULL")
  List<String> findDistinctThreadIdsByThreadIndexRoot(@Param("userId")
  String userId, @Param("threadIndexRoot")
  String threadIndexRoot);

  /**
   * The reference chains of the user's cached messages: each row's thread id with
   * its raw {@code References} and {@code In-Reply-To} headers. This feeds the
   * REVERSE thread lookup (which already-cached messages point AT a given
   * Message-ID — see {@code EmailBoxStorage#getThreadIdsReferencingMessageId}),
   * needed when a message is cached after the replies that reference it: the sync
   * drains prefetch slices in completion order, newest mail first, so a parent
   * routinely lands last. The substring matching itself deliberately happens in
   * Java, NOT here: {@code MAIL_REFERENCES} maps to CLOB on some dialects (verified
   * live on HSQLDB), where SQL string functions are unsupported — a
   * {@code LOCATE}-based version of this query threw
   * {@code SQLFeatureNotSupportedException} on the first live reset and aborted the
   * whole sync. Equality and null checks are all a CLOB column can be trusted with
   * across dialects. The result is bounded by the per-user cache cap
   * (a few hundred rows), and the chain-presence predicate keeps chainless
   * messages — most of a typical inbox — out of the transfer entirely. The
   * empty-string guard keeps backfill-pending rows (threading columns added after
   * their creation) out of the merge machinery.
   *
   * @param userId the mailbox owner
   * @return rows of {@code [threadId, mailReferences, inReplyTo]} for every cached
   *         message that has a thread id and at least one chain header
   */
  @Query("SELECT email.threadId, email.mailReferences, email.inReplyTo FROM EmailBoxEntity email WHERE email.userId = :userId AND email.threadId IS NOT NULL AND email.threadId <> '' AND (email.mailReferences IS NOT NULL OR email.inReplyTo IS NOT NULL)")
  List<Object[]> findThreadReferenceChainsByUserId(@Param("userId")
  String userId);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.threadIndexRoot = :threadIndexRoot WHERE email.userId = :userId AND email.folder = :folder AND email.mailRemoteId = :mailRemoteId")
  void updateThreadIndexRoot(@Param("userId")
  String userId, @Param("mailRemoteId")
  Long mailRemoteId, @Param("folder")
  String folder, @Param("threadIndexRoot")
  String threadIndexRoot);

  @Query("SELECT email.threadId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.threadId IN :threadIds ORDER BY email.receivedDate ASC")
  List<String> findThreadIdsOrderedByAge(@Param("userId")
  String userId, @Param("threadIds")
  List<String> threadIds);

  /**
   * Of the given IMAP UIDs, the ones already cached in a folder — one IN query
   * for the whole search result list, so decorating hits with their "already
   * openable locally" flag costs a single statement rather than one lookup per
   * hit (the same per-row discipline the sync reconcile follows).
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator scoping the UIDs
   * @param mailRemoteIds the candidate IMAP UIDs
   * @return the subset of {@code mailRemoteIds} present in the local cache
   */
  @Query("SELECT email.mailRemoteId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.folder = :folder AND email.mailRemoteId IN :mailRemoteIds")
  List<Long> findCachedMailRemoteIds(@Param("userId")
  String userId, @Param("folder")
  String folder, @Param("mailRemoteIds")
  List<Long> mailRemoteIds);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.threadId = :canonicalThreadId WHERE email.userId = :userId AND email.threadId IN :threadIds")
  void mergeThreads(@Param("userId")
  String userId, @Param("canonicalThreadId")
  String canonicalThreadId, @Param("threadIds")
  List<String> threadIds);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.threadId = :threadId, email.inReplyTo = :inReplyTo, email.mailReferences = :mailReferences, email.threadIndexRoot = :threadIndexRoot WHERE email.userId = :userId AND email.folder = :folder AND email.mailRemoteId = :mailRemoteId")
  void updateThreadInfo(@Param("userId")
  String userId, @Param("mailRemoteId")
  Long mailRemoteId, @Param("threadId")
  String threadId, @Param("inReplyTo")
  String inReplyTo, @Param("mailReferences")
  String mailReferences, @Param("folder")
  String folder, @Param("threadIndexRoot")
  String threadIndexRoot);

}
