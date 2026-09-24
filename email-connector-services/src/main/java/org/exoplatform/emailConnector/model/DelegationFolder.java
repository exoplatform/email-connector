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
 * One folder of the owner's mailbox in the owner's "Folders and access" list for one
 * delegate (EXO-90556), as the mail server holds it right now: read with GETACL on that
 * folder, never from what eXo recorded.
 *
 * @param folder the folder's full name on the owner's session -- the key a change names
 * @param displayName its last path segment
 * @param parent the full name of its parent folder, null at the top
 * @param depth how deep it sits, 0 at the top
 * @param role its role in the owner's mailbox, null for INBOX and the owner's own folders
 * @param access READER, EDITOR or NONE; null when the entry reads as no preset (set in
 *          another mail application) or could not be read
 * @param rights the delegate's letters there, raw, for an entry that reads as no preset
 * @param readable whether the folder's ACL could be read at all
 * @param editable whether the owner may change it here: never INBOX, which is the share
 *          itself
 */
public record DelegationFolder(String folder,
                               String displayName,
                               String parent,
                               int depth,
                               FolderRole role,
                               FolderAccess access,
                               String rights,
                               boolean readable,
                               boolean editable) {
}
