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
package org.exoplatform.emailConnector.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The per-mailbox synchronization memory, persisted as JSON in SettingService
 * (its own key, deliberately NOT inside {@link UserEmailSetting}: that object is
 * read-modified-written by user-facing preference flows, and sync bookkeeping
 * racing user preferences over one JSON blob is how settings get clobbered).
 * Carries one {@link FolderSyncSnapshot} per bulk-synced folder — the basis of
 * the skip-if-unchanged check — and the discovered Sent/Archive folder names, so
 * a routine sync stops re-scanning the whole folder list ({@code LIST *}) every
 * period just to re-find two folders that never move. Flat fields rather than a
 * map, so the JSON round-trip stays trivial for the settings serializer.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MailboxSyncState {

  private FolderSyncSnapshot inboxSnapshot;

  private FolderSyncSnapshot sentSnapshot;

  private FolderSyncSnapshot archiveSnapshot;

  private String             sentFolderName;

  private String             archiveFolderName;

  /**
   * The stored snapshot of a bulk-synced folder.
   *
   * @param folderKey the {@link MailFolder} discriminator
   * @return the folder's snapshot, or null when it was never captured (or the
   *         folder is not one the bulk sync tracks)
   */
  public FolderSyncSnapshot getSnapshot(String folderKey) {
    return switch (folderKey) {
      case MailFolder.INBOX -> inboxSnapshot;
      case MailFolder.SENT -> sentSnapshot;
      case MailFolder.ARCHIVE -> archiveSnapshot;
      default -> null;
    };
  }

  /**
   * Stores (or clears, with null) a bulk-synced folder's snapshot.
   *
   * @param folderKey the {@link MailFolder} discriminator
   * @param snapshot the freshly captured snapshot, or null to force the next sync
   *          down the full path
   */
  public void setSnapshot(String folderKey, FolderSyncSnapshot snapshot) {
    switch (folderKey) {
      case MailFolder.INBOX -> inboxSnapshot = snapshot;
      case MailFolder.SENT -> sentSnapshot = snapshot;
      case MailFolder.ARCHIVE -> archiveSnapshot = snapshot;
      default -> {
        // ALL_MAIL is an on-demand completion store, never bulk-synced: nothing to track.
      }
    }
  }
}
