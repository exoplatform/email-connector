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

// EXO-90434 -- scheduled send, the mailbox's half: the "Scheduled" view among the
// folders (icon, name, count, warning), its list and each row's actions, a scheduled
// reply read-only in its conversation, and the REST contract they speak.

import Vue from 'vue';
import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxScheduledList from '../EmailConnectorMailBoxScheduledList.vue';
import EmailConnectorMailBoxScheduledListItem from '../EmailConnectorMailBoxScheduledListItem.vue';
import EmailConnectorMailBoxDrawerNavigation from '../EmailConnectorMailBoxDrawerNavigation.vue';
import EmailConnectorMailBoxDrawerActionMenuItems from '../EmailConnectorMailBoxDrawerActionMenuItems.vue';
import EmailConnectorMailBoxDrawerThreadDraft from '../EmailConnectorMailBoxDrawerThreadDraft.vue';
import EmailConnectorMailBoxDrawerListItemDetailContent from '../EmailConnectorMailBoxDrawerListItemDetailContent.vue';
import EmailConnectorMailBoxDrawerThreadContent from '../EmailConnectorMailBoxDrawerThreadContent.vue';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

Vue.config.ignoredElements.push(/^email-connector-/, 'extension-registry-components', 'exo-confirm-dialog', 'exo-drawer');

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

const translate = (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key);

/**
 * A service whose every function answers an empty promise, the ones under test supplied,
 * the pure helpers the real ones.
 *
 * @param {Object} overrides the functions under test
 * @returns {Proxy} the service
 */
function serviceStub(overrides) {
  const target = {
    formatScheduledDate: emailConnectorMailBoxService.formatScheduledDate,
    scheduledStateLine: emailConnectorMailBoxService.scheduledStateLine,
    scheduledActions: emailConnectorMailBoxService.scheduledActions,
    scheduledErrorMessage: emailConnectorMailBoxService.scheduledErrorMessage,
    folderLabel: emailConnectorMailBoxService.folderLabel,
    folderIcon: emailConnectorMailBoxService.folderIcon,
    formatCount: emailConnectorMailBoxService.formatCount,
    isScheduledView: emailConnectorMailBoxService.isScheduledView,
    ...overrides,
  };
  return new Proxy(target, {
    get(stubbed, name) {
      if (!(name in stubbed)) {
        stubbed[name] = jest.fn(() => Promise.resolve(null));
      }
      return stubbed[name];
    },
  });
}

/**
 * A scheduled mail as GET /scheduled lists it.
 *
 * @param {String} id its draft local id
 * @param {Object} extra further fields
 * @returns {Object} the row
 */
function scheduledRow(id, extra = {}) {
  return {
    draftLocalId: id,
    to: [{ name: 'Bob', address: 'bob@host' }],
    subject: `Subject ${id}`,
    snippet: `Snippet ${id}`,
    scheduledDate: Date.UTC(2026, 9, 1, 6, 0),
    timeZone: 'Europe/Paris',
    status: 'SCHEDULED',
    lastError: null,
    ...extra,
  };
}

/**
 * A refusal as the service throws it.
 *
 * @param {Number} status the HTTP status
 * @param {String} code the server's message code
 * @returns {Error} the error
 */
function refusedWith(status, code) {
  const error = new Error(code);
  error.status = status;
  error.code = code;
  return error;
}

describe('the REST contract of scheduled send (EXO-90434)', () => {
  let calls;
  beforeEach(() => {
    calls = [];
  });
  afterEach(() => {
    delete global.fetch;
  });

  /**
   * Answers every fetch with the given response, recording the request.
   *
   * @param {Object} response {ok, status, body}
   * @returns {void}
   */
  function answer(response) {
    global.fetch = jest.fn((url, init) => {
      calls.push([url, init]);
      return Promise.resolve({
        ok: response.ok,
        status: response.status,
        json: () => Promise.resolve(response.body),
        text: () => Promise.resolve(typeof response.body === 'string' ? response.body : JSON.stringify(response.body)),
      });
    });
  }

  it('schedules a draft by POST, with the draft, the instant and the zone', async () => {
    answer({ ok: true, status: 200, body: { draftLocalId: 'd 1' } });
    await emailConnectorMailBoxService.scheduleDraft('d 1', { subject: 's' }, 42, 'Europe/Paris');
    expect(calls[0][0]).toBe('/email-connector/rest/email-box/drafts/d%201/schedule');
    expect(calls[0][1].method).toBe('POST');
    expect(JSON.parse(calls[0][1].body)).toEqual({ draft: { subject: 's' }, scheduledDate: 42, timeZone: 'Europe/Paris' });
  });

  it('reads, reschedules, cancels and sends through the /scheduled endpoints', async () => {
    answer({ ok: true, status: 200, body: [] });
    await emailConnectorMailBoxService.getScheduledEmails(20, 20);
    await emailConnectorMailBoxService.rescheduleEmail('d1', 7, 'UTC');
    await emailConnectorMailBoxService.cancelScheduledEmail('d1');
    await emailConnectorMailBoxService.sendScheduledEmailNow('d1');
    expect(calls.map(([url, init]) => [url, init.method])).toEqual([
      ['/email-connector/rest/email-box/scheduled?offset=20&limit=20', 'GET'],
      ['/email-connector/rest/email-box/scheduled/d1', 'PUT'],
      ['/email-connector/rest/email-box/scheduled/d1', 'DELETE'],
      ['/email-connector/rest/email-box/scheduled/d1/send', 'POST'],
    ]);
    expect(JSON.parse(calls[1][1].body)).toEqual({ scheduledDate: 7, timeZone: 'UTC' });
  });

  it('carries the server\'s message code on a refusal, JSON or plain, never an error page', async () => {
    answer({ ok: false, status: 409, body: { status: 409, message: 'emailConnector.scheduled.sending' } });
    await expect(emailConnectorMailBoxService.cancelScheduledEmail('d1')).rejects.toMatchObject({ status: 409, code: 'emailConnector.scheduled.sending' });

    answer({ ok: false, status: 400, body: 'emailConnector.scheduled.date.tooSoon' });
    await expect(emailConnectorMailBoxService.rescheduleEmail('d1', 1, 'UTC')).rejects.toMatchObject({ code: 'emailConnector.scheduled.date.tooSoon' });

    answer({ ok: false, status: 500, body: '<html>Oops</html>' });
    await expect(emailConnectorMailBoxService.sendScheduledEmailNow('d1')).rejects.toMatchObject({ status: 500, code: null });

    answer({ ok: false, status: 409, body: { message: 'emailConnector.scheduled.sending' } });
    await expect(emailConnectorMailBoxService.deleteDraft('d1')).rejects.toMatchObject({ code: 'emailConnector.scheduled.sending' });
  });

  it('translates a known code, and falls back on an unknown one', () => {
    const vm = { $t: key => `t:${key}`, $te: key => key === 'emailConnector.scheduled.locked' };
    expect(emailConnectorMailBoxService.scheduledErrorMessage(refusedWith(409, 'emailConnector.scheduled.locked'), vm, 'fallback'))
      .toBe('t:emailConnector.scheduled.locked');
    expect(emailConnectorMailBoxService.scheduledErrorMessage(refusedWith(500, 'some.other'), vm, 'fallback')).toBe('t:fallback');
    expect(emailConnectorMailBoxService.scheduledErrorMessage(new Error('x'), vm, 'fallback')).toBe('t:fallback');
  });
});

describe('what a scheduled mail says and offers in its state (EXO-90434)', () => {
  it('says nothing while it waits, "Sending", "Not sent: {reason}" in red, "Couldn\'t confirm" in orange', () => {
    const line = emailConnectorMailBoxService.scheduledStateLine;
    expect(line({ status: 'SCHEDULED' })).toBeNull();
    expect(line({ status: 'SENDING' }).key).toBe('emailConnector.mailBox.scheduled.sending');
    expect(line({ status: 'FAILED', lastError: 'RECIPIENT_REFUSED' })).toEqual({
      key: 'emailConnector.mailBox.scheduled.notSent',
      reasonKey: 'emailConnector.mailBox.scheduled.error.RECIPIENT_REFUSED',
      color: 'error--text',
    });
    expect(line({ status: 'FAILED', lastError: 'SOMETHING_NEW' }).reasonKey).toBe('emailConnector.mailBox.scheduled.error.unknown');
    expect(line({ status: 'UNCERTAIN', lastError: 'UNCONFIRMED' })).toEqual({ key: 'emailConnector.mailBox.scheduled.uncertain', color: 'warning--text' });
  });

  it('translates every reason code the backend lists', () => {
    const reasons = ['NETWORK', 'RECIPIENT_REFUSED', 'AUTHENTICATION', 'ATTACHMENT_GONE', 'TOO_LARGE', 'DISCONNECTED', 'REFUSED', 'INTERNAL',
      'MAILBOX_UNSHARED', 'SEND_MODE_WITHDRAWN', 'SEND_MODE_UNAVAILABLE', 'SEND_MODE_REFUSED'];
    expect(emailConnectorMailBoxService.NOT_SENT_REASONS).toEqual(reasons);
    // The two others mean "may have gone", never "not sent".
    ['INTERRUPTED', 'UNCONFIRMED'].forEach(reason => expect(reasons).not.toContain(reason));
  });

  it('offers Edit, Reschedule, Send now, Cancel, Discard while it waits; no Reschedule once uncertain; nothing while sending', () => {
    const actions = emailConnectorMailBoxService.scheduledActions;
    expect(actions({ status: 'SCHEDULED' })).toEqual(['edit', 'reschedule', 'sendNow', 'cancel', 'discard']);
    expect(actions({ status: 'FAILED' })).toEqual(['retry', 'edit', 'reschedule', 'moveToDrafts', 'discard']);
    expect(actions({ status: 'UNCERTAIN' })).toEqual(['sendAgain', 'edit', 'moveToDrafts', 'discard']);
    expect(actions({ status: 'SENDING' })).toEqual([]);
  });
});

describe('the Scheduled view among the folders (EXO-90434)', () => {
  const FOLDERS = [
    { key: 'INBOX', type: 'BUILT_IN' },
    { key: 'DRAFTS', type: 'BUILT_IN', count: 2 },
    { key: 'SCHEDULED', type: 'BUILT_IN', count: 3, attention: true },
    { key: 'CUSTOM:4', type: 'CUSTOM', displayName: 'Scheduled', path: 'Scheduled', syncEnabled: true },
    { key: 'CUSTOM:5', type: 'CUSTOM', displayName: 'Invoices', path: 'Invoices', syncEnabled: true },
  ];

  it('draws it with a clock and names it through the bundle', () => {
    expect(emailConnectorMailBoxService.folderIcon(FOLDERS[2])).toBe('fa-clock');
    expect(emailConnectorMailBoxService.folderLabel(FOLDERS[2], translate)).toBe('emailConnector.mailBox.list.drawer.folder.scheduled');
  });

  it('labels the mail server\'s own "Scheduled" folder as the server\'s, in any case, in English or in the user\'s words', () => {
    expect(emailConnectorMailBoxService.folderLabel(FOLDERS[3], translate))
      .toBe('emailConnector.mailBox.list.drawer.folder.custom.serverScheduled|Scheduled');
    expect(emailConnectorMailBoxService.folderLabel({ type: 'CUSTOM', displayName: ' SCHEDULED ' }, translate))
      .toBe('emailConnector.mailBox.list.drawer.folder.custom.serverScheduled| SCHEDULED ');
    const french = (key, params) => (key.endsWith('folder.scheduled') ? 'Programmés' : translate(key, params));
    expect(emailConnectorMailBoxService.folderLabel({ type: 'CUSTOM', displayName: 'programmés' }, french))
      .toBe('emailConnector.mailBox.list.drawer.folder.custom.serverScheduled|programmés');
    expect(emailConnectorMailBoxService.folderLabel(FOLDERS[4], translate)).toBe('Invoices');
  });

  it('shows its count in the warning colour in the full-screen column when a mail needs the user', () => {
    const wrapper = shallowMount(EmailConnectorMailBoxDrawerNavigation, {
      propsData: {
        folders: FOLDERS,
        folderCounts: { DRAFTS: { count: 2, unread: false }, SCHEDULED: { count: 3, unread: false, attention: true } },
      },
      mocks: { $t: translate, $emailConnectorMailBoxService: emailConnectorMailBoxService },
      stubs: { 'v-tooltip': { template: '<div><slot name="activator" :on="{}" :attrs="{}" /><slot /></div>' } },
    });
    const scheduled = wrapper.vm.folderEntries.find(entry => entry.folderKey === 'SCHEDULED');
    expect([scheduled.icon, scheduled.count, scheduled.attention]).toEqual(['fa-clock', 3, true]);
    expect(scheduled.ariaLabel).toBe('emailConnector.mailBox.list.drawer.navigation.attention|emailConnector.mailBox.list.drawer.navigation.total|emailConnector.mailBox.list.drawer.folder.scheduled|3');
    const counts = wrapper.findAll('v-list-item-action-text');
    const warned = counts.wrappers.filter(count => count.classes().includes('warning--text'));
    expect(warned.map(count => count.text())).toEqual(['3']);
    expect(wrapper.vm.folderEntries.find(entry => entry.folderKey === 'DRAFTS').attention).toBe(false);
  });

  it('shows it with its count in the 3-dots menu, and no multi-selection when it is listed', () => {
    const mountMenu = currentFolder => shallowMount(EmailConnectorMailBoxDrawerActionMenuItems, {
      propsData: { availableFolders: FOLDERS, currentFolder },
      mocks: { $t: translate, $emailConnectorMailBoxService: emailConnectorMailBoxService },
    });
    const menu = mountMenu('INBOX');
    expect(menu.vm.visibleFolders.map(folder => [folder.key, folder.count, folder.attention])).toEqual([
      ['INBOX', 0, false], ['DRAFTS', 0, false], ['SCHEDULED', 3, true], ['CUSTOM:4', 0, false], ['CUSTOM:5', 0, false]]);
    expect(menu.findAll('.folder-menu-count').wrappers.map(count => [count.text(), count.classes().includes('warning--text')]))
      .toEqual([['3', true]]);
    expect(menu.text()).toContain('emailConnector.mailBox.list.drawer.menu.selectSeveral');
    expect(mountMenu('SCHEDULED').text()).not.toContain('emailConnector.mailBox.list.drawer.menu.selectSeveral');
  });
});

describe('a row of the Scheduled view (EXO-90434)', () => {
  /**
   * Mounts a row.
   *
   * @param {Object} scheduled the scheduled mail
   * @returns {Object} the wrapper
   */
  function mountRow(scheduled) {
    return shallowMount(EmailConnectorMailBoxScheduledListItem, {
      propsData: { scheduled },
      mocks: { $t: translate, $emailConnectorMailBoxService: emailConnectorMailBoxService },
      stubs: { 'v-menu': { template: '<div><slot name="activator" :on="{}" :attrs="{}" /><slot /></div>' } },
    });
  }

  it('says who, what and when, with no chip, checkbox or drag', () => {
    const row = mountRow(scheduledRow('d1', { to: [{ name: 'Bob', address: 'bob@host' }, { address: 'eve@host' }] }));
    expect(row.find('.scheduled-email-recipients').text()).toBe('emailConnector.mailBox.scheduled.to|Bob, eve@host');
    expect(row.find('.scheduled-email-subject').text()).toContain('Subject d1');
    expect(row.find('.scheduled-email-subject').text()).toContain('Snippet d1');
    expect(row.find('.scheduled-email-date').text())
      .toContain(`emailConnector.mailBox.scheduled.at|${emailConnectorMailBoxService.formatScheduledDate(Date.UTC(2026, 9, 1, 6, 0), 'Europe/Paris')}`);
    expect(row.find('.scheduled-email-state').exists()).toBe(false);
    expect(row.find('v-checkbox').exists()).toBe(false);
    expect(row.attributes('draggable')).toBeUndefined();
  });

  it('says "(no subject)" and "No recipient" rather than nothing', () => {
    const row = mountRow(scheduledRow('d1', { to: [], subject: '' }));
    expect(row.find('.scheduled-email-recipients').text()).toBe('emailConnector.mailBox.scheduled.noRecipient');
    expect(row.find('.scheduled-email-subject').text()).toContain('emailConnector.mailBox.scheduled.noSubject');
  });

  it('says "Not sent: {reason}" in red and "Couldn\'t confirm it was sent" in orange', () => {
    const failed = mountRow(scheduledRow('d1', { status: 'FAILED', lastError: 'AUTHENTICATION' })).find('.scheduled-email-state');
    expect(failed.text()).toBe('emailConnector.mailBox.scheduled.notSent|emailConnector.mailBox.scheduled.error.AUTHENTICATION');
    expect(failed.classes()).toContain('error--text');
    const uncertain = mountRow(scheduledRow('d1', { status: 'UNCERTAIN', lastError: 'INTERRUPTED' })).find('.scheduled-email-state');
    expect(uncertain.text()).toBe('emailConnector.mailBox.scheduled.uncertain');
    expect(uncertain.classes()).toContain('warning--text');
  });

  it('lists the actions its state offers and hands the chosen one over', async () => {
    const row = mountRow(scheduledRow('d1'));
    const actions = row.findAll('.scheduled-email-action');
    expect(actions.wrappers.map(action => action.attributes('data-action'))).toEqual(['edit', 'reschedule', 'sendNow', 'cancel', 'discard']);
    await actions.at(2).trigger('click');
    expect(row.emitted('action')[0]).toEqual(['sendNow', row.props('scheduled')]);
    expect(mountRow(scheduledRow('d1', { status: 'SENDING' })).find('.scheduled-email-menu').exists()).toBe(false);
  });
});

describe('the Scheduled view\'s list and its actions (EXO-90434)', () => {
  /**
   * Mounts the list over the given rows.
   *
   * @param {Object} answers the service's functions under test
   * @returns {Promise<Object>} {wrapper, service, emitted}
   */
  async function mountList(answers = {}) {
    const service = serviceStub({
      getScheduledEmails: jest.fn(() => Promise.resolve([scheduledRow('d1'), scheduledRow('d2', { status: 'FAILED', lastError: 'NETWORK' })])),
      ...answers,
    });
    const confirmOpen = jest.fn();
    const modalClose = jest.fn();
    const wrapper = shallowMount(EmailConnectorMailBoxScheduledList, {
      mocks: { $t: translate, $te: key => key.startsWith('emailConnector.scheduled.'), $emailConnectorMailBoxService: service },
      stubs: {
        'exo-confirm-dialog': { props: ['title', 'message', 'okLabel'], template: '<div class="confirm" />', methods: { open: confirmOpen } },
        'email-connector-mail-box-popup': { template: '<div class="modal"><slot /></div>', methods: { open: jest.fn(), close: modalClose } },
      },
    });
    const emitted = [];
    const emit = wrapper.vm.$root.$emit.bind(wrapper.vm.$root);
    wrapper.vm.$root.$emit = (...args) => {
      emitted.push(args);
      return emit(...args);
    };
    await flush();
    return { wrapper, service, emitted, confirmOpen, modalClose };
  }

  const alerts = emitted => emitted.filter(event => event[0] === 'alert-message').map(event => [event[1], event[2]]);

  it('reads its first page, soonest first, one row per mail', async () => {
    const { wrapper, service } = await mountList();
    expect(service.getScheduledEmails).toHaveBeenCalledWith(0, 20);
    expect(wrapper.findAll('email-connector-mail-box-scheduled-list-item').length).toBe(2);
    expect(wrapper.find('.scheduled-email-empty').exists()).toBe(false);
  });

  it('says "No scheduled emails" once read empty, and pages on when a page is full', async () => {
    const empty = await mountList({ getScheduledEmails: jest.fn(() => Promise.resolve([])) });
    expect(empty.wrapper.find('.scheduled-email-empty').text()).toContain('emailConnector.mailBox.scheduled.empty');

    const page = Array.from({ length: 20 }, (value, index) => scheduledRow(`d${index}`));
    const full = await mountList({ getScheduledEmails: jest.fn(offset => Promise.resolve(offset ? [scheduledRow('last')] : page)) });
    expect(full.wrapper.find('.scheduled-email-load-more').exists()).toBe(true);
    await full.wrapper.vm.loadMore();
    expect(full.service.getScheduledEmails).toHaveBeenLastCalledWith(20, 20);
    expect(full.wrapper.vm.items.length).toBe(21);
    expect(full.wrapper.vm.hasMore).toBe(false);
  });

  it('re-reads whole pages, so "Show more" never lists a mail twice (the server pages by offset / limit)', async () => {
    // The server's paging: page offset / limit of size limit, over its current rows.
    let rows = Array.from({ length: 45 }, (value, index) => scheduledRow(`d${index}`));
    const serve = jest.fn((offset, limit) => {
      const page = Math.floor(offset / limit);
      return Promise.resolve(rows.slice(page * limit, page * limit + limit));
    });
    const { wrapper } = await mountList({ getScheduledEmails: serve });
    await wrapper.vm.loadMore();
    await wrapper.vm.loadMore();
    expect(wrapper.vm.items.length).toBe(45);
    expect(wrapper.vm.hasMore).toBe(false);

    // One cancelled here, two scheduled elsewhere: 46 rows now.
    rows = [...rows.slice(1), scheduledRow('new1'), scheduledRow('new2')];
    await wrapper.vm.reload();
    expect(serve).toHaveBeenLastCalledWith(0, 60);
    expect(wrapper.vm.items.length).toBe(46);
    expect(wrapper.vm.hasMore).toBe(false);

    // A reload of a length past the server's largest page asks for that page at most.
    rows = Array.from({ length: 130 }, (value, index) => scheduledRow(`e${index}`));
    await wrapper.setData({ items: rows.slice(0, 101) });
    await wrapper.vm.reload();
    expect(serve).toHaveBeenLastCalledWith(0, 100);
    expect(wrapper.vm.hasMore).toBe(true);
    await wrapper.vm.loadMore();
    const ids = wrapper.vm.items.map(item => item.draftLocalId);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids.length).toBe(120);
  });

  it('drops a mail already listed when a page brings it again, and still reads the next page after it', async () => {
    // 60 rows served by page; one row moves ahead of the window between two reads.
    let rows = Array.from({ length: 60 }, (value, index) => scheduledRow(`d${index}`));
    const serve = jest.fn((offset, limit) => {
      const page = Math.floor(offset / limit);
      return Promise.resolve(rows.slice(page * limit, page * limit + limit));
    });
    const { wrapper } = await mountList({ getScheduledEmails: serve });
    rows = [scheduledRow('moved'), ...rows.filter(row => row.draftLocalId !== 'd30')];
    await wrapper.vm.loadMore();
    // d19 comes again at the top of page 2 and is dropped: 39 rows, off a page boundary.
    expect(wrapper.vm.items.length).toBe(39);
    await wrapper.vm.loadMore();
    expect(serve).toHaveBeenLastCalledWith(40, 20);
    const ids = wrapper.vm.items.map(item => item.draftLocalId);
    expect(new Set(ids).size).toBe(ids.length);
    expect(ids).toContain('d59');
  });

  it('re-reads itself when the server\'s count moves, or a schedule changed elsewhere', async () => {
    const { wrapper, service } = await mountList();
    await wrapper.setProps({ signal: '1|true' });
    wrapper.vm.$root.$emit('scheduled-emails-changed');
    await flush();
    expect(service.getScheduledEmails).toHaveBeenCalledTimes(3);
  });

  it('edits through the composer, which cancels the schedule first', async () => {
    const { wrapper, emitted } = await mountList();
    wrapper.vm.onAction('edit', scheduledRow('d1'));
    expect(emitted).toEqual([['edit-scheduled-email', expect.objectContaining({
      draftLocalId: 'd1', scheduledDate: Date.UTC(2026, 9, 1, 6, 0), timeZone: 'Europe/Paris',
    })]]);
  });

  it('reschedules with the shared picker, starting on the previous time, by PUT', async () => {
    const { wrapper, service, emitted, modalClose } = await mountList({
      rescheduleEmail: jest.fn((id, date, zone) => Promise.resolve(scheduledRow(id, { scheduledDate: date, timeZone: zone }))),
    });
    wrapper.vm.onAction('reschedule', scheduledRow('d1'));
    await wrapper.vm.$nextTick();
    const picker = wrapper.find('email-connector-schedule-picker');
    expect(picker.exists()).toBe(true);
    expect(picker.attributes('value')).toBe(String(Date.UTC(2026, 9, 1, 6, 0)));

    await wrapper.vm.reschedule(Date.UTC(2026, 9, 2, 6, 0), 'UTC');
    expect(service.rescheduleEmail).toHaveBeenCalledWith('d1', Date.UTC(2026, 9, 2, 6, 0), 'UTC');
    expect(modalClose).toHaveBeenCalled();
    expect(alerts(emitted)[0][1]).toBe('success');
    expect(emitted.map(event => event[0])).toContain('refresh-email-box');
  });

  it('asks before sending now, sends by POST, and says it went, the row busy meanwhile', async () => {
    let answerSend;
    const { wrapper, service, emitted, confirmOpen } = await mountList({
      sendScheduledEmailNow: jest.fn(() => new Promise(resolve => answerSend = resolve)),
    });
    wrapper.vm.onAction('sendNow', scheduledRow('d1'));
    await wrapper.vm.$nextTick();
    expect(confirmOpen).toHaveBeenCalled();
    expect(wrapper.find('.confirm').props('message')).toBe('emailConnector.mailBox.scheduled.sendNow.confirm.message');
    expect(service.sendScheduledEmailNow).not.toHaveBeenCalled();

    wrapper.vm.runConfirmed();
    await flush();
    expect(service.sendScheduledEmailNow).toHaveBeenCalledWith('d1');
    expect(wrapper.vm.busyIds).toEqual(['d1']);
    expect(alerts(emitted)).toEqual([['emailConnector.mailBox.scheduled.sendNow.progress', 'info']]);

    answerSend({ ...scheduledRow('d1'), status: 'SENT' });
    await flush();
    expect(wrapper.vm.busyIds).toEqual([]);
    expect(alerts(emitted)[1]).toEqual(['emailConnector.mailBox.scheduled.sendNow.success', 'success']);
    expect(emitted.map(event => event[0])).toEqual(expect.arrayContaining(['email-sent', 'refresh-email-box']));
  });

  it('says why a mail sent now did not go', async () => {
    const { wrapper, emitted } = await mountList({
      sendScheduledEmailNow: jest.fn(() => Promise.resolve(scheduledRow('d2', { status: 'FAILED', lastError: 'TOO_LARGE' }))),
    });
    await wrapper.vm.sendNow(scheduledRow('d2'));
    expect(alerts(emitted)[1]).toEqual(['emailConnector.mailBox.scheduled.notSent|emailConnector.mailBox.scheduled.error.TOO_LARGE', 'error']);
  });

  it('says a mail sent now that could not reach the mail server will be retried by itself', async () => {
    const { wrapper, emitted } = await mountList({
      sendScheduledEmailNow: jest.fn(() => Promise.resolve(scheduledRow('d1', { status: 'SCHEDULED', lastError: 'NETWORK' }))),
    });
    await wrapper.vm.sendNow(scheduledRow('d1'));
    expect(alerts(emitted)[1]).toEqual(['emailConnector.mailBox.scheduled.sendNow.retryLater', 'warning']);
  });

  it('warns that sending an uncertain mail again may deliver it twice', async () => {
    const { wrapper } = await mountList();
    wrapper.vm.onAction('sendAgain', scheduledRow('d1', { status: 'UNCERTAIN' }));
    await wrapper.vm.$nextTick();
    expect(wrapper.find('.confirm').props('message')).toBe('emailConnector.mailBox.scheduled.sendNow.confirm.uncertainMessage');
  });

  it('cancels the schedule by DELETE after saying it goes back to Drafts, and discards through the draft', async () => {
    const { wrapper, service, emitted } = await mountList({
      cancelScheduledEmail: jest.fn(() => Promise.resolve()),
      deleteDraft: jest.fn(() => Promise.resolve()),
    });
    wrapper.vm.onAction('cancel', scheduledRow('d1'));
    await wrapper.vm.$nextTick();
    expect(wrapper.find('.confirm').props('okLabel')).toBe('emailConnector.mailBox.scheduled.cancel.confirm.ok');
    expect(wrapper.find('.confirm').props('message')).toBe('emailConnector.mailBox.scheduled.cancel.confirm.message');
    wrapper.vm.runConfirmed();
    await flush();
    expect(service.cancelScheduledEmail).toHaveBeenCalledWith('d1');

    wrapper.vm.onAction('discard', scheduledRow('d2'));
    await wrapper.vm.$nextTick();
    wrapper.vm.runConfirmed();
    await flush();
    expect(service.deleteDraft).toHaveBeenCalledWith('d2');
    expect(alerts(emitted)).toEqual([
      ['emailConnector.mailBox.scheduled.cancel.success', 'success'],
      ['emailConnector.mailBox.scheduled.discard.success', 'success'],
    ]);
  });

  it('says a 409 in the user\'s words -- being sent, uncertain -- and shows the mail as it now stands', async () => {
    const { wrapper, service, emitted } = await mountList({
      cancelScheduledEmail: jest.fn(() => Promise.reject(refusedWith(409, 'emailConnector.scheduled.sending'))),
      rescheduleEmail: jest.fn(() => Promise.reject(refusedWith(409, 'emailConnector.scheduled.uncertain'))),
    });
    const readsBefore = service.getScheduledEmails.mock.calls.length;
    await wrapper.vm.cancel(scheduledRow('d1'));
    wrapper.vm.onAction('reschedule', scheduledRow('d1'));
    await wrapper.vm.reschedule(Date.now() + 86400000, 'UTC');
    expect(alerts(emitted)).toEqual([
      ['emailConnector.scheduled.sending', 'error'],
      ['emailConnector.scheduled.uncertain', 'error'],
    ]);
    expect(service.getScheduledEmails.mock.calls.length).toBe(readsBefore + 2);
  });
});

describe('a scheduled reply in its conversation is read-only (EXO-90434, PO decision (a))', () => {
  const DRAFT = {
    draftLocalId: 'd1',
    sender: { name: 'Benjamin', address: 'ben@host' },
    to: [{ address: 'bob@host' }],
    content: { body: '<p>See you</p>' },
    scheduled: true,
    scheduledDate: Date.UTC(2026, 9, 1, 6, 0),
    scheduledTimeZone: 'Europe/Paris',
    scheduledStatus: 'SCHEDULED',
  };

  /**
   * Mounts the message renderer on a scheduled draft, as the conversation does.
   *
   * @param {Object} email the draft row
   * @returns {Object} the wrapper
   */
  function mountMessage(email) {
    return shallowMount(EmailConnectorMailBoxDrawerListItemDetailContent, {
      propsData: { email, hideSubject: true },
      mocks: { $t: translate, $emailConnectorMailBoxService: emailConnectorMailBoxService, $vuetify: { breakpoint: {} } },
      stubs: { 'v-btn': { template: '<button type="button" v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' } },
    });
  }

  it('says when it goes in place of a date, offers Edit, and none of a message\'s own actions', async () => {
    const message = mountMessage(DRAFT);
    expect(message.find('.scheduled-mail-date').text().replace(/\s+/g, ' '))
      .toBe(`far fa-clock emailConnector.mailBox.scheduled.at|${emailConnectorMailBoxService.formatScheduledDate(DRAFT.scheduledDate, 'Europe/Paris')}`);
    expect(message.find('email-connector-mail-box-drawer-favorite-toggle').exists()).toBe(false);
    expect(message.find('email-connector-mail-box-drawer-list-item-detail-action-menu').exists()).toBe(false);
    await message.find('.scheduled-mail-edit').trigger('click');
    expect(message.emitted('edit')).toHaveLength(1);
  });

  it('says a mail not sent or not confirmed from the status the conversation\'s row carries, never an invented reason', () => {
    const failed = mountMessage({ ...DRAFT, scheduledStatus: 'FAILED' }).find('.scheduled-mail-state');
    expect(failed.text()).toBe('emailConnector.mailBox.list.drawer.thread.draft.notSent');
    expect(failed.classes()).toContain('error--text');
    expect(mountMessage({ ...DRAFT, scheduledStatus: 'UNCERTAIN' }).find('.scheduled-mail-state').text())
      .toBe('emailConnector.mailBox.scheduled.uncertain');
    expect(mountMessage(DRAFT).find('.scheduled-mail-state').exists()).toBe(false);
    expect(mountMessage({ ...DRAFT, scheduledStatus: 'SENDING' }).find('.scheduled-mail-edit').attributes('disabled')).toBe('disabled');
  });

  it('keeps an unscheduled draft as it was: its strip, resumed on a click', async () => {
    const strip = shallowMount(EmailConnectorMailBoxDrawerThreadDraft, {
      propsData: { draft: { ...DRAFT, scheduled: false } },
      mocks: { $t: translate, $emailConnectorMailBoxService: emailConnectorMailBoxService },
    });
    await strip.trigger('click');
    expect(strip.emitted('resume')).toHaveLength(1);
  });

  it('hands Edit to the composer, the Scheduled view\'s own way', () => {
    const emitted = [];
    const vm = { $root: { $emit: (...args) => emitted.push(args) } };
    EmailConnectorMailBoxDrawerThreadContent.methods.editScheduledDraft.call(vm, DRAFT);
    expect(emitted).toEqual([['edit-scheduled-email', expect.objectContaining({
      draftLocalId: 'd1', scheduledDate: DRAFT.scheduledDate, timeZone: 'Europe/Paris', draft: DRAFT,
    })]]);
    // The Scheduled view's own row, a snippet for a body, is not handed over as the text.
    emitted.length = 0;
    EmailConnectorMailBoxDrawerThreadContent.methods.editScheduledDraft.call(vm, { ...DRAFT, scheduledRow: {} });
    expect(emitted[0][1].draft).toBeNull();
  });
});

describe('the mailbox lists the Scheduled view from its own endpoint (EXO-90434)', () => {
  /**
   * Mounts the mailbox drawer.
   *
   * @param {Object} answers the service's functions under test
   * @returns {Promise<Object>} {wrapper, service}
   */
  async function mountDrawer(answers = {}) {
    const service = serviceStub({
      getEmailBox: jest.fn(folder => Promise.resolve({
        emails: [{ mailRemoteId: 1, folder: folder || 'INBOX', draftLocalId: folder === 'DRAFTS' ? 'x' : null, categoryIds: [] }],
        folders: [{ key: 'INBOX', type: 'BUILT_IN' }, { key: 'SCHEDULED', type: 'BUILT_IN', count: 2, attention: true }],
        emailSyncStatus: 'SUCCESS',
      })),
      getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
      ...answers,
    });
    const wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
      mocks: {
        $t: translate,
        $emailConnectorMailBoxService: service,
        $emailConnectorCommonService: serviceStub({ getUserEmailSetting: jest.fn(() => Promise.resolve({ defaultCategoryView: 11 })) }),
        $vuetify: { breakpoint: {}, rtl: false },
      },
      stubs: { 'exo-drawer': { template: '<div><slot name="content" /><slot name="fullAppLeftContent" /></div>', methods: { open: jest.fn() } } },
    });
    await flush();
    return { wrapper, service };
  }

  it('reads the folders with the Drafts\' listing, shows none of its rows, and puts its own list in their place', async () => {
    const { wrapper, service } = await mountDrawer();
    await wrapper.setData({ emailBoxDrawer: true, currentFolder: 'SCHEDULED' });
    await wrapper.vm.loadEmailBox();

    expect(service.getEmailBox).toHaveBeenLastCalledWith('DRAFTS', false);
    expect(wrapper.vm.emails).toEqual([]);
    expect(wrapper.vm.canSearch).toBe(false);
    expect(wrapper.vm.folderCounts.SCHEDULED).toEqual({ count: 2, unread: false, attention: true });
    expect(wrapper.vm.scheduledViewSignal).toBe('2|true');
    await wrapper.vm.$nextTick();
    expect(wrapper.find('email-connector-mail-box-scheduled-list').exists()).toBe(true);
    expect(wrapper.find('email-connector-mail-box-drawer-filter-chips').exists()).toBe(false);
  });

  it('opens on the Scheduled view when asked from outside, without the inbox\'s default view', async () => {
    const { wrapper, service } = await mountDrawer();
    await wrapper.vm.open(false, 'SCHEDULED');
    expect(wrapper.vm.currentFolder).toBe('SCHEDULED');
    expect(wrapper.vm.categoryViewId).toBeNull();
    expect(service.getEmailBox).toHaveBeenLastCalledWith('DRAFTS', false);

    await wrapper.vm.open(false, 'CUSTOM:../../etc');
    expect(wrapper.vm.currentFolder).toBe('INBOX');
  });
});

describe('every code the backend answers with is said in the user\'s words (EXO-90434)', () => {
  /**
   * The keys of an _en bundle.
   *
   * @param {String} path the bundle's path under the resources
   * @returns {Set} its keys
   */
  function bundleKeys(path) {
    // eslint-disable-next-line no-undef
    const fs = require('fs');
    // eslint-disable-next-line no-undef
    const file = require('path').resolve(__dirname, '../../../../../../resources', path);
    // A test reads its fixture once, synchronously, like any require.
    // eslint-disable-next-line no-sync
    return new Set(fs.readFileSync(file, 'utf8').split('\n').filter(line => line.includes('=')).map(line => line.split('=')[0].trim()));
  }

  it('has a key for every error code, every reason code and every string the scheduled views use', () => {
    const keys = bundleKeys('locale/portlet/emailConnector/emailConnectorMailBox_en.properties');
    const codes = ['emailConnector.scheduled.locked', 'emailConnector.scheduled.sending', 'emailConnector.scheduled.uncertain',
      'emailConnector.scheduled.date.tooSoon', 'emailConnector.scheduled.date.tooFar', 'emailConnector.scheduled.timeZone.invalid',
      'emailConnector.scheduled.limitReached', 'emailConnector.scheduled.recipientsMandatory',
      'emailConnector.scheduled.attachmentsNotStored', 'emailConnector.scheduled.serverCopyRemains',
      'emailConnector.drafts.send.attachmentGone', 'emailConnector.drafts.send.gone'];
    const reasons = [...emailConnectorMailBoxService.NOT_SENT_REASONS, 'unknown'].map(reason => `emailConnector.mailBox.scheduled.error.${reason}`);
    const states = ['SCHEDULED', 'SENDING', 'FAILED', 'UNCERTAIN'].map(status => emailConnectorMailBoxService.scheduledStateLine({ status }))
      .filter(Boolean).map(line => line.key);
    const missing = [...codes, ...reasons, ...states, 'emailConnector.mailBox.list.drawer.folder.scheduled',
      'emailConnector.mailBox.scheduled.sendNow.retryLater', 'emailConnector.mailBox.list.drawer.thread.draft.notSent',
      'emailConnector.mailBox.list.drawer.folder.custom.serverScheduled'].filter(key => !keys.has(key));
    expect(missing).toEqual([]);
  });
});

describe('the Scheduled view ends a running search (EXO-90434)', () => {
  it('clears the search when switching to the view, which has none', async () => {
    const service = serviceStub({
      getEmailBox: jest.fn(() => Promise.resolve({ emails: [], folders: [], emailSyncStatus: 'SUCCESS' })),
      getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
    });
    const resetFilter = jest.fn();
    const wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
      mocks: {
        $t: translate,
        $emailConnectorMailBoxService: service,
        $emailConnectorCommonService: serviceStub({}),
        $vuetify: { breakpoint: {}, rtl: false },
      },
      stubs: { 'exo-drawer': { template: '<div><slot name="content" /></div>', methods: { open: jest.fn(), resetFilter } } },
    });
    await flush();
    await wrapper.setData({ emailBoxDrawer: true, searchTerm: 'invoice' });
    wrapper.vm.onSwitchFolder('SCHEDULED');
    await flush();
    expect(wrapper.vm.searchActive).toBe(false);
    expect(resetFilter).toHaveBeenCalled();
    expect(wrapper.find('email-connector-mail-box-scheduled-list').exists()).toBe(true);
  });
});
