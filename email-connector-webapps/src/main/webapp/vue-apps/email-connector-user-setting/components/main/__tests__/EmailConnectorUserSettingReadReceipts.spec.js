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

// EXO-90435 -- read receipts, the settings: "Request a read receipt by default" and
// "When someone asks for a read receipt: Ask me / Never send / Always send", the last
// one hidden while the administrator does not allow it. EXO-90872: a one-line row with
// the settings' usual edit action, opening a drawer that stores the choices on Save.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorUserSettingReadReceipts from '../EmailConnectorUserSettingReadReceipts.vue';
import EmailConnectorUserSettingReadReceiptsDrawer from '../../drawer/read-receipts/EmailConnectorUserSettingReadReceiptsDrawer.vue';
import * as commonService from '../../../../email-connector-common/js/EmailConnectorCommonService.js';

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

// exo-drawer renders its slots, so the drawer's content and footer are on screen.
const ExoDrawerStub = {
  template: '<div class="exo-drawer"><slot name="title" /><slot name="content" /><slot name="footer" /></div>',
};

/**
 * The service as the server answers it.
 *
 * @param {Object} stored what the server answers
 * @param {Function} save what a save does
 * @returns {Object} the mocked service
 */
const serviceOver = (stored, save = jest.fn(settings => Promise.resolve({ ...settings, alwaysAllowed: stored.alwaysAllowed }))) => ({
  getReadReceiptSettings: jest.fn(() => Promise.resolve(stored)),
  saveReadReceiptSettings: save,
});

/**
 * Mounts a component over a mocked service, and records what it emits on the root.
 *
 * @param {Object} component the component
 * @param {Object} common the mocked service
 * @returns {Promise<Object>} {wrapper, emitted}
 */
async function mountOver(component, common) {
  const wrapper = shallowMount(component, {
    mocks: { $t: key => key, $emailConnectorCommonService: common },
    stubs: { 'exo-drawer': ExoDrawerStub },
  });
  const emitted = [];
  const emit = wrapper.vm.$root.$emit.bind(wrapper.vm.$root);
  wrapper.vm.$root.$emit = (...args) => {
    emitted.push(args);
    return emit(...args);
  };
  await flush();
  return { wrapper, emitted };
}

/**
 * Opens the drawer over stored preferences, as the row's edit action does.
 *
 * @param {Object} stored what the server answers
 * @param {Function} save what a save does
 * @returns {Promise<Object>} {wrapper, common, emitted}
 */
async function openDrawer(stored, save) {
  const common = serviceOver(stored, save);
  const { wrapper, emitted } = await mountOver(EmailConnectorUserSettingReadReceiptsDrawer, common);
  wrapper.vm.$root.$emit('open-email-read-receipts-drawer');
  await flush();
  return { wrapper, common, emitted };
}

/**
 * The values of the radios on screen.
 *
 * @param {Object} wrapper the drawer
 * @returns {Array<String>} the policies offered
 */
const offered = wrapper => wrapper.findAll('v-radio').wrappers.map(radio => radio.attributes('value'));

describe('the read-receipt settings row (EXO-90435, EXO-90872)', () => {
  it('summarises the stored preferences on one line', async () => {
    const { wrapper } = await mountOver(EmailConnectorUserSettingReadReceipts,
      serviceOver({ requestByDefault: true, responsePolicy: 'NEVER', alwaysAllowed: false }));
    expect(wrapper.vm.summary).toBe('UserSettings.emailConnector.readReceipt.summary');
    expect(wrapper.vm.responsePolicy).toBe('NEVER');
    expect(wrapper.vm.requestByDefault).toBe(true);
    expect(wrapper.findAll('v-radio').length).toBe(0);
    expect(wrapper.find('v-switch').exists()).toBe(false);
  });

  it('opens the drawer from its edit action, like the other rows', async () => {
    const { wrapper, emitted } = await mountOver(EmailConnectorUserSettingReadReceipts,
      serviceOver({ requestByDefault: false, responsePolicy: 'ASK', alwaysAllowed: true }));
    const edit = wrapper.find('v-btn.read-receipt-edit');
    expect(edit.attributes('title')).toBe('UserSettings.emailConnector.readReceipt.edit.tooltip');
    expect(edit.find('v-icon').text()).toBe('fa-edit');
    await edit.trigger('click');
    expect(emitted).toEqual([['open-email-read-receipts-drawer']]);
  });

  it('shows what the drawer saved', async () => {
    const { wrapper } = await mountOver(EmailConnectorUserSettingReadReceipts,
      serviceOver({ requestByDefault: false, responsePolicy: 'ASK', alwaysAllowed: true }));
    wrapper.vm.$root.$emit('email-read-receipts-updated', { requestByDefault: true, responsePolicy: 'ALWAYS', alwaysAllowed: true });
    expect(wrapper.vm.requestByDefault).toBe(true);
    expect(wrapper.vm.responsePolicy).toBe('ALWAYS');
  });
});

describe('the read-receipt settings drawer (EXO-90435, EXO-90872)', () => {
  afterEach(() => {
    delete global.fetch;
  });

  it('opens on the stored preferences, and offers Always send while it is allowed', async () => {
    const { wrapper, common } = await openDrawer({ requestByDefault: true, responsePolicy: 'ALWAYS', alwaysAllowed: true });
    expect(common.getReadReceiptSettings).toHaveBeenCalledTimes(1);
    expect(wrapper.vm.drawer).toBe(true);
    expect(wrapper.vm.requestByDefault).toBe(true);
    expect(wrapper.vm.responsePolicy).toBe('ALWAYS');
    expect(offered(wrapper)).toEqual(['ASK', 'NEVER', 'ALWAYS']);
    expect(wrapper.text()).toContain('UserSettings.emailConnector.readReceipt.policy.ALWAYS.hint');
  });

  it('hides Always send while the administrator does not allow it', async () => {
    const { wrapper } = await openDrawer({ requestByDefault: false, responsePolicy: 'ASK', alwaysAllowed: false });
    expect(offered(wrapper)).toEqual(['ASK', 'NEVER']);
    expect(wrapper.text()).not.toContain('policy.ALWAYS.hint');
  });

  it('stores nothing until Save, then stores both preferences, hands them to the row and closes', async () => {
    const { wrapper, common, emitted } = await openDrawer({ requestByDefault: false, responsePolicy: 'ASK', alwaysAllowed: true });
    expect(wrapper.find('v-btn.read-receipt-save').attributes('disabled')).toBeTruthy();
    await wrapper.setData({ requestByDefault: true, responsePolicy: 'NEVER' });
    expect(common.saveReadReceiptSettings).not.toHaveBeenCalled();
    expect(wrapper.find('v-btn.read-receipt-save').attributes('disabled')).toBeFalsy();

    await wrapper.find('v-btn.read-receipt-save').trigger('click');
    await flush();
    expect(common.saveReadReceiptSettings).toHaveBeenCalledWith({ requestByDefault: true, responsePolicy: 'NEVER' });
    expect(emitted).toContainEqual(['email-read-receipts-updated', { requestByDefault: true, responsePolicy: 'NEVER', alwaysAllowed: true }]);
    expect(wrapper.vm.drawer).toBe(false);
  });

  it('drops what was not saved on Cancel', async () => {
    const { wrapper, common } = await openDrawer({ requestByDefault: false, responsePolicy: 'ASK', alwaysAllowed: true });
    await wrapper.setData({ responsePolicy: 'NEVER' });
    await wrapper.find('v-btn.read-receipt-cancel').trigger('click');
    expect(wrapper.vm.drawer).toBe(false);
    expect(common.saveReadReceiptSettings).not.toHaveBeenCalled();
  });

  it('puts the screen back, says so and stays open when a save is refused', async () => {
    const { wrapper, emitted } = await openDrawer({ requestByDefault: false, responsePolicy: 'ASK', alwaysAllowed: true },
      jest.fn(() => Promise.reject(new Error('refused'))));
    await wrapper.setData({ responsePolicy: 'ALWAYS', requestByDefault: true });
    await wrapper.vm.save();
    expect(wrapper.vm.responsePolicy).toBe('ASK');
    expect(wrapper.vm.requestByDefault).toBe(false);
    expect(wrapper.vm.drawer).toBe(true);
    expect(emitted).toContainEqual(['alert-message', 'UserSettings.emailConnector.readReceipt.saveError', 'error']);
    expect(emitted.map(event => event[0])).not.toContain('email-read-receipts-updated');
  });

  it('reads and writes the preferences at /user-email-setting/read-receipts', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({ requestByDefault: true }) }));
    await commonService.getReadReceiptSettings();
    expect(global.fetch.mock.calls[0][0]).toBe('/email-connector/rest/user-email-setting/read-receipts');
    await commonService.saveReadReceiptSettings({ requestByDefault: 1 });
    expect(global.fetch.mock.calls[1][1]).toEqual(expect.objectContaining({
      method: 'PUT',
      body: JSON.stringify({ requestByDefault: true, responsePolicy: 'ASK' }),
    }));
  });
});
