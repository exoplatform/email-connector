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
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.dao.EmailFolderDAO;
import org.exoplatform.emailConnector.entity.EmailFolderEntity;
import org.exoplatform.emailConnector.model.EmailFolder;
import org.exoplatform.emailConnector.model.FolderSyncSnapshot;

/**
 * The custom-folder registry's persistence: entity to {@link EmailFolder} and back,
 * and one targeted write per writer.
 * <p>
 * Every update here is a named, narrow method -- "mark seen", "record a sync", "set
 * the opt-in" -- rather than a save of a whole DTO, and each one loads, mutates and
 * flushes INSIDE one transaction. That is what makes the entity's {@code DynamicUpdate}
 * mean something: a detached DTO merged back would copy every column it carried,
 * including the sync checkpoint the job wrote after the DTO was read, and put it back.
 * A managed entity mutated in place flushes the mutated columns and nothing else, so
 * the settings screen flipping {@code SYNC_ENABLED} and the job stamping
 * {@code LAST_SYNC_DATE} can cross without either erasing the other. The transaction
 * holds nothing foreign: no other domain is called from inside it, which is the
 * objection {@link EmailBoxStorage#saveDraftRow} raises against transactional writes
 * over there and does not apply here.
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
   * chosen yet.
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
    return fromEntity(emailFolderDAO.save(entity));
  }

  /**
   * Discovery's write: the folder was seen again (or not). Refreshes what the server
   * says about it and stamps the sighting; touches neither the opt-in nor the sync
   * memory.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   * @param displayName the last path segment as the server now spells it
   * @param delimiter the hierarchy separator as the server now reports it
   * @param missing whether the walk failed to see it
   * @param seenDate the sighting time, null when it was not seen
   */
  @Transactional
  public void updateDiscovery(String userId, long id, String displayName, String delimiter, boolean missing, Date seenDate) {
    EmailFolderEntity entity = managed(userId, id);
    if (entity == null) {
      return;
    }
    if (displayName != null) {
      entity.setDisplayName(displayName);
    }
    if (delimiter != null) {
      entity.setDelimiter(delimiter);
    }
    entity.setMissing(missing);
    if (seenDate != null) {
      entity.setLastSeenDate(seenDate);
    }
  }

  /**
   * The user's write: the opt-in. Enabling stamps the opt-in date and forgets any sync
   * date, so the folder is first in the next rotation; disabling clears the folder's
   * sync memory along with it, because the mirrored rows are being deleted by the
   * caller and a surviving snapshot would let a later opt-in skip "unchanged" over an
   * empty cache.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   * @param enabled the new opt-in
   * @param when the opt-in time
   */
  @Transactional
  public void updateSyncEnabled(String userId, long id, boolean enabled, Date when) {
    EmailFolderEntity entity = managed(userId, id);
    if (entity == null) {
      return;
    }
    entity.setSyncEnabled(enabled);
    entity.setEnabledDate(enabled ? when : null);
    entity.setLastSyncDate(null);
    if (!enabled) {
      applySnapshot(entity, null);
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
  @Transactional
  public void updateSyncMemory(String userId, long id, FolderSyncSnapshot snapshot, Date when) {
    EmailFolderEntity entity = managed(userId, id);
    if (entity == null) {
      return;
    }
    entity.setLastSyncDate(when);
    if (snapshot != null) {
      applySnapshot(entity, snapshot);
    }
  }

  /**
   * Drops one registered folder.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   */
  @Transactional
  public void deleteFolder(String userId, long id) {
    EmailFolderEntity entity = managed(userId, id);
    if (entity != null) {
      emailFolderDAO.delete(entity);
    }
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
   * The managed row of a folder, inside the caller's transaction, or null when no such
   * row belongs to that user.
   *
   * @param userId the mailbox owner
   * @param id the registry id
   * @return the managed entity, or null
   */
  private EmailFolderEntity managed(String userId, long id) {
    return emailFolderDAO.findByIdAndUserId(id, userId).stream().findFirst().orElse(null);
  }

  /**
   * Writes a snapshot's five signals onto a row, or clears them.
   *
   * @param entity the managed row
   * @param snapshot the snapshot, or null to clear
   */
  private void applySnapshot(EmailFolderEntity entity, FolderSyncSnapshot snapshot) {
    entity.setUidValidity(snapshot == null ? null : snapshot.getUidValidity());
    entity.setUidNext(snapshot == null ? null : snapshot.getUidNext());
    entity.setMessageCount(snapshot == null ? null : snapshot.getMessageCount());
    entity.setHighestModSeq(snapshot == null ? null : snapshot.getHighestModSeq());
    entity.setWindowSize(snapshot == null ? null : snapshot.getWindowSize());
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
