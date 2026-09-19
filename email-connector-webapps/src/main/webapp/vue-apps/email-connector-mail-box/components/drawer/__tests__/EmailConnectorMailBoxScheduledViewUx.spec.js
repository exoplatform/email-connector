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
import EmailConnectorMailBoxDrawerListItemDetailContent from '../EmailConnectorMailBoxDrawerListItemDetailContent.vue';
import EmailConnectorMailBoxPopup from '../EmailConnectorMailBoxPopup.vue';
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

// The schedule picker as the popup drives it: its check left out, its validity told,
// confirm() handing the picked instant over.
const PICKER = {
  props: { value: Number, hideConfirm: Boolean },
  template: '<div class="picker" />',
  mounted() {
    this.$emit('valid', true);
  },
  methods: {
    confirm() {
      this.$emit('confirm', Date.UTC(2026, 9, 3, 6, 0), 'UTC');
    },
  },
};

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
      'email-connector-mail-box-popup': {
        props: { title: String, okLabel: String, cancelLabel: String, okDisabled: Boolean, width: String },
        template: '<div class="modal"><slot /></div>',
        methods: modal,
      },
      'email-connector-schedule-picker': PICKER,
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

  // The message's parts, stubbed to read what they are given.
  const BODY = { props: ['emailBody', 'htmlBody'], template: '<div />' };
  const ATTACHMENTS = { props: ['emailAttachments'], template: '<div />' };
  const AVATAR = { props: ['email'], template: '<div />' };

  /**
   * Mounts the message renderer the conversation renders a scheduled draft with.
   *
   * @param {Object} propsData its props
   * @returns {Object} the wrapper
   */
  function mountMessage(propsData) {
    return shallowMount(EmailConnectorMailBoxDrawerListItemDetailContent, {
      propsData: { hideSubject: true, ...propsData },
      mocks: { $t: translate, $emailConnectorMailBoxService: emailConnectorMailBoxService, $vuetify: { breakpoint: {} } },
      stubs: {
        ...ROW_STUBS,
        'email-connector-mail-box-drawer-list-item-detail-body': BODY,
        'email-connector-mail-box-drawer-list-item-detail-attachments': ATTACHMENTS,
        'email-connector-mail-box-drawer-list-item-detail-sender-avatar': AVATAR,
      },
    });
  }

  it('is a message: the user as sender, its recipients, its whole body and its attachments as any mail\'s', () => {
    window.eXo = { env: { portal: { userName: 'root' } } };
    const message = mountMessage({ email: { ...DRAFT, sender: { name: 'Root Root', address: 'root@host' } } });
    expect(message.text()).toContain('Root Root');
    expect(message.text()).toContain('emailConnector.mailBox.list.drawer.detail.to bob@host');
    expect(message.findComponent(BODY).props('emailBody')).toBe(DRAFT.content.body);
    expect(message.findComponent(ATTACHMENTS).props('emailAttachments')).toEqual([{ name: 'slides.pdf' }]);
    // The user's own platform avatar: a draft's sender carries no picture.
    expect(message.findComponent(AVATAR).props('email').sender.avatarUrl).toBe('/portal/rest/v1/social/users/root/avatar');
    // Read-only, said in one slim line with its Edit; no dashed box, no centring.
    expect(message.find('.scheduled-mail-read-only').text()).toContain('emailConnector.mailBox.scheduled.readOnly');
    expect(message.html()).not.toContain('dashed');
    expect(message.html()).not.toContain('text-center');
    delete window.eXo;
  });

  it('says "Me" and cannot fail on a row that carries only what the Scheduled view lists', () => {
    const row = emailConnectorMailBoxService.scheduledReaderRow(scheduledRow('d1'));
    const message = mountMessage({ email: row, scheduledRow: row.scheduledRow });
    expect(message.text()).toContain('emailConnector.mailBox.list.drawer.detail.me');
    expect(message.text()).toContain('emailConnector.mailBox.list.drawer.detail.to Bob');
  });

  it('says when it goes in place of the date, in its state\'s colour, why it was not sent, and offers the row\'s actions in its ⋮', async () => {
    const message = mountMessage({ email: DRAFT, scheduledRow: scheduledRow('d1', { status: 'FAILED', lastError: 'NETWORK' }) });
    const date = message.find('.scheduled-mail-date');
    expect(date.text()).toContain('emailConnector.mailBox.scheduled.at|');
    expect(date.classes()).toContain('error--text');
    expect(message.find('.scheduled-mail-state').text())
      .toBe('emailConnector.mailBox.scheduled.notSent|emailConnector.mailBox.scheduled.error.NETWORK');
    const actions = message.findAll('.scheduled-mail-action');
    expect(actions.wrappers.map(action => action.attributes('data-action'))).toEqual(['retry', 'edit', 'reschedule', 'moveToDrafts', 'discard']);
    await actions.at(2).trigger('click');
    expect(message.emitted('scheduled-action')).toEqual([['reschedule']]);
    expect(message.emitted('edit')).toBeFalsy();
  });

  it('offers only its Edit in a conversation opened elsewhere, where the view does not run the others', () => {
    const message = mountMessage({ email: DRAFT });
    expect(message.find('.scheduled-mail-menu').exists()).toBe(false);
    expect(message.find('.scheduled-mail-edit').exists()).toBe(true);
  });

  it('renders a scheduled draft of a conversation as its messages, an unsent one as its strip, and no category bar over a draft alone', async () => {
    const detailStub = { props: { email: Object, scheduledRow: Object, hideSubject: Boolean }, template: '<div class="detail-stub" />' };
    const draftStub = { props: ['draft'], template: '<div class="draft-stub" />' };
    const categoryStub = { props: ['emails'], template: '<div class="category-stub" />' };
    const row = emailConnectorMailBoxService.scheduledReaderRow(scheduledRow('d1', { threadId: 't1' }));
    const mountReader = conversation => shallowMount(EmailConnectorMailBoxDrawerThreadContent, {
      propsData: { email: row, emails: [] },
      mocks: {
        $t: translate,
        $emailConnectorMailBoxService: serviceStub({
          getThreadByThreadId: jest.fn(() => Promise.resolve(conversation)),
          completeThreadByThreadId: jest.fn(() => Promise.resolve(null)),
          isListingRow: emailConnectorMailBoxService.isListingRow,
          formatDateString: () => 'date',
        }),
      },
      stubs: {
        'email-connector-mail-box-drawer-list-item-detail-content': detailStub,
        'email-connector-mail-box-drawer-thread-draft': draftStub,
        'email-connector-mail-box-drawer-category-bar': categoryStub,
        'email-connector-mail-box-drawer-thread-message': true,
      },
    });
    const alone = mountReader([{ ...DRAFT, threadId: 't1', receivedDate: 1 }]);
    await flush();
    const detail = alone.findComponent(detailStub);
    expect(detail.props('email').content.body).toBe(DRAFT.content.body);
    expect(detail.props('scheduledRow')).toBe(row.scheduledRow);
    expect(detail.props('hideSubject')).toBe(true);
    expect(alone.findComponent(draftStub).exists()).toBe(false);
    expect(alone.findComponent(categoryStub).exists()).toBe(false);

    const withMail = mountReader([
      { mailRemoteId: 7, folder: 'INBOX', threadId: 't1', receivedDate: 1, to: [], sender: { name: 'Bob' }, content: { body: 'x' } },
      { ...DRAFT, threadId: 't1', receivedDate: 2 },
      { draftLocalId: 'd2', threadId: 't1', receivedDate: 3, to: [], content: { body: 'wip' } },
    ]);
    await flush();
    expect(withMail.findAllComponents(detailStub).length).toBe(1);
    expect(withMail.findAllComponents(draftStub).length).toBe(1);
    expect(withMail.findComponent(categoryStub).props('emails').map(email => email.mailRemoteId)).toEqual([7]);
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
  it('opens the shared picker in the standard popup, titled, with Reschedule and Cancel, the picker\'s check left out', async () => {
    const modal = { open: jest.fn(), close: jest.fn() };
    const rescheduleEmail = jest.fn((id, date, zone) => Promise.resolve(scheduledRow(id, { scheduledDate: date, timeZone: zone })));
    const { wrapper } = await mountList({ rescheduleEmail }, {}, modal);
    expect(wrapper.find('v-dialog').exists()).toBe(false);
    wrapper.vm.onAction('reschedule', scheduledRow('d1'));
    await wrapper.vm.$nextTick();
    await wrapper.vm.$nextTick();
    expect(modal.open).toHaveBeenCalled();
    const popup = wrapper.findComponent('.modal');
    expect(popup.props()).toEqual({
      title: 'emailConnector.mailBox.scheduled.reschedule.title',
      okLabel: 'emailConnector.mailBox.scheduled.action.reschedule',
      cancelLabel: 'emailConnector.mailBox.scheduled.reschedule.cancel',
      okDisabled: false,
      width: '460px',
    });
    const picker = wrapper.findComponent(PICKER);
    expect(picker.props()).toEqual({ value: Date.UTC(2026, 9, 1, 6, 0), hideConfirm: true });

    // No instant picked: Reschedule waits.
    picker.vm.$emit('valid', false);
    await wrapper.vm.$nextTick();
    expect(popup.props('okDisabled')).toBe(true);
    picker.vm.$emit('valid', true);

    // The popup's Reschedule is the one confirm: it has the picker hand its instant over.
    popup.vm.$emit('ok');
    await flush();
    expect(rescheduleEmail).toHaveBeenCalledWith('d1', Date.UTC(2026, 9, 3, 6, 0), 'UTC');
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

describe('every action of both menus -- the row\'s and the reader\'s -- runs its own handler (EXO-90434)', () => {
  // What each action must do, and in which state the menus offer it.
  const CASES = [
    { action: 'edit', status: 'SCHEDULED', event: 'edit-scheduled-email' },
    { action: 'reschedule', status: 'SCHEDULED', modal: true },
    { action: 'sendNow', status: 'SCHEDULED', confirmTitle: 'emailConnector.mailBox.scheduled.sendNow.confirm.title', call: 'sendScheduledEmailNow' },
    { action: 'retry', status: 'FAILED', confirmTitle: 'emailConnector.mailBox.scheduled.sendNow.confirm.title', call: 'sendScheduledEmailNow' },
    { action: 'sendAgain', status: 'UNCERTAIN', confirmTitle: 'emailConnector.mailBox.scheduled.sendNow.confirm.title', call: 'sendScheduledEmailNow' },
    { action: 'cancel', status: 'SCHEDULED', confirmTitle: 'emailConnector.mailBox.scheduled.cancel.confirm.title', call: 'cancelScheduledEmail' },
    { action: 'moveToDrafts', status: 'FAILED', confirmTitle: 'emailConnector.mailBox.scheduled.cancel.confirm.title', call: 'cancelScheduledEmail' },
    { action: 'discard', status: 'SCHEDULED', confirmTitle: 'emailConnector.mailBox.scheduled.discard.confirm.title', call: 'deleteDraft' },
  ];
  const CALLS = ['sendScheduledEmailNow', 'cancelScheduledEmail', 'deleteDraft', 'rescheduleEmail'];

  /**
   * Mounts the Scheduled view with its real rows, their menus rendered in place.
   *
   * @param {Object} row the one scheduled mail
   * @returns {Promise<Object>} {wrapper, service, emitted, modal, confirm}
   */
  async function mountView(row) {
    const modal = { open: jest.fn(), close: jest.fn() };
    const confirm = { open: jest.fn() };
    const answers = { getScheduledEmails: jest.fn(() => Promise.resolve([row])) };
    CALLS.forEach(name => answers[name] = jest.fn(() => Promise.resolve(null)));
    const service = serviceStub(answers);
    const wrapper = shallowMount(EmailConnectorMailBoxScheduledList, {
      mocks: { $t: translate, $te: () => false, $emailConnectorMailBoxService: service },
      stubs: {
        ...ROW_STUBS,
        'email-connector-mail-box-scheduled-list-item': EmailConnectorMailBoxScheduledListItem,
        'exo-confirm-dialog': { props: ['title'], template: '<div class="confirm" />', methods: confirm },
        'email-connector-mail-box-popup': { template: '<div class="modal"><slot /></div>', methods: modal },
      },
    });
    const emitted = [];
    const emit = wrapper.vm.$root.$emit.bind(wrapper.vm.$root);
    wrapper.vm.$root.$emit = (...args) => {
      emitted.push(args);
      return emit(...args);
    };
    await flush();
    return { wrapper, service, emitted, modal, confirm };
  }

  /**
   * Checks what one action did: its own effect, and none of the others'.
   *
   * @param {Object} testCase the expected effect
   * @param {Object} mounted the mounted view
   * @returns {Promise<void>} resolved once checked
   */
  async function expectHandled(testCase, { wrapper, service, emitted, modal, confirm }) {
    await wrapper.vm.$nextTick();
    await wrapper.vm.$nextTick();
    const events = emitted.map(event => event[0]);
    expect(events.includes('edit-scheduled-email')).toBe(testCase.event === 'edit-scheduled-email');
    expect(modal.open.mock.calls.length).toBe(testCase.modal ? 1 : 0);
    expect(confirm.open.mock.calls.length).toBe(testCase.confirmTitle ? 1 : 0);
    if (testCase.confirmTitle) {
      expect(wrapper.find('.confirm').props('title')).toBe(testCase.confirmTitle);
      wrapper.vm.runConfirmed();
      await flush();
    }
    CALLS.forEach(name => expect([name, service[name].mock.calls.length]).toEqual([name, name === testCase.call ? 1 : 0]));
  }

  CASES.forEach(testCase => {
    it(`runs "${testCase.action}" from the row's menu`, async () => {
      const row = scheduledRow('d1', { status: testCase.status });
      const mounted = await mountView(row);
      await mounted.wrapper.find(`.scheduled-email-action[data-action="${testCase.action}"]`).trigger('click');
      await expectHandled(testCase, mounted);
    });

    it(`runs "${testCase.action}" from the reader's menu`, async () => {
      const row = scheduledRow('d1', { status: testCase.status });
      const mounted = await mountView(row);
      const reader = { email: emailConnectorMailBoxService.scheduledReaderRow(row), $root: mounted.wrapper.vm.$root };
      const message = shallowMount(EmailConnectorMailBoxDrawerListItemDetailContent, {
        propsData: { email: reader.email, scheduledRow: row, hideSubject: true },
        mocks: { $t: translate, $emailConnectorMailBoxService: emailConnectorMailBoxService, $vuetify: { breakpoint: {} } },
        stubs: ROW_STUBS,
      });
      // The conversation relays the message's choice (ThreadContent's @scheduled-action).
      message.vm.$on('scheduled-action', action => EmailConnectorMailBoxDrawerThreadContent.methods.onScheduledAction.call(reader, action));
      await message.find(`.scheduled-mail-action[data-action="${testCase.action}"]`).trigger('click');
      await expectHandled(testCase, mounted);
    });
  });
});

describe('the add-on\'s standard popup mirrors exo-confirm-dialog, with a slot (EXO-90434)', () => {
  const DIALOG = { props: ['value', 'contentClass', 'width'], template: '<div class="dialog"><slot /></div>' };

  /**
   * Mounts the popup.
   *
   * @param {Object} propsData its props
   * @returns {Object} the wrapper
   */
  function mountPopup(propsData = {}) {
    return shallowMount(EmailConnectorMailBoxPopup, {
      propsData: { title: 'Reschedule', okLabel: 'Reschedule', cancelLabel: 'Cancel', ...propsData },
      slots: { default: '<div class="slotted" />' },
      stubs: {
        'v-dialog': DIALOG,
        'v-card': { template: '<div class="card" v-bind="$attrs"><slot /></div>' },
        'v-card-text': { template: '<div><slot /></div>' },
        'v-card-actions': { template: '<div><slot /></div>' },
      },
    });
  }

  it('draws exo-confirm-dialog\'s popup: uiPopup, the branding layout, a transparent card, its header, its buttons', () => {
    const popup = mountPopup();
    expect(popup.findComponent(DIALOG).props('contentClass')).toBe('uiPopup layout-drawer');
    expect(popup.find('.card').classes()).toEqual(['card', 'elevation-12', 'transparent']);
    expect(popup.find('.popupHeader').classes()).toEqual(expect.arrayContaining(['ignore-vuetify-classes', 'ClearFix', 'layout-drawer']));
    expect(popup.find('.popupHeader .text-title').text()).toBe('Reschedule');
    expect(popup.find('.uiIconClose').exists()).toBe(true);
    expect(popup.find('.slotted').exists()).toBe(true);
    expect(popup.find('.popup-ok').classes()).toEqual(expect.arrayContaining(['btn', 'btn-primary']));
    expect(popup.find('.popup-cancel').classes()).toEqual(expect.arrayContaining(['btn']));
    expect(mountPopup({ isBrandingLayout: false }).findComponent(DIALOG).props('contentClass')).toBe('uiPopup ');
  });

  it('says OK and stays open for the caller to close; Cancel closes it and tells the platform', async () => {
    const events = [];
    const listener = () => events.push('modalClosed');
    document.addEventListener('modalClosed', listener);
    const popup = mountPopup();
    popup.vm.open();
    await popup.vm.$nextTick();
    await popup.find('.popup-ok').trigger('click');
    expect(popup.emitted('ok')).toHaveLength(1);
    expect(popup.vm.dialog).toBe(true);
    await popup.find('.popup-cancel').trigger('click');
    await flush();
    expect(popup.vm.dialog).toBe(false);
    expect(popup.emitted('dialog-closed')).toHaveLength(1);
    expect(events).toEqual(['modalClosed']);
    document.removeEventListener('modalClosed', listener);
  });

  it('keeps OK disabled while its content is not valid, or while it works', () => {
    expect(mountPopup({ okDisabled: true }).find('.popup-ok').attributes('disabled')).toBe('disabled');
    expect(mountPopup({ loading: true }).find('.popup-ok').attributes('disabled')).toBe('disabled');
    expect(mountPopup().find('.popup-ok').attributes('disabled')).toBeUndefined();
  });
});
