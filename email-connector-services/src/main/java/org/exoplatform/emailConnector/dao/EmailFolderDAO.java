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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailFolderEntity;

/**
 * The custom-folder registry. Every read AND every write is scoped to a user: a folder
 * id sent by a client is only ever resolved together with the caller's own user id, so
 * a number guessed or remembered from another account addresses nothing.
 * <p>
 * The writes are explicit UPDATE statements, one per writer, each naming the columns
 * that writer owns. The row has three writers (discovery, the user's opt-in, the sync
 * job's checkpoint), and a read-modify-save would put back whatever the other two
 * committed since the read -- a hazard {@code DynamicUpdate} on the entity only
 * narrows to "since the load", which is not nothing when the load can come from a
 * persistence context that outlives the call. A statement that names its columns
 * cannot touch the others, whatever context is around. {@code clearAutomatically}
 * so a read after the write sees the row, not a cached instance of it.
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
   * Discovery's write for a folder the walk saw again: what the server now says about
   * it, un-missed, and the sighting stamped. One statement naming its columns and no
   * others, which is what makes the row safe for its two other writers: the settings
   * screen's opt-in and the sync job's checkpoint are never read here, so they can
   * never be put back stale.
   *
   * @param id the row id
   * @param userId the mailbox owner
   * @param displayName the last path segment as the server now spells it
   * @param delimiter the hierarchy separator as the server now reports it
   * @param seenDate the sighting time
   * @return the rows updated: one, or zero when no such row belongs to that user
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailFolderEntity folder SET folder.displayName = :displayName, folder.delimiter = :delimiter, folder.missing = false, folder.lastSeenDate = :seenDate WHERE folder.id = :id AND folder.userId = :userId")
  int markSeen(@Param("id")
  long id, @Param("userId")
  String userId, @Param("displayName")
  String displayName, @Param("delimiter")
  String delimiter, @Param("seenDate")
  Date seenDate);

  /**
   * Discovery's write for a folder the walk (or a sync) could not find: the missing
   * mark and nothing else, so the grace rule of the next walk applies.
   *
   * @param id the row id
   * @param userId the mailbox owner
   * @return the rows updated: one, or zero when no such row belongs to that user
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailFolderEntity folder SET folder.missing = true WHERE folder.id = :id AND folder.userId = :userId")
  int markMissing(@Param("id")
  long id, @Param("userId")
  String userId);

  /**
   * The user's opt-in. Stamps the opt-in date and forgets any check date, so the
   * folder is first in the next rotation; touches no snapshot column.
   *
   * @param id the row id
   * @param userId the mailbox owner
   * @param when the opt-in time
   * @return the rows updated: one, or zero when no such row belongs to that user
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailFolderEntity folder SET folder.syncEnabled = true, folder.enabledDate = :enabledDate, folder.lastSyncDate = null WHERE folder.id = :id AND folder.userId = :userId")
  int enableSync(@Param("id")
  long id, @Param("userId")
  String userId, @Param("enabledDate")
  Date when);

  /**
   * The user's opt-out. Clears the opt-in AND the folder's whole sync memory in one
   * statement: the caller is deleting the mirrored rows, and a snapshot surviving them
   * would let a later opt-in skip "unchanged" over an empty cache -- the folder would
   * come up blank and stay blank until new mail happened to arrive.
   *
   * @param id the row id
   * @param userId the mailbox owner
   * @return the rows updated: one, or zero when no such row belongs to that user
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailFolderEntity folder SET folder.syncEnabled = false, folder.enabledDate = null, folder.lastSyncDate = null, folder.uidValidity = null, folder.uidNext = null, folder.messageCount = null, folder.highestModSeq = null, folder.windowSize = null WHERE folder.id = :id AND folder.userId = :userId")
  int disableSync(@Param("id")
  long id, @Param("userId")
  String userId);

  /**
   * The sync job's write when the folder was checked and found unchanged: the check
   * date alone, so the folder rotates to the back of the queue and keeps the snapshot
   * it had. Only while the folder is still opted in: an opt-out that landed during the
   * check must not be un-done by a stamp written after it.
   *
   * @param id the row id
   * @param userId the mailbox owner
   * @param when the check time
   * @return the rows updated: one, or zero when no such row belongs to that user
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailFolderEntity folder SET folder.lastSyncDate = :when WHERE folder.id = :id AND folder.userId = :userId AND folder.syncEnabled = true")
  int recordCheck(@Param("id")
  long id, @Param("userId")
  String userId, @Param("when")
  Date when);

  /**
   * The sync job's write when the folder was fully synced: the check date and the five
   * signals of the snapshot the sync captured at its SELECT. Only while the folder is
   * still opted in, for the reason {@link #recordCheck} gives and one more: a snapshot
   * re-planted on a folder whose rows the opt-out just deleted would let the next
   * opt-in skip "unchanged" over an empty cache.
   *
   * @param id the row id
   * @param userId the mailbox owner
   * @param when the check time
   * @param uidValidity the folder's UIDVALIDITY
   * @param uidNext the folder's UIDNEXT
   * @param messageCount the message count the window listing used
   * @param highestModSeq the folder's HIGHESTMODSEQ, negative without CONDSTORE
   * @param windowSize the window the sync listed
   * @return the rows updated: one, or zero when no such row belongs to that user
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailFolderEntity folder SET folder.lastSyncDate = :when, folder.uidValidity = :uidValidity, folder.uidNext = :uidNext, folder.messageCount = :messageCount, folder.highestModSeq = :highestModSeq, folder.windowSize = :windowSize WHERE folder.id = :id AND folder.userId = :userId AND folder.syncEnabled = true")
  int recordSnapshot(@Param("id")
  long id, @Param("userId")
  String userId, @Param("when")
  Date when, @Param("uidValidity")
  long uidValidity, @Param("uidNext")
  long uidNext, @Param("messageCount")
  long messageCount, @Param("highestModSeq")
  long highestModSeq, @Param("windowSize")
  int windowSize);

  /**
   * The in-app rename's write: the folder's own two name columns, and nothing a rename
   * does not own. Neither {@code SYNC_ENABLED} nor any snapshot column is named here,
   * on purpose: the row's id -- and with it the {@code CUSTOM:<id>} key every mirrored
   * message carries -- is what makes a rename different from a delete-then-create, and
   * that is only true if the row the opt-in and the sync job know about is the SAME row
   * this statement touches, untouched in every other column.
   *
   * @param id the row id
   * @param userId the mailbox owner
   * @param remoteName the folder's new full name on the server
   * @param displayName the new display name
   * @return the rows updated: one, or zero when no such row belongs to that user
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailFolderEntity folder SET folder.remoteName = :remoteName, folder.displayName = :displayName WHERE folder.id = :id AND folder.userId = :userId")
  int renameFolder(@Param("id")
  long id, @Param("userId")
  String userId, @Param("remoteName")
  String remoteName, @Param("displayName")
  String displayName);

  /**
   * Drops one registered folder, by id and owner.
   *
   * @param id the row id
   * @param userId the mailbox owner
   * @return the rows deleted: one, or zero when no such row belongs to that user
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM EmailFolderEntity folder WHERE folder.id = :id AND folder.userId = :userId")
  int deleteByIdAndUserId(@Param("id")
  long id, @Param("userId")
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
