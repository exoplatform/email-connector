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

// EXO-90434 -- disconnecting cancels the mails scheduled to be sent: the confirmation
// says how many, before the account is released.

import Vue from 'vue';
import { shallowMount } from '@vue/test-utils';
import DisconnectDrawer from '../EmailConnectorUserSettingDisconnectDrawer.vue';

Vue.config.ignoredElements.push(/^email-connector-/, 'exo-drawer');

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

/**
 * Mounts the disconnect drawer over the given counts.
 *
 * @param {Number} contacts the contacts count
 * @param {Number} scheduled the scheduled mails count
 * @returns {Object} {wrapper, service, drawerOpen}
 */
function mountDrawer(contacts, scheduled) {
  const service = {
    getContactsCount: jest.fn(() => Promise.resolve(contacts)),
    getScheduledEmailsCount: jest.fn(() => Promise.resolve(scheduled)),
    deleteUserEmailSetting: jest.fn(() => Promise.resolve()),
  };
  const drawerOpen = jest.fn();
  const wrapper = shallowMount(DisconnectDrawer, {
    mocks: { $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key), $emailConnectorUserSettingService: service },
    stubs: {
      'exo-drawer': {
        props: ['value'],
        template: '<div><slot name="content" /><slot name="footer" /></div>',
        methods: { open: drawerOpen, close: jest.fn() },
      },
    },
  });
  return { wrapper, service, drawerOpen };
}

describe('the disconnect confirmation warns about the scheduled mails (EXO-90434)', () => {
  it('asks first when mails are scheduled, even with no contact, and says how many will be cancelled', async () => {
    const { wrapper, service, drawerOpen } = mountDrawer(0, 3);
    wrapper.vm.open({ name: 'BlueMind' });
    await flush();

    expect(drawerOpen).toHaveBeenCalled();
    expect(service.deleteUserEmailSetting).not.toHaveBeenCalled();
    await wrapper.setData({ disconnectDrawer: true });
    expect(wrapper.find('.scheduled-emails-warning').text()).toBe('UserSettings.emailConnector.userSetting.disconnect.scheduled.many|3');
    // No contact: no contact question, and the plain Disconnect goes on.
    expect(wrapper.find('email-connector-contacts-choice-step').exists()).toBe(false);
  });

  it('says it in the singular for one', async () => {
    const { wrapper } = mountDrawer(2, 1);
    wrapper.vm.open({ name: 'BlueMind' });
    await flush();
    await wrapper.setData({ disconnectDrawer: true });
    expect(wrapper.find('.scheduled-emails-warning').text()).toBe('UserSettings.emailConnector.userSetting.disconnect.scheduled.one');
    expect(wrapper.find('email-connector-contacts-choice-step').exists()).toBe(true);
  });

  it('stays one click with nothing stored and nothing scheduled', async () => {
    const { wrapper, service, drawerOpen } = mountDrawer(0, 0);
    wrapper.vm.open({ name: 'BlueMind' });
    await flush();
    expect(drawerOpen).not.toHaveBeenCalled();
    expect(service.deleteUserEmailSetting).toHaveBeenCalled();
    expect(wrapper.find('.scheduled-emails-warning').exists()).toBe(false);
  });
});
