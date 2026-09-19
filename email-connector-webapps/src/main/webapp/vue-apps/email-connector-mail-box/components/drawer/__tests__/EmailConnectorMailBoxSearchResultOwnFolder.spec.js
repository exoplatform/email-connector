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

// EXO-90416 — an IMAP UID only numbers a message within its folder, and a search holds
// several folders. Every action and every read on a search result used to travel as a
// bare UID, which the mailbox resolved against the LISTED folder first: acting on the
// hit ARCHIVE:5 while the inbox lists a UID 5 deleted, archived, spammed, moved, flagged
// or read INBOX:5 on the mail server. These pins use that twin -- two different
// messages under one number -- and assert every path addresses the hit's own folder and
// leaves the twin alone.

import { createLocalVue, mount, shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerActions from '../EmailConnectorMailBoxDrawerActions.vue';
import EmailConnectorMailBoxDrawerListItem from '../EmailConnectorMailBoxDrawerListItem.vue';
import EmailConnectorMailBoxDrawerListItemActionMenuItems from '../EmailConnectorMailBoxDrawerListItemActionMenuItems.vue';
import EmailConnectorMailBoxDrawerListItemDetail from '../EmailConnectorMailBoxDrawerListItemDetail.vue';
import EmailConnectorMailBoxDrawerThreadContent from '../EmailConnectorMailBoxDrawerThreadContent.vue';
import EmailConnectorMailBoxMoveToFolderDrawer from '../EmailConnectorMailBoxMoveToFolderDrawer.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'ARCHIVE', type: 'BUILT_IN', syncEnabled: true },
  { key: 'CUSTOM:1', type: 'CUSTOM', displayName: 'Factures', path: 'Factures', syncEnabled: true },
];

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

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
 * A message.
 *
 * @param {Number} mailRemoteId its UID
 * @param {String} folder the folder it is numbered in
 * @param {Object} extra further fields
 * @returns {Object} the message
 */
function message(mailRemoteId, folder, extra = {}) {
  return {
    mailRemoteId,
    folder,
    mailHeaderId: `<${folder}-${mailRemoteId}@host>`,
    threadId: `<${folder}-${mailRemoteId}@host>`,
    read: true,
    subject: `${folder} ${mailRemoteId}`,
    sender: { name: 'Alice', address: 'alice@host' },
    receivedDate: '2026-09-01T10:00:00Z',
    categoryIds: [],
    ...extra,
  };
}

describe('the mailbox addresses a search result in its own folder, never its listed twin (EXO-90416)', () => {
  let wrapper;
  let service;
  let alerts;
  let listener;

  /**
   * Mounts the mailbox drawer listing INBOX:5 (unread) and INBOX:6, searching, the
   * server having found ARCHIVE:5 -- another message under the listed number.
   *
   * @returns {Promise<void>} resolved once mounted
   */
  async function mountWithTwin() {
    service = serviceStub({
      folderLabel: emailConnectorMailBoxService.folderLabel,
      isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
      deleteEmails: jest.fn(() => Promise.resolve({ failedDeletions: 0 })),
      archiveEmails: jest.fn(() => Promise.resolve({ failedArchives: 0 })),
      markAsJunk: jest.fn(() => Promise.resolve({ failedJunkMoves: 0 })),
      moveEmails: jest.fn(() => Promise.resolve({ failedMoves: 0 })),
      undoMoveEmails: jest.fn(() => Promise.resolve({ failedUndos: 0 })),
      getEmailByRemoteId: jest.fn((mailRemoteId, folder) => Promise.resolve(message(mailRemoteId, folder))),
      getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
    });
    wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
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
      emailBox: { emails: [message(5, 'INBOX', { read: false }), message(6, 'INBOX')], folders: FOLDERS },
      searchTerm: 'nothing listed matches',
      searchServerResults: [message(5, 'ARCHIVE', { read: false, cached: true })],
    });
    alerts = [];
    listener = event => alerts.push(event.detail);
    document.addEventListener('alert-message', listener);
  }

  afterEach(() => {
    document.removeEventListener('alert-message', listener);
    wrapper?.vm.stopAutoRefresh();
    wrapper?.destroy();
  });

  it('deletes, archives and reports as spam the hit in its folder', async () => {
    await mountWithTwin();

    wrapper.vm.$root.$emit('delete-email', [5], 'ARCHIVE');
    wrapper.vm.$root.$emit('junk-email', [5], 'ARCHIVE');
    wrapper.vm.$root.$emit('archive-email', [5], 'ARCHIVE');
    await flush();

    expect(service.deleteEmails.mock.calls).toEqual([[[5], 'ARCHIVE', true]]);
    expect(service.markAsJunk.mock.calls).toEqual([[[5], 'ARCHIVE', true]]);
    expect(service.archiveEmails.mock.calls).toEqual([[[5], 'ARCHIVE']]);
  });

  it('moves the hit from its folder, and its Undo names the hit by its own Message-ID', async () => {
    await mountWithTwin();

    wrapper.vm.$root.$emit('move-email', [5], 'CUSTOM:1', 'ARCHIVE');
    await flush();
    expect(service.moveEmails.mock.calls).toEqual([[[5], 'ARCHIVE', 'CUSTOM:1']]);

    await alerts[0].alertLinkCallback();
    expect(service.undoMoveEmails).toHaveBeenCalledWith(['<ARCHIVE-5@host>'], 'CUSTOM:1', 'ARCHIVE');
  });

  it('marks the hit read or unread in its folder, and leaves the listed twin as it was', async () => {
    await mountWithTwin();

    wrapper.vm.$root.$emit('update-email-read-status', true, [5], 'ARCHIVE');
    await flush();

    expect(service.updateEmailsReadStatus.mock.calls).toEqual([[[5], true, 'ARCHIVE']]);
    expect(wrapper.vm.emails.find(email => email.mailRemoteId === 5).read).toBe(false);
    expect(wrapper.vm.searchServerResults[0].read).toBe(true);
  });

  it('reads a message opened from outside once, in its folder, and not one already read', async () => {
    await mountWithTwin();

    // What the mail drawer sends as it opens a mail it knows nothing of (a favorite, a
    // unified-search hit), then one it knows read, then the search row itself.
    wrapper.vm.$root.$emit('update-email-read-status', true, [9], 'ARCHIVE');
    wrapper.vm.$root.$emit('update-email-read-status', true, [7], 'ARCHIVE', true);
    await flush();
    expect(service.updateEmailsReadStatus.mock.calls).toEqual([[[9], true, 'ARCHIVE']]);

    wrapper.vm.$root.$emit('update-email-read-status', true, [5], 'ARCHIVE');
    wrapper.vm.$root.$emit('update-email-read-status', true, [5], 'ARCHIVE');
    await flush();
    expect(service.updateEmailsReadStatus.mock.calls).toEqual([[[9], true, 'ARCHIVE'], [[5], true, 'ARCHIVE']]);
  });

  it('reads a result opened in full screen in its folder, once, and not when it already was', async () => {
    await mountWithTwin();
    await wrapper.setData({ expanded: true });

    await wrapper.vm.openSearchResult(wrapper.vm.searchServerResults[0]);
    await flush();
    expect(service.updateEmailsReadStatus.mock.calls).toEqual([[[5], true, 'ARCHIVE']]);
    expect(wrapper.vm.selectEmailPlaceHolder).toBe(false);
    expect(wrapper.vm.emails.find(email => email.mailRemoteId === 5).read).toBe(false);

    service.updateEmailsReadStatus.mockClear();
    await wrapper.vm.openSearchResult({ ...wrapper.vm.searchServerResults[0] });
    await flush();
    expect(service.updateEmailsReadStatus).not.toHaveBeenCalled();
  });

  it('retries a result that could not be read in its own folder, never the listed twin', async () => {
    await mountWithTwin();
    await wrapper.setData({ expanded: true, email: { ...message(5, 'ARCHIVE'), unavailable: true } });
    service.getEmailByRemoteId.mockClear();

    wrapper.vm.$root.$emit('retry-email-read', wrapper.vm.email);
    // Not even for a moment: the listed INBOX:5 is another message.
    expect(wrapper.vm.email.folder).toBe('ARCHIVE');
    await flush();

    expect(service.getEmailByRemoteId).toHaveBeenCalledWith(5, 'ARCHIVE');
    expect(wrapper.vm.email.folder).toBe('ARCHIVE');
  });

  it('keeps a selection by folder and UID', async () => {
    await mountWithTwin();

    wrapper.vm.$root.$emit('select-email', { emailId: 5, folder: 'ARCHIVE', selected: true });
    wrapper.vm.$root.$emit('select-email', { emailId: 5, folder: 'INBOX', selected: true });
    wrapper.vm.$root.$emit('select-email', { emailId: 5, folder: 'INBOX', selected: false });

    expect(wrapper.vm.selectedEmails).toEqual(['ARCHIVE:5']);
  });

  it('never pushes a read status into a folder the interface only reads', async () => {
    await mountWithTwin();

    wrapper.vm.$root.$emit('update-email-read-status', true, [9], 'TRASH');
    await flush();

    expect(service.updateEmailsReadStatus).not.toHaveBeenCalled();
  });

  it('opens the mail drawer on a result, or a mail from outside, telling it the folder', async () => {
    await mountWithTwin();
    const openings = [];
    wrapper.vm.$root.$on('open-email-detail-drawer', (...args) => openings.push(args));

    await wrapper.vm.openSearchResult(wrapper.vm.searchServerResults[0]);
    await wrapper.vm.openMailFromOutside({ mailRemoteId: 5, folder: 'ARCHIVE' });

    expect(openings.map(args => args[6])).toEqual(['ARCHIVE', 'ARCHIVE']);
  });

  it('addresses a listed row exactly as before when no folder is given', async () => {
    await mountWithTwin();

    wrapper.vm.$root.$emit('delete-email', [5]);
    wrapper.vm.$root.$emit('update-email-read-status', true, [6]);
    await flush();

    expect(service.deleteEmails.mock.calls).toEqual([[[5], 'INBOX', true]]);
    expect(service.updateEmailsReadStatus).not.toHaveBeenCalled();
  });
});

describe('every emitter of an action or a read says which folder its ids are numbered in (EXO-90416)', () => {
  const archived = message(5, 'ARCHIVE');

  /**
   * Replaces a mounted component's root emit with a spy.
   *
   * @param {Object} vm the component
   * @returns {Function} the spy
   */
  function spyRoot(vm) {
    const emit = jest.fn();
    vm.$root.$emit = emit;
    return emit;
  }

  it('the move picker sends the folder it was opened for', () => {
    const emit = jest.fn();
    const picker = { mailRemoteIds: [5], sourceFolder: 'ARCHIVE', $root: { $emit: emit }, $refs: { moveToFolderDrawer: { close: jest.fn() } } };

    EmailConnectorMailBoxMoveToFolderDrawer.methods.moveTo.call(picker, { key: 'CUSTOM:1' });

    expect(emit).toHaveBeenCalledWith('move-email', [5], 'CUSTOM:1', 'ARCHIVE');
  });

  it.each([
    ['updateEmailReadStatus', 'update-email-read-status', args => args[2]],
    ['deleteEmail', 'delete-email', args => args[1]],
    ['archiveEmail', 'archive-email', args => args[1]],
    ['markAsJunk', 'junk-email', args => args[1]],
  ])('a row\'s menu sends its row\'s folder with %s', (method, event, folderOf) => {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemActionMenuItems, {
      propsData: { email: archived, thread: null },
      mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
    });
    const emit = spyRoot(wrapper.vm);

    wrapper.vm[method]();

    expect(folderOf(emit.mock.calls.find(call => call[0] === event).slice(1))).toBe('ARCHIVE');
    wrapper.destroy();
  });

  describe('a list row', () => {
    /**
     * Mounts one row of a list.
     *
     * @param {Object} props the row's props
     * @returns {Object} {wrapper, emit}
     */
    function mountRow(props) {
      const localVue = createLocalVue();
      localVue.directive('touch', {});
      localVue.directive('touch-hold', {});
      const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItem, {
        localVue,
        propsData: props,
        mocks: {
          $t: key => key,
          $emailConnectorMailBoxService: { ...emailConnectorMailBoxService, formatDateString: () => 'today' },
          $vuetify: { breakpoint: { smAndDown: false } },
        },
      });
      return { wrapper, emit: spyRoot(wrapper.vm) };
    }

    it('opens its message with its folder, in both layouts', () => {
      let { wrapper, emit } = mountRow({ email: archived, expanded: true });
      wrapper.vm.openDetail();
      expect(emit).toHaveBeenCalledWith('open-email-detail-content', 5, 'ARCHIVE');
      wrapper.destroy();

      ({ wrapper, emit } = mountRow({ email: archived, emails: [archived] }));
      wrapper.vm.openDetail();
      expect(emit.mock.calls.find(call => call[0] === 'open-email-detail-drawer')[7]).toBe('ARCHIVE');
      wrapper.destroy();
    });

    it('sends a swipe as its menu acts: the conversation in the row\'s own folder, with that folder', () => {
      const inbox = message(3, 'INBOX');
      const alsoArchived = message(4, 'ARCHIVE');
      const thread = { threadId: 't', emails: [archived, inbox, alsoArchived], mailRemoteIds: [5, 3, 4], count: 3, unreadCount: 0 };
      const { wrapper, emit } = mountRow({ email: archived, thread });

      wrapper.vm.emitForRow('delete-email');

      expect(emit.mock.calls).toEqual([['delete-email', [5, 4], 'ARCHIVE']]);
      wrapper.destroy();
    });

    it('selects only its own messages, by folder and UID, never another folder\'s message sharing a number', () => {
      const { wrapper, emit } = mountRow({ email: archived, selectMode: true, selectedEmails: ['INBOX:5'] });

      expect(wrapper.vm.selected).toBe(false);
      wrapper.vm.emitSelect(true);

      expect(emit).toHaveBeenCalledWith('select-email', { emailId: 5, folder: 'ARCHIVE', selected: true });
      wrapper.destroy();
    });
  });

  describe('the bulk actions of a selection', () => {
    /**
     * Mounts the bulk toolbar over a list and a selection.
     *
     * @param {Array} emails the list
     * @param {Array} selectedEmails the selection keys (folder:uid)
     * @returns {Object} {wrapper, emit}
     */
    function mountBar(emails, selectedEmails) {
      const wrapper = shallowMount(EmailConnectorMailBoxDrawerActions, {
        propsData: { emails, selectedEmails, selectMode: true },
        mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
      });
      return { wrapper, emit: spyRoot(wrapper.vm) };
    }

    it('send each folder\'s ids with that folder', () => {
      const { wrapper, emit } = mountBar([message(5, 'ARCHIVE'), message(6, 'INBOX')], ['ARCHIVE:5', 'INBOX:6']);

      wrapper.vm.deleteEmails();
      wrapper.vm.updateEmailsReadStatus(false);

      expect(emit.mock.calls).toEqual([
        ['delete-email', [5], 'ARCHIVE'], ['delete-email', [6], 'INBOX'],
        ['update-email-read-status', false, [5], 'ARCHIVE'], ['update-email-read-status', false, [6], 'INBOX'],
      ]);
      wrapper.destroy();
    });

    it('enable "Move to..." only for a selection in one folder, and say why otherwise', async () => {
      // The folder list lives on the root, unobserved (see the mailbox drawer's
      // loadEmailBox): a new selection is what makes the bar read it again.
      const { wrapper: single, emit } = mountBar([message(5, 'INBOX'), message(6, 'INBOX')], ['INBOX:5']);
      single.vm.$root.mailFolders = FOLDERS;
      await single.setProps({ selectedEmails: ['INBOX:5', 'INBOX:6'] });
      const enabledInOneFolder = single.vm.canMoveSelection;
      single.vm.moveEmails();
      expect(emit).toHaveBeenCalledWith('open-move-to-folder-drawer', [5, 6], 'INBOX');
      single.destroy();

      const { wrapper: mixed } = mountBar([message(5, 'ARCHIVE'), message(6, 'INBOX')], ['ARCHIVE:5']);
      mixed.vm.$root.mailFolders = FOLDERS;
      await mixed.setProps({ selectedEmails: ['ARCHIVE:5', 'INBOX:6'] });
      expect(mixed.vm.canOfferMove).toBe(true);
      expect(mixed.vm.canMoveSelection).toBe(false);
      expect(mixed.vm.moveTitle).toBe('emailConnector.mailBox.list.drawer.detail.moveTo.oneFolder');
      expect(mixed.find('[title="emailConnector.mailBox.list.drawer.detail.moveTo.oneFolder"]').exists()).toBe(true);
      mixed.destroy();

      expect(enabledInOneFolder).toBe(true);
    });

    it('act on the selected twin only: ticking ARCHIVE:5 leaves INBOX:5 out', () => {
      const { wrapper, emit } = mountBar([message(5, 'INBOX'), message(5, 'ARCHIVE')], ['ARCHIVE:5']);

      wrapper.vm.archiveEmails();
      wrapper.vm.restoreEmails();

      expect(emit.mock.calls).toEqual([['archive-email', [5], 'ARCHIVE'], ['restore-email', [5]]]);
      wrapper.destroy();
    });
  });

  it('the reader marks its conversation read per folder, only the conversation\'s own rows', () => {
    const emit = jest.fn();
    const conversation = message(5, 'ARCHIVE', { read: false, threadId: 't' });
    const sibling = message(8, 'SENT', { read: false, threadId: 't' });
    const twin = message(5, 'INBOX', { read: false });
    const reader = {
      email: conversation,
      emails: [conversation, sibling, twin],
      threadKey: EmailConnectorMailBoxDrawerThreadContent.methods.threadKey,
      $root: { $emit: emit },
    };

    EmailConnectorMailBoxDrawerThreadContent.methods.markThreadRead.call(reader);

    expect(emit.mock.calls).toEqual([
      ['update-email-read-status', true, [5], 'ARCHIVE'],
      ['update-email-read-status', true, [8], 'SENT'],
    ]);
  });
});

describe('the mail drawer opened on search results opens, reads and removes the hit in its own folder (EXO-90416)', () => {
  let wrapper;
  let service;
  let reads;

  /**
   * Mounts the mail drawer, expanded, over a search list holding INBOX:5 and ARCHIVE:5.
   *
   * @returns {void}
   */
  function mountOnTwins() {
    service = serviceStub({
      isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
      getEmailByRemoteId: jest.fn((mailRemoteId, folder) => Promise.resolve(message(mailRemoteId, folder))),
    });
    wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetail, {
      mocks: { $t: key => key, $emailConnectorMailBoxService: service, $vuetify: { breakpoint: {}, rtl: false } },
      stubs: { 'exo-drawer': true },
    });
    reads = [];
    wrapper.vm.$root.$on('update-email-read-status', (read, ids, folder, known) => reads.push(typeof known === 'boolean' ? [read, ids, folder, known] : [read, ids, folder]));
  }

  afterEach(() => wrapper?.destroy());

  it('opens the hit it was handed from its folder, and reads it there', async () => {
    await mountOnTwins();
    const twins = [message(5, 'INBOX'), message(5, 'ARCHIVE')];

    wrapper.vm.$root.$emit('open-email-detail-drawer', 5, twins, false, null, true, false, 'ARCHIVE');
    await flush();

    expect(service.getEmailByRemoteId).toHaveBeenCalledWith(5, 'ARCHIVE');
    // With what the list knows of it: read already, so nothing is pushed again.
    expect(reads).toEqual([[true, [5], 'ARCHIVE', true]]);
  });

  it('opens a mail from outside with a single read, and tells what it knows of it', async () => {
    mountOnTwins();

    wrapper.vm.$root.$emit('open-email-detail-drawer', 9, [{ mailRemoteId: 9, folder: 'ARCHIVE', cached: true }], false, null, true, true, 'ARCHIVE');
    wrapper.vm.$root.$emit('open-email-detail-drawer', 5, [message(5, 'ARCHIVE', { read: true })], false, null, true, false, 'ARCHIVE');
    await flush();

    expect(reads).toEqual([[true, [9], 'ARCHIVE'], [true, [5], 'ARCHIVE', true]]);
    // The row it was handed turned read with that first read: the reader's own read of
    // the conversation has nothing left to send.
    const emit = jest.fn();
    EmailConnectorMailBoxDrawerThreadContent.methods.markThreadRead.call({
      email: wrapper.vm.emails[0], emails: wrapper.vm.emails,
      threadKey: EmailConnectorMailBoxDrawerThreadContent.methods.threadKey, $root: { $emit: emit },
    });
    expect(emit).not.toHaveBeenCalled();
  });

  it('switches to a row of its list from the row\'s folder', async () => {
    await mountOnTwins();
    await wrapper.setData({ emailDetailDrawer: true, expanded: true, emails: [message(5, 'INBOX'), message(5, 'ARCHIVE')] });

    wrapper.vm.$root.$emit('open-email-detail-content', 5, 'ARCHIVE');
    await flush();

    expect(service.getEmailByRemoteId).toHaveBeenCalledWith(5, 'ARCHIVE');
    // With what the list knows of it: read already, so nothing is pushed again.
    expect(reads).toEqual([[true, [5], 'ARCHIVE', true]]);
  });

  it('retries a result that could not be read in its own folder', async () => {
    mountOnTwins();
    await wrapper.setData({ emailDetailDrawer: true, detachedFromList: true,
      emails: [message(5, 'INBOX'), message(5, 'ARCHIVE')], email: { ...message(5, 'ARCHIVE'), unavailable: true } });

    wrapper.vm.$root.$emit('retry-email-read', wrapper.vm.email);
    await flush();

    expect(service.getEmailByRemoteId).toHaveBeenCalledWith(5, 'ARCHIVE');
  });

  it('keeps its selection by folder and UID', async () => {
    mountOnTwins();
    await wrapper.setData({ emailDetailDrawer: true });

    wrapper.vm.$root.$emit('select-email', { emailId: 5, folder: 'ARCHIVE', selected: true });

    expect(wrapper.vm.selectedEmails).toEqual(['ARCHIVE:5']);
  });

  it('removes only the hit acted on from its list, and turns only it read', async () => {
    await mountOnTwins();
    await wrapper.setData({ emailDetailDrawer: true, expanded: true,
      emails: [message(5, 'INBOX', { read: false }), message(5, 'ARCHIVE', { read: false })] });

    wrapper.vm.$root.$emit('update-email-read-status', true, [5], 'ARCHIVE');
    expect(wrapper.vm.emails.map(email => email.read)).toEqual([false, true]);

    wrapper.vm.$root.$emit('delete-email', [5], 'ARCHIVE');
    await flush();
    expect(wrapper.vm.emails.map(email => `${email.folder}:${email.mailRemoteId}`)).toEqual(['INBOX:5']);
  });
});

describe('an unread result opened in the narrow layout is read on the server, once (EXO-90416)', () => {
  it('through the mailbox drawer handing it to the mail drawer', async () => {
    const service = serviceStub({
      folderLabel: emailConnectorMailBoxService.folderLabel,
      isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
      threadIdsInFolder: emailConnectorMailBoxService.threadIdsInFolder,
      getEmailByRemoteId: jest.fn((mailRemoteId, folder) => Promise.resolve(message(mailRemoteId, folder, { read: false }))),
      getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
    });
    // Both drawers under one root, the way the page holds them: they talk through it.
    const host = mount({
      render(createElement) {
        return createElement('div', [
          createElement(EmailConnectorMailBoxDrawer, { ref: 'mailbox' }),
          createElement(EmailConnectorMailBoxDrawerListItemDetail, { ref: 'mail' }),
        ]);
      },
    }, {
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: service,
        $emailConnectorCommonService: serviceStub({}),
        $vuetify: { breakpoint: {}, rtl: false },
      },
      stubs: { 'exo-drawer': true },
    });
    const mailbox = host.vm.$refs.mailbox;
    mailbox.emailBoxDrawer = true;
    mailbox.emailBox = { emails: [message(6, 'INBOX')], folders: FOLDERS };
    mailbox.searchTerm = 'nothing listed matches';
    mailbox.searchServerResults = [message(5, 'ARCHIVE', { read: false, cached: true })];
    await host.vm.$nextTick();

    await mailbox.openSearchResult(mailbox.mergedSearchResults[0]);
    await flush();

    expect(host.vm.$refs.mail.emailDetailDrawer).toBe(true);
    expect(service.updateEmailsReadStatus.mock.calls).toEqual([[[5], true, 'ARCHIVE']]);
    expect(mailbox.searchServerResults[0].read).toBe(true);
    mailbox.stopAutoRefresh();
    host.destroy();
  });
});

describe('a search row selects one scope: its conversation in its own folder (EXO-90416)', () => {
  it('the checkbox, the row\'s ticked state and the menu\'s "Select" agree', () => {
    const localVue = createLocalVue();
    localVue.directive('touch', {});
    localVue.directive('touch-hold', {});
    const archived = message(5, 'ARCHIVE', { threadId: 't' });
    const sentCopy = message(8, 'SENT', { threadId: 't' });
    const thread = { threadId: 't', emails: [archived, sentCopy], mailRemoteIds: [5, 8], count: 2, unreadCount: 0 };
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItem, {
      localVue,
      propsData: { email: archived, thread, selectMode: true, selectedEmails: ['ARCHIVE:5'] },
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: { ...emailConnectorMailBoxService, formatDateString: () => 'today' },
        $vuetify: { breakpoint: { smAndDown: false } },
      },
    });
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    // What the menu's "Select" leaves behind reads as ticked...
    expect(wrapper.vm.selected).toBe(true);
    // ...and the checkbox selects the same messages, never the Sent copy.
    wrapper.vm.emitSelect(true);
    expect(emit.mock.calls).toEqual([['select-email', { emailId: 5, folder: 'ARCHIVE', selected: true }]]);
    wrapper.destroy();
  });
});
