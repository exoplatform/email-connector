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
import { getWaitingSuggestionEmails, getWaitingSuggestionMails } from '../../email-connector-user-setting/js/EmailConnectorUserSettingService.js';
import { sharedMailboxOfFolder } from './EmailConnectorSharedMailboxes.js';

/**
 * Whether a mail is one of the user's own mailbox: filters and their Automations
 * panel apply there only (EXO-90654). A row of a mailbox somebody shared with the user
 * is someone else's mail.
 *
 * @param {object} email - the mail, as the mailbox lists it
 * @returns {boolean} true for a mail of the user's own mailbox
 */
export function isOwnMailboxMail(email) {
  return !!email && !sharedMailboxOfFolder(email.folder || 'INBOX');
}

/**
 * Whether "Create a filter from this mail" is offered on a mail: a received mail of
 * the user's own mailbox with a sender, never a draft.
 *
 * @param {object} email - the mail
 * @returns {boolean} true when offered
 */
export function canCreateFilterFrom(email) {
  return isOwnMailboxMail(email) && !email.draftLocalId && !!email.sender?.address
    && !['DRAFTS', 'SENT', 'SCHEDULED'].includes(email.folder);
}

// The mails with a suggestion of an assistant waiting for the user (EXO-90669), as the
// server last answered: Message-ID -> how many wait on it. With them, the same mails as
// the "Suggestions" view lists them (EXO-90851) -- one openable copy each, with its
// count --, read together so the view and its count in the folder column never tell two
// stories; and whether the last read failed. Observable, so every list row, the column
// and the view follow a read without being told; the platform's Vue is a global, and a
// context without it gets a plain object.
const waitingSuggestions = typeof Vue !== 'undefined' && Vue.observable
  ? Vue.observable({ mailHeaderIds: {}, mails: [], failed: false })
  : { mailHeaderIds: {}, mails: [], failed: false };

/** How long a read of the waiting suggestions is reused before the list reads them again, in ms. */
const WAITING_SUGGESTIONS_TTL = 60 * 1000;

/** When the waiting suggestions were last read, in ms; 0 before the first read. */
let waitingSuggestionsReadAt = 0;

/** The read on its way, shared by every caller while it is. */
let waitingSuggestionsRead = null;

/** Whether the server said the feature is not there for this user: no read is made again. */
let waitingSuggestionsUnavailable = false;

/**
 * Reads again which of the user's mails have a suggestion waiting for them, at most once
 * a minute unless forced: two requests for the whole mailbox, whatever the rows -- the
 * Message-IDs the rows are marked by, and the mails the "Suggestions" view lists
 * (EXO-90851). A read that fails keeps what was known and says it failed; one the server
 * refuses (the feature off, no mailbox connected) empties it and is not made again.
 *
 * @param {boolean} [force] - true to read even within the minute, after a decision
 * @returns {Promise<void>} resolved once read, or at once when not needed
 */
export function refreshWaitingSuggestions(force) {
  if (waitingSuggestionsUnavailable) {
    return Promise.resolve();
  }
  if (waitingSuggestionsRead) {
    return waitingSuggestionsRead;
  }
  if (!force && Date.now() - waitingSuggestionsReadAt < WAITING_SUGGESTIONS_TTL) {
    return Promise.resolve();
  }
  // Started inside the chain, so that even a request that cannot be made at all ends in
  // the catch below rather than failing the list's rendering.
  waitingSuggestionsRead = Promise.resolve()
    .then(() => Promise.all([getWaitingSuggestionMails(), getWaitingSuggestionEmails()]))
    .then(([ids, mails]) => {
      waitingSuggestions.mailHeaderIds = (ids || []).reduce((known, id) => ({ ...known, [id]: (known[id] || 0) + 1 }), {});
      waitingSuggestions.mails = mails || [];
      waitingSuggestions.failed = false;
    })
    .catch(error => {
      if (error?.status === 403 || error?.status === 404) {
        waitingSuggestionsUnavailable = true;
        waitingSuggestions.mailHeaderIds = {};
        waitingSuggestions.mails = [];
      } else {
        waitingSuggestions.failed = true;
      }
    })
    .finally(() => {
      waitingSuggestionsReadAt = Date.now();
      waitingSuggestionsRead = null;
    });
  return waitingSuggestionsRead;
}

/**
 * The user's mails with a suggestion waiting, as the "Suggestions" view lists them
 * (EXO-90851), as last read: newest first, one openable copy each, with how many
 * suggestions wait on it. Reactive: it follows every read.
 *
 * @returns {Array<object>} the mails
 */
export function waitingSuggestionMails() {
  return waitingSuggestions.mails;
}

/**
 * How many suggestions wait for the user on the mails the "Suggestions" view lists, as
 * last read: the count its entry in the folder column shows (EXO-90851). A suggestion on
 * a mail the view cannot open -- deleted, marked as spam, out of the cache -- is not
 * counted, so the count never promises what the view does not show. Reactive.
 *
 * @returns {number} the number of waiting suggestions, 0 when none
 */
export function waitingSuggestionTotal() {
  return waitingSuggestions.mails.reduce((total, mail) => total + (mail.waitingCount || 0), 0);
}

/**
 * Whether the last read of the waiting suggestions failed: the view then says so.
 *
 * @returns {boolean} true after a failed read, until one succeeds
 */
export function waitingSuggestionsReadFailed() {
  return waitingSuggestions.failed;
}

// Whether the mailbox lists its "Suggestions" view (EXO-90851): a mail opened from it
// shows its Automations panel open, whatever the user last chose for the panel.
// Observable for the same reason as the waiting suggestions above.
const suggestionsView = typeof Vue !== 'undefined' && Vue.observable
  ? Vue.observable({ listed: false })
  : { listed: false };

/**
 * Tells whether the mailbox lists its "Suggestions" view.
 *
 * @param {boolean} listed - true while it does
 * @returns {void}
 */
export function setSuggestionsViewListed(listed) {
  suggestionsView.listed = !!listed;
}

/**
 * Whether the mailbox lists its "Suggestions" view: the Automations panel then shows
 * open on a mail with a suggestion waiting.
 *
 * @returns {boolean} true while it does
 */
export function isSuggestionsViewListed() {
  return suggestionsView.listed;
}

/**
 * How many suggestions wait for the user on the given mails -- a list row's, or every
 * mail of its conversation --, as last read: the server answers a Message-ID once per
 * waiting suggestion. Mails of a mailbox somebody shared with the user count none.
 *
 * @param {Array<object>} emails - the mails
 * @returns {number} the number of waiting suggestions, 0 when none
 */
export function waitingSuggestionCount(emails) {
  const known = waitingSuggestions.mailHeaderIds;
  const seen = new Set();
  return (emails || []).reduce((count, email) => {
    const id = email?.mailHeaderId;
    if (!id || seen.has(id) || !isOwnMailboxMail(email)) {
      return count;
    }
    seen.add(id);
    return count + (known[id] || 0);
  }, 0);
}
