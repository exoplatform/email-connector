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

// EXO-90415 — the full-screen mailbox shows its FOLDERS and CATEGORIES in a column
// beside the list, Gmail/Outlook style, rather than in the 3-dots menu: the column, the
// menu that hides them in full screen only, and the drawer that lays the column out,
// follows it and opens the first mail of wherever the user went.

import Vue from 'vue';
import { mount, shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerNavigation from '../EmailConnectorMailBoxDrawerNavigation.vue';
import EmailConnectorMailBoxDrawerActionMenuItems from '../EmailConnectorMailBoxDrawerActionMenuItems.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';
import { KEY_OPEN_DELAY_MS } from '../../../js/EmailConnectorMailBoxListNavigation.js';

Vue.config.ignoredElements.push(/^email-connector-/, 'extension-registry-components', 'exo-confirm-dialog');

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'SENT', type: 'BUILT_IN', syncEnabled: true },
  { key: 'DRAFTS', type: 'BUILT_IN', syncEnabled: true },
  { key: 'JUNK', type: 'BUILT_IN', syncEnabled: true },
  { key: 'CUSTOM:1', type: 'CUSTOM', displayName: 'Factures', path: 'Factures', syncEnabled: true },
];

const CATEGORIES = [
  { id: 11, name: 'Important', nameId: 'emailImportantCategory', icon: 'fa-exclamation' },
  { id: 12, name: 'Invitation', nameId: 'emailInvitationCategory', icon: 'fa-calendar' },
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
 * A stand-in for the platform's exo-drawer: its expand flag and event, its drawerWidth
 * prop, and every named slot rendered.
 *
 * @returns {Object} the component
 */
function exoDrawerStub() {
  return {
    name: 'exo-drawer',
    props: {
      value: { type: Boolean, default: false },
      allowExpand: { type: Boolean, default: false },
      drawerWidth: { type: String, default: '420px' },
    },
    data: () => ({ expand: false }),
    watch: {
      expand(expand) {
        this.$emit('expand-updated', expand);
      },
    },
    methods: {
      toogleExpand() {
        this.expand = !this.expand;
      },
      resetFilter() {
        return null;
      },
    },
    render(createElement) {
      return createElement('div', Object.keys(this.$slots)
        .map(name => createElement('div', { attrs: { 'data-slot': name } }, this.$slots[name])));
    },
  };
}

/**
 * A listed message, alone in its conversation.
 *
 * @param {Number} mailRemoteId the IMAP UID
 * @param {String} folder the folder it is numbered in
 * @param {Object} extra further fields
 * @returns {Object} the row
 */
function row(mailRemoteId, folder = 'INBOX', extra = {}) {
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
    ...extra,
  };
}

/**
 * Mounts the mailbox drawer, open, over the inbox; the other folders answer their own
 * rows when listed.
 *
 * @param {Object} byFolder the rows of each folder, by folder key
 * @returns {Promise<Object>} {wrapper, service, teardown}
 */
async function mountDrawer(byFolder) {
  const service = serviceStub({
    folderLabel: emailConnectorMailBoxService.folderLabel,
    folderIcon: emailConnectorMailBoxService.folderIcon,
    isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
    isListingRow: emailConnectorMailBoxService.isListingRow,
    getEmailByRemoteId: jest.fn((mailRemoteId, folder) => Promise.resolve({ ...row(mailRemoteId, folder), to: [] })),
    getEmailBox: jest.fn(folder => Promise.resolve({ emails: byFolder[folder || 'INBOX'] || [], folders: FOLDERS, emailSyncStatus: 'SUCCESS' })),
    getAvailableEmailCategories: jest.fn(() => Promise.resolve(CATEGORIES)),
    getSubcategoryIds: jest.fn(id => Promise.resolve([id])),
  });
  const wrapper = mount(EmailConnectorMailBoxDrawer, {
    attachTo: document.body,
    mocks: {
      $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key),
      $emailConnectorMailBoxService: service,
      $emailConnectorCommonService: serviceStub({}),
      $vuetify: { breakpoint: {}, rtl: false },
    },
    stubs: { 'exo-drawer': exoDrawerStub() },
  });
  await flush();
  await wrapper.setData({
    emailBoxDrawer: true,
    emailBox: { emails: byFolder.INBOX || [], folders: FOLDERS, emailSyncStatus: 'SUCCESS' },
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
 * Expands the drawer the way exo-drawer does, and waits for its `expanded` to follow.
 *
 * @param {Object} fixture the mounted drawer
 * @returns {Promise<void>} resolved once full screen
 */
async function expand(fixture) {
  jest.useFakeTimers();
  fixture.wrapper.vm.$refs.emailBoxDrawer.toogleExpand();
  await fixture.wrapper.vm.$nextTick();
  jest.advanceTimersByTime(250);
  jest.useRealTimers();
  await flush();
}

describe('the folder column (EXO-90415)', () => {
  /**
   * Mounts the column alone.
   *
   * @param {Object} propsData its props
   * @returns {Object} {wrapper, emit}
   */
  function mountColumn(propsData) {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerNavigation, {
      propsData: { folders: FOLDERS, categories: CATEGORIES, ...propsData },
      mocks: {
        $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key),
        $emailConnectorMailBoxService: emailConnectorMailBoxService,
      },
      // The entries are the tooltips' activators: left an unknown element, a tooltip
      // would render its text and never its entry.
      stubs: { 'v-tooltip': { template: '<div><slot name="activator" :on="{}" :attrs="{}" /><span class="tooltip"><slot /></span></div>' } },
    });
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;
    return { wrapper, emit };
  }

  it('names and draws each folder as the 3-dots menu does', () => {
    const { wrapper } = mountColumn({});
    const menu = shallowMount(EmailConnectorMailBoxDrawerActionMenuItems, {
      propsData: { availableFolders: FOLDERS },
      mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
    });

    expect(wrapper.vm.folderEntries.map(entry => [entry.key, entry.label, entry.icon]))
      .toEqual(menu.vm.visibleFolders.map(folder => [folder.key, folder.label, folder.icon]));
    expect(wrapper.vm.folderEntries.map(entry => entry.icon))
      .toEqual(['fa-inbox', 'fa-paper-plane', 'fa-file-alt', 'fa-ban', 'fa-folder']);
    // A folder of the user's own is shown as they named it, never through the bundle.
    expect(wrapper.vm.folderEntries[4].label).toBe('Factures');
  });

  it('lights the listed folder, and a category view instead of it when one is open', async () => {
    const { wrapper } = mountColumn({ currentFolder: 'SENT' });
    expect(wrapper.vm.activeKey).toBe('folder:SENT');

    await wrapper.setProps({ categoryViewId: 12 });
    expect(wrapper.vm.activeKey).toBe('category:12');
  });

  it('sends the menu\'s own events, the folder already listed included', () => {
    const { wrapper, emit } = mountColumn({ currentFolder: 'INBOX', categoryViewId: 11 });

    wrapper.vm.switchFolder('INBOX');
    wrapper.vm.openCategoryView(12);

    expect(emit.mock.calls).toEqual([['switch-folder', 'INBOX'], ['open-category-view', 12]]);
  });

  it('shows the names beside the icons open, in tooltips only as a rail', () => {
    const open = mountColumn({ rail: false }).wrapper;
    expect(open.findAll('v-list-item-title').wrappers.map(title => title.text()))
      .toEqual(['emailConnector.mailBox.list.drawer.folder.inbox', 'emailConnector.mailBox.list.drawer.folder.sent',
        'emailConnector.mailBox.list.drawer.folder.drafts', 'emailConnector.mailBox.list.drawer.folder.junk', 'Factures',
        'Important', 'Invitation']);
    expect(open.text()).toContain('emailConnector.mailBox.list.drawer.menu.folders');

    const rail = mountColumn({ rail: true }).wrapper;
    expect(rail.findAll('v-list-item-title').length).toBe(0);
    expect(rail.findAll('v-subheader').length).toBe(0);
    // Each entry keeps its name for the tooltip and for a screen reader.
    expect(rail.findAll('v-list-item').wrappers.map(item => item.attributes('aria-label')))
      .toEqual(['emailConnector.mailBox.list.drawer.folder.inbox', 'emailConnector.mailBox.list.drawer.folder.sent',
        'emailConnector.mailBox.list.drawer.folder.drafts', 'emailConnector.mailBox.list.drawer.folder.junk', 'Factures',
        'Important', 'Invitation']);
  });
});

describe('the 3-dots menu in full screen keeps the actions only (EXO-90415)', () => {
  /**
   * Mounts the menu's items.
   *
   * @param {Object} propsData its props
   * @returns {Object} the wrapper
   */
  function mountMenu(propsData) {
    return shallowMount(EmailConnectorMailBoxDrawerActionMenuItems, {
      propsData: { availableFolders: FOLDERS, categories: CATEGORIES, hasWebmailAccess: true, ...propsData },
      mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
    });
  }

  it('leaves FOLDERS and CATEGORIES to the column', () => {
    const text = mountMenu({ hideViews: true }).text();
    expect(text).not.toContain('emailConnector.mailBox.list.drawer.menu.folders');
    expect(text).not.toContain('emailConnector.mailBox.list.drawer.menu.categories');
    expect(text).not.toContain('Factures');
    expect(text).toContain('emailConnector.mailBox.list.drawer.sync.tooltip');
    expect(text).toContain('emailConnector.mailBox.list.drawer.menu.selectSeveral');
    expect(text).toContain('emailConnector.mailBox.list.drawer.webmail.button.title');
  });

  it('keeps them in the narrow layout', () => {
    const text = mountMenu({}).text();
    expect(text).toContain('emailConnector.mailBox.list.drawer.menu.folders');
    expect(text).toContain('Factures');
    expect(text).toContain('emailConnector.mailBox.list.drawer.menu.categories');
  });
});

describe('the full-screen left pane (EXO-90415)', () => {
  let fixture;

  afterEach(() => {
    jest.useRealTimers();
    fixture?.teardown();
    try {
      window.localStorage.clear();
    } catch (e) {
      // nothing stored
    }
  });

  it('puts the column beside the list, and hides FOLDERS and CATEGORIES from the menu there only', async () => {
    fixture = await mountDrawer({ INBOX: [row(1), row(2)] });
    await expand(fixture);

    const left = fixture.wrapper.find('[data-slot="fullAppLeftContent"]');
    expect(left.find('email-connector-mail-box-drawer-navigation').exists()).toBe(true);
    expect(fixture.wrapper.find('[data-slot="fullAppLeftTitle"] email-connector-mail-box-drawer-actions')
      .attributes('hide-views')).toBeDefined();

    jest.useFakeTimers();
    fixture.wrapper.vm.$refs.emailBoxDrawer.toogleExpand();
    await fixture.wrapper.vm.$nextTick();
    jest.advanceTimersByTime(250);
    jest.useRealTimers();
    await fixture.wrapper.vm.$nextTick();
    expect(fixture.wrapper.find('email-connector-mail-box-drawer-navigation').exists()).toBe(false);
    expect(fixture.wrapper.find('[data-slot="titleIcons"] email-connector-mail-box-drawer-actions')
      .attributes('hide-views')).toBeUndefined();
  });

  it('stays on screen over an empty folder, where it is the way out', async () => {
    fixture = await mountDrawer({ INBOX: [row(1)], SENT: [] });
    await expand(fixture);

    fixture.wrapper.vm.$root.$emit('switch-folder', 'SENT');
    await flush();
    await fixture.wrapper.vm.$nextTick();

    expect(fixture.wrapper.vm.emails).toEqual([]);
    expect(fixture.wrapper.find('email-connector-mail-box-drawer-navigation').exists()).toBe(true);
  });

  it('widens with the column, at once, and narrows at once on collapse', async () => {
    fixture = await mountDrawer({ INBOX: [row(1)] });
    await fixture.wrapper.setData({ navigationRail: false });
    const drawer = fixture.wrapper.vm.$refs.emailBoxDrawer;
    expect(drawer.drawerWidth).toBe('420px');

    jest.useFakeTimers();
    drawer.toogleExpand();
    await fixture.wrapper.vm.$nextTick();
    // Before the drawer's own `expanded` has followed.
    expect(fixture.wrapper.vm.expanded).toBe(false);
    expect(drawer.drawerWidth).toBe('620px');
    jest.advanceTimersByTime(250);

    await fixture.wrapper.setData({ navigationRail: true });
    expect(drawer.drawerWidth).toBe('476px');

    drawer.toogleExpand();
    await fixture.wrapper.vm.$nextTick();
    // Never a 476 px narrow drawer while `expanded` catches up.
    expect(fixture.wrapper.vm.expanded).toBe(true);
    expect(drawer.drawerWidth).toBe('420px');
    jest.advanceTimersByTime(250);
    jest.useRealTimers();
  });

  it('folds to a rail and back, and remembers it in the browser', async () => {
    fixture = await mountDrawer({ INBOX: [row(1)] });
    await fixture.wrapper.setData({ navigationRail: false });

    fixture.wrapper.vm.toggleNavigationRail();
    expect(fixture.wrapper.vm.navigationRail).toBe(true);
    expect(window.localStorage.getItem('emailConnector.mailBox.navigationRail')).toBe('true');

    fixture.wrapper.vm.toggleNavigationRail();
    expect(window.localStorage.getItem('emailConnector.mailBox.navigationRail')).toBe('false');
  });

  it('starts as the user left it, else as a rail below 1440 px', async () => {
    const width = window.innerWidth;
    try {
      window.localStorage.setItem('emailConnector.mailBox.navigationRail', 'false');
      window.innerWidth = 1280;
      fixture = await mountDrawer({ INBOX: [] });
      expect(fixture.wrapper.vm.navigationRail).toBe(false);
      fixture.teardown();

      window.localStorage.clear();
      fixture = await mountDrawer({ INBOX: [] });
      expect(fixture.wrapper.vm.navigationRail).toBe(true);
      fixture.teardown();

      window.innerWidth = 1600;
      fixture = await mountDrawer({ INBOX: [] });
      expect(fixture.wrapper.vm.navigationRail).toBe(false);
    } finally {
      window.innerWidth = width;
    }
  });

  it('opens by the width alone when the browser keeps nothing', async () => {
    const width = window.innerWidth;
    const getItem = jest.spyOn(Storage.prototype, 'getItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    const setItem = jest.spyOn(Storage.prototype, 'setItem').mockImplementation(() => {
      throw new Error('blocked');
    });
    try {
      window.innerWidth = 1280;
      fixture = await mountDrawer({ INBOX: [] });
      expect(fixture.wrapper.vm.navigationRail).toBe(true);
      fixture.wrapper.vm.toggleNavigationRail();
      expect(fixture.wrapper.vm.navigationRail).toBe(false);
    } finally {
      getItem.mockRestore();
      setItem.mockRestore();
      window.innerWidth = width;
    }
  });
});

describe('going somewhere in full screen opens its first mail (EXO-90415)', () => {
  let fixture;

  afterEach(() => {
    jest.useRealTimers();
    fixture?.teardown();
  });

  it('opens the first mail of the folder switched to, automatically, and focuses its row', async () => {
    fixture = await mountDrawer({ INBOX: [row(1), row(2)], SENT: [row(7, 'SENT'), row(8, 'SENT')] });
    await expand(fixture);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(1);
    fixture.service.getEmailByRemoteId.mockClear();
    const revealThreadRow = jest.spyOn(fixture.wrapper.vm, 'revealThreadRow');

    fixture.wrapper.vm.$root.$emit('switch-folder', 'SENT');
    await flush();

    expect(fixture.service.getEmailByRemoteId.mock.calls).toEqual([[7, 'SENT', { broadcast: false }]]);
    expect(fixture.wrapper.vm.email.folder).toBe('SENT');
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(7);
    expect(fixture.wrapper.vm.autoOpenReadPending).toBe(true);
    expect(revealThreadRow).toHaveBeenCalledWith(row(7, 'SENT').threadId);
  });

  it('never shows the previous folder\'s mail while the next one loads', async () => {
    let answer;
    fixture = await mountDrawer({ INBOX: [row(1)], SENT: [row(7, 'SENT')] });
    await expand(fixture);
    fixture.service.getEmailBox.mockImplementationOnce(() => new Promise(resolve => {
      answer = () => resolve({ emails: [row(7, 'SENT')], folders: FOLDERS, emailSyncStatus: 'SUCCESS' });
    }));

    fixture.wrapper.vm.$root.$emit('switch-folder', 'SENT');
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(true);
    answer();
    await flush();

    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(7);
  });

  it('opens the first mail of a category view the open mail is not in', async () => {
    fixture = await mountDrawer({ INBOX: [row(1), row(2, 'INBOX', { categoryIds: [12] })] });
    await expand(fixture);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(1);

    fixture.wrapper.vm.$root.$emit('open-category-view', 12);
    await flush();
    await flush();

    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
  });

  it('keeps the open mail when the view still lists it', async () => {
    fixture = await mountDrawer({ INBOX: [row(1, 'INBOX', { categoryIds: [12] }), row(2, 'INBOX', { categoryIds: [12] })] });
    await expand(fixture);
    await fixture.wrapper.vm.openListedEmail(fixture.wrapper.vm.emails[1]);
    await flush();

    fixture.wrapper.vm.$root.$emit('open-category-view', 12);
    await flush();
    await flush();

    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
  });

  it('opens nothing in the narrow layout, where the list is what is on screen', async () => {
    fixture = await mountDrawer({ INBOX: [row(1)], SENT: [row(7, 'SENT')] });
    const revealThreadRow = jest.spyOn(fixture.wrapper.vm, 'revealThreadRow');

    fixture.wrapper.vm.$root.$emit('switch-folder', 'SENT');
    await flush();

    expect(fixture.service.getEmailByRemoteId).not.toHaveBeenCalled();
    // Nor does it move the focus: the 3-dots menu it was picked from keeps it.
    expect(revealThreadRow).not.toHaveBeenCalled();
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(false);
  });

  it('leaves the arrow keys pressed in the column to the column, and walks the list once the focus left it', async () => {
    fixture = await mountDrawer({ INBOX: [row(1), row(2), row(3)] });
    await expand(fixture);
    fixture.service.getEmailByRemoteId.mockClear();
    // v-list-item-group renders the column as a listbox.
    const column = document.createElement('div');
    column.setAttribute('role', 'listbox');
    const entry = document.createElement('div');
    entry.tabIndex = 0;
    column.appendChild(entry);
    fixture.wrapper.element.appendChild(column);

    const inColumn = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
    entry.dispatchEvent(inColumn);
    await new Promise(resolve => setTimeout(resolve, KEY_OPEN_DELAY_MS + 20));
    expect(inColumn.defaultPrevented).toBe(false);
    expect(fixture.service.getEmailByRemoteId).not.toHaveBeenCalled();

    // What a folder switch leaves: the focus off the column (on the opened row, or on
    // the page); the keys are the list's again.
    entry.blur();
    const onPage = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
    document.body.dispatchEvent(onPage);
    await new Promise(resolve => setTimeout(resolve, KEY_OPEN_DELAY_MS + 20));
    expect(fixture.service.getEmailByRemoteId.mock.calls[0][0]).toBe(2);
  });
});
