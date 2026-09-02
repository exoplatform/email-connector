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
 * A folder as the interface lists it -- built-in or custom, in one shape, so the
 * client's folder menu, its title and its move-to picker read one list rather than a
 * hard-coded array beside a server-side check. A custom folder carries the name the
 * user wrote ({@code displayName}, never translated); a built-in carries only its key,
 * which the client resolves through its own bundle.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class MailFolderView {

  public static final String TYPE_BUILT_IN = "BUILT_IN";

  public static final String TYPE_CUSTOM   = "CUSTOM";

  // The EMAIL_BOX.FOLDER discriminator: a MailFolder constant, or CUSTOM:<id>.
  private String  key;

  private String  type;

  // The registry id, custom folders only.
  private Long    id;

  // The last path segment as the user wrote it, custom folders only.
  private String  displayName;

  // The IMAP full name, custom folders only.
  private String  path;

  private String  delimiter;

  // Custom folders only; a built-in is always mirrored when the mailbox has it.
  private boolean syncEnabled;

  private boolean missing;

  // How many messages the mirror holds for this folder.
  private int     count;

  private Date    lastSyncDate;

  // The mirror window, so the listing can say "showing the N most recent".
  private Integer windowSize;

  /**
   * Whether this is one of the user's own folders.
   *
   * @return true for a custom folder
   */
  public boolean isCustom() {
    return TYPE_CUSTOM.equals(type);
  }
}
