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
// one hidden while the administrator does not allow it.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorUserSettingReadReceipts from '../EmailConnectorUserSettingReadReceipts.vue';
import * as commonService from '../../../../email-connector-common/js/EmailConnectorCommonService.js';

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

/**
 * Mounts the rows over stored preferences.
 *
 * @param {Object} stored what the server answers
 * @param {Function} save what a save does
 * @returns {Promise<Object>} {wrapper, common, emitted}
 */
async function mountRows(stored, save = jest.fn(settings => Promise.resolve({ ...settings, alwaysAllowed: stored.alwaysAllowed }))) {
  const common = {
    getReadReceiptSettings: jest.fn(() => Promise.resolve(stored)),
    saveReadReceiptSettings: save,
  };
  const wrapper = shallowMount(EmailConnectorUserSettingReadReceipts, {
    mocks: { $t: key => key, $emailConnectorCommonService: common },
  });
  const emitted = [];
  wrapper.vm.$root.$emit = (...args) => emitted.push(args);
  await flush();
  return { wrapper, common, emitted };
}

/**
 * The values of the radios on screen.
 *
 * @param {Object} wrapper the rows
 * @returns {Array<String>} the policies offered
 */
const offered = wrapper => wrapper.findAll('v-radio').wrappers.map(radio => radio.attributes('value'));

describe('the read-receipt settings (EXO-90435)', () => {
  afterEach(() => {
    delete global.fetch;
  });

  it('shows the stored preferences, and offers Always send while it is allowed', async () => {
    const { wrapper } = await mountRows({ requestByDefault: true, responsePolicy: 'ALWAYS', alwaysAllowed: true });
    expect(wrapper.vm.requestByDefault).toBe(true);
    expect(wrapper.vm.responsePolicy).toBe('ALWAYS');
    expect(offered(wrapper)).toEqual(['ASK', 'NEVER', 'ALWAYS']);
    expect(wrapper.text()).toContain('UserSettings.emailConnector.readReceipt.policy.ALWAYS.hint');
  });

  it('hides Always send while the administrator does not allow it', async () => {
    const { wrapper } = await mountRows({ requestByDefault: false, responsePolicy: 'ASK', alwaysAllowed: false });
    expect(offered(wrapper)).toEqual(['ASK', 'NEVER']);
    expect(wrapper.text()).not.toContain('policy.ALWAYS.hint');
  });

  it('stores both preferences on a change, and shows what the server kept', async () => {
    const { wrapper, common } = await mountRows({ requestByDefault: false, responsePolicy: 'ASK', alwaysAllowed: true });
    await wrapper.setData({ requestByDefault: true });
    await wrapper.vm.save();
    expect(common.saveReadReceiptSettings).toHaveBeenLastCalledWith({ requestByDefault: true, responsePolicy: 'ASK' });

    await wrapper.setData({ responsePolicy: 'NEVER' });
    await wrapper.vm.save();
    expect(common.saveReadReceiptSettings).toHaveBeenLastCalledWith({ requestByDefault: true, responsePolicy: 'NEVER' });
    expect(wrapper.vm.responsePolicy).toBe('NEVER');
  });

  it('puts the screen back and says so when a save is refused', async () => {
    const { wrapper, emitted } = await mountRows({ requestByDefault: false, responsePolicy: 'ASK', alwaysAllowed: true },
      jest.fn(() => Promise.reject(new Error('refused'))));
    await wrapper.setData({ responsePolicy: 'ALWAYS', requestByDefault: true });
    await wrapper.vm.save();
    expect(wrapper.vm.responsePolicy).toBe('ASK');
    expect(wrapper.vm.requestByDefault).toBe(false);
    expect(emitted).toEqual([['alert-message', 'UserSettings.emailConnector.readReceipt.saveError', 'error']]);
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
