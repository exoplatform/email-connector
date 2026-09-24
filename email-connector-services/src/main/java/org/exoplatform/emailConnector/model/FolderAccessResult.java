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

/**
 * What became of one folder change (EXO-90556). Each folder is its own write on the mail
 * server: one refused does not undo another, and the owner is told folder by folder.
 *
 * @param folder the folder's full name on the owner's session
 * @param access the access asked for
 * @param outcome what happened
 */
public record FolderAccessResult(String folder, FolderAccess access, Outcome outcome) {

  /** What happened to one folder change. */
  public enum Outcome {
    /** Written as asked. */
    DONE,
    /** The narrower access was refused, so the delegate's entry was removed instead. */
    REMOVED,
    /** The server refused; the folder is as it was. */
    REFUSED,
    /** The owner's own rights on that folder leave nothing a delegate could read. */
    NOTHING_TO_GRANT,
    /** Neither the narrower access nor the removal was accepted: the wider access stays. */
    NOT_NARROWED,
    /** The mail server could not be reached before this folder's turn. */
    NOT_REACHED
  }
}
