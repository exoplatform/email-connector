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
 * The owner's "Folders and access" list for one delegate (EXO-90556).
 *
 * @param folders INBOX first, then the role folders in grant order, then the owner's
 *          other folders by name
 * @param truncated whether the mailbox has more folders than can be shared one by one
 */
public record DelegationFolders(List<DelegationFolder> folders, boolean truncated) {
}
