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
package org.exoplatform.emailConnector.event;

import java.util.List;

/**
 * The folders of a shared mailbox were dropped from a delegate's registry -- by a
 * revoke, a leave, a disconnect or a withdrawal found on the server -- and the mail
 * mirrored under their keys must go with them (stack review #437-1). Published by the
 * delegation service, which holds no reference to the mailbox cache; the cache's own
 * service purges on it.
 *
 * @param username the delegate whose rows they were
 * @param folderKeys the {@code CUSTOM:<id>} keys the dropped folders' mail is stored under
 */
public record DelegatedFoldersDroppedEvent(String username, List<String> folderKeys) {
}
