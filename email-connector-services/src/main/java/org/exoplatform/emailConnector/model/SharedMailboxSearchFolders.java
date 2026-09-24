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
import java.util.Map;

/**
 * What the search over cached mail needs to know of the mailboxes shared with a user
 * (EXO-90554), read in one pass over their shares.
 *
 * @param sharedKeys the folder keys of every mailbox shared with the user and in use --
 *          what the read of the user's own mail leaves out
 * @param searchable the folders of those mailboxes the search reads, each with whose
 *          mailbox it is
 */
public record SharedMailboxSearchFolders(List<String> sharedKeys, Map<String, SharedMailboxSearchScope> searchable) {

  /** A user with no mailbox shared with them. */
  public static final SharedMailboxSearchFolders NONE = new SharedMailboxSearchFolders(List.of(), Map.of());
}
