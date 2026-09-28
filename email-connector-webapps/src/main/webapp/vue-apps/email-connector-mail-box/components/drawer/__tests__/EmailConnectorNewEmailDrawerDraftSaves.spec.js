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

// The composer's own draft saves (EXO-89337): the autosave, the closing save and the
// Discard button all read `hasContent`, which the drafts work left undefined when it
// renamed nothing -- so none of them ever ran. Found by EXO-90435, whose read-receipt
// choice rides those saves.

import Vue from 'vue';
import { mount } from '@vue/test-utils';
import EmailConnectorNewEmailDrawer from '../EmailConnectorNewEmailDrawer.vue';
import * as scheduledSendService from '../../../js/EmailConnectorScheduledSendService.js';

Vue.config.ignoredElements.push(/^email-connector-/, 'exo-drawer', 'rich-editor', 'exo-confirm-dialog', 'date-picker', 'time-picker');

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

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

describe('the composer\'s draft saves (EXO-89337)', () => {
  it('knows when it holds something: the Discard button shows, and a pause saves the draft', async () => {
    const { wrapper, service } = mountComposer(false);
    await wrapper.vm.open(null, false, false, null);
    await flush();
    expect(wrapper.vm.hasContent).toBe(false);
    await openAndFill(wrapper);
    expect(wrapper.vm.hasContent).toBe(true);
    expect(wrapper.findAll('button').filter(button => button.text() === 'emailConnector.mailBox.newEmail.drawer.discard.label').length).toBe(1);

    wrapper.vm.saveDraft(false);
    await flush();
    expect(service.saveDraft).toHaveBeenCalledTimes(1);
    expect(service.saveDraft.mock.calls[0][0].subject).toBe('Hello');
  });

  it('stores what is on screen when the drawer closes', async () => {
    const { wrapper, service, emitted } = mountComposer(false);
    await openAndFill(wrapper);
    wrapper.vm.close();
    await flush();
    expect(service.saveDraft).toHaveBeenCalledTimes(1);
    expect(service.saveDraft.mock.calls[0][0].content.body).toBe('<p>Hi</p>');
    expect(emitted.map(event => event[0])).toContain('refresh-email-box');
  });

  it('saves nothing for an empty composer', async () => {
    const { wrapper, service } = mountComposer(false);
    await wrapper.vm.open(null, false, false, null);
    await flush();
    wrapper.vm.saveDraft(false);
    wrapper.vm.close();
    await flush();
    expect(service.saveDraft).not.toHaveBeenCalled();
  });
});
