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

// The owner's folder-by-folder choice (EXO-90556), the rules the "Folders and access"
// drawer and the invitation's folder choice both follow. Stateless: the two screens are
// separate bundles' components.

/**
 * The choices after the owner chose an access for a folder: that folder, and every
 * folder inside it, get it -- a choice on a parent applies to its children by default,
 * and each child can still be changed afterwards (PO decision P-3). "Inside" is by full
 * name and the folder's own delimiter, so a folder under a container that cannot hold
 * mail (not listed) is reached too; INBOX, the share itself, is never changed here.
 *
 * @param {Array} folders the folders, as the server lists them
 * @param {Object} choices the access chosen per folder, by full name
 * @param {String} folder the folder chosen for
 * @param {String} access READER, EDITOR or NONE
 * @returns {Object} the new choices
 */
export function chooseWithDescendants(folders, choices, folder, access) {
  const next = Object.assign({}, choices, { [folder]: access });
  const chosen = (folders || []).find(candidate => candidate.folder === folder);
  const prefix = `${folder}${chosen?.delimiter || '/'}`;
  (folders || []).filter(candidate => candidate.editable && candidate.folder.startsWith(prefix))
    .forEach(candidate => next[candidate.folder] = access);
  return next;
}

/**
 * What a save sends: every role folder (Sent, Archive, Trash, Spam) with an access -- so
 * the share records all four, and a folder renamed in another mail application gets its
 * grant again -- and every other folder whose choice differs from what the server said.
 * A folder with nothing chosen is left alone.
 *
 * @param {Array} folders the folders, as the server lists them
 * @param {Object} initial the access the server said per folder, by full name
 * @param {Object} choices the access chosen per folder, by full name
 * @returns {Array} the changes, [{folder, access}]
 */
export function changesOf(folders, initial, choices) {
  return (folders || [])
    .filter(folder => folder.editable && choices[folder.folder])
    .filter(folder => folder.role || choices[folder.folder] !== initial[folder.folder])
    .map(folder => ({ folder: folder.folder, access: choices[folder.folder] }));
}

/**
 * Whether the owner changed anything at all.
 *
 * @param {Array} folders the folders, as the server lists them
 * @param {Object} initial the access the server said per folder, by full name
 * @param {Object} choices the access chosen per folder, by full name
 * @returns {Boolean} true when a save would change something
 */
export function hasChanges(folders, initial, choices) {
  return (folders || []).some(folder => folder.editable && choices[folder.folder] && choices[folder.folder] !== initial[folder.folder]);
}
