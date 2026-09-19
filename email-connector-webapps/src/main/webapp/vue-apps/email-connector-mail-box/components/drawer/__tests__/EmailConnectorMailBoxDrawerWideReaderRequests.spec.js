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

// EXO-90412 — the mailbox drawer's own wide reader. Clicking a row opens it at once
// on that row and reads the full message behind it; an answer for a message the user
// has left — another row clicked, the message deleted — is dropped, and the loading
// state it put on ends with it.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerThreadContent from '../EmailConnectorMailBoxDrawerThreadContent.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'TRASH', type: 'BUILT_IN', syncEnabled: true },
];

/**
 * A promise whose settlement the test decides.
 *
 * @returns {Object} {promise, resolve, reject}
 */
function deferred() {
  let resolve;
  let reject;
  const promise = new Promise((res, rej) => {
    resolve = res;
    reject = rej;
  });
  return { promise, resolve, reject };
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

/**
 * A service whose every function the drawer may call answers an empty promise, with
 * the few a test is about supplied for real.
 *
 * @param {Object} overrides the functions under test
 * @returns {Proxy} the service
 */
function serviceStub(overrides) {
  return new Proxy({
    folderLabel: emailConnectorMailBoxService.folderLabel,
    isListingRow: emailConnectorMailBoxService.isListingRow,
    settleListingRow: emailConnectorMailBoxService.settleListingRow,
    ...overrides,
  }, {
    get(target, name) {
      if (!(name in target)) {
        target[name] = jest.fn(() => Promise.resolve(null));
      }
      return target[name];
    },
  });
}

/**
 * A listed inbox row: no body, no recipients.
 *
 * @param {Number} mailRemoteId the IMAP UID
 * @returns {Object} the row
 */
function row(mailRemoteId) {
  return {
    mailRemoteId,
    mailHeaderId: `<m${mailRemoteId}@host>`,
    threadId: `<t${mailRemoteId}@host>`,
    folder: 'INBOX',
    subject: `mail ${mailRemoteId}`,
    receivedDate: '2026-09-17T10:00:00Z',
    read: true,
    content: { excerpt: 'excerpt' },
  };
}

/**
 * The same message as a full read returns it.
 *
 * @param {Object} listed the list row
 * @returns {Object} the full message
 */
function full(listed) {
  return { ...listed, to: [], cc: [], bcc: [], content: { body: `body ${listed.mailRemoteId}` } };
}

/**
 * Mounts the mailbox drawer, open and expanded, over a listed inbox.
 *
 * @param {Array} emails the listed rows
 * @param {Function} getEmailByRemoteId the single-message read
 * @param {Object} stubs the components to stub, or to render for real
 * @returns {Object} {wrapper, service, teardown}
 */
async function mountWide(emails, getEmailByRemoteId, stubs = { 'exo-drawer': true }) {
  const service = serviceStub({
    getEmailByRemoteId: jest.fn(getEmailByRemoteId),
    deleteEmails: jest.fn(() => Promise.resolve({ failedDeletions: 0 })),
    getEmailBox: jest.fn(() => Promise.resolve({ emails, folders: FOLDERS, emailSyncStatus: 'SUCCESS' })),
    getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
  });
  const wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
    mocks: {
      $t: key => key,
      $emailConnectorMailBoxService: service,
      $emailConnectorCommonService: serviceStub({}),
      $vuetify: { breakpoint: {}, rtl: false },
    },
    stubs,
  });
  await wrapper.setData({ emailBox: { emails, folders: FOLDERS }, emailBoxDrawer: true, expanded: true });
  return {
    wrapper,
    service,
    teardown: () => {
      wrapper.vm.stopAutoRefresh();
      wrapper.destroy();
    },
  };
}

describe('EmailConnectorMailBoxDrawer — the wide reader\'s message requests', () => {
  it('drops the answer for a row the user clicked before the current one', async () => {
    const first = row(1);
    const second = row(2);
    const answers = { 1: deferred(), 2: deferred() };
    const fixture = await mountWide([first, second], uid => answers[uid].promise);
    const vm = fixture.wrapper.vm;

    vm.openEmailDetailContent(1);
    vm.openEmailDetailContent(2);
    expect(vm.email).toBe(second);

    answers[1].resolve(full(first));
    await flush();
    expect(vm.email).toBe(second);
    expect(vm.loadingEmail).toBe(true);

    answers[2].resolve(full(second));
    await flush();
    expect(vm.email.content.body).toBe('body 2');
    expect(vm.loadingEmail).toBe(false);
    fixture.teardown();
  });

  it('a listed click ends the loading an unlisted open had put on', async () => {
    const listed = row(2);
    const answers = { 9: deferred(), 2: deferred() };
    const fixture = await mountWide([listed], uid => answers[uid].promise);
    const vm = fixture.wrapper.vm;

    // UID 9 is not in the list: nothing to open the reader on, the content waits.
    vm.openEmailDetailContent(9);
    expect(vm.loading).toBe(true);

    vm.openEmailDetailContent(2);
    expect(vm.loading).toBe(false);
    expect(vm.email).toBe(listed);

    answers[9].resolve(full(row(9)));
    await flush();
    expect(vm.email).toBe(listed);
    expect(vm.loading).toBe(false);
    fixture.teardown();
  });

  it('never reopens a message deleted while its full copy was on its way', async () => {
    const listed = row(2);
    const answer = deferred();
    const fixture = await mountWide([listed, row(3)], () => answer.promise);
    const vm = fixture.wrapper.vm;

    vm.openEmailDetailContent(2);
    vm.$root.$emit('delete-email', [2]);
    expect(vm.selectEmailPlaceHolder).toBe(true);
    expect(vm.loadingEmail).toBe(false);

    answer.resolve(full(listed));
    await flush();
    expect(vm.selectEmailPlaceHolder).toBe(true);
    expect(vm.email?.content?.body).toBeUndefined();
    fixture.teardown();
  });

  it('a failed read leaves the row marked unavailable, and Retry reads it again', async () => {
    const listed = row(2);
    const reads = [deferred(), deferred()];
    let call = 0;
    const fixture = await mountWide([listed], () => reads[call++].promise);
    const vm = fixture.wrapper.vm;

    vm.openEmailDetailContent(2);
    reads[0].reject(new Error('down'));
    await flush();
    expect(vm.email.unavailable).toBe(true);

    vm.$root.$emit('retry-email-read', vm.email);
    expect(fixture.service.getEmailByRemoteId).toHaveBeenCalledTimes(2);
    reads[1].resolve(full(listed));
    await flush();
    expect(vm.email.unavailable).toBeUndefined();
    expect(vm.email.content.body).toBe('body 2');
    fixture.teardown();
  });

  it('marking the conversation read before the message answers never closes the message being opened', async () => {
    // Unread, and with no conversation id: the reader has nothing to read and marks
    // the conversation read at once, before the message's own answer.
    const unread = { ...row(2), read: false, threadId: null };
    const answer = deferred();
    const fixture = await mountWide([unread, row(3)], () => answer.promise, {
      'exo-drawer': { template: '<div><slot name="content" /></div>' },
      'email-connector-mail-box-drawer-thread-content': EmailConnectorMailBoxDrawerThreadContent,
    });
    const vm = fixture.wrapper.vm;

    vm.openEmailDetailContent(2);
    await flush();
    const reader = fixture.wrapper.findComponent(EmailConnectorMailBoxDrawerThreadContent).vm;
    // Whoever marks it read — this drawer at open, the reader on landing, the list
    // row's menu — the message stays open and its request alive.
    vm.$root.$emit('update-email-read-status', true, [2]);
    await flush();
    expect(vm.selectEmailPlaceHolder).toBe(false);
    expect(vm.loadingEmail).toBe(true);

    answer.resolve(full(unread));
    await flush();
    expect(vm.selectEmailPlaceHolder).toBe(false);
    expect(vm.email.content.body).toBe('body 2');
    expect(fixture.wrapper.findComponent(EmailConnectorMailBoxDrawerThreadContent).vm).toBe(reader);
    fixture.teardown();
  });

  it('marking the opened message UNREAD still puts it away', async () => {
    const listed = row(2);
    const fixture = await mountWide([listed, row(3)], () => Promise.resolve(full(listed)));
    const vm = fixture.wrapper.vm;
    vm.openEmailDetailContent(2);
    await flush();

    vm.$root.$emit('update-email-read-status', false, [2]);
    expect(vm.selectEmailPlaceHolder).toBe(true);
    fixture.teardown();
  });
});
