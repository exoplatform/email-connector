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

// EXO-90435 -- read receipts, the composer's half: "Request a read receipt" in the
// More options (⋮) menu before Send, its chip, the user's default, and the choice
// carried by every draft save, send and schedule, and read back when a draft is resumed
// or a scheduled mail edited. The server reads a missing value as "no", so every
// payload must carry it.

import Vue from 'vue';
import { mount } from '@vue/test-utils';
import EmailConnectorNewEmailDrawer from '../EmailConnectorNewEmailDrawer.vue';
import * as scheduledSendService from '../../../js/EmailConnectorScheduledSendService.js';

Vue.config.ignoredElements.push(/^email-connector-/, 'exo-drawer', 'rich-editor', 'exo-confirm-dialog', 'date-picker', 'time-picker');

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The translation stand-in: the key, then its parameters.
 *
 * @param {String} key the key
 * @param {Object} params its parameters
 * @returns {String} the "translation"
 */
const translate = (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key);

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
 * A stand-in for exo-drawer that renders its named slots.
 *
 * @returns {Object} the component
 */
function exoDrawerStub() {
  return {
    name: 'exo-drawer',
    props: { value: { type: Boolean, default: false } },
    methods: { open: jest.fn(), close: jest.fn() },
    render(createElement) {
      return createElement('div', Object.keys(this.$slots).map(name => createElement('div', { attrs: { 'data-slot': name } }, this.$slots[name])));
    },
  };
}

// Vuetify's menu, rendering its activator and its content.
const V_MENU = {
  props: { value: { type: Boolean, default: false } },
  render(createElement) {
    const activator = this.$scopedSlots.activator ? this.$scopedSlots.activator({ on: {}, attrs: {} }) : [];
    return createElement('div', { class: 'v-menu-stub' }, [...(activator || []), ...(this.$slots.default || [])]);
  },
};

const V_BTN = { template: '<button type="button" v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' };
const V_LIST_ITEM = { template: '<div v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></div>' };
const V_CHIP = { template: '<span class="v-chip-stub" v-bind="$attrs"><slot /><button type="button" class="chip-close" @click="$emit(\'click:close\')" /></span>' };

/**
 * Mounts the composer, closed, with the user's read-receipt default.
 *
 * @param {Boolean} requestByDefault the user's "request a read receipt by default"
 * @param {Object} answers the mailbox service's functions under test
 * @returns {Object} {wrapper, service, common, emitted}
 */
function mountComposer(requestByDefault = false, answers = {}) {
  const service = serviceStub({
    formatScheduledDate: jest.fn(date => `date:${date}`),
    scheduledErrorMessage: scheduledSendService.scheduledErrorMessage,
    saveDraft: jest.fn(draft => Promise.resolve({ draftLocalId: 'draft-1', draftRevision: draft.draftRevision, draftState: 'LOCAL_ONLY' })),
    scheduleDraft: jest.fn((id, draft, scheduledDate, timeZone) => Promise.resolve({ draftLocalId: id, scheduledDate, timeZone, status: 'SCHEDULED' })),
    sendEmail: jest.fn(() => Promise.resolve()),
    sendDraft: jest.fn(() => Promise.resolve()),
    ...answers,
  });
  const common = serviceStub({
    getEmailSignature: jest.fn(() => Promise.resolve(null)),
    getReadReceiptSettings: jest.fn(() => Promise.resolve({ requestByDefault, responsePolicy: 'ASK', alwaysAllowed: true })),
  });
  const wrapper = mount(EmailConnectorNewEmailDrawer, {
    mocks: {
      $t: translate,
      $te: () => false,
      $emailConnectorMailBoxService: service,
      $emailConnectorCommonService: common,
      $uploadService: serviceStub({}),
    },
    stubs: {
      'exo-drawer': exoDrawerStub(),
      'v-menu': V_MENU,
      'v-btn': V_BTN,
      'v-list-item': V_LIST_ITEM,
      'v-chip': V_CHIP,
      'email-connector-recipient-field': true,
      'email-connector-new-email-drawer-attachments': true,
      'email-connector-schedule-picker': true,
    },
  });
  const emitted = [];
  const emit = wrapper.vm.$root.$emit.bind(wrapper.vm.$root);
  wrapper.vm.$root.$emit = (...args) => {
    emitted.push(args);
    return emit(...args);
  };
  return { wrapper, service, common, emitted };
}

/**
 * Opens a new mail and fills it: a recipient, a subject and a body.
 *
 * @param {Object} wrapper the composer
 * @returns {Promise<void>} resolved once open and filled
 */
async function openAndFill(wrapper) {
  await wrapper.vm.open(null, false, false, null);
  await flush();
  await wrapper.setData({
    to: [{ name: 'Bob', address: 'bob@host' }],
    email: { mailHeaderId: null, to: [], cc: [], bcc: [], subject: 'Hello', content: { body: '<p>Hi</p>' } },
  });
}

describe('the composer\'s read receipt (EXO-90435)', () => {
  beforeEach(() => jest.useRealTimers());

  it('opens on the user\'s default, off or on, and the chip says so', async () => {
    const off = mountComposer(false);
    await off.wrapper.vm.open(null, false, false, null);
    await flush();
    expect(off.wrapper.vm.readReceiptRequested).toBe(false);
    expect(off.wrapper.find('.read-receipt-chip').exists()).toBe(false);

    const on = mountComposer(true);
    await on.wrapper.vm.open(null, false, false, null);
    await flush();
    expect(on.wrapper.vm.readReceiptRequested).toBe(true);
    expect(on.wrapper.find('.read-receipt-chip').exists()).toBe(true);
    expect(on.common.getReadReceiptSettings).toHaveBeenCalledTimes(1);
  });

  it('opens with no request when the preference cannot be read', async () => {
    const { wrapper, common } = mountComposer(true);
    common.getReadReceiptSettings.mockImplementation(() => Promise.reject(new Error('down')));
    await wrapper.vm.open(null, false, false, null);
    await flush();
    expect(wrapper.vm.readReceiptRequested).toBe(false);
  });

  it('is a checkable entry of More options (⋮) before Send, and the chip removes it', async () => {
    const { wrapper } = mountComposer(false);
    await openAndFill(wrapper);
    const more = wrapper.find('.composer-more-options');
    expect(more.exists()).toBe(true);
    expect(more.attributes('aria-label')).toBe('emailConnector.mailBox.newEmail.drawer.moreOptions');
    const toggle = wrapper.find('.read-receipt-toggle');
    expect(toggle.attributes('role')).toBe('menuitemcheckbox');
    expect(toggle.attributes('aria-checked')).toBe('false');

    await toggle.trigger('click');
    expect(wrapper.vm.readReceiptRequested).toBe(true);
    expect(wrapper.find('.read-receipt-toggle').attributes('aria-checked')).toBe('true');
    expect(wrapper.find('.read-receipt-chip').exists()).toBe(true);

    await wrapper.find('.read-receipt-chip .chip-close').trigger('click');
    expect(wrapper.vm.readReceiptRequested).toBe(false);
    expect(wrapper.find('.read-receipt-chip').exists()).toBe(false);
  });

  it('carries the choice on every draft save, off included, and a change of it is saved', async () => {
    const { wrapper, service } = mountComposer(false);
    await openAndFill(wrapper);
    wrapper.vm.saveDraft(false);
    await flush();
    expect(service.saveDraft.mock.calls[0][0].readReceiptRequested).toBe(false);

    await wrapper.setData({ readReceiptRequested: true });
    // A changed choice is a change of the draft: the next save is not skipped.
    wrapper.vm.saveDraft(false);
    await flush();
    expect(service.saveDraft).toHaveBeenCalledTimes(2);
    expect(service.saveDraft.mock.calls[1][0].readReceiptRequested).toBe(true);
  });

  it('carries it on a send, with and without a draft behind the composer', async () => {
    const first = mountComposer(true);
    await openAndFill(first.wrapper);
    first.wrapper.vm.sendEmail();
    await flush();
    expect(first.service.sendEmail.mock.calls[0][0].readReceiptRequested).toBe(true);

    const second = mountComposer(false);
    await openAndFill(second.wrapper);
    second.wrapper.vm.draftSession.localId = 'draft-3';
    second.wrapper.vm.sendEmail();
    await flush();
    expect(second.service.sendDraft.mock.calls[0][0]).toBe('draft-3');
    expect(second.service.sendDraft.mock.calls[0][1].readReceiptRequested).toBe(false);
  });

  it('carries it through the no-subject question, whose answer hands the payload back', async () => {
    const { wrapper, service, emitted } = mountComposer(false);
    await openAndFill(wrapper);
    await wrapper.setData({ readReceiptRequested: true, email: { ...wrapper.vm.email, subject: '' } });
    wrapper.vm.sendEmail();
    const asked = emitted.find(event => event[0] === 'open-no-subject-email-confirm-popup');
    expect(asked[1].readReceiptRequested).toBe(true);

    wrapper.vm.sendEmail(asked[1]);
    await flush();
    expect(service.sendEmail.mock.calls[0][0].readReceiptRequested).toBe(true);
  });

  it('sends the composer\'s choice whatever payload is handed back to it', async () => {
    const { wrapper, service } = mountComposer(true);
    await openAndFill(wrapper);
    // A payload built elsewhere (the send-email event) that says nothing about it.
    wrapper.vm.sendEmail({ to: [{ address: 'bob@host' }], cc: [], bcc: [], subject: 'S', content: { body: 'b' } });
    await flush();
    expect(service.sendEmail.mock.calls[0][0].readReceiptRequested).toBe(true);
  });

  it('carries it on a schedule', async () => {
    const { wrapper, service } = mountComposer(true);
    await openAndFill(wrapper);
    await wrapper.vm.scheduleEmail(Date.now() + DAY_MS, 'UTC');
    expect(service.scheduleDraft).toHaveBeenCalledTimes(1);
    expect(service.scheduleDraft.mock.calls[0][1].readReceiptRequested).toBe(true);
    // The draft the schedule freezes was created with it too.
    expect(service.saveDraft.mock.calls[0][0].readReceiptRequested).toBe(true);
  });

  it('reads the draft\'s own choice back when it is resumed, never the default', async () => {
    const { wrapper, common } = mountComposer(true);
    wrapper.vm.resume({ draftLocalId: 'draft-5', to: [], cc: [], bcc: [], subject: 'S', content: { body: 'b' }, readReceiptRequested: false });
    expect(wrapper.vm.readReceiptRequested).toBe(false);
    expect(common.getReadReceiptSettings).not.toHaveBeenCalled();

    wrapper.vm.resume({ draftLocalId: 'draft-6', to: [], cc: [], bcc: [], subject: 'S', content: { body: 'b' }, readReceiptRequested: true });
    expect(wrapper.vm.readReceiptRequested).toBe(true);
    await wrapper.vm.$nextTick();
    // Saved again under its own id, still asking.
    wrapper.vm.saveDraft(true);
    await flush();
    const saved = wrapper.vm.$emailConnectorMailBoxService.saveDraft.mock.calls[0][0];
    expect([saved.draftLocalId, saved.readReceiptRequested]).toEqual(['draft-6', true]);
  });

  it('keeps a scheduled mail\'s choice when it is edited again, and schedules it with it', async () => {
    const { wrapper, service } = mountComposer(false, {
      cancelScheduledEmail: jest.fn(() => Promise.resolve()),
      getEmailBox: jest.fn(() => Promise.resolve({ emails: [{
        draftLocalId: 'draft-7', to: [{ address: 'bob@host' }], cc: [], bcc: [], subject: 'S', content: { body: 'b' },
        readReceiptRequested: true,
      }] })),
    });
    await wrapper.vm.editScheduledEmail({ draftLocalId: 'draft-7', scheduledDate: Date.now() + DAY_MS });
    expect(wrapper.vm.readReceiptRequested).toBe(true);
    expect(wrapper.find('.read-receipt-chip').exists()).toBe(true);

    await wrapper.vm.scheduleEmail(Date.now() + DAY_MS, 'UTC');
    expect(service.scheduleDraft.mock.calls[0][0]).toBe('draft-7');
    expect(service.scheduleDraft.mock.calls[0][1].readReceiptRequested).toBe(true);
  });

  it('forgets the choice when the composer closes, after the closing save stored it', async () => {
    const { wrapper, service } = mountComposer(false);
    await openAndFill(wrapper);
    await wrapper.setData({ readReceiptRequested: true });
    wrapper.vm.close();
    await flush();
    expect(service.saveDraft.mock.calls[0][0].readReceiptRequested).toBe(true);
    expect(wrapper.vm.readReceiptRequested).toBe(false);
  });
});
