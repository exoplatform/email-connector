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

// EXO-90503 — the two things this screen must not get wrong: a control for a right the
// mail server did not grant is absent rather than disabled, and declining an invitation
// is never presented as taking the access away, because it does not.

import { mount } from '@vue/test-utils';
import EmailConnectorUserSettingSharedWithMeDrawer from '../EmailConnectorUserSettingSharedWithMeDrawer.vue';

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

/**
 * Mounts the drawer over a service answering with the given rows.
 *
 * @param {Array} delegations the rows the server answers
 * @returns {Object} the wrapper
 */
function mountDrawer(delegations) {
  return mount(EmailConnectorUserSettingSharedWithMeDrawer, {
    mocks: {
      $t: key => key,
      $emailConnectorUserSettingService: {
        getReceivedDelegations: jest.fn(() => Promise.resolve(delegations)),
      },
    },
    stubs: {
      'exo-drawer': { template: '<div><slot name="content"></slot></div>', methods: { open: () => {}, close: () => {} } },
      'user-avatar': true,
      'email-connector-delegation-rights': true,
    },
  });
}

/**
 * An accepted share whose rights carry the given affordances.
 *
 * @param {Object} affordances the affordances the server computed
 * @returns {Object} the row
 */
function accepted(affordances) {
  return {
    id: 3,
    ownerId: 'anne',
    ownerMailbox: 'anne@example.org',
    status: 'ACCEPTED',
    preset: 'READER',
    rights: 'lrs',
    badgeIncluded: false,
    affordances,
  };
}

describe('the shared-with-me drawer shows only what the rights allow (EXO-90503)', () => {
  it('offers the unread-badge toggle when read state can be kept', async () => {
    const wrapper = mountDrawer([accepted({ read: true, markRead: true })]);
    wrapper.vm.open();
    await flush();

    expect(wrapper.text()).toContain('UserSettings.emailConnector.sharedWithMe.badge');
  });

  it('does not offer it at all — not disabled — when it cannot', async () => {
    const wrapper = mountDrawer([accepted({ read: true, markRead: false })]);
    wrapper.vm.open();
    await flush();

    expect(wrapper.text()).not.toContain('UserSettings.emailConnector.sharedWithMe.badge');
  });
});

describe('the shared-with-me drawer never says that declining removes the access', () => {
  it('says the opposite, on the invitation itself', async () => {
    const wrapper = mountDrawer([{
      id: 4,
      ownerId: 'anne',
      status: 'PENDING',
      preset: 'READER',
      rights: 'lrs',
      affordances: { read: true },
    }]);
    wrapper.vm.open();
    await flush();

    expect(wrapper.text()).toContain('UserSettings.emailConnector.sharedWithMe.declineKeepsAccess');
    expect(wrapper.text()).toContain('UserSettings.emailConnector.sharedWithMe.decline');
  });
});
