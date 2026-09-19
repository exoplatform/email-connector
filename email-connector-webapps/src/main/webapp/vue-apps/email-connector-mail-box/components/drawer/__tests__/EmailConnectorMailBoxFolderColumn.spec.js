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
import EmailConnectorMailBoxApp from '../../EmailConnectorMailBoxApp.vue';
import EmailConnectorMailBoxDrawerNoEmail from '../EmailConnectorMailBoxDrawerNoEmail.vue';
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

    expect(wrapper.vm.folderEntries.map(entry => [entry.value, entry.label, entry.icon]))
      .toEqual(menu.vm.visibleFolders.map(folder => [`folder:${folder.key}`, folder.label, folder.icon]));
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

  it('makes each entry an option of the listbox, selected when lit, with tooltip attributes only as a rail', () => {
    const tooltipped = { template: '<div><slot name="activator" :on="{}" :attrs="{ \'aria-haspopup\': \'true\' }" /><slot /></div>' };
    const mountWith = rail => shallowMount(EmailConnectorMailBoxDrawerNavigation, {
      propsData: { folders: FOLDERS, categories: CATEGORIES, currentFolder: 'SENT', rail },
      mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
      stubs: { 'v-tooltip': tooltipped },
    });
    const open = mountWith(false).findAll('v-list-item');
    expect(open.wrappers.every(item => item.attributes('role') === 'option')).toBe(true);
    expect(open.wrappers.map(item => item.attributes('aria-selected')))
      .toEqual(['false', 'true', 'false', 'false', 'false', 'false', 'false']);
    expect(open.at(0).attributes('aria-haspopup')).toBeUndefined();
    expect(mountWith(true).findAll('v-list-item').at(0).attributes('aria-haspopup')).toBe('true');
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
    expect(rail.findAll('v-subheader').wrappers.every(header => header.isVisible() === false)).toBe(true);
    // Each entry keeps its name for the tooltip and for a screen reader.
    expect(rail.findAll('v-list-item').wrappers.map(item => item.attributes('aria-label')))
      .toEqual(['emailConnector.mailBox.list.drawer.folder.inbox', 'emailConnector.mailBox.list.drawer.folder.sent',
        'emailConnector.mailBox.list.drawer.folder.drafts', 'emailConnector.mailBox.list.drawer.folder.junk', 'Factures',
        'Important', 'Invitation']);
  });
});

describe('the folder column folds to a rail and back without losing its order (EXO-90415)', () => {
  /**
   * Mounts the column, open, with the platform's components as plain elements.
   *
   * @returns {Object} the wrapper
   */
  function mountOpenColumn() {
    return mount(EmailConnectorMailBoxDrawerNavigation, {
      propsData: { folders: FOLDERS, categories: CATEGORIES, rail: false },
      mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
      stubs: { 'v-tooltip': { template: '<span><slot name="activator" :on="{}" :attrs="{}" /></span>' } },
    });
  }

  /**
   * What the column shows, top to bottom: headers, dividers and entries, the hidden
   * ones left out.
   *
   * @param {Object} wrapper the mounted column
   * @returns {Array<String>} H:<header>, DIV or I:<entry>
   */
  function visibleOrder(wrapper) {
    return Array.from(wrapper.element.querySelectorAll('v-subheader, v-divider, v-list-item'))
      .filter(element => !element.closest('[style*="display: none"]'))
      .map(element => {
        if (element.tagName === 'V-SUBHEADER') {
          return `H:${element.querySelector('span').textContent.trim()}`;
        }
        return element.tagName === 'V-DIVIDER' ? 'DIV' : `I:${element.getAttribute('aria-label')}`;
      });
  }

  const OPEN = ['H:emailConnector.mailBox.list.drawer.menu.folders',
    'I:emailConnector.mailBox.list.drawer.folder.inbox', 'I:emailConnector.mailBox.list.drawer.folder.sent',
    'I:emailConnector.mailBox.list.drawer.folder.drafts', 'I:emailConnector.mailBox.list.drawer.folder.junk', 'I:Factures',
    'DIV', 'H:emailConnector.mailBox.list.drawer.menu.categories', 'I:Important', 'I:Invitation'];

  it('keeps each section\'s header, divider and entries together, open again after a rail', async () => {
    const wrapper = mountOpenColumn();
    expect(visibleOrder(wrapper)).toEqual(OPEN);

    await wrapper.setProps({ rail: true });
    await wrapper.setProps({ rail: false });

    expect(visibleOrder(wrapper)).toEqual(OPEN);
    // The real VTooltip's patching put the CATEGORIES header above the folders when the
    // headers were loose siblings of the entries; each section is its own element now.
    const sections = wrapper.findAll('[data-section]').wrappers;
    expect(sections.map(section => section.attributes('data-section'))).toEqual(['folders', 'categories']);
    expect(sections[0].findAll('v-list-item').length).toBe(5);
    expect(sections[1].findAll('v-list-item').length).toBe(2);
    expect(sections[1].find('v-subheader').text()).toBe('emailConnector.mailBox.list.drawer.menu.categories');
  });

  it('shows no divider, header or top spacing as a rail: the first icon is on the first row', async () => {
    const wrapper = mountOpenColumn();
    await wrapper.setProps({ rail: true });

    expect(visibleOrder(wrapper)).toEqual(['I:emailConnector.mailBox.list.drawer.folder.inbox',
      'I:emailConnector.mailBox.list.drawer.folder.sent', 'I:emailConnector.mailBox.list.drawer.folder.drafts',
      'I:emailConnector.mailBox.list.drawer.folder.junk', 'I:Factures', 'I:Important', 'I:Invitation']);
  });

  it('starts its first row on the chips row\'s line, open (the FOLDERS header) and as a rail (the first icon)', async () => {
    // The chips row and the column's first row share one height; the FOLDERS header is
    // as tall and centres its label, and as a rail the first entry -- a dense row -- is
    // centred in it by half of the difference. Heights, not offsets.
    const wrapper = mountOpenColumn();
    const header = wrapper.find('[data-section="folders"] v-subheader');
    expect(header.attributes('style')).toContain(`height: ${emailConnectorMailBoxService.LIST_TOP_ROW_HEIGHT}`);
    expect(wrapper.find('[data-section="categories"] v-subheader').attributes('style')).toBeUndefined();
    expect(wrapper.element.style.paddingTop).toBe('0px');
    expect(visibleOrder(wrapper)[0]).toBe('H:emailConnector.mailBox.list.drawer.menu.folders');

    await wrapper.setProps({ rail: true });
    expect(emailConnectorMailBoxService.RAIL_TOP_PADDING).toBe(`${(emailConnectorMailBoxService.LIST_TOP_ROW_HEIGHT_PX
      - emailConnectorMailBoxService.DENSE_ROW_HEIGHT_PX) / 2}px`);
    expect(wrapper.element.style.paddingTop).toBe(emailConnectorMailBoxService.RAIL_TOP_PADDING);
    expect(visibleOrder(wrapper)[0]).toBe('I:emailConnector.mailBox.list.drawer.folder.inbox');
    // Nothing of its own paints over the shade the drawer gives it.
    expect(wrapper.element.classList.contains('transparent')).toBe(false);
  });
});

describe('the folder column\'s icons sit on the axis of their names (EXO-90415)', () => {
  it('centres every icon, open and as a rail, folders and categories, with the platform\'s nav gap', async () => {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerNavigation, {
      propsData: { folders: FOLDERS, categories: CATEGORIES },
      mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
      stubs: { 'v-tooltip': { template: '<div><slot name="activator" :on="{}" :attrs="{}" /></div>' } },
    });
    // Vuetify's own list icon is top-aligned (align-self: flex-start, 8 px margins on a
    // dense item), which put a 16 px icon above its name's centre line; the social
    // hamburger menus centre it the same way (my-auto, me-2).
    const icons = () => wrapper.findAll('v-list-item-icon').wrappers;
    expect(icons()).toHaveLength(7);
    icons().forEach(icon => expect(icon.classes()).toEqual(expect.arrayContaining(['my-auto', 'align-self-center', 'align-center', 'ms-0', 'me-2'])));

    await wrapper.setProps({ rail: true });
    icons().forEach(icon => {
      expect(icon.classes()).toEqual(expect.arrayContaining(['my-auto', 'align-self-center', 'align-center', 'mx-auto']));
      expect(icon.classes()).not.toContain('me-2');
    });
  });
});

describe('the folder column opens the settings\' folders drawer (EXO-90415)', () => {
  /**
   * Mounts the column alone.
   *
   * @param {Boolean} rail whether folded to a rail
   * @returns {Object} {wrapper, emit}
   */
  function mountColumn(rail) {
    const wrapper = mount(EmailConnectorMailBoxDrawerNavigation, {
      propsData: { folders: FOLDERS, categories: CATEGORIES, rail },
      mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
      stubs: {
        'v-tooltip': { template: '<span><slot name="activator" :on="{}" :attrs="{}" /></span>' },
        'v-btn': { template: '<button type="button" v-bind="$attrs" @click="$emit(\'click\')"><slot /></button>' },
      },
    });
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;
    return { wrapper, emit };
  }

  const MANAGE = '[aria-label="emailConnector.mailBox.list.drawer.navigation.manageFolders"]';

  it('offers a pen on the FOLDERS header, open, and opens the drawer with it', async () => {
    const { wrapper, emit } = mountColumn(false);
    const buttons = wrapper.findAll(MANAGE);
    expect(buttons).toHaveLength(1);
    expect(buttons.at(0).attributes('title')).toBe('emailConnector.mailBox.list.drawer.navigation.manageFolders');
    // On FOLDERS, not on CATEGORIES.
    expect(wrapper.find('[data-section="folders"]').find(MANAGE).exists()).toBe(true);
    expect(buttons.at(0).isVisible()).toBe(true);

    await buttons.at(0).trigger('click');
    expect(emit).toHaveBeenCalledWith('open-email-folders-drawer');
  });

  it('hides it as a rail, with the header', () => {
    const { wrapper } = mountColumn(true);
    expect(wrapper.find(MANAGE).isVisible()).toBe(false);
  });

  it('mounts the settings\' own folders and name drawers in the mailbox app, reused, not copied', () => {
    const template = EmailConnectorMailBoxApp.template
      || shallowMount(EmailConnectorMailBoxApp, { mocks: { $emailConnectorCommonService: {} } }).html();
    expect(template).toContain('email-connector-user-setting-folders-drawer');
    expect(template).toContain('email-connector-user-setting-folder-name-drawer');
  });
});

describe('the folder column\'s counts cap at 99+ (EXO-90415)', () => {
  it('formats a count as the platform\'s badges do', () => {
    expect(emailConnectorMailBoxService.formatCount(99)).toBe('99');
    expect(emailConnectorMailBoxService.formatCount(100)).toBe('99+');
    expect(emailConnectorMailBoxService.formatCount(0)).toBe('');
    expect(emailConnectorMailBoxService.formatCount(null)).toBe('');
  });

  it('shows 99+ in the column and keeps the exact number for a screen reader and a hover', () => {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerNavigation, {
      propsData: {
        folders: FOLDERS,
        categories: [],
        folderCounts: { INBOX: { count: 99, unread: true }, DRAFTS: { count: 100, unread: false } },
      },
      mocks: {
        $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key),
        $emailConnectorMailBoxService: emailConnectorMailBoxService,
      },
      stubs: { 'v-tooltip': { template: '<div><slot name="activator" :on="{}" :attrs="{}" /></div>' } },
    });
    expect(wrapper.findAll('v-list-item-action-text').wrappers.map(count => count.text())).toEqual(['99', '99+']);
    const drafts = wrapper.findAll('v-list-item').at(2);
    expect(drafts.attributes('aria-label')).toBe('emailConnector.mailBox.list.drawer.navigation.total|emailConnector.mailBox.list.drawer.folder.drafts|100');
    expect(drafts.attributes('title')).toBe(drafts.attributes('aria-label'));
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

  it('keeps the list, folder or search, on the pane\'s grey, the column a shade darker, a divider between, open and as a rail', async () => {
    fixture = await mountDrawer({ INBOX: [row(1)] });
    await fixture.wrapper.setData({ navigationRail: false });
    await expand(fixture);

    const pane = () => fixture.wrapper.vm.$refs.expandedListPane;
    const column = () => fixture.wrapper.find('email-connector-mail-box-drawer-navigation');
    // The chips row is the height the column's first row shares, its chips centred in it.
    const chips = pane().querySelector('email-connector-mail-box-drawer-filter-chips');
    expect(chips.style.minHeight).toBe(emailConnectorMailBoxService.LIST_TOP_ROW_HEIGHT);
    expect(chips.classList.contains('py-3')).toBe(false);
    // The list paints nothing of its own: exo-drawer's grey pane, as its header strip.
    expect(Array.from(pane().classList).some(name => name.includes('background') || name === 'white')).toBe(false);
    // A veil of the platform's grey over the pane: darker than the list in any
    // branding, and not a class Vuetify's .transparent could outrank.
    expect(column().attributes('style')).toContain('background-color: rgba(112, 112, 112, 0.08)');
    // The divider sits between them, whichever the reading direction.
    const divider = column().element.nextElementSibling;
    expect(divider.tagName).toBe('V-DIVIDER');
    expect(divider.getAttribute('vertical')).not.toBeNull();
    expect(divider.nextElementSibling).toBe(pane());

    await fixture.wrapper.setData({ navigationRail: true, searchTerm: 'mail' });
    expect(pane().querySelector('email-connector-mail-box-drawer-search-results')).not.toBeNull();
    expect(Array.from(pane().classList).some(name => name.includes('background'))).toBe(false);
    expect(column().attributes('style')).toContain('background-color: rgba(112, 112, 112, 0.08)');
  });

  describe('an empty list says so in the list, not in the reader', () => {
    /**
     * Where the "No email" message is, and what the reader shows.
     *
     * @returns {Object} {inList, compact, inReader, placeholder, reader}
     */
    function emptyState() {
      const left = fixture.wrapper.find('[data-slot="fullAppLeftContent"]');
      const content = fixture.wrapper.find('[data-slot="content"]');
      const inList = left.find('email-connector-mail-box-drawer-no-email');
      return {
        inList: inList.exists(),
        compact: inList.exists() && 'compact' in inList.attributes(),
        inReader: content.exists() && content.find('email-connector-mail-box-drawer-no-email').exists(),
        placeholder: content.exists() && content.find('email-connector-mail-box-drawer-select-email').exists(),
        reader: content.exists() && content.find('email-connector-mail-box-drawer-thread-content').exists(),
      };
    }

    it('an empty folder', async () => {
      fixture = await mountDrawer({ INBOX: [] });
      await expand(fixture);

      expect(emptyState()).toEqual({ inList: true, compact: true, inReader: false, placeholder: false, reader: false });
      // Under the chips, which stay: the way out of a filter.
      const chips = fixture.wrapper.vm.$refs.expandedListPane.querySelector('email-connector-mail-box-drawer-filter-chips');
      expect(chips.nextElementSibling.tagName).toBe('EMAIL-CONNECTOR-MAIL-BOX-DRAWER-NO-EMAIL');
    });

    it('chips or a category view matching nothing', async () => {
      fixture = await mountDrawer({ INBOX: [row(1)] });
      await expand(fixture);
      expect(emptyState().reader).toBe(true);

      await fixture.wrapper.setData({ selectEmailPlaceHolder: true });
      fixture.wrapper.vm.$root.$emit('open-category-view', 12);
      await flush();
      await flush();
      expect(emptyState()).toEqual({ inList: true, compact: true, inReader: false, placeholder: false, reader: false });
    });

    it('a search with no result', async () => {
      fixture = await mountDrawer({ INBOX: [row(1)] });
      await expand(fixture);
      await fixture.wrapper.setData({ searchTerm: 'nothing matches this', selectEmailPlaceHolder: true });

      const state = emptyState();
      expect(state.inReader).toBe(false);
      expect(state.placeholder).toBe(false);
      // The search results say "no result" in the list column themselves.
      expect(fixture.wrapper.vm.$refs.expandedListPane.querySelector('email-connector-mail-box-drawer-search-results')).not.toBeNull();
    });

    it('keeps the select-an-email placeholder while the list has mail', async () => {
      fixture = await mountDrawer({ INBOX: [row(1)] });
      await expand(fixture);
      await fixture.wrapper.setData({ selectEmailPlaceHolder: true });

      expect(emptyState().placeholder).toBe(true);
    });

    /**
     * Draws the message on its own.
     *
     * @param {Object} propsData its props
     * @returns {Object} the wrapper
     */
    function drawMessage(propsData) {
      return shallowMount(EmailConnectorMailBoxDrawerNoEmail, {
        propsData,
        mocks: { $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key) },
      });
    }

    it('centres the compact message under its icon, in the platform\'s muted colours', () => {
      const compact = drawMessage({ compact: true, folderName: 'Sent' });
      const block = compact.element;
      expect(block.classList.contains('text-center')).toBe(true);
      expect(block.classList.contains('pt-10')).toBe(true);
      const icon = compact.find('v-icon');
      expect(icon.attributes('size')).toBe('32');
      expect(icon.classes()).toContain('icon-default-color');
      // The text right under the icon, 8 px apart, in the row's secondary style.
      const text = icon.element.nextElementSibling;
      expect(Array.from(text.classList)).toEqual(expect.arrayContaining(['mt-2', 'text-subtitle', 'text-sub-title']));
      expect(compact.find('v-list-item').exists()).toBe(false);
    });

    it('says why the list is empty: the folder, or the filters, which it offers to clear', () => {
      expect(drawMessage({ compact: true, folderName: 'Sent' }).find('.text-sub-title').text())
        .toBe('emailConnector.mailBox.list.drawer.noEmail.folder|Sent');
      const filtered = drawMessage({ compact: true, folderName: 'Sent', filtered: true });
      expect(filtered.find('.text-sub-title').text()).toBe('emailConnector.mailBox.list.drawer.noEmail.filtered');
      expect(filtered.find('v-btn').text()).toBe('emailConnector.mailBox.list.drawer.noEmail.clearFilters');
      expect(drawMessage({ compact: true, folderName: 'Sent' }).find('v-btn').exists()).toBe(false);
    });

    it('keeps the narrow message as it was', () => {
      const narrow = drawMessage({});
      expect(narrow.find('v-list-item').classes()).toEqual(expect.arrayContaining(['full-height', 'align-center']));
      expect(narrow.find('v-icon').attributes('size')).toBe('60');
      expect(narrow.find('v-list-item-title').text()).toBe('emailConnector.mailBox.list.drawer.noEmail');
    });

    it('tells the message the folder and whether filters narrow the list, and clears them all', async () => {
      fixture = await mountDrawer({ INBOX: [row(1, 'INBOX', { starred: true })] });
      await expand(fixture);
      const message = () => fixture.wrapper.find('[data-slot="fullAppLeftContent"] email-connector-mail-box-drawer-no-email');

      // Unread on, then the Important view: nothing matches both.
      fixture.wrapper.vm.toggleUnreadFilter();
      fixture.wrapper.vm.$root.$emit('open-category-view', 11);
      await flush();
      await flush();
      expect(message().exists()).toBe(true);
      expect(message().attributes('filtered')).toBe('true');
      expect(message().attributes('folder-name')).toBe('emailConnector.mailBox.list.drawer.folder.inbox');

      fixture.service.getEmailBox.mockClear();
      // The message is a plain element here: its event reaches the drawer as a DOM one.
      message().element.dispatchEvent(new CustomEvent('clear-filters'));
      await flush();
      await flush();

      expect(fixture.wrapper.vm.categoryViewId).toBeNull();
      expect(fixture.wrapper.vm.unreadOnly).toBe(false);
      expect(fixture.wrapper.vm.emails.map(email => email.mailRemoteId)).toEqual([1]);
      expect(message().exists()).toBe(false);
      // Unread and a category are client-side: nothing to reload for them.
      expect(fixture.service.getEmailBox).not.toHaveBeenCalled();
    });

    it('clears the Favorites chip too, which the server answers', async () => {
      fixture = await mountDrawer({ INBOX: [row(1)] });
      await expand(fixture);
      await fixture.wrapper.setData({ favoriteOnly: true });
      fixture.service.getEmailBox.mockClear();

      fixture.wrapper.vm.clearFilters();
      await flush();

      expect(fixture.wrapper.vm.favoriteOnly).toBe(false);
      expect(fixture.service.getEmailBox).toHaveBeenCalledWith('INBOX', false);
    });

    it('leaves the narrow layout as it was: the message fills the drawer', async () => {
      fixture = await mountDrawer({ INBOX: [] });
      await fixture.wrapper.vm.$nextTick();

      const message = fixture.wrapper.find('[data-slot="content"]').find('email-connector-mail-box-drawer-no-email');
      expect(message.exists()).toBe(true);
      expect(message.attributes('compact')).toBeUndefined();
    });

    it('a mailbox blocked from synchronizing says so across the drawer', async () => {
      fixture = await mountDrawer({ INBOX: [] });
      await fixture.wrapper.setData({ emailBox: { emails: [], folders: FOLDERS, emailSyncStatus: 'BLOCKED' } });
      await expand(fixture);

      expect(fixture.wrapper.find('[data-slot="fullAppLeftContent"]').exists()).toBe(false);
      expect(fixture.wrapper.find('[data-slot="content"]').text()).toContain('emailConnector.mailBox.list.drawer.sync.blocked.reconnect');
    });
  });

  it('re-reads the folder list when the settings\' folders drawer changed it', async () => {
    fixture = await mountDrawer({ INBOX: [row(1)] });
    await expand(fixture);
    fixture.service.getEmailBox.mockClear();
    ['email-folders-list-changed', 'email-folders-saved', 'email-folders-updated']
      .forEach(event => fixture.wrapper.vm.$root.$emit(event));
    await flush();
    expect(fixture.service.getEmailBox.mock.calls).toEqual([['INBOX', false], ['INBOX', false], ['INBOX', false]]);
    // The column and the menu read the new list.
    fixture.service.getEmailBox.mockImplementationOnce(() => Promise.resolve({ emails: [row(1)],
      folders: [...FOLDERS, { key: 'CUSTOM:2', type: 'CUSTOM', displayName: 'Projets', syncEnabled: true }], emailSyncStatus: 'SUCCESS' }));
    fixture.wrapper.vm.$root.$emit('email-folders-list-changed');
    await flush();
    expect(fixture.wrapper.vm.availableFolders.map(folder => folder.key)).toContain('CUSTOM:2');
  });

  it('falls back to the inbox when the listed folder was deleted', async () => {
    fixture = await mountDrawer({ INBOX: [row(1)], 'CUSTOM:1': [row(5, 'CUSTOM:1')] });
    await expand(fixture);
    fixture.wrapper.vm.$root.$emit('switch-folder', 'CUSTOM:1');
    await flush();
    expect(fixture.wrapper.vm.currentFolder).toBe('CUSTOM:1');
    // Deleted: the listing refuses the key.
    fixture.service.getEmailBox.mockImplementationOnce(() => Promise.reject(new Error('emailConnector.folder.notBrowsable')));

    fixture.wrapper.vm.$root.$emit('email-folders-saved');
    await flush();
    await flush();

    expect(fixture.wrapper.vm.currentFolder).toBe('INBOX');
    expect(fixture.service.getEmailBox).toHaveBeenLastCalledWith('INBOX', false);
  });

  it('falls back to the inbox when the listed folder is no longer offered (opted out)', async () => {
    fixture = await mountDrawer({ INBOX: [row(1)], 'CUSTOM:1': [row(5, 'CUSTOM:1')] });
    await expand(fixture);
    fixture.wrapper.vm.$root.$emit('switch-folder', 'CUSTOM:1');
    await flush();
    fixture.service.getEmailBox.mockImplementationOnce(() => Promise.resolve({ emails: [],
      folders: FOLDERS.map(folder => (folder.key === 'CUSTOM:1' ? { ...folder, syncEnabled: false } : folder)), emailSyncStatus: 'SUCCESS' }));

    fixture.wrapper.vm.$root.$emit('email-folders-updated');
    await flush();
    await flush();

    expect(fixture.wrapper.vm.currentFolder).toBe('INBOX');
  });

  it('leaves the arrow keys to the folders drawer opened over it', async () => {
    fixture = await mountDrawer({ INBOX: [row(1), row(2)] });
    await expand(fixture);
    fixture.service.getEmailByRemoteId.mockClear();
    window.eXo = { openedDrawers: [fixture.wrapper.vm.$refs.emailBoxDrawer, { id: 'userSettingFoldersDrawer' }] };
    try {
      const event = new KeyboardEvent('keydown', { key: 'ArrowDown', bubbles: true, cancelable: true });
      document.body.dispatchEvent(event);
      await new Promise(resolve => setTimeout(resolve, KEY_OPEN_DELAY_MS + 20));
      expect(event.defaultPrevented).toBe(false);
      expect(fixture.service.getEmailByRemoteId).not.toHaveBeenCalled();
    } finally {
      delete window.eXo;
    }
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

  it('never opens the previous folder\'s mail when the folder is switched from a category view', async () => {
    let answer;
    fixture = await mountDrawer({ INBOX: [row(1), row(2, 'INBOX', { categoryIds: [12] }), row(3)], SENT: [row(7, 'SENT')] });
    await expand(fixture);
    fixture.wrapper.vm.$root.$emit('open-category-view', 12);
    await flush();
    await flush();
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
    fixture.service.getEmailByRemoteId.mockClear();
    fixture.service.getEmailBox.mockImplementationOnce(() => new Promise(resolve => {
      answer = () => resolve({ emails: [row(7, 'SENT')], folders: FOLDERS, emailSyncStatus: 'SUCCESS' });
    }));

    fixture.wrapper.vm.$root.$emit('switch-folder', 'SENT');
    await flush();
    await flush();

    // Leaving the view opened nothing of the inbox while SENT was on its way.
    expect(fixture.service.getEmailByRemoteId).not.toHaveBeenCalled();
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(true);
    expect(fixture.wrapper.vm.autoOpenReadPending).toBe(false);
    answer();
    await flush();
    expect(fixture.service.getEmailByRemoteId.mock.calls).toEqual([[7, 'SENT', { broadcast: false }]]);
  });

  it('opens nothing of a folder the user already left for another one still loading', async () => {
    const answers = {};
    fixture = await mountDrawer({ INBOX: [row(1)] });
    await expand(fixture);
    fixture.service.getEmailBox.mockImplementation(folder => new Promise(resolve => {
      answers[folder.toLowerCase()] = rows => resolve({ emails: rows, folders: FOLDERS, emailSyncStatus: 'SUCCESS' });
    }));
    fixture.service.getEmailByRemoteId.mockClear();

    fixture.wrapper.vm.$root.$emit('switch-folder', 'SENT');
    fixture.wrapper.vm.$root.$emit('switch-folder', 'DRAFTS');
    // SENT's answer lands after DRAFTS was asked for: DRAFTS is still on its way.
    answers.sent([row(7, 'SENT')]);
    await flush();
    expect(fixture.service.getEmailByRemoteId).not.toHaveBeenCalled();

    answers.drafts([row(8, 'DRAFTS')]);
    await flush();
    expect(fixture.service.getEmailByRemoteId.mock.calls).toEqual([[8, 'DRAFTS', { broadcast: false }]]);
  });

  it('opens nothing of a folder left for another one, even when its list answers last', async () => {
    const answers = {};
    fixture = await mountDrawer({ INBOX: [row(1)] });
    await expand(fixture);
    fixture.service.getEmailBox.mockImplementation(folder => new Promise(resolve => {
      answers[folder.toLowerCase()] = rows => resolve({ emails: rows, folders: FOLDERS, emailSyncStatus: 'SUCCESS' });
    }));
    fixture.service.getEmailByRemoteId.mockClear();

    fixture.wrapper.vm.$root.$emit('switch-folder', 'SENT');
    fixture.wrapper.vm.$root.$emit('switch-folder', 'DRAFTS');
    answers.drafts([row(8, 'DRAFTS')]);
    await flush();
    answers.sent([row(7, 'SENT')]);
    await flush();

    expect(fixture.service.getEmailByRemoteId.mock.calls).toEqual([[8, 'DRAFTS', { broadcast: false }]]);
    expect(fixture.wrapper.vm.currentFolder).toBe('DRAFTS');
  });

  it('still opens a category view\'s first mail while a chip reloads the folder', async () => {
    let answer;
    fixture = await mountDrawer({ INBOX: [row(1, 'INBOX', { starred: true }), row(2, 'INBOX', { categoryIds: [12], starred: true })] });
    await expand(fixture);
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(1);
    fixture.service.getEmailBox.mockImplementationOnce(() => new Promise(resolve => {
      answer = () => resolve({ emails: [row(1, 'INBOX', { starred: true }), row(2, 'INBOX', { categoryIds: [12], starred: true })], folders: FOLDERS, emailSyncStatus: 'SUCCESS' });
    }));
    const revealThreadRow = jest.spyOn(fixture.wrapper.vm, 'revealThreadRow');

    // The Favorites chip reloads the folder (the favorite subset is the server's).
    fixture.wrapper.vm.onToggleFavoriteFilter();
    fixture.wrapper.vm.$root.$emit('open-category-view', 12);
    await flush();
    await flush();
    answer();
    await flush();

    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
    expect(fixture.wrapper.vm.selectEmailPlaceHolder).toBe(false);
    expect(revealThreadRow).toHaveBeenCalledWith(row(2).threadId);
  });

  it('reads the categories\' subcategories on the first full screen only, never for a narrow drawer', async () => {
    fixture = await mountDrawer({ INBOX: [row(1)] });
    await flush();
    expect(fixture.service.getSubcategoryIds).not.toHaveBeenCalled();

    await expand(fixture);
    await flush();
    expect(fixture.service.getSubcategoryIds.mock.calls.map(([id]) => id)).toEqual([11, 12]);

    fixture.wrapper.vm.updateExpand(false);
    fixture.wrapper.vm.updateExpand(true);
    await flush();
    expect(fixture.service.getSubcategoryIds).toHaveBeenCalledTimes(2);
    jest.useFakeTimers();
    jest.runOnlyPendingTimers();
    jest.useRealTimers();
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

  it('gives the focus to the kept mail\'s row when the view still lists it, so the arrows walk on from it', async () => {
    fixture = await mountDrawer({ INBOX: [row(1, 'INBOX', { categoryIds: [12] }), row(2, 'INBOX', { categoryIds: [12] }),
      row(3, 'INBOX', { categoryIds: [12] })] });
    await expand(fixture);
    await fixture.wrapper.vm.openListedEmail(fixture.wrapper.vm.emails[1]);
    await flush();
    const revealThreadRow = jest.spyOn(fixture.wrapper.vm, 'revealThreadRow');

    fixture.wrapper.vm.$root.$emit('open-category-view', 12);
    await flush();
    await flush();
    expect(revealThreadRow).toHaveBeenCalledWith(row(2).threadId);

    // And when the view is left for the folder that still holds it.
    revealThreadRow.mockClear();
    fixture.wrapper.vm.$root.$emit('open-category-view', 12);
    await flush();
    await flush();
    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(2);
    expect(revealThreadRow).toHaveBeenCalledWith(row(2).threadId);
  });

  it('gives the focus to the first row when the kept mail is pinned from outside and not listed', async () => {
    fixture = await mountDrawer({ INBOX: [row(1, 'INBOX', { categoryIds: [12] }), row(2, 'INBOX', { categoryIds: [12] })] });
    await expand(fixture);
    await fixture.wrapper.setData({ pinnedEmail: true, email: { ...row(9), to: [] }, selectEmailPlaceHolder: false });
    const revealThreadRow = jest.spyOn(fixture.wrapper.vm, 'revealThreadRow');

    fixture.wrapper.vm.$root.$emit('open-category-view', 12);
    await flush();
    await flush();

    expect(fixture.wrapper.vm.email.mailRemoteId).toBe(9);
    expect(revealThreadRow).toHaveBeenCalledWith(row(1).threadId);
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

describe('the folder column counts what Gmail and Outlook count (EXO-90415)', () => {
  let fixture;

  afterEach(() => {
    jest.useRealTimers();
    fixture?.teardown();
  });

  /**
   * The folder list as the server sends it, with its counts.
   *
   * @returns {Array} the folder views
   */
  function countedFolders() {
    return [
      { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true, count: 40, unreadCount: 5 },
      { key: 'SENT', type: 'BUILT_IN', syncEnabled: true, count: 30, unreadCount: 2 },
      { key: 'DRAFTS', type: 'BUILT_IN', syncEnabled: true, count: 3, unreadCount: 3 },
      { key: 'JUNK', type: 'BUILT_IN', syncEnabled: true, count: 7, unreadCount: 4 },
    ];
  }

  it('unread on the inbox and the spam, the total on the drafts, nothing elsewhere', async () => {
    fixture = await mountDrawer({ INBOX: [row(1)] });
    await fixture.wrapper.setData({ emailBox: { emails: [row(1)], folders: countedFolders(), emailSyncStatus: 'SUCCESS' } });

    expect(fixture.wrapper.vm.folderCounts).toEqual({
      INBOX: { count: 5, unread: true },
      DRAFTS: { count: 3, unread: false },
      JUNK: { count: 4, unread: true },
    });
  });

  it('follows the reads made here until the next load brings the server\'s count', async () => {
    fixture = await mountDrawer({ INBOX: [] });
    const unread = [row(1, 'INBOX', { read: false }), row(2, 'INBOX', { read: false })];
    await fixture.wrapper.setData({ emailBox: { emails: unread, folders: countedFolders(), emailSyncStatus: 'SUCCESS' } });

    fixture.wrapper.vm.updateEmailsReadStatus(true, [1, 2]);
    expect(fixture.wrapper.vm.folderCounts.INBOX.count).toBe(3);
    fixture.wrapper.vm.updateEmailsReadStatus(false, [2]);
    expect(fixture.wrapper.vm.folderCounts.INBOX.count).toBe(4);
    // Read again: already read, no change, no count.
    fixture.wrapper.vm.updateEmailsReadStatus(true, [1]);
    expect(fixture.wrapper.vm.folderCounts.INBOX.count).toBe(4);

    fixture.service.getEmailBox.mockImplementationOnce(() => Promise.resolve({
      emails: unread, folders: countedFolders().map(folder => (folder.key === 'INBOX' ? { ...folder, unreadCount: 9 } : folder)),
      emailSyncStatus: 'SUCCESS',
    }));
    await fixture.wrapper.vm.loadEmailBox();
    expect(fixture.wrapper.vm.folderCounts.INBOX.count).toBe(9);
  });

  it('counts a hit outside the listed window read here only when it is known to have been unread', async () => {
    fixture = await mountDrawer({ INBOX: [] });
    await fixture.wrapper.setData({
      emailBox: { emails: [], folders: countedFolders(), emailSyncStatus: 'SUCCESS' },
      searchTerm: 'offer',
      searchServerResults: [row(8, 'INBOX', { read: false }), row(9, 'JUNK', { read: false })],
    });

    fixture.wrapper.vm.updateEmailsReadStatus(true, [8], 'INBOX');
    expect(fixture.wrapper.vm.folderCounts.INBOX.count).toBe(4);
    // Unknown to this drawer: pushed, but not counted.
    fixture.wrapper.vm.updateEmailsReadStatus(true, [99], 'INBOX');
    expect(fixture.wrapper.vm.folderCounts.INBOX.count).toBe(4);
    // The spam is read-only here: its read state is the server's alone, and so is its count.
    fixture.wrapper.vm.updateEmailsReadStatus(true, [9], 'JUNK');
    expect(fixture.wrapper.vm.folderCounts.JUNK.count).toBe(4);
  });

  it('counts each category\'s unread mail over the loaded window, its subcategories included', async () => {
    fixture = await mountDrawer({ INBOX: [] });
    fixture.service.getSubcategoryIds.mockImplementation(id => Promise.resolve(id === 12 ? [12, 120] : [id]));
    await fixture.wrapper.vm.readCategorySubtrees();
    await fixture.wrapper.setData({ emailBox: { emails: [
      row(1, 'INBOX', { read: false, categoryIds: [11] }),
      row(2, 'INBOX', { read: true, categoryIds: [11] }),
      row(3, 'INBOX', { read: false, categoryIds: [120] }),
      row(4, 'INBOX', { read: false, categoryIds: [] }),
    ], folders: FOLDERS, emailSyncStatus: 'SUCCESS' } });

    expect(fixture.wrapper.vm.categoryUnreadCounts).toEqual({ 11: 1, 12: 1 });

    // A message deleted here leaves the count at once, with the list.
    fixture.wrapper.vm.deleteEmails([1], 'INBOX');
    expect(fixture.wrapper.vm.categoryUnreadCounts[11]).toBe(0);
  });

  it('shows the counts in the column, bold when unread, as a dot on the rail', () => {
    const mountColumn = rail => shallowMount(EmailConnectorMailBoxDrawerNavigation, {
      propsData: {
        folders: FOLDERS,
        categories: CATEGORIES,
        folderCounts: { INBOX: { count: 5, unread: true }, DRAFTS: { count: 3, unread: false }, JUNK: { count: 0, unread: true } },
        categoryUnreadCounts: { 11: 2, 12: 0 },
        rail,
      },
      mocks: {
        $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key),
        $emailConnectorMailBoxService: emailConnectorMailBoxService,
      },
      stubs: { 'v-tooltip': { template: '<div><slot name="activator" :on="{}" :attrs="{}" /><span class="tooltip"><slot /></span></div>' } },
    });
    const open = mountColumn(false);
    const counts = open.findAll('v-list-item-action-text').wrappers.map(count => [count.text(), count.classes('font-weight-bold')]);
    expect(counts).toEqual([['5', true], ['3', false], ['2', true]]);
    expect(open.findAll('v-list-item').at(0).attributes('aria-label'))
      .toBe('emailConnector.mailBox.list.drawer.navigation.unread|emailConnector.mailBox.list.drawer.folder.inbox|5');

    const rail = mountColumn(true);
    const dots = rail.findAll('v-badge').wrappers.map(badge => badge.attributes('value') === 'true');
    // Inbox and Important have unread mail; the drafts' total is no reason for a dot.
    expect(dots).toEqual([true, false, false, false, false, true, false]);
  });
});
