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

import java.util.List;

/**
 * What a folder of a shared mailbox is in its owner's mailbox (EXO-90548): the role a
 * delete, an archive or a "mark as spam" in that mailbox files into, and the unit an
 * owner's grant writes its letters for. The names are the {@link MailFolder} keys of the
 * same built-in folders of the user's own mailbox.
 */
public enum FolderRole {

  /** Sent mail. */
  SENT,

  /** Archive, where "archive" files. */
  ARCHIVE,

  /** Trash, where "delete" files. */
  TRASH,

  /** Spam, where "mark as spam" files. */
  JUNK,

  /** Drafts: never granted by eXo (they are the owner's unfinished mail), but discovered. */
  DRAFTS;

  /** The roles an owner's grant covers beside INBOX, in the order they are granted. */
  public static final List<FolderRole> GRANTED = List.of(SENT, ARCHIVE, TRASH, JUNK);

  /**
   * The role of a stored name, null for a blank or unknown one -- a row written by a
   * later version with a role this one does not know is read as no role, not as an error.
   *
   * @param name the stored name
   * @return the role, or null
   */
  public static FolderRole of(String name) {
    if (name == null || name.isBlank()) {
      return null;
    }
    for (FolderRole role : values()) {
      if (role.name().equals(name.trim())) {
        return role;
      }
    }
    return null;
  }
}
