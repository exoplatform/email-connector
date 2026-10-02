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

// EXO-90871 -- the search results and the Suggestions view render the folder list's own
// row, so a hit has the star, the selection and its bulk actions, the categories and the
// drag onto a folder of a listed mail. What a hit lacks of a listed row -- a body, a
// conversation, sometimes a sender's name -- the row reads without breaking, and what a
// list of several folders' mails adds -- the folder, a hit outside the local copy, the
// opening the list does itself -- are options of the row.

import Vue from 'vue';
import { createLocalVue, shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerListItem from '../EmailConnectorMailBoxDrawerListItem.vue';
import EmailConnectorMailBoxDrawerSearchResults from '../EmailConnectorMailBoxDrawerSearchResults.vue';
import EmailConnectorMailBoxSuggestionsList from '../EmailConnectorMailBoxSuggestionsList.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';
import { refreshWaitingSuggestions } from '../../../js/EmailConnectorMailFilters.js';
import * as userSettingService from '../../../../email-connector-user-setting/js/EmailConnectorUserSettingService.js';

Vue.config.ignoredElements.push(/^email-connector-/, 'pinneable-drawer');

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'ARCHIVE', type: 'BUILT_IN', syncEnabled: true },
  { key: 'CUSTOM:1', type: 'CUSTOM', displayName: 'Factures', path: 'Factures', syncEnabled: true },
];

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

const t = (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key);

/**
 * A search hit, as the search answers it: no body, no conversation, its own excerpt.
 *
 * @param {Number} mailRemoteId its UID
 * @param {String} folder the folder it is numbered in
 * @param {Object} extra further fields
 * @returns {Object} the hit
 */
function hit(mailRemoteId, folder = 'INBOX', extra = {}) {
  return {
    mailRemoteId,
    folder,
    subject: `${folder} ${mailRemoteId}`,
    sender: { name: 'Alice', address: 'alice@host' },
    receivedDate: '2026-09-01T10:00:00Z',
    read: true,
    starred: false,
    cached: true,
    categoryIds: [],
    ...extra,
  };
}

/**
 * A listed message of the folder's list, with its body.
 *
 * @param {Number} mailRemoteId its UID
 * @returns {Object} the row
 */
function listed(mailRemoteId) {
  const row = {
    ...hit(mailRemoteId),
    mailHeaderId: `<${mailRemoteId}@host>`,
    threadId: `<${mailRemoteId}@host>`,
    content: { excerpt: 'opening words', attachments: [] },
  };
  // A listed row says nothing of a cache it is always in.
  delete row.cached;
  return row;
}

// The row as a list renders it, with what the list hands it.
const rowStub = {
  props: {
    email: Object,
    emails: Array,
    rowKey: String,
    openedKey: String,
    selectMode: Boolean,
    selectedEmails: Array,
    expanded: Boolean,
    dragSource: Object,
    showFolder: Boolean,
    folders: Array,
  },
  render(createElement) {
    return createElement('div', {
      attrs: { 'data-thread-key': this.rowKey },
      on: { click: () => this.$emit('open') },
    });
  },
};

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
 * Mounts the folder list's row alone.
 *
 * @param {Object} props the row's props
 * @param {Object} listeners what the list listens to on the row
 * @returns {Wrapper} the row
 */
function mountRow(props, listeners = {}) {
  const localVue = createLocalVue();
  localVue.directive('touch', {});
  localVue.directive('touch-hold', {});
  return shallowMount(EmailConnectorMailBoxDrawerListItem, {
    localVue,
    propsData: props,
    listeners,
    mocks: {
      $t: t,
      $emailConnectorMailBoxService: { ...emailConnectorMailBoxService, formatDateString: () => 'today' },
      $vuetify: { breakpoint: { smAndDown: false } },
    },
  });
}

describe('the search results render the folder list\'s row for each hit (EXO-90871)', () => {
  let wrapper;

  afterEach(() => wrapper?.destroy());

  it('hands each row the hit, its key by folder and UID, the reader\'s key, the selection and the drag', () => {
    const results = [hit(5, 'ARCHIVE'), hit(5, 'INBOX')];
    const dragSource = { folder: 'INBOX', ids: [5] };
    wrapper = shallowMount(EmailConnectorMailBoxDrawerSearchResults, {
      propsData: { results, openedKey: 'ARCHIVE:5', selectMode: true, selectedEmails: ['ARCHIVE:5'], expanded: true, dragSource, folders: FOLDERS },
      mocks: { $t: t },
      stubs: { 'email-connector-mail-box-drawer-list-item': rowStub },
    });

    const rows = wrapper.findAllComponents(rowStub).wrappers;
    expect(rows.map(row => row.props('rowKey'))).toEqual(['ARCHIVE:5', 'INBOX:5']);
    expect(rows.map(row => row.props('email'))).toEqual(results);
    rows.forEach(row => {
      expect(row.props()).toMatchObject({ emails: results, openedKey: 'ARCHIVE:5', selectMode: true, selectedEmails: ['ARCHIVE:5'], expanded: true, dragSource, showFolder: true, folders: FOLDERS });
    });
    // The light row's own props are gone with it.
    expect(wrapper.vm.$options.props.draggableHits).toBeUndefined();
  });

  it('opens the hit the row asks for, the search\'s way (open-result), and not in full screen alone', () => {
    const results = [hit(5, 'ARCHIVE')];
    wrapper = shallowMount(EmailConnectorMailBoxDrawerSearchResults, {
      propsData: { results },
      mocks: { $t: t },
      stubs: { 'email-connector-mail-box-drawer-list-item': rowStub },
    });

    wrapper.find('[data-thread-key="ARCHIVE:5"]').trigger('click');

    expect(wrapper.emitted('open-result')).toEqual([[results[0]]]);
    expect(wrapper.findComponent(rowStub).props('expanded')).toBe(false);
  });
});

describe('the Suggestions view renders the folder list\'s row for each mail (EXO-90871)', () => {
  let wrapper;
  let mails;

  beforeEach(() => {
    mails = [hit(7, 'ARCHIVE', { mailHeaderId: '<7@host>', waitingCount: 2 }), hit(8, 'INBOX', { mailHeaderId: '<8@host>', waitingCount: 1 })];
    jest.spyOn(userSettingService, 'getWaitingSuggestionMails').mockResolvedValue(['<7@host>', '<7@host>', '<8@host>']);
    jest.spyOn(userSettingService, 'getWaitingSuggestionEmails').mockResolvedValue(mails);
  });

  afterEach(() => {
    wrapper?.destroy();
    jest.restoreAllMocks();
  });

  /**
   * Mounts the view over the mails the drawer hands it, and lets its own read of the
   * waiting suggestions land.
   *
   * @param {Object} props the view's props, the mails unless given
   * @returns {Promise<Wrapper>} the view, loaded
   */
  async function mountView(props = {}) {
    wrapper = shallowMount(EmailConnectorMailBoxSuggestionsList, {
      propsData: { emails: mails, ...props },
      mocks: { $t: t },
      stubs: { 'email-connector-mail-box-drawer-list-item': rowStub },
    });
    await flush();
    return wrapper;
  }

  it('renders the mails the drawer hands it, not the last read on its own: the drawer leaves out the ones acted on', async () => {
    await refreshWaitingSuggestions(true);
    await mountView({ emails: [mails[1]] });

    expect(wrapper.findAllComponents(rowStub).wrappers.map(row => row.props('rowKey'))).toEqual(['INBOX:8']);
    await wrapper.setProps({ emails: [] });
    expect(wrapper.findAllComponents(rowStub).length).toBe(0);
    expect(wrapper.find('.suggestions-email-empty').exists()).toBe(true);
  });

  it('hands each row the mail, its key by folder and UID, the folder to name, the selection and the drag', async () => {
    const dragSource = { folder: 'ARCHIVE', ids: [7] };
    await mountView({ compact: true, selectMode: true, selectedEmails: ['INBOX:8'], dragSource, folders: FOLDERS });

    const rows = wrapper.findAllComponents(rowStub).wrappers;
    expect(rows.map(row => row.props('rowKey'))).toEqual(['ARCHIVE:7', 'INBOX:8']);
    rows.forEach(row => {
      expect(row.props()).toMatchObject({ emails: mails, openedKey: null, selectMode: true, selectedEmails: ['INBOX:8'], expanded: true, dragSource, showFolder: true, folders: FOLDERS });
    });
  });

  it('in the narrow drawer its rows are the narrow rows, with nothing selected or dragged', async () => {
    await mountView();

    expect(wrapper.findComponent(rowStub).props()).toMatchObject({ expanded: false, selectMode: false, selectedEmails: [], dragSource: null });
  });

  it('opens the mail the row asks for as a mail picked outside the list, and lights its row', async () => {
    await mountView({ compact: true });
    const openings = [];
    wrapper.vm.$root.$on('open-suggested-email', opening => openings.push(opening));

    wrapper.find('[data-thread-key="ARCHIVE:7"]').trigger('click');
    await wrapper.vm.$nextTick();

    expect(openings).toEqual([{ mailRemoteId: 7, folder: 'ARCHIVE', cached: true }]);
    expect(wrapper.findAllComponents(rowStub).wrappers.map(row => row.props('openedKey'))).toEqual(['ARCHIVE:7', 'ARCHIVE:7']);
  });
});

describe('the folder list\'s row as a list of hits uses it (EXO-90871)', () => {
  let wrapper;

  afterEach(() => wrapper?.destroy());

  it('is walked and lit by the list\'s key, never by its UID alone, which another folder\'s hit shares', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5', openedKey: 'INBOX:5', expanded: true });
    const focusable = wrapper.find('[data-thread-key]');

    expect(focusable.attributes('data-thread-key')).toBe('ARCHIVE:5');
    expect(focusable.attributes('aria-current')).toBeUndefined();
    expect(wrapper.classes()).not.toContain('grey-lighten1-background-opacity-3');

    await wrapper.setProps({ openedKey: 'ARCHIVE:5' });

    expect(focusable.attributes('aria-current')).toBe('true');
    expect(wrapper.classes()).toContain('grey-lighten1-background-opacity-3');
  });

  it('keeps the conversation\'s key and the opened UID for a row of the folder\'s list', () => {
    const email = listed(5);
    const thread = { threadId: '<5@host>', emails: [email], mailRemoteIds: [5], count: 1, unreadCount: 0 };
    wrapper = mountRow({ email, thread, openedEmailId: 5, readerEmailId: 5, expanded: true });

    const focusable = wrapper.find('[data-thread-key]');
    expect(focusable.attributes('data-thread-key')).toBe('<5@host>');
    expect(focusable.attributes('aria-current')).toBe('true');
    expect(wrapper.classes()).toContain('grey-lighten1-background-opacity-3');
  });

  it('quotes a hit\'s own excerpt, nothing for a hit without one, and still says a listed body is empty', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE', { excerpt: 'around the match' }) });
    expect(wrapper.text()).toContain('around the match');
    expect(wrapper.text()).not.toContain('emptyEmail');

    await wrapper.setProps({ email: hit(5, 'ARCHIVE') });
    expect(wrapper.text()).not.toContain('emptyEmail');

    await wrapper.setProps({ email: { ...listed(5), content: { excerpt: '', attachments: [] } } });
    expect(wrapper.text()).toContain('emailConnector.mailBox.list.drawer.emptyEmail');
  });

  it('says a hit outside the local copy will be fetched, and nothing of a cached hit or a listed row', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE', { cached: false }) });
    expect(wrapper.find('.row-not-cached').attributes('title')).toBe('emailConnector.mailBox.search.notCached');

    await wrapper.setProps({ email: hit(5, 'ARCHIVE') });
    expect(wrapper.find('.row-not-cached').exists()).toBe(false);

    await wrapper.setProps({ email: listed(5) });
    expect(wrapper.find('.row-not-cached').exists()).toBe(false);
  });

  it('names the folder the row sits in when asked, as the folder column names it, the key until the folders land', async () => {
    // Drawn before the mailbox's folders answered: the key, then the name follows them.
    wrapper = mountRow({ email: hit(5, 'CUSTOM:1'), showFolder: true });
    expect(wrapper.find('.row-folder').text()).toBe('CUSTOM:1');
    await wrapper.setProps({ folders: FOLDERS });
    expect(wrapper.find('.row-folder').text()).toBe('Factures');

    await wrapper.setProps({ email: hit(5, 'ARCHIVE') });
    expect(wrapper.find('.row-folder').text()).toBe('emailConnector.mailBox.list.drawer.folder.archive');

    await wrapper.setProps({ email: hit(5, 'ALL_MAIL') });
    expect(wrapper.find('.row-folder').text()).toBe('ALL_MAIL');

    await wrapper.setProps({ showFolder: false });
    expect(wrapper.find('.row-folder').exists()).toBe(false);
  });

  it('names a sender by its address when it has no name, and renders a hit with none', () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE', { sender: { address: 'alice@host' } }) });
    expect(wrapper.find('v-list-item-title').text()).toBe('alice@host');
    expect(wrapper.find('[data-thread-key]').attributes('aria-label')).toBe('Open email from alice@host about ARCHIVE 5');
    wrapper.destroy();

    wrapper = mountRow({ email: hit(5, 'ARCHIVE', { sender: null }) });
    expect(wrapper.find('v-list-item-title').text()).toBe('');
  });

  it('hands its opening to the list that listens for it, and ticks itself in select mode instead', async () => {
    const opened = [];
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5', expanded: true }, { open: () => opened.push(true) });
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    wrapper.find('[data-thread-key]').trigger('click');
    expect(opened).toEqual([true]);
    expect(emit).not.toHaveBeenCalled();

    await wrapper.setProps({ selectMode: true });
    wrapper.find('[data-thread-key]').trigger('click');
    expect(opened).toEqual([true]);
    expect(emit).toHaveBeenCalledWith('select-email', expect.objectContaining({ emailId: 5, folder: 'ARCHIVE', selected: true }));
  });

  it('opens a row of the folder\'s list itself when no list listens', () => {
    wrapper = mountRow({ email: listed(5), expanded: true });
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    wrapper.find('[data-thread-key]').trigger('click');

    expect(emit).toHaveBeenCalledWith('open-email-detail-content', 5, 'INBOX');
  });

  it('stars a hit in its own folder, and drags it alone in that folder in full screen only', () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5', expanded: true });
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    wrapper.vm.toggleThreadFavorite();
    expect(emit).toHaveBeenCalledWith('update-email-favorite-status', true, [5], 'ARCHIVE');
    expect(wrapper.attributes('draggable')).toBe('true');
    wrapper.destroy();

    wrapper = mountRow({ email: hit(5, 'TRASH'), rowKey: 'TRASH:5', expanded: true });
    expect(wrapper.attributes('draggable')).toBeUndefined();
    wrapper.destroy();

    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5' });
    expect(wrapper.attributes('draggable')).toBeUndefined();
  });
});

describe('the mailbox drawer gives its bars the rows on screen and ends a search\'s selection with it (EXO-90871)', () => {
  let wrapper;
  let service;
  let alerts;
  const onAlert = event => alerts.push(event.detail);

  /**
   * Mounts the mailbox drawer listing INBOX:5 and INBOX:6, with ARCHIVE:5 among the
   * server's hits and ARCHIVE:6 among eXo's copy's.
   *
   * @returns {Promise<void>} resolved once mounted
   */
  async function mountDrawer() {
    service = serviceStub({
      folderLabel: emailConnectorMailBoxService.folderLabel,
      isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
      getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
      updateEmailsFavoriteStatus: jest.fn(() => Promise.resolve({ failedUpdates: 0 })),
      updateEmailsReadStatus: jest.fn(() => Promise.resolve({ failedUpdates: 0 })),
      deleteEmails: jest.fn(() => Promise.resolve({ failedDeletions: 0 })),
      moveEmails: jest.fn(() => Promise.resolve({ failedMoves: 0 })),
      undoMoveEmails: jest.fn(() => Promise.resolve({ failedUndos: 0 })),
    });
    alerts = [];
    document.addEventListener('alert-message', onAlert);
    wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
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
      emailBox: { emails: [listed(5), listed(6)], folders: FOLDERS },
    });
  }

  afterEach(() => {
    document.removeEventListener('alert-message', onAlert);
    wrapper?.vm.stopAutoRefresh();
    wrapper?.destroy();
    jest.restoreAllMocks();
  });

  it('lists the folder\'s rows, the search\'s hits while searching, the Suggestions view\'s mails on it', async () => {
    await mountDrawer();
    expect(wrapper.vm.listedEmails.map(row => row.mailRemoteId)).toEqual([5, 6]);

    await wrapper.setData({ searchTerm: 'nothing listed matches', searchServerResults: [hit(5, 'ARCHIVE')] });
    expect(wrapper.vm.listedEmails.map(row => `${row.folder}:${row.mailRemoteId}`)).toEqual(['ARCHIVE:5']);

    const mails = await suggestionsRead([hit(7, 'ARCHIVE', { mailHeaderId: '<7@host>', waitingCount: 1 })]);
    wrapper.vm.clearSearch();
    await wrapper.setData({ currentFolder: emailConnectorMailBoxService.SUGGESTIONS_VIEW });
    expect(wrapper.vm.listedEmails).toEqual(mails);
  });

  /**
   * Has the waiting suggestions read answer the given mails, and reads them, as the
   * Suggestions view does on opening.
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

  it('stamps a star and a read status set on a Suggestions mail on its row, in its folder, never on a twin', async () => {
    await mountDrawer();
    const mails = await suggestionsRead([
      hit(5, 'ARCHIVE', { mailHeaderId: '<a5@host>', waitingCount: 1, read: false }),
      hit(5, 'INBOX', { mailHeaderId: '<5@host>', waitingCount: 1, read: false }),
    ]);
    await wrapper.setData({ currentFolder: emailConnectorMailBoxService.SUGGESTIONS_VIEW });

    wrapper.vm.$root.$emit('update-email-favorite-status', true, [5], 'ARCHIVE');
    wrapper.vm.$root.$emit('update-email-read-status', true, [5], 'ARCHIVE');
    await flush();

    expect(mails.map(mail => [mail.starred, mail.read])).toEqual([[true, true], [false, false]]);
    expect(service.updateEmailsFavoriteStatus).toHaveBeenCalledWith([5], true, 'ARCHIVE');
    expect(service.updateEmailsReadStatus).toHaveBeenCalledWith([5], true, 'ARCHIVE');
    // The read state the toggle started from was known off the row: the folder's unread
    // count moves at once, as for a listed row.
    expect(wrapper.vm.unreadAdjustments).toEqual({ ARCHIVE: -1 });
  });

  it('offers the Undo of a Suggestions mail moved, named by its own Message-ID, as the inbox does', async () => {
    await mountDrawer();
    await suggestionsRead([hit(7, 'ARCHIVE', { mailHeaderId: '<7@host>', waitingCount: 1 })]);
    await wrapper.setData({ currentFolder: emailConnectorMailBoxService.SUGGESTIONS_VIEW });

    await wrapper.vm.moveEmails([7], 'CUSTOM:1', 'ARCHIVE');
    await flush();

    expect(service.moveEmails).toHaveBeenCalledWith([7], 'ARCHIVE', 'CUSTOM:1');
    expect(alerts.map(alert => typeof alert.alertLinkCallback)).toEqual(['function']);
    await alerts[0].alertLinkCallback();
    expect(service.undoMoveEmails).toHaveBeenCalledWith(['<7@host>'], 'CUSTOM:1', 'ARCHIVE');
  });

  it('takes a Suggestions mail acted on out of the view at once, and reads the view again once the server answered', async () => {
    await mountDrawer();
    await suggestionsRead([
      hit(7, 'ARCHIVE', { mailHeaderId: '<7@host>', waitingCount: 1 }),
      hit(8, 'INBOX', { mailHeaderId: '<8@host>', waitingCount: 1 }),
    ]);
    await wrapper.setData({ currentFolder: emailConnectorMailBoxService.SUGGESTIONS_VIEW });
    const readsBefore = userSettingService.getWaitingSuggestionEmails.mock.calls.length;

    wrapper.vm.$root.$emit('delete-email', [7], 'ARCHIVE');

    const keys = rows => rows.map(row => `${row.folder}:${row.mailRemoteId}`);
    expect(keys(wrapper.vm.suggestionMails)).toEqual(['INBOX:8']);
    expect(keys(wrapper.vm.listedEmails)).toEqual(['INBOX:8']);
    expect(userSettingService.getWaitingSuggestionEmails.mock.calls.length).toBe(readsBefore);
    await flush();
    expect(service.deleteEmails).toHaveBeenCalledWith([7], 'ARCHIVE', true);
    expect(userSettingService.getWaitingSuggestionEmails.mock.calls.length).toBe(readsBefore + 1);
  });

  it('ends a selection made among the hits when the search ends, and leaves one made in the list when no search ran', async () => {
    await mountDrawer();
    await wrapper.setData({ searchTerm: 'nothing listed matches', searchServerResults: [hit(5, 'ARCHIVE')] });
    wrapper.vm.$root.$emit('select-email', { emailId: 5, folder: 'ARCHIVE', selected: true });
    expect(wrapper.vm.selectMode).toBe(true);

    wrapper.vm.clearSearch();
    expect(wrapper.vm.selectMode).toBe(false);
    expect(wrapper.vm.selectedEmails).toEqual([]);

    wrapper.vm.$root.$emit('select-email', { emailId: 6, folder: 'INBOX', selected: true });
    wrapper.vm.clearSearch();
    expect(wrapper.vm.selectMode).toBe(true);
    expect(wrapper.vm.selectedEmails).toEqual(['INBOX:6']);
  });

  it('stamps a star set on a hit on the server\'s and on eXo\'s copy\'s hits of that folder, never on the listed twin', async () => {
    await mountDrawer();
    await wrapper.setData({ searchTerm: 'nothing listed matches', searchServerResults: [hit(5, 'ARCHIVE')], searchLocalResults: [hit(6, 'ARCHIVE')] });

    wrapper.vm.$root.$emit('update-email-favorite-status', true, [5, 6], 'ARCHIVE');
    await flush();

    expect(wrapper.vm.searchServerResults[0].starred).toBe(true);
    expect(wrapper.vm.searchLocalResults[0].starred).toBe(true);
    expect(wrapper.vm.emails.map(email => email.starred)).toEqual([false, false]);
    expect(service.updateEmailsFavoriteStatus).toHaveBeenCalledWith([5, 6], true, 'ARCHIVE');
  });
});
