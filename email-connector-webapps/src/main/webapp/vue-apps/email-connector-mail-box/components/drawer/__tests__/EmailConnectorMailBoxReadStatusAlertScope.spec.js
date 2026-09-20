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

// EXO-90444 — the failure count of a read or unread (EXO-90438) is told to whoever
// asked for the change, and to nobody else. Three paths reach the same push without
// the user having asked for anything: opening a mail, the conversation reading its
// own messages, and the two-second dwell of an automatically opened one. A mail
// deleted from another client before the next synchronization is counted as a refused
// push by the server -- so those three used to raise "1 email cannot be marked as
// read" over a click that did exactly what the user meant.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerActions from '../EmailConnectorMailBoxDrawerActions.vue';
import EmailConnectorMailBoxDrawerListItemDetailActions from '../EmailConnectorMailBoxDrawerListItemDetailActions.vue';
import EmailConnectorMailBoxDrawerListItemActionMenuItems from '../EmailConnectorMailBoxDrawerListItemActionMenuItems.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

const FOLDERS = [{ key: 'INBOX', type: 'BUILT_IN', syncEnabled: true }];

/**
 * A service whose every function the drawer may call at creation answers an empty
 * promise, with the few this spec is about supplied for real.
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
 * Mounts the mailbox drawer over an inbox whose read/unread pushes the mail server
 * refuses -- what a message deleted from another client answers until the next
 * synchronization drops its row here.
 *
 * @param {Array} emails the listed rows
 * @param {Number} failedUpdates how many pushes the server says it would not take
 * @returns {Object} {wrapper, service, alerts, teardown}
 */
async function mountDrawer(emails, failedUpdates = 1) {
  const service = serviceStub({
    folderLabel: emailConnectorMailBoxService.folderLabel,
    isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
    isDraftsFolder: emailConnectorMailBoxService.isDraftsFolder,
    isListingRow: emailConnectorMailBoxService.isListingRow,
    updateEmailsReadStatus: jest.fn(() => Promise.resolve({ failedUpdates })),
    getEmailBox: jest.fn(() => Promise.resolve({ emails, folders: FOLDERS, emailSyncStatus: 'SUCCESS' })),
    getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
  });
  const alerts = [];
  const listener = event => alerts.push(event.detail);
  document.addEventListener('alert-message', listener);
  const wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
    mocks: {
      $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key),
      $emailConnectorMailBoxService: service,
      $emailConnectorCommonService: serviceStub({}),
      $vuetify: { breakpoint: {}, rtl: false },
    },
    stubs: { 'exo-drawer': true },
  });
  await wrapper.setData({ emailBox: { emails, folders: FOLDERS }, currentFolder: 'INBOX', emailBoxDrawer: true });
  return {
    wrapper,
    service,
    alerts,
    teardown: () => {
      document.removeEventListener('alert-message', listener);
      wrapper.vm.stopAutoRefresh();
      wrapper.destroy();
    },
  };
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0));
const unreadRow = () => ({ mailRemoteId: 1, folder: 'INBOX', read: false, subject: 'gone elsewhere' });

describe('a read the user did not ask for says nothing when the mail server refuses it (EXO-90444)', () => {
  let fixture;
  afterEach(() => fixture?.teardown());

  it('opening a mail the server no longer holds raises no alert', async () => {
    fixture = await mountDrawer([unreadRow()]);

    await fixture.wrapper.vm.openEmailDetailContent(1, 'INBOX');
    await flush();

    expect(fixture.service.updateEmailsReadStatus).toHaveBeenCalledWith([1], true, 'INBOX');
    expect(fixture.alerts).toEqual([]);
  });

  it('the reader, the conversation and the dwell read what they show without alerting', async () => {
    fixture = await mountDrawer([unreadRow()]);

    // What the narrow drawer's open, markThreadRead and markAutoOpenedEmailRead all
    // emit: no fifth argument, so no opt-in, so no alert.
    fixture.wrapper.vm.$root.$emit('update-email-read-status', true, [1], 'INBOX');
    await flush();

    expect(fixture.service.updateEmailsReadStatus).toHaveBeenCalledWith([1], true, 'INBOX');
    expect(fixture.alerts).toEqual([]);
  });

  it('a read the user asked for still says how many pushes the server would not take', async () => {
    fixture = await mountDrawer([unreadRow()]);

    // What the toolbar, the reader's toolbar and the row menu emit (EXO-90438).
    fixture.wrapper.vm.$root.$emit('update-email-read-status', true, [1], 'INBOX', null, { userInitiated: true });
    await flush();

    expect(fixture.alerts.pop().alertMessage)
      .toBe('emailConnector.mailBox.list.drawer.read.email.error|1');
  });
});

// The drawer cannot tell an opening from a hand-made read on its own: the opt-in is
// the emitter's word. So each of the three ways a user makes one is pinned where it
// is emitted -- the reader toolbar and the row menu could otherwise have lost theirs
// in silence, only the selection toolbar's payload being asserted anywhere.
describe('every way the user asks for a read or unread opts into the failure count (EXO-90444)', () => {
  const archived = { mailRemoteId: 5, folder: 'ARCHIVE', subject: 'A search result' };

  /**
   * Replaces a mounted component's root emit with a spy. $root is a Vue-reserved
   * instance property, which the mocks option cannot shadow.
   *
   * @param {Object} vm the component
   * @returns {Function} the spy
   */
  function spyRoot(vm) {
    const emit = jest.fn();
    vm.$root.$emit = emit;
    return emit;
  }

  /**
   * The options object a component emitted the read/unread with, or undefined.
   *
   * @param {jest.Mock} emit the $root.$emit spy
   * @returns {Object} the fifth argument of the event
   */
  function emittedOptions(emit) {
    return emit.mock.calls.find(call => call[0] === 'update-email-read-status')?.[5];
  }

  it('the selection toolbar', () => {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerActions, {
      propsData: { emails: [archived], selectedEmails: ['ARCHIVE:5'], selectMode: true },
      mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
    });
    const emit = spyRoot(wrapper.vm);

    wrapper.vm.updateEmailsReadStatus(false);

    expect(emittedOptions(emit)).toEqual({ userInitiated: true });
    wrapper.destroy();
  });

  it('the reader toolbar', () => {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetailActions, {
      propsData: { email: archived, thread: null },
      mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
    });
    const emit = spyRoot(wrapper.vm);

    wrapper.vm.updateEmailReadStatus();

    expect(emittedOptions(emit)).toEqual({ userInitiated: true });
    wrapper.destroy();
  });

  it('the row menu', () => {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemActionMenuItems, {
      propsData: { email: archived, thread: null },
      mocks: { $t: key => key, $emailConnectorMailBoxService: emailConnectorMailBoxService },
    });
    const emit = spyRoot(wrapper.vm);

    wrapper.vm.updateEmailReadStatus();

    expect(emittedOptions(emit)).toEqual({ userInitiated: true });
    wrapper.destroy();
  });
});
