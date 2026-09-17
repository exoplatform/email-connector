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

// EXO-89942 — an action on a conversation from eXo leaves the mailbox as the same
// action done in Gmail would. On the drawer's side that is three things: a delete or
// a "Mark as spam" asks the server for the WHOLE conversation (the server takes the
// user's own replies in Sent along); the deleted rows show in the Trash or Junk
// listing at once, on the remembered-rows mechanics a "Move to..." already uses
// (EXO-89966), until the server's re-read lists them; and a restore shows each row in
// the folder the server says it went back to — Sent for the user's own, the inbox for
// the rest. Same honesty as the move: a request the server refused leaves no row in a
// folder the messages never reached.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'SENT', type: 'BUILT_IN', syncEnabled: true },
  { key: 'TRASH', type: 'BUILT_IN', syncEnabled: true },
  { key: 'JUNK', type: 'BUILT_IN', syncEnabled: true },
];

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
 * Mounts the mailbox drawer over a listed folder.
 *
 * @param {Array} emails the listed rows
 * @param {Object} answers the server's answers, by service function
 * @param {String} currentFolder the folder on screen
 * @returns {Object} {wrapper, service, teardown}
 */
async function mountDrawer(emails, answers = {}, currentFolder = 'INBOX') {
  const service = serviceStub({
    folderLabel: emailConnectorMailBoxService.folderLabel,
    deleteEmails: jest.fn(() => answers.deleteEmails || Promise.resolve({ failedDeletions: 0 })),
    markAsJunk: jest.fn(() => answers.markAsJunk || Promise.resolve({ failedJunkMoves: 0 })),
    restoreEmails: jest.fn(() => answers.restoreEmails || Promise.resolve({ failedRestores: 0, restoredToSent: [] })),
    restoreFromJunk: jest.fn(() => answers.restoreFromJunk || Promise.resolve({ failedJunkRestores: 0, restoredToSent: [] })),
    getEmailBox: jest.fn(() => Promise.resolve({ emails, folders: FOLDERS, emailSyncStatus: 'SUCCESS' })),
    getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
  });
  const wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
    mocks: {
      $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key),
      $emailConnectorMailBoxService: service,
      $emailConnectorCommonService: serviceStub({}),
      $vuetify: { breakpoint: {}, rtl: false },
    },
    stubs: { 'exo-drawer': true },
  });
  await wrapper.setData({ emailBox: { emails, folders: FOLDERS }, currentFolder });
  return {
    wrapper,
    service,
    teardown: () => {
      wrapper.vm.stopAutoRefresh();
      wrapper.destroy();
    },
  };
}

/**
 * One listed row.
 *
 * @param {Number} mailRemoteId the IMAP UID
 * @param {String} mailHeaderId the Message-ID
 * @param {String} folder the folder the row is listed in
 * @returns {Object} the row
 */
function row(mailRemoteId, mailHeaderId, folder = 'INBOX') {
  return { mailRemoteId, mailHeaderId, folder, subject: `mail ${mailRemoteId}`, receivedDate: '2026-09-17T10:00:00Z' };
}

/**
 * The remembered rows waiting in a folder, by Message-ID.
 *
 * @param {Object} fixture the mounted drawer
 * @param {String} folder the folder key
 * @returns {Array<String>} the Message-IDs remembered there
 */
function rememberedIn(fixture, folder) {
  return fixture.wrapper.vm.refreshPendingRows.filter(pending => pending.folder === folder).map(pending => pending.mailHeaderId);
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

describe('a delete or a spam report files the whole conversation and shows it in its folder at once (EXO-89942)', () => {
  let fixture;
  afterEach(() => fixture?.teardown());

  it('asks the server for the whole conversation, one request per listed folder', async () => {
    fixture = await mountDrawer([row(1, '<a@host>'), row(2, '<b@host>'), row(3, '<c@host>', 'SENT')]);

    fixture.wrapper.vm.deleteEmails([1, 2, 3]);
    await flush();

    expect(fixture.service.deleteEmails).toHaveBeenCalledWith([1, 2], 'INBOX', true);
    expect(fixture.service.deleteEmails).toHaveBeenCalledWith([3], 'SENT', true);
  });

  it('the deleted rows leave the listing and wait in the Trash before the server lists them there', async () => {
    fixture = await mountDrawer([row(1, '<a@host>'), row(2, '<b@host>')]);

    fixture.wrapper.vm.deleteEmails([1, 2]);
    await flush();

    expect(fixture.wrapper.vm.emails.map(email => email.mailRemoteId)).toEqual([]);
    expect(rememberedIn(fixture, 'TRASH')).toEqual(['<a@host>', '<b@host>']);
    // Inert until the server lists it: a UID the Trash cannot hold, no conversation to
    // be grouped under.
    const waiting = fixture.wrapper.vm.refreshPendingRows[0];
    expect(waiting.mailRemoteId).toBeLessThan(0);
    expect(waiting.refreshPending).toBe(true);
  });

  it('a delete the server refused leaves no row in the Trash', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], { deleteEmails: Promise.resolve({ failedDeletions: 1 }) });

    fixture.wrapper.vm.deleteEmails([1]);
    await flush();

    expect(rememberedIn(fixture, 'TRASH')).toEqual([]);
  });

  it('a spam report does the same, into the Junk folder', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')]);

    fixture.wrapper.vm.markAsJunk([1]);
    await flush();

    expect(fixture.service.markAsJunk).toHaveBeenCalledWith([1], 'INBOX', true);
    expect(rememberedIn(fixture, 'JUNK')).toEqual(['<a@host>']);
    expect(rememberedIn(fixture, 'TRASH')).toEqual([]);
  });
});

describe('a restore shows each row where the server put it back (EXO-89942)', () => {
  let fixture;
  afterEach(() => fixture?.teardown());

  it('the user\'s own message waits in Sent, the other in the inbox, once the server has said which is which', async () => {
    fixture = await mountDrawer([row(11, '<theirs@host>', 'TRASH'), row(12, '<mine@host>', 'TRASH')],
      { restoreEmails: Promise.resolve({ failedRestores: 0, restoredToSent: [12] }) },
      'TRASH');

    fixture.wrapper.vm.restoreEmails([11, 12]);
    // Nothing is remembered before the answer: the destination is the server's to say.
    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
    await flush();

    expect(fixture.service.restoreEmails).toHaveBeenCalledWith([11, 12]);
    expect(fixture.wrapper.vm.emails.map(email => email.mailRemoteId)).toEqual([]);
    expect(rememberedIn(fixture, 'SENT')).toEqual(['<mine@host>']);
    expect(rememberedIn(fixture, 'INBOX')).toEqual(['<theirs@host>']);
  });

  it('a restore the server honoured only in part remembers nothing: it cannot tell which rows moved', async () => {
    fixture = await mountDrawer([row(11, '<theirs@host>', 'TRASH'), row(12, '<mine@host>', 'TRASH')],
      { restoreEmails: Promise.resolve({ failedRestores: 1, restoredToSent: [] }) },
      'TRASH');

    fixture.wrapper.vm.restoreEmails([11, 12]);
    await flush();

    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
  });

  it('"Not spam" follows the same answer out of the Junk folder', async () => {
    fixture = await mountDrawer([row(21, '<mine@host>', 'JUNK')],
      { restoreFromJunk: Promise.resolve({ failedJunkRestores: 0, restoredToSent: [21] }) },
      'JUNK');

    fixture.wrapper.vm.restoreFromJunk([21]);
    await flush();

    expect(fixture.service.restoreFromJunk).toHaveBeenCalledWith([21]);
    expect(rememberedIn(fixture, 'SENT')).toEqual(['<mine@host>']);
    expect(rememberedIn(fixture, 'INBOX')).toEqual([]);
  });
});

describe('the service names the conversation on the wire (EXO-89942)', () => {
  const urls = [];
  beforeEach(() => {
    urls.length = 0;
    global.fetch = jest.fn(url => {
      urls.push(url);
      return Promise.resolve({ ok: true, json: () => Promise.resolve({}) });
    });
  });
  afterEach(() => {
    delete global.fetch;
  });

  it('a conversation delete carries conversation=true, beside the folder when there is one', async () => {
    await emailConnectorMailBoxService.deleteEmails([1], 'INBOX', true);
    await emailConnectorMailBoxService.deleteEmails([1], 'SENT', true);
    await emailConnectorMailBoxService.deleteEmails([1], 'SENT');
    await emailConnectorMailBoxService.markAsJunk([1], 'INBOX', true);

    expect(urls).toEqual([
      '/email-connector/rest/email-box?conversation=true',
      '/email-connector/rest/email-box?folder=SENT&conversation=true',
      '/email-connector/rest/email-box?folder=SENT',
      '/email-connector/rest/email-box/junk?conversation=true',
    ]);
  });
});
