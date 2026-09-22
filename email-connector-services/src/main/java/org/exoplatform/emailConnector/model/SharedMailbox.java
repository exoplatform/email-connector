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
 * Another user's mailbox as the grantee's own session sees it under the Other Users
 * namespace (RFC 2342), before eXo knows anything about it.
 *
 * @param ownerIdentifier the owner as the namespace names them -- the last segment of
 *          the namespace path ({@code anne.dupont} in {@code Other Users/anne.dupont})
 * @param remoteRoot the full name of the owner's root under the namespace
 * @param inboxName the full name of the owner's INBOX as this session must SELECT it --
 *          a child named INBOX when the server lists one, the root itself otherwise
 * @param delimiter the hierarchy delimiter of the namespace, null when flat
 */
public record SharedMailbox(String ownerIdentifier, String remoteRoot, String inboxName, String delimiter) {
}
