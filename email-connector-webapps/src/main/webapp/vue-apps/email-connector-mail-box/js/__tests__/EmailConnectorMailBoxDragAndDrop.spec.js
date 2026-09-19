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

// EXO-90421 -- what a drag carries and where it may land: the rules of "Move to...",
// Delete and "Mark as spam", never looser.

import {
  DRAG_MIME,
  canDropOn,
  dragLabel,
  dragPayloadOfRow,
  dragPayloadOfSearchHit,
  folderDropAction,
  hasDragPayload,
  startDrag,
} from '../EmailConnectorMailBoxDragAndDrop.js';
import { linkEmailsToCategory, unlinkEmailsFromCategory } from '../EmailConnectorMailBoxService.js';

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'SENT', type: 'BUILT_IN', syncEnabled: true },
  { key: 'DRAFTS', type: 'BUILT_IN', syncEnabled: true },
  { key: 'ARCHIVE', type: 'BUILT_IN', syncEnabled: true },
  { key: 'TRASH', type: 'BUILT_IN', syncEnabled: true },
  { key: 'JUNK', type: 'BUILT_IN', syncEnabled: true },
  { key: 'ALL_MAIL', type: 'BUILT_IN', syncEnabled: true },
  { key: 'CUSTOM:1', type: 'CUSTOM', displayName: 'Factures', syncEnabled: true },
  { key: 'CUSTOM:2', type: 'CUSTOM', displayName: 'Old', syncEnabled: true, missing: true },
  { key: 'CUSTOM:3', type: 'CUSTOM', displayName: 'Unsynced', syncEnabled: false },
];

/**
 * A listed message.
 *
 * @param {Number} mailRemoteId the IMAP UID
 * @param {String} folder the folder it is numbered in
 * @param {Object} extra further fields
 * @returns {Object} the row
 */
function message(mailRemoteId, folder = 'INBOX', extra = {}) {
  return { mailRemoteId, folder, ...extra };
}

/**
 * A drag event whose dataTransfer records what it is given.
 *
 * @param {Array<String>} types the types the transfer carries
 * @returns {Object} the event
 */
function dragEvent(types = []) {
  const data = {};
  return {
    dataTransfer: {
      types,
      data,
      effectAllowed: 'all',
      setData: jest.fn((type, value) => data[type] = value),
      setDragImage: jest.fn(),
    },
  };
}

describe('what a dragged row carries (EXO-90421)', () => {
  it('a lone row carries itself, in its folder', () => {
    expect(dragPayloadOfRow({ email: message(7, 'CUSTOM:1') })).toEqual({ folder: 'CUSTOM:1', ids: [7] });
    expect(dragPayloadOfRow({ email: message(7, null) })).toEqual({ folder: 'INBOX', ids: [7] });
  });

  it('a conversation carries only its messages of the row\'s folder, never its raw ids', () => {
    const email = message(3, 'INBOX');
    const thread = { mailRemoteIds: [3, 9, 12], emails: [email, message(9, 'ARCHIVE'), message(12, 'INBOX')] };

    expect(dragPayloadOfRow({ email, thread })).toEqual({ folder: 'INBOX', ids: [3, 12] });
  });

  it('a selected row carries the whole selection', () => {
    const payload = dragPayloadOfRow({
      email: message(2),
      selectMode: true,
      selectedEmails: ['INBOX:1', 'INBOX:2', 'INBOX:5'],
    });

    expect(payload).toEqual({ folder: 'INBOX', ids: [1, 2, 5] });
  });

  it('an unselected row of a running selection carries only itself', () => {
    const payload = dragPayloadOfRow({ email: message(9), selectMode: true, selectedEmails: ['INBOX:1', 'INBOX:2'] });

    expect(payload).toEqual({ folder: 'INBOX', ids: [9] });
  });

  it('a selection across folders is not dragged, as "Move to..." is disabled for it', () => {
    const payload = dragPayloadOfRow({
      email: message(2),
      selectMode: true,
      selectedEmails: ['INBOX:2', 'ARCHIVE:2'],
    });

    expect(payload).toBeNull();
  });

  it.each([
    ['a draft of the Drafts folder', message(1, 'DRAFTS')],
    ['a draft row', message(1, 'INBOX', { draftLocalId: 'local-1' })],
    ['a Trash row', message(1, 'TRASH')],
    ['a Spam row', message(1, 'JUNK')],
    ['a row the server has not listed yet', message(1, 'INBOX', { refreshPending: true })],
  ])('%s is not dragged', (label, email) => {
    expect(dragPayloadOfRow({ email })).toBeNull();
  });

  it('a search hit carries itself in the folder the server found it, and not out of Trash or Spam', () => {
    expect(dragPayloadOfSearchHit({ mailRemoteId: 4, folder: 'ARCHIVE' })).toEqual({ folder: 'ARCHIVE', ids: [4] });
    expect(dragPayloadOfSearchHit({ mailRemoteId: 4 })).toEqual({ folder: 'INBOX', ids: [4] });
    expect(dragPayloadOfSearchHit({ mailRemoteId: 4, folder: 'TRASH' })).toBeNull();
    expect(dragPayloadOfSearchHit({ mailRemoteId: 4, folder: 'JUNK' })).toBeNull();
    expect(dragPayloadOfSearchHit({ mailRemoteId: 4, folder: 'DRAFTS' })).toBeNull();
  });

  it('an All Mail hit is not dragged: the server moves nothing out of it and categorizes nothing in it', () => {
    expect(dragPayloadOfSearchHit({ mailRemoteId: 4, folder: 'ALL_MAIL' })).toBeNull();
  });
});

describe('where dragged mail may land (EXO-90421)', () => {
  const fromInbox = { folder: 'INBOX', ids: [1, 2] };

  it('Inbox, Archive and the synced custom folders are moves, exactly as "Move to..." emits', () => {
    expect(folderDropAction(FOLDERS, fromInbox, 'CUSTOM:1')).toEqual({ event: 'move-email', args: [[1, 2], 'CUSTOM:1', 'INBOX'] });
    expect(folderDropAction(FOLDERS, fromInbox, 'ARCHIVE')).toEqual({ event: 'move-email', args: [[1, 2], 'ARCHIVE', 'INBOX'] });
    expect(folderDropAction(FOLDERS, { folder: 'CUSTOM:1', ids: [4] }, 'INBOX'))
      .toEqual({ event: 'move-email', args: [[4], 'INBOX', 'CUSTOM:1'] });
  });

  it('the Trash deletes and the Spam marks as spam, with the source folder, as the row menu emits', () => {
    expect(folderDropAction(FOLDERS, fromInbox, 'TRASH')).toEqual({ event: 'delete-email', args: [[1, 2], 'INBOX'] });
    expect(folderDropAction(FOLDERS, { folder: 'SENT', ids: [3] }, 'JUNK')).toEqual({ event: 'junk-email', args: [[3], 'SENT'] });
  });

  it('the Trash and the Spam take nothing the mailbox does not list', () => {
    const withoutThem = FOLDERS.filter(folder => !['TRASH', 'JUNK'].includes(folder.key));
    expect(canDropOn(withoutThem, fromInbox, 'TRASH')).toBe(false);
    expect(canDropOn(withoutThem, fromInbox, 'JUNK')).toBe(false);
  });

  it.each(['INBOX', 'DRAFTS', 'SENT', 'ALL_MAIL', 'CUSTOM:2', 'CUSTOM:3', 'CUSTOM:9'])('refuses a drop from the Inbox on %s', target => {
    expect(canDropOn(FOLDERS, fromInbox, target)).toBe(false);
  });

  it('refuses a drop out of a folder whose mail those actions refuse, and with nothing dragged', () => {
    expect(canDropOn(FOLDERS, { folder: 'DRAFTS', ids: [1] }, 'TRASH')).toBe(false);
    expect(canDropOn(FOLDERS, { folder: 'TRASH', ids: [1] }, 'JUNK')).toBe(false);
    expect(canDropOn(FOLDERS, { folder: 'JUNK', ids: [1] }, 'TRASH')).toBe(false);
    expect(canDropOn(FOLDERS, null, 'CUSTOM:1')).toBe(false);
    expect(canDropOn(FOLDERS, { folder: 'INBOX', ids: [] }, 'CUSTOM:1')).toBe(false);
  });
});

describe('the drag itself (EXO-90421)', () => {
  it('writes a move under the mailbox\'s own type, and pictures it with the message count', () => {
    const event = dragEvent();

    startDrag(event, { folder: 'INBOX', ids: [1, 2] }, 'Move 2 emails');

    expect(event.dataTransfer.effectAllowed).toBe('move');
    expect(JSON.parse(event.dataTransfer.data[DRAG_MIME])).toEqual({ folder: 'INBOX', ids: [1, 2] });
    const [image] = event.dataTransfer.setDragImage.mock.calls[0];
    expect(image.textContent).toBe('Move 2 emails');
    expect(document.body.contains(image)).toBe(true);
  });

  it('counts the messages in the picture', () => {
    const t = (key, params) => (params ? `${key}|${params[0]}` : key);
    expect(dragLabel(1, t)).toBe('emailConnector.mailBox.list.drawer.drag.email');
    expect(dragLabel(3, t)).toBe('emailConnector.mailBox.list.drawer.drag.emails|3');
  });

  it('tells mail of this mailbox from a file or a text', () => {
    expect(hasDragPayload(dragEvent([DRAG_MIME]))).toBe(true);
    expect(hasDragPayload(dragEvent(['Files']))).toBe(false);
    expect(hasDragPayload(dragEvent(['text/plain']))).toBe(false);
    expect(hasDragPayload({})).toBe(false);
  });
});

describe('the category assignment a drop sends (EXO-90421)', () => {
  afterEach(() => delete global.fetch);

  it('names the folder the UIDs are numbered in, and leaves the inbox\'s request as it was', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({ linked: 1 }) }));

    await linkEmailsToCategory([4], 11, 'CUSTOM:1');
    await linkEmailsToCategory([4], 11, 'INBOX');
    await linkEmailsToCategory([4], 11);

    expect(global.fetch.mock.calls.map(([url]) => url)).toEqual([
      '/email-connector/rest/email-box/categories/11?folder=CUSTOM%3A1',
      '/email-connector/rest/email-box/categories/11',
      '/email-connector/rest/email-box/categories/11',
    ]);
    expect(global.fetch.mock.calls[0][1]).toMatchObject({ method: 'POST', body: '[4]' });
  });

  it('removes a category in the folder named too, the inbox\'s request unchanged', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ ok: true, json: () => Promise.resolve({ unlinked: 1 }) }));

    await unlinkEmailsFromCategory([4], 11, 'ARCHIVE');
    await unlinkEmailsFromCategory([4], 11);

    expect(global.fetch.mock.calls.map(([url, init]) => [url, init.method])).toEqual([
      ['/email-connector/rest/email-box/categories/11?folder=ARCHIVE', 'DELETE'],
      ['/email-connector/rest/email-box/categories/11', 'DELETE'],
    ]);
  });
});
