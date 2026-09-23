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

// EXO-90421 -- in the full-screen mailbox, a mail (the selection, a search hit) dragged
// onto an entry of the folder column. Native HTML5 drag and drop, no library: what is
// dragged is a set of UIDs and the folder they are numbered in, and what a drop does is
// exactly what the existing action does -- "Move to..." (move-email), Delete
// (delete-email), "Mark as spam" (junk-email), or the category assignment -- with the
// same rules for which mail may go where. Nothing here talks to the server.

import { canDelete, canMarkAsJunk, canMoveOutOf, groupEmailsByThread, isListedFolder, moveTargets, threadIdsInFolder } from './EmailConnectorMailBoxService.js';
import { selectionByFolder, selectionKey } from './EmailConnectorMailBoxSelection.js';

// The type the dragged payload is written under: a drop that does not carry it (a file,
// a link, a text from another page) is not a mail and is ignored.
export const DRAG_MIME = 'application/x-exo-email-move';

// The folders a drop hands to their own action rather than to "Move to...", with the
// event that action emits: dropping on the Trash deletes, on the Spam marks as spam.
const ACTION_FOLDERS = { TRASH: 'delete-email', JUNK: 'junk-email' };

/**
 * Whether mail of a folder may be dragged at all: the rows "Move to...", Delete and
 * "Mark as spam" are offered on -- not a draft, not a Trash or Spam row -- and not a
 * row the server has not listed yet (refreshPending), whose UID is a placeholder; never
 * an All Mail hit (isListedFolder), which the server moves nothing out of and categorizes
 * nothing in, so any drop of one could only fail.
 *
 * @param {String} folder the folder the row is numbered in; blank means INBOX
 * @param {Object} email the row, when there is one
 * @returns {Boolean} true when it may be dragged
 */
export function canDragFrom(folder, email) {
  return canMoveOutOf(folder) && isListedFolder(folder) && !email?.draftLocalId && !email?.refreshPending;
}

/**
 * What dragging a list row carries. A row of a running selection carries the whole
 * selection -- when it sits in one folder, as "Move to..." requires (EXO-90416); any
 * other row carries itself: its conversation's messages in the row's own folder
 * (threadIdsInFolder), never the conversation's raw ids, which span folders.
 *
 * @param {Object} row {email, thread, selectMode, selectedEmails}: the row's message,
 *   its conversation, whether a selection runs, and the selection's keys
 * @returns {Object} {folder, ids}, or null when the row may not be dragged
 */
export function dragPayloadOfRow({ email, thread, selectMode, selectedEmails }) {
  const folder = email?.folder || 'INBOX';
  if (!email || !canDragFrom(folder, email)) {
    return null;
  }
  const ids = threadIdsInFolder(email, thread);
  if (dragsSelection({ email, thread, selectMode, selectedEmails })) {
    const groups = selectionByFolder(selectedEmails);
    if (groups.length !== 1 || !canDragFrom(groups[0][0])) {
      return null;
    }
    return { folder: groups[0][0], ids: groups[0][1] };
  }
  return { folder, ids };
}

/**
 * Whether dragging a row drags the running selection: the row is selected -- every
 * message it gathers in its folder (threadIdsInFolder), the rule the row's checkbox reads.
 *
 * @param {Object} row {email, thread, selectMode, selectedEmails}, as dragPayloadOfRow takes it
 * @returns {Boolean} true when the selection is what moves
 */
function dragsSelection({ email, thread, selectMode, selectedEmails }) {
  const folder = email.folder || 'INBOX';
  return !!selectMode && threadIdsInFolder(email, thread)
    .every(mailRemoteId => (selectedEmails || []).includes(selectionKey({ mailRemoteId, folder })));
}

/**
 * How many ROWS a drag of a list row moves, which is what its picture says: the user
 * picks conversations, not messages, so two selected conversations of three messages
 * read "Move 2 emails" (the payload still carries all their messages). The selection's
 * rows are its conversations among the listed messages, grouped as the list groups them
 * (groupEmailsByThread); a row dragged alone is one.
 *
 * @param {Object} row {email, thread, selectMode, selectedEmails, emails}: dragPayloadOfRow's
 *   argument, plus the listed messages
 * @returns {Number} the number of rows dragged, at least 1
 */
export function draggedRowCount({ email, thread, selectMode, selectedEmails, emails }) {
  if (!email || !dragsSelection({ email, thread, selectMode, selectedEmails })) {
    return 1;
  }
  const selected = (emails || []).filter(message => (selectedEmails || []).includes(selectionKey(message)));
  return Math.max(groupEmailsByThread(selected).length, 1);
}

/**
 * What dragging a search hit carries: the hit alone, in the folder the server found it.
 *
 * @param {Object} result the hit ({mailRemoteId, folder})
 * @returns {Object} {folder, ids}, or null when it may not be dragged
 */
export function dragPayloadOfSearchHit(result) {
  const folder = result?.folder || 'INBOX';
  if (!result || !canDragFrom(folder)) {
    return null;
  }
  return { folder, ids: [result.mailRemoteId] };
}

/**
 * What dropping the dragged mail on a folder of the column does, or null when it may not
 * be dropped there. The Trash and the Spam take the mail their actions take (the same
 * rows, from any other folder) when the mailbox lists them; every other folder is a
 * "Move to..." target exactly when the picker would offer it (moveTargets): Inbox,
 * Archive and the synced custom folders, never the source, never Drafts, Sent or All Mail.
 *
 * @param {Array} folders the folders the column lists
 * @param {Object} drag the dragged {folder, ids}, null when nothing is dragged
 * @param {String} target the folder's key
 * @returns {Object} {event, args}: the $root event to emit and its arguments, or null
 */
export function folderDropAction(folders, drag, target) {
  if (!drag?.ids?.length || !target || target === drag.folder) {
    return null;
  }
  if (ACTION_FOLDERS[target]) {
    const listed = (folders || []).some(folder => folder.key === target && !folder.missing);
    // Each action asks its own destination (EXO-90548): a shared mailbox that shares no
    // Trash takes no drop on the Trash, whatever it shares for spam, and the reverse.
    const allowed = target === 'TRASH' ? canDelete(drag.folder) : canMarkAsJunk(drag.folder);
    return listed && allowed ? { event: ACTION_FOLDERS[target], args: [drag.ids, drag.folder] } : null;
  }
  return moveTargets(folders, drag.folder).some(folder => folder.key === target)
    ? { event: 'move-email', args: [drag.ids, target, drag.folder] }
    : null;
}

/**
 * Whether the drag under way may be dropped on a folder of the column.
 *
 * @param {Array} folders the folders the column lists
 * @param {Object} drag the dragged {folder, ids}
 * @param {String} target the folder's key
 * @returns {Boolean} true when a drop there does something
 */
export function canDropOn(folders, drag, target) {
  return !!folderDropAction(folders, drag, target);
}

/**
 * Whether a drag event carries mail of this mailbox -- not a file, a link or a text.
 *
 * @param {DragEvent} event the event
 * @returns {Boolean} true when the payload type is there
 */
export function hasDragPayload(event) {
  return Array.from(event?.dataTransfer?.types || []).includes(DRAG_MIME);
}

/**
 * What the picture following the pointer says: how many rows -- conversations, or
 * search hits -- are dragged (draggedRowCount), not how many messages they hold.
 *
 * @param {Number} count how many rows
 * @param {Function} t the translation function ($t)
 * @returns {String} "Move 1 email", "Move 3 emails"
 */
export function dragLabel(count, t) {
  return count === 1
    ? t('emailConnector.mailBox.list.drawer.drag.email')
    : t('emailConnector.mailBox.list.drawer.drag.emails', { 0: count });
}

/**
 * Builds the picture that follows the pointer: a small chip saying how many messages
 * move. It has to be in the page, and rendered, when the browser takes the picture
 * (Safari draws nothing otherwise); it is taken out on the next frame.
 *
 * @param {String} label what the chip says
 * @returns {HTMLElement} the chip, already in the page
 */
export function buildDragImage(label) {
  const chip = document.createElement('div');
  chip.textContent = label;
  Object.assign(chip.style, {
    position: 'fixed',
    top: '0',
    left: '0',
    zIndex: '-1',
    pointerEvents: 'none',
    padding: '6px 12px',
    borderRadius: '16px',
    font: '14px sans-serif',
    whiteSpace: 'nowrap',
    color: '#fff',
    background: 'var(--allPagesPrimaryColor, #578dc9)',
  });
  document.body.appendChild(chip);
  const remove = () => chip.remove();
  if (window.requestAnimationFrame) {
    window.requestAnimationFrame(() => window.setTimeout(remove, 0));
  } else {
    window.setTimeout(remove, 0);
  }
  return chip;
}

/**
 * Starts a drag: a move, the payload written under DRAG_MIME, the counting chip as its
 * picture. The picture is a nicety, and a browser that refuses it keeps its own.
 *
 * @param {DragEvent} event the dragstart event
 * @param {Object} payload the dragged {folder, ids}
 * @param {String} label what the chip says
 * @returns {void}
 */
export function startDrag(event, payload, label) {
  const transfer = event.dataTransfer;
  if (!transfer) {
    return;
  }
  transfer.effectAllowed = 'move';
  transfer.setData(DRAG_MIME, JSON.stringify(payload));
  try {
    transfer.setDragImage(buildDragImage(label), 0, 0);
  } catch (e) {
    // The browser's own picture of the row, then.
  }
}
