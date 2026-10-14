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
 * Moving mail OUT of a shared mailbox -- Delete (into its Trash), Archive, "Mark as spam"
 * and "Move to..." -- is offered on a folder whose own letters carry both t and e
 * (EXO-90548): the move is a copy, then \Deleted and an expunge, and a server holding the
 * source without e keeps the original (silently, on Dovecot). The server computes that
 * as the "moveOut" affordance of each folder. The phase-1 switch that kept these off is
 * gone: the shared mailbox's own Trash, Archive and Spam are registered now, and every
 * destination is inside it.
 *
 * The star is decided per folder by canStar (EmailConnectorMailBoxService): w on that
 * folder, never in its Trash, Spam or Drafts (EXO-90550).
 */

/**
 * What a share's rights let the user do in the mailbox, as the mailbox drawer offers
 * it -- the one rule the drawer's controls and the settings' description of a share
 * both read, so the settings can never promise what the drawer does not offer:
 * reading always, read/unread with s, taking mail out only with t AND e (the server's
 * "moveOut"), and marking as favorite with w -- the owner's favorite too (EXO-90550). The
 * star control itself is canStar's, per folder (see above).
 *
 * @param {Object} affordances a folder's (or a share's) affordances, as the server names them
 * @returns {Object} {markRead, moveOut, star}
 */
export function sharedMailboxCapabilities(affordances) {
  return {
    markRead: !!affordances?.markRead,
    moveOut: !!affordances?.moveOut,
    star: !!affordances?.star,
  };
}
