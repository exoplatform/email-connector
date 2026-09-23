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
package org.exoplatform.emailConnector.model;

import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A registered custom folder as the service layer sees it: the row of
 * {@code EMAIL_FOLDER}, snapshot folded into one {@link FolderSyncSnapshot}. The
 * {@code key} is derived, never stored: it is what every mirrored message of the
 * folder carries in {@code EMAIL_BOX.FOLDER}.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class EmailFolder {

  private Long               id;

  private String             userId;

  private String             remoteName;

  private String             displayName;

  private String             delimiter;

  private String             type;

  private boolean            syncEnabled;

  private Date               enabledDate;

  private boolean            missing;

  private Date               discoveredDate;

  private Date               lastSeenDate;

  private Date               lastSyncDate;

  // Null when the folder was never fully synced: the next sync takes the full path.
  private FolderSyncSnapshot snapshot;

  // Null for a folder of the user's own mailbox; the EMAIL_DELEGATION id for a folder of
  // a mailbox shared with them. The class is @AllArgsConstructor and built
  // positionally: fields are only ever added after the existing ones.
  private Long               delegationId;

  // EXO-90548: a delegated folder's role in its owner's mailbox, null otherwise.
  private FolderRole         role;

  // EXO-90548: the delegate's own letters on this delegated folder, null otherwise.
  private String             rights;

  // EXO-90548: when those letters were last read.
  private Date               rightsCheckDate;

  /**
   * The folder as it was before EXO-90548 gave delegated folders a role and letters of
   * their own: every existing positional caller keeps building it this way, with no role
   * and no letters.
   *
   * @param id the row id
   * @param userId the user
   * @param remoteName the IMAP full name
   * @param displayName the last segment
   * @param delimiter the hierarchy separator
   * @param type CUSTOM, DELEGATED_INBOX or DELEGATED
   * @param syncEnabled the opt-in
   * @param enabledDate when opted in
   * @param missing whether the last walk missed it
   * @param discoveredDate when first seen
   * @param lastSeenDate when last seen
   * @param lastSyncDate when last synced
   * @param snapshot the sync snapshot
   * @param delegationId the delegation of a shared mailbox's folder
   */
  public EmailFolder(Long id,
                     String userId,
                     String remoteName,
                     String displayName,
                     String delimiter,
                     String type,
                     boolean syncEnabled,
                     Date enabledDate,
                     boolean missing,
                     Date discoveredDate,
                     Date lastSeenDate,
                     Date lastSyncDate,
                     FolderSyncSnapshot snapshot,
                     Long delegationId) {
    this(id, userId, remoteName, displayName, delimiter, type, syncEnabled, enabledDate, missing, discoveredDate, lastSeenDate,
         lastSyncDate, snapshot, delegationId, null, null, null);
  }

  /**
   * The {@code EMAIL_BOX.FOLDER} discriminator of this folder's mirrored messages.
   *
   * @return {@code CUSTOM:<id>}, or null before the row is created
   */
  public String getKey() {
    return id == null ? null : MailFolder.customKey(id);
  }
}
