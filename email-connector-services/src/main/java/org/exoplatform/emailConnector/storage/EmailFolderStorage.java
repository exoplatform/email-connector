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
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailFolderDAO;
import org.exoplatform.emailConnector.entity.EmailFolderEntity;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.FolderSyncSnapshot;

/**
 * The custom-folder registry's persistence: entity to {@link EmailFolder} and back,
 * and one targeted write per writer.
 * <p>
 * Every update here is a named, narrow method -- "mark seen", "record a sync", "set
 * the opt-in" -- and each one is a single UPDATE statement in the DAO that names the
 * columns that writer owns (see {@link EmailFolderDAO}). Nothing here loads a row to
 * save it back: a merged DTO would copy every column it carried, including the sync
 * checkpoint the job wrote after the DTO was read, and put it back; and a managed
 * entity is only as fresh as the persistence context it came from, which on a request
 * thread is not always this call's. So the settings screen flipping the opt-in and the
 * job stamping the check date can cross without either erasing the other, by the shape
 * of the statements rather than by timing.
 */
@Component
public class EmailFolderStorage {

  @Autowired
  private EmailFolderDAO emailFolderDAO;

  /**
   * Every registered folder of a mailbox, missing ones included, by display name.
   *
   * @param userId the mailbox owner
   * @return the folders, never null
   */
  public List<EmailFolder> getFolders(String userId) {
    return emailFolderDAO.findByUserId(userId).stream().map(this::fromEntity).toList();
  }

  /**
   * One registered folder, by id and owner.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   * @return the folder, or null when no such row belongs to that user
   */
  public EmailFolder getFolder(String userId, long id) {
    return emailFolderDAO.findByIdAndUserId(id, userId).stream().findFirst().map(this::fromEntity).orElse(null);
  }

  /**
   * The registered folder carrying a remote name.
   *
   * @param userId the mailbox owner
   * @param remoteName the IMAP full name
   * @return the folder, or null when none is registered under that name
   */
  public EmailFolder getFolderByRemoteName(String userId, String remoteName) {
    return emailFolderDAO.findByUserIdAndRemoteName(userId, remoteName)
                         .stream()
                         .findFirst()
                         .map(this::fromEntity)
                         .orElse(null);
  }

  /**
   * The folders a user opted in and the last walk still saw, oldest opt-in first.
   *
   * @param userId the mailbox owner
   * @return the enabled, present folders, never null
   */
  public List<EmailFolder> getEnabledFolders(String userId) {
    return emailFolderDAO.findEnabledByUserId(userId).stream().map(this::fromEntity).toList();
  }

  /**
   * How many folders a user has opted in.
   *
   * @param userId the mailbox owner
   * @return the enabled count
   */
  public long countEnabledFolders(String userId) {
    return emailFolderDAO.countEnabledByUserId(userId);
  }

  /**
   * Registers a folder a discovery walk found for the first time. The opt-in is off
   * and the snapshot empty whatever the DTO says: a new row is a folder nobody has
   * chosen yet. Flushed on the spot, so the unique index's refusal of a duplicate name
   * surfaces HERE, where the reconcile catches it per folder, and not at the end of
   * whatever persistence context this call happens to run in.
   *
   * @param folder the folder to register (id ignored)
   * @return the row as created, id set
   */
  public EmailFolder createFolder(EmailFolder folder) {
    EmailFolderEntity entity = new EmailFolderEntity();
    entity.setUserId(folder.getUserId());
    entity.setRemoteName(folder.getRemoteName());
    entity.setDisplayName(folder.getDisplayName());
    entity.setDelimiter(folder.getDelimiter());
    entity.setType(folder.getType());
    entity.setSyncEnabled(false);
    entity.setMissing(false);
    entity.setDiscoveredDate(folder.getDiscoveredDate());
    entity.setLastSeenDate(folder.getLastSeenDate());
    return fromEntity(emailFolderDAO.saveAndFlush(entity));
  }

  /**
   * Discovery's write for a folder seen again: what the server now says about it,
   * un-missed, the sighting stamped. Touches neither the opt-in nor the sync memory.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   * @param displayName the last path segment as the server now spells it
   * @param delimiter the hierarchy separator as the server now reports it
   * @param seenDate the sighting time
   */
  public void markSeen(String userId, long id, String displayName, String delimiter, Date seenDate) {
    emailFolderDAO.markSeen(id, userId, displayName, delimiter, seenDate);
  }

  /**
   * Discovery's (or the sync's) write for a folder it could not find: the missing
   * mark, and nothing else.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   */
  public void markMissing(String userId, long id) {
    emailFolderDAO.markMissing(id, userId);
  }

  /**
   * The user's write: the opt-in. Enabling stamps the opt-in date and forgets any
   * check date, so the folder is first in the next rotation; disabling clears the
   * folder's sync memory along with it, because the mirrored rows are being deleted by
   * the caller and a surviving snapshot would let a later opt-in skip "unchanged" over
   * an empty cache.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   * @param enabled the new opt-in
   * @param when the opt-in time
   */
  public void updateSyncEnabled(String userId, long id, boolean enabled, Date when) {
    if (enabled) {
      emailFolderDAO.enableSync(id, userId, when);
    } else {
      emailFolderDAO.disableSync(id, userId);
    }
  }

  /**
   * The sync job's write: the folder was checked, and possibly synced. The snapshot is
   * replaced only when one was captured; a skipped folder keeps the one it had.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   * @param snapshot the freshly captured snapshot, or null when the sync was skipped
   *          or captured nothing
   * @param when the check time
   */
  public void updateSyncMemory(String userId, long id, FolderSyncSnapshot snapshot, Date when) {
    if (snapshot == null) {
      emailFolderDAO.recordCheck(id, userId, when);
    } else {
      emailFolderDAO.recordSnapshot(id,
                                    userId,
                                    when,
                                    snapshot.getUidValidity(),
                                    snapshot.getUidNext(),
                                    snapshot.getMessageCount(),
                                    snapshot.getHighestModSeq(),
                                    snapshot.getWindowSize());
    }
  }

  /**
   * The in-app rename's write: the row's own name columns replaced, everything else --
   * the opt-in, the sync memory -- left exactly as it was. One statement naming only
   * the two columns a rename owns, for the same reason every other writer here does:
   * so it can never put back what the opt-in switch or the sync job wrote since this
   * request read the row.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   * @param remoteName the folder's new full name on the server
   * @param displayName the new display name
   * @return the row as it now stands
   */
  public EmailFolder renameFolder(String userId, long id, String remoteName, String displayName) {
    emailFolderDAO.renameFolder(id, userId, remoteName, displayName);
    return getFolder(userId, id);
  }

  /**
   * Drops one registered folder.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   */
  public void deleteFolder(String userId, long id) {
    emailFolderDAO.deleteByIdAndUserId(id, userId);
  }

  /**
   * Drops every registered folder of a mailbox.
   *
   * @param userId the mailbox owner
   */
  public void deleteFolders(String userId) {
    emailFolderDAO.deleteByUserId(userId);
  }

  /**
   * Entity to DTO. The snapshot is rebuilt only when every one of its signals is
   * present: a half-written one is no snapshot, and the sync's skip check must not be
   * handed something it would refuse anyway.
   *
   * @param entity the row
   * @return the DTO
   */
  private EmailFolder fromEntity(EmailFolderEntity entity) {
    FolderSyncSnapshot snapshot = null;
    if (entity.getUidValidity() != null && entity.getUidNext() != null && entity.getMessageCount() != null
        && entity.getHighestModSeq() != null && entity.getWindowSize() != null) {
      snapshot = new FolderSyncSnapshot(entity.getUidValidity(),
                                        entity.getUidNext(),
                                        entity.getMessageCount(),
                                        entity.getHighestModSeq(),
                                        entity.getWindowSize());
    }
    return new EmailFolder(entity.getId(),
                           entity.getUserId(),
                           entity.getRemoteName(),
                           entity.getDisplayName(),
                           entity.getDelimiter(),
                           entity.getType(),
                           entity.isSyncEnabled(),
                           entity.getEnabledDate(),
                           entity.isMissing(),
                           entity.getDiscoveredDate(),
                           entity.getLastSeenDate(),
                           entity.getLastSyncDate(),
                           snapshot);
  }
}
