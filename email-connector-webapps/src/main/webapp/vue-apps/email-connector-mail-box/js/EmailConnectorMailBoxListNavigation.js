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

// How long a mail the reader opened on its own -- the first of the list in full screen,
// the next one after an action, the one the arrow keys stopped on -- must stay the
// opened one before it counts as read and as opened, the way Outlook's reading pane
// does: walking past a mail leaves it unread. An explicit click reads it at once.
export const AUTO_OPEN_MARK_READ_DELAY_MS = 2000;

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
 * Search hits as rows of the list: one message each -- the results are messages, not
 * conversations -- keyed by folder and UID, a UID being unique only within its folder.
 * Shaped like groupEmailsByThread's threads so every helper above walks them unchanged.
 *
 * @param {Array} results the search hits, as listed
 * @returns {Array} one row per hit, {threadId, emails, latest, mailRemoteIds}
 */
export function searchRows(results) {
  return (results || []).map(result => ({
    threadId: `${result.folder || 'INBOX'}:${result.mailRemoteId}`,
    emails: [result],
    latest: result,
    mailRemoteIds: [result.mailRemoteId],
  }));
}

/**
 * The behaviour of the drawer that shows the mail list beside the reader -- the mailbox
 * drawer, whose full screen is the only one since EXO-90415 (the mail drawer hands its
 * mail over to it rather than widening itself).
 *
 * The component provides:
 * - `email` (data): the message the reader shows, or null;
 * - `expanded` (data): whether the wide layout is on screen;
 * - `selectEmailPlaceHolder` (data): whether the reader shows the "select an email"
 *   placeholder;
 * - `navigationEmails` (computed): the listed messages, as the list receives them;
 * - `navigationDrawerOpen` (computed): whether the drawer is open -- the keys are
 *   listened to on the whole page only then;
 * - `canNavigateList` (computed): whether the arrow keys drive the list right now;
 * - `openListedEmail(row, {automatic})` (method): opens a listed message in the reader,
 *   the way a click on its row does -- or, automatic, without reading it or counting it
 *   as opened (see AUTO_OPEN_MARK_READ_DELAY_MS);
 * - `emailRequest` (plain field): the reader's request generation (EXO-90412), moved
 *   on by every opening of the reader and by every leave for the placeholder, so a
 *   delayed opening, or a delayed read, can tell it was overtaken; the component's
 *   `supersedeEmailRequest` also calls cancelAutoOpenDwell, so a mail the user opens
 *   themselves is never held back from being read;
 * - `navigationList()` (method): the list component on screen, or null;
 * - `navigationDrawer()` (method): its exo-drawer -- the very instance exo-drawer
 *   pushes into `eXo.openedDrawers`, so the inner one when a wrapper such as
 *   pinneable-drawer holds it (EXO-90577) -- to tell whether it is the drawer on top of
 *   the page;
 * - optionally `navigationEntriesOf(rows)` (method): the rows the list shows for a
 *   listing -- conversations by default (threadRows), one per hit in a search;
 * - optionally `onAutoOpenedEmailRead(row)` (method): what else the drawer marks read
 *   when an automatically opened mail was read.
 */
export default {
  data() {
    return {
      // An automatically opened mail is on screen and not read yet: the reader must not
      // mark its conversation read on its own (deferThreadRead) before the dwell ends.
      autoOpenReadPending: false,
    };
  },
  created() {
    // Whether the last click in the wide layout landed in the reader pane: plain, not
    // reactive -- nothing renders it. See onListNavigationPointerDown.
    this.lastPointerInReader = false;
    // The pending reader opening of the arrow keys (see KEY_OPEN_DELAY_MS).
    this.keyOpenTimer = null;
    // The pending read of an automatically opened mail (AUTO_OPEN_MARK_READ_DELAY_MS).
    this.autoOpenReadTimer = null;
  },
  beforeDestroy() {
    document.removeEventListener('keydown', this.onDocumentNavigationKeydown);
    window.clearTimeout(this.keyOpenTimer);
    window.clearTimeout(this.autoOpenReadTimer);
  },
  watch: {
    /**
     * Listens to the keys on the whole page while the drawer is open.
     * <p>
     * Not on the drawer itself: a key only reaches an element through the focus, and the
     * focus leaves the drawer whenever the element holding it goes away -- the expand
     * button, re-rendered as the drawer switches layouts; a row an action removed -- and
     * falls back to the page's body, where a listener on the drawer never hears it
     * (EXO-90414: the arrow keys did nothing after expanding until the user clicked in
     * the list). Only the drawer on top of the page acts (onDocumentNavigationKeydown).
     *
     * @param {Boolean} open whether the drawer is open
     * @returns {void}
     */
    navigationDrawerOpen: {
      immediate: true,
      handler(open) {
        if (open) {
          document.addEventListener('keydown', this.onDocumentNavigationKeydown);
        } else {
          document.removeEventListener('keydown', this.onDocumentNavigationKeydown);
        }
      },
    },
    /**
     * A layout switch forgets where the last click landed -- in the narrow layout the
     * list itself sits in the pane the reader takes in the wide one -- and ends the wait
     * of an automatically opened mail: the reader is gone.
     *
     * @param {Boolean} expanded whether the wide layout is on screen
     * @returns {void}
     */
    expanded(expanded) {
      this.lastPointerInReader = false;
      if (!expanded) {
        this.cancelAutoOpenDwell();
      }
    },
  },
  methods: {
    /**
     * The rows the list shows for a listing; conversations unless the drawer says
     * otherwise.
     *
     * @param {Array} rows the listed messages
     * @returns {Array} the rows, shaped like groupEmailsByThread's threads
     */
    navigationEntriesOf(rows) {
      return threadRows(rows);
    },
    /**
     * A key pressed anywhere on the page, taken by the list when this drawer is the one
     * on top: exo-drawer stacks every open drawer in `eXo.openedDrawers`, and a drawer
     * opened over this one -- the composer, the folder picker, the mail drawer -- owns
     * the keys while it is there. Every other guard is the list's own
     * (onListNavigationKeydown).
     *
     * @param {KeyboardEvent} event the key press
     * @returns {void}
     */
    onDocumentNavigationKeydown(event) {
      if (this.isTopmostNavigationDrawer()) {
        this.onListNavigationKeydown(event);
      }
    },
    /**
     * Whether this drawer is the one on top of the page: no drawer open at all (the
     * dedicated full-screen tab, where the drawer is permanent and never stacked), or
     * the last one stacked is this drawer's own exo-drawer (navigationDrawer).
     *
     * @returns {Boolean} true when no other drawer is open over it
     */
    isTopmostNavigationDrawer() {
      const stack = window.eXo?.openedDrawers;
      if (!stack?.length) {
        return true;
      }
      return stack[stack.length - 1] === this.navigationDrawer();
    },
    /**
     * Remembers whether the last click in the wide layout landed in the reader pane.
     * <p>
     * A click on the message's text focuses no element of the reader -- the nearest
     * focusable ancestor is the drawer itself -- yet the browser scrolls the pane
     * clicked last with the arrow keys. So a key pressed outside any row after such a
     * click still belongs to the reader. Forgotten at every layout switch: in the narrow
     * layout that pane holds the list (see the `expanded` watcher).
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
     * In the wide layout the reader follows: the next row opens beside the list once
     * the user stops on it (KEY_OPEN_DELAY_MS) -- the row is focused and lit at once --
     * and counts as read only once the user stayed on it (AUTO_OPEN_MARK_READ_DELAY_MS).
     * In the narrow one the reader is a drawer of its own that would cover the list, so
     * the key only moves the focus -- the row lights up and scrolls into view -- and
     * Enter or Space, which the row already answers, opens it. Either way the row is
     * revealed first when it lies beyond the rows the list has built so far.
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
      const threads = this.navigationEntriesOf(this.navigationEmails);
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
        // Leaving the mail on screen is leaving it: its wait ends with the key, not with
        // the next opening a moment later.
        this.cancelAutoOpenDwell();
        this.$root.$emit('set-opened', target.latest.mailRemoteId);
        window.clearTimeout(this.keyOpenTimer);
        const openingsBefore = this.emailRequest;
        this.keyOpenTimer = window.setTimeout(() => {
          this.keyOpenTimer = null;
          if (this.isKeyOpeningStillWanted(target, openingsBefore)) {
            this.openAutomatically(target.latest);
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
        && this.navigationEntriesOf(this.navigationEmails).some(thread => thread.threadId === target.threadId
          && thread.latest.mailRemoteId === target.latest.mailRemoteId);
    },
    /**
     * Opens a row the user did not click -- the first of the list, the next one after
     * an action, the one the arrow keys stopped on -- and starts the wait after which it
     * counts as read and as opened.
     *
     * @param {Object} row the listed message
     * @returns {Promise} resolved once the message is on screen
     */
    openAutomatically(row) {
      const opening = this.openListedEmail(row, { automatic: true });
      // The opening's own request, current right after it started (every opening
      // supersedes the one before, synchronously).
      const request = this.emailRequest;
      // Held from the start: the reader must not read the conversation while the mail
      // is on its way either.
      this.autoOpenReadPending = true;
      // The wait starts once the mail is on screen, not when it was asked for: an
      // uncached search hit is pulled in first, a synchronization can hold that for
      // seconds, and the wait would otherwise end on the previous mail.
      Promise.resolve(opening).then(() => {
        if (this.emailRequest === request && this.readerShows(row)) {
          this.startAutoOpenDwell(row);
        }
      });
      return opening;
    },
    /**
     * Whether the reader shows a given message: same UID in the same folder, the
     * placeholder down.
     *
     * @param {Object} row the message
     * @returns {Boolean} true when it is the one on screen
     */
    readerShows(row) {
      return !this.selectEmailPlaceHolder && !!this.email && this.email.mailRemoteId === row.mailRemoteId
        && (this.email.folder || 'INBOX') === (row.folder || 'INBOX');
    },
    /**
     * Starts the wait of an automatically opened mail (AUTO_OPEN_MARK_READ_DELAY_MS).
     * <p>
     * Called once the opening is on screen. Every other opening, the placeholder, the drawer
     * closing (they all supersede the reader's request, which cancels this wait), a key
     * moving on and a collapse end the wait; the mail is read only if the reader still
     * shows it when the wait ends.
     *
     * @param {Object} row the listed message that was opened
     * @returns {void}
     */
    startAutoOpenDwell(row) {
      this.cancelAutoOpenDwell();
      this.autoOpenReadPending = true;
      this.autoOpenReadTimer = window.setTimeout(() => {
        this.autoOpenReadTimer = null;
        this.autoOpenReadPending = false;
        if (this.readerShows(row)) {
          this.markAutoOpenedEmailRead(row);
        }
      }, AUTO_OPEN_MARK_READ_DELAY_MS);
    },
    /**
     * Ends the wait of an automatically opened mail without reading it: the user moved
     * on, or opened something themselves -- which the reader then reads at once.
     *
     * @returns {void}
     */
    cancelAutoOpenDwell() {
      window.clearTimeout(this.autoOpenReadTimer);
      this.autoOpenReadTimer = null;
      this.autoOpenReadPending = false;
    },
    /**
     * Reads an automatically opened mail once the user stayed on it: its conversation's
     * unread messages in the list -- what the reader marks read when it opens one on a
     * click -- and one opening counted, which its read left out.
     *
     * @param {Object} row the listed message
     * @returns {void}
     */
    markAutoOpenedEmailRead(row) {
      const threads = this.navigationEntriesOf(this.navigationEmails);
      const index = threadIndexOf(threads, row);
      const messages = index >= 0 ? threads[index].emails : [row];
      // Each message in the folder its UID is numbered in, one read per folder, as the
      // reader's own read of a conversation goes: a row may gather a conversation's
      // messages from several folders, and the listing may hold another message under
      // any of those numbers (EXO-90414).
      const unreadByFolder = new Map();
      messages.filter(message => !message.read).forEach(message => {
        const folder = message.folder || 'INBOX';
        unreadByFolder.set(folder, (unreadByFolder.get(folder) || []).concat(message.mailRemoteId));
      });
      unreadByFolder.forEach((unread, folder) => this.$root.$emit('update-email-read-status', true, unread, folder));
      this.onAutoOpenedEmailRead?.(row);
      // The reader may now treat it as displayed: a read receipt its sender asked for
      // may leave on its own (EXO-90435), exactly when the mail counts as read.
      this.$root.$emit('email-read-on-display', row);
      this.$emailConnectorMailBoxService.broadcastOpenEmail().catch(() => null);
    },
    /**
     * Whether a key pressed in the wide layout belongs to the reader: pressed in the
     * reader pane, or outside any row right after a click in the reader pane (see
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
     * it opens the row that took its place (see threadTakingThePlaceOf) -- as an
     * automatic opening, read only if the user stays on it -- or leaves the "select an
     * email" placeholder when nothing is left to open.
     *
     * Called by the component right after the action has taken the rows out of its
     * listing, with the listing as it was before.
     *
     * @param {Array<Number>} removedIds the IMAP UIDs the action applied to
     * @param {Array} listedBefore the listed messages before the action
     * @param {String} folder the folder those UIDs are numbered in, when the emitter knows it
     * @returns {Object} the row opened, or null
     */
    openNextAfterRemoval(removedIds, listedBefore, folder = null) {
      if (!this.expanded || !this.email || !(removedIds || []).includes(this.email.mailRemoteId)
          || (folder && (this.email.folder || 'INBOX') !== folder)) {
        return null;
      }
      const next = threadTakingThePlaceOf(this.navigationEntriesOf(listedBefore),
        this.navigationEntriesOf(this.navigationEmails), this.email);
      if (next) {
        this.openAutomatically(next.latest);
        this.revealThreadRow(next.threadId);
      }
      return next;
    },
  },
};
