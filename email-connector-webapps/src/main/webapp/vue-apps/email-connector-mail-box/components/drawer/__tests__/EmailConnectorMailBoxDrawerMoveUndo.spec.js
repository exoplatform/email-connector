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
//
// EXO-89963 — the Undo answers on the move-back and the server re-reads the folder in
// the background, so the drawer puts the rows back itself, at once. The pins below hold
// the honesty of that: a row is back before the server answers, and OUT again the moment
// the server says it did not go back; a remembered row gives way to the server's own.
//
// EXO-89966 — the same snapshot, pointed at the DESTINATION of a move: the server
// re-reads the folder the messages went to in the background, and the drawer shows them
// there at once, inert, until it does. Same honesty: a move the server refused, in whole
// or in part, leaves no row in a folder the messages never reached.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerContent from '../EmailConnectorMailBoxDrawerContent.vue';
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
      // The Undo arms the listing's poll; a real interval must not outlive the test.
      wrapper.vm.stopAutoRefresh();
      wrapper.destroy();
    },
  };
}

/**
 * One listed inbox row.
 *
 * @param {Number} mailRemoteId the IMAP UID
 * @param {String} mailHeaderId the Message-ID, or null for a row without one
 * @param {String} receivedDate when it arrived, for the tests about ordering
 * @returns {Object} the row
 */
function row(mailRemoteId, mailHeaderId, receivedDate) {
  return { mailRemoteId, mailHeaderId, folder: 'INBOX', subject: `mail ${mailRemoteId}`, receivedDate };
}

/**
 * The UIDs the drawer lists, in order.
 *
 * @param {Object} fixture the mounted drawer
 * @returns {Array<Number>} the listed UIDs
 */
function listedIds(fixture) {
  return fixture.wrapper.vm.emails.map(email => email.mailRemoteId);
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

  it('the Undo moves the messages back by Message-ID, into the folder they came from, and puts the row back at once', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    expect(listedIds(fixture)).toEqual([]);

    await fixture.alerts[0].alertLinkCallback();

    expect(fixture.service.undoMoveEmails).toHaveBeenCalledWith(['<a@host>'], 'CUSTOM:1', 'INBOX');
    expect(listedIds(fixture)).toEqual([1]);
    // A clean undo says nothing: the row coming back says it.
    expect(fixture.alerts).toHaveLength(1);
  });

  it('the row is back before the server answers, and stays once it has (EXO-89963)', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    let answer;
    fixture.service.undoMoveEmails.mockImplementation(() => new Promise(resolve => answer = resolve));

    const undone = fixture.alerts[0].alertLinkCallback();

    expect(listedIds(fixture)).toEqual([1]);
    answer({ failedUndos: 0 });
    await undone;
    expect(listedIds(fixture)).toEqual([1]);
  });

  it('the Undo does not reload on its own: it arms the listing\'s poll for the background re-read', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    fixture.service.getEmailBox.mockClear();

    await fixture.alerts[0].alertLinkCallback();

    expect(fixture.service.getEmailBox).not.toHaveBeenCalled();
    expect(fixture.wrapper.vm.refreshInterval).toBeTruthy();
    expect(fixture.wrapper.vm.refreshWatchDeadline).toBeGreaterThan(Date.now());
  });

  it('a remembered row gives way to the server\'s own once a reload lists the message again, and the poll ends', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    await fixture.alerts[0].alertLinkCallback();
    // The re-read gave the message a new UID in the inbox; the Message-ID is what it kept.
    fixture.service.getEmailBox.mockImplementation(() => Promise.resolve({ emails: [row(2, '<a@host>')], folders: FOLDERS, emailSyncStatus: 'SUCCESS' }));

    await fixture.wrapper.vm.loadEmailBox();

    expect(listedIds(fixture)).toEqual([2]);
    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
    expect(fixture.wrapper.vm.refreshWatchDeadline).toBeNull();
    expect(fixture.wrapper.vm.refreshInterval).toBeNull();
  });

  it('a remembered row keeps its place by date among the rows the server lists', async () => {
    fixture = await mountDrawer([
      row(2, '<b@host>', '2026-09-03T10:00:00Z'),
      row(3, '<c@host>', '2026-09-02T10:00:00Z'),
      row(1, '<a@host>', '2026-09-01T10:00:00Z'),
    ], {});
    await fixture.wrapper.vm.moveEmails([3], 'CUSTOM:1');
    expect(listedIds(fixture)).toEqual([2, 1]);

    await fixture.alerts[0].alertLinkCallback();

    expect(listedIds(fixture)).toEqual([2, 3, 1]);
  });

  it('a remembered row belongs to the folder it went back to: another folder\'s listing does not show it', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    await fixture.alerts[0].alertLinkCallback();

    await fixture.wrapper.setData({ currentFolder: 'SENT' });

    expect(listedIds(fixture)).toEqual([]);
    await fixture.wrapper.setData({ currentFolder: 'INBOX' });
    expect(listedIds(fixture)).toEqual([1]);
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

  it('an Undo request the server rejected outright takes the row out again, takes the error toast, and nothing escapes', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    fixture.service.undoMoveEmails.mockImplementation(() => Promise.reject(new Error('Error when undoing the move')));

    await expect(fixture.alerts[0].alertLinkCallback()).resolves.toBeUndefined();

    expect(listedIds(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
    expect(fixture.wrapper.vm.refreshInterval).toBeNull();
    expect(fixture.alerts).toHaveLength(2);
    expect(fixture.alerts[1].alertType).toBe('error');
    expect(fixture.alerts[1].alertMessage).toBe('emailConnector.mailBox.list.drawer.undoMove.email.error|1');
  });

  it('a group the server honoured only in part leaves the listing entirely, and polls for the ones that did go back', async () => {
    fixture = await mountDrawer([row(1, '<a@host>'), row(2, '<b@host>')], { undoMoveEmails: { failedUndos: 1 } });
    await fixture.wrapper.vm.moveEmails([1, 2], 'CUSTOM:1');

    await fixture.alerts[0].alertLinkCallback();

    expect(listedIds(fixture)).toEqual([]);
    expect(fixture.alerts[1].alertMessage).toBe('emailConnector.mailBox.list.drawer.undoMove.email.error|1');
    // Which of the two went back is not the drawer's to guess, so it polls for its
    // whole budget: a reload with nothing remembered must NOT end the watch.
    expect(fixture.wrapper.vm.refreshInterval).toBeTruthy();
    await fixture.wrapper.vm.loadEmailBox();
    expect(fixture.wrapper.vm.refreshInterval).toBeTruthy();
    expect(fixture.wrapper.vm.refreshWatchDeadline).toBeGreaterThan(Date.now());
  });

  it('a remembered row is inert: a click on it opens nothing while the server cannot address it', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    await fixture.alerts[0].alertLinkCallback();
    expect(fixture.wrapper.vm.emails[0].refreshPending).toBe(true);

    fixture.wrapper.vm.openEmailDetailContent(1);

    expect(fixture.service.getEmailByRemoteId).not.toHaveBeenCalled();
    expect(fixture.wrapper.vm.loading).toBe(false);
  });

  it('a remembered row the server never listed again is dropped once the re-read\'s budget is spent', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    await fixture.alerts[0].alertLinkCallback();
    expect(listedIds(fixture)).toEqual([1]);
    // The server never lists it again (the re-read failed, or was withdrawn): only the
    // budget can drop it.
    fixture.service.getEmailBox.mockImplementation(() => Promise.resolve({ emails: [], folders: FOLDERS, emailSyncStatus: 'SUCCESS' }));
    fixture.wrapper.vm.refreshPendingRows[0].refreshExpiresAt = Date.now() - 1;
    fixture.wrapper.vm.refreshWatchDeadline = Date.now() - 1;

    await fixture.wrapper.vm.loadEmailBox();

    expect(listedIds(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
    expect(fixture.wrapper.vm.refreshInterval).toBeNull();
  });

  it('the undo watch ending never stops the poll a running sync owns', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    await fixture.alerts[0].alertLinkCallback();
    fixture.service.getEmailBox.mockImplementation(() => Promise.resolve({ emails: [row(2, '<a@host>')], folders: FOLDERS, emailSyncStatus: 'IN_PROGRESS' }));

    await fixture.wrapper.vm.loadEmailBox();

    expect(fixture.wrapper.vm.refreshWatchDeadline).toBeNull();
    expect(fixture.wrapper.vm.syncInProgress).toBe(true);
    expect(fixture.wrapper.vm.refreshInterval).toBeTruthy();
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

  it('an Undo the server could not honour takes the error toast, and the row does NOT stay in the list', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], { undoMoveEmails: { failedUndos: 1 } });
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');

    await fixture.alerts[0].alertLinkCallback();

    expect(fixture.alerts).toHaveLength(2);
    expect(fixture.alerts[1].alertType).toBe('error');
    expect(fixture.alerts[1].alertMessage).toBe('emailConnector.mailBox.list.drawer.undoMove.email.error|1');
    expect(listedIds(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
  });
});

describe('the moved rows in their destination (EXO-89966)', () => {
  let fixture;

  afterEach(() => fixture?.teardown());

  /**
   * Lists the destination folder without reloading it, the way the folder tests above
   * do: the remembered rows are the drawer's own, so no server answer is involved.
   *
   * @param {String} folder the folder key to list
   * @returns {Promise} resolving once the listing has recomputed
   */
  function listFolder(folder) {
    return fixture.wrapper.setData({ currentFolder: folder });
  }

  it('a moved row shows in its destination at once, before the server lists it -- and is gone from its origin', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});

    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');

    expect(listedIds(fixture)).toEqual([]);
    await listFolder('CUSTOM:1');
    expect(fixture.wrapper.vm.emails).toHaveLength(1);
    expect(fixture.wrapper.vm.emails[0]).toMatchObject({ mailHeaderId: '<a@host>', folder: 'CUSTOM:1', refreshPending: true });
  });

  it('the moved row shows before the server answers the move', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    let answer;
    fixture.service.moveEmails.mockImplementation(() => new Promise(resolve => answer = resolve));

    const moved = fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');

    await listFolder('CUSTOM:1');
    expect(fixture.wrapper.vm.emails.map(email => email.mailHeaderId)).toEqual(['<a@host>']);
    answer({ failedMoves: 0 });
    await moved;
    expect(fixture.wrapper.vm.emails.map(email => email.mailHeaderId)).toEqual(['<a@host>']);
  });

  it('a moved row carries no UID the destination can hold, and no conversation it could be grouped under', async () => {
    fixture = await mountDrawer([{ ...row(5, '<a@host>', '2026-09-01T10:00:00Z'), threadId: 'thread-5', threadCount: 3 }], {});

    await fixture.wrapper.vm.moveEmails([5], 'CUSTOM:1');

    const [remembered] = fixture.wrapper.vm.refreshPendingRows;
    expect(remembered.mailRemoteId).toBe(-5);
    expect(remembered.threadId).toBeNull();
    // The destination's OWN row 5 -- another message that happens to hold that number
    // there -- is listed beside the snapshot and still opens by its UID.
    await listFolder('CUSTOM:1');
    await fixture.wrapper.setData({ emailBox: { emails: [{ ...row(5, '<z@host>', '2020-01-01T00:00:00Z'), folder: 'CUSTOM:1' }], folders: FOLDERS } });
    expect(fixture.wrapper.vm.emails.map(email => email.mailRemoteId)).toEqual([-5, 5]);
    fixture.wrapper.vm.openEmailDetailContent(5);
    expect(fixture.service.getEmailByRemoteId).toHaveBeenCalledWith(5, 'CUSTOM:1');
  });

  it('a moved row is inert: a click on it opens nothing, and select-all skips it', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    await listFolder('CUSTOM:1');
    const [remembered] = fixture.wrapper.vm.emails;
    expect(remembered.refreshPending).toBe(true);

    fixture.wrapper.vm.openEmailDetailContent(remembered.mailRemoteId);

    expect(fixture.service.getEmailByRemoteId).not.toHaveBeenCalled();
    expect(fixture.wrapper.vm.loading).toBe(false);
    const content = shallowMount(EmailConnectorMailBoxDrawerContent, {
      propsData: { emails: [remembered, { mailRemoteId: 2, mailHeaderId: '<b@host>' }], selectMode: true },
      mocks: { $t: key => key },
    });
    content.vm.onSelectAllChange(true);
    expect(content.emitted('update:selected-emails')[0][0]).toEqual([2]);
    content.destroy();
  });

  it('a failed move leaves no row in the destination, and forgets it BEFORE the error toast', async () => {
    fixture = await mountDrawer([row(1, '<a@host>'), row(2, '<b@host>')], { moveEmails: { failedMoves: 1 } });
    const pendingAtAlert = [];
    const listener = () => pendingAtAlert.push(fixture.wrapper.vm.refreshPendingRows.length);
    document.addEventListener('alert-message', listener);
    try {
      await fixture.wrapper.vm.moveEmails([1, 2], 'CUSTOM:1');
    } finally {
      document.removeEventListener('alert-message', listener);
    }

    expect(fixture.alerts).toHaveLength(1);
    expect(fixture.alerts[0].alertType).toBe('error');
    expect(pendingAtAlert).toEqual([0]);
    await listFolder('CUSTOM:1');
    expect(listedIds(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
  });

  it('a move request the server rejected outright leaves no row in the destination either', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    fixture.service.moveEmails.mockImplementation(() => Promise.reject(new Error('Error when moving emails')));

    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');

    await listFolder('CUSTOM:1');
    expect(listedIds(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
  });

  it('a batch from two folders reverts per request: the origin the server refused forgets its rows only', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.setData({ searchServerResults: [{ mailRemoteId: 9, mailHeaderId: '<s@host>', folder: 'SENT' }] });
    fixture.service.moveEmails.mockImplementation((ids, folder) => Promise.resolve({ failedMoves: folder === 'SENT' ? 1 : 0 }));

    await fixture.wrapper.vm.moveEmails([1, 9], 'CUSTOM:1');

    await listFolder('CUSTOM:1');
    expect(fixture.wrapper.vm.emails.map(email => email.mailHeaderId)).toEqual(['<a@host>']);
  });

  it('opening the destination arms the poll, and the server\'s own row replaces the remembered one and ends it', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    // The move armed nothing: the folder on screen holds no remembered row.
    expect(fixture.wrapper.vm.refreshWatchDeadline).toBeNull();
    fixture.service.getEmailBox.mockImplementation(() => Promise.resolve({ emails: [], folders: FOLDERS, emailSyncStatus: 'SUCCESS' }));

    await listFolder('CUSTOM:1');
    await fixture.wrapper.vm.loadEmailBox();

    expect(fixture.wrapper.vm.emails.map(email => email.mailHeaderId)).toEqual(['<a@host>']);
    expect(fixture.wrapper.vm.refreshInterval).toBeTruthy();
    expect(fixture.wrapper.vm.refreshWatchDeadline).toBeGreaterThan(Date.now());
    // The re-read landed: the message is listed under the UID the COPY gave it.
    fixture.service.getEmailBox.mockImplementation(() => Promise.resolve({ emails: [{ ...row(7, '<a@host>'), folder: 'CUSTOM:1' }], folders: FOLDERS, emailSyncStatus: 'SUCCESS' }));
    await fixture.wrapper.vm.loadEmailBox();
    expect(listedIds(fixture)).toEqual([7]);
    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
    expect(fixture.wrapper.vm.refreshWatchDeadline).toBeNull();
    expect(fixture.wrapper.vm.refreshInterval).toBeNull();
  });

  it('a moved row the server never listed is dropped once the re-read\'s budget is spent', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');
    fixture.service.getEmailBox.mockImplementation(() => Promise.resolve({ emails: [], folders: FOLDERS, emailSyncStatus: 'SUCCESS' }));
    fixture.wrapper.vm.refreshPendingRows[0].refreshExpiresAt = Date.now() - 1;

    await listFolder('CUSTOM:1');
    await fixture.wrapper.vm.loadEmailBox();

    expect(listedIds(fixture)).toEqual([]);
    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
    expect(fixture.wrapper.vm.refreshInterval).toBeNull();
  });

  it('a row with no Message-ID is not remembered: nothing could ever recognise the server\'s row for it', async () => {
    fixture = await mountDrawer([row(1, '<a@host>'), row(2, null)], {});

    await fixture.wrapper.vm.moveEmails([1, 2], 'CUSTOM:1');

    expect(fixture.wrapper.vm.refreshPendingRows.map(remembered => remembered.mailHeaderId)).toEqual(['<a@host>']);
  });

  it('a batch filed into the folder on screen shows there at once and the move arms the poll itself', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.setData({ searchServerResults: [{ mailRemoteId: 9, mailHeaderId: '<s@host>', folder: 'SENT' }] });

    await fixture.wrapper.vm.moveEmails([9], 'INBOX');

    expect(fixture.service.moveEmails).toHaveBeenCalledWith([9], 'SENT', 'INBOX');
    expect(fixture.wrapper.vm.emails.map(email => email.mailHeaderId)).toEqual(['<a@host>', '<s@host>']);
    expect(fixture.wrapper.vm.refreshWatchDeadline).toBeGreaterThan(Date.now());
    expect(fixture.wrapper.vm.refreshInterval).toBeTruthy();
  });

  it('a batch filed into the folder on screen that the server honoured in part polls for the whole budget', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], { moveEmails: { failedMoves: 1 } });
    await fixture.wrapper.setData({ searchServerResults: [
      { mailRemoteId: 9, mailHeaderId: '<s@host>', folder: 'SENT' },
      { mailRemoteId: 10, mailHeaderId: '<t@host>', folder: 'SENT' },
    ] });

    await fixture.wrapper.vm.moveEmails([9, 10], 'INBOX');

    // Which of the two moved is not the drawer's to guess: no remembered row, but a poll.
    expect(fixture.wrapper.vm.refreshPendingRows).toEqual([]);
    expect(fixture.wrapper.vm.refreshWatchUntilDeadline).toBe(true);
    expect(fixture.wrapper.vm.refreshWatchDeadline).toBeGreaterThan(Date.now());
    expect(fixture.wrapper.vm.refreshInterval).toBeTruthy();
  });

  it('the Undo takes the moved row out of the destination and puts it back in its origin', async () => {
    fixture = await mountDrawer([row(1, '<a@host>')], {});
    await fixture.wrapper.vm.moveEmails([1], 'CUSTOM:1');

    await fixture.alerts[0].alertLinkCallback();

    expect(listedIds(fixture)).toEqual([1]);
    await listFolder('CUSTOM:1');
    expect(listedIds(fixture)).toEqual([]);
  });
});
