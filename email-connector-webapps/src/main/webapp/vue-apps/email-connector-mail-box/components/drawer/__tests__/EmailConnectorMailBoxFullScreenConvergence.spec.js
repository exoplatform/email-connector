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

// EXO-90415 — one full-screen layout, the mailbox drawer's. The mail drawer's expand
// button hands its mail over to the mailbox drawer, which expands on it, and then
// closes; it never widens itself any more. Pinned with both drawers under one root, the
// way the page holds them, and an exo-drawer stand-in that keeps the platform drawer's
// expand and close contract (expand-updated on every change, closed on every close,
// the confirmation asked by close() and skipped by closeEffectively()).

import Vue from 'vue';
import { mount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerListItemDetail from '../EmailConnectorMailBoxDrawerListItemDetail.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

// The drawers' own children are rendered as plain tags: these pins are about the two
// drawers talking to each other, not about what they contain.
Vue.config.ignoredElements.push(/^email-connector-/, 'categories-filter', 'exo-confirm-dialog');

const FOLDERS = [
  { key: 'INBOX', type: 'BUILT_IN', syncEnabled: true },
  { key: 'ARCHIVE', type: 'BUILT_IN', syncEnabled: true },
];

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

/**
 * A service whose every function answers an empty promise, the ones under test supplied.
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
 * A stand-in for the platform's exo-drawer: its expand flag, its expand-updated event,
 * its close with or without the confirmation, and every named slot rendered.
 *
 * @returns {Object} the component
 */
function exoDrawerStub() {
  return {
    name: 'exo-drawer',
    props: {
      value: { type: Boolean, default: false },
      allowExpand: { type: Boolean, default: false },
      confirmClose: { type: null, default: null },
      drawerWidth: { type: String, default: '420px' },
    },
    data: () => ({ expand: false, confirmationAsked: false }),
    watch: {
      expand(expand) {
        this.$emit('expand-updated', expand);
      },
    },
    methods: {
      toogleExpand() {
        if (this.allowExpand) {
          this.expand = !this.expand;
        }
      },
      close() {
        if (this.confirmClose) {
          this.confirmationAsked = true;
        } else {
          this.closeEffectively();
        }
      },
      closeEffectively() {
        this.$emit('input', false);
        this.$emit('closed');
      },
      resetFilter() {
        return null;
      },
    },
    render(createElement) {
      return createElement('div', Object.keys(this.$slots)
        .map(name => createElement('div', { attrs: { 'data-slot': name } }, this.$slots[name])));
    },
  };
}

/**
 * A message.
 *
 * @param {Number} mailRemoteId its UID
 * @param {String} folder the folder it is numbered in
 * @param {Object} extra further fields
 * @returns {Object} the message
 */
function message(mailRemoteId, folder = 'INBOX', extra = {}) {
  return {
    mailRemoteId,
    folder,
    mailHeaderId: `<${folder}-${mailRemoteId}@host>`,
    threadId: `<${folder}-${mailRemoteId}@host>`,
    read: true,
    subject: `${folder} ${mailRemoteId}`,
    sender: { name: 'Alice', address: 'alice@host' },
    receivedDate: new Date(2026, 8, 30 - mailRemoteId).toISOString(),
    categoryIds: [],
    ...extra,
  };
}

/**
 * The server's full copy of a message: what the reader holds once its request answered.
 *
 * @param {Object} row the list row
 * @returns {Object} the full copy
 */
function full(row) {
  return { ...row, to: [], cc: [], content: { body: `<p>body ${row.mailRemoteId}</p>` } };
}

/**
 * Mounts the mailbox drawer, open and narrow over a listed inbox, and the mail drawer
 * beside it, closed, under one root.
 *
 * @param {Array} emails the listed rows
 * @param {Object} overrides service functions
 * @returns {Promise<Object>} {host, mailbox, mail, service, opened, teardown}
 */
async function mountBoth(emails, overrides = {}) {
  const service = serviceStub({
    folderLabel: emailConnectorMailBoxService.folderLabel,
    isReadOnlyFolder: emailConnectorMailBoxService.isReadOnlyFolder,
    isListingRow: emailConnectorMailBoxService.isListingRow,
    settleListingRow: emailConnectorMailBoxService.settleListingRow,
    getEmailByRemoteId: jest.fn((mailRemoteId, folder) => Promise.resolve(full(message(mailRemoteId, folder)))),
    getEmailBox: jest.fn(() => Promise.resolve({ emails, folders: FOLDERS, emailSyncStatus: 'SUCCESS' })),
    getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
    ...overrides,
  });
  const host = mount({
    render(createElement) {
      return createElement('div', [
        createElement(EmailConnectorMailBoxDrawer, { ref: 'mailbox' }),
        createElement(EmailConnectorMailBoxDrawerListItemDetail, { ref: 'mail' }),
      ]);
    },
  }, {
    attachTo: document.body,
    mocks: {
      $t: key => key,
      $emailConnectorMailBoxService: service,
      $emailConnectorCommonService: serviceStub({}),
      $vuetify: { breakpoint: {}, rtl: false },
    },
    stubs: { 'exo-drawer': exoDrawerStub() },
  });
  const mailbox = host.vm.$refs.mailbox;
  const mail = host.vm.$refs.mail;
  mailbox.emailBoxDrawer = true;
  mailbox.emailBox = { emails, folders: FOLDERS, emailSyncStatus: 'SUCCESS' };
  await host.vm.$nextTick();
  const opened = [];
  host.vm.$root.$on('set-opened', id => opened.push(id));
  return {
    host,
    mailbox,
    mail,
    service,
    opened,
    teardown: () => {
      mailbox.stopAutoRefresh();
      host.destroy();
      document.body.innerHTML = '';
    },
  };
}

/**
 * Presses the mail drawer's expand button and lets the mailbox drawer switch layouts
 * (its own `expanded` follows exo-drawer's 200 ms later).
 *
 * @param {Object} fixture the mounted drawers
 * @returns {Promise<void>} resolved once the full screen is on
 */
async function expandFromMail(fixture) {
  jest.useFakeTimers();
  fixture.mail.expandInMailBox();
  await fixture.host.vm.$nextTick();
  jest.advanceTimersByTime(250);
  jest.useRealTimers();
  await flush();
}

describe('the mail drawer hands its mail over to the one full-screen layout (EXO-90415)', () => {
  let fixture;

  afterEach(() => {
    jest.useRealTimers();
    fixture?.teardown();
  });

  it('never widens itself: exo-drawer\'s own expand is off, its button hands over', async () => {
    fixture = await mountBoth([message(1), message(2)]);
    fixture.host.vm.$root.$emit('open-email-detail-drawer', 2, fixture.mailbox.emails, false, null, false, false, 'INBOX');
    await flush();

    const drawer = fixture.mail.$refs.emailDetailDrawer;
    expect(drawer.allowExpand).toBe(false);
    expect(fixture.mail.canExpandInMailBox).toBe(true);
  });

  it('is not offered on a mail opened on its own, with no mailbox behind it', async () => {
    fixture = await mountBoth([message(1)]);
    fixture.host.vm.$root.$emit('open-email-detail-drawer', 9, [message(9)], false, null, true, true, 'INBOX');
    await flush();

    expect(fixture.mail.canExpandInMailBox).toBe(false);
  });

  it('opens the mailbox full screen on that mail, then closes -- no second read, no placeholder', async () => {
    fixture = await mountBoth([message(1), message(2), message(3)]);
    // Opened from its row, which holds the focus: the row the mailbox would give the
    // focus back to on a plain close.
    const clickedRow = document.createElement('div');
    clickedRow.setAttribute('data-thread-key', message(2).threadId);
    clickedRow.tabIndex = 0;
    document.body.appendChild(clickedRow);
    clickedRow.focus();
    fixture.host.vm.$root.$emit('open-email-detail-drawer', 2, fixture.mailbox.emails, false, null, false, false, 'INBOX');
    await flush();
    expect(fixture.mail.email.content).toBeTruthy();
    fixture.service.getEmailByRemoteId.mockClear();
    fixture.service.updateEmailsReadStatus.mockClear();
    const revealThreadRow = jest.spyOn(fixture.mailbox, 'revealThreadRow');

    await expandFromMail(fixture);

    // Closing on a hand-over is not a return to the narrow list: no row to focus there.
    expect(revealThreadRow).not.toHaveBeenCalled();
    expect(fixture.mailbox.$refs.emailBoxDrawer.expand).toBe(true);
    expect(fixture.mailbox.expanded).toBe(true);
    expect(fixture.mailbox.email.mailRemoteId).toBe(2);
    expect(fixture.mailbox.selectEmailPlaceHolder).toBe(false);
    // The mail drawer's full copy is shown as it is: not fetched, read or counted again,
    // and the first mail of the list is not opened over it.
    expect(fixture.service.getEmailByRemoteId).not.toHaveBeenCalled();
    expect(fixture.service.updateEmailsReadStatus).not.toHaveBeenCalled();
    expect(fixture.opened[fixture.opened.length - 1]).toBe(2);
    // The mail drawer is gone, and the mailbox takes the keys and the actions again.
    expect(fixture.mail.emailDetailDrawer).toBe(false);
    expect(fixture.host.vm.$root.isDetailDrawerActive).toBe(false);
  });

  it('opens a mail still showing as its list row through the ordinary path, quietly', async () => {
    let answer;
    fixture = await mountBoth([message(1), message(2)], {
      getEmailByRemoteId: jest.fn(() => new Promise(resolve => {
        answer = resolve;
      })),
    });
    fixture.host.vm.$root.$emit('open-email-detail-drawer', 2, fixture.mailbox.emails, false, null, false, false, 'INBOX');
    await flush();
    // The mail drawer is still waiting for the full copy: it shows the list row.
    expect(emailConnectorMailBoxService.isListingRow(fixture.mail.email)).toBe(true);
    fixture.service.getEmailByRemoteId.mockClear();
    fixture.service.updateEmailsReadStatus.mockClear();

    await expandFromMail(fixture);
    answer(full(message(2)));
    await flush();

    expect(fixture.service.getEmailByRemoteId.mock.calls).toEqual([[2, 'INBOX', { broadcast: false }]]);
    expect(fixture.service.updateEmailsReadStatus).not.toHaveBeenCalled();
    expect(fixture.mailbox.email.content.body).toBe('<p>body 2</p>');
  });

  it('lands a search hit of another folder in the full-screen search, lit', async () => {
    fixture = await mountBoth([message(5), message(6)]);
    fixture.mailbox.searchTerm = 'archived';
    fixture.mailbox.searchServerResults = [message(5, 'ARCHIVE', { cached: true })];
    await fixture.host.vm.$nextTick();
    await fixture.mailbox.openSearchResult(fixture.mailbox.mergedSearchResults.find(hit => hit.folder === 'ARCHIVE'));
    await flush();
    expect(fixture.mail.emailDetailDrawer).toBe(true);
    expect(fixture.mail.email.folder).toBe('ARCHIVE');

    await expandFromMail(fixture);

    // ARCHIVE:5, never the inbox's own 5.
    expect(fixture.mailbox.email.folder).toBe('ARCHIVE');
    expect(fixture.mailbox.openedSearchKey).toBe('ARCHIVE:5');
    expect(fixture.mailbox.searchActive).toBe(true);
    expect(fixture.mailbox.pinnedEmail).toBe(false);
  });

  it('pins a mail the list does not hold, so a reload does not take it back', async () => {
    fixture = await mountBoth([message(1)]);
    // The global Favorites drawer opened a mail over the open mailbox.
    fixture.host.vm.$root.$emit('open-email-detail-drawer', 9, [message(9, 'INBOX')], false, null, true, false, 'INBOX');
    await flush();

    await expandFromMail(fixture);
    await fixture.mailbox.loadEmailBox();
    await flush();

    expect(fixture.mailbox.pinnedEmail).toBe(true);
    expect(fixture.mailbox.email.mailRemoteId).toBe(9);
    expect(fixture.mailbox.selectEmailPlaceHolder).toBe(false);
  });

  it('keeps a draft\'s conversation open on the draft\'s own row', async () => {
    const draft = message(4, 'DRAFTS', { draftLocalId: 'local-4', threadId: 'thread-1' });
    fixture = await mountBoth([message(1), draft]);
    fixture.host.vm.$root.$emit('open-email-thread-drawer', draft, fixture.mailbox.emails, false, null);
    await flush();

    await expandFromMail(fixture);

    expect(fixture.mailbox.email).toBe(draft);
    expect(fixture.mailbox.selectEmailPlaceHolder).toBe(false);
    // Nothing fetched by UID: a draft may have none.
    expect(fixture.service.getEmailByRemoteId).not.toHaveBeenCalled();
    // Its mail actions address a message by UID: not offered on the draft.
    expect(fixture.mailbox.$el.querySelector('email-connector-mail-box-drawer-list-item-detail-actions')).toBeNull();
  });

  it('does not ask to give up a running download, and the full screen still guards it', async () => {
    fixture = await mountBoth([message(1), message(2)]);
    fixture.host.vm.$root.$emit('open-email-detail-drawer', 2, fixture.mailbox.emails, false, null, false, false, 'INBOX');
    await flush();
    const download = { mailRemoteId: 2, attachmentRemoteId: 'a', abortController: new AbortController() };
    fixture.host.vm.$root.$emit('attachment-download-started', download);
    await fixture.host.vm.$nextTick();

    await expandFromMail(fixture);

    expect(fixture.mail.$refs.emailDetailDrawer.confirmationAsked).toBe(false);
    expect(fixture.mail.emailDetailDrawer).toBe(false);
    expect(download.abortController.signal.aborted).toBe(false);
    // Closing the full screen asks, as it did before the hand-over.
    fixture.mailbox.$refs.emailBoxDrawer.close();
    expect(fixture.mailbox.$refs.emailBoxDrawer.confirmationAsked).toBe(true);
  });

  it('comes back to the list on collapse', async () => {
    fixture = await mountBoth([message(1), message(2)]);
    fixture.host.vm.$root.$emit('open-email-detail-drawer', 2, fixture.mailbox.emails, false, null, false, false, 'INBOX');
    await flush();
    await expandFromMail(fixture);

    jest.useFakeTimers();
    fixture.mailbox.$refs.emailBoxDrawer.toogleExpand();
    await fixture.host.vm.$nextTick();
    jest.advanceTimersByTime(250);
    jest.useRealTimers();
    await fixture.host.vm.$nextTick();

    expect(fixture.mailbox.expanded).toBe(false);
    expect(fixture.mail.emailDetailDrawer).toBe(false);
    expect(fixture.mailbox.$el.querySelector('[data-slot="fullAppLeftContent"]')).toBeNull();
  });
});

describe('a draft\'s conversation opens in the full-screen reader (EXO-90415)', () => {
  let fixture;

  afterEach(() => {
    jest.useRealTimers();
    fixture?.teardown();
  });

  it('opens on the draft\'s row clicked in the full-screen list', async () => {
    const draft = message(4, 'DRAFTS', { draftLocalId: 'local-4', threadId: 'thread-1' });
    fixture = await mountBoth([message(1), draft]);
    await fixture.mailbox.$nextTick();
    fixture.mailbox.expanded = true;

    fixture.host.vm.$root.$emit('open-email-thread-content', draft);

    expect(fixture.mailbox.email).toBe(draft);
    expect(fixture.mailbox.selectEmailPlaceHolder).toBe(false);
    expect(fixture.service.getEmailByRemoteId).not.toHaveBeenCalled();
  });

  it('shows it over an arrow-key opening answering last', async () => {
    const draft = message(4, 'DRAFTS', { draftLocalId: 'local-4', threadId: 'thread-1' });
    let answer;
    fixture = await mountBoth([message(1), message(3), draft], {
      getEmailByRemoteId: jest.fn(mailRemoteId => new Promise(resolve => {
        answer = () => resolve(full(message(mailRemoteId)));
      })),
    });
    fixture.mailbox.expanded = true;
    fixture.mailbox.openAutomatically(fixture.mailbox.emails.find(email => email.mailRemoteId === 3));

    fixture.host.vm.$root.$emit('open-email-thread-content', draft);
    answer();
    await flush();

    expect(fixture.mailbox.email).toBe(draft);
    expect(fixture.mailbox.loadingEmail).toBe(false);
    expect(fixture.mailbox.autoOpenReadPending).toBe(false);
  });

  it('leaves it to the mail drawer in the narrow layout', async () => {
    const draft = message(4, 'DRAFTS', { draftLocalId: 'local-4', threadId: 'thread-1' });
    fixture = await mountBoth([message(1), draft]);

    fixture.host.vm.$root.$emit('open-email-thread-content', draft);

    expect(fixture.mailbox.email).toBeNull();
  });
});
