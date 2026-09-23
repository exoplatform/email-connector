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

import java.util.Map;

/**
 * One folder of a shared mailbox as the delegate's switcher and folder tree show it
 * (EXO-90548): its key, its role in the owner's mailbox, its name, and the controls the
 * delegate's own letters on THAT folder unlock -- a Reader may see the owner's Trash and
 * nothing more, an Editor may delete from INBOX but never purge the Trash.
 *
 * @param key the {@code CUSTOM:<id>} key it is listed under
 * @param role its role in the owner's mailbox, null for INBOX and for a folder of the
 *          owner's own
 * @param displayName its last path segment, as the server spells it
 * @param rights the delegate's letters on it
 * @param affordances the controls those letters unlock
 * @param readable whether the delegate may read it at all ({@code l} without {@code r}:
 *          listed, greyed, never synced)
 */
public record SharedMailboxFolder(String key,
                                  FolderRole role,
                                  String displayName,
                                  String rights,
                                  Map<String, Boolean> affordances,
                                  boolean readable) {
}
