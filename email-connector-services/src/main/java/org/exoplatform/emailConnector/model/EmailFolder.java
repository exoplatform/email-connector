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

  /**
   * The {@code EMAIL_BOX.FOLDER} discriminator of this folder's mirrored messages.
   *
   * @return {@code CUSTOM:<id>}, or null before the row is created
   */
  public String getKey() {
    return id == null ? null : MailFolder.customKey(id);
  }
}
