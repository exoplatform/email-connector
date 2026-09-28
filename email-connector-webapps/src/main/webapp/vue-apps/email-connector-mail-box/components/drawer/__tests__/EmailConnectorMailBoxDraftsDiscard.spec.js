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

// EXO-90438 — in the Drafts folder the selection bar used to offer Delete, Archive and
// "Mark as unread" ("Mark as spam" and "Move to..." were already withheld there). The
// server refuses the first two on purpose (EmailBoxService#canMoveOutOf: a draft is
// discarded, not filed away): the user ticked six drafts, pressed Delete, and nothing
// happened — the failure count came back and nobody showed it. What Drafts offers now
// is Discard, on the bar and on a row's own menu, asked once in one dialog; the third
// is withheld because a draft is stored read and has no read state worth pushing.

import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxDrawerActions from '../EmailConnectorMailBoxDrawerActions.vue';
import EmailConnectorMailBoxDiscardDraftsConfirmPopup from '../EmailConnectorMailBoxDiscardDraftsConfirmPopup.vue';
import EmailConnectorMailBoxDrawerListItemActionMenuItems from '../EmailConnectorMailBoxDrawerListItemActionMenuItems.vue';
import EmailConnectorMailBoxDrawerListItem from '../EmailConnectorMailBoxDrawerListItem.vue';
import EmailConnectorMailBoxDrawerContent from '../EmailConnectorMailBoxDrawerContent.vue';
import EmailConnectorMailBoxDrawerListItemDetail from '../EmailConnectorMailBoxDrawerListItemDetail.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';
import { parseSelectionKey, selectionByFolder, selectionKey } from '../../../js/EmailConnectorMailBoxSelection.js';

/**
 * A draft row as the Drafts listing holds one.
 *
 * @param {Number} mailRemoteId the UID of its copy on the mail server, null for a
 *        draft that has never been pushed there
 * @param {String} draftLocalId the composer's own handle on it
 * @param {Boolean} scheduled whether a scheduled send has frozen it
 * @returns {Object} the row
 */
function draftRow(mailRemoteId, draftLocalId, scheduled = false) {
  return {
    mailRemoteId,
    draftLocalId,
    scheduled,
    folder: 'DRAFTS',
    subject: `draft ${draftLocalId}`,
    read: true,
    receivedDate: '2026-09-17T10:00:00Z',
    sender: { name: 'me' },
    threadParticipants: ['Someone'],
  };
}

/**
 * One listed mail.
 *
 * @param {Number} mailRemoteId the IMAP UID
 * @param {String} folder the folder it is listed in
 * @returns {Object} the row
 */
function mailRow(mailRemoteId, folder = 'INBOX') {
  return {
    mailRemoteId,
    folder,
    subject: `mail ${mailRemoteId}`,
    read: false,
    receivedDate: '2026-09-17T10:00:00Z',
    sender: { name: 'Someone' },
  };
}

// The list item renders a date, and the formatter reads the portal's language off the
// page's own global — which no component test has.
global.eXo = { env: { portal: { language: 'en' } } };

/**
 * Mounts the selection toolbar over a listing, with rows already ticked.
 *
 * @param {Array} emails the listed rows
 * @param {Array} selectedEmails the ticked ids
 * @returns {Object} {wrapper, emit}
 */
function mountActions(emails, selectedEmails) {
  const wrapper = shallowMount(EmailConnectorMailBoxDrawerActions, {
    propsData: { emails, selectedEmails, selectMode: true, top: true },
    mocks: {
      $t: key => key,
      $emailConnectorMailBoxService: emailConnectorMailBoxService,
    },
  });
  const emit = jest.fn();
  wrapper.vm.$root.$emit = emit;
  wrapper.vm.$root.mailFolders = [
    { key: 'INBOX', type: 'BUILT_IN' },
    { key: 'ARCHIVE', type: 'BUILT_IN' },
  ];
  return { wrapper, emit };
}

/**
 * Mounts the confirmation, with its dialog ref stubbed so the spec can see whether it
 * was opened at all.
 *
 * @returns {Object} {wrapper, emit, opened, alerts}
 */
function mountConfirm() {
  const wrapper = shallowMount(EmailConnectorMailBoxDiscardDraftsConfirmPopup, {
    mocks: { $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key) },
  });
  const opened = jest.fn();
  wrapper.vm.$refs.discardDraftsConfirmDialog = { open: opened };
  const emit = jest.fn();
  wrapper.vm.$root.$emit = emit;
  const alerts = [];
  document.addEventListener('alert-message', event => alerts.push(event.detail));
  return { wrapper, emit, opened, alerts };
}

/**
 * Mounts one listing row.
 *
 * @param {Object} email the row
 * @param {Array} selectedEmails the ticked ids
 * @param {Object} thread the conversation it stands for, when it is a grouped row
 * @param {Boolean} selectMode whether the listing is in multi-select
 * @returns {Object} {wrapper}
 */
function mountRow(email, selectedEmails = [], thread = null, selectMode = true) {
  const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItem, {
    propsData: { email, thread, selectedEmails, selectMode, emails: [email] },
    mocks: {
      $t: key => key,
      $emailConnectorMailBoxService: emailConnectorMailBoxService,
      $vuetify: { breakpoint: {}, rtl: false },
    },
  });
  return { wrapper };
}

/**
 * Mounts a row's own ⋮ menu.
 *
 * @param {Object} email the row
 * @param {Object} thread the conversation it stands for, when it is a grouped row
 * @returns {Object} {wrapper, emit}
 */
function mountMenu(email, thread = null) {
  const wrapper = shallowMount(EmailConnectorMailBoxDrawerListItemActionMenuItems, {
    propsData: { email, thread },
    mocks: {
      $t: key => key,
      $emailConnectorMailBoxService: emailConnectorMailBoxService,
    },
  });
  const emit = jest.fn();
  wrapper.vm.$root.$emit = emit;
  wrapper.vm.$root.mailFolders = [{ key: 'INBOX', type: 'BUILT_IN' }];
  return { wrapper, emit };
}

describe('the selection bar in the Drafts folder offers Discard and nothing that would fail', () => {
  it('offers Discard, and withholds Delete, Archive, Mark as spam and Move to...', () => {
    const { wrapper } = mountActions([draftRow(11, 'local-1'), draftRow(12, 'local-2')],
      ['DRAFTS:DRAFT-local-1', 'DRAFTS:DRAFT-local-2']);

    expect(wrapper.vm.canDiscardSelection).toBe(true);
    expect(wrapper.vm.canMutateSelection).toBe(false);
    expect(wrapper.vm.canMarkSelectionAsJunk).toBe(false);
    expect(wrapper.vm.canMoveSelection).toBe(false);
    // What is actually on the bar, not only what the computeds say.
    const titles = wrapper.findAll('v-btn').wrappers.map(button => button.attributes('title'));
    expect(titles).toEqual(['emailConnector.mailBox.list.drawer.detail.discard.label']);
  });

  it('withholds read/unread there too: a draft is the user\'s own text, stored read', () => {
    const { wrapper } = mountActions([draftRow(11, 'local-1')], ['DRAFTS:DRAFT-local-1']);

    expect(wrapper.vm.canUpdateEmailsReadStatus(false)).toBe(false);
    expect(wrapper.vm.canUpdateEmailsReadStatus(true)).toBe(false);
  });

  it('leaves every other folder as it was: the inbox keeps Delete and offers no Discard', () => {
    const { wrapper } = mountActions([mailRow(1), mailRow(2)], ['INBOX:1', 'INBOX:2']);

    expect(wrapper.vm.canDiscardSelection).toBe(false);
    expect(wrapper.vm.canMutateSelection).toBe(true);
    expect(wrapper.vm.canMarkSelectionAsJunk).toBe(true);
    expect(wrapper.vm.canUpdateEmailsReadStatus(true)).toBe(true);
  });

  it('hands the confirmation the selected draft ROWS, which is where the local ids are', () => {
    const { wrapper, emit } = mountActions([draftRow(11, 'local-1'), draftRow(12, 'local-2'), mailRow(13, 'INBOX')],
      ['DRAFTS:DRAFT-local-1', 'DRAFTS:DRAFT-local-2']);

    wrapper.vm.discardDrafts();

    const call = emit.mock.calls.find(args => args[0] === 'open-discard-drafts-confirm-popup');
    expect(call[1].map(draft => draft.draftLocalId)).toEqual(['local-1', 'local-2']);
  });

  it('discards the one never-pushed draft that was ticked, not every draft sharing its empty UID', () => {
    // Such a draft has no UID, and the selection used to be a list of UIDs: both rows
    // answered to the same null, so ticking one lit the other and Discard would have
    // thrown away a draft the user never ticked. The selection keys a draft by its own
    // local id instead (selectionKey), which is also what the endpoint addresses.
    const { wrapper } = mountActions([draftRow(null, 'local-1'), draftRow(null, 'local-2')], ['DRAFTS:DRAFT-local-1']);

    expect(wrapper.vm.canDiscardSelection).toBe(true);
    expect(wrapper.vm.selectedDraftRows.map(draft => draft.draftLocalId)).toEqual(['local-1']);
  });

  it('still resolves both when both were ticked', () => {
    const { wrapper } = mountActions([draftRow(null, 'local-1'), draftRow(null, 'local-2')],
      ['DRAFTS:DRAFT-local-1', 'DRAFTS:DRAFT-local-2']);

    expect(wrapper.vm.selectedDraftRows.map(draft => draft.draftLocalId)).toEqual(['local-1', 'local-2']);
  });

  it('a draft that HAS been pushed is selected by its local id too, so the bar reads one map', () => {
    const { wrapper } = mountActions([draftRow(11, 'local-1')], ['DRAFTS:DRAFT-local-1']);

    expect(wrapper.vm.canDiscardSelection).toBe(true);
    expect(wrapper.vm.canMutateSelection).toBe(false);
    expect(wrapper.vm.selectedDraftRows.map(draft => draft.draftLocalId)).toEqual(['local-1']);
  });
});

describe('a row is selected by an id every row has (EXO-90438)', () => {
  it('a draft is keyed by its folder and its local id, a message by its folder and UID', () => {
    const { wrapper } = mountRow(draftRow(null, 'local-1'));

    expect(wrapper.vm.selectionKeys).toEqual(['DRAFTS:DRAFT-local-1']);
    expect(mountRow(mailRow(7)).wrapper.vm.selectionKeys).toEqual(['INBOX:7']);
  });

  it('two unsent drafts no longer share one key, so ticking one does not tick the other', () => {
    const first = draftRow(null, 'local-1');
    const second = draftRow(null, 'local-2');
    const selection = [selectionKey(first)];

    expect(mountRow(first, selection).wrapper.vm.selected).toBe(true);
    expect(mountRow(second, selection).wrapper.vm.selected).toBe(false);
  });

  it('the same draft local id in two folders is two keys, so ticking one does not tick the other', () => {
    // The other half of the key (EXO-90416): a listing of search results holds several
    // folders. A draft's local id is unique, but the key is what the two fixes share,
    // and it has to stay folder-scoped for the draft half as it is for the UID half --
    // or the merge of the two would have quietly dropped the folder for every draft.
    const here = { ...draftRow(null, 'local-1'), folder: 'DRAFTS' };
    const elsewhere = { ...draftRow(null, 'local-1'), folder: 'ARCHIVE' };

    expect(selectionKey(here)).toBe('DRAFTS:DRAFT-local-1');
    expect(selectionKey(elsewhere)).toBe('ARCHIVE:DRAFT-local-1');
    expect(mountRow(here, [selectionKey(here)]).wrapper.vm.selected).toBe(true);
    expect(mountRow(elsewhere, [selectionKey(here)]).wrapper.vm.selected).toBe(false);
  });

  it('ticking a draft names its local id, so the key the drawer builds is the key the row reads', () => {
    // The two halves of one round trip, and the only place they meet: the row emits one
    // select-email per message (emitSelect) and the mailbox drawer builds the key from
    // it (its select-email handler, selectionKey({mailRemoteId, draftLocalId, folder})).
    // Drop the local id from the event and the drawer keys every unsent draft DRAFTS:null
    // again -- the row would go on reading its own key, see no match, and the checkbox
    // would never light; two unsent drafts would share the one key the fix removed.
    const first = draftRow(null, 'local-1');
    const second = draftRow(null, 'local-2');
    const thread = { threadId: 'thread-1', emails: [first, second], mailRemoteIds: [null, null] };
    const { wrapper } = mountRow(first, [], thread);
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    wrapper.vm.emitSelect(true);

    const keys = emit.mock.calls
      .filter(args => args[0] === 'select-email')
      .map(([, { emailId, draftLocalId, folder }]) => selectionKey({ mailRemoteId: emailId, draftLocalId, folder }));
    expect(keys).toEqual(['DRAFTS:DRAFT-local-1', 'DRAFTS:DRAFT-local-2']);
    expect(keys).toEqual(wrapper.vm.selectionKeys);
  });

  it('ticking a mail still names its UID, and the drawer keys it as before', () => {
    const { wrapper } = mountRow(mailRow(7));
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    wrapper.vm.emitSelect(true);

    const keys = emit.mock.calls
      .filter(args => args[0] === 'select-email')
      .map(([, { emailId, draftLocalId, folder }]) => selectionKey({ mailRemoteId: emailId, draftLocalId, folder }));
    expect(keys).toEqual(['INBOX:7']);
  });

  it('a draft key is never grouped as a UID: what a bulk action is sent with holds no draft', () => {
    // selectionByFolder is the one place a key is turned back into the id a request is
    // addressed by. A draft key holds no UID, and a null handed over as one would be
    // answered against whatever that folder holds at that number.
    const keys = [selectionKey(draftRow(null, 'local-1')), selectionKey(draftRow(11, 'local-2')), selectionKey(mailRow(7))];

    expect(selectionByFolder(keys)).toEqual([['INBOX', [7]]]);
    expect(parseSelectionKey('DRAFTS:DRAFT-local-1')).toEqual({ folder: 'DRAFTS', id: null, draftLocalId: 'local-1' });
    expect(parseSelectionKey('INBOX:7')).toEqual({ folder: 'INBOX', id: 7, draftLocalId: null });
  });

  it('a long press on a draft row opens the menu, which on a phone is the only way to its Discard', () => {
    // Outside select mode, which is where a long press happens.
    const { wrapper } = mountRow(draftRow(null, 'local-1'), [], null, false);
    const emit = jest.fn();
    wrapper.vm.$root.$emit = emit;

    wrapper.vm.openActionMenuDrawer();

    expect(emit.mock.calls.some(args => args[0] === 'open-email-action-menu-drawer')).toBe(true);
  });

  it('select-all gives one entry per row, drafts included', () => {
    const rows = [draftRow(null, 'local-1'), draftRow(null, 'local-2')];
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerContent, {
      propsData: { emails: rows, selectMode: true },
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: emailConnectorMailBoxService,
      },
    });

    wrapper.vm.onSelectAllChange(true);

    expect(wrapper.emitted('update:selected-emails')[0][0]).toEqual(['DRAFTS:DRAFT-local-1', 'DRAFTS:DRAFT-local-2']);
  });
});

describe('the confirmation names the count and holds scheduled drafts back', () => {
  let fixture;
  afterEach(() => fixture?.wrapper.destroy());

  it('asks about the count, and sends the local ids once the user goes through with it', () => {
    fixture = mountConfirm();

    fixture.wrapper.vm.open([draftRow(11, 'local-1'), draftRow(12, 'local-2'), draftRow(13, 'local-3')]);

    expect(fixture.opened).toHaveBeenCalled();
    expect(fixture.wrapper.vm.title).toBe('emailConnector.mailBox.list.drawer.discard.confirm.titles|3');
    expect(fixture.wrapper.vm.message).toBe('emailConnector.mailBox.list.drawer.discard.confirm.messages|3');

    fixture.wrapper.vm.discardDrafts();
    const call = fixture.emit.mock.calls.find(args => args[0] === 'discard-drafts');
    expect(call[1]).toEqual(['local-1', 'local-2', 'local-3']);
  });

  it('a single draft is asked about in the singular', () => {
    fixture = mountConfirm();

    fixture.wrapper.vm.open([draftRow(11, 'local-1')]);

    expect(fixture.wrapper.vm.title).toBe('emailConnector.mailBox.list.drawer.discard.confirm.title');
    expect(fixture.wrapper.vm.message).toBe('emailConnector.mailBox.list.drawer.discard.confirm.message');
  });

  it('counts only what will actually go, and says why the rest stays', () => {
    fixture = mountConfirm();

    fixture.wrapper.vm.open([draftRow(11, 'local-1'), draftRow(12, 'local-2', true), draftRow(13, 'local-3', true)]);

    expect(fixture.wrapper.vm.title).toBe('emailConnector.mailBox.list.drawer.discard.confirm.title');
    expect(fixture.wrapper.vm.message)
      .toContain('emailConnector.mailBox.list.drawer.discard.confirm.scheduledsKept|2');

    fixture.wrapper.vm.discardDrafts();
    const call = fixture.emit.mock.calls.find(args => args[0] === 'discard-drafts');
    expect(call[1]).toEqual(['local-1']);
  });

  it('a selection of nothing but scheduled drafts asks nothing and says so on screen', () => {
    fixture = mountConfirm();

    fixture.wrapper.vm.open([draftRow(11, 'local-1', true), draftRow(12, 'local-2', true)]);

    expect(fixture.opened).not.toHaveBeenCalled();
    expect(fixture.alerts.pop().alertMessage)
      .toBe('emailConnector.mailBox.list.drawer.discard.scheduleds.error|2');
  });
});

describe('the mailbox drawer is the only place a selection lives (EXO-90415, EXO-90438)', () => {
  it('the narrow reader has no select mode of its own for a discard to leave standing', () => {
    // EXO-90438 wired a discard-drafts handler into the reader, because the reader then
    // rendered the same listing and the same selection bar beside the mail, and the
    // mailbox drawer's own handler bails out while the reader is up -- so a selection
    // left standing there outlived the discarded rows, and ids matching no row read as
    // INBOX messages, which put Delete and Archive back on the bar.
    //
    // EXO-90415 took that second selection away: the mail drawer is the narrow reader
    // only, its full-screen list, its toolbar and its multi-selection removed. So the
    // handler had nothing left to cancel and is gone with them. This pin is what fails
    // if a selection ever comes back to this component without a discard-drafts handler
    // coming back with it.
    expect(EmailConnectorMailBoxDrawerListItemDetail.data()).not.toHaveProperty('selectMode');
    expect(EmailConnectorMailBoxDrawerListItemDetail.data()).not.toHaveProperty('selectedEmails');
  });
});

describe('a single draft\'s own menu offers the same Discard', () => {
  it('offers Discard on a draft row, and asks the same confirmation for that one row', () => {
    const { wrapper, emit } = mountMenu(draftRow(11, 'local-1'));

    expect(wrapper.vm.canDiscard).toBe(true);
    expect(wrapper.vm.canMove).toBe(false);

    wrapper.vm.discardDraft();
    const call = emit.mock.calls.find(args => args[0] === 'open-discard-drafts-confirm-popup');
    expect(call[1]).toEqual([wrapper.vm.email]);
  });

  it('a collapsed row standing for two drafts discards both, as ticking it would', () => {
    // The listing groups by conversation, so two drafts answering the same exchange are
    // one row. The menu has to mean what the checkbox beside it means.
    const first = draftRow(null, 'local-1');
    const second = draftRow(null, 'local-2');
    const thread = { threadId: 'thread-1', emails: [first, second], mailRemoteIds: [null, null] };
    const { wrapper, emit } = mountMenu(first, thread);

    wrapper.vm.discardDraft();

    const call = emit.mock.calls.find(args => args[0] === 'open-discard-drafts-confirm-popup');
    expect(call[1].map(draft => draft.draftLocalId)).toEqual(['local-1', 'local-2']);
  });

  // A guard, not a screen: groupEmailsByThread runs over ONE folder's rows, so a
  // listing row never mixes a draft with the mail it answers, and no user can reach
  // this shape today. It pins draftRows' folder filter for the day the grouping widens
  // — the reader's cross-folder conversation already has that shape, under .messages.
  it('a conversation row that holds a draft AND its mail discards only the draft', () => {
    const draft = draftRow(null, 'local-1');
    const mail = mailRow(7);
    const thread = { threadId: 'thread-1', emails: [draft, mail], mailRemoteIds: [null, 7] };
    const { wrapper, emit } = mountMenu(draft, thread);

    wrapper.vm.discardDraft();

    const call = emit.mock.calls.find(args => args[0] === 'open-discard-drafts-confirm-popup');
    expect(call[1].map(draft => draft.draftLocalId)).toEqual(['local-1']);
  });

  it('shows Discard and no read/unread on a draft row', () => {
    const { wrapper } = mountMenu(draftRow(11, 'local-1'));

    expect(wrapper.text()).toContain('emailConnector.mailBox.list.drawer.detail.discard.label');
    expect(wrapper.text()).not.toContain('emailConnector.mailBox.list.drawer.detail.unread.label');
    expect(wrapper.text()).not.toContain('emailConnector.mailBox.list.drawer.detail.delete.label');
  });

  it('leaves a mail row as it was: read/unread and Delete, no Discard', () => {
    const { wrapper } = mountMenu(mailRow(1));

    expect(wrapper.vm.canDiscard).toBe(false);
    expect(wrapper.text()).not.toContain('emailConnector.mailBox.list.drawer.detail.discard.label');
    expect(wrapper.text()).toContain('emailConnector.mailBox.list.drawer.detail.read.label');
    expect(wrapper.text()).toContain('emailConnector.mailBox.list.drawer.detail.delete.label');
  });
});
