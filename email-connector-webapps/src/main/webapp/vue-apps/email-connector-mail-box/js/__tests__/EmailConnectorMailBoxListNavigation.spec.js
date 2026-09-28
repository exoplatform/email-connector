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

// EXO-90414 — the rules the full-screen reader and the arrow keys follow, pinned on the
// pure helpers: which conversation takes the place of one that left the list, and which
// key presses are the list's to take.

import {
  NEXT,
  PREVIOUS,
  adjacentThread,
  firstOpenableThread,
  navigationStep,
  threadIndexOf,
  threadRows,
  threadTakingThePlaceOf,
} from '../EmailConnectorMailBoxListNavigation.js';

/**
 * One listed message, alone in its conversation unless a thread id is given.
 *
 * @param {Number} mailRemoteId the IMAP UID
 * @param {Object} extra further fields (threadId, refreshPending, draftLocalId…)
 * @returns {Object} the row
 */
function row(mailRemoteId, extra = {}) {
  return { mailRemoteId, folder: 'INBOX', read: true, ...extra };
}

/**
 * The listing without the given UIDs, as the drawer's optimistic removal leaves it.
 *
 * @param {Array} emails the listing
 * @param {Array<Number>} ids the UIDs removed
 * @returns {Array} what remains
 */
function without(emails, ids) {
  return emails.filter(email => !ids.includes(email.mailRemoteId));
}

/**
 * The conversation to open once `removed` left a listing whose reader showed `opened`.
 *
 * @param {Array} emails the listing before
 * @param {Number} opened the UID the reader showed
 * @param {Array<Number>} removed the UIDs removed
 * @returns {Number} the UID to open, or null
 */
function nextAfter(emails, opened, removed) {
  const next = threadTakingThePlaceOf(threadRows(emails), threadRows(without(emails, removed)),
    emails.find(email => email.mailRemoteId === opened));
  return next ? next.latest.mailRemoteId : null;
}

describe('the conversation that takes the opened one\'s place (EXO-90414)', () => {
  const listing = [row(1), row(2), row(3)];

  it('is the one listed after it', () => {
    expect(nextAfter(listing, 2, [2])).toBe(3);
  });

  it('is the one before it when it was the last', () => {
    expect(nextAfter(listing, 3, [3])).toBe(2);
  });

  it('is none when the list is left empty', () => {
    expect(nextAfter([row(1)], 1, [1])).toBeNull();
  });

  it('steps over what left with it, a multi-selection taking several rows at once', () => {
    expect(nextAfter([row(1), row(2), row(3), row(4)], 2, [2, 3])).toBe(4);
    expect(nextAfter([row(1), row(2), row(3), row(4)], 3, [3, 4])).toBe(2);
  });

  it('walks conversations, not messages: a whole conversation leaves as one row', () => {
    const emails = [row(10, { threadId: 't1' }), row(9, { threadId: 't1' }), row(8, { threadId: 't2' })];
    expect(nextAfter(emails, 10, [10, 9])).toBe(8);
  });

  it('stays on the conversation when a selection took only its newest message', () => {
    const emails = [row(10, { threadId: 't1' }), row(9, { threadId: 't1' }), row(8, { threadId: 't2' })];
    expect(nextAfter(emails, 10, [10])).toBe(9);
  });

  it('never lands on a row the reader cannot open: an inert one, or a draft', () => {
    const emails = [row(1), row(2), row(3, { refreshPending: true }), row(4, { draftLocalId: 'd1' }), row(5)];
    expect(nextAfter(emails, 2, [2])).toBe(5);
    expect(nextAfter([row(1), row(2, { refreshPending: true })], 1, [1])).toBeNull();
  });

  it('is none when the reader was showing a message the list does not hold', () => {
    const next = threadTakingThePlaceOf(threadRows(listing), threadRows(without(listing, [2])), row(42));
    expect(next).toBeNull();
  });

  it('tells a message of another folder from a listed one sharing its UID', () => {
    expect(threadIndexOf(threadRows(listing), row(2, { folder: 'SENT' }))).toBe(-1);
    expect(threadIndexOf(threadRows(listing), row(2))).toBe(1);
  });
});

describe('the first and the adjacent conversation', () => {
  it('opens on the first conversation the reader can open', () => {
    const threads = threadRows([row(1, { draftLocalId: 'd' }), row(2, { refreshPending: true }), row(3)]);
    expect(firstOpenableThread(threads).latest.mailRemoteId).toBe(3);
    expect(firstOpenableThread([])).toBeNull();
  });

  it('moves one row down and up, stepping over what cannot be landed on, and stops at the ends', () => {
    const threads = threadRows([row(1), row(2, { refreshPending: true }), row(3)]);
    expect(adjacentThread(threads, 0, NEXT).latest.mailRemoteId).toBe(3);
    expect(adjacentThread(threads, 2, PREVIOUS).latest.mailRemoteId).toBe(1);
    expect(adjacentThread(threads, 2, NEXT)).toBeNull();
    expect(adjacentThread(threads, 0, PREVIOUS)).toBeNull();
  });
});

describe('which key presses the list takes', () => {
  afterEach(() => {
    document.body.innerHTML = '';
  });

  /**
   * A key press on an element, as the drawer's listener receives it.
   *
   * @param {String} key the key
   * @param {Element} target where it was pressed
   * @param {Object} init further KeyboardEvent fields (modifiers)
   * @returns {KeyboardEvent} the event, targeted
   */
  function press(key, target = document.body, init = {}) {
    const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
    Object.defineProperty(event, 'target', { value: target });
    return event;
  }

  /**
   * Puts an element in the page.
   *
   * @param {String} html its markup
   * @returns {Element} the element's innermost first child, the one keys are pressed on
   */
  function place(html) {
    const holder = document.createElement('div');
    holder.innerHTML = html;
    document.body.appendChild(holder);
    let element = holder.firstElementChild;
    while (element.firstElementChild) {
      element = element.firstElementChild;
    }
    return element;
  }

  it('takes a bare Down and Up', () => {
    expect(navigationStep(press('ArrowDown'))).toBe(NEXT);
    expect(navigationStep(press('ArrowUp'))).toBe(PREVIOUS);
  });

  it('leaves every other key alone', () => {
    ['ArrowLeft', 'ArrowRight', 'Enter', ' ', 'j', 'PageDown'].forEach(key => expect(navigationStep(press(key))).toBe(0));
  });

  it('leaves a key with a modifier to the browser and the system', () => {
    ['altKey', 'ctrlKey', 'metaKey', 'shiftKey'].forEach(modifier =>
      expect(navigationStep(press('ArrowDown', document.body, { [modifier]: true }))).toBe(0));
  });

  it('never takes a key while the user types: a field, a text area, a rich editor', () => {
    expect(navigationStep(press('ArrowDown', place('<input type="text">')))).toBe(0);
    expect(navigationStep(press('ArrowDown', place('<textarea></textarea>')))).toBe(0);
    expect(navigationStep(press('ArrowDown', place('<div contenteditable="true"><p>text</p></div>')))).toBe(0);
    expect(navigationStep(press('ArrowDown', place('<div role="combobox"></div>')))).toBe(0);
  });

  it('leaves the keys to a menu or a dialog, whether it holds the focus or is merely open', () => {
    expect(navigationStep(press('ArrowDown', place('<div role="menu"><button>item</button></div>')))).toBe(0);
    const row = place('<div data-thread-key="1" tabindex="0"></div>');
    place('<div class="v-menu__content menuable__content__active"></div>');
    expect(navigationStep(press('ArrowDown', row))).toBe(0);
    document.body.innerHTML = '';
    const again = place('<div data-thread-key="1" tabindex="0"></div>');
    place('<div class="v-dialog v-dialog--active"></div>');
    expect(navigationStep(press('ArrowDown', again))).toBe(0);
  });

  it('leaves a key someone already handled', () => {
    const event = press('ArrowDown');
    event.preventDefault();
    expect(navigationStep(event)).toBe(0);
  });
});
