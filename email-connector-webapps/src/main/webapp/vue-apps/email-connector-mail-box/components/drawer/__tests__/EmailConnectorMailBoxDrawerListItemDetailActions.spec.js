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

// EXO-89942 — "Filing a conversation from the reader leaves part of it behind": the
// reader's header toolbar used to act on [this.email.mailRemoteId] alone, even when it
// was opened on a conversation of several messages. These pins drive every action on
// that toolbar and assert it reaches the whole conversation, not just the one message
// that happened to be open — the real $emailConnectorMailBoxService runs underneath
// (not mocked out), so the fix (threadIdsInFolder) is exercised for real.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawerListItemDetailActions from '../EmailConnectorMailBoxDrawerListItemDetailActions.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

/**
 * Mounts the toolbar as the drawer does: opened on `email`, showing the conversation
 * `thread` the reader assembled for it.
 *
 * @param {Object} email the opened message
 * @param {Object} thread the conversation, {threadId, messages, subject} — the
 *   reader's shape, possibly spanning several folders
 * @returns {Object} {wrapper, emit} — the mounted component and a spy standing in
 *   for $root.$emit
 */
function mountToolbar(email, thread) {
  const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemDetailActions, {
    propsData: { email, thread },
    mocks: {
      $t: key => key,
      $emailConnectorMailBoxService: emailConnectorMailBoxService,
    },
  });
  // $root is a Vue-reserved instance property: Vue Test Utils' `mocks` option cannot
  // shadow it (verified — a mocked $root passed there never reaches the component), so
  // the real root instance the shallow mount created is spied on directly instead.
  const emit = jest.fn();
  wrapper.vm.$root.$emit = emit;
  return { wrapper, emit };
}

/**
 * The arguments a given $root event was last emitted with, or undefined when it never
 * fired.
 *
 * @param {jest.Mock} emit the $root.$emit spy
 * @param {String} event the event name to look for
 * @returns {Array} the arguments emitted after the event name, or undefined
 */
function emittedArgs(emit, event) {
  const call = emit.mock.calls.find(args => args[0] === event);
  return call && call.slice(1);
}

// A conversation of two messages, BOTH listed in the folder the reader is opened
// from — the shape the bug report was filed against: filing, deleting, archiving or
// reporting it from the reader must take both, not only the one message that
// happened to be clicked in the list behind it.
const openedEmail = { mailRemoteId: 1, folder: 'INBOX', subject: 'Two in the inbox' };
const twoMessageThread = {
  threadId: 'thread-1',
  subject: 'Two in the inbox',
  messages: [
    { mailRemoteId: 1, folder: 'INBOX' },
    { mailRemoteId: 2, folder: 'INBOX' },
  ],
};

describe('EmailConnectorMailBoxDrawerListItemDetailActions', () => {
  describe.each([
    ['updateEmailReadStatus', 'update-email-read-status', args => args[1]],
    ['deleteEmail', 'delete-email', args => args[0]],
    ['archiveEmail', 'archive-email', args => args[0]],
    ['moveToFolder', 'open-move-to-folder-drawer', args => args[0]],
    ['markAsJunk', 'junk-email', args => args[0]],
    ['restoreFromJunk', 'not-junk-email', args => args[0]],
    ['restoreEmail', 'restore-email', args => args[0]],
    ['purgeEmail', 'open-purge-email-confirm-popup', args => args[0]],
  ])('%s()', (method, event, idsFromArgs) => {
    it('acts on every message of the conversation listed in the source folder, not only the opened one', () => {
      const { wrapper, emit } = mountToolbar(openedEmail, twoMessageThread);
      wrapper.vm[method]();
      const args = emittedArgs(emit, event);
      expect(args).toBeDefined();
      expect(idsFromArgs(args)).toEqual([1, 2]);
    });
  });

  it('scopes the conversation to the acting folder: a message filed elsewhere must not move along with the Inbox one (EXO-89942)', () => {
    // thread.messages spans folders on purpose (EmailConnectorMailBoxDrawerThreadContent
    // assembles the reader's conversation ACROSS folders, so a filed message resurfaces
    // in it) — acting from the Inbox must still touch only the Inbox message.
    const crossFolderThread = {
      threadId: 'thread-2',
      subject: 'One inbox, one archived',
      messages: [
        { mailRemoteId: 1, folder: 'INBOX' },
        { mailRemoteId: 2, folder: 'ARCHIVE' },
      ],
    };
    const { wrapper, emit } = mountToolbar(openedEmail, crossFolderThread);
    wrapper.vm.deleteEmail();
    expect(emittedArgs(emit, 'delete-email')[0]).toEqual([1]);
  });

  it('falls back to the opened message alone when nothing else is open (no conversation)', () => {
    const { wrapper, emit } = mountToolbar(openedEmail, null);
    wrapper.vm.deleteEmail();
    expect(emittedArgs(emit, 'delete-email')[0]).toEqual([1]);
  });
});
