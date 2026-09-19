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

// EXO-90412 — opening a message. The reader paints the conversation at once from the
// folder list's rows and fills it in as the server answers; the drawer's header bar is
// on only while the user is waiting for something they opened; and an answer to a
// message or a conversation the user has already left is dropped, never painted.
// A running sync is signalled on the same bar, not by a spinner of the toolbar's own.

/**
 * The platform drawer, reduced to the one prop these tests read.
 */
const ExoDrawerStub = {
  name: 'ExoDrawer',
  props: ['loading'],
  template: '<div><slot name="fullAppLeftTitle" /><slot name="content" /></div>',
};

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawerThreadContent from '../EmailConnectorMailBoxDrawerThreadContent.vue';
import EmailConnectorMailBoxDrawerListItemDetail from '../EmailConnectorMailBoxDrawerListItemDetail.vue';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerActions from '../EmailConnectorMailBoxDrawerActions.vue';
import EmailConnectorMailBoxDrawerListItemDetailContent from '../EmailConnectorMailBoxDrawerListItemDetailContent.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

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

/**
 * Lets every settled promise run its callbacks.
 *
 * @returns {Promise} resolved once the microtask queue is drained
 */
function flush() {
  return new Promise(resolve => setTimeout(resolve, 0));
}

/**
 * A service where every function the component may call answers an empty promise,
 * with the ones a test is about supplied for real.
 *
 * @param {Object} overrides the functions under test
 * @returns {Proxy} the service
 */
function serviceStub(overrides) {
  return new Proxy({
    isListingRow: emailConnectorMailBoxService.isListingRow,
    settleListingRow: emailConnectorMailBoxService.settleListingRow,
    isReadOnlyFolder: () => false,
    formatDateString: () => '',
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
 * A row as the folder listing returns it: no body, no recipients.
 *
 * @param {Number} uid the IMAP UID
 * @param {String} date the received date
 * @param {Object} extra more fields
 * @returns {Object} the row
 */
function listRow(uid, date, extra = {}) {
  return {
    mailRemoteId: uid,
    mailHeaderId: `<m${uid}@host>`,
    threadId: '<t@host>',
    folder: 'INBOX',
    subject: 'Subject',
    receivedDate: date,
    read: true,
    sender: { name: `Sender ${uid}` },
    content: { excerpt: `excerpt ${uid}`, attachments: [] },
    ...extra,
  };
}

/**
 * The same message as a full read returns it.
 *
 * @param {Object} row the list row
 * @returns {Object} the full message
 */
function full(row) {
  return { ...row, to: [], cc: [], bcc: [], content: { body: `<p>body ${row.mailRemoteId}</p>`, attachments: [] } };
}

describe('EmailConnectorMailBoxDrawerThreadContent — progressive conversation', () => {
  /**
   * Mounts the reader on an opened message.
   *
   * @param {Object} email the opened message
   * @param {Array} emails the folder list
   * @param {Object} answers the service functions
   * @returns {Object} {wrapper, service}
   */
  function mountReader(email, emails, answers) {
    const service = serviceStub(answers);
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerThreadContent, {
      propsData: { email, emails },
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: service,
        $vuetify: { breakpoint: {}, rtl: false },
      },
    });
    return { wrapper, service };
  }

  it('shows the listed rows at once, the opened one as a skeleton, before the conversation answers', async () => {
    const older = listRow(1, '2026-09-01T10:00:00Z');
    const latest = listRow(2, '2026-09-02T10:00:00Z', { threadCount: 3 });
    const thread = deferred();
    const { wrapper } = mountReader(latest, [latest, older], {
      getThreadByThreadId: jest.fn(() => thread.promise),
    });
    await flush();

    const vm = wrapper.vm;
    expect(vm.messages.map(message => message.mailRemoteId)).toEqual([1, 2]);
    expect(vm.expandedIds).toEqual(['INBOX-2']);
    expect(vm.isPartial(vm.messages[1])).toBe(true);
    // The sent reply the list does not hold stands as one skeleton strip.
    expect(vm.pendingSkeletons).toBe(1);
    // The header is told about the seeded rows only as a provisional conversation.
    expect(wrapper.emitted('thread-context').filter(event => event[0]).every(event => event[0].provisional)).toBe(true);
    expect(wrapper.emitted('loading').pop()).toEqual([true]);
    wrapper.destroy();
  });

  it('turns the loading off and announces the conversation once it lands', async () => {
    const older = listRow(1, '2026-09-01T10:00:00Z');
    const latest = listRow(2, '2026-09-02T10:00:00Z');
    const sent = full(listRow(3, '2026-09-03T10:00:00Z', { folder: 'SENT' }));
    const { wrapper } = mountReader(latest, [latest, older], {
      getThreadByThreadId: jest.fn(() => Promise.resolve([full(older), full(latest), sent])),
      completeThreadByThreadId: jest.fn(() => deferred().promise),
    });
    await flush();

    const vm = wrapper.vm;
    expect(vm.threadLanded).toBe(true);
    expect(vm.messages.map(message => message.mailRemoteId)).toEqual([1, 2, 3]);
    // Only the conversation's own latest is open, not the seed's default as well.
    expect(vm.expandedIds).toEqual(['SENT-3']);
    // Off although the archive pass is still running: nobody is waiting on it.
    expect(wrapper.emitted('loading').pop()).toEqual([false]);
    expect(wrapper.emitted('opened-partial').pop()).toEqual([false]);
    expect(wrapper.emitted('thread-context').pop()[0].messages).toHaveLength(3);
    wrapper.destroy();
  });

  it('drops the answer for a conversation the user has already left', async () => {
    const first = listRow(1, '2026-09-01T10:00:00Z', { threadId: '<first@host>' });
    const second = listRow(2, '2026-09-02T10:00:00Z', { threadId: '<second@host>' });
    const firstThread = deferred();
    const secondThread = deferred();
    const { wrapper } = mountReader(first, [first, second], {
      getThreadByThreadId: jest.fn(threadId => (threadId === '<first@host>' ? firstThread.promise : secondThread.promise)),
      completeThreadByThreadId: jest.fn(() => deferred().promise),
    });
    await flush();
    wrapper.setProps({ email: second });
    await flush();

    secondThread.resolve([full(second)]);
    await flush();
    firstThread.resolve([full(first)]);
    await flush();

    expect(wrapper.vm.messages.map(message => message.mailRemoteId)).toEqual([2]);
    expect(wrapper.emitted('loading').pop()).toEqual([false]);
    wrapper.destroy();
  });

  it('fills the opened body in from the full message when it answers before the conversation', async () => {
    const latest = listRow(2, '2026-09-02T10:00:00Z');
    const { wrapper } = mountReader(latest, [latest], {
      getThreadByThreadId: jest.fn(() => deferred().promise),
    });
    await flush();
    expect(wrapper.emitted('opened-partial').pop()).toEqual([true]);

    wrapper.setProps({ email: full(latest) });
    await flush();

    expect(wrapper.vm.isPartial(wrapper.vm.messages[0])).toBe(false);
    expect(wrapper.emitted('opened-partial').pop()).toEqual([false]);
    wrapper.destroy();
  });
});

describe('EmailConnectorMailBoxDrawerListItemDetail — opening a message', () => {
  /**
   * Mounts the detail drawer, closed.
   *
   * @param {Object} answers the service functions
   * @returns {Object} {wrapper, service}
   */
  function mountDrawer(answers) {
    const service = serviceStub(answers);
    const root = { $on: jest.fn(), $off: jest.fn(), $emit: jest.fn() };
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetail, {
      mocks: {
        $t: key => key,
        $root: root,
        $emailConnectorMailBoxService: service,
        $vuetify: { breakpoint: {}, rtl: false },
      },
    });
    return { wrapper, service };
  }

  it('opens on the listed row at once, without the loading bar for the message itself', async () => {
    const row = listRow(2, '2026-09-02T10:00:00Z');
    const { wrapper } = mountDrawer({ getEmailByRemoteId: jest.fn(() => deferred().promise) });
    wrapper.vm.open(2, [row], false, null);
    await flush();

    expect(wrapper.vm.email).toBe(row);
    expect(wrapper.vm.waitingForEmail).toBe(false);
    wrapper.destroy();
  });

  it('keeps the loading bar only while the message it cannot show yet is on its way', async () => {
    const answer = deferred();
    const { wrapper } = mountDrawer({ getEmailByRemoteId: jest.fn(() => answer.promise) });
    // Not in the list (a search hit, a favorite): nothing to open the reader on.
    wrapper.vm.open(7, [], false, null);
    await flush();
    expect(wrapper.vm.waitingForEmail).toBe(true);

    answer.resolve(full(listRow(7, '2026-09-02T10:00:00Z')));
    await flush();
    expect(wrapper.vm.waitingForEmail).toBe(false);
    expect(wrapper.vm.loadingEmail).toBe(false);
    wrapper.destroy();
  });

  it('drops the answer for a message the user has already left, and keeps the bar of the current one', async () => {
    const first = listRow(1, '2026-09-01T10:00:00Z');
    const second = listRow(2, '2026-09-02T10:00:00Z');
    const answers = { 1: deferred(), 2: deferred() };
    const { wrapper } = mountDrawer({ getEmailByRemoteId: jest.fn(uid => answers[uid].promise) });
    wrapper.vm.open(1, [first, second], false, null);
    wrapper.vm.open(2, [first, second], false, null);

    answers[1].resolve(full(first));
    await flush();
    expect(wrapper.vm.email).toBe(second);
    expect(wrapper.vm.loadingEmail).toBe(true);

    answers[2].resolve(full(second));
    await flush();
    expect(wrapper.vm.email.mailRemoteId).toBe(2);
    expect(wrapper.vm.email.content.body).toBe('<p>body 2</p>');
    expect(wrapper.vm.loadingEmail).toBe(false);
    wrapper.destroy();
  });

  it('settles the row rather than leaving a skeleton when the message cannot be read', async () => {
    const row = listRow(2, '2026-09-02T10:00:00Z');
    const { wrapper } = mountDrawer({ getEmailByRemoteId: jest.fn(() => Promise.reject(new Error('down'))) });
    wrapper.vm.open(2, [row], false, null);
    await flush();

    expect(emailConnectorMailBoxService.isListingRow(wrapper.vm.email)).toBe(false);
    expect(wrapper.vm.email.mailRemoteId).toBe(2);
    expect(wrapper.vm.loadingEmail).toBe(false);
    wrapper.destroy();
  });
});

describe('a running sync — the drawer bar, and no spinner of its own', () => {
  it('the mailbox drawer shows a running sync on its header bar', async () => {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: serviceStub({
          getEmailBox: jest.fn(() => Promise.resolve({ emails: [], folders: [], emailSyncStatus: 'SUCCESS' })),
          getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
        }),
        $emailConnectorCommonService: serviceStub({}),
        $vuetify: { breakpoint: {}, rtl: false },
      },
      stubs: { 'exo-drawer': ExoDrawerStub },
    });
    await wrapper.setData({ syncInProgress: false });
    expect(wrapper.findComponent(ExoDrawerStub).props('loading')).toBeFalsy();
    await wrapper.setData({ syncInProgress: true });
    expect(wrapper.findComponent(ExoDrawerStub).props('loading')).toBe(true);
    wrapper.vm.stopAutoRefresh();
    wrapper.destroy();
  });

  it('the detail drawer shows it too once widened, where the mailbox toolbar sits in its title', async () => {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetail, {
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: serviceStub({}),
        $vuetify: { breakpoint: {}, rtl: false },
      },
      stubs: { 'exo-drawer': ExoDrawerStub },
    });
    await wrapper.setData({ syncInProgress: true, expanded: false });
    expect(wrapper.findComponent(ExoDrawerStub).props('loading')).toBeFalsy();
    await wrapper.setData({ expanded: true });
    expect(wrapper.findComponent(ExoDrawerStub).props('loading')).toBe(true);
    wrapper.destroy();
  });

  it('the mailbox toolbar renders no sync spinner while a sync runs', () => {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerActions, {
      propsData: { emails: [], selectedEmails: [], syncInProgress: true },
      // Renders a tooltip's activator, where the spinner used to sit: left as an
      // unknown element, its scoped slot would never render and this test could not
      // tell the spinner from its absence.
      stubs: { 'v-tooltip': { template: '<div><slot name="activator" :on="{}" :attrs="{}" /><slot /></div>' } },
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: serviceStub({}),
        $vuetify: { breakpoint: {}, rtl: false },
      },
    });
    expect(wrapper.html()).not.toContain('email-box-sync-loader');
    expect(wrapper.html()).toContain('email-connector-mail-box-drawer-action-menu');
    wrapper.destroy();
  });
});

describe('while a conversation is on its way, the header acts on what is on screen', () => {
  it('announces the seeded rows as a provisional conversation, never to the summary seam', async () => {
    const older = listRow(1, '2026-09-01T10:00:00Z');
    const latest = listRow(2, '2026-09-02T10:00:00Z');
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerThreadContent, {
      propsData: { email: latest, emails: [latest, older] },
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: serviceStub({ getThreadByThreadId: jest.fn(() => deferred().promise) }),
        $vuetify: { breakpoint: {}, rtl: false },
      },
    });
    await flush();

    const context = wrapper.emitted('thread-context').pop()[0];
    expect(context.provisional).toBe(true);
    expect(context.messages.map(message => message.mailRemoteId)).toEqual([1, 2]);
    expect(emailConnectorMailBoxService.threadIdsInFolder(latest, context)).toEqual([1, 2]);
    expect(wrapper.vm.summaryExtensionParams.messages).toEqual([]);
    wrapper.destroy();
  });
});

describe('the detail drawer never reopens a message removed while it loads', () => {
  // With nothing left to open, the delete leaves the placeholder (EXO-90414 moves the
  // reader on to the next mail when there is one -- see the test after this one).
  it('drops the answer once a delete switched to the placeholder', async () => {
    const listed = listRow(2, '2026-09-02T10:00:00Z');
    const answer = deferred();
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetail, {
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: serviceStub({ getEmailByRemoteId: jest.fn(() => answer.promise) }),
        $vuetify: { breakpoint: {}, rtl: false },
      },
    });
    const vm = wrapper.vm;
    await wrapper.setData({ expanded: true });
    vm.open(2, [listed], false, null);
    vm.$root.$emit('delete-email', [2]);
    expect(vm.selectEmailPlaceHolder).toBe(true);
    expect(vm.loadingEmail).toBe(false);

    answer.resolve(full(listed));
    await flush();
    expect(vm.selectEmailPlaceHolder).toBe(true);
    expect(vm.email?.content?.body).toBeUndefined();
    wrapper.destroy();
  });

  it('moves on to the next mail, never back to the one deleted while it loads (EXO-90414)', async () => {
    const listed = listRow(2, '2026-09-02T10:00:00Z', { threadId: '<t2@host>' });
    const other = listRow(3, '2026-09-03T10:00:00Z', { threadId: '<t3@host>' });
    const answer = deferred();
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetail, {
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: serviceStub({
          getEmailByRemoteId: jest.fn(mailRemoteId => (mailRemoteId === 2 ? answer.promise : new Promise(() => null))),
        }),
        $vuetify: { breakpoint: {}, rtl: false },
      },
    });
    const vm = wrapper.vm;
    await wrapper.setData({ expanded: true });
    vm.open(2, [other, listed], false, null);
    vm.$root.$emit('delete-email', [2]);
    expect(vm.selectEmailPlaceHolder).toBe(false);
    expect(vm.email).toBe(other);

    answer.resolve(full(listed));
    await flush();
    expect(vm.email).toBe(other);
    expect(vm.email?.content?.body).toBeUndefined();
    wrapper.destroy();
  });

  it('Retry reads a message that could not be read again', async () => {
    const listed = listRow(2, '2026-09-02T10:00:00Z');
    const reads = [deferred(), deferred()];
    let call = 0;
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetail, {
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: serviceStub({ getEmailByRemoteId: jest.fn(() => reads[call++].promise) }),
        $vuetify: { breakpoint: {}, rtl: false },
      },
    });
    const vm = wrapper.vm;
    vm.open(2, [listed], false, null);
    reads[0].reject(new Error('down'));
    await flush();
    expect(vm.email.unavailable).toBe(true);

    vm.$root.$emit('retry-email-read', vm.email);
    reads[1].resolve(full(listed));
    await flush();
    expect(vm.email.unavailable).toBeUndefined();
    expect(vm.email.content.body).toBe('<p>body 2</p>');
    wrapper.destroy();
  });
});

describe('a message whose full copy could not be read', () => {
  it('says so with a Retry, and offers no reply, reply all or forward', () => {
    const unavailable = emailConnectorMailBoxService.settleListingRow(listRow(2, '2026-09-02T10:00:00Z'));
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetailContent, {
      propsData: { email: unavailable },
      stubs: { 'v-btn': { template: '<button @click="$emit(\'click\')"><slot /></button>' } },
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: serviceStub({}),
        $vuetify: { breakpoint: {}, rtl: false },
      },
    });
    const html = wrapper.html();
    expect(html).toContain('emailConnector.mailBox.list.drawer.detail.unavailable');
    expect(html).not.toContain('fa-reply');
    expect(html).not.toContain('email-connector-mail-box-drawer-list-item-detail-action-menu');
    expect(html).not.toContain('email-connector-mail-box-drawer-list-item-detail-body');

    const retries = [];
    wrapper.vm.$root.$on('retry-email-read', email => retries.push(email));
    wrapper.findAll('button').filter(button => button.text() === 'emailConnector.mailBox.list.drawer.detail.unavailable.retry').at(0).trigger('click');
    expect(retries).toEqual([unavailable]);
    wrapper.destroy();
  });
});

describe('the expanded detail drawer, opening an unread message', () => {
  it('stays on the message when the conversation is marked read before the message answers', async () => {
    const opened = listRow(1, '2026-09-01T10:00:00Z', { threadId: '<first@host>' });
    const unread = listRow(2, '2026-09-02T10:00:00Z', { read: false, threadId: null });
    const answers = { 1: Promise.resolve(full(opened)), 2: deferred() };
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetail, {
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: serviceStub({
          getEmailByRemoteId: jest.fn(uid => (uid === 2 ? answers[2].promise : answers[1])),
          getThreadByThreadId: jest.fn(() => Promise.resolve([full(opened)])),
          completeThreadByThreadId: jest.fn(() => deferred().promise),
        }),
        $vuetify: { breakpoint: {}, rtl: false },
      },
      stubs: {
        'exo-drawer': { template: '<div><slot name="content" /></div>' },
        'email-connector-mail-box-drawer-thread-content': EmailConnectorMailBoxDrawerThreadContent,
      },
    });
    const vm = wrapper.vm;
    vm.open(1, [opened, unread], false, null);
    await flush();
    await wrapper.setData({ expanded: true });

    // The wide layout's click on the unread row: the reader lands (no conversation id,
    // so at once) and marks it read before the message's own answer.
    vm.openEmailDetailContent(2);
    await flush();
    const reader = wrapper.findComponent(EmailConnectorMailBoxDrawerThreadContent).vm;
    expect(vm.selectEmailPlaceHolder).toBe(false);
    expect(vm.loadingEmail).toBe(true);

    answers[2].resolve(full(unread));
    await flush();
    expect(vm.selectEmailPlaceHolder).toBe(false);
    expect(vm.email.content.body).toBe('<p>body 2</p>');
    expect(wrapper.findComponent(EmailConnectorMailBoxDrawerThreadContent).vm).toBe(reader);
    wrapper.destroy();
  });
});
