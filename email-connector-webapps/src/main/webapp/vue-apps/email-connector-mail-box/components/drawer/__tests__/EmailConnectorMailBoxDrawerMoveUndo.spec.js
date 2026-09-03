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

// EXO-89952 — the move's toast with its Undo. The mailbox keeps a failure-only alert
// convention for every action (alertOnActionFailures says nothing on success); move is
// the one exception, and only because its toast carries a capability. These pins hold
// the two sides of that: the toast appears exactly when the Undo can work (every
// request succeeded, every row has a Message-ID to be found again by), and the Undo it
// carries addresses the messages by identity, each group back to the folder it came
// from.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'CUSTOM:1', type: 'CUSTOM', displayName: 'Factures', path: 'Factures', syncEnabled: true },
  { key: 'ARCHIVE', type: 'BUILT_IN', syncEnabled: true },
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
 * Mounts the mailbox drawer over a listed inbox, recording every alert it dispatches.
 *
 * @param {Array} emails the listed rows
 * @param {Object} answers {moveEmails, undoMoveEmails} — the server's answers
 * @returns {Object} {wrapper, alerts, service, teardown}
 */
async function mountDrawer(emails, answers) {
  const service = serviceStub({
    folderLabel: emailConnectorMailBoxService.folderLabel,
    moveEmails: jest.fn(() => Promise.resolve(answers.moveEmails || { failedMoves: 0 })),
    undoMoveEmails: jest.fn(() => Promise.resolve(answers.undoMoveEmails || { failedUndos: 0 })),
    getEmailBox: jest.fn(() => Promise.resolve({ emails, folders: FOLDERS })),
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
  await wrapper.setData({ emailBox: { emails, folders: FOLDERS } });
  const alerts = [];
  const listener = event => alerts.push(event.detail);
  document.addEventListener('alert-message', listener);
  return {
    wrapper,
    alerts,
    service,
    teardown: () => {
      document.removeEventListener('alert-message', listener);
      wrapper.destroy();
    },
  };
}

/**
 * One listed inbox row.
 *
 * @param {Number} mailRemoteId the IMAP UID
 * @param {String} mailHeaderId the Message-ID, or null for a row without one
 * @returns {Object} the row
 */
function row(mailRemoteId, mailHeaderId) {
  return { mailRemoteId, mailHeaderId, folder: 'INBOX', subject: `mail ${mailRemoteId}` };
}

describe('the move toast and its Undo (EXO-89952)', () => {
  let fixture;

  afterEach(() => fixture?.teardown());

  it('offers an Undo once a single move succeeded, named after the folder', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});

    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');

    expect(fixture.service.moveEmails).toHaveBeenCalledWith([1], 'INBOX', 'CUSTOM:1');
    expect(fixture.alerts).toHaveLength(1);
    const [toast] = fixture.alerts;
    expect(toast.alertType).toBe('success');
    expect(toast.alertMessage).toBe('emailConnector.mailBox.list.drawer.move.email.success|Factures');
    expect(toast.alertLinkText).toBe('emailConnector.mailBox.list.drawer.move.undo.label');
    expect(typeof toast.alertLinkCallback).toBe('function');
  });

  it('the Undo moves the messages back by Message-ID, into the folder they came from, then reloads', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    fixture.service.getEmailBox.mockClear();

    await fixture.alerts[0].alertLinkCallback();

    expect(fixture.service.undoMoveEmails).toHaveBeenCalledWith(['<a@host>'], 'CUSTOM:1', 'INBOX');
    expect(fixture.service.getEmailBox).toHaveBeenCalled();
    // A clean undo says nothing: the rows coming back say it.
    expect(fixture.alerts).toHaveLength(1);
  });

  it('a bulk move counts, and its Undo carries the whole batch', async () => {
    fixture = await mountDrawer([row(1, '<a@host>'), row(2, '<b@host>'), row(3, '<c@host>')], {});

    await fixture.wrapper.vm.moveEmails([1, 2], 'CUSTOM:1');
    await fixture.alerts[0].alertLinkCallback();

    expect(fixture.alerts[0].alertMessage).toBe('emailConnector.mailBox.list.drawer.move.emails.success|2|Factures');
    expect(fixture.service.undoMoveEmails).toHaveBeenCalledWith(['<a@host>', '<b@host>'], 'CUSTOM:1', 'INBOX');
  });

  it('says nothing but the error when the move failed, even in part', async () => {
    fixture = await mountDrawer([row(1, '<a@host>'), row(2, '<b@host>')], { moveEmails: { failedMoves: 1 } });

    await fixture.wrapper.vm.moveEmails([1, 2], 'CUSTOM:1');

    expect(fixture.alerts).toHaveLength(1);
    expect(fixture.alerts[0].alertType).toBe('error');
    expect(fixture.alerts[0].alertMessage).toBe('emailConnector.mailBox.list.drawer.move.email.error|1');
  });

  it('says nothing at all when a moved row has no Message-ID to be found again by', async () => {
    fixture = await mountDrawer([row(1, '<a@host>'), row(2, null)], {});

    await fixture.wrapper.vm.moveEmails([1, 2], 'CUSTOM:1');

    expect(fixture.service.moveEmails).toHaveBeenCalledWith([1, 2], 'INBOX', 'CUSTOM:1');
    expect(fixture.alerts).toHaveLength(0);
  });

  it('a batch from two folders goes back to each of them, one request per origin', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.setData({ searchServerResults: [{ mailRemoteId: 9, mailHeaderId: '<s@host>', folder: 'SENT' }] });

    await fixture.wrapper.vm.moveEmails([1, 9], 'CUSTOM:1');
    await fixture.alerts[0].alertLinkCallback();

    expect(fixture.service.moveEmails).toHaveBeenCalledWith([1], 'INBOX', 'CUSTOM:1');
    expect(fixture.service.moveEmails).toHaveBeenCalledWith([9], 'SENT', 'CUSTOM:1');
    expect(fixture.alerts[0].alertMessage).toBe('emailConnector.mailBox.list.drawer.move.emails.success|2|Factures');
    expect(fixture.service.undoMoveEmails).toHaveBeenCalledWith(['<a@host>'], 'CUSTOM:1', 'INBOX');
    expect(fixture.service.undoMoveEmails).toHaveBeenCalledWith(['<s@host>'], 'CUSTOM:1', 'SENT');
  });

  it('a move request the server rejected outright takes the error toast and offers no Undo', async () => {
    fixture = await mountDrawer([row(1, '<a@host>'), row(2, '<b@host>')], {});
    fixture.service.moveEmails.mockImplementation(() => Promise.reject(new Error('Error when moving emails')));

    await fixture.wrapper.vm.moveEmails([1, 2], 'CUSTOM:1');

    expect(fixture.alerts).toHaveLength(1);
    expect(fixture.alerts[0].alertType).toBe('error');
    expect(fixture.alerts[0].alertMessage).toBe('emailConnector.mailBox.list.drawer.move.emails.error|2');
  });

  it('a built-in destination is named through its translation key', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});

    await fixture.wrapper.vm.moveEmails([1], 'ARCHIVE');

    expect(fixture.alerts[0].alertMessage)
      .toBe('emailConnector.mailBox.list.drawer.move.email.success|emailConnector.mailBox.list.drawer.folder.archive');
  });

  it('the listing shows its loading state while the Undo runs, and a failed reload does not escape', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    fixture.service.getEmailBox.mockImplementation(() => Promise.reject(new Error('reload refused')));
    let seenLoading = false;
    fixture.service.undoMoveEmails.mockImplementation(() => {
      seenLoading = fixture.wrapper.vm.loading;
      return Promise.resolve({ failedUndos: 0 });
    });

    await expect(fixture.alerts[0].alertLinkCallback()).resolves.toBeNull();

    expect(seenLoading).toBe(true);
    expect(fixture.wrapper.vm.loading).toBe(false);
  });

  it('the Undo is single-shot and closes its toast: a second click sends nothing', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    const closed = jest.fn();
    document.addEventListener('close-alert-message', closed);
    try {
      const first = fixture.alerts[0].alertLinkCallback();
      await fixture.alerts[0].alertLinkCallback();
      await first;
      await fixture.alerts[0].alertLinkCallback();
    } finally {
      document.removeEventListener('close-alert-message', closed);
    }

    expect(fixture.service.undoMoveEmails).toHaveBeenCalledTimes(1);
    expect(closed).toHaveBeenCalledTimes(1);
  });

  it('an Undo the server could not honour takes the error toast', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], { undoMoveEmails: { failedUndos: 1 } });
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');

    await fixture.alerts[0].alertLinkCallback();

    expect(fixture.alerts).toHaveLength(2);
    expect(fixture.alerts[1].alertType).toBe('error');
    expect(fixture.alerts[1].alertMessage).toBe('emailConnector.mailBox.list.drawer.undoMove.email.error|1');
  });
});
