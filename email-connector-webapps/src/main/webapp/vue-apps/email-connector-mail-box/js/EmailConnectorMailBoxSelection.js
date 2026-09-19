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

// A multi-selection names each message by its folder AND its IMAP UID (EXO-90416). A
// UID only numbers a message within its folder, and a list of search results holds
// several folders: keyed by the number alone, ticking ARCHIVE:5 ticked INBOX:5 beside
// it, and a bulk action then reached both. In a folder's own listing the two keys are
// one-to-one, so nothing changes there.

/**
 * The selection key of a message.
 *
 * @param {Object} message a listed message, or {mailRemoteId, folder}
 * @returns {String} `<folder>:<uid>`, the folder INBOX when the row does not say
 */
export function selectionKey(message) {
  return `${message.folder || 'INBOX'}:${message.mailRemoteId}`;
}

/**
 * The folder and the UID a selection key names.
 *
 * @param {String} key a selection key (selectionKey)
 * @returns {Object} {folder, id}, the id a Number
 */
export function parseSelectionKey(key) {
  const separator = String(key).lastIndexOf(':');
  return { folder: String(key).slice(0, separator), id: Number(String(key).slice(separator + 1)) };
}

/**
 * The UIDs of a selection, grouped by the folder they are numbered in, in selection
 * order -- one request, or one event, per folder.
 *
 * @param {Array<String>} keys the selection keys
 * @returns {Array} [folder, ids] pairs
 */
export function selectionByFolder(keys) {
  const groups = new Map();
  (keys || []).forEach(key => {
    const { folder, id } = parseSelectionKey(key);
    groups.set(folder, (groups.get(folder) || []).concat(id));
  });
  return Array.from(groups.entries());
}
