/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

// What a mailbox somebody shared with the user lets the interface offer, as pure rules
// with no state: imported by the mail drawer (through EmailConnectorSharedMailboxes.js)
// and by the settings' "Shared with you" drawer, which are separate bundles. Keep it
// stateless -- a module-level value here would exist twice on a page that loads both.

/**
 * PHASE-2 SWITCH -- moving mail OUT of a shared mailbox: Delete (into Trash), Archive,
 * "Mark as spam" and "Move to...".
 *
 * Off in phase 1 by construction, not by caution: accepting a share registers its
 * INBOX and nothing else, so every one of these has its destination in the user's OWN
 * mailbox, and the backend refuses a move across mailboxes
 * (emailConnector.folder.crossMailbox, EmailBoxService#checkDelegatedMove). Offering
 * them would be offering buttons that always fail. Turn this on in the change that
 * registers the shared mailbox's Trash, Archive, Junk and other folders; the rights
 * (t on the source, i on the destination) and the once-per-mailbox confirmation
 * (EmailConnectorMailBoxDrawer#whenSharedMailboxConfirmed) are already wired behind it.
 * One thing to do in that same change: the confirmation is asked where the mailbox
 * drawer RECEIVES delete-email / archive-email, and the mail drawer
 * (EmailConnectorMailBoxDrawerListItemDetail) drops the mail on the same events at
 * once -- so a Cancel there would leave it showing a mail that was not deleted. Ask
 * where the event is emitted instead (the row menu, the swipe, the reader toolbar, the
 * bulk toolbar), as the permanent delete does.
 *
 * Starring is not behind this switch and stays absent in phase 1 whatever w says:
 * every star control is INBOX-only, because PATCH /email-box/starred takes no folder
 * and pushes \Flagged by UID through the user's OWN inbox. Offering it on a shared
 * mailbox needs that endpoint to take a folder and the w guard in front of it.
 */
export const DELEGATED_MOVE_OUT_ENABLED = false;

/**
 * What a share's rights let the user do in the mailbox, as the mailbox drawer offers
 * it -- the one rule the drawer's controls and the settings' description of a share
 * both read, so the settings can never promise what the drawer does not offer:
 * reading always, read/unread with s, taking mail out only with t AND the phase-2
 * switch. Starring is not listed: it is absent from shared mailboxes in phase 1 (see
 * DELEGATED_MOVE_OUT_ENABLED).
 *
 * @param {Object} affordances the share's affordances, as the server names them
 * @returns {Object} {markRead, moveOut}
 */
export function sharedMailboxCapabilities(affordances) {
  return {
    markRead: !!affordances?.markRead,
    moveOut: DELEGATED_MOVE_OUT_ENABLED && !!affordances?.delete,
  };
}
