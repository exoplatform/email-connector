/*
 * Copyright (C) 2025 eXo Platform SAS.
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

// A regression pin for the row menu's own `threadIds`, now routed through the shared
// $emailConnectorMailBoxService.threadIdsInFolder (EXO-89942) instead of reading
// thread.mailRemoteIds directly — this is what the row menu behaved like before the
// extraction, and must keep behaving like after it.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawerListItemActionMenuItems from '../EmailConnectorMailBoxDrawerListItemActionMenuItems.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

function mountMenu(email, thread) {
  const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemActionMenuItems, {
    propsData: { email, thread },
    mocks: {
      $t: key => key,
      $emailConnectorMailBoxService: emailConnectorMailBoxService,
    },
  });
  const emit = jest.fn();
  wrapper.vm.$root.$emit = emit;
  return { wrapper, emit };
}

describe('EmailConnectorMailBoxDrawerListItemActionMenuItems', () => {
  it('acts on the whole thread as grouped for this row\'s own folder', () => {
    const email = { mailRemoteId: 1, folder: 'INBOX' };
    // groupEmailsByThread's own output shape: emails + mailRemoteIds, already scoped
    // to the folder the row is listed in.
    const thread = {
      threadId: 'thread-1',
      emails: [
        { mailRemoteId: 1, folder: 'INBOX' },
        { mailRemoteId: 2, folder: 'INBOX' },
      ],
      mailRemoteIds: [1, 2],
    };
    const { wrapper, emit } = mountMenu(email, thread);
    wrapper.vm.deleteEmail();
    const call = emit.mock.calls.find(args => args[0] === 'delete-email');
    expect(call[1]).toEqual([1, 2]);
  });

  it('falls back to the row\'s own id alone when it is not part of a conversation', () => {
    const email = { mailRemoteId: 1, folder: 'INBOX' };
    const { wrapper, emit } = mountMenu(email, null);
    wrapper.vm.deleteEmail();
    const call = emit.mock.calls.find(args => args[0] === 'delete-email');
    expect(call[1]).toEqual([1]);
  });
});
