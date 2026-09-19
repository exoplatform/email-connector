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

// Moving through the mail list without the mouse, and keeping the full-screen reader
// on a mail when the one it showed leaves the list (EXO-90414).
//
// The list shows CONVERSATIONS, not messages: groupEmailsByThread is the one definition
// of a row, and everything here walks the same rows the list renders, in the same
// order, so "the next mail" is always the row the user sees below the current one.

import { groupEmailsByThread } from './EmailConnectorMailBoxService.js';

/** One row down the list. */
export const NEXT = 1;

/** One row up the list. */
export const PREVIOUS = -1;

// Where typing happens: an arrow key there moves a caret or a selection, never the list.
const EDITABLE_SELECTOR = 'input, textarea, select, [contenteditable=""], [contenteditable="true"], [role="textbox"], [role="combobox"]';

// A popup of its own that the arrow keys already drive (a menu, a list of options, a
// dialog): the key is its, whether the focus is inside it or it is merely on screen.
const POPUP_SELECTOR = '[role="menu"], [role="listbox"], [role="dialog"], .v-menu__content';
const OPEN_POPUP_SELECTOR = '.v-menu__content.menuable__content__active, .v-dialog--active';

// How long the arrow keys wait for the user to stop before opening the row they reached
// in the full-screen reader. The focus and the highlight move at once; the reader only
// fetches the row the user stops on, so a held key does not open -- mark read, count as
// read -- every conversation it passes.
export const KEY_OPEN_DELAY_MS = 150;

// The drawer's right-hand pane. In the wide layout it holds the reader, where the
// arrow keys scroll the message being read, as they do in any page.
const READER_PANE_SELECTOR = '.drawerContent';

/**
 * Whether the reader can be opened on a listed row: not one a move or an Undo put
 * there before the server listed it (it carries a UID the server has replaced, and the
 * row itself refuses the click), and not a draft, which the reader does not open by
 * UID -- a draft goes back to the composer, and that is the user's call, never a key
 * press passing over it.
 *
 * @param {Object} email the row's latest message
 * @returns {Boolean} true when the reader may open it
 */
export function isOpenableRow(email) {
  return !!email && !email.refreshPending && !email.draftLocalId;
}

/**
 * Whether a row may take the keyboard focus in the narrow layout, where a key only
 * moves the focus and Enter decides: every row the list lets the user act on.
 *
 * @param {Object} email the row's latest message
 * @returns {Boolean} true when the row may be focused
 */
export function isFocusableRow(email) {
  return !!email && !email.refreshPending;
}

/**
 * The list's rows, built exactly as the list builds them.
 *
 * @param {Array} emails the listed messages, newest first
 * @returns {Array} the conversations, as groupEmailsByThread returns them
 */
export function threadRows(emails) {
  return groupEmailsByThread(emails || []);
}

/**
 * The index of the row holding a message: by IMAP UID, within the folder when both
 * sides say which (a UID only numbers a message inside one folder).
 *
 * @param {Array} threads the rows
 * @param {Object} email the message, or null
 * @returns {Number} the row's index, or -1 when it is not listed
 */
export function threadIndexOf(threads, email) {
  if (!email) {
    return -1;
  }
  return threads.findIndex(thread => thread.emails.some(message => message === email
    || (message.mailRemoteId === email.mailRemoteId
      && (!message.folder || !email.folder || message.folder === email.folder))));
}

/**
 * The first row the reader can open, which is what the full-screen reader shows when
 * it opens with nothing chosen yet.
 *
 * @param {Array} threads the rows
 * @returns {Object} the row, or null when the list holds none
 */
export function firstOpenableThread(threads) {
  return threads.find(thread => isOpenableRow(thread.latest)) || null;
}

/**
 * The nearest row in one direction that a predicate accepts, stepping over the others.
 *
 * @param {Array} threads the rows
 * @param {Number} fromIndex where to start from, excluded
 * @param {Number} step NEXT or PREVIOUS
 * @param {Function} accept row's latest message => whether the row may be landed on
 * @returns {Object} the row, or null at the end of the list
 */
export function adjacentThread(threads, fromIndex, step, accept = isOpenableRow) {
  for (let index = fromIndex + step; index >= 0 && index < threads.length; index += step) {
    if (accept(threads[index].latest)) {
      return threads[index];
    }
  }
  return null;
}

/**
 * The row the reader moves to once the one it showed has left the list (deleted,
 * archived, reported as spam, moved, restored or purged): the row that took its place,
 * that is the one listed after it; the one before it when it was the last; none when
 * the list has nothing left to open.
 *
 * Read off the list as it was and the list as it is, rather than off the ids the action
 * named, because what leaves the listing is the listing's own business (a move is keyed
 * by folder, a bulk action may leave part of a conversation behind): a row still listed
 * after the action is a row still there. The opened conversation itself is the first
 * candidate for that reason -- a selection that took only some of its messages leaves
 * it listed, on its newest remaining message.
 *
 * @param {Array} threadsBefore the rows before the action
 * @param {Array} threadsAfter the rows after it
 * @param {Object} openedEmail the message the reader was showing
 * @returns {Object} the row to open, from threadsAfter, or null
 */
export function threadTakingThePlaceOf(threadsBefore, threadsAfter, openedEmail) {
  const index = threadIndexOf(threadsBefore, openedEmail);
  if (index < 0) {
    return null;
  }
  const remaining = new Map(threadsAfter.map(thread => [thread.threadId, thread]));
  const survivor = thread => {
    const after = remaining.get(thread.threadId);
    return after && isOpenableRow(after.latest) ? after : null;
  };
  for (let candidate = index; candidate < threadsBefore.length; candidate++) {
    const after = survivor(threadsBefore[candidate]);
    if (after) {
      return after;
    }
  }
  for (let candidate = index - 1; candidate >= 0; candidate--) {
    const after = survivor(threadsBefore[candidate]);
    if (after) {
      return after;
    }
  }
  return null;
}

/**
 * The list move a key press asks for, or 0 when the key is not the list's to take.
 *
 * Only a bare Up or Down: with a modifier it is a shortcut of the browser or of the
 * system (Shift extends a text selection, Alt scrolls, Ctrl and Cmd jump). Never while
 * the user types -- the search field, a recipient, the composer, CKEditor -- nor while
 * a menu or a dialog is open, whose own items the arrows walk. A key someone already
 * handled is left alone too.
 *
 * @param {KeyboardEvent} event the key press
 * @returns {Number} NEXT, PREVIOUS or 0
 */
export function navigationStep(event) {
  if (!event || event.defaultPrevented || event.isComposing
      || event.altKey || event.ctrlKey || event.metaKey || event.shiftKey) {
    return 0;
  }
  const step = (event.key === 'ArrowDown' && NEXT) || (event.key === 'ArrowUp' && PREVIOUS) || 0;
  if (!step) {
    return 0;
  }
  const target = event.target;
  if (target?.isContentEditable || target?.closest?.(EDITABLE_SELECTOR) || target?.closest?.(POPUP_SELECTOR)) {
    return 0;
  }
  if (document.querySelector(OPEN_POPUP_SELECTOR)) {
    return 0;
  }
  return step;
}

/**
 * The shared behaviour of the two drawers that show the mail list beside the reader --
 * the mailbox drawer, and the mail drawer once expanded -- so both move the same way.
 *
 * The component provides:
 * - `email` (data): the message the reader shows, or null;
 * - `expanded` (data): whether the wide layout is on screen;
 * - `navigationEmails` (computed): the listed messages, as the list receives them;
 * - `canNavigateList` (computed): whether the arrow keys drive the list right now;
 * - `openListedEmail(row)` (method): opens a listed message in the reader, the way a
 *   click on its row does;
 * - `emailRequest` (plain field): the reader's request generation (EXO-90412), moved
 *   on by every opening of the reader and by every leave for the placeholder, so a
 *   delayed arrow-key opening can tell it was overtaken;
 * - `navigationList()` (method): the list content component on screen, or null.
 */
export default {
  created() {
    // Whether the last click in the drawer landed in the reader pane: plain, not
    // reactive -- nothing renders it. See onListNavigationPointerDown.
    this.lastPointerInReader = false;
    // The pending reader opening of the arrow keys (see KEY_OPEN_DELAY_MS).
    this.keyOpenTimer = null;
  },
  beforeDestroy() {
    window.clearTimeout(this.keyOpenTimer);
  },
  methods: {
    /**
     * Remembers whether the last click in the drawer landed in the reader pane.
     * <p>
     * A click on the message's text focuses no element of the reader -- the nearest
     * focusable ancestor is the drawer itself -- yet the browser scrolls the pane
     * clicked last with the arrow keys. So a key pressed on the drawer itself after
     * such a click still belongs to the reader.
     *
     * @param {MouseEvent} event the press
     * @returns {void}
     */
    onListNavigationPointerDown(event) {
      this.lastPointerInReader = !!event.target?.closest?.(READER_PANE_SELECTOR);
    },
    /**
     * Up and Down on the list.
     *
     * In the wide layout the reader follows: the next conversation opens beside the
     * list, as a click on it would, once the user stops on it (KEY_OPEN_DELAY_MS) --
     * the row is focused and lit at once. In the narrow one the reader is a drawer of its own
     * that would cover the list, so the key only moves the focus -- the row lights up
     * and scrolls into view -- and Enter or Space, which the row already answers, opens
     * it. Either way the row is revealed first when it lies beyond the rows the list
     * has built so far.
     *
     * The keys the reader needs stay the reader's: in the wide layout a key pressed
     * with the focus in the message scrolls the message.
     *
     * @param {KeyboardEvent} event the key press
     * @returns {void}
     */
    onListNavigationKeydown(event) {
      const step = navigationStep(event);
      if (!step || !this.canNavigateList) {
        return;
      }
      if (this.expanded && this.isKeyForReader(event)) {
        return;
      }
      const threads = threadRows(this.navigationEmails);
      const accept = this.expanded ? isOpenableRow : isFocusableRow;
      // The row holding the focus first: each key focuses the row it goes to at once,
      // while the reader only shows it once the server answered -- so a key pressed
      // before that answer, a held key above all, must start from the row the previous
      // key went to, not from the mail still on screen.
      const focused = this.focusedThreadIndex(threads, event);
      const current = focused >= 0 || !this.expanded ? focused : threadIndexOf(threads, this.email);
      const target = current < 0
        ? threads.find(thread => accept(thread.latest))
        : adjacentThread(threads, current, step, accept);
      // At either end the key is still the list's: letting it through would scroll the
      // pane away from the row the user is standing on.
      event.preventDefault();
      if (!target) {
        return;
      }
      if (this.expanded) {
        this.$root.$emit('set-opened', target.latest.mailRemoteId);
        window.clearTimeout(this.keyOpenTimer);
        const openingsBefore = this.emailRequest;
        this.keyOpenTimer = window.setTimeout(() => {
          this.keyOpenTimer = null;
          if (this.isKeyOpeningStillWanted(target, openingsBefore)) {
            this.openListedEmail(target.latest);
          }
        }, KEY_OPEN_DELAY_MS);
      }
      this.revealThreadRow(target.threadId);
    },
    /**
     * Whether the reader opening an arrow key scheduled is still the user's latest
     * intent when its delay ends: nothing was opened in the meantime (a click, a search
     * hit, the reader moving on after an action -- every opening counts in
     * `emailRequest`), the list is still the one the keys walk, and the row is still
     * listed (it may have been deleted from its own menu meanwhile).
     *
     * @param {Object} target the row the key went to
     * @param {Number} openingsBefore `emailRequest` when the key was pressed
     * @returns {Boolean} true when the opening may go ahead
     */
    isKeyOpeningStillWanted(target, openingsBefore) {
      return this.expanded && this.canNavigateList && this.emailRequest === openingsBefore
        && threadRows(this.navigationEmails).some(thread => thread.threadId === target.threadId
          && thread.latest.mailRemoteId === target.latest.mailRemoteId);
    },
    /**
     * Whether a key pressed in the wide layout belongs to the reader: pressed in the
     * reader pane, or on the drawer itself right after a click in the reader pane (see
     * onListNavigationPointerDown).
     *
     * @param {KeyboardEvent} event the key press
     * @returns {Boolean} true when the key is the reader's
     */
    isKeyForReader(event) {
      const target = event.target;
      if (target?.closest?.(READER_PANE_SELECTOR)) {
        return true;
      }
      return this.lastPointerInReader && !target?.closest?.('[data-thread-key]');
    },
    /**
     * The row holding the keyboard focus, found from the element the key was pressed on.
     *
     * @param {Array} threads the rows
     * @param {KeyboardEvent} event the key press
     * @returns {Number} the row's index, or -1 when the focus is on no row
     */
    focusedThreadIndex(threads, event) {
      const key = event.target?.closest?.('[data-thread-key]')?.getAttribute('data-thread-key');
      return key == null ? -1 : threads.findIndex(thread => String(thread.threadId) === key);
    },
    /**
     * Scrolls a row into view and gives it the focus, building it first if the list
     * has not rendered that far yet.
     *
     * @param {String} threadKey the row's key, as groupEmailsByThread gives it
     * @returns {void}
     */
    revealThreadRow(threadKey) {
      this.navigationList()?.revealThread?.(threadKey);
    },
    /**
     * Keeps the full-screen reader on a mail when the one it showed leaves the list:
     * it opens the row that took its place (see threadTakingThePlaceOf), or leaves the
     * "select an email" placeholder when nothing is left to open.
     *
     * Called by the component right after the action has taken the rows out of its
     * listing, with the listing as it was before.
     *
     * @param {Array<Number>} removedIds the IMAP UIDs the action applied to
     * @param {Array} listedBefore the listed messages before the action
     * @returns {Object} the row opened, or null
     */
    openNextAfterRemoval(removedIds, listedBefore) {
      if (!this.expanded || !this.email || !(removedIds || []).includes(this.email.mailRemoteId)) {
        return null;
      }
      const next = threadTakingThePlaceOf(threadRows(listedBefore), threadRows(this.navigationEmails), this.email);
      if (next) {
        this.openListedEmail(next.latest);
        this.revealThreadRow(next.threadId);
      }
      return next;
    },
  },
};
