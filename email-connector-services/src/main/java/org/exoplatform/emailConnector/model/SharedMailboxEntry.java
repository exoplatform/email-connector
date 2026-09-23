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
 * One entry of the mailbox switcher in the delegate's mail drawer (delegation plan
 * 7.3): an ACCEPTED share, with what the drawer needs to open it and to draw its chrome
 * -- the folder key its INBOX is listed under, the rights the server last granted and
 * the affordances they unlock, and the unread count of that INBOX as the delegate's
 * mirror holds it (which, {@code \Seen} being shared on the server, is the owner's own
 * unread count, plan 4.1).
 *
 * @param delegationId the delegation id, what the deep link's {@code mailbox} names
 * @param ownerId the owner's eXo username, null for a share made outside eXo that no
 *          connected user could be mapped to
 * @param ownerFullName the owner's display name, the mailbox address when no eXo user
 *          is known
 * @param ownerMailbox the owner's mailbox address
 * @param preset the preset the rights read as, null for custom letters
 * @param rights the RFC 4314 letters the server last granted the delegate
 * @param affordances the controls those letters unlock, as {@link MailboxRights#affordances()}
 *          names them
 * @param folderKey the {@code CUSTOM:<id>} key the shared INBOX is listed under
 * @param unreadCount how many messages of that INBOX the delegate's mirror holds unread
 * @param folders the shared mailbox's other folders the delegate may see -- the owner's
 *          Sent, Archive, Trash, Spam and the rest the share covers (EXO-90548), roles
 *          first; empty for a share that covers INBOX only
 * @param inboxOnly whether the share covers the owner's INBOX alone -- written before eXo
 *          shared the owner's other folders (EXO-90548 review): what the band tells the
 *          delegate, rather than what the last discovery happened to find
 */
public record SharedMailboxEntry(Long delegationId,
                                 String ownerId,
                                 String ownerFullName,
                                 String ownerMailbox,
                                 DelegationPreset preset,
                                 String rights,
                                 Map<String, Boolean> affordances,
                                 String folderKey,
                                 int unreadCount,
                                 List<SharedMailboxFolder> folders,
                                 boolean inboxOnly) {
}
