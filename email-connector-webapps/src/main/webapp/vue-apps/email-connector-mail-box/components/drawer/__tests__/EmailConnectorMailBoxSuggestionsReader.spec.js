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

// EXO-90875 -- the Suggestions view is a list like the others: in full screen its first
// mail opens on entry, the arrow keys walk it as they walk the search results, and a mail
// opened from it is read as its conversation, by the thread id its row carries, so its
// Automations panel shows the suggestions waiting on it.

import Vue from 'vue';
import { shallowMount } from '@vue/test-utils';

// The waiting suggestions are an observable store when the page's Vue is global, as it
// is in the portal: made so before the modules holding it load.
global.Vue = Vue;
const EmailConnectorMailBoxDrawer = require('../EmailConnectorMailBoxDrawer.vue').default;
const EmailConnectorMailBoxDrawerThreadContent = require('../EmailConnectorMailBoxDrawerThreadContent.vue').default;
const EmailConnectorMailBoxSuggestionsList = require('../EmailConnectorMailBoxSuggestionsList.vue').default;
const emailConnectorMailBoxService = require('../../../js/EmailConnectorMailBoxService.js');
const { KEY_OPEN_DELAY_MS } = require('../../../js/EmailConnectorMailBoxListNavigation.js');
const { refreshWaitingSuggestions } = require('../../../js/EmailConnectorMailFilters.js');
const userSettingService = require('../../../../email-connector-user-setting/js/EmailConnectorUserSettingService.js');

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'ARCHIVE', type: 'BUILT_IN', syncEnabled: true },
];

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

// Long enough for the arrow keys' reader opening to have fired and landed.
const settle = () => new Promise(resolve => setTimeout(resolve, KEY_OPEN_DELAY_MS + 20));

const t = key => key;

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
 * A mail of the Suggestions view, as its endpoint answers it.
 *
 * @param {Number} mailRemoteId its UID
 * @param {String} folder the folder it is numbered in
 * @param {Object} extra further fields
 * @returns {Object} the row
 */
function suggestion(mailRemoteId, folder = 'INBOX', extra = {}) {
  return {
    emailId: 100 + mailRemoteId,
    mailRemoteId,
    folder,
    mailHeaderId: `<${mailRemoteId}@host>`,
    threadId: `<thread-${mailRemoteId}@host>`,
    subject: `mail ${mailRemoteId}`,
    sender: { name: 'Alice', address: 'alice@host' },
    receivedDate: new Date(2026, 0, 30 - mailRemoteId).toISOString(),
    read: true,
    starred: false,
    cached: true,
    waitingCount: 2,
    ...extra,
  };
}

/**
 * Has the waiting suggestions read answer the given mails, and reads them.
 *
 * @param {Array} mails the mails with a suggestion waiting
 * @returns {Promise<Array>} the mails, read
 */
async function suggestionsRead(mails) {
  jest.spyOn(userSettingService, 'getWaitingSuggestionMails').mockResolvedValue(mails.map(mail => mail.mailHeaderId));
  jest.spyOn(userSettingService, 'getWaitingSuggestionEmails').mockResolvedValue(mails);
  await refreshWaitingSuggestions(true);
  return mails;
}

// The Suggestions view as the drawer renders it, with the reveal the arrow keys call.
const suggestionsListStub = {
  name: 'SuggestionsListStub',
  props: ['emails', 'readerKey'],
  methods: {
    revealThread: jest.fn(),
  },
  render(createElement) {
    return createElement('div');
  },
};

// The reader, with what the drawer hands it.
const readerStub = {
  name: 'ReaderStub',
  props: ['email', 'emails'],
  render(createElement) {
    return createElement('div');
  },
};

/**
 * Mounts the mailbox drawer, open, over an inbox -- empty unless given.
 *
 * @param {Object} options {expanded}: whether in full screen; {emails}: the inbox's rows;
 *        {categories}: the add-on's categories; {subcategoryIds}: what a category's
 *        expansion answers, the category alone by default
 * @returns {Promise<Object>} {wrapper, service, teardown}
 */
async function mountDrawer({ expanded = true, emails = [], categories = [], subcategoryIds = id => Promise.resolve([id]) } = {}) {
  const service = serviceStub({
    folderLabel: emailConnectorMailBoxService.folderLabel,
    isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
    getEmailByRemoteId: jest.fn((mailRemoteId, folder) => Promise.resolve({ id: 100 + mailRemoteId, mailRemoteId, folder, subject: `mail ${mailRemoteId}` })),
    getEmailBox: jest.fn(folder => Promise.resolve({ emails: folder === 'INBOX' ? emails : [], folders: FOLDERS, emailSyncStatus: 'SUCCESS' })),
    getAvailableEmailCategories: jest.fn(() => Promise.resolve(categories)),
    getSubcategoryIds: jest.fn(subcategoryIds),
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
    stubs: {
      'exo-drawer': true,
      'pinneable-drawer': { template: '<div><slot name="fullAppLeftContent" /><slot name="content" /></div>' },
      'email-connector-mail-box-suggestions-list': suggestionsListStub,
      'email-connector-mail-box-drawer-thread-content': readerStub,
    },
  });
  await wrapper.setData({
    emailBoxDrawer: true,
    expanded,
    emailBox: { emails, folders: FOLDERS, emailSyncStatus: 'SUCCESS' },
  });
  return {
    wrapper,
    service,
    teardown: () => {
      wrapper.vm.stopAutoRefresh();
      wrapper.destroy();
      document.body.innerHTML = '';
    },
  };
}

/**
 * The UIDs and folders the reader was asked to open, in order.
 *
 * @param {Object} fixture the mounted drawer
 * @returns {Array<Array>} [uid, folder, options] of each read
 */
function readerOpenings(fixture) {
  return fixture.service.getEmailByRemoteId.mock.calls;
}

/**
 * Presses a key on the page, the way it reaches the drawer.
 *
 * @param {Object} fixture the mounted drawer
 * @param {String} key the key
 * @returns {void}
 */
function press(fixture, key) {
  fixture.wrapper.element.dispatchEvent(new KeyboardEvent('keydown', { key, bubbles: true, cancelable: true }));
}

describe('the Suggestions view opens its first mail on entry in full screen (EXO-90875)', () => {
  let fixture;

  afterEach(() => {
    fixture?.teardown();
    jest.restoreAllMocks();
    suggestionsListStub.methods.revealThread.mockClear();
  });

  it('opens the first mail of the view, as an automatic opening, in its own folder, and lights its row', async () => {
    await suggestionsRead([suggestion(7, 'ARCHIVE'), suggestion(8)]);
    fixture = await mountDrawer();

    fixture.wrapper.vm.onSwitchFolder(emailConnectorMailBoxService.SUGGESTIONS_VIEW);
    await flush();
    await flush();

    expect(readerOpenings(fixture)).toEqual([[7, 'ARCHIVE', { broadcast: false }]]);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(7);
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(false);
    expect(fixture.wrapper.findComponent(suggestionsListStub).props('readerKey')).toBe('ARCHIVE:7');
    expect(suggestionsListStub.methods.revealThread).toHaveBeenCalledWith('ARCHIVE:7');
  });

  it('keeps the mail it opened on screen when the folders\' listing is read again: the view is not that listing', async () => {
    await suggestionsRead([suggestion(7, 'ARCHIVE')]);
    fixture = await mountDrawer();
    fixture.wrapper.vm.onSwitchFolder(emailConnectorMailBoxService.SUGGESTIONS_VIEW);
    await flush();
    await flush();

    await fixture.wrapper.setData({ emailBox: { emails: [], folders: FOLDERS, emailSyncStatus: 'SUCCESS' } });
    await flush();

    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(false);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(7);
  });

  it('waits for the view\'s mails when none was read yet, and opens the first once they land', async () => {
    await suggestionsRead([]);
    fixture = await mountDrawer();

    fixture.wrapper.vm.onSwitchFolder(emailConnectorMailBoxService.SUGGESTIONS_VIEW);
    // The view reads what waits as it opens, and says so (its loading event).
    await fixture.wrapper.setData({ suggestionsLoading: true });
    await flush();
    await flush();
    expect(readerOpenings(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.autoSelectPending).toBe(true);

    // Opened as soon as the view's mails land, the view still saying it reads.
    await suggestionsRead([suggestion(8)]);
    await flush();

    expect(readerOpenings(fixture)).toEqual([[8, 'INBOX', { broadcast: false }]]);
    await fixture.wrapper.setData({ suggestionsLoading: false });
    await flush();
    expect(readerOpenings(fixture).length).toBe(1);
  });

  it('stops waiting once the view read nothing, so a mail arriving later is not opened for the user', async () => {
    await suggestionsRead([]);
    fixture = await mountDrawer();
    fixture.wrapper.vm.onSwitchFolder(emailConnectorMailBoxService.SUGGESTIONS_VIEW);
    await fixture.wrapper.setData({ suggestionsLoading: true });
    await flush();
    await flush();
    expect(fixture.wrapper.vm.autoSelectPending).toBe(true);

    await fixture.wrapper.setData({ suggestionsLoading: false });

    expect(fixture.wrapper.vm.autoSelectPending).toBe(false);
  });

  it('opens nothing in the narrow drawer, where the view is the list', async () => {
    await suggestionsRead([suggestion(7, 'ARCHIVE')]);
    fixture = await mountDrawer({ expanded: false });

    fixture.wrapper.vm.onSwitchFolder(emailConnectorMailBoxService.SUGGESTIONS_VIEW);
    await flush();
    await flush();

    expect(readerOpenings(fixture)).toEqual([]);
  });
});

describe('a category picked on a view lists the inbox narrowed to it, as picked from the inbox (EXO-90885)', () => {
  const IMPORTANT = { id: 11, name: 'Important', nameId: 'emailImportantCategory' };
  const INVITATION = { id: 12, name: 'Invitation', nameId: 'emailInvitationCategory' };
  const CATEGORIES = [IMPORTANT, INVITATION];
  let fixture;

  afterEach(() => {
    fixture?.teardown();
    jest.restoreAllMocks();
    suggestionsListStub.methods.revealThread.mockClear();
  });

  /**
   * A row of the inbox's list, in the given categories.
   *
   * @param {Number} mailRemoteId its UID, the smallest the newest
   * @param {Array<Number>} categoryIds its categories
   * @returns {Object} the row
   */
  function inboxRow(mailRemoteId, categoryIds) {
    return suggestion(mailRemoteId, 'INBOX', { waitingCount: 0, categoryIds });
  }

  /**
   * The UIDs of the rows the list on screen holds.
   *
   * @returns {Array<Number>} the UIDs
   */
  function listed() {
    return fixture.wrapper.vm.listedEmails.map(row => row.mailRemoteId);
  }

  /**
   * Mounts the drawer over an inbox of three mails -- one in no category, the newest,
   * one Important, one an Invitation -- and lists the given view, its first mail opened
   * in full screen.
   *
   * @param {String} view the view's key
   * @param {Object} options what mountDrawer takes on top
   * @returns {Promise<void>} resolved once the view is listed
   */
  async function onView(view, options = {}) {
    await suggestionsRead([suggestion(7, 'ARCHIVE')]);
    fixture = await mountDrawer({ emails: [inboxRow(1, []), inboxRow(2, [IMPORTANT.id]), inboxRow(3, [INVITATION.id])], categories: CATEGORIES, ...options });
    await flush();
    fixture.wrapper.vm.onSwitchFolder(view);
    await flush();
    await flush();
    fixture.service.getEmailByRemoteId.mockClear();
  }

  it.each([
    ['Important', IMPORTANT, 2],
    ['a category of the user\'s', INVITATION, 3],
  ])('from Suggestions, %s: the inbox is listed narrowed to it, lit and titled, its first mail opened', async (label, category, uid) => {
    await onView(emailConnectorMailBoxService.SUGGESTIONS_VIEW);
    expect(fixture.wrapper.vm.suggestionsView).toBe(true);

    fixture.wrapper.vm.$root.$emit('open-category-view', category.id);
    await flush();
    await flush();
    await flush();

    expect(fixture.wrapper.vm.currentFolder).toBe('INBOX');
    expect(fixture.wrapper.vm.suggestionsView).toBe(false);
    expect(fixture.wrapper.vm.categoryViewId).toBe(category.id);
    expect(listed()).toEqual([uid]);
    expect(fixture.wrapper.vm.titleSuffix).toBe(category.name);
    expect(readerOpenings(fixture)).toEqual([[uid, 'INBOX', { broadcast: false }]]);
  });

  it('from Scheduled: the inbox is listed narrowed to the category', async () => {
    await onView(emailConnectorMailBoxService.SCHEDULED_VIEW);
    expect(fixture.wrapper.vm.scheduledView).toBe(true);

    fixture.wrapper.vm.openCategoryView(INVITATION.id);
    await flush();
    await flush();
    await flush();

    expect(fixture.wrapper.vm.scheduledView).toBe(false);
    expect(fixture.wrapper.vm.currentFolder).toBe('INBOX');
    expect(listed()).toEqual([3]);
    expect(readerOpenings(fixture)).toEqual([[3, 'INBOX', { broadcast: false }]]);
  });

  it('in the narrow drawer: the inbox is listed narrowed to the category, nothing opened', async () => {
    await onView(emailConnectorMailBoxService.SUGGESTIONS_VIEW, { expanded: false });

    fixture.wrapper.vm.openCategoryView(IMPORTANT.id);
    await flush();
    await flush();

    expect(fixture.wrapper.vm.currentFolder).toBe('INBOX');
    expect(listed()).toEqual([2]);
    expect(readerOpenings(fixture)).toEqual([]);
  });

  it('from a category, Suggestions leaves it: the view\'s mails, the category no longer lit nor titled', async () => {
    await onView('INBOX');
    fixture.wrapper.vm.openCategoryView(IMPORTANT.id);
    await flush();
    await flush();
    expect(listed()).toEqual([2]);

    fixture.wrapper.vm.onSwitchFolder(emailConnectorMailBoxService.SUGGESTIONS_VIEW);
    await flush();
    await flush();

    expect(fixture.wrapper.vm.categoryViewId).toBe(null);
    expect(listed()).toEqual([7]);
    expect(fixture.wrapper.vm.titleSuffix).not.toContain(IMPORTANT.name);
  });

  it('from a search: the search ends and the category\'s list shows', async () => {
    await onView('INBOX');
    await fixture.wrapper.setData({ searchTerm: 'mail' });
    expect(fixture.wrapper.vm.searchActive).toBe(true);

    fixture.wrapper.vm.openCategoryView(INVITATION.id);
    await flush();
    await flush();

    expect(fixture.wrapper.vm.searchActive).toBe(false);
    expect(listed()).toEqual([3]);
  });

  it('from a search under the lit category: picking it again lists it, the search ended, not the whole inbox', async () => {
    await onView('INBOX');
    fixture.wrapper.vm.openCategoryView(IMPORTANT.id);
    await flush();
    await flush();
    await fixture.wrapper.setData({ searchTerm: 'mail' });

    fixture.wrapper.vm.openCategoryView(IMPORTANT.id);
    await flush();
    await flush();

    expect(fixture.wrapper.vm.searchActive).toBe(false);
    expect(fixture.wrapper.vm.categoryViewId).toBe(IMPORTANT.id);
    expect(listed()).toEqual([2]);
  });

  it('in full screen, picking the lit category during a search leaves the hit for the category\'s first mail', async () => {
    await onView('INBOX');
    fixture.wrapper.vm.openCategoryView(IMPORTANT.id);
    await flush();
    await flush();
    // A hit outside Important is in the reader.
    await fixture.wrapper.setData({ searchTerm: 'mail', email: inboxRow(3, [INVITATION.id]), selectEmailPlaceHolder: false });
    fixture.service.getEmailByRemoteId.mockClear();

    fixture.wrapper.vm.openCategoryView(IMPORTANT.id);
    await flush();
    await flush();

    expect(listed()).toEqual([2]);
    expect(readerOpenings(fixture)).toEqual([[2, 'INBOX', { broadcast: false }]]);
  });

  it('lists the category itself and opens its first mail when its expansion fails', async () => {
    await onView(emailConnectorMailBoxService.SUGGESTIONS_VIEW, { subcategoryIds: () => Promise.reject(new Error('down')) });

    fixture.wrapper.vm.openCategoryView(INVITATION.id);
    await flush();
    await flush();
    await flush();

    expect(listed()).toEqual([3]);
    expect(readerOpenings(fixture)).toEqual([[3, 'INBOX', { broadcast: false }]]);
  });

  it('opens no mail of the inbox before the category is expanded, when its expansion lands after the inbox', async () => {
    let expand;
    await onView(emailConnectorMailBoxService.SUGGESTIONS_VIEW, {
      subcategoryIds: id => new Promise(resolve => expand = () => resolve([id])),
    });

    fixture.wrapper.vm.openCategoryView(INVITATION.id);
    await flush();
    await flush();
    // The inbox is listed, the category not expanded yet: its newest mail, in no
    // category, is not opened.
    expect(fixture.wrapper.vm.folderLoading).toBe(false);
    expect(readerOpenings(fixture)).toEqual([]);

    expand();
    await flush();
    await flush();

    expect(readerOpenings(fixture)).toEqual([[3, 'INBOX', { broadcast: false }]]);
  });
});

describe('the arrow keys walk the Suggestions view as they walk the search results (EXO-90875)', () => {
  let fixture;

  afterEach(() => {
    fixture?.teardown();
    jest.restoreAllMocks();
    suggestionsListStub.methods.revealThread.mockClear();
  });

  it('opens the mail the key went to in full screen, as an automatic opening, one mail per row', async () => {
    // Two mails of one conversation: one row each, as the view lists them.
    await suggestionsRead([suggestion(7, 'ARCHIVE', { threadId: '<t@host>' }), suggestion(8, 'INBOX', { threadId: '<t@host>' }), suggestion(9)]);
    fixture = await mountDrawer();
    fixture.wrapper.vm.onSwitchFolder(emailConnectorMailBoxService.SUGGESTIONS_VIEW);
    await flush();
    await flush();
    fixture.service.getEmailByRemoteId.mockClear();

    press(fixture, 'ArrowDown');
    await settle();

    expect(readerOpenings(fixture)).toEqual([[8, 'INBOX', { broadcast: false }]]);
    expect(suggestionsListStub.methods.revealThread).toHaveBeenLastCalledWith('INBOX:8');
    expect(fixture.wrapper.findComponent(suggestionsListStub).props('readerKey')).toBe('INBOX:8');

    press(fixture, 'ArrowUp');
    await settle();

    expect(readerOpenings(fixture).map(([uid]) => uid)).toEqual([8, 7]);
  });

  it('only moves the focus in the narrow drawer, where Enter opens the row', async () => {
    await suggestionsRead([suggestion(7, 'ARCHIVE'), suggestion(8)]);
    fixture = await mountDrawer({ expanded: false });
    await fixture.wrapper.setData({ currentFolder: emailConnectorMailBoxService.SUGGESTIONS_VIEW });

    press(fixture, 'ArrowDown');
    await settle();

    expect(suggestionsListStub.methods.revealThread).toHaveBeenLastCalledWith('ARCHIVE:7');
    expect(readerOpenings(fixture)).toEqual([]);
  });
});

describe('a mail opened from the Suggestions view is read as its conversation (EXO-90875)', () => {
  let fixture;

  afterEach(() => {
    fixture?.teardown();
    jest.restoreAllMocks();
  });

  it('hands the full-screen reader the view\'s rows, which carry the conversation\'s thread id', async () => {
    const mails = await suggestionsRead([suggestion(7, 'ARCHIVE'), suggestion(8)]);
    fixture = await mountDrawer();
    await fixture.wrapper.setData({ currentFolder: emailConnectorMailBoxService.SUGGESTIONS_VIEW });

    fixture.wrapper.vm.$root.$emit('open-suggested-email', { mailRemoteId: 8, folder: 'INBOX', cached: true });
    await flush();

    const reader = fixture.wrapper.findComponent(readerStub);
    expect(reader.props('email').mailRemoteId).toBe(8);
    expect(reader.props('emails')).toEqual(mails);
  });

  it('hands the mail drawer the view\'s rows in the narrow drawer', async () => {
    const mails = await suggestionsRead([suggestion(7, 'ARCHIVE'), suggestion(8)]);
    fixture = await mountDrawer({ expanded: false });
    await fixture.wrapper.setData({ currentFolder: emailConnectorMailBoxService.SUGGESTIONS_VIEW });
    const openings = [];
    fixture.wrapper.vm.$root.$on('open-email-detail-drawer', (...args) => openings.push(args));

    fixture.wrapper.vm.$root.$emit('open-suggested-email', { mailRemoteId: 7, folder: 'ARCHIVE', cached: true });
    await flush();

    expect(openings.length).toBe(1);
    expect(openings[0][0]).toBe(7);
    expect(openings[0][1]).toEqual(mails);
    expect(openings[0][6]).toBe('ARCHIVE');
  });

  it('hands the mail drawer the view\'s rows when going back from full screen on a Suggestions mail', async () => {
    const mails = await suggestionsRead([suggestion(7, 'ARCHIVE'), suggestion(8)]);
    fixture = await mountDrawer();
    await fixture.wrapper.setData({ currentFolder: emailConnectorMailBoxService.SUGGESTIONS_VIEW });
    fixture.wrapper.vm.$root.$emit('open-suggested-email', { mailRemoteId: 8, folder: 'INBOX', cached: true });
    await flush();
    const handovers = [];
    fixture.wrapper.vm.$root.$on('collapse-mail-box-on-email', handover => handovers.push(handover));

    fixture.wrapper.vm.handEmailBackToMailDrawer();

    expect(handovers.length).toBe(1);
    expect(handovers[0].email.mailRemoteId).toBe(8);
    expect(handovers[0].emails).toEqual(mails);
  });
});

describe('the reader reads a Suggestions mail by its row\'s thread id, and its panel by the copy the conversation gives (EXO-90875)', () => {
  // The panel, with what the reader hands it.
  const automationsStub = {
    name: 'AutomationsStub',
    props: ['email', 'messages'],
    render(createElement) {
      return createElement('div');
    },
  };

  /**
   * Mounts the reader on a mail.
   *
   * @param {Object} email the opened copy
   * @param {Array} emails the rows handed with it
   * @param {Array} conversation what the conversation read answers
   * @returns {Object} {wrapper, service}
   */
  function mountReader(email, emails, conversation) {
    const service = serviceStub({
      isListingRow: emailConnectorMailBoxService.isListingRow,
      settleListingRow: emailConnectorMailBoxService.settleListingRow,
      isReadOnlyFolder: () => false,
      formatDateString: () => '',
      getThreadByThreadId: jest.fn(() => Promise.resolve(conversation)),
      completeThreadByThreadId: jest.fn(() => new Promise(() => null)),
    });
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerThreadContent, {
      propsData: { email, emails, expandedDrawer: true },
      mocks: {
        $t: t,
        $emailConnectorMailBoxService: service,
        $vuetify: { breakpoint: {}, rtl: false },
      },
      stubs: { 'email-connector-mail-box-drawer-automations': automationsStub },
    });
    return { wrapper, service };
  }

  /**
   * A message as a full read returns it.
   *
   * @param {Number} id its row id
   * @param {Number} mailRemoteId its UID
   * @param {String} threadId its conversation
   * @param {String} receivedDate when it was received
   * @returns {Object} the message
   */
  function full(id, mailRemoteId, threadId, receivedDate) {
    return {
      id, mailRemoteId, threadId, folder: 'INBOX', mailHeaderId: `<${mailRemoteId}@host>`, subject: 'Une place réservée',
      receivedDate, read: true, to: [], cc: [], bcc: [], content: { body: 'body' }, sender: { address: 'alice@host' },
    };
  }

  it('reads the conversation of the row, two messages, and hands the panel both and the opened one as the conversation gives it', async () => {
    // The opened copy is an older body the browser kept (a 304 on an eTag that does not
    // follow the row): its row, id 90, was since replaced under the same UID, and its
    // thread id is not the conversation's; the view's row names the conversation's.
    const opened = full(90, 8, '<old@host>', '2026-09-02T10:00:00Z');
    const conversation = [full(107, 7, '<t@host>', '2026-09-01T10:00:00Z'), full(108, 8, '<t@host>', '2026-09-02T10:00:00Z')];
    const { wrapper, service } = mountReader(opened, [suggestion(8, 'INBOX', { threadId: '<t@host>' })], conversation);
    await flush();

    expect(service.getThreadByThreadId).toHaveBeenCalledWith('<t@host>', 'INBOX');
    const panel = wrapper.findComponent(automationsStub);
    expect(panel.props('messages').map(message => message.id)).toEqual([107, 108]);
    expect(panel.props('email').id).toBe(108);
    wrapper.destroy();
  });

  it('hands the panel the opened copy until the conversation lands', () => {
    const opened = full(90, 8, '<t@host>', '2026-09-02T10:00:00Z');
    const { wrapper } = mountReader(opened, [], []);

    expect(wrapper.findComponent(automationsStub).props('email')).toBe(opened);
    wrapper.destroy();
  });
});

describe('the Suggestions view lights the reader\'s mail and focuses the one the keys go to (EXO-90875)', () => {
  afterEach(() => jest.restoreAllMocks());

  it('lights the reader\'s row, none while it shows nothing, and focuses a row by its key', async () => {
    const mails = [suggestion(7, 'ARCHIVE'), suggestion(8)];
    await suggestionsRead(mails);
    const wrapper = shallowMount(EmailConnectorMailBoxSuggestionsList, {
      attachTo: document.body,
      propsData: { emails: mails, compact: true },
      mocks: { $t: t },
      stubs: {
        'email-connector-mail-box-drawer-list-item': {
          props: ['rowKey', 'openedKey'],
          render(createElement) {
            return createElement('div', {
              attrs: { tabindex: '0', 'data-thread-key': this.rowKey, 'data-opened': String(this.openedKey === this.rowKey) },
              on: { click: () => this.$emit('open') },
            });
          },
        },
      },
    });
    const lit = () => wrapper.findAll('[data-opened="true"]').wrappers.map(item => item.attributes('data-thread-key'));

    wrapper.find('[data-thread-key="ARCHIVE:7"]').trigger('click');
    await wrapper.vm.$nextTick();
    expect(lit()).toEqual(['ARCHIVE:7']);

    await wrapper.setProps({ readerKey: 'INBOX:8' });
    expect(lit()).toEqual(['INBOX:8']);

    // The reader shows the placeholder: no row is lit, the one clicked last included.
    await wrapper.setProps({ readerKey: null });
    expect(lit()).toEqual([]);

    await wrapper.vm.revealThread('ARCHIVE:7');
    expect(document.activeElement.getAttribute('data-thread-key')).toBe('ARCHIVE:7');
    wrapper.destroy();
    document.body.innerHTML = '';
  });
});
