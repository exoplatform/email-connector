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

// EXO-90839 -- the user's own folders as a tree. The server lists every folder flat,
// each with its full name (path) and the mail server's hierarchy delimiter; the tree is
// read off those two, the way the mail server itself nests them, so a folder made as a
// sub-folder in another mail client is nested here too. One spelling for every screen
// that lists folders: the full-screen folder column, the 3-dots menu, the settings
// Folders drawer, the "Move to..." picker.

// Where a viewer's collapsed folders are remembered, per browser: a convenience, so a
// collapsed branch stays collapsed across the column, the menu and a reload.
const COLLAPSED_STORAGE_KEY = 'emailConnector.folders.collapsed';

/**
 * The folders in tree order, each as a row: the folders that are not the user's own
 * (the built-ins, a shared mailbox's) first and flat, in the order given; then the
 * user's own folders, each followed by the folders inside it, siblings by name. A
 * folder's parent is the nearest folder of the list whose full name is a prefix of its
 * own up to a delimiter -- so when an intermediate folder is not in the list (not
 * mirrored, or a container the server says cannot hold mail), the folder hangs from the
 * nearest one that is, or sits at the top with its whole path as its name.
 *
 * @param {Array} folders the folders as the server lists them ({key, type, path, delimiter, displayName, ...})
 * @param {Array} namespaceFolders every folder of the user's, mirrored or not, which
 *        decides whether INBOX is their namespace (inboxIsNamespace); the folders when omitted
 * @returns {Array} the rows, {folder, depth, parentKey, ancestorKeys, hasChildren, showPath, pathLabel}
 */
export function buildFolderTree(folders, namespaceFolders) {
  const list = (folders || []).filter(folder => !!folder);
  const dropInbox = inboxIsNamespace(namespaceFolders || list);
  const own = list.filter(isTreeFolder);
  const byPath = new Map(own.map(folder => [folder.path, folder]));
  const children = new Map();
  const roots = [];
  own.forEach(folder => {
    const parent = nearestAncestor(folder, byPath);
    if (parent) {
      if (!children.has(parent.key)) {
        children.set(parent.key, []);
      }
      children.get(parent.key).push(folder);
    } else {
      roots.push(folder);
    }
  });
  const rows = list.filter(folder => !isTreeFolder(folder)).map(folder => ({
    folder,
    depth: 0,
    parentKey: null,
    ancestorKeys: [],
    hasChildren: false,
    showPath: false,
    pathLabel: '',
  }));
  appendRows(rows, roots, children, 0, [], dropInbox);
  return rows;
}

/**
 * The rows a viewer sees: those with no collapsed folder above them.
 *
 * @param {Array} rows the rows, from buildFolderTree
 * @param {Object} collapsed the collapsed folders, by key
 * @returns {Array} the visible rows
 */
export function visibleFolderRows(rows, collapsed) {
  return (rows || []).filter(row => !row.ancestorKeys.some(key => collapsed?.[key]));
}

/**
 * The keys of the folders of the list inside a folder, at any depth -- what a move
 * may not go into, and what a delete takes along.
 *
 * @param {Array} folders the folders as the server lists them
 * @param {Object} folder the folder
 * @returns {Array} the keys of the folders inside it, the folder itself excluded
 */
export function descendantKeys(folders, folder) {
  if (!folder?.path || !folder.delimiter) {
    return [];
  }
  const prefix = `${folder.path}${folder.delimiter}`;
  return (folders || []).filter(candidate => isTreeFolder(candidate) && candidate.path.startsWith(prefix))
    .map(candidate => candidate.key);
}

/**
 * The collapsed folders this viewer left collapsed, or none when the browser keeps
 * nothing (a private window, blocked storage).
 *
 * @returns {Object} the collapsed folders, by key
 */
export function readCollapsedFolders() {
  try {
    const stored = JSON.parse(window.localStorage.getItem(COLLAPSED_STORAGE_KEY));
    return stored && typeof stored === 'object' ? stored : {};
  } catch (e) {
    return {};
  }
}

/**
 * Collapses or expands one folder, and remembers it for this viewer when the browser
 * lets it.
 *
 * @param {Object} collapsed the collapsed folders, by key
 * @param {String} key the folder's key
 * @returns {Object} the new collapsed folders
 */
export function toggleCollapsedFolder(collapsed, key) {
  const next = Object.assign({}, collapsed);
  if (next[key]) {
    delete next[key];
  } else {
    next[key] = true;
  }
  try {
    window.localStorage.setItem(COLLAPSED_STORAGE_KEY, JSON.stringify(next));
  } catch (e) {
    // Not remembered: the branch stays collapsed until the page is left.
  }
  return next;
}

/**
 * Whether a folder takes part in the tree: one of the user's own, with a full name.
 *
 * @param {Object} folder the folder
 * @returns {Boolean} true for a folder of the user's own
 */
function isTreeFolder(folder) {
  return folder?.type === 'CUSTOM' && !!folder.path;
}

/**
 * The nearest folder of the list a folder lies inside, by its full name cut at each
 * delimiter from the right.
 *
 * @param {Object} folder the folder
 * @param {Map} byPath the user's folders, by full name
 * @returns {Object} the parent, or null at the top
 */
function nearestAncestor(folder, byPath) {
  const delimiter = folder.delimiter;
  if (!delimiter) {
    return null;
  }
  let path = folder.path;
  let cut = path.lastIndexOf(delimiter);
  while (cut > 0) {
    path = path.substring(0, cut);
    if (byPath.has(path)) {
      return byPath.get(path);
    }
    cut = path.lastIndexOf(delimiter);
  }
  return null;
}

/**
 * Appends a level of the tree, siblings by name, each followed by its own folders.
 *
 * @param {Array} rows the rows, appended to
 * @param {Array} level the folders of this level
 * @param {Map} children the folders inside each folder, by its key
 * @param {Number} depth the level's depth
 * @param {Array} ancestorKeys the keys of the folders above this level
 * @param {Boolean} dropInbox whether INBOX is the namespace every folder lives under
 * @returns {void}
 */
function appendRows(rows, level, children, depth, ancestorKeys, dropInbox) {
  level.slice()
    .sort((first, second) => sortName(first, depth, dropInbox).localeCompare(sortName(second, depth, dropInbox), [], { sensitivity: 'base', numeric: true }))
    .forEach(folder => {
      const inside = children.get(folder.key) || [];
      const segments = ownSegments(folder, dropInbox);
      rows.push({
        folder,
        depth,
        parentKey: ancestorKeys.length ? ancestorKeys[ancestorKeys.length - 1] : null,
        ancestorKeys,
        hasChildren: inside.length > 0,
        // A folder at the top whose full name is deeper than one level hangs from a
        // folder the list does not show: its path says where it lives.
        showPath: depth === 0 && segments.length > 1,
        pathLabel: segments.join(' / '),
      });
      appendRows(rows, inside, children, depth + 1, ancestorKeys.concat(folder.key), dropInbox);
    });
}

/**
 * A folder's full name as the user reads it: its segments joined by a spaced slash
 * ("Customers / Acme"), without a leading INBOX when the user's folders show INBOX is the
 * namespace they all live under (see inboxIsNamespace). The one spelling of a folder's
 * path on every screen.
 *
 * @param {Object} folder the folder as the server lists it ({path, delimiter})
 * @param {Array} folders the user's folders it is listed with; without them INBOX is kept
 * @returns {String} the path, or nothing for a folder without one
 */
export function folderPathLabel(folder, folders) {
  return folder?.path ? ownSegments(folder, inboxIsNamespace(folders)).join(' / ') : '';
}

/**
 * Whether INBOX is the namespace the user's own folders live under ("INBOX.Customers" on
 * Courier, Cyrus without the alternate namespace) rather than a folder some of them sit
 * in (a sub-folder made inside the inbox on Dovecot's default layout): true when every
 * one of the user's own folders is inside INBOX.
 *
 * @param {Array} folders the folders as the server lists them
 * @returns {Boolean} true when INBOX is where every folder of the user's lives
 */
export function inboxIsNamespace(folders) {
  const own = (folders || []).filter(isTreeFolder);
  return own.length > 0 && own.every(folder => !!folder.delimiter
    && folder.path.includes(folder.delimiter) && isInboxPath(folder.path.split(folder.delimiter)[0]));
}

/**
 * Whether a full name is a namespace's INBOX itself -- what a folder at the top of a
 * mail server whose folders all live under the inbox has for parent.
 *
 * @param {String} path the full name
 * @returns {Boolean} true for INBOX
 */
export function isInboxPath(path) {
  return (path || '').toUpperCase() === 'INBOX';
}

/**
 * A folder's full name, segment by segment, without a leading INBOX when INBOX is the
 * namespace every folder lives under: saying it would put "INBOX / " before every name.
 *
 * @param {Object} folder the folder
 * @param {Boolean} dropInbox whether INBOX is that namespace (inboxIsNamespace)
 * @returns {Array} the segments, at least one
 */
function ownSegments(folder, dropInbox) {
  if (!folder.delimiter) {
    return [folder.path];
  }
  const segments = folder.path.split(folder.delimiter);
  return dropInbox && segments.length > 1 && isInboxPath(segments[0]) ? segments.slice(1) : segments;
}

/**
 * What siblings are sorted by: the name shown -- the path of a folder at the top that
 * hangs from a folder not listed, else its own name, else its full name.
 *
 * @param {Object} folder the folder
 * @param {Number} depth the folder's depth
 * @param {Boolean} dropInbox whether INBOX is the namespace every folder lives under
 * @returns {String} the name
 */
function sortName(folder, depth, dropInbox) {
  const segments = ownSegments(folder, dropInbox);
  if (depth === 0 && segments.length > 1) {
    return segments.join(' / ');
  }
  return folder.displayName || folder.path || '';
}
