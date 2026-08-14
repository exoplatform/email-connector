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
 * the skip-if-unchanged check — and the discovered Sent/Archive/Drafts/Trash
 * folder names, so a routine sync stops re-scanning the whole folder list
 * ({@code LIST *}) every period just to re-find folders that never move. Flat
 * fields rather than a map, so the JSON round-trip stays trivial for the settings
 * serializer.
 * <p>
 * New fields go at the END, always. The all-args constructor is positional and
 * Lombok regenerates it silently, so a field slipped into the middle re-numbers
 * every argument after it while every call site still compiles — a mistake that
 * shows up as data in the wrong column rather than as a build failure.
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

  // The discovered Drafts folder, remembered for the same reason as the two above:
  // the draft save path resolves it on every save, and re-walking the whole folder
  // list to re-find a folder that never moves would put a LIST * in front of every
  // autosave. Declared last so the Lombok all-args constructor only grows a trailing
  // argument.
  private String             draftsFolderName;

  // The Drafts folder's own change snapshot. It earns its place for the same reason
  // the other three do — a Drafts folder nobody has touched since the last sync must
  // not be re-listed and re-fetched every period — and it is the cheapest of the four
  // to keep honest: the folder is small, so the rare wrong "changed" costs almost
  // nothing, while the wrong "unchanged" is the one that would leave a draft written
  // on the user's phone invisible here.
  private FolderSyncSnapshot draftsSnapshot;

  // The discovered Trash folder, remembered for the same reason as the three above:
  // a folder that never moves should not cost a LIST * every time something needs it.
  // Declared after every existing field, and that placement is the point rather than
  // tidiness -- this class carries a Lombok all-args constructor, so a field inserted
  // anywhere but the end silently re-numbers every positional argument after it and
  // every existing call site keeps compiling while meaning something else.
  private String             trashFolderName;

  // The Trash folder's own change snapshot, so a Trash nobody has touched since the
  // last sync is not re-listed and re-fetched every period -- the same skip-if-unchanged
  // deal the other four folders get. Also trailing, for the reason above.
  private FolderSyncSnapshot trashSnapshot;

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
      case MailFolder.DRAFTS -> draftsSnapshot;
      case MailFolder.TRASH -> trashSnapshot;
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
      case MailFolder.DRAFTS -> draftsSnapshot = snapshot;
      case MailFolder.TRASH -> trashSnapshot = snapshot;
      default -> {
        // ALL_MAIL is an on-demand completion store, never bulk-synced: nothing to track.
      }
    }
  }
}
