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

// EXO-90434 -- the Scheduled view as the PO found it on the rig: its rows on the pane's
// own background, no loading bar of its own, a row that opens, and a Reschedule that
// works in the platform's popup.

import Vue from 'vue';
import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxScheduledList from '../EmailConnectorMailBoxScheduledList.vue';
import EmailConnectorMailBoxScheduledListItem from '../EmailConnectorMailBoxScheduledListItem.vue';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerThreadDraft from '../EmailConnectorMailBoxDrawerThreadDraft.vue';
import EmailConnectorMailBoxDrawerThreadContent from '../EmailConnectorMailBoxDrawerThreadContent.vue';
import EmailConnectorMailBoxDrawerListItemDetail from '../EmailConnectorMailBoxDrawerListItemDetail.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

Vue.config.ignoredElements.push(/^email-connector-/, 'extension-registry-components', 'exo-confirm-dialog', 'exo-drawer', 'exo-modal');

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

const translate = (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key);

/**
 * A service whose every function answers an empty promise, the ones under test supplied,
 * the pure helpers the real ones.
 *
 * @param {Object} overrides the functions under test
 * @returns {Proxy} the service
 */
function serviceStub(overrides) {
  const target = {
    formatScheduledDate: emailConnectorMailBoxService.formatScheduledDate,
    scheduledStateLine: emailConnectorMailBoxService.scheduledStateLine,
    scheduledActions: emailConnectorMailBoxService.scheduledActions,
    scheduledErrorMessage: emailConnectorMailBoxService.scheduledErrorMessage,
    scheduledReaderRow: emailConnectorMailBoxService.scheduledReaderRow,
    folderLabel: emailConnectorMailBoxService.folderLabel,
    folderIcon: emailConnectorMailBoxService.folderIcon,
    formatCount: emailConnectorMailBoxService.formatCount,
    isScheduledView: emailConnectorMailBoxService.isScheduledView,
    ...overrides,
  };
  return new Proxy(target, {
    get(stubbed, name) {
      if (!(name in stubbed)) {
        stubbed[name] = jest.fn(() => Promise.resolve(null));
      }
      return stubbed[name];
    },
  });
}

/**
 * A scheduled mail as GET /scheduled lists it.
 *
 * @param {String} id its draft local id
 * @param {Object} extra further fields
 * @returns {Object} the row
 */
function scheduledRow(id, extra = {}) {
  return {
    draftLocalId: id,
    to: [{ name: 'Bob', address: 'bob@host' }],
    subject: `Subject ${id}`,
    snippet: `Snippet ${id}`,
    scheduledDate: Date.UTC(2026, 9, 1, 6, 0),
    timeZone: 'Europe/Paris',
    status: 'SCHEDULED',
    lastError: null,
    ...extra,
  };
}

/**
 * Mounts the list, its reads answered by the given functions.
 *
 * @param {Object} answers the service's functions under test
 * @param {Object} propsData the list's props
 * @param {Object} modal the reschedule popup's open and close
 * @returns {Promise<Object>} {wrapper, service, emitted}
 */
async function mountList(answers = {}, propsData = {}, modal = { open: jest.fn(), close: jest.fn() }) {
  const service = serviceStub({
    getScheduledEmails: jest.fn(() => Promise.resolve([scheduledRow('d1'), scheduledRow('d2')])),
    ...answers,
  });
  const wrapper = shallowMount(EmailConnectorMailBoxScheduledList, {
    propsData,
    mocks: { $t: translate, $te: () => false, $emailConnectorMailBoxService: service },
    stubs: {
      'exo-confirm-dialog': { template: '<div class="confirm" />', methods: { open: jest.fn() } },
      'exo-modal': { props: { title: String, width: String, hideActions: Boolean }, template: '<div class="modal"><slot /></div>', methods: modal },
    },
  });
  const emitted = [];
  const emit = wrapper.vm.$root.$emit.bind(wrapper.vm.$root);
  wrapper.vm.$root.$emit = (...args) => {
    emitted.push(args);
    return emit(...args);
  };
  await flush();
  return { wrapper, service, emitted };
}

// A row's Vuetify parts as plain elements that still bubble: the item listens natively,
// the button and the menu's items emit their click as Vuetify's do.
const ROW_STUBS = {
  'v-menu': { template: '<div><slot name="activator" :on="{}" :attrs="{}" /><slot /></div>' },
  'v-btn': { template: '<button type="button" v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' },
  'v-list-item': { template: '<div v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></div>' },
};

/**
 * Mounts a row.
 *
 * @param {Object} propsData the row's props
 * @returns {Object} the wrapper
 */
function mountRow(propsData) {
  return shallowMount(EmailConnectorMailBoxScheduledListItem, {
    propsData,
    mocks: { $t: translate, $emailConnectorMailBoxService: emailConnectorMailBoxService },
    stubs: ROW_STUBS,
  });
}

describe('the Scheduled view sits on the pane\'s background and has no loading bar of its own (EXO-90434)', () => {
  it('paints no white surface: its list is transparent, like a folder\'s rows', async () => {
    const { wrapper } = await mountList();
    expect(wrapper.find('v-list').classes()).toContain('transparent');
  });

  it('shows no bar while it reads, and tells the drawer instead', async () => {
    let answerRead;
    const { wrapper } = await mountList({ getScheduledEmails: jest.fn(() => new Promise(resolve => answerRead = resolve)) });
    expect(wrapper.find('v-progress-linear').exists()).toBe(false);
    expect(wrapper.emitted('loading').slice(-1)).toEqual([[true]]);
    answerRead([scheduledRow('d1')]);
    await flush();
    expect(wrapper.emitted('loading').slice(-1)).toEqual([[false]]);
    wrapper.destroy();
    expect(wrapper.emitted('loading').slice(-1)).toEqual([[false]]);
  });

  it('tells the drawer while an action runs on a row, and the row shows no bar, its menu disabled', async () => {
    let answerCancel;
    const { wrapper } = await mountList({ cancelScheduledEmail: jest.fn(() => new Promise(resolve => answerCancel = resolve)) });
    wrapper.vm.cancel(scheduledRow('d1'));
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('loading').slice(-1)).toEqual([[true]]);
    answerCancel();
    await flush();
    expect(wrapper.emitted('loading').slice(-1)).toEqual([[false]]);

    const busy = mountRow({ scheduled: scheduledRow('d1'), busy: true });
    expect(busy.find('v-progress-linear').exists()).toBe(false);
    expect(busy.find('.scheduled-email-menu').attributes('disabled')).toBe('disabled');
  });

  it('feeds the drawer\'s own header bar, the only one', async () => {
    const service = serviceStub({
      getEmailBox: jest.fn(() => Promise.resolve({ emails: [], folders: [], emailSyncStatus: 'SUCCESS' })),
      getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
    });
    const drawerStub = { props: ['loading'], template: '<div><slot name="content" /></div>', methods: { open: jest.fn() } };
    const listStub = { template: '<div />' };
    const wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
      mocks: {
        $t: translate,
        $emailConnectorMailBoxService: service,
        $emailConnectorCommonService: serviceStub({}),
        $vuetify: { breakpoint: {}, rtl: false },
      },
      stubs: {
        'exo-drawer': drawerStub,
        'email-connector-mail-box-scheduled-list': listStub,
      },
    });
    await flush();
    await wrapper.setData({ emailBoxDrawer: true, currentFolder: 'SCHEDULED', loading: false, syncInProgress: false });
    const drawer = wrapper.findComponent(drawerStub);
    expect(drawer.props('loading')).toBe(false);
    wrapper.findComponent(listStub).vm.$emit('loading', true);
    await wrapper.vm.$nextTick();
    expect(drawer.props('loading')).toBe(true);
  });
});

describe('a row of the Scheduled view opens its mail read-only, as a folder row opens its mail (EXO-90434)', () => {
  it('opens on a click, on Enter and on Space, and not from its menu', async () => {
    const row = mountRow({ scheduled: scheduledRow('d1') });
    await row.trigger('click');
    await row.trigger('keydown.enter');
    await row.trigger('keydown.space');
    expect(row.emitted('open')).toEqual([[row.props('scheduled')], [row.props('scheduled')], [row.props('scheduled')]]);

    await row.find('.scheduled-email-menu').trigger('click');
    await row.find('.scheduled-email-menu').trigger('keydown.enter');
    expect(row.emitted('open')).toHaveLength(3);
    expect(row.attributes('tabindex')).toBe('0');
    expect(row.attributes('aria-label')).toBe('emailConnector.mailBox.scheduled.open|Subject d1');
  });

  it('is lit like a folder row: under the pointer, and while the full-screen reader shows it', async () => {
    const row = mountRow({ scheduled: scheduledRow('d1'), expanded: true });
    expect(row.classes()).not.toContain('grey-lighten1-background-opacity-3');
    await row.setProps({ opened: true });
    expect(row.classes()).toContain('grey-lighten1-background-opacity-3');
    expect(row.attributes('aria-current')).toBe('true');
    const narrow = mountRow({ scheduled: scheduledRow('d1') });
    await narrow.trigger('mouseenter');
    expect(narrow.classes()).toContain('light-grey-background-color');
  });

  it('opens beside the list in full screen, in the mail drawer otherwise, on the draft, read-only, with its row', async () => {
    const full = await mountList({}, { compact: true });
    full.wrapper.vm.open(scheduledRow('d1'));
    const [event, row] = full.emitted[full.emitted.length - 1];
    expect(event).toBe('open-email-thread-content');
    expect(row).toEqual(expect.objectContaining({
      draftLocalId: 'd1', subject: 'Subject d1', scheduled: true, scheduledStatus: 'SCHEDULED', read: true,
      scheduledTimeZone: 'Europe/Paris', scheduledRow: scheduledRow('d1'),
    }));
    expect(row.content.body).toBe('Snippet d1');
    expect(full.wrapper.vm.openedId).toBe('d1');

    const narrow = await mountList();
    narrow.wrapper.vm.open(scheduledRow('d2'));
    const [narrowEvent, narrowRow, narrowRows] = narrow.emitted[narrow.emitted.length - 1];
    expect(narrowEvent).toBe('open-email-thread-drawer');
    expect(narrowRows).toEqual([narrowRow]);
  });

  it('escapes the snippet it shows as the body, and reads the conversation when the row names it', () => {
    const row = emailConnectorMailBoxService.scheduledReaderRow(scheduledRow('d1', { snippet: 'a <b> & c', threadId: 't1' }));
    expect(row.content.body).toBe('a &lt;b&gt; &amp; c');
    expect(row.threadId).toBe('t1');
  });

  it('tells the reader what became of the opened mail, and forgets it once it left the view', async () => {
    let rows = [scheduledRow('d1'), scheduledRow('d2')];
    const { wrapper, emitted } = await mountList({ getScheduledEmails: jest.fn(() => Promise.resolve(rows)) }, { compact: true });
    wrapper.vm.open(scheduledRow('d1'));
    rows = [scheduledRow('d1', { status: 'FAILED', lastError: 'NETWORK' }), scheduledRow('d2')];
    await wrapper.vm.reload();
    const updated = emitted.filter(event => event[0] === 'scheduled-email-updated');
    expect(updated[0][1]).toBe('d1');
    expect(updated[0][2].scheduledStatus).toBe('FAILED');

    rows = [scheduledRow('d2')];
    await wrapper.vm.reload();
    expect(emitted.filter(event => event[0] === 'scheduled-email-updated').pop()).toEqual(['scheduled-email-updated', 'd1', null]);
    expect(wrapper.vm.openedId).toBeNull();
  });

  it('runs the actions the reader offers for the opened mail, as its own', async () => {
    const { wrapper, emitted } = await mountList();
    wrapper.vm.$root.$emit('scheduled-email-action', 'edit', scheduledRow('d1'));
    expect(emitted).toContainEqual(['edit-scheduled-email', { draftLocalId: 'd1', scheduledDate: Date.UTC(2026, 9, 1, 6, 0) }]);
  });
});

describe('the opened scheduled mail in the reader (EXO-90434, PO decision (a))', () => {
  const DRAFT = {
    draftLocalId: 'd1',
    to: [{ address: 'bob@host' }],
    content: { body: '<p>See you on Monday, with the slides</p>', attachments: [{ name: 'slides.pdf' }] },
    scheduled: true,
    scheduledDate: Date.UTC(2026, 9, 1, 6, 0),
    scheduledTimeZone: 'Europe/Paris',
    scheduledStatus: 'FAILED',
  };

  /**
   * Mounts the draft strip.
   *
   * @param {Object} propsData its props
   * @returns {Object} the wrapper
   */
  function mountStrip(propsData) {
    return shallowMount(EmailConnectorMailBoxDrawerThreadDraft, {
      propsData,
      mocks: { $t: translate, $emailConnectorMailBoxService: emailConnectorMailBoxService },
      stubs: ROW_STUBS,
    });
  }

  it('shows the whole text, the attachments, when it goes and why it was not sent, with the row\'s actions', async () => {
    const scheduledRowOfIt = scheduledRow('d1', { status: 'FAILED', lastError: 'NETWORK' });
    const strip = mountStrip({ draft: DRAFT, scheduledRow: scheduledRowOfIt });
    expect(strip.find('.scheduled-draft-text').text()).toBe('See you on Monday, with the slides');
    expect(strip.find('.scheduled-draft-attachment').text()).toBe('fa-paperclip slides.pdf');
    expect(strip.find('.scheduled-draft-state').text())
      .toBe('emailConnector.mailBox.scheduled.notSent|emailConnector.mailBox.scheduled.error.NETWORK');
    expect(strip.find('.scheduled-draft-edit').exists()).toBe(false);
    const actions = strip.findAll('.scheduled-draft-action');
    expect(actions.wrappers.map(action => action.attributes('data-action'))).toEqual(['retry', 'edit', 'reschedule', 'moveToDrafts', 'discard']);
    await actions.at(2).trigger('click');
    expect(strip.emitted('action')).toEqual([['reschedule']]);
  });

  it('stays the conversation\'s compact strip, with its lone Edit, when not opened from the view', () => {
    const strip = mountStrip({ draft: DRAFT });
    expect(strip.find('.scheduled-draft-menu').exists()).toBe(false);
    expect(strip.find('.scheduled-draft-edit').exists()).toBe(true);
    expect(strip.find('.scheduled-draft-attachment').exists()).toBe(false);
  });

  it('gives the view\'s row to the opened draft only, and hands its actions to the view', () => {
    const emitted = [];
    const row = scheduledRow('d1');
    const vm = { email: { draftLocalId: 'd1', scheduledRow: row }, $root: { $emit: (...args) => emitted.push(args) } };
    const { scheduledRowOf, onScheduledAction } = EmailConnectorMailBoxDrawerThreadContent.methods;
    expect(scheduledRowOf.call(vm, { draftLocalId: 'd1' })).toBe(row);
    expect(scheduledRowOf.call(vm, { draftLocalId: 'd9' })).toBeNull();
    expect(scheduledRowOf.call({ email: { draftLocalId: 'd1' } }, { draftLocalId: 'd1' })).toBeNull();
    onScheduledAction.call(vm, 'sendNow');
    expect(emitted).toEqual([['scheduled-email-action', 'sendNow', row]]);
  });

  it('follows the mail in the full-screen reader, and lets it go once it left the view', () => {
    const { onScheduledEmailUpdated } = EmailConnectorMailBoxDrawer.methods;
    const opened = { draftLocalId: 'd1', scheduledRow: scheduledRow('d1') };
    const vm = { expanded: true, email: opened, pinnedEmail: true, selectEmailPlaceHolder: false };
    const moved = { draftLocalId: 'd1', scheduledRow: scheduledRow('d1', { status: 'FAILED' }) };
    onScheduledEmailUpdated.call(vm, 'd1', moved);
    expect(vm.email).toBe(moved);
    onScheduledEmailUpdated.call(vm, 'd2', null);
    expect(vm.email).toBe(moved);
    onScheduledEmailUpdated.call(vm, 'd1', null);
    expect(vm).toEqual(expect.objectContaining({ email: null, pinnedEmail: false, selectEmailPlaceHolder: true }));
  });

  it('follows the mail in the mail drawer, which closes once it left the view', () => {
    const { onScheduledEmailUpdated } = EmailConnectorMailBoxDrawerListItemDetail.methods;
    const close = jest.fn();
    const vm = { emailDetailDrawer: true, email: { draftLocalId: 'd1', scheduledRow: scheduledRow('d1') }, emails: [], close };
    const moved = { draftLocalId: 'd1', scheduledRow: scheduledRow('d1') };
    onScheduledEmailUpdated.call(vm, 'd1', moved);
    expect(vm.email).toBe(moved);
    expect(vm.emails).toEqual([moved]);
    onScheduledEmailUpdated.call(vm, 'd1', null);
    expect(close).toHaveBeenCalled();
  });
});

describe('Reschedule is the platform\'s popup, and it applies the new time (EXO-90434)', () => {
  it('opens the shared picker in exo-modal, sized and titled, with the picker\'s check as its one confirm', async () => {
    const modal = { open: jest.fn(), close: jest.fn() };
    const { wrapper } = await mountList({}, {}, modal);
    expect(wrapper.find('v-dialog').exists()).toBe(false);
    wrapper.vm.onAction('reschedule', scheduledRow('d1'));
    await wrapper.vm.$nextTick();
    await wrapper.vm.$nextTick();
    expect(modal.open).toHaveBeenCalled();
    const popup = wrapper.find('.modal');
    expect(popup.vm.$props).toEqual({ title: 'emailConnector.mailBox.scheduled.reschedule.title', width: '460px', hideActions: true });
    const picker = popup.find('email-connector-schedule-picker');
    expect(picker.attributes('value')).toBe(String(Date.UTC(2026, 9, 1, 6, 0)));
  });

  it('PUTs the picked time and closes on success; a refusal says why and leaves the popup open', async () => {
    const modal = { open: jest.fn(), close: jest.fn() };
    let refuse = false;
    const rescheduleEmail = jest.fn((id, date, zone) => (refuse
      ? Promise.reject(Object.assign(new Error('x'), { status: 400, code: 'emailConnector.scheduled.date.tooSoon' }))
      : Promise.resolve(scheduledRow(id, { scheduledDate: date, timeZone: zone }))));
    const { wrapper, emitted } = await mountList({ rescheduleEmail }, {}, modal);
    wrapper.vm.onAction('reschedule', scheduledRow('d1'));
    await wrapper.vm.$nextTick();
    await wrapper.vm.reschedule(Date.UTC(2026, 9, 2, 6, 0), 'UTC');
    expect(rescheduleEmail).toHaveBeenCalledWith('d1', Date.UTC(2026, 9, 2, 6, 0), 'UTC');
    expect(modal.close).toHaveBeenCalledTimes(1);
    expect(emitted.filter(event => event[0] === 'alert-message').pop()[2]).toBe('success');

    refuse = true;
    wrapper.vm.onAction('reschedule', scheduledRow('d1'));
    await wrapper.vm.reschedule(Date.UTC(2026, 9, 2, 6, 0), 'UTC');
    expect(modal.close).toHaveBeenCalledTimes(1);
    expect(emitted.filter(event => event[0] === 'alert-message').pop()[2]).toBe('error');
  });

  it('forgets the mail once the popup closed, so the next opening starts afresh', async () => {
    const { wrapper } = await mountList();
    wrapper.vm.onAction('reschedule', scheduledRow('d1'));
    await wrapper.vm.$nextTick();
    wrapper.find('.modal').vm.$emit('dialog-closed');
    expect(wrapper.vm.rescheduled).toBeNull();
  });
});
