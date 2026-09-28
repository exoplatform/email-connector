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

// EXO-90421 -- the reader's category bar, now that a category can be put on mail of any
// folder (a drop on the folder column): it tags and untags each message IN ITS FOLDER,
// and it shows a category a drop put on the mail it is reading.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawerCategoryBar from '../EmailConnectorMailBoxDrawerCategoryBar.vue';

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

/**
 * Mounts the bar over a conversation, recording what it asks the server and announces.
 *
 * @param {Array} emails the conversation's messages
 * @returns {Object} {wrapper, service, emitted}
 */
function mountBar(emails) {
  const service = {
    getAvailableEmailCategories: jest.fn(() => Promise.resolve([{ id: 11, name: 'Important' }])),
    linkEmailsToCategory: jest.fn(() => Promise.resolve({ linked: 1 })),
    unlinkEmailsFromCategory: jest.fn(() => Promise.resolve({ unlinked: 1 })),
  };
  const wrapper = shallowMount(EmailConnectorMailBoxDrawerCategoryBar, {
    propsData: { emails },
    mocks: { $t: key => key, $emailConnectorMailBoxService: service },
  });
  const emitted = [];
  wrapper.vm.$root.$on('email-categories-updated', update => emitted.push(update));
  return { wrapper, service, emitted };
}

describe('the reader\'s category bar across folders (EXO-90421)', () => {
  it('tags and untags each message in the folder its UID is numbered in', async () => {
    const emails = [{ mailRemoteId: 5, folder: 'INBOX' }, { mailRemoteId: 5, folder: 'ARCHIVE' }, { mailRemoteId: 8 }];
    const { wrapper, service, emitted } = mountBar(emails);

    await wrapper.vm.toggle({ id: 11 }, true);

    expect(service.linkEmailsToCategory.mock.calls).toEqual([[[5, 8], 11, 'INBOX'], [[5], 11, 'ARCHIVE']]);
    expect(emitted).toEqual([
      { mailRemoteIds: [5, 8], categoryId: 11, assign: true, folder: 'INBOX' },
      { mailRemoteIds: [5], categoryId: 11, assign: true, folder: 'ARCHIVE' },
    ]);
    expect(emails.map(email => email.categoryIds)).toEqual([[11], [11], [11]]);

    await wrapper.vm.toggle({ id: 11 }, false);

    expect(service.unlinkEmailsFromCategory.mock.calls).toEqual([[[5, 8], 11, 'INBOX'], [[5], 11, 'ARCHIVE']]);
    expect(wrapper.vm.assignedIds).toEqual([]);
  });

  it('sends nothing for the conversation\'s All Mail copies, which the server does not categorize', async () => {
    const { wrapper, service } = mountBar([{ mailRemoteId: 5, folder: 'INBOX' }, { mailRemoteId: 9, folder: 'ALL_MAIL' }]);

    await wrapper.vm.toggle({ id: 11 }, true);
    await wrapper.vm.toggle({ id: 11 }, false);

    expect(service.linkEmailsToCategory.mock.calls).toEqual([[[5], 11, 'INBOX']]);
    expect(service.unlinkEmailsFromCategory.mock.calls).toEqual([[[5], 11, 'INBOX']]);
  });

  it('a folder the server refused keeps its messages as they were', async () => {
    const emails = [{ mailRemoteId: 5, folder: 'INBOX' }, { mailRemoteId: 6, folder: 'ARCHIVE' }];
    const { wrapper, service } = mountBar(emails);
    service.linkEmailsToCategory.mockImplementation((ids, id, folder) =>
      (folder === 'ARCHIVE' ? Promise.reject(new Error('refused')) : Promise.resolve({ linked: 1 })));

    await wrapper.vm.toggle({ id: 11 }, true);

    expect(emails.map(email => email.categoryIds || 'untouched')).toEqual([[11], 'untouched']);
  });

  it('shows a category a drop put on the mail it reads -- that folder\'s, not a same-UID one', async () => {
    const emails = [{ mailRemoteId: 5, folder: 'ARCHIVE', categoryIds: [] }, { mailRemoteId: 5, folder: 'INBOX', categoryIds: [] }];
    const { wrapper } = mountBar(emails);
    await flush();

    wrapper.vm.$root.$emit('email-categories-updated', { mailRemoteIds: [5], categoryId: 11, assign: true, folder: 'ARCHIVE' });

    expect(emails.map(email => email.categoryIds)).toEqual([[11], []]);
    expect(wrapper.vm.assignedIds).toEqual([11]);
    wrapper.destroy();
  });
});
