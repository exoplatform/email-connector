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

// EXO-90421 -- in the full-screen mailbox, a mail dragged from the list onto an entry of
// the folder column does what that entry's own action does: a move with its Undo, a
// Delete, a "Mark as spam", or a category assignment addressed by folder. The row and
// the column speak on the drawer's root (onDrawerRoot), so a drag travels the real
// events from the row to the drop and on to the drawer's handlers.

import Vue from 'vue';
import { createLocalVue, shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerListItem from '../EmailConnectorMailBoxDrawerListItem.vue';
import EmailConnectorMailBoxDrawerNavigation from '../EmailConnectorMailBoxDrawerNavigation.vue';
import EmailConnectorMailBoxDrawerSearchResultItem from '../EmailConnectorMailBoxDrawerSearchResultItem.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';
import { DRAG_MIME } from '../../../js/EmailConnectorMailBoxDragAndDrop.js';

Vue.config.ignoredElements.push(/^email-connector-/, 'exo-confirm-dialog');

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'SENT', type: 'BUILT_IN', syncEnabled: true },
  { key: 'DRAFTS', type: 'BUILT_IN', syncEnabled: true },
  { key: 'ARCHIVE', type: 'BUILT_IN', syncEnabled: true },
  { key: 'TRASH', type: 'BUILT_IN', syncEnabled: true },
  { key: 'JUNK', type: 'BUILT_IN', syncEnabled: true },
  { key: 'CUSTOM:1', type: 'CUSTOM', displayName: 'Factures', path: 'Factures', syncEnabled: true },
];

const CATEGORIES = [{ id: 11, name: 'Important', icon: 'fa-exclamation' }];

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

const t = (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key);

/**
 * A service whose every function answers an empty promise, the ones under test supplied.
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
 * A listed message, alone in its conversation.
 *
 * @param {Number} mailRemoteId the IMAP UID
 * @param {String} folder the folder it is numbered in
 * @returns {Object} the row
 */
function row(mailRemoteId, folder = 'INBOX') {
  return {
    mailRemoteId,
    folder,
    mailHeaderId: `<${folder}-${mailRemoteId}@host>`,
    threadId: `<${folder}-${mailRemoteId}@host>`,
    read: true,
    subject: `${folder} ${mailRemoteId}`,
    sender: { name: 'Alice', address: 'alice@host' },
    receivedDate: new Date(2026, 8, 30 - mailRemoteId).toISOString(),
    categoryIds: [],
    content: { excerpt: 'x', attachments: [] },
  };
}

/**
 * A drag event as the browser dispatches it, its dataTransfer recording what it is given.
 *
 * @param {String} type the event type
 * @param {Array<String>} types the types the transfer carries
 * @returns {Event} the event, cancelable
 */
function dragEvent(type, types = [DRAG_MIME]) {
  const event = new Event(type, { bubbles: true, cancelable: true });
  const data = {};
  event.dataTransfer = {
    types,
    data,
    dropEffect: 'none',
    effectAllowed: 'all',
    setData: (format, value) => data[format] = value,
    setDragImage: jest.fn(),
  };
  return event;
}

/**
 * Makes a separately mounted component speak on the drawer's root, as it does when the
 * drawer renders it.
 *
 * @param {Object} fixture the mounted drawer
 * @param {Object} child the component's wrapper
 * @returns {Object} the wrapper
 */
function onDrawerRoot(fixture, child) {
  child.vm.$root.$emit = (...args) => fixture.wrapper.vm.$root.$emit(...args);
  fixture.mounted.push(child);
  return child;
}

/**
 * Mounts the mailbox drawer, open and full screen over a listed inbox.
 *
 * @param {Array} emails the listed rows
 * @param {Object} answers the server's answers to override
 * @returns {Promise<Object>} {wrapper, service, alerts, teardown}
 */
async function mountDrawer(emails, answers = {}) {
  const service = serviceStub({
    ...emailConnectorMailBoxService,
    formatDateString: () => 'today',
    getEmailByRemoteId: jest.fn((mailRemoteId, folder) => Promise.resolve({ ...row(mailRemoteId, folder), to: [] })),
    getEmailBox: jest.fn(() => Promise.resolve({ emails, folders: FOLDERS, emailSyncStatus: 'SUCCESS' })),
    getAvailableEmailCategories: jest.fn(() => Promise.resolve(CATEGORIES)),
    getEmailCategoryUnreadCounts: jest.fn(() => Promise.resolve({})),
    getSubcategoryIds: jest.fn(() => Promise.resolve([])),
    deleteEmails: jest.fn(() => Promise.resolve({ failedDeletions: 0 })),
    markAsJunk: jest.fn(() => Promise.resolve({ failedJunkMoves: 0 })),
    moveEmails: jest.fn(() => Promise.resolve({ failedMoves: 0 })),
    undoMoveEmails: jest.fn(() => Promise.resolve({ failedUndos: 0 })),
    linkEmailsToCategory: jest.fn(() => answers.linkEmailsToCategory || Promise.resolve({ linked: 1 })),
    broadcastOpenEmail: jest.fn(() => Promise.resolve()),
  });
  const wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
    attachTo: document.body,
    mocks: {
      $t: t,
      $emailConnectorMailBoxService: service,
      $emailConnectorCommonService: serviceStub({}),
      $vuetify: { breakpoint: {}, rtl: false },
    },
    stubs: { 'exo-drawer': true },
  });
  await wrapper.setData({
    emailBoxDrawer: true,
    emailBox: { emails, folders: FOLDERS, emailSyncStatus: 'SUCCESS' },
    emailCategories: CATEGORIES,
    expanded: true,
  });
  const alerts = [];
  const alertListener = event => alerts.push(event.detail);
  document.addEventListener('alert-message', alertListener);
  const mounted = [];
  return {
    wrapper,
    service,
    alerts,
    mounted,
    teardown: () => {
      document.removeEventListener('alert-message', alertListener);
      mounted.forEach(child => child.destroy());
      wrapper.vm.stopAutoRefresh();
      wrapper.destroy();
      document.body.innerHTML = '';
    },
  };
}

/**
 * Mounts one list row on the drawer's root, as the full-screen list renders it.
 *
 * @param {Object} fixture the mounted drawer
 * @param {Number} mailRemoteId the row's UID
 * @param {Object} props further props (selection, layout)
 * @param {Boolean} phone whether the screen is a phone's
 * @returns {Object} the row's wrapper
 */
function mountRow(fixture, mailRemoteId, props = {}, phone = false) {
  const localVue = createLocalVue();
  localVue.directive('touch', {});
  localVue.directive('touch-hold', {});
  const email = fixture.wrapper.vm.emails.find(e => e.mailRemoteId === mailRemoteId);
  const item = shallowMount(EmailConnectorMailBoxDrawerListItem, {
    localVue,
    propsData: { email, expanded: true, ...props },
    mocks: {
      $t: t,
      $emailConnectorMailBoxService: fixture.service,
      $vuetify: { breakpoint: { smAndDown: phone } },
    },
  });
  return onDrawerRoot(fixture, item);
}

/**
 * Mounts the folder column on the drawer's root, fed as the drawer feeds it.
 *
 * @param {Object} fixture the mounted drawer
 * @param {Boolean} rail whether it is folded to a rail
 * @returns {Object} the column's wrapper
 */
function mountColumn(fixture, rail = false) {
  const column = shallowMount(EmailConnectorMailBoxDrawerNavigation, {
    propsData: {
      folders: fixture.wrapper.vm.availableFolders,
      categories: CATEGORIES,
      dragSource: fixture.wrapper.vm.emailDrag,
      rail,
    },
    mocks: { $t: t, $emailConnectorMailBoxService: fixture.service },
    stubs: {
      'v-tooltip': {
        props: ['value'],
        template: '<div class="tip" :data-open="String(!!value)"><slot name="activator" :on="{}" :attrs="{}" /><slot /></div>',
      },
    },
  });
  return onDrawerRoot(fixture, column);
}

/**
 * The drop zone of a column entry.
 *
 * @param {Object} column the column's wrapper
 * @param {String} value the entry's value (folder:KEY, category:ID)
 * @returns {HTMLElement} the element carrying the drag listeners
 */
function zone(column, value) {
  return column.find(`v-list-item[value="${value}"]`).element.parentElement;
}

/**
 * Drags a row onto a column entry: dragstart on the row, the column told what the
 * drawer now holds, dragover and drop on the entry.
 *
 * @param {Object} fixture the mounted drawer
 * @param {Object} item the row's wrapper
 * @param {Object} column the column's wrapper
 * @param {String} value the entry's value
 * @returns {Promise<Object>} {over, drop}: the dispatched dragover and drop events
 */
async function dragOnto(fixture, item, column, value) {
  item.element.dispatchEvent(dragEvent('dragstart', []));
  await column.setProps({ dragSource: fixture.wrapper.vm.emailDrag });
  const over = dragEvent('dragover');
  zone(column, value).dispatchEvent(over);
  const drop = dragEvent('drop');
  zone(column, value).dispatchEvent(drop);
  await flush();
  return { over, drop };
}

describe('dragging a mail onto the folder column (EXO-90421)', () => {
  let fixture;

  afterEach(() => fixture?.teardown());

  it('moves it exactly as "Move to..." does: gone from the list, Undo offered, the next mail opened', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await fixture.wrapper.vm.openListedEmail(fixture.wrapper.vm.emails[1]);
    await flush();
    fixture.service.getEmailByRemoteId.mockClear();

    const { over, drop } = await dragOnto(fixture, mountRow(fixture, 2), mountColumn(fixture), 'folder:CUSTOM:1');

    expect(over.defaultPrevented).toBe(true);
    expect(drop.defaultPrevented).toBe(true);
    expect(fixture.service.moveEmails).toHaveBeenCalledWith([2], 'INBOX', 'CUSTOM:1');
    expect(fixture.wrapper.vm.emails.map(e => e.mailRemoteId)).toEqual([1, 3]);
    expect(fixture.service.getEmailByRemoteId.mock.calls.map(([id]) => id)).toEqual([3]);
    expect(fixture.wrapper.vm.emailDrag).toBeNull();
    const [toast] = fixture.alerts;
    expect(toast.alertMessage).toBe('emailConnector.mailBox.list.drawer.move.email.success|Factures');

    await toast.alertLinkCallback();

    expect(fixture.service.undoMoveEmails).toHaveBeenCalledWith(['<INBOX-2@host>'], 'CUSTOM:1', 'INBOX');
  });

  it('two selected rows of three messages each say "Move 2 emails", and move all six messages', async () => {
    const conversation = (threadId, uids) => uids.map(uid => ({ ...row(uid), threadId }));
    fixture = await mountDrawer([...conversation('a', [1, 2, 3]), ...conversation('b', [4, 5, 6]), row(7)]);
    const selectedEmails = ['INBOX:1', 'INBOX:2', 'INBOX:3', 'INBOX:4', 'INBOX:5', 'INBOX:6'];
    await fixture.wrapper.setData({ selectMode: true, selectedEmails });
    const thread = emailConnectorMailBoxService.groupEmailsByThread(fixture.wrapper.vm.emails)
      .find(candidate => candidate.threadId === 'a');
    const item = mountRow(fixture, thread.latest.mailRemoteId,
      { thread, selectMode: true, selectedEmails, emails: fixture.wrapper.vm.emails });
    const start = dragEvent('dragstart', []);

    item.element.dispatchEvent(start);

    const [image] = start.dataTransfer.setDragImage.mock.calls[0];
    expect(image.textContent).toBe('emailConnector.mailBox.list.drawer.drag.emails|2');
    expect([...fixture.wrapper.vm.emailDrag.ids].sort()).toEqual([1, 2, 3, 4, 5, 6]);
  });

  it('a single row of three messages says "Move 1 email"', async () => {
    fixture = await mountDrawer([1, 2, 3].map(uid => ({ ...row(uid), threadId: 'a' })));
    const thread = emailConnectorMailBoxService.groupEmailsByThread(fixture.wrapper.vm.emails)[0];
    const start = dragEvent('dragstart', []);

    mountRow(fixture, thread.latest.mailRemoteId, { thread, emails: fixture.wrapper.vm.emails }).element.dispatchEvent(start);

    expect(start.dataTransfer.setDragImage.mock.calls[0][0].textContent).toBe('emailConnector.mailBox.list.drawer.drag.email');
    expect(fixture.wrapper.vm.emailDrag.ids).toHaveLength(3);
  });

  it('a selected row drags the selection, one move from its folder, and ends the selection', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    await fixture.wrapper.setData({ selectMode: true, selectedEmails: ['INBOX:1', 'INBOX:3'] });

    const item = mountRow(fixture, 1, { selectMode: true, selectedEmails: ['INBOX:1', 'INBOX:3'] });
    await dragOnto(fixture, item, mountColumn(fixture), 'folder:ARCHIVE');

    expect(fixture.service.moveEmails).toHaveBeenCalledTimes(1);
    expect(fixture.service.moveEmails).toHaveBeenCalledWith([1, 3], 'INBOX', 'ARCHIVE');
    expect(fixture.wrapper.vm.emails.map(e => e.mailRemoteId)).toEqual([2]);
    expect(fixture.wrapper.vm.selectMode).toBe(false);
  });

  it('the Trash deletes it and the Spam marks it as spam, from its own folder', async () => {
    fixture = await mountDrawer([row(1), row(2), row(3)]);
    const column = mountColumn(fixture);

    await dragOnto(fixture, mountRow(fixture, 1), column, 'folder:TRASH');
    await dragOnto(fixture, mountRow(fixture, 2), column, 'folder:JUNK');

    expect(fixture.service.deleteEmails).toHaveBeenCalledWith([1], 'INBOX', true);
    expect(fixture.service.markAsJunk).toHaveBeenCalledWith([2], 'INBOX', true);
    expect(fixture.service.moveEmails).not.toHaveBeenCalled();
    expect(fixture.wrapper.vm.emails.map(e => e.mailRemoteId)).toEqual([3]);
  });

  it('a category is assigned to it, by folder, and it stays in the list', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    const announced = [];
    fixture.wrapper.vm.$root.$on('email-categories-updated', update => announced.push(update));

    const { over } = await dragOnto(fixture, mountRow(fixture, 2), mountColumn(fixture), 'category:11');

    // Announced with its folder, for the reader's category bar to follow when it shows the mail.
    expect(announced).toEqual([{ mailRemoteIds: [2], categoryId: 11, assign: true, folder: 'INBOX' }]);

    expect(over.defaultPrevented).toBe(true);
    expect(fixture.service.linkEmailsToCategory).toHaveBeenCalledWith([2], 11, 'INBOX');
    expect(fixture.wrapper.vm.emails.map(e => e.mailRemoteId)).toEqual([1, 2]);
    expect(fixture.wrapper.vm.emails[1].categoryIds).toEqual([11]);
    expect(fixture.alerts.map(alert => alert.alertMessage))
      .toEqual(['emailConnector.mailBox.list.drawer.categorize.email.success|Important']);
  });

  it('a search hit is categorized in ITS folder, and the listed mail with the same UID is left alone', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    const hit = shallowMount(EmailConnectorMailBoxDrawerSearchResultItem, {
      propsData: { result: row(2, 'ARCHIVE'), rowKey: 'ARCHIVE:2', draggableHit: true },
      mocks: { $t: t, $emailConnectorMailBoxService: fixture.service, $vuetify: { breakpoint: { smAndDown: false } } },
    });
    onDrawerRoot(fixture, hit);
    expect(hit.attributes('draggable')).toBe('true');

    await dragOnto(fixture, hit, mountColumn(fixture), 'category:11');

    expect(fixture.service.linkEmailsToCategory).toHaveBeenCalledWith([2], 11, 'ARCHIVE');
    expect(fixture.wrapper.vm.emails[1].categoryIds).toEqual([]);
  });

  it('says so when the category could not be assigned', async () => {
    fixture = await mountDrawer([row(1)], { linkEmailsToCategory: Promise.reject(new Error('refused')) });

    await dragOnto(fixture, mountRow(fixture, 1), mountColumn(fixture), 'category:11');

    expect(fixture.alerts.map(alert => [alert.alertType, alert.alertMessage]))
      .toEqual([['error', 'emailConnector.mailBox.list.drawer.categorize.email.error|1']]);
    expect(fixture.wrapper.vm.emails[0].categoryIds).toEqual([]);
  });

  it.each(['folder:SENT', 'folder:DRAFTS', 'folder:INBOX'])('%s takes nothing: no drop allowed, nothing sent', async value => {
    fixture = await mountDrawer([row(1)]);

    const { over, drop } = await dragOnto(fixture, mountRow(fixture, 1), mountColumn(fixture), value);

    expect(over.defaultPrevented).toBe(false);
    expect(drop.defaultPrevented).toBe(false);
    expect(fixture.service.moveEmails).not.toHaveBeenCalled();
    expect(fixture.service.deleteEmails).not.toHaveBeenCalled();
    expect(fixture.wrapper.vm.emails.map(e => e.mailRemoteId)).toEqual([1]);
  });

  it('ignores what is not mail of this mailbox, and a drop with no drag under way', async () => {
    fixture = await mountDrawer([row(1)]);
    const column = mountColumn(fixture);

    const file = dragEvent('dragover', ['Files']);
    zone(column, 'folder:CUSTOM:1').dispatchEvent(file);
    zone(column, 'folder:CUSTOM:1').dispatchEvent(dragEvent('drop'));
    await flush();

    expect(file.defaultPrevented).toBe(false);
    expect(fixture.service.moveEmails).not.toHaveBeenCalled();

    // A drag under way, but a file dropped: still nothing.
    mountRow(fixture, 1).element.dispatchEvent(dragEvent('dragstart', []));
    await column.setProps({ dragSource: fixture.wrapper.vm.emailDrag });
    zone(column, 'folder:CUSTOM:1').dispatchEvent(dragEvent('drop', ['Files']));
    await flush();
    expect(fixture.service.moveEmails).not.toHaveBeenCalled();
  });

  it('as a rail, the entry under the pointer shows its name, and a drop there works', async () => {
    fixture = await mountDrawer([row(1)]);
    const column = mountColumn(fixture, true);
    mountRow(fixture, 1).element.dispatchEvent(dragEvent('dragstart', []));
    await column.setProps({ dragSource: fixture.wrapper.vm.emailDrag });

    zone(column, 'folder:CUSTOM:1').dispatchEvent(dragEvent('dragover'));
    await column.vm.$nextTick();
    const tip = zone(column, 'folder:CUSTOM:1').parentElement;
    expect(tip.getAttribute('data-open')).toBe('true');
    expect(zone(column, 'folder:CUSTOM:1').style.boxShadow).toContain('inset');

    zone(column, 'folder:CUSTOM:1').dispatchEvent(dragEvent('drop'));
    await flush();
    expect(fixture.service.moveEmails).toHaveBeenCalledWith([1], 'INBOX', 'CUSTOM:1');
  });

  it('forgets the drag on dragend, on a window blur, on leaving full screen and on close', async () => {
    fixture = await mountDrawer([row(1)]);
    const item = mountRow(fixture, 1);
    const start = () => item.element.dispatchEvent(dragEvent('dragstart', []));

    start();
    expect(fixture.wrapper.vm.emailDrag).toEqual({ folder: 'INBOX', ids: [1] });
    item.element.dispatchEvent(dragEvent('dragend'));
    expect(fixture.wrapper.vm.emailDrag).toBeNull();

    start();
    window.dispatchEvent(new Event('blur'));
    expect(fixture.wrapper.vm.emailDrag).toBeNull();

    start();
    await fixture.wrapper.setData({ expanded: false });
    expect(fixture.wrapper.vm.emailDrag).toBeNull();

    await fixture.wrapper.setData({ expanded: true });
    start();
    fixture.wrapper.vm.close();
    expect(fixture.wrapper.vm.emailDrag).toBeNull();
  });
});

describe('which rows may be dragged (EXO-90421)', () => {
  let fixture;

  afterEach(() => fixture?.teardown());

  it('in full screen only, never on a phone', async () => {
    fixture = await mountDrawer([row(1)]);

    expect(mountRow(fixture, 1).attributes('draggable')).toBe('true');
    expect(mountRow(fixture, 1, { expanded: false }).attributes('draggable')).toBeUndefined();
    expect(mountRow(fixture, 1, {}, true).attributes('draggable')).toBeUndefined();
  });

  it('refuses a selection across folders, and fades the rows being dragged', async () => {
    fixture = await mountDrawer([row(1), row(2)]);
    const acrossFolders = mountRow(fixture, 1, { selectMode: true, selectedEmails: ['INBOX:1', 'ARCHIVE:1'] });
    const refused = dragEvent('dragstart', []);

    acrossFolders.element.dispatchEvent(refused);

    expect(refused.defaultPrevented).toBe(true);
    expect(fixture.wrapper.vm.emailDrag).toBeNull();

    const dragged = mountRow(fixture, 2, { dragSource: { folder: 'INBOX', ids: [2] } });
    const other = mountRow(fixture, 1, { dragSource: { folder: 'INBOX', ids: [2] } });
    const sameUidElsewhere = mountRow(fixture, 2, { dragSource: { folder: 'ARCHIVE', ids: [2] } });
    expect(dragged.element.style.opacity).toBe('0.5');
    expect(other.element.style.opacity).toBe('');
    expect(sameUidElsewhere.element.style.opacity).toBe('');
  });

  it('a hit of the narrow list is not draggable', async () => {
    fixture = await mountDrawer([row(1)]);
    const hit = shallowMount(EmailConnectorMailBoxDrawerSearchResultItem, {
      propsData: { result: row(2, 'ARCHIVE'), rowKey: 'ARCHIVE:2' },
      mocks: { $t: t, $emailConnectorMailBoxService: fixture.service },
    });
    fixture.mounted.push(hit);

    expect(hit.attributes('draggable')).toBeUndefined();
  });
});
