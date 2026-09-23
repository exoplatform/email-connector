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

// The mailboxes other people shared with the user, as the mail drawer's switcher
// offers them (delegation plan 7.3), and the ONE place the interface asks what a
// shared mailbox lets the user do (7.5). Reached like the rest of the service, through
// this.$emailConnectorMailBoxService, which re-exports this module.
//
// "Absent right, absent control": every question below answers true for a folder of
// the user's own mailbox, so a caller can put it in front of any control without
// changing what that control does at home. A folder of a shared mailbox is answered
// from the letters the server last granted there -- the same letters the backend's
// write guards check (EmailDelegationService#checkRight), so the chrome and the guard
// cannot disagree about a right, only about how fresh it is.

// The rules themselves -- what a folder's rights allow -- live in a module with no state,
// which the settings bundle imports too.
import { sharedMailboxCapabilities } from './EmailConnectorSharedMailboxRules.js';

export { sharedMailboxCapabilities };

// The switcher's state: the entries the server answered, and the one the drawer is
// in (null: the user's own mailbox). Observable, so the header, the list rows and the
// composer follow a switch without being told. The platform's Vue is a global; a
// context without it (a module loaded outside the portal) gets a plain object.
const sharedMailboxes = typeof Vue !== 'undefined' && Vue.observable
  ? Vue.observable({ entries: [], current: null })
  : { entries: [], current: null };

// The mailboxes whose destructive actions were confirmed for this browser session.
// sessionStorage keeps the answer across a page reload in the same tab, as "this
// session" reads to a user; an unavailable storage keeps it in memory instead.
const CONFIRMED_STORAGE_KEY = 'emailConnector.sharedMailbox.confirmed';

const confirmedInMemory = new Set();

/**
 * The switcher's observable state: {entries, current}.
 *
 * @returns {Object} the state
 */
export function sharedMailboxState() {
  return sharedMailboxes;
}

/**
 * Reads the shared mailboxes the user can switch to, and keeps them as the switcher's
 * entries. A read that fails leaves the entries and the current mailbox as they were:
 * a failed read is not the server saying a share is gone, and the drawer leaves a
 * shared mailbox only on the server's word (see its loadEmailBox). Before the first
 * successful read the entries are empty, so the switcher is hidden rather than stale.
 *
 * @returns {Promise<Array>} the entries as they now stand
 */
export function loadSharedMailboxes() {
  // Started inside the chain, so that even a request that cannot be made at all ends
  // in the catch below rather than failing the drawer's opening.
  return Promise.resolve().then(() => fetch('/email-connector/rest/user-email-setting/delegations/mailboxes', {
    credentials: 'include',
    cache: 'no-store',
    method: 'GET',
  })).then(resp => {
    if (!resp?.ok) {
      throw new Error('Error when reading the mailboxes shared with you');
    }
    return resp.json();
  }).then(entries => {
    sharedMailboxes.entries = Array.isArray(entries) ? entries : [];
    // The mailbox the user is in follows its fresh entry -- rights narrowed since the
    // switch show in the band at once -- and a share left or revoked meanwhile takes
    // the user back to their own mailbox.
    if (sharedMailboxes.current) {
      sharedMailboxes.current = sharedMailboxes.entries
        .find(entry => entry.delegationId === sharedMailboxes.current.delegationId) || null;
    }
    return sharedMailboxes.entries;
  }).catch(() => sharedMailboxes.entries);
}

/**
 * Makes one shared mailbox the current one, or the user's own when null.
 *
 * @param {Object} entry a switcher entry, or null
 * @returns {void}
 */
export function setCurrentSharedMailbox(entry) {
  sharedMailboxes.current = entry || null;
}

/**
 * The switcher entry with a given delegation id.
 *
 * @param {Number|String} delegationId the id, as a deep link carries it
 * @returns {Object} the entry, or null
 */
export function sharedMailboxById(delegationId) {
  return sharedMailboxes.entries.find(entry => String(entry.delegationId) === String(delegationId)) || null;
}

/**
 * The shared mailbox a folder belongs to.
 *
 * @param {String} folder a folder key, CUSTOM:<id> for a shared mailbox's
 * @returns {Object} the switcher entry, or null for a folder of the user's own mailbox
 */
export function sharedMailboxOfFolder(folder) {
  if (!folder || !String(folder).startsWith('CUSTOM:')) {
    return null;
  }
  return sharedMailboxes.entries.find(entry => entry.folderKey === folder
                                               || (entry.folders || []).some(shared => shared.key === folder)) || null;
}

/**
 * The affordances of one folder of a shared mailbox: its own, as the server computed them
 * from the delegate's letters on THAT folder (EXO-90548), and the share's for its INBOX.
 *
 * @param {Object} entry the switcher entry
 * @param {String} folder a folder key of that mailbox
 * @returns {Object} the affordances
 */
function folderAffordances(entry, folder) {
  const shared = (entry?.folders || []).find(candidate => candidate.key === folder);
  return shared ? shared.affordances : entry?.affordances;
}

/**
 * The role a folder of a shared mailbox has in its owner's mailbox -- TRASH, JUNK,
 * DRAFTS, ARCHIVE or SENT -- as the server discovered it (EXO-90548).
 *
 * @param {String} folder a folder key
 * @returns {String} the role, or null for the shared INBOX, a folder with no role, or a
 *          folder of the user's own mailbox
 */
export function sharedFolderRole(folder) {
  const entry = sharedMailboxOfFolder(folder);
  return (entry?.folders || []).find(shared => shared.key === folder)?.role || null;
}

/**
 * Whether an action of a shared mailbox has somewhere to file into: that mailbox shares a
 * folder of the role -- its Trash for a delete, its Archive for an archive, its Spam for
 * "mark as spam" -- that the user may read and insert into (i, the "moveTarget"
 * affordance), as the server requires (EmailBoxService#checkDelegatedMove). A missing
 * destination hides only its own action (EXO-90548): a share of the Inbox alone offers
 * none of the three, however wide the letters on that Inbox. True for a folder of the
 * user's own mailbox.
 *
 * @param {String} folder a folder key
 * @param {String} role TRASH, ARCHIVE or JUNK
 * @returns {Boolean} true when the action's destination exists and takes mail
 */
export function sharedMailboxCanFileInto(folder, role) {
  const entry = sharedMailboxOfFolder(folder);
  return !entry || (entry.folders || []).some(shared => shared.role === role && shared.readable && !!shared.affordances?.moveTarget);
}

/**
 * The shared mailbox of a folder when its owner shares the Inbox alone and the user may
 * otherwise take mail out of it -- where a hint says why Delete and Archive are absent
 * and who can change that (EXO-90548).
 *
 * @param {String} folder a folder key
 * @param {Boolean} mayTakeOut whether mail could be taken out of that folder were its
 *        destinations shared (the letters allow it, and no Delete or Archive stands)
 * @returns {Object} the switcher entry, or null when no hint belongs there
 */
export function inboxOnlyShareOf(folder, mayTakeOut) {
  const entry = sharedMailboxOfFolder(folder);
  return entry?.inboxOnly && mayTakeOut ? entry : null;
}

/**
 * Whether a folder belongs to a mailbox somebody shared with the user.
 *
 * @param {String} folder a folder key
 * @returns {Boolean} true for a shared mailbox's folder
 */
export function isSharedMailboxFolder(folder) {
  return !!sharedMailboxOfFolder(folder);
}

/**
 * Whether the rights on a folder unlock one control, by the name the server gives it
 * (markRead, star, moveTarget, delete, createFolder...). Always true for the user's own
 * folders.
 *
 * @param {String} folder a folder key
 * @param {String} affordance the control's name
 * @returns {Boolean} true when the control may be offered there
 */
export function sharedMailboxAllows(folder, affordance) {
  const entry = sharedMailboxOfFolder(folder);
  return !entry || !!folderAffordances(entry, folder)?.[affordance];
}

/**
 * Whether mail may be moved out of a folder -- deleted, archived, reported as spam or
 * filed elsewhere. Always true for the user's own folders; for a shared mailbox, t and e
 * on that folder.
 *
 * @param {String} folder a folder key
 * @returns {Boolean} true when those actions may be offered there
 */
export function sharedMailboxAllowsMoveOut(folder) {
  const entry = sharedMailboxOfFolder(folder);
  return !entry || sharedMailboxCapabilities(folderAffordances(entry, folder)).moveOut;
}


/**
 * Whether a destructive action in a shared mailbox was already confirmed this session.
 *
 * @param {Object} entry the switcher entry
 * @returns {Boolean} true when it need not be asked again
 */
export function isDestructiveActionConfirmed(entry) {
  if (!entry) {
    return true;
  }
  const key = String(entry.delegationId);
  if (confirmedInMemory.has(key)) {
    return true;
  }
  try {
    return JSON.parse(window.sessionStorage.getItem(CONFIRMED_STORAGE_KEY) || '[]').includes(key);
  } catch (e) {
    return false;
  }
}

/**
 * Remembers, for this session, that destructive actions in a shared mailbox need not
 * be confirmed again.
 *
 * @param {Object} entry the switcher entry
 * @returns {void}
 */
export function rememberDestructiveActionConfirmed(entry) {
  if (!entry) {
    return;
  }
  const key = String(entry.delegationId);
  confirmedInMemory.add(key);
  try {
    const confirmed = JSON.parse(window.sessionStorage.getItem(CONFIRMED_STORAGE_KEY) || '[]');
    if (!confirmed.includes(key)) {
      confirmed.push(key);
      window.sessionStorage.setItem(CONFIRMED_STORAGE_KEY, JSON.stringify(confirmed));
    }
  } catch (e) {
    // No storage: the memory above holds it until the page is left.
  }
}
