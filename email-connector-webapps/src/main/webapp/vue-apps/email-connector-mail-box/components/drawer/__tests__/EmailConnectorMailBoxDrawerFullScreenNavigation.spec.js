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

// EXO-90414 — the full-screen mailbox opens on a mail, moves on to the next one when
// the opened mail leaves the list, and follows the arrow keys. Pinned on the mailbox
// drawer itself, through the events and the key presses that drive it in the page, and
// on the list's own reveal of a row beyond its rendered window.

import { createLocalVue, shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerList from '../EmailConnectorMailBoxDrawerList.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';
import { AUTO_OPEN_MARK_READ_DELAY_MS, KEY_OPEN_DELAY_MS } from '../../../js/EmailConnectorMailBoxListNavigation.js';
import EmailConnectorMailBoxDrawerSearchResults from '../EmailConnectorMailBoxDrawerSearchResults.vue';
import EmailConnectorMailBoxMoveToFolderDrawer from '../EmailConnectorMailBoxMoveToFolderDrawer.vue';
import EmailConnectorMailBoxDrawerListItem from '../EmailConnectorMailBoxDrawerListItem.vue';
import EmailConnectorMailBoxDrawerSearchResultItem from '../EmailConnectorMailBoxDrawerSearchResultItem.vue';
import EmailConnectorMailBoxDrawerThreadContent from '../EmailConnectorMailBoxDrawerThreadContent.vue';

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'CUSTOM:1', type: 'CUSTOM', displayName: 'Factures', path: 'Factures', syncEnabled: true },
];

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

// Long enough for the arrow keys' reader opening to have fired and landed.
const settle = () => new Promise(resolve => setTimeout(resolve, KEY_OPEN_DELAY_MS + 20));

/**
 * A service whose every function the drawer may call answers an empty promise, with
 * the ones these pins are about supplied for real.
 *
 * @param {Object} overrides the functions under test
 * @returns {Proxy} the service
 */
function serviceStub(overrides) {
  return new Proxy({ ...overrides }, {
    get(target, name) {
      if (!(name in target)) {
        target[name] = jest.fn(() => Promise.resolve(null));
      }
      return target[name];
    },
  });
}

/**
 * One listed inbox message, alone in its conversation.
 *
 * @param {Number} mailRemoteId the IMAP UID
 * @returns {Object} the row
 */
function row(mailRemoteId) {
  return {
    mailRemoteId,
    mailHeaderId: `<${mailRemoteId}@host>`,
    folder: 'INBOX',
    read: true,
    subject: `mail ${mailRemoteId}`,
    sender: { name: 'Alice', address: 'alice@host' },
    receivedDate: new Date(2026, 0, 30 - mailRemoteId).toISOString(),
    categoryIds: [],
  };
}

/**
 * Mounts the mailbox drawer, open, over a listed inbox.
 *
 * @param {Array} emails the listed rows, or null for a list still on its way
 * @returns {Object} {wrapper, service, alerts, opened, teardown}
 */
async function mountDrawer(emails) {
  const service = serviceStub({
    folderLabel: emailConnectorMailBoxService.folderLabel,
    isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
    getEmailByRemoteId: jest.fn((mailRemoteId, folder) => Promise.resolve({ mailRemoteId, folder, subject: `mail ${mailRemoteId}` })),
    getEmailBox: jest.fn(() => Promise.resolve({ emails: emails || [], folders: FOLDERS, emailSyncStatus: 'SUCCESS' })),
    getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
    deleteEmails: jest.fn(() => Promise.resolve({ failedDeletions: 0 })),
    archiveEmails: jest.fn(() => Promise.resolve({ failedArchives: 0 })),
    markAsJunk: jest.fn(() => Promise.resolve({ failedJunkMoves: 0 })),
    moveEmails: jest.fn(() => Promise.resolve({ failedMoves: 0 })),
    undoMoveEmails: jest.fn(() => Promise.resolve({ failedUndos: 0 })),
    broadcastOpenEmail: jest.fn(() => Promise.resolve()),
  });
  const wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
    attachTo: document.body,
    mocks: {
      $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key),
      $emailConnectorMailBoxService: service,
      $emailConnectorCommonService: serviceStub({}),
      $vuetify: { breakpoint: {}, rtl: false },
    },
    stubs: { 'exo-drawer': true },
  });
  await wrapper.setData({
    emailBoxDrawer: true,
    emailBox: emails && { emails, folders: FOLDERS, emailSyncStatus: 'SUCCESS' },
  });
  const alerts = [];
  const alertListener = event => alerts.push(event.detail);
  document.addEventListener('alert-message', alertListener);
  const opened = [];
  wrapper.vm.$root.$on('set-opened', id => opened.push(id));
  return {
    wrapper,
    service,
    alerts,
    opened,
    teardown: () => {
      document.removeEventListener('alert-message', alertListener);
      wrapper.vm.stopAutoRefresh();
      wrapper.destroy();
      document.body.innerHTML = '';
    },
  };
}

/**
 * The UIDs the reader was asked to open, in order.
 *
 * @param {Object} fixture the mounted drawer
 * @returns {Array<Number>} the UIDs
 */
function readerOpenings(fixture) {
  return fixture.service.getEmailByRemoteId.mock.calls.map(([mailRemoteId]) => mailRemoteId);
}

/**
 * Puts the drawer full screen with one mail open, as a click on its row would.
 *
 * @param {Object} fixture the mounted drawer
 * @param {Number} mailRemoteId the UID to open
 * @returns {Promise<void>} resolved once it is on screen
 */
async function openFullScreenOn(fixture, mailRemoteId) {
  await fixture.wrapper.setData({ expanded: true });
  await fixture.wrapper.vm.openListedEmail(fixture.wrapper.vm.emails.find(e => e.mailRemoteId === mailRemoteId));
  await flush();
  fixture.service.getEmailByRemoteId.mockClear();
}

/**
 * Presses a key on an element of the drawer, the way the page delivers it: bubbling up
 * to the drawer's root, where the drawer listens.
 *
 * @param {Object} fixture the mounted drawer
 * @param {String} key the key
 * @param {Object} options {target: the element pressed on (defaults to the root), modifiers}
 * @returns {KeyboardEvent} the event dispatched
 */
function press(fixture, key, { target, ...init } = {}) {
  const event = new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true, ...init });
  (target || fixture.wrapper.element).dispatchEvent(event);
  return event;
}

/**
 * Appends an element inside the drawer, for a key to be pressed on it.
 *
 * @param {Object} fixture the mounted drawer
 * @param {String} html its markup
 * @returns {Element} the innermost first element
 */
function inDrawer(fixture, html) {
  const holder = document.createElement('div');
  holder.innerHTML = html;
  fixture.wrapper.element.appendChild(holder);
  let element = holder.firstElementChild;
  while (element.firstElementChild) {
    element = element.firstElementChild;
  }
  return element;
}

describe('full screen opens on a mail (EXO-90414)', () => {
  let fixture;

  afterEach(() => {
    jest.useRealTimers();
    fixture?.teardown();
  });

  it('opens the first mail of the list when expanded with nothing open', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    jest.useFakeTimers();

    fixture.wrapper.vm.updateExpand(true);
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(true);
    jest.advanceTimersByTime(200);
    jest.useRealTimers();
    await flush();

    expect(readerOpenings(fixture)).toEqual([1]);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(1);
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(false);
    expect(fixture.opened[fixture.opened.length - 1]).toBe(1);
  });

  it('opens the first mail when the one left open went away while the drawer was narrow', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 2);
    await fixture.wrapper.setData({ expanded: false });
    // The list changed under the narrow layout (another folder, a delete from a row's
    // menu): the mail still held is no longer listed.
    await fixture.wrapper.setData({ emailBox: { emails: [row(4), row(5)], folders: FOLDERS } });
    await flush();
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(true);
    jest.useFakeTimers();

    fixture.wrapper.vm.updateExpand(true);
    jest.advanceTimersByTime(200);
    jest.useRealTimers();
    await flush();

    expect(readerOpenings(fixture)).toEqual([4]);
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(false);
  });

  it('keeps the mail already open', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await fixture.wrapper.setData({ email: { mailRemoteId: 2, folder: 'INBOX' } });
    await fixture.wrapper.setData({ expanded: true });

    fixture.wrapper.vm.autoSelectFirstEmail();
    await flush();

    expect(readerOpenings(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
  });

  it('keeps the placeholder over an empty list, and waits for nothing', async () => {
    fixture = await mountDrawer([]);
    await fixture.wrapper.setData({ expanded: true, selectEmailPlaceHolder: true });

    fixture.wrapper.vm.autoSelectFirstEmail();
    await flush();

    expect(readerOpenings(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.autoSelectPending).toBe(false);
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(true);
  });

  it('opens the first mail once a list still loading arrives', async () => {
    fixture = await mountDrawer(null);
    await fixture.wrapper.setData({ expanded: true, loading: true });

    fixture.wrapper.vm.autoSelectFirstEmail();
    expect(fixture.wrapper.vm.autoSelectPending).toBe(true);
    await fixture.wrapper.setData({ emailBox: { emails: [row(4), row(5)], folders: FOLDERS }, loading: false });
    await flush();

    expect(readerOpenings(fixture)).toEqual([4]);
    expect(fixture.wrapper.vm.autoSelectPending).toBe(false);
  });

  it('does not override a mail the user opened while the list was loading', async () => {
    fixture = await mountDrawer(null);
    await fixture.wrapper.setData({ expanded: true, loading: true });
    fixture.wrapper.vm.autoSelectFirstEmail();

    // The user opens a mail from the list before the list refresh lands: its answer is
    // still on its way, so nothing is open yet when the list arrives.
    let answer;
    fixture.service.getEmailByRemoteId.mockImplementationOnce(mailRemoteId =>
      new Promise(resolve => answer = () => resolve({ mailRemoteId, folder: 'INBOX' })));
    fixture.wrapper.vm.openEmailDetailContent(5);
    await fixture.wrapper.setData({ emailBox: { emails: [row(4), row(5)], folders: FOLDERS }, loading: false });
    await flush();
    expect(fixture.wrapper.vm.email).toBeNull();
    answer();
    await flush();

    expect(readerOpenings(fixture)).toEqual([5]);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(5);
  });

  it.each([
    ['a search hit', vm => vm.openSearchResult({ mailRemoteId: 9, folder: 'INBOX', cached: true })],
    ['a mail from outside', vm => vm.$root.$emit('open-mail-box-drawer', { mailRemoteId: 9, folder: 'INBOX' })],
  ])('does not override %s opened while the list was loading', async (label, openIt) => {
    fixture = await mountDrawer(null);
    await fixture.wrapper.setData({ expanded: true, loading: true });
    fixture.wrapper.vm.autoSelectFirstEmail();
    expect(fixture.wrapper.vm.autoSelectPending).toBe(true);
    let answer;
    fixture.service.getEmailByRemoteId.mockImplementationOnce(mailRemoteId =>
      new Promise(resolve => answer = () => resolve({ mailRemoteId, folder: 'INBOX' })));

    // Its answer is still on its way when the list lands.
    openIt(fixture.wrapper.vm);
    await flush();
    await fixture.wrapper.setData({ emailBox: { emails: [row(4)], folders: FOLDERS }, loading: false });
    await flush();
    answer();
    await flush();

    expect(readerOpenings(fixture)).toEqual([9]);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(9);
  });

  it('opens the first search hit while a search is running', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await fixture.wrapper.setData({ expanded: true, searchTerm: 'mail 2' });

    fixture.wrapper.vm.autoSelectFirstEmail();
    await flush();

    expect(fixture.service.getEmailByRemoteId).toHaveBeenCalledWith(2, 'INBOX', { broadcast: false });
  });

  it('forgets the wait when the drawer goes back to its narrow layout', async () => {
    fixture = await mountDrawer(null);
    await fixture.wrapper.setData({ expanded: true, loading: true });
    fixture.wrapper.vm.autoSelectFirstEmail();
    jest.useFakeTimers();

    fixture.wrapper.vm.updateExpand(false);
    jest.advanceTimersByTime(200);
    jest.useRealTimers();
    await fixture.wrapper.setData({ emailBox: { emails: [row(4)], folders: FOLDERS }, loading: false });
    await flush();

    expect(readerOpenings(fixture)).toEqual([]);
  });
});

describe('full screen moves on to the next mail after an action (EXO-90414)', () => {
  let fixture;

  afterEach(() => fixture?.teardown());

  it('opens the mail that took the deleted one\'s place', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 2);

    fixture.wrapper.vm.$root.$emit('delete-email', [2]);
    await flush();

    expect(readerOpenings(fixture)).toEqual([3]);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(3);
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(false);
    // Lit once, as the next mail opens: the placeholder is up and down again within the
    // same tick (the reader opens on the next list row at once), so its watcher, which
    // would clear the highlight, never runs.
    expect(fixture.opened[fixture.opened.length - 1]).toBe(3);
  });

  it('opens the previous mail when the archived one was the last', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 3);

    fixture.wrapper.vm.$root.$emit('archive-email', [3]);
    await flush();

    expect(readerOpenings(fixture)).toEqual([2]);
  });

  it('does the same for "Mark as spam"', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 1);

    fixture.wrapper.vm.$root.$emit('junk-email', [1]);
    await flush();

    expect(readerOpenings(fixture)).toEqual([2]);
  });

  it('shows the placeholder when the list is left empty', async () => {
    fixture = await mountDrawer([row(1)]);
    await openFullScreenOn(fixture, 1);

    fixture.wrapper.vm.$root.$emit('delete-email', [1]);
    await flush();

    expect(readerOpenings(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(true);
  });

  it('stays on the open mail when the action took another row', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 2);

    fixture.wrapper.vm.$root.$emit('delete-email', [1]);
    await flush();

    expect(readerOpenings(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(false);
  });

  it('moves on after a move, and stays there when the move is undone', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 2);

    fixture.wrapper.vm.$root.$emit('move-email', [2], 'CUSTOM:1');
    await flush();
    expect(readerOpenings(fixture)).toEqual([3]);

    await fixture.alerts[0].alertLinkCallback();
    await flush();

    // The undone row is back, inert until the server lists it again; the reader stays
    // on the mail it moved on to, which is still listed.
    expect(fixture.wrapper.vm.emails.map(e => e.mailRemoteId)).toEqual([1, 2, 3]);
    expect(fixture.wrapper.vm.emails[1].refreshPending).toBe(true);
    expect(readerOpenings(fixture)).toEqual([3]);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(3);
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(false);
  });

  it('moves on after a multi-selection that took the open mail', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3), row(4)]);
    await openFullScreenOn(fixture, 2);
    await fixture.wrapper.setData({ selectMode: true, selectedEmails: [2, 3] });

    fixture.wrapper.vm.$root.$emit('delete-email', [2, 3]);
    await flush();

    expect(fixture.wrapper.vm.selectMode).toBe(false);
    expect(readerOpenings(fixture)).toEqual([4]);
  });

  it('opens nothing in the narrow layout, where there is no reader beside the list', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await fixture.wrapper.setData({ email: { mailRemoteId: 1, folder: 'INBOX' } });

    fixture.wrapper.vm.$root.$emit('delete-email', [1]);
    await flush();

    expect(readerOpenings(fixture)).toEqual([]);
  });

  it('shows the later of two openings, whichever answer lands last', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await fixture.wrapper.setData({ expanded: true });
    const answers = {};
    fixture.service.getEmailByRemoteId.mockImplementation(mailRemoteId =>
      new Promise(resolve => answers[mailRemoteId] = () => resolve({ mailRemoteId, folder: 'INBOX' })));

    fixture.wrapper.vm.openEmailDetailContent(1);
    fixture.wrapper.vm.openEmailDetailContent(2);
    answers[2]();
    await flush();
    answers[1]();
    await flush();

    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
    expect(fixture.wrapper.vm.loading).toBe(false);
  });

  it('shows a list opening over an older opening from outside answering last', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await fixture.wrapper.setData({ expanded: true });
    const answers = {};
    fixture.service.getEmailByRemoteId.mockImplementation(mailRemoteId =>
      new Promise(resolve => answers[mailRemoteId] = () => resolve({ mailRemoteId, folder: 'INBOX' })));

    fixture.wrapper.vm.$root.$emit('open-mail-box-drawer', { mailRemoteId: 1, folder: 'INBOX' });
    await flush();
    fixture.wrapper.vm.openEmailDetailContent(2);
    answers[2]();
    await flush();
    answers[1]();
    await flush();

    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
    expect(fixture.wrapper.vm.loading).toBe(false);
  });

  it('shows a list opening over an older search opening answering last', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await fixture.wrapper.setData({ expanded: true });
    const answers = {};
    fixture.service.getEmailByRemoteId.mockImplementation(mailRemoteId =>
      new Promise(resolve => answers[mailRemoteId] = () => resolve({ mailRemoteId, folder: 'INBOX' })));

    fixture.wrapper.vm.openSearchResult({ mailRemoteId: 1, folder: 'INBOX', cached: true });
    await flush();
    fixture.wrapper.vm.openEmailDetailContent(2);
    answers[2]();
    await flush();
    answers[1]();
    await flush();

    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
    expect(fixture.wrapper.vm.loading).toBe(false);
  });
});

describe('the arrow keys walk the list (EXO-90414)', () => {
  let fixture;

  afterEach(() => fixture?.teardown());

  it('opens the next and the previous mail in full screen', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 2);

    const down = press(fixture, 'ArrowDown');
    await settle();
    expect(down.defaultPrevented).toBe(true);
    expect(readerOpenings(fixture)).toEqual([3]);

    press(fixture, 'ArrowUp');
    await settle();
    press(fixture, 'ArrowUp');
    await settle();
    expect(readerOpenings(fixture)).toEqual([3, 2, 1]);
  });

  it('lights each row at once but only opens the one the user stops on', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3), row(4)]);
    await openFullScreenOn(fixture, 1);
    fixture.opened.length = 0;

    // A held key: three rows passed in quick succession, each focused as it is reached
    // (by the list on reveal; by hand here, the list being stubbed).
    press(fixture, 'ArrowDown');
    press(fixture, 'ArrowDown', { target: inDrawer(fixture, '<div data-thread-key="<2@host>" tabindex="0"></div>') });
    press(fixture, 'ArrowDown', { target: inDrawer(fixture, '<div data-thread-key="<3@host>" tabindex="0"></div>') });
    expect(fixture.opened).toEqual([2, 3, 4]);
    expect(readerOpenings(fixture)).toEqual([]);
    await settle();

    // One fetch -- one mail marked read, one "open email" counted -- not three.
    expect(readerOpenings(fixture)).toEqual([4]);
  });

  it('gives way to a row clicked before the key\'s opening fires', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3), row(4)]);
    await openFullScreenOn(fixture, 1);

    press(fixture, 'ArrowDown');
    fixture.wrapper.vm.openEmailDetailContent(4);
    await settle();

    expect(readerOpenings(fixture)).toEqual([4]);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(4);
  });

  it('does not open a row that left the list before the key\'s opening fires', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3), row(4)]);
    await openFullScreenOn(fixture, 1);

    press(fixture, 'ArrowDown');
    fixture.wrapper.vm.$root.$emit('delete-email', [2]);
    await settle();

    expect(readerOpenings(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(1);
  });

  it('does not open anything once the list stopped being walkable', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 1);

    press(fixture, 'ArrowDown');
    await fixture.wrapper.setData({ selectMode: true });
    await settle();

    expect(readerOpenings(fixture)).toEqual([]);
  });

  it('keeps going when a key comes before the previous mail is on screen', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 1);
    fixture.service.getEmailByRemoteId.mockImplementation(() => new Promise(() => null));

    press(fixture, 'ArrowDown');
    await settle();
    // The first key focused the row it went to -- the list does it on reveal, pinned
    // below on the list itself ("builds the rows up to one beyond…"); the list is
    // stubbed here, so the second key is pressed on that row by hand. The reader still
    // shows mail 1, its answer for mail 2 not in yet.
    press(fixture, 'ArrowDown', { target: inDrawer(fixture, '<div data-thread-key="<2@host>" tabindex="0"></div>') });
    await settle();

    expect(readerOpenings(fixture)).toEqual([2, 3]);
  });

  it('walks on from a mail whose full copy could not be read (EXO-90412)', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 2);
    // What a failed read leaves on screen: a settled copy of the row, not the row.
    await fixture.wrapper.setData({ email: emailConnectorMailBoxService.settleListingRow(row(2)) });

    press(fixture, 'ArrowDown');
    await settle();

    expect(readerOpenings(fixture)).toEqual([3]);
  });

  it('stops at either end of the list', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await openFullScreenOn(fixture, 2);

    press(fixture, 'ArrowDown');
    await flush();

    expect(readerOpenings(fixture)).toEqual([]);
  });

  it('starts from the first mail when nothing is open', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await fixture.wrapper.setData({ expanded: true });

    press(fixture, 'ArrowDown');
    await settle();

    expect(readerOpenings(fixture)).toEqual([1]);
  });

  it('only moves the focus in the narrow layout, and leaves opening to Enter', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    const reveal = jest.spyOn(fixture.wrapper.vm, 'revealThreadRow').mockImplementation(() => null);
    const focusedRow = inDrawer(fixture, '<div data-thread-key="<2@host>" tabindex="0"></div>');

    press(fixture, 'ArrowDown', { target: focusedRow });
    await flush();

    expect(reveal).toHaveBeenCalledWith('<3@host>');
    expect(readerOpenings(fixture)).toEqual([]);
  });

  it('never takes a key while the user types, nor with a modifier', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 1);

    press(fixture, 'ArrowDown', { target: inDrawer(fixture, '<input type="text">') });
    press(fixture, 'ArrowDown', { target: inDrawer(fixture, '<div contenteditable="true"><p>x</p></div>') });
    press(fixture, 'ArrowDown', { ctrlKey: true });
    press(fixture, 'ArrowDown', { shiftKey: true });
    await flush();

    expect(readerOpenings(fixture)).toEqual([]);
  });

  it('leaves the keys to the message read when it was clicked last, even with the focus on the drawer', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 1);
    const text = inDrawer(fixture, '<div class="drawerContent"><p>body</p></div>');
    const list = inDrawer(fixture, '<div class="list"><p>row</p></div>');

    text.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    const afterReaderClick = press(fixture, 'ArrowDown');
    await settle();
    expect(afterReaderClick.defaultPrevented).toBe(false);
    expect(readerOpenings(fixture)).toEqual([]);

    list.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    press(fixture, 'ArrowDown');
    await settle();
    expect(readerOpenings(fixture)).toEqual([2]);
  });

  it('gives the focus back to the row once the mail drawer opened from it closes', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    const reveal = jest.spyOn(fixture.wrapper.vm, 'revealThreadRow').mockImplementation(() => null);
    inDrawer(fixture, '<div data-thread-key="<2@host>" tabindex="0"></div>').focus();

    fixture.wrapper.vm.$root.$emit('open-email-detail-drawer', 2, [], false, null);
    fixture.wrapper.vm.$root.$emit('email-detail-drawer-closed');

    expect(reveal).toHaveBeenCalledWith('<2@host>');
  });

  it('gives the focus to the row that took its place when the mail was deleted from the mail drawer', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    const reveal = jest.spyOn(fixture.wrapper.vm, 'revealThreadRow').mockImplementation(() => null);
    inDrawer(fixture, '<div data-thread-key="<3@host>" tabindex="0"></div>').focus();
    fixture.wrapper.vm.$root.$emit('open-email-detail-drawer', 3, [], false, null);
    fixture.wrapper.vm.$root.isDetailDrawerActive = true;

    fixture.wrapper.vm.$root.$emit('delete-email', [3]);
    fixture.wrapper.vm.$root.isDetailDrawerActive = false;
    fixture.wrapper.vm.$root.$emit('email-detail-drawer-closed');

    // It was the last row: the one now last takes its place.
    expect(reveal).toHaveBeenCalledWith('<2@host>');
  });

  it('leaves the keys to the message being read in full screen', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await openFullScreenOn(fixture, 1);

    const inReader = press(fixture, 'ArrowDown', { target: inDrawer(fixture, '<div class="drawerContent"><p>body</p></div>') });
    await flush();

    expect(inReader.defaultPrevented).toBe(false);
    expect(readerOpenings(fixture)).toEqual([]);
  });

  it('leaves the keys alone during a multi-selection, or with the mail drawer on top', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await openFullScreenOn(fixture, 1);

    await fixture.wrapper.setData({ selectMode: true });
    press(fixture, 'ArrowDown');
    await fixture.wrapper.setData({ selectMode: false });
    await settle();
    expect(readerOpenings(fixture)).toEqual([]);

    // The list was walkable a moment ago -- and the mail drawer's flag is a plain root
    // property, which nothing re-evaluates a cached answer for.
    expect(fixture.wrapper.vm.canNavigateList).toBe(true);
    fixture.wrapper.vm.$root.isDetailDrawerActive = true;
    press(fixture, 'ArrowDown');
    fixture.wrapper.vm.$root.isDetailDrawerActive = false;
    await settle();

    expect(readerOpenings(fixture)).toEqual([]);
  });

  it('hears the keys with the focus on the page body, as soon as the drawer is open (no click first)', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 1);
    // What expanding leaves: the element that held the focus went away with the layout.
    document.activeElement?.blur?.();
    expect(document.activeElement).toBe(document.body);

    const key = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
    document.body.dispatchEvent(key);
    await settle();

    expect(key.defaultPrevented).toBe(true);
    expect(readerOpenings(fixture)).toEqual([2]);
  });

  it('leaves the keys to a drawer opened over it, and stops listening once closed', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await openFullScreenOn(fixture, 1);
    const drawerOnTop = {};
    window.eXo = { openedDrawers: [fixture.wrapper.vm.$refs.emailBoxDrawer, drawerOnTop] };
    try {
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }));
      await settle();
      expect(readerOpenings(fixture)).toEqual([]);

      window.eXo.openedDrawers.pop();
      await fixture.wrapper.setData({ emailBoxDrawer: false });
      document.body.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }));
      await settle();
      expect(readerOpenings(fixture)).toEqual([]);
    } finally {
      delete window.eXo;
    }
  });

  it('forgets a click in the narrow list once expanded (the list sat where the reader now is)', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    const narrowList = inDrawer(fixture, '<div class="drawerContent"><p>row</p></div>');
    narrowList.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await openFullScreenOn(fixture, 1);

    press(fixture, 'ArrowDown');
    await settle();

    expect(readerOpenings(fixture)).toEqual([2]);
  });

  it('stops listening once the drawer is destroyed', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await openFullScreenOn(fixture, 1);
    const root = fixture.wrapper.element;
    const service = fixture.service;
    fixture.wrapper.destroy();

    root.dispatchEvent(new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true }));
    await flush();

    expect(service.getEmailByRemoteId).not.toHaveBeenCalled();
    fixture = null;
  });
});

describe('an automatically opened mail is read only once the user stayed on it (EXO-90414)', () => {
  let fixture;

  afterEach(() => {
    jest.useRealTimers();
    fixture?.teardown();
  });

  /**
   * Advances the fake clock, then lets the promises it released settle.
   *
   * @param {Number} ms how far to advance
   * @returns {Promise<void>} resolved once settled
   */
  async function tick(ms) {
    jest.advanceTimersByTime(ms);
    for (let i = 0; i < 6; i++) {
      await Promise.resolve(); // eslint-disable-line no-await-in-loop
    }
  }

  /**
   * The read-status pushes the drawer sent.
   *
   * @returns {Array} the ids of each push marking read
   */
  function readPushes() {
    return fixture.service.updateEmailsReadStatus.mock.calls.filter(call => call[1] === true).map(call => call[0]);
  }

  /**
   * Mounts the drawer full screen on mail 1, with mails 2 and 3 unread, the clock faked.
   *
   * @returns {Promise<void>} resolved once mounted
   */
  async function mountOnFirst() {
    fixture = await mountDrawer([row(1), { ...row(2), read: false }, { ...row(3), read: false }]);
    await openFullScreenOn(fixture, 1);
    fixture.service.updateEmailsReadStatus.mockClear();
    jest.useFakeTimers();
  }

  it('leaves a mail walked past unread, and counts no opening for it', async () => {
    await mountOnFirst();

    press(fixture, 'ArrowDown');
    await tick(KEY_OPEN_DELAY_MS);
    expect(fixture.service.getEmailByRemoteId).toHaveBeenLastCalledWith(2, 'INBOX', { broadcast: false });
    await tick(AUTO_OPEN_MARK_READ_DELAY_MS - 100);
    press(fixture, 'ArrowDown', { target: inDrawer(fixture, '<div data-thread-key="<2@host>" tabindex="0"></div>') });
    await tick(KEY_OPEN_DELAY_MS);
    await tick(AUTO_OPEN_MARK_READ_DELAY_MS - 100);

    expect(readPushes()).toEqual([]);
    expect(fixture.service.broadcastOpenEmail).not.toHaveBeenCalled();
  });

  it('reads it, and counts one opening, once the user stayed on it', async () => {
    await mountOnFirst();

    press(fixture, 'ArrowDown');
    await tick(KEY_OPEN_DELAY_MS);
    await tick(AUTO_OPEN_MARK_READ_DELAY_MS);
    await tick(AUTO_OPEN_MARK_READ_DELAY_MS);

    expect(readPushes()).toEqual([[2]]);
    expect(fixture.service.broadcastOpenEmail).toHaveBeenCalledTimes(1);
  });

  it('reads a clicked mail at once, and counts its opening with the read itself', async () => {
    await mountOnFirst();

    fixture.wrapper.vm.openEmailDetailContent(2);
    await tick(0);

    expect(readPushes()).toEqual([[2]]);
    expect(fixture.service.getEmailByRemoteId).toHaveBeenLastCalledWith(2, 'INBOX');
    await tick(AUTO_OPEN_MARK_READ_DELAY_MS);
    expect(readPushes()).toEqual([[2]]);
    expect(fixture.service.broadcastOpenEmail).not.toHaveBeenCalled();
  });

  it('does not read a mail opened automatically once the user clicked another one', async () => {
    await mountOnFirst();

    press(fixture, 'ArrowDown');
    await tick(KEY_OPEN_DELAY_MS);
    expect(fixture.wrapper.vm.autoOpenReadPending).toBe(true);
    fixture.wrapper.vm.openEmailDetailContent(3);
    // The reader is free at once to read the clicked one's conversation.
    expect(fixture.wrapper.vm.autoOpenReadPending).toBe(false);
    await tick(AUTO_OPEN_MARK_READ_DELAY_MS);

    expect(readPushes()).toEqual([[3]]);
    expect(fixture.service.broadcastOpenEmail).not.toHaveBeenCalled();
  });

  it('applies to the next mail after an action, and to the first one in full screen', async () => {
    await mountOnFirst();

    fixture.wrapper.vm.$root.$emit('delete-email', [1]);
    await tick(0);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
    expect(readPushes()).toEqual([]);
    expect(fixture.wrapper.vm.autoOpenReadPending).toBe(true);
    await tick(AUTO_OPEN_MARK_READ_DELAY_MS);
    expect(readPushes()).toEqual([[2]]);

    fixture.wrapper.vm.updateExpand(false);
    await tick(200);
    await fixture.wrapper.setData({ email: null });
    fixture.wrapper.vm.updateExpand(true);
    await tick(200);
    // Mail 1 was deleted: the first of the list is now mail 2, read already.
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
    await tick(AUTO_OPEN_MARK_READ_DELAY_MS);
    expect(fixture.service.broadcastOpenEmail).toHaveBeenCalledTimes(2);
  });

  it('forgets the wait when the drawer collapses', async () => {
    await mountOnFirst();

    press(fixture, 'ArrowDown');
    await tick(KEY_OPEN_DELAY_MS);
    fixture.wrapper.vm.updateExpand(false);
    // The layout switches 200 ms later; the page then settles before the wait would end.
    await tick(200);
    await tick(AUTO_OPEN_MARK_READ_DELAY_MS);

    expect(readPushes()).toEqual([]);
    expect(fixture.service.broadcastOpenEmail).not.toHaveBeenCalled();
  });

  it('keeps the reader from reading the conversation of a mail opened automatically', () => {
    const emit = jest.fn();
    const opened = { mailRemoteId: 2, folder: 'INBOX', read: false };
    const reader = {
      email: opened,
      emails: [opened],
      threadKey: EmailConnectorMailBoxDrawerThreadContent.methods.threadKey,
      $root: { $emit: emit },
    };

    EmailConnectorMailBoxDrawerThreadContent.methods.markThreadRead.call({ ...reader, deferThreadRead: true });
    expect(emit).not.toHaveBeenCalled();

    // Per folder, with the folder (EXO-90416).
    EmailConnectorMailBoxDrawerThreadContent.methods.markThreadRead.call({ ...reader, deferThreadRead: false });
    expect(emit).toHaveBeenCalledWith('update-email-read-status', true, [2], 'INBOX');
  });
});

describe('the arrow keys and the next mail after an action work on search results (EXO-90414)', () => {
  let fixture;

  afterEach(() => fixture?.teardown());

  it('walks the hits in full screen, opening them as automatic openings', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await fixture.wrapper.setData({ expanded: true, searchTerm: 'mail' });
    await fixture.wrapper.vm.openSearchResult(fixture.wrapper.vm.mergedSearchResults[0]);
    await flush();
    fixture.service.getEmailByRemoteId.mockClear();

    press(fixture, 'ArrowDown');
    await settle();

    expect(readerOpenings(fixture)).toEqual([2]);
    expect(fixture.service.getEmailByRemoteId).toHaveBeenLastCalledWith(2, 'INBOX', { broadcast: false });
    expect(fixture.wrapper.vm.openedSearchKey).toBe('INBOX:2');
  });

  it('walks hits one message at a time, two of one conversation included', async () => {
    const sameConversation = [{ ...row(1), threadId: 't' }, { ...row(2), threadId: 't' }, row(3)];
    fixture = await mountDrawer(sameConversation);
    await fixture.wrapper.setData({ expanded: true, searchTerm: 'mail' });
    await fixture.wrapper.vm.openSearchResult(fixture.wrapper.vm.mergedSearchResults[0]);
    await flush();
    fixture.service.getEmailByRemoteId.mockClear();

    press(fixture, 'ArrowDown');
    await settle();

    expect(readerOpenings(fixture)).toEqual([2]);
  });

  it('opens the hit that took the place of one acted on, which leaves the results', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await fixture.wrapper.setData({ expanded: true, searchTerm: 'mail' });
    await fixture.wrapper.vm.openSearchResult(fixture.wrapper.vm.mergedSearchResults[1]);
    await flush();
    fixture.service.getEmailByRemoteId.mockClear();

    fixture.wrapper.vm.$root.$emit('delete-email', [2]);
    await flush();

    expect(fixture.wrapper.vm.mergedSearchResults.map(result => result.mailRemoteId)).toEqual([1, 3]);
    expect(readerOpenings(fixture)).toEqual([3]);
  });

  it('lets a click on another hit take over an automatic first hit still on its way', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await fixture.wrapper.setData({ expanded: true, searchTerm: 'mail' });
    // The first hit is not cached: pulling it in waits on the server (a running sync
    // holds it for seconds).
    let pulled;
    fixture.service.fetchSearchedEmail.mockImplementation(() => new Promise(resolve => pulled = resolve));
    const first = { ...fixture.wrapper.vm.mergedSearchResults[0], cached: false };
    fixture.wrapper.vm.openAutomatically(first);

    await fixture.wrapper.vm.openSearchResult(fixture.wrapper.vm.mergedSearchResults[1]);
    pulled();
    await flush();

    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
    expect(readerOpenings(fixture)).toEqual([2]);
  });

  it('raises no error for a hit opening the user already moved on from', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    await fixture.wrapper.setData({ expanded: true, searchTerm: 'mail' });
    let refuse;
    fixture.service.fetchSearchedEmail.mockImplementation(() => new Promise((resolve, reject) => refuse = reject));
    fixture.wrapper.vm.openSearchResult({ ...fixture.wrapper.vm.mergedSearchResults[0], cached: false });

    await fixture.wrapper.vm.openSearchResult(fixture.wrapper.vm.mergedSearchResults[1]);
    refuse(new Error('gone'));
    await flush();

    expect(fixture.alerts).toEqual([]);
  });

  it('lights the hit the reader shows and focuses the one the keys go to', async () => {
    const results = [row(1), row(2)];
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerSearchResults, {
      attachTo: document.body,
      propsData: { results, openedKey: 'INBOX:2' },
      mocks: { $t: key => key },
      stubs: {
        'email-connector-mail-box-drawer-search-result-item': {
          props: ['rowKey', 'opened'],
          render(createElement) {
            return createElement('div', { attrs: { tabindex: '0', 'data-thread-key': this.rowKey, 'data-opened': String(this.opened) } });
          },
        },
      },
    });

    expect(wrapper.findAll('[data-opened="true"]').wrappers.map(item => item.attributes('data-thread-key'))).toEqual(['INBOX:2']);
    await wrapper.vm.revealThread('INBOX:1');
    expect(document.activeElement.getAttribute('data-thread-key')).toBe('INBOX:1');
    wrapper.destroy();
  });
});

describe('a search hit is addressed in its own folder, never by its number alone (EXO-90414)', () => {
  let fixture;

  afterEach(() => {
    jest.useRealTimers();
    fixture?.teardown();
  });

  /**
   * A server search hit.
   *
   * @param {Number} mailRemoteId its UID
   * @param {String} folder the folder it is numbered in
   * @param {Object} extra further fields
   * @returns {Object} the hit
   */
  function hit(mailRemoteId, folder, extra = {}) {
    return { ...row(mailRemoteId), folder, subject: `archived ${mailRemoteId}`, cached: true, ...extra };
  }

  /**
   * Mounts the drawer listing INBOX:5 (unread), full screen, searching, the server
   * having found ARCHIVE:5 -- another message under the same number.
   *
   * @returns {Promise<void>} resolved once mounted
   */
  async function mountWithTwins() {
    fixture = await mountDrawer([{ ...row(5), read: false }, row(6)]);
    await fixture.wrapper.setData({ expanded: true, searchTerm: 'archived',
      searchServerResults: [hit(5, 'ARCHIVE', { read: false }), hit(7, 'ARCHIVE')] });
  }

  it('reads an automatically opened hit in its folder, not the listed mail sharing its number', async () => {
    await mountWithTwins();
    jest.useFakeTimers();

    fixture.wrapper.vm.openAutomatically(fixture.wrapper.vm.mergedSearchResults.find(result => result.mailRemoteId === 5));
    for (let i = 0; i < 8; i++) {
      await Promise.resolve(); // eslint-disable-line no-await-in-loop
    }
    jest.advanceTimersByTime(AUTO_OPEN_MARK_READ_DELAY_MS + 100);

    expect(fixture.service.updateEmailsReadStatus.mock.calls).toEqual([[[5], true, 'ARCHIVE']]);
    expect(fixture.wrapper.vm.emails.find(email => email.mailRemoteId === 5).read).toBe(false);
  });

  it('acts on the hit in its folder: delete, archive, spam, move and mark unread never reach the listed twin', async () => {
    await mountWithTwins();

    fixture.wrapper.vm.$root.$emit('delete-email', [5], 'ARCHIVE');
    fixture.wrapper.vm.$root.$emit('junk-email', [7], 'ARCHIVE');
    await flush();
    expect(fixture.service.deleteEmails).toHaveBeenCalledWith([5], 'ARCHIVE', true);
    expect(fixture.service.markAsJunk).toHaveBeenCalledWith([7], 'ARCHIVE', true);

    fixture.wrapper.vm.$root.$emit('archive-email', [5], 'CUSTOM:1');
    fixture.wrapper.vm.$root.$emit('move-email', [5], 'CUSTOM:1', 'SENT');
    fixture.wrapper.vm.$root.$emit('update-email-read-status', false, [5], 'SENT');
    await flush();
    expect(fixture.service.archiveEmails).toHaveBeenCalledWith([5], 'CUSTOM:1');
    expect(fixture.service.moveEmails).toHaveBeenCalledWith([5], 'SENT', 'CUSTOM:1');
    expect(fixture.service.updateEmailsReadStatus).toHaveBeenCalledWith([5], false, 'SENT');

    // The listed INBOX:5 was never touched, and is still listed.
    expect(fixture.service.deleteEmails).not.toHaveBeenCalledWith([5], 'INBOX', true);
    expect(fixture.wrapper.vm.emails.map(email => email.mailRemoteId)).toEqual([5, 6]);
  });

  it('undoes a move of the hit, not of the listed mail sharing its number', async () => {
    await mountWithTwins();
    fixture.wrapper.vm.searchServerResults[0].mailHeaderId = '<5-archived@host>';

    fixture.wrapper.vm.$root.$emit('move-email', [5], 'CUSTOM:1', 'ARCHIVE');
    await flush();
    await fixture.alerts[0].alertLinkCallback();

    expect(fixture.service.undoMoveEmails).toHaveBeenCalledWith(['<5-archived@host>'], 'CUSTOM:1', 'ARCHIVE');
  });

  it('files the move the picker was opened for in the folder it was opened from', () => {
    const emit = jest.fn();
    const picker = { mailRemoteIds: [5], sourceFolder: 'ARCHIVE', $root: { $emit: emit }, $refs: { moveToFolderDrawer: { close: jest.fn() } } };

    EmailConnectorMailBoxMoveToFolderDrawer.methods.moveTo.call(picker, { key: 'CUSTOM:1' });

    expect(emit).toHaveBeenCalledWith('move-email', [5], 'CUSTOM:1', 'ARCHIVE');
  });

  it('hides only the hit acted on, not every hit sharing its number', async () => {
    fixture = await mountDrawer([row(6)]);
    await fixture.wrapper.setData({ expanded: true, searchTerm: 'archived',
      searchServerResults: [hit(5, 'INBOX'), hit(5, 'ARCHIVE')] });

    fixture.wrapper.vm.$root.$emit('delete-email', [5], 'ARCHIVE');
    await flush();

    expect(fixture.wrapper.vm.mergedSearchResults.map(result => `${result.folder}:${result.mailRemoteId}`)).toEqual(['INBOX:5']);
  });

  it('starts the wait of a slow automatic opening only once it is on screen', async () => {
    fixture = await mountDrawer([row(6)]);
    await fixture.wrapper.setData({ expanded: true, searchTerm: 'archived',
      searchServerResults: [hit(8, 'ARCHIVE', { read: false, cached: false })] });
    jest.useFakeTimers();
    // Pulling the hit in takes three seconds (a synchronization holding the mailbox).
    fixture.service.fetchSearchedEmail.mockImplementation(() => new Promise(resolve => window.setTimeout(resolve, 3000)));

    fixture.wrapper.vm.openAutomatically(fixture.wrapper.vm.mergedSearchResults[0]);
    jest.advanceTimersByTime(3000);
    for (let i = 0; i < 12; i++) {
      await Promise.resolve(); // eslint-disable-line no-await-in-loop
    }
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(8);
    expect(fixture.wrapper.vm.autoOpenReadPending).toBe(true);
    jest.advanceTimersByTime(AUTO_OPEN_MARK_READ_DELAY_MS - 100);
    expect(fixture.service.updateEmailsReadStatus).not.toHaveBeenCalled();
    jest.advanceTimersByTime(100);

    expect(fixture.service.updateEmailsReadStatus.mock.calls).toEqual([[[8], true, 'ARCHIVE']]);
    expect(fixture.service.broadcastOpenEmail).toHaveBeenCalledTimes(1);
  });
});

describe('the list reveals the row the keys go to', () => {
  let wrapper;

  afterEach(() => {
    wrapper?.destroy();
    document.body.innerHTML = '';
  });

  /**
   * Mounts the list over many conversations, each row rendered as the focusable
   * element the real row carries.
   *
   * @param {Number} count how many conversations
   * @returns {Object} the wrapper
   */
  function mountList(count) {
    const localVue = createLocalVue();
    localVue.directive('intersect', {});
    const emails = Array.from({ length: count }, (value, index) => row(index + 1));
    return shallowMount(EmailConnectorMailBoxDrawerList, {
      localVue,
      attachTo: document.body,
      propsData: { emails },
      mocks: { $emailConnectorMailBoxService: emailConnectorMailBoxService },
      stubs: {
        'email-connector-mail-box-drawer-list-item': {
          props: ['thread'],
          render(createElement) {
            return createElement('div', { attrs: { tabindex: '0', 'data-thread-key': String(this.thread.threadId) } });
          },
        },
      },
    });
  }

  it('builds the rows up to one beyond the rendered window, then focuses and scrolls to it', async () => {
    wrapper = mountList(100);
    const scrolled = [];
    Element.prototype.scrollIntoView = function scrollIntoView(options) {
      scrolled.push([this.getAttribute('data-thread-key'), options]);
    };

    expect(wrapper.findAll('[data-thread-key]')).toHaveLength(40);
    await wrapper.vm.revealThread('<45@host>');

    expect(wrapper.findAll('[data-thread-key]').length).toBeGreaterThanOrEqual(45);
    expect(document.activeElement.getAttribute('data-thread-key')).toBe('<45@host>');
    expect(scrolled).toEqual([['<45@host>', { block: 'nearest' }]]);
    delete Element.prototype.scrollIntoView;
  });

  it('builds the next page when the row reached is the last one built', async () => {
    wrapper = mountList(100);

    await wrapper.vm.revealThread('<40@host>');

    expect(wrapper.findAll('[data-thread-key]')).toHaveLength(80);
  });

  it('stops listening to the opened row once destroyed', () => {
    wrapper = mountList(3);
    const root = wrapper.vm.$root;
    wrapper.destroy();
    wrapper = null;

    expect(root._events['set-opened'] || []).toHaveLength(0);
  });
});

// The mail drawer used to carry a full screen of its own, with the list beside the
// reader and the same moves as the mailbox's. There is one full-screen layout now, the
// mailbox drawer's (EXO-90415): the mail drawer is the narrow reader only, and hands
// its mail over to that full screen. What it still owes is to stay out of the way.
describe('the mail drawer is the narrow reader only (EXO-90415)', () => {
  let wrapper;
  let service;

  afterEach(() => {
    wrapper?.destroy();
    document.body.innerHTML = '';
  });

  /**
   * Mounts the mail drawer, open on one mail of its list.
   *
   * @param {Array} emails the list it was opened from
   * @param {Number} opened the UID open in the reader
   * @returns {Promise<void>} resolved once mounted
   */
  async function mountOpen(emails, opened) {
    service = serviceStub({
      isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
      getEmailByRemoteId: jest.fn((mailRemoteId, folder) => Promise.resolve({ mailRemoteId, folder })),
      broadcastOpenEmail: jest.fn(() => Promise.resolve()),
    });
    // Imported here so that a failure to mount it only fails these pins.
    const { default: EmailConnectorMailBoxDrawerListItemDetail } = await import('../EmailConnectorMailBoxDrawerListItemDetail.vue');
    wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetail, {
      attachTo: document.body,
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: service,
        $vuetify: { breakpoint: {}, rtl: false },
      },
      stubs: { 'exo-drawer': true },
    });
    await wrapper.setData({ emailDetailDrawer: true, emails, email: { mailRemoteId: opened, folder: 'INBOX' } });
  }

  it('keeps showing the mail after a move, as it did before', async () => {
    await mountOpen([row(1), row(2), row(3)], 2);

    wrapper.vm.$root.$emit('move-email', [2], 'CUSTOM:1');
    await flush();

    expect(wrapper.vm.selectEmailPlaceHolder).toBe(false);
    expect(wrapper.vm.emails.map(e => e.mailRemoteId)).toEqual([1, 2, 3]);
    expect(service.getEmailByRemoteId).not.toHaveBeenCalled();
  });

  it('opens nothing on its own after a delete: its toolbar closes it', async () => {
    await mountOpen([row(1), row(2), row(3)], 2);

    wrapper.vm.$root.$emit('delete-email', [2], 'INBOX');
    await flush();

    expect(wrapper.vm.emails.map(e => e.mailRemoteId)).toEqual([1, 3]);
    expect(wrapper.vm.email.mailRemoteId).toBe(2);
    expect(service.getEmailByRemoteId).not.toHaveBeenCalled();
  });

  it('never takes the arrow keys: the list they walk is the mailbox drawer\'s', async () => {
    await mountOpen([row(1), row(2), row(3)], 2);

    const event = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
    document.body.dispatchEvent(event);
    await settle();

    expect(event.defaultPrevented).toBe(false);
    expect(service.getEmailByRemoteId).not.toHaveBeenCalled();
  });
});

describe('the row the arrow keys focus is lit, without the browser\'s focus ring (EXO-90414)', () => {
  let wrapper;

  afterEach(() => {
    wrapper?.destroy();
    document.body.innerHTML = '';
  });

  it('on a row of the folder list, narrow and full screen', async () => {
    for (const expanded of [false, true]) {
      const localVue = createLocalVue();
      localVue.directive('touch', {});
      localVue.directive('touch-hold', {});
      const email = { ...row(3), content: { excerpt: 'x', attachments: [] } };
      wrapper = shallowMount(EmailConnectorMailBoxDrawerListItem, {
        localVue,
        attachTo: document.body,
        propsData: { email, expanded },
        mocks: {
          $t: key => key,
          // The dates need the portal's locale, which a unit test has none of.
          $emailConnectorMailBoxService: { ...emailConnectorMailBoxService, formatDateString: () => 'today' },
          $vuetify: { breakpoint: { smAndDown: false } },
        },
      });
      const focusable = wrapper.find('[data-thread-key]');

      focusable.element.focus();
      await wrapper.vm.$nextTick(); // eslint-disable-line no-await-in-loop

      expect(document.activeElement).toBe(focusable.element);
      expect(focusable.element.style.outline).toBe('none');
      expect(wrapper.classes()).toContain(expanded ? 'grey-lighten1-background-opacity-3' : 'light-grey-background-color');
      wrapper.destroy();
      wrapper = null;
    }
  });

  it('on a search hit', async () => {
    wrapper = shallowMount(EmailConnectorMailBoxDrawerSearchResultItem, {
      attachTo: document.body,
      propsData: { result: row(4), rowKey: 'INBOX:4' },
      mocks: { $t: key => key, $emailConnectorMailBoxService: { ...emailConnectorMailBoxService, formatDateString: () => 'today' } },
    });

    wrapper.element.focus();
    await wrapper.vm.$nextTick();

    expect(document.activeElement).toBe(wrapper.element);
    expect(wrapper.element.style.outline).toBe('none');
    expect(wrapper.classes()).toContain('light-grey-background-color');
  });
});
