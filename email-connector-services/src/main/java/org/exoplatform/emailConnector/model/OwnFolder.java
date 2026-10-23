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
 * One folder of the session user's OWN mailbox, as the owner's session lists it
 * (EXO-90556): what the owner can choose to share folder by folder. Never a folder under
 * another user's or a shared namespace.
 *
 * @param fullName the folder's full name on the owner's session -- the only name a
 *          grant is ever written on
 * @param displayName its last path segment
 * @param delimiter the hierarchy delimiter
 * @param role its role in the owner's mailbox, read on the owner's session; null for
 *          INBOX and for a folder of the owner's own making
 */
public record OwnFolder(String fullName, String displayName, String delimiter, FolderRole role) {
}
