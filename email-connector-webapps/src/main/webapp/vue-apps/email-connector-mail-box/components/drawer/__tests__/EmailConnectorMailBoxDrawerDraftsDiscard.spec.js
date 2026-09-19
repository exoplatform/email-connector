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

// EXO-90438 — the mailbox drawer's side of the Drafts Discard: one request per draft,
// one re-read of the list when they have all answered, and a count the user is told
// about.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'DRAFTS', type: 'BUILT_IN', syncEnabled: true },
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
 * @returns {Object} {wrapper, service, alerts, teardown}
 */
async function mountDrawer(emails, answers = {}, currentFolder = 'DRAFTS') {
  const service = serviceStub({
    folderLabel: emailConnectorMailBoxService.folderLabel,
    isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
    isDraftsFolder: emailConnectorMailBoxService.isDraftsFolder,
    discardDrafts: jest.fn(() => answers.discardDrafts || Promise.resolve({ discarded: 0, failed: 0, conflicted: 0 })),
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
  await wrapper.setData({ emailBox: { emails, folders: FOLDERS }, currentFolder, emailBoxDrawer: true });
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

/**
 * A draft row as the Drafts listing holds one.
 *
 * @param {Number} mailRemoteId the UID of its copy on the mail server
 * @param {String} draftLocalId the composer's own handle on it
 * @returns {Object} the row
 */
function draftRow(mailRemoteId, draftLocalId) {
  return { mailRemoteId, draftLocalId, folder: 'DRAFTS', subject: `draft ${draftLocalId}`, read: true };
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

describe('the drawer discards the drafts it is handed and says what became of them (EXO-90438)', () => {
  let fixture;
  afterEach(() => fixture?.teardown());

  it('sends the local ids to the per-draft discard, and re-reads the list once', async () => {
    fixture = await mountDrawer([draftRow(11, 'local-1'), draftRow(12, 'local-2')],
      { discardDrafts: Promise.resolve({ discarded: 2, failed: 0, conflicted: 0 }) });
    fixture.service.getEmailBox.mockClear();

    fixture.wrapper.vm.discardDrafts(['local-1', 'local-2']);
    await flush();

    expect(fixture.service.discardDrafts).toHaveBeenCalledWith(['local-1', 'local-2']);
    expect(fixture.service.getEmailBox).toHaveBeenCalledTimes(1);
    expect(fixture.alerts).toEqual([]);
  });

  it('refreshes through refresh-email-box, the one signal an open conversation listens to', async () => {
    // Calling loadEmailBox() here instead would refresh the listing and leave a
    // conversation open beside it showing the draft that was just thrown away — with a
    // Resume button that writes it back under the same local id.
    fixture = await mountDrawer([draftRow(11, 'local-1')],
      { discardDrafts: Promise.resolve({ discarded: 1, failed: 0, conflicted: 0 }) });
    const emitted = [];
    const emit = fixture.wrapper.vm.$root.$emit.bind(fixture.wrapper.vm.$root);
    fixture.wrapper.vm.$root.$emit = (...args) => {
      emitted.push(args[0]);
      return emit(...args);
    };

    fixture.wrapper.vm.discardDrafts(['local-1']);
    await flush();

    expect(emitted).toContain('refresh-email-box');
  });

  it('a partial failure is told as a ratio, not as a bare count', async () => {
    fixture = await mountDrawer([draftRow(11, 'local-1')],
      { discardDrafts: Promise.resolve({ discarded: 4, failed: 2, conflicted: 0 }) });

    fixture.wrapper.vm.discardDrafts(['a', 'b', 'c', 'd', 'e', 'f']);
    await flush();

    expect(fixture.alerts.pop().alertMessage)
      .toBe('emailConnector.mailBox.list.drawer.discard.partial.error|4|6');
  });

  it('a batch where nothing went is told as a failure count', async () => {
    fixture = await mountDrawer([draftRow(11, 'local-1')],
      { discardDrafts: Promise.resolve({ discarded: 0, failed: 2, conflicted: 0 }) });

    fixture.wrapper.vm.discardDrafts(['a', 'b']);
    await flush();

    expect(fixture.alerts.pop().alertMessage)
      .toBe('emailConnector.mailBox.list.drawer.discard.emails.error|2');
  });

  it('a draft the server refused with a 409 is named apart: its mail is already on its way', async () => {
    fixture = await mountDrawer([draftRow(11, 'local-1')],
      { discardDrafts: Promise.resolve({ discarded: 1, failed: 0, conflicted: 1 }) });

    fixture.wrapper.vm.discardDrafts(['local-1', 'local-2']);
    await flush();

    const message = fixture.alerts.pop().alertMessage;
    expect(message).toContain('emailConnector.mailBox.list.drawer.discard.partial.error|1|2');
    expect(message).toContain('emailConnector.mailBox.list.drawer.discard.sending.error|1');
  });

  it('a draft refused ONLY because it is being sent says that and nothing else', async () => {
    fixture = await mountDrawer([draftRow(11, 'local-1')],
      { discardDrafts: Promise.resolve({ discarded: 0, failed: 0, conflicted: 1 }) });

    fixture.wrapper.vm.discardDrafts(['local-1']);
    await flush();

    // Not "1 draft cannot be discarded" in front of it: that would be a second
    // sentence about the same draft.
    expect(fixture.alerts.pop().alertMessage)
      .toBe('emailConnector.mailBox.list.drawer.discard.sending.error|1');
  });

  it('the failure count counts the failures, not the ones that are on their way out', async () => {
    fixture = await mountDrawer([draftRow(11, 'local-1')],
      { discardDrafts: Promise.resolve({ discarded: 0, failed: 2, conflicted: 1 }) });

    fixture.wrapper.vm.discardDrafts(['a', 'b', 'c']);
    await flush();

    const message = fixture.alerts.pop().alertMessage;
    expect(message).toContain('emailConnector.mailBox.list.drawer.discard.emails.error|2');
    expect(message).toContain('emailConnector.mailBox.list.drawer.discard.sending.error|1');
  });

  it('the list is re-read even when the batch failed, so the rows on screen are the server\'s', async () => {
    fixture = await mountDrawer([draftRow(11, 'local-1')],
      { discardDrafts: Promise.resolve({ discarded: 0, failed: 1, conflicted: 0 }) });
    fixture.service.getEmailBox.mockClear();

    fixture.wrapper.vm.discardDrafts(['local-1']);
    await flush();

    expect(fixture.service.getEmailBox).toHaveBeenCalledTimes(1);
  });
});

describe('the drawer keys a selected draft by the local id the row named (EXO-90438)', () => {
  let fixture;
  afterEach(() => fixture?.teardown());

  it('two unsent drafts make two keys, not one null the pair would share', async () => {
    // The drawer's own half of the round trip: the row emits one select-email per
    // message naming its local id (EmailConnectorMailBoxDrawerListItem.emitSelect), and
    // THIS is where that becomes a key. Read the event's UID alone and both rows key to
    // DRAFTS:null: ticking one lights the other, the header still says "1 email
    // selected", and the bulk Discard throws away a draft nobody ticked -- irreversibly,
    // a discard being no move to the Trash.
    fixture = await mountDrawer([draftRow(null, 'local-1'), draftRow(null, 'local-2')]);

    fixture.wrapper.vm.$root.$emit('select-email', { emailId: null, draftLocalId: 'local-1', folder: 'DRAFTS', selected: true });
    fixture.wrapper.vm.$root.$emit('select-email', { emailId: null, draftLocalId: 'local-2', folder: 'DRAFTS', selected: true });

    expect(fixture.wrapper.vm.selectedEmails).toEqual(['DRAFTS:DRAFT-local-1', 'DRAFTS:DRAFT-local-2']);
  });

  it('unticking one draft leaves the other ticked', async () => {
    fixture = await mountDrawer([draftRow(null, 'local-1'), draftRow(null, 'local-2')]);
    fixture.wrapper.vm.$root.$emit('select-email', { emailId: null, draftLocalId: 'local-1', folder: 'DRAFTS', selected: true });
    fixture.wrapper.vm.$root.$emit('select-email', { emailId: null, draftLocalId: 'local-2', folder: 'DRAFTS', selected: true });

    fixture.wrapper.vm.$root.$emit('select-email', { emailId: null, draftLocalId: 'local-1', folder: 'DRAFTS', selected: false });

    expect(fixture.wrapper.vm.selectedEmails).toEqual(['DRAFTS:DRAFT-local-2']);
  });

  it('a mail is keyed by its folder and UID, as it was', async () => {
    fixture = await mountDrawer([{ mailRemoteId: 5, folder: 'INBOX', read: true }], {}, 'INBOX');

    fixture.wrapper.vm.$root.$emit('select-email', { emailId: 5, folder: 'INBOX', selected: true });

    expect(fixture.wrapper.vm.selectedEmails).toEqual(['INBOX:5']);
  });
});
