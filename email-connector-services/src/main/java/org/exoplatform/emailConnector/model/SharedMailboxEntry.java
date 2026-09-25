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

import java.util.Date;
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
 * @param inboxOnly whether eXo wrote the share before it shared the owner's other folders,
 *          so that only the INBOX is shared and the owner can extend it (EXO-90548 review):
 *          what the band tells the delegate, rather than what the last discovery happened
 *          to find. False for a share made in the mail server's own interface
 * @param sentCopy whether a mail the delegate sends from this mailbox is also filed in
 *          its owner's Sent (EXO-90551): a Sent is shared, the delegate holds i there,
 *          and an administrator has not switched the copy off -- what the composer's
 *          notice says and what its "Copy {owner}" box defaults from (PO decision Q-3)
 * @param sendModes the shapes the delegate can write mail in the owner's name in now
 *          (EXO-90582): the owner's consent narrowed to what the connector declares,
 *          less the shapes the server refused since the owner last set it (EXO-90626) --
 *          the list the From picker offers. Most transparent first; empty for none, never
 *          null
 * @param sendRefusedDate when the owner's mail server last refused a mail in her name
 *          from the delegate, since she last set the consent; null for never
 * @param sendRefusedMode the shape that refusal blocks from (EXO-90626): {@code AS} when
 *          only writing as the owner is refused and on her behalf still works,
 *          {@code ON_BEHALF} when both are; null with no refusal
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
                                 boolean inboxOnly,
                                 boolean sentCopy,
                                 List<SendMode> sendModes,
                                 Date sendRefusedDate,
                                 SendMode sendRefusedMode) {

  /**
   * Normalises the usable shapes to an unmodifiable list, never null.
   *
   * @param delegationId the delegation id
   * @param ownerId the owner's eXo username
   * @param ownerFullName the owner's display name
   * @param ownerMailbox the owner's mailbox address
   * @param preset the preset the rights read as
   * @param rights the letters the server last granted the delegate
   * @param affordances the controls those letters unlock
   * @param folderKey the key the shared INBOX is listed under
   * @param unreadCount the unread count of that INBOX
   * @param folders the shared mailbox's other folders
   * @param inboxOnly whether only the INBOX is shared
   * @param sentCopy whether a mail sent from here is filed in the owner's Sent
   * @param sendModes the shapes the delegate can write in the owner's name in now
   * @param sendRefusedDate when the owner's mail server last refused one, null for never
   * @param sendRefusedMode the shape that refusal blocks from, null with no refusal
   */
  public SharedMailboxEntry {
    sendModes = sendModes == null ? List.of() : List.copyOf(sendModes);
  }

  /**
   * An entry the delegate cannot write in the owner's name from -- every caller written
   * before EXO-90582.
   *
   * @param delegationId the delegation id
   * @param ownerId the owner's eXo username
   * @param ownerFullName the owner's display name
   * @param ownerMailbox the owner's mailbox address
   * @param preset the preset the rights read as
   * @param rights the letters the server last granted the delegate
   * @param affordances the controls those letters unlock
   * @param folderKey the key the shared INBOX is listed under
   * @param unreadCount the unread count of that INBOX
   * @param folders the shared mailbox's other folders
   * @param inboxOnly whether only the INBOX is shared
   * @param sentCopy whether a mail sent from here is filed in the owner's Sent
   */
  public SharedMailboxEntry(Long delegationId,
                            String ownerId,
                            String ownerFullName,
                            String ownerMailbox,
                            DelegationPreset preset,
                            String rights,
                            Map<String, Boolean> affordances,
                            String folderKey,
                            int unreadCount,
                            List<SharedMailboxFolder> folders,
                            boolean inboxOnly,
                            boolean sentCopy) {
    this(delegationId,
         ownerId,
         ownerFullName,
         ownerMailbox,
         preset,
         rights,
         affordances,
         folderKey,
         unreadCount,
         folders,
         inboxOnly,
         sentCopy,
         List.of());
  }

  /**
   * An entry whose owner's mail server refused nothing since the consent was set -- every
   * caller written before EXO-90626.
   *
   * @param delegationId the delegation id
   * @param ownerId the owner's eXo username
   * @param ownerFullName the owner's display name
   * @param ownerMailbox the owner's mailbox address
   * @param preset the preset the rights read as
   * @param rights the letters the server last granted the delegate
   * @param affordances the controls those letters unlock
   * @param folderKey the key the shared INBOX is listed under
   * @param unreadCount the unread count of that INBOX
   * @param folders the shared mailbox's other folders
   * @param inboxOnly whether only the INBOX is shared
   * @param sentCopy whether a mail sent from here is filed in the owner's Sent
   * @param sendModes the shapes the delegate can write in the owner's name in now
   */
  public SharedMailboxEntry(Long delegationId,
                            String ownerId,
                            String ownerFullName,
                            String ownerMailbox,
                            DelegationPreset preset,
                            String rights,
                            Map<String, Boolean> affordances,
                            String folderKey,
                            int unreadCount,
                            List<SharedMailboxFolder> folders,
                            boolean inboxOnly,
                            boolean sentCopy,
                            List<SendMode> sendModes) {
    this(delegationId,
         ownerId,
         ownerFullName,
         ownerMailbox,
         preset,
         rights,
         affordances,
         folderKey,
         unreadCount,
         folders,
         inboxOnly,
         sentCopy,
         sendModes,
         null,
         null);
  }
}
