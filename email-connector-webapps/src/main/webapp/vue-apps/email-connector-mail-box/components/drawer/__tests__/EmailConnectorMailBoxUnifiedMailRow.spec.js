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
import EmailConnectorMailBoxDrawerListItemDetailSenderAvatar from '../EmailConnectorMailBoxDrawerListItemDetailSenderAvatar.vue';
import EmailConnectorMailBoxDrawerMultiSelectEmail from '../EmailConnectorMailBoxDrawerMultiSelectEmail.vue';
import EmailConnectorMailBoxDrawerActions from '../EmailConnectorMailBoxDrawerActions.vue';
import { MAX_AVATAR_BATCH, rememberSenderAvatar, requestSenderAvatar, resetSenderAvatars, senderAvatarUrl, watchSenderAvatar } from '../../../js/EmailConnectorSenderAvatars.js';
import { avatarColor } from '../../../js/EmailRecipientDisplay.js';
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
 * A search hit, as the search answers it for a mail outside the local copy: no content,
 * no conversation.
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
 * @param {Boolean} phone whether the screen is a phone's (no hover)
 * @returns {Wrapper} the row
 */
function mountRow(props, listeners = {}, phone = false) {
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
      $vuetify: { breakpoint: { smAndDown: phone } },
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

  it('shows a hit\'s listed excerpt, attachments and conversation size (EXO-90882), nothing for a hit without them, and still says a listed body is empty', async () => {
    const attachments = [{ id: 1, name: 'figures.xlsx' }];
    wrapper = mountRow({ email: hit(5, 'ARCHIVE', { content: { excerpt: 'opening words', attachments }, threadCount: 3 }) });
    expect(wrapper.text()).toContain('opening words');
    expect(wrapper.text()).toContain('3');
    expect(wrapper.find('email-connector-mail-box-drawer-list-item-attachments').exists()).toBe(true);
    expect(wrapper.vm.emailAttachments).toEqual(attachments);
    expect(wrapper.vm.threadCount).toBe(3);

    await wrapper.setProps({ email: hit(5, 'ARCHIVE') });
    expect(wrapper.text()).not.toContain('emptyEmail');
    expect(wrapper.find('email-connector-mail-box-drawer-list-item-attachments').exists()).toBe(false);
    expect(wrapper.vm.threadCount).toBe(1);

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

  it('shows the suggestions waiting on a mail as the Suggestions view\'s icon and a flat number before the subject, never as the pill of a count (EXO-90890)', async () => {
    jest.spyOn(userSettingService, 'getWaitingSuggestionMails').mockResolvedValue(['<7@host>', '<7@host>', '<8@host>']);
    jest.spyOn(userSettingService, 'getWaitingSuggestionEmails').mockResolvedValue([]);
    await refreshWaitingSuggestions(true);
    try {
      wrapper = mountRow({ email: hit(7, 'ARCHIVE', { mailHeaderId: '<7@host>', threadCount: 3 }) });
      const marker = wrapper.find('.row-waiting-suggestions');
      expect(marker.attributes('title')).toBe('emailConnector.mailBox.list.drawer.waitingSuggestionsCount|2');
      expect(marker.attributes('aria-label')).toBe(marker.attributes('title'));
      expect(marker.classes()).toEqual(expect.arrayContaining(['text-light-color', 'text-no-wrap', 'flex-shrink-0']));
      expect(marker.classes()).not.toContain('primary');
      expect(marker.classes()).not.toContain('rounded-pill');
      expect(marker.find('v-icon-stub, v-icon').text()).toBe(emailConnectorMailBoxService.folderIcon({ key: 'SUGGESTIONS', type: 'BUILT_IN' }));
      expect(marker.text()).toBe('fa-magic2');
      // The conversation's size keeps its place after the sender, the marker its own
      // before the subject.
      expect(wrapper.find('v-list-item-title-stub, v-list-item-title').text()).toContain('3');
      expect(marker.element.nextElementSibling.textContent).toBe('ARCHIVE 7');

      await wrapper.setProps({ email: hit(8, 'INBOX', { mailHeaderId: '<8@host>' }) });
      expect(wrapper.find('.row-waiting-suggestions').attributes('title')).toBe('emailConnector.mailBox.list.drawer.waitingSuggestionsOne');

      await wrapper.setProps({ email: hit(9, 'INBOX', { mailHeaderId: '<9@host>' }) });
      expect(wrapper.find('.row-waiting-suggestions').exists()).toBe(false);
    } finally {
      jest.restoreAllMocks();
      jest.spyOn(userSettingService, 'getWaitingSuggestionMails').mockResolvedValue([]);
      jest.spyOn(userSettingService, 'getWaitingSuggestionEmails').mockResolvedValue([]);
      await refreshWaitingSuggestions(true);
      jest.restoreAllMocks();
    }
  });

  it('names the folder the row sits in when asked, after the sender on the first line (EXO-90882), as the folder column names it, the key until the folders land', async () => {
    // Drawn before the mailbox's folders answered: the key, then the name follows them.
    wrapper = mountRow({ email: hit(5, 'CUSTOM:1'), showFolder: true });
    expect(wrapper.find('.row-folder').text()).toBe('CUSTOM:1');
    // On the sender's line, after the name, which gives way first; never on the subject's.
    const title = wrapper.find('v-list-item-title');
    expect(title.find('.row-folder').exists()).toBe(true);
    expect(title.element.firstElementChild.classList.contains('text-truncate')).toBe(true);
    expect(title.element.firstElementChild.textContent).toBe('Alice');
    expect(title.find('.row-folder').classes()).toContain('flex-shrink-0');
    expect(wrapper.findAll('v-list-item-subtitle').wrappers.some(line => line.find('.row-folder').exists())).toBe(false);
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
      groupEmailsByThread: emailConnectorMailBoxService.groupEmailsByThread,
      threadRowsInFolder: emailConnectorMailBoxService.threadRowsInFolder,
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

  it('lists the folder\'s rows, the search\'s hits while searching, the Suggestions view\'s mails on it, the Important chip offered on the folder only (EXO-90882)', async () => {
    await mountDrawer();
    const important = { id: 3, nameId: 'emailImportantCategory', name: 'Important' };
    await wrapper.setData({ emailCategories: [important] });
    expect(wrapper.vm.listedEmails.map(row => row.mailRemoteId)).toEqual([5, 6]);
    expect(wrapper.vm.searchBarProps.importantCategory).toEqual(important);

    await wrapper.setData({ searchTerm: 'nothing listed matches', searchServerResults: [hit(5, 'ARCHIVE')] });
    expect(wrapper.vm.listedEmails.map(row => `${row.folder}:${row.mailRemoteId}`)).toEqual(['ARCHIVE:5']);
    expect(wrapper.vm.searchBarProps.importantCategory).toBeNull();

    const mails = await suggestionsRead([hit(7, 'ARCHIVE', { mailHeaderId: '<7@host>', waitingCount: 1 })]);
    wrapper.vm.clearSearch();
    await wrapper.setData({ currentFolder: emailConnectorMailBoxService.SUGGESTIONS_VIEW });
    expect(wrapper.vm.listedEmails).toEqual(mails);
    // The Suggestions view's row offers what the search results' does: no Important chip.
    expect(wrapper.vm.searchBarProps.importantCategory).toBeNull();
    expect(wrapper.vm.searchBarProps).toMatchObject({ favoriteOnly: false, unreadOnly: false });
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

  it('ends the select mode once the last row is unticked, or the select-all row cleared, and the rows show their avatars again (EXO-90891)', async () => {
    await mountDrawer();
    wrapper.vm.$root.$emit('select-email', { emailId: 5, folder: 'INBOX', selected: true });
    wrapper.vm.$root.$emit('select-email', { emailId: 6, folder: 'INBOX', selected: true });
    wrapper.vm.$root.$emit('select-email', { emailId: 5, folder: 'INBOX', selected: false });
    expect(wrapper.vm.selectMode).toBe(true);
    expect(wrapper.vm.selectedEmails).toEqual(['INBOX:6']);

    wrapper.vm.$root.$emit('select-email', { emailId: 6, folder: 'INBOX', selected: false });
    expect(wrapper.vm.selectMode).toBe(false);
    expect(wrapper.vm.selectedEmails).toEqual([]);

    // The select-all row ticked keeps it on; cleared, its list hands the drawer an empty
    // selection (update:selected-emails -> setSelectedEmails), which ends it.
    wrapper.vm.$root.$emit('select-email', { emailId: 5, folder: 'INBOX', selected: true });
    wrapper.vm.setSelectedEmails(['INBOX:5', 'INBOX:6']);
    expect(wrapper.vm.selectMode).toBe(true);
    wrapper.vm.setSelectedEmails([]);
    expect(wrapper.vm.selectMode).toBe(false);
  });

  it('enters the select mode from the drawer\'s menu with one row ticked: the reader\'s, else the list\'s first (EXO-90891)', async () => {
    await mountDrawer();
    // In the narrow drawer no reader stands beside the list: its first row.
    await wrapper.setData({ email: listed(6) });

    wrapper.vm.$root.$emit('enter-select-mode');
    expect(wrapper.vm.selectMode).toBe(true);
    expect(wrapper.vm.selectedEmails).toEqual(['INBOX:5']);
    wrapper.vm.cancelSelectMode();

    // In full screen, the row of the mail the reader shows.
    await wrapper.setData({ expanded: true, email: listed(6), selectEmailPlaceHolder: false });
    wrapper.vm.$root.$emit('enter-select-mode');
    expect(wrapper.vm.selectedEmails).toEqual(['INBOX:6']);
    wrapper.vm.cancelSelectMode();

    // The placeholder showing, the reader shows none: the first row.
    await wrapper.setData({ selectEmailPlaceHolder: true });
    wrapper.vm.$root.$emit('enter-select-mode');
    expect(wrapper.vm.selectedEmails).toEqual(['INBOX:5']);
    wrapper.vm.cancelSelectMode();

    // A row the server has not listed yet is not ticked.
    await wrapper.setData({ selectEmailPlaceHolder: false, email: null, emailBox: { emails: [{ ...listed(5), refreshPending: true }, listed(6)], folders: FOLDERS } });
    wrapper.vm.$root.$emit('enter-select-mode');
    expect(wrapper.vm.selectedEmails).toEqual(['INBOX:6']);
    wrapper.vm.cancelSelectMode();

    // The search's first hit while searching, the reader's hit when it shows one.
    await wrapper.setData({ searchTerm: 'nothing listed matches', searchServerResults: [hit(5, 'ARCHIVE'), hit(7, 'ARCHIVE')] });
    wrapper.vm.$root.$emit('enter-select-mode');
    expect(wrapper.vm.selectedEmails).toEqual(['ARCHIVE:5']);
    wrapper.vm.cancelSelectMode();
    await wrapper.setData({ email: hit(7, 'ARCHIVE') });
    wrapper.vm.$root.$emit('enter-select-mode');
    expect(wrapper.vm.selectedEmails).toEqual(['ARCHIVE:7']);
    wrapper.vm.cancelSelectMode();

    // An empty list: nothing.
    await wrapper.setData({ searchServerResults: [] });
    wrapper.vm.$root.$emit('enter-select-mode');
    expect(wrapper.vm.selectMode).toBe(false);
    expect(wrapper.vm.selectedEmails).toEqual([]);
  });

  it('enters the select mode on the Suggestions view with its first mail ticked (EXO-90891)', async () => {
    await mountDrawer();
    await suggestionsRead([hit(7, 'ARCHIVE', { mailHeaderId: '<7@host>', waitingCount: 1 }), hit(8, 'INBOX', { mailHeaderId: '<8@host>', waitingCount: 1 })]);
    await wrapper.setData({ currentFolder: emailConnectorMailBoxService.SUGGESTIONS_VIEW });

    wrapper.vm.$root.$emit('enter-select-mode');
    expect(wrapper.vm.selectedEmails).toEqual(['ARCHIVE:7']);
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

describe('the row\'s sender avatar is its checkbox (EXO-90891)', () => {
  let wrapper;

  afterEach(() => wrapper?.destroy());

  const avatarOf = row => row.find('.row-avatar email-connector-mail-box-drawer-list-item-detail-sender-avatar');
  const checkboxOf = row => row.find('.row-avatar v-checkbox');

  it('shows the sender\'s avatar on the left, 32 px, top-aligned, and no checkbox column', () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5', expanded: true });

    const box = wrapper.find('.row-avatar');
    expect(box.attributes('style')).toContain('width: 32px');
    expect(box.attributes('style')).toContain('height: 32px');
    expect(box.classes()).toEqual(expect.arrayContaining(['align-self-start', 'flex-shrink-0']));
    // First in the row, before the text block.
    expect(box.element.parentElement.firstElementChild).toBe(box.element);
    expect(avatarOf(wrapper).attributes('size')).toBe('32');
    expect(wrapper.vm.avatarPerson).toBeNull();
    expect(wrapper.findAll('v-checkbox').length).toBe(0);
  });

  it('in full screen, turns into the checkbox while the row is hovered or focused, labelled, and back', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5', expanded: true });

    await wrapper.trigger('mouseenter');
    expect(avatarOf(wrapper).exists()).toBe(false);
    expect(checkboxOf(wrapper).attributes('aria-label')).toBe('emailConnector.mailBox.list.drawer.selectRow|Alice|ARCHIVE 5');
    await wrapper.trigger('mouseleave');
    expect(checkboxOf(wrapper).exists()).toBe(false);
    expect(avatarOf(wrapper).exists()).toBe(true);

    await wrapper.trigger('focusin');
    expect(checkboxOf(wrapper).exists()).toBe(true);
    await wrapper.trigger('focusout');
    expect(checkboxOf(wrapper).exists()).toBe(false);
  });

  it('in the narrow drawer, turns into the checkbox only under the pointer on the avatar itself, or with the keyboard focus', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5' });

    // Hovering the row to read it keeps its avatar.
    await wrapper.trigger('mouseenter');
    expect(avatarOf(wrapper).exists()).toBe(true);
    expect(checkboxOf(wrapper).exists()).toBe(false);

    await wrapper.find('.row-avatar').trigger('mouseenter');
    expect(checkboxOf(wrapper).attributes('aria-label')).toBe('emailConnector.mailBox.list.drawer.selectRow|Alice|ARCHIVE 5');
    await wrapper.find('.row-avatar').trigger('mouseleave');
    expect(checkboxOf(wrapper).exists()).toBe(false);
    expect(avatarOf(wrapper).exists()).toBe(true);
    await wrapper.trigger('mouseleave');

    // The keyboard's focus shows it, as in full screen.
    await wrapper.trigger('focusin');
    expect(checkboxOf(wrapper).exists()).toBe(true);
    await wrapper.trigger('focusout', { relatedTarget: document.body });
    expect(checkboxOf(wrapper).exists()).toBe(false);

    // And select mode, on every row.
    await wrapper.setProps({ selectMode: true });
    expect(checkboxOf(wrapper).exists()).toBe(true);
  });

  it('on a phone, an avatar the pointer is reported over stays an avatar', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5' }, {}, true);

    await wrapper.find('.row-avatar').trigger('mouseenter');
    expect(avatarOf(wrapper).exists()).toBe(true);
  });

  it('keeps the checkbox the keyboard focus is on when the pointer leaves, and while the focus moves inside the row', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5', expanded: true }, {}, false);
    document.body.appendChild(wrapper.element);

    await wrapper.trigger('focusin');
    await wrapper.trigger('mouseenter');
    await wrapper.trigger('mouseleave');
    expect(checkboxOf(wrapper).exists()).toBe(true);

    await wrapper.trigger('focusout', { relatedTarget: wrapper.find('[data-thread-key]').element });
    expect(checkboxOf(wrapper).exists()).toBe(true);

    await wrapper.trigger('focusout', { relatedTarget: document.body });
    expect(checkboxOf(wrapper).exists()).toBe(false);
    wrapper.element.remove();
  });

  it('gives a clicked row its avatar back once the pointer leaves: the focus a click brings is not the keyboard\'s', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5', expanded: true });

    await wrapper.trigger('mouseenter');
    await wrapper.trigger('mousedown');
    await wrapper.trigger('focusin');
    expect(checkboxOf(wrapper).exists()).toBe(true);
    await wrapper.trigger('mouseleave');
    expect(checkboxOf(wrapper).exists()).toBe(false);
    expect(avatarOf(wrapper).exists()).toBe(true);

    // A press that focused nothing is forgotten when the pointer leaves: the keyboard
    // coming in afterwards still keeps the checkbox.
    await wrapper.trigger('focusout', { relatedTarget: document.body });
    await wrapper.trigger('mouseenter');
    await wrapper.find('.row-avatar').trigger('mousedown');
    await wrapper.trigger('mouseleave');
    await wrapper.trigger('focusin');
    await wrapper.trigger('mouseenter');
    await wrapper.trigger('mouseleave');
    expect(checkboxOf(wrapper).exists()).toBe(true);

    // A click that moved no focus is over once released: the keyboard's focus that
    // follows, pointer still on the row, keeps the checkbox when the pointer leaves.
    await wrapper.trigger('focusout', { relatedTarget: document.body });
    await wrapper.trigger('mouseenter');
    await wrapper.trigger('mousedown');
    await wrapper.trigger('mouseup');
    await wrapper.trigger('focusin');
    await wrapper.trigger('mouseleave');
    expect(checkboxOf(wrapper).exists()).toBe(true);
  });

  it('forgets a press that started a drag, which no release follows', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5', expanded: true });
    wrapper.vm.$root.$emit = jest.fn();

    await wrapper.trigger('mouseenter');
    await wrapper.trigger('mousedown');
    wrapper.vm.onDragEnd();
    await wrapper.trigger('mouseenter');
    await wrapper.trigger('focusin');
    await wrapper.trigger('mouseleave');
    expect(checkboxOf(wrapper).exists()).toBe(true);
  });

  it('is a checkbox on every row in select mode, ticked as the row is, and ticking it selects the row', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5', selectMode: true, selectedEmails: ['ARCHIVE:5'] });
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    expect(avatarOf(wrapper).exists()).toBe(false);
    expect(checkboxOf(wrapper).attributes('input-value')).toBe('true');
    await wrapper.setProps({ selectedEmails: [] });
    expect(checkboxOf(wrapper).attributes('input-value')).toBeUndefined();

    wrapper.vm.onSelectChange(true);
    expect(emit).toHaveBeenCalledWith('select-email', expect.objectContaining({ emailId: 5, folder: 'ARCHIVE', selected: true }));
    // The row keeps its place: no padding of its own for the select mode.
    expect(wrapper.classes()).toContain('ps-4');
  });

  it('keeps a row the server has not listed yet out of the selection', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE', { refreshPending: true }), selectMode: true });
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    expect(checkboxOf(wrapper).attributes('disabled')).toBe('true');
    await wrapper.find('.row-avatar').trigger('click');
    expect(emit).not.toHaveBeenCalled();
  });

  it('ticks the row on Enter or Space pressed on the avatar box, and leaves a key inside the checkbox to it', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5' });
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    await wrapper.find('.row-avatar').trigger('keydown', { key: 'Enter' });
    expect(emit).toHaveBeenCalledWith('select-email', expect.objectContaining({ emailId: 5, folder: 'ARCHIVE', selected: true }));

    emit.mockClear();
    wrapper.vm.onAvatarKeydown({ key: ' ', target: {}, currentTarget: {}, preventDefault: jest.fn() });
    expect(emit).not.toHaveBeenCalled();
  });

  it('on a phone, with no hover, a tap on the avatar ticks the row and enters select mode', async () => {
    wrapper = mountRow({ email: hit(5, 'ARCHIVE'), rowKey: 'ARCHIVE:5' }, {}, true);
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    await wrapper.trigger('mouseenter');
    expect(avatarOf(wrapper).exists()).toBe(true);

    await wrapper.find('.row-avatar').trigger('click');
    expect(emit).toHaveBeenCalledWith('select-email', expect.objectContaining({ emailId: 5, folder: 'ARCHIVE', selected: true }));
  });

  it('draws a draft after the first person of its conversation, as its first line names it; a draft answering nothing after its sender', () => {
    const draft = { ...listed(5), folder: 'DRAFTS', draftLocalId: 'local-1', threadParticipants: ['Véronika', 'Bob'] };
    wrapper = mountRow({ email: draft });
    expect(wrapper.vm.avatarPerson).toEqual({ name: 'Véronika' });
    wrapper.destroy();

    wrapper = mountRow({ email: { ...draft, threadParticipants: [] } });
    expect(wrapper.vm.avatarPerson).toBeNull();
  });
});

describe('the sender avatar draws the reader\'s initials and the page\'s cached picture (EXO-90891)', () => {
  let wrapper;
  let requests;

  beforeEach(() => {
    jest.useFakeTimers();
    // The platform's Vue is a global, which the avatar cache's answers are observed by.
    global.Vue = Vue;
    resetSenderAvatars();
    requests = [];
    global.fetch = jest.fn((url, options) => {
      const addresses = JSON.parse(options.body);
      requests.push(addresses);
      const answer = {};
      addresses.filter(address => address.endsWith('@example.org')).forEach(address => {
        answer[address] = `/portal/rest/v1/social/users/${address}/avatar`;
      });
      return Promise.resolve({ ok: true, json: () => Promise.resolve(answer) });
    });
  });

  afterEach(() => {
    wrapper?.destroy();
    wrapper = null;
    resetSenderAvatars();
    delete global.fetch;
    delete global.IntersectionObserver;
    delete global.Vue;
    jest.useRealTimers();
  });

  /**
   * Lets the gathered addresses leave, and their answers land.
   *
   * @returns {Promise<void>} resolved once they have
   */
  async function answered() {
    jest.advanceTimersByTime(100);
    // The request's chain settles over a handful of microtasks.
    await Array.from({ length: 12 }).reduce(settled => settled.then(() => Promise.resolve()), Promise.resolve());
  }

  /**
   * Mounts the avatar.
   *
   * @param {Object} props its props
   * @returns {Wrapper} the avatar
   */
  function mountAvatar(props) {
    return shallowMount(EmailConnectorMailBoxDrawerListItemDetailSenderAvatar, { propsData: props });
  }

  it('asks once, in one request, for the addresses asked within one moment, whatever their case', async () => {
    requestSenderAvatar('Bob@Example.org');
    requestSenderAvatar(' bob@example.org ');
    requestSenderAvatar('ann@client.org');
    requestSenderAvatar('not an address');
    expect(requests).toEqual([]);

    await answered();
    expect(requests).toEqual([['bob@example.org', 'ann@client.org']]);
    expect(senderAvatarUrl('BOB@example.org')).toBe('/portal/rest/v1/social/users/bob@example.org/avatar');
    expect(senderAvatarUrl('ann@client.org')).toBeNull();

    // Known, a picture or none: never asked again.
    requestSenderAvatar('bob@example.org');
    requestSenderAvatar('ann@client.org');
    await answered();
    expect(requests.length).toBe(1);
  });

  it('splits what it gathered at the server\'s cap', async () => {
    for (let i = 0; i < MAX_AVATAR_BATCH + 1; i++) {
      requestSenderAvatar(`user${i}@client.org`);
    }
    await answered();
    expect(requests.map(batch => batch.length)).toEqual([MAX_AVATAR_BATCH, 1]);
  });

  it('keeps nothing of a failed request: initials meanwhile, asked again the next time', async () => {
    const answer = global.fetch;
    global.fetch = jest.fn(() => Promise.reject(new Error('down')));
    requestSenderAvatar('bob@example.org');
    await answered();
    expect(senderAvatarUrl('bob@example.org')).toBeNull();
    global.fetch = jest.fn(() => Promise.resolve({ ok: false }));
    requestSenderAvatar('bob@example.org');
    await answered();

    global.fetch = answer;
    requestSenderAvatar('bob@example.org');
    await answered();
    expect(requests).toEqual([['bob@example.org']]);
    expect(senderAvatarUrl('bob@example.org')).toBe('/portal/rest/v1/social/users/bob@example.org/avatar');
  });

  it('takes what the reader learnt of a sender: a photo, or the generated initials meaning there is none', async () => {
    rememberSenderAvatar('bob@example.org', '/portal/rest/v1/social/users/12/avatar');
    rememberSenderAvatar('ann@client.org', 'data:image/png;base64,AAAA');
    requestSenderAvatar('bob@example.org');
    requestSenderAvatar('ann@client.org');
    await answered();

    expect(requests).toEqual([]);
    expect(senderAvatarUrl('bob@example.org')).toBe('/portal/rest/v1/social/users/12/avatar');
    expect(senderAvatarUrl('ann@client.org')).toBeNull();
  });

  it('asks for an avatar off screen only once it comes into view', async () => {
    const observed = [];
    let onIntersect = null;
    global.IntersectionObserver = class {
      constructor(callback) {
        onIntersect = callback;
      }
      observe(element) {
        observed.push(element);
      }
      unobserve(element) {
        observed.splice(observed.indexOf(element), 1);
      }
      disconnect() {
        observed.length = 0;
      }
    };
    const element = document.createElement('div');
    watchSenderAvatar(element, 'bob@example.org');
    await answered();
    expect(requests).toEqual([]);
    expect(observed).toEqual([element]);
    // Leaving the screen, or reported off it, asks nothing either.
    onIntersect([{ target: element, isIntersecting: false }]);
    await answered();
    expect(requests).toEqual([]);

    onIntersect([{ target: element, isIntersecting: true }]);
    await answered();
    expect(requests).toEqual([['bob@example.org']]);
    expect(observed).toEqual([]);
  });

  it('draws the reader\'s coloured initials until the page knows a photo, then the photo', async () => {
    wrapper = mountAvatar({ email: hit(5, 'INBOX', { sender: { name: 'Gina Carter', address: 'gina@example.org' } }), size: 32 });

    expect(wrapper.text()).toBe('GC');
    expect(wrapper.find('span').classes()).toContain('caption');
    expect(wrapper.attributes('color')).toBe(avatarColor('Gina Carter'));
    expect(wrapper.find('img').exists()).toBe(false);

    await answered();
    expect(wrapper.find('img').attributes('src')).toBe('/portal/rest/v1/social/users/gina@example.org/avatar');
    expect(requests).toEqual([['gina@example.org']]);
  });

  it('draws an outsider\'s initials as the server does -- an address alone is one word -- and asks for no picture of a message that carries one', async () => {
    wrapper = mountAvatar({ email: hit(5, 'INBOX', { sender: { address: 'john.doe@client.org' } }) });
    expect(wrapper.text()).toBe('J');
    await answered();
    expect(wrapper.find('img').exists()).toBe(false);
    wrapper.destroy();

    wrapper = mountAvatar({ email: hit(5, 'INBOX', { sender: { name: 'Ann', address: 'ann@example.org', avatarUrl: 'data:image/png;base64,AAAA' } }) });
    await answered();
    expect(requests).toEqual([['john.doe@client.org']]);
    expect(wrapper.find('img').attributes('src')).toBe('data:image/png;base64,AAAA');
  });

  it('draws the person asked for instead of the sender, by name alone', async () => {
    wrapper = mountAvatar({ email: hit(5, 'DRAFTS'), person: { name: 'Véronika Smith' }, size: 32 });
    await answered();
    expect(wrapper.text()).toBe('VS');
    expect(requests).toEqual([]);
  });
});

describe('the reading pane of a selection says how many mails its tiles act on (EXO-90891)', () => {
  let wrapper;

  afterEach(() => wrapper?.destroy());

  it('says it in bold, centred above the tiles', async () => {
    wrapper = shallowMount(EmailConnectorMailBoxDrawerMultiSelectEmail, {
      propsData: { emails: [hit(5), hit(6)], selectedEmails: ['INBOX:5'] },
      mocks: { $t: t },
    });
    const count = wrapper.find('.multi-select-count');
    expect(count.text()).toBe('emailConnector.mailBox.list.drawer.multiSelect.selectedOne');
    expect(count.classes()).toEqual(expect.arrayContaining(['font-weight-bold', 'text-center', 'text-h6']));
    // Above the actions' row, out of the flow, so the row stays at the pane's vertical centre.
    expect(count.attributes('style')).toContain('position: absolute');
    expect(count.attributes('style')).toContain('bottom: 100%');
    expect(count.element.nextElementSibling.tagName.toLowerCase()).toBe('email-connector-mail-box-drawer-actions');
    expect(wrapper.classes()).toEqual(expect.arrayContaining(['flex-column', 'align-center', 'justify-center']));

    await wrapper.setProps({ selectedEmails: ['INBOX:5', 'INBOX:6'] });
    expect(wrapper.find('.multi-select-count').text()).toBe('emailConnector.mailBox.list.drawer.multiSelect.selectedMany|2');
  });

  it('draws the tiles\' icons at 32 px', () => {
    wrapper = shallowMount(EmailConnectorMailBoxDrawerActions, {
      propsData: { emails: [hit(5)], selectedEmails: ['INBOX:5'], selectMode: true, top: false },
      mocks: {
        $t: t,
        $emailConnectorMailBoxService: { ...emailConnectorMailBoxService },
        $vuetify: { breakpoint: {}, rtl: false },
      },
    });
    const icons = wrapper.findAll('v-icon');
    expect(icons.length).toBeGreaterThan(0);
    icons.wrappers.forEach(icon => expect(icon.attributes('size')).toBe('32'));
  });
});
