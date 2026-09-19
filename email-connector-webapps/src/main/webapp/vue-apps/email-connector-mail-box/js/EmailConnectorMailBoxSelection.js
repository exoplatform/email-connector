/*
Copyright (C) 2026 eXo Platform SAS.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
*/

// A multi-selection names each message by its FOLDER and by the id that message can be
// named by inside it. Both halves were paid for:
//
// - the folder (EXO-90416), because a UID only numbers a message within its folder and a
//   list of search results holds several: keyed by the number alone, ticking ARCHIVE:5
//   ticked INBOX:5 beside it, and a bulk action then reached both;
// - the id (EXO-90438), because a DRAFT that has never been uploaded to the mail server
//   has no UID at all. Whether drafts are uploaded is an administrator's switch
//   (exo.email.connector.drafts.server.enabled), so on a mailbox with it off every draft
//   carried `mailRemoteId: null` and answered to the one key DRAFTS:null -- ticking one
//   unsent draft lit every other one's checkbox, and a Discard on that selection would
//   have thrown away drafts nobody ticked. Irreversibly: a discard is not a move to the
//   Trash. A draft is keyed by its own LOCAL id instead, which it always has, which is
//   unique, and which is what its own endpoints address it by.
//
// In a folder's own listing of ordinary mail the key is the folder and the UID as before,
// one-to-one with the row; nothing changes there.
//
// Not to be confused with the reader's own row identity (`msgKey` in
// EmailConnectorMailBoxDrawerThreadContent), which looks alike and answers another
// question: that one names a message inside ONE conversation, which deliberately spans
// folders, and never addresses a request. A selection key names a row of ONE listing.
//
// ONE function builds a key and ONE parses it, here, because the two halves were invented
// in two places and a selection whose producers disagree about what a key is is precisely
// the defect both of them were fixing. In particular a key is never sent to the server:
// what travels in a request is a UID (selectionByFolder) or a draft's local id (resolved
// from the rows), and a draft key must never be handed over as either.

/** What marks the id half of a key as a draft's local id rather than as an IMAP UID. */
const DRAFT_PREFIX = 'DRAFT-';

/**
 * The selection key of a message.
 *
 * @param {Object} message a listed message, or {mailRemoteId, draftLocalId, folder}
 * @returns {String} `<folder>:<uid>`, or `<folder>:DRAFT-<local id>` for a draft; the
 *          folder INBOX when the row does not say
 */
export function selectionKey(message) {
  const folder = message?.folder || 'INBOX';
  return message?.draftLocalId
    ? `${folder}:${DRAFT_PREFIX}${message.draftLocalId}`
    : `${folder}:${message?.mailRemoteId}`;
}

/**
 * The folder and the id a selection key names, and which of the two kinds of id it is.
 *
 * @param {String} key a selection key (selectionKey)
 * @returns {Object} {folder, id, draftLocalId}: `id` is the UID, a Number, and null for
 *          a draft key; `draftLocalId` is the draft's local id, and null otherwise
 */
export function parseSelectionKey(key) {
  const text = String(key);
  const separator = text.lastIndexOf(':');
  const folder = text.slice(0, separator);
  const id = text.slice(separator + 1);
  return id.startsWith(DRAFT_PREFIX)
    ? { folder, id: null, draftLocalId: id.slice(DRAFT_PREFIX.length) }
    : { folder, id: Number(id), draftLocalId: null };
}

/**
 * The UIDs of a selection, grouped by the folder they are numbered in, in selection
 * order -- one request, or one event, per folder.
 *
 * A DRAFT key is left out rather than grouped: it names no UID, and every caller of this
 * sends what it gets as one. A draft's own actions (Discard) resolve their rows from the
 * listing and travel by local id; the UID actions -- delete, archive, spam, move,
 * read/unread -- are withheld on a draft upstream, so a folder whose selection is nothing
 * but drafts correctly yields no group at all rather than a group of nulls.
 *
 * @param {Array<String>} keys the selection keys
 * @returns {Array} [folder, ids] pairs
 */
export function selectionByFolder(keys) {
  const groups = new Map();
  (keys || []).forEach(key => {
    const { folder, id } = parseSelectionKey(key);
    if (id === null) {
      return;
    }
    groups.set(folder, (groups.get(folder) || []).concat(id));
  });
  return Array.from(groups.entries());
}
