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

import org.exoplatform.emailConnector.entity.EmailFolderEntity;

/**
 * The custom-folder registry. Every read is scoped to a user: a folder id sent by a
 * client is only ever resolved together with the caller's own user id, so a number
 * guessed or remembered from another account addresses nothing.
 */
public interface EmailFolderDAO extends JpaRepository<EmailFolderEntity, Long> {

  /**
   * Every registered folder of a mailbox, missing ones included, in the order the
   * settings screen shows them. The rows of one user are bounded by the folder count of
   * their mailbox -- tens, exceptionally hundreds -- which is why this read is not paged.
   *
   * @param userId the mailbox owner
   * @return the folders, by display name, never null
   */
  @Query("SELECT folder FROM EmailFolderEntity folder WHERE folder.userId = :userId ORDER BY folder.displayName ASC, folder.remoteName ASC")
  List<EmailFolderEntity> findByUserId(@Param("userId")
  String userId);

  /**
   * One registered folder, by id AND owner. A list rather than an Optional for the
   * reason {@link EmailThreadAiSummaryDAO#findByUserIdAndThreadId} gives: the query is
   * not the thing that should throw. The caller takes the first, or answers "unknown".
   *
   * @param id the row id
   * @param userId the mailbox owner
   * @return the matching rows, normally exactly one or none, never null
   */
  @Query("SELECT folder FROM EmailFolderEntity folder WHERE folder.id = :id AND folder.userId = :userId")
  List<EmailFolderEntity> findByIdAndUserId(@Param("id")
  long id, @Param("userId")
  String userId);

  /**
   * The registered folder carrying a remote name, for the upsert a discovery walk
   * performs. Unique in the database ({@code UQ_EMAIL_FOLDER_USER_NAME}); a list for the
   * same reason as above.
   *
   * @param userId the mailbox owner
   * @param remoteName the IMAP full name
   * @return the matching rows, normally exactly one or none, never null
   */
  @Query("SELECT folder FROM EmailFolderEntity folder WHERE folder.userId = :userId AND folder.remoteName = :remoteName")
  List<EmailFolderEntity> findByUserIdAndRemoteName(@Param("userId")
  String userId, @Param("remoteName")
  String remoteName);

  /**
   * The folders a user opted in and the last walk still saw -- the candidates of one
   * sync cycle, before the cap and the per-cycle budget are applied to them. Ordered by
   * opt-in date so the cap, when an administrator lowers it under what a user already
   * enabled, keeps the OLDEST opt-ins; the least-recently-synced rotation is applied by
   * the service over this bounded list rather than in a second query, because both
   * orders are needed at once and the list is at most the cap plus what was enabled
   * before the cap moved.
   *
   * @param userId the mailbox owner
   * @return the enabled, present folders, oldest opt-in first, never null
   */
  @Query("SELECT folder FROM EmailFolderEntity folder WHERE folder.userId = :userId AND folder.syncEnabled = true AND folder.missing = false ORDER BY folder.enabledDate ASC, folder.id ASC")
  List<EmailFolderEntity> findEnabledByUserId(@Param("userId")
  String userId);

  /**
   * How many folders a user has opted in -- what the cap is checked against.
   *
   * @param userId the mailbox owner
   * @return the enabled count, missing ones included (they still hold a slot until
   *         their grace walk expires)
   */
  @Query("SELECT COUNT(folder) FROM EmailFolderEntity folder WHERE folder.userId = :userId AND folder.syncEnabled = true")
  long countEnabledByUserId(@Param("userId")
  String userId);

  /**
   * Drops every registered folder of a mailbox -- the disconnect / rebind wipe. The
   * mirrored rows those folders keyed are deleted by the same wipe through the total
   * mailbox read; a registry row outliving the account it belonged to would let the next
   * account bound here inherit a key that names another mailbox's folder.
   *
   * @param userId the mailbox owner
   */
  @Transactional
  @Modifying
  @Query("DELETE FROM EmailFolderEntity folder WHERE folder.userId = :userId")
  void deleteByUserId(@Param("userId")
  String userId);
}
