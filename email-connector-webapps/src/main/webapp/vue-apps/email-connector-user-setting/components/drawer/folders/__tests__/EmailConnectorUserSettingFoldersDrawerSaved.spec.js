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

// EXO-90415 — the folders drawer is reused over the full-screen mailbox, whose folder
// column must follow what is saved in it: an opt-in switched, a folder deleted.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorUserSettingFoldersDrawer from '../EmailConnectorUserSettingFoldersDrawer.vue';

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

/**
 * Mounts the drawer with a service answering every call.
 *
 * @param {Object} service the settings service
 * @returns {Object} {wrapper, emit}
 */
function mountDrawer(service) {
  const wrapper = shallowMount(EmailConnectorUserSettingFoldersDrawer, {
    mocks: { $t: key => key, $emailConnectorUserSettingService: service },
    stubs: { 'exo-drawer': true, 'exo-confirm-dialog': true },
  });
  const emit = jest.fn();
  wrapper.vm.$root.$emit = emit;
  return { wrapper, emit };
}

describe('the folders drawer says when it saved a folder (EXO-90415)', () => {
  it('after an opt-in switched, whether it succeeded or not', async () => {
    const service = {
      getMailFolders: jest.fn(() => Promise.resolve({ folders: [] })),
      setMailFolderMirror: jest.fn(() => Promise.reject(new Error('down'))),
    };
    const { wrapper, emit } = mountDrawer(service);

    wrapper.vm.toggle({ id: 5 }, true);
    await flush();

    expect(emit).toHaveBeenCalledWith('email-folders-saved');
  });

  it('after a delete', async () => {
    const service = {
      getMailFolders: jest.fn(() => Promise.resolve({ folders: [] })),
      deleteMailFolder: jest.fn(() => Promise.resolve()),
    };
    const { wrapper, emit } = mountDrawer(service);
    await wrapper.setData({ deleteTarget: { id: 5, displayName: 'Projets' } });

    wrapper.vm.doDelete();
    await flush();

    expect(emit).toHaveBeenCalledWith('email-folders-saved');
  });
});
