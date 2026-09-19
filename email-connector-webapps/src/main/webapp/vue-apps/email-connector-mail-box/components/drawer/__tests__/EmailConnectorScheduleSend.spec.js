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

// EXO-90434 -- scheduled send, the composer's half: the split Send button and its date
// and time card (Social's scheduled-post UX), the schedule running Send's own checks and
// storing the draft first, and Edit taking a mail out of its schedule before opening it.

import Vue from 'vue';
import { mount, shallowMount } from '@vue/test-utils';
import EmailConnectorNewEmailDrawer from '../EmailConnectorNewEmailDrawer.vue';
import EmailConnectorScheduleSendPicker from '../EmailConnectorScheduleSendPicker.vue';
import EmailConnectorNewEmailDrawerNoSubjectConfirmPopup from '../EmailConnectorNewEmailDrawerNoSubjectConfirmPopup.vue';
import * as scheduledSendService from '../../../js/EmailConnectorScheduledSendService.js';

Vue.config.ignoredElements.push(/^email-connector-/, 'exo-drawer', 'rich-editor', 'exo-confirm-dialog', 'date-picker', 'time-picker');

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * The translation stand-in: the key, then its parameters.
 *
 * @param {String} key the key
 * @param {Object} params its parameters
 * @returns {String} the "translation"
 */
const translate = (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key);

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
 * A stand-in for exo-drawer that renders its named slots.
 *
 * @returns {Object} the component
 */
function exoDrawerStub() {
  return {
    name: 'exo-drawer',
    props: { value: { type: Boolean, default: false } },
    methods: { open: jest.fn(), close: jest.fn() },
    render(createElement) {
      return createElement('div', Object.keys(this.$slots).map(name => createElement('div', { attrs: { 'data-slot': name } }, this.$slots[name])));
    },
  };
}

// Vuetify's menu, rendering its activator and its content: left an unknown element, a
// menu would render neither.
const V_MENU = {
  props: { value: { type: Boolean, default: false } },
  render(createElement) {
    const activator = this.$scopedSlots.activator ? this.$scopedSlots.activator({ on: {}, attrs: {} }) : [];
    return createElement('div', { class: 'v-menu-stub' }, [...(activator || []), ...(this.$slots.default || [])]);
  },
};

const V_BTN = { template: '<button type="button" v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' };
const V_LIST_ITEM = { template: '<div v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></div>' };

/**
 * Mounts the composer, open, with a recipient, a subject and a body.
 *
 * @param {Object} answers the mailbox service's functions under test
 * @returns {Promise<Object>} {wrapper, service, emitted}
 */
async function mountComposer(answers = {}) {
  const service = serviceStub({
    formatScheduledDate: jest.fn(date => `date:${date}`),
    scheduledErrorMessage: scheduledSendService.scheduledErrorMessage,
    saveDraft: jest.fn(draft => Promise.resolve({ draftLocalId: 'draft-1', draftRevision: draft.draftRevision, draftState: 'LOCAL_ONLY' })),
    scheduleDraft: jest.fn((id, draft, scheduledDate, timeZone) => Promise.resolve({ draftLocalId: id, scheduledDate, timeZone, status: 'SCHEDULED' })),
    ...answers,
  });
  const wrapper = mount(EmailConnectorNewEmailDrawer, {
    mocks: {
      $t: translate,
      $te: key => key.startsWith('emailConnector.scheduled.'),
      $emailConnectorMailBoxService: service,
      $emailConnectorCommonService: serviceStub({ getEmailSignature: jest.fn(() => Promise.resolve(null)) }),
      $uploadService: serviceStub({}),
    },
    stubs: {
      'exo-drawer': exoDrawerStub(),
      'v-menu': V_MENU,
      'v-btn': V_BTN,
      'v-list-item': V_LIST_ITEM,
      'email-connector-recipient-field': true,
      'email-connector-new-email-drawer-attachments': true,
      'email-connector-schedule-picker': { props: ['value', 'loading', 'disabled', 'confirmLabel'], template: '<div class="picker-stub" />' },
    },
  });
  const emitted = [];
  const emit = wrapper.vm.$root.$emit.bind(wrapper.vm.$root);
  wrapper.vm.$root.$emit = (...args) => {
    emitted.push(args);
    return emit(...args);
  };
  await wrapper.setData({
    newEmailDrawer: true,
    to: [{ name: 'Bob', address: 'bob@host' }],
    email: { mailHeaderId: '<parent@host>', to: [], cc: [], bcc: [], subject: 'Hello', content: { body: '<p>Hi<blockquote>quoted</blockquote></p>' } },
  });
  return { wrapper, service, emitted };
}

/**
 * The alerts the composer raised, as [message, type].
 *
 * @param {Array} emitted the root events
 * @returns {Array} the alerts
 */
const alerts = emitted => emitted.filter(event => event[0] === 'alert-message').map(event => [event[1], event[2]]);

describe('the schedule picker (EXO-90434), Social\'s date and time card', () => {
  /**
   * Mounts the picker.
   *
   * @param {Object} propsData its props
   * @returns {Object} {wrapper, emitted}
   */
  function mountPicker(propsData = {}) {
    const wrapper = shallowMount(EmailConnectorScheduleSendPicker, {
      propsData,
      mocks: { $t: translate },
    });
    const emitted = [];
    wrapper.vm.$root.$emit = (...args) => emitted.push(args);
    return { wrapper, emitted };
  }

  it('starts tomorrow at 08:00, as Social\'s scheduled post does', () => {
    const { wrapper } = mountPicker();
    const expected = new Date();
    expected.setDate(expected.getDate() + 1);
    expected.setHours(8, 0, 0, 0);

    expect(wrapper.vm.scheduledDateTime).toBe(expected.getTime());
    // The date-picker and time-picker are the platform's own, not copies.
    expect(wrapper.find('date-picker').exists()).toBe(true);
    expect(wrapper.find('time-picker').exists()).toBe(true);
  });

  it('starts on the previous time when there is one still ahead, and on the default when it has passed', () => {
    const later = new Date(Date.now() + 3 * DAY_MS);
    later.setHours(17, 30, 0, 0);
    expect(mountPicker({ value: later.getTime() }).wrapper.vm.scheduledDateTime).toBe(later.getTime());

    const past = mountPicker({ value: Date.now() - DAY_MS }).wrapper.vm.scheduledDateTime;
    expect(new Date(past).getHours()).toBe(8);
    expect(past).toBeGreaterThan(Date.now());
  });

  it('keeps today\'s hours a minute ahead, and leaves any later day free', async () => {
    const { wrapper } = mountPicker();
    expect(wrapper.vm.minScheduleHour).toBeNull();

    await wrapper.setData({ scheduledDate: wrapper.vm.minScheduleDate });
    const min = wrapper.vm.minScheduleHour.getTime();
    expect(min).toBeGreaterThanOrEqual(Date.now() + 59 * 1000);
    expect(min).toBeLessThanOrEqual(Date.now() + 61 * 1000);
  });

  it('refuses a time in the past or less than a minute ahead, and one more than a year ahead', async () => {
    const { wrapper, emitted } = mountPicker();
    await wrapper.setData({ scheduledDate: wrapper.vm.minScheduleDate, scheduledHour: new Date(Date.now() - 60 * 60 * 1000) });
    wrapper.vm.confirm();
    expect(wrapper.emitted('confirm')).toBeFalsy();
    expect(emitted).toEqual([['alert-message', 'emailConnector.mailBox.newEmail.drawer.schedule.mustBeInFuture', 'warning']]);

    const far = new Date(Date.now() + 400 * DAY_MS);
    await wrapper.setData({ scheduledDate: `${far.getFullYear()}-${String(far.getMonth() + 1).padStart(2, '0')}-${String(far.getDate()).padStart(2, '0')}`, scheduledHour: far });
    wrapper.vm.confirm();
    expect(wrapper.emitted('confirm')).toBeFalsy();
    expect(emitted[1]).toEqual(['alert-message', 'emailConnector.scheduled.date.tooFar', 'warning']);
  });

  it('hands over the picked instant with the browser\'s zone, and says when it goes and in which zone', () => {
    const { wrapper } = mountPicker();
    wrapper.vm.confirm();

    const zone = new Intl.DateTimeFormat().resolvedOptions().timeZone;
    expect(wrapper.emitted('confirm')[0]).toEqual([wrapper.vm.scheduledDateTime, zone]);
    expect(wrapper.find('.schedule-picker-caption').text())
      .toBe(`emailConnector.mailBox.newEmail.drawer.schedule.sentAt|${scheduledSendService.formatScheduledTime(wrapper.vm.scheduledDateTime)}|${zone}`);
  });
});

describe('the composer\'s split Send button (EXO-90434)', () => {
  it('offers "Schedule send" beside Send, and opens the date and time card with it', async () => {
    const { wrapper } = await mountComposer();

    const action = wrapper.find('.schedule-send-action');
    expect(action.text()).toContain('emailConnector.mailBox.newEmail.drawer.schedule.label');
    expect(wrapper.find('.picker-stub').exists()).toBe(false);

    await action.trigger('click');
    expect(wrapper.vm.scheduleMode).toBe(true);
    expect(wrapper.find('.picker-stub').exists()).toBe(true);
  });

  it('greys out Send and its caret while the card is open, and sends nothing past it, until it closes', async () => {
    const { wrapper, service } = await mountComposer();
    await wrapper.find('.schedule-send-action').trigger('click');
    expect(wrapper.find('.composer-send-button').attributes('disabled')).toBe('disabled');
    expect(wrapper.find('.schedule-send-menu-button').attributes('disabled')).toBe('disabled');
    wrapper.vm.sendEmail();
    expect(service.sendEmail).not.toHaveBeenCalled();
    expect(service.sendDraft).not.toHaveBeenCalled();
    expect(wrapper.vm.loading).toBe(false);

    // Closed any way -- confirmed, cancelled, clicked away -- the card is scheduleMode
    // going false, and both come back.
    await wrapper.setData({ scheduleMode: false });
    expect(wrapper.find('.composer-send-button').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('.schedule-send-menu-button').attributes('disabled')).toBeUndefined();
  });

  it('disables the caret as it disables Send: no recipient, or a file still going up', async () => {
    const { wrapper } = await mountComposer();
    expect(wrapper.find('.schedule-send-menu-button').attributes('disabled')).toBeUndefined();

    await wrapper.setData({ attachments: [{ key: 'a', uploading: true }] });
    expect(wrapper.find('.schedule-send-menu-button').attributes('disabled')).toBe('disabled');
  });
});

describe('scheduling runs Send\'s checks, then stores the draft and freezes it (EXO-90434)', () => {
  it('schedules nothing without a recipient, or with an address the field refused', async () => {
    const { wrapper, service } = await mountComposer();
    await wrapper.setData({ to: [] , pendingTo: 'someone' });
    await wrapper.vm.scheduleEmail(Date.now() + DAY_MS, 'Europe/Paris');
    await wrapper.setData({ to: [{ address: 'bob@host' }], pendingTo: '', pendingCc: 'not an address' });
    await wrapper.vm.scheduleEmail(Date.now() + DAY_MS, 'Europe/Paris');

    expect(service.scheduleDraft).not.toHaveBeenCalled();
    expect(service.saveDraft).not.toHaveBeenCalled();
  });

  it('asks before scheduling a mail without subject, in a schedule\'s words, and schedules it on "go on"', async () => {
    const { wrapper, service, emitted } = await mountComposer();
    await wrapper.setData({ email: { ...wrapper.vm.email, subject: '' } });
    const date = Date.now() + DAY_MS;
    await wrapper.vm.scheduleEmail(date, 'Europe/Paris');

    const asked = emitted.find(event => event[0] === 'open-no-subject-email-confirm-popup');
    expect(asked[2].okLabel).toBe('emailConnector.mailBox.newEmail.drawer.confirmNoSubject.button.schedule');
    expect(service.scheduleDraft).not.toHaveBeenCalled();

    await asked[2].onConfirm();
    await flush();
    expect(service.scheduleDraft).toHaveBeenCalledWith('draft-1', expect.objectContaining({ subject: '' }), date, 'Europe/Paris');
  });

  it('refuses while a file is not on the draft yet: a scheduled mail is sent from its stored row', async () => {
    const { wrapper, service, emitted } = await mountComposer();
    await wrapper.setData({ attachments: [{ key: 'a', uploadId: 'u1', uploading: false }] });
    await wrapper.vm.scheduleEmail(Date.now() + DAY_MS, 'Europe/Paris');

    expect(service.scheduleDraft).not.toHaveBeenCalled();
    expect(alerts(emitted)).toEqual([['emailConnector.scheduled.attachmentsNotStored', 'warning']]);
  });

  it('creates the draft first, then posts the formatted body like the send, with the date and the zone', async () => {
    const { wrapper, service, emitted } = await mountComposer();
    await wrapper.setData({ cc: [{ address: 'carol@host' }], attachments: [{ key: 's', id: 5, stored: true, uploadId: null }] });
    const date = Date.now() + DAY_MS;
    await wrapper.vm.scheduleEmail(date, 'Europe/Paris');
    await flush();

    // The draft is created through the session's own queue, before anything is frozen.
    expect(service.saveDraft.mock.invocationCallOrder[0]).toBeLessThan(service.scheduleDraft.mock.invocationCallOrder[0]);
    const [id, draft, scheduledDate, timeZone] = service.scheduleDraft.mock.calls[0];
    expect([id, scheduledDate, timeZone]).toEqual(['draft-1', date, 'Europe/Paris']);
    expect(draft).toEqual({
      mailHeaderId: '<parent@host>',
      to: [{ address: 'bob@host' }],
      cc: [{ address: 'carol@host' }],
      bcc: [],
      subject: 'Hello',
      content: { body: wrapper.vm.formatEmailBody('<p>Hi<blockquote>quoted</blockquote></p>') },
      attachments: [],
    });
    expect(draft.content.body).toContain('border-left');
    expect(alerts(emitted)).toEqual([[`emailConnector.mailBox.newEmail.drawer.schedule.success|date:${date}`, 'success']]);
    expect(emitted.map(event => event[0])).toEqual(expect.arrayContaining(['scheduled-emails-changed', 'refresh-email-box']));
    // Closed and emptied: the close finds nothing to save back onto the frozen draft.
    expect(wrapper.vm.newEmailDrawer).toBe(false);
    expect(service.saveDraft).toHaveBeenCalledTimes(1);
  });

  it('schedules an existing draft under its own id, once its pending saves have landed', async () => {
    const { wrapper, service } = await mountComposer();
    wrapper.vm.draftSession.localId = 'draft-9';
    await wrapper.vm.scheduleEmail(Date.now() + DAY_MS, 'UTC');
    expect(service.saveDraft).not.toHaveBeenCalled();
    expect(service.scheduleDraft.mock.calls[0][0]).toBe('draft-9');
  });

  it('never sends beside a schedule in flight: Send waits, and a send asked meanwhile does nothing', async () => {
    const { wrapper, service } = await mountComposer();
    await wrapper.setData({ scheduling: true });
    expect(wrapper.find('.composer-send-button').attributes('disabled')).toBe('disabled');
    wrapper.vm.sendEmail();
    expect(service.sendEmail).not.toHaveBeenCalled();
    expect(service.sendDraft).not.toHaveBeenCalled();
    expect(wrapper.vm.loading).toBe(false);
  });

  it('says a refusal in the user\'s words and keeps the composer open', async () => {
    const refusedWith = code => jest.fn(() => {
      const error = new Error(code);
      error.code = code;
      error.status = 400;
      return Promise.reject(error);
    });
    const { wrapper, emitted } = await mountComposer({ scheduleDraft: refusedWith('emailConnector.scheduled.limitReached') });
    await wrapper.vm.scheduleEmail(Date.now() + DAY_MS, 'UTC');
    await flush();

    expect(alerts(emitted)).toEqual([['emailConnector.scheduled.limitReached', 'error']]);
    expect(wrapper.vm.newEmailDrawer).toBe(true);
    expect(wrapper.vm.email.subject).toBe('Hello');
    expect(wrapper.vm.scheduling).toBe(false);
  });
});

describe('Edit keeps a mail scheduled until Update, Outlook\'s way (EXO-90434)', () => {
  const DATE = Date.UTC(2026, 9, 1, 6, 0);
  const DRAFT = {
    draftLocalId: 'draft-7',
    threadId: 'thread-7',
    subject: 'Later',
    to: [{ address: 'bob@host' }],
    content: { body: '<p>x</p>', attachments: [{ id: 5, name: 'kept.pdf', mimeType: 'application/pdf', size: 3 }] },
    scheduled: true,
    scheduledDate: DATE,
    scheduledTimeZone: 'Europe/Paris',
    draftRevision: 4,
  };

  /**
   * Opens the composer on the scheduled mail, as the reader hands it over.
   *
   * @param {Object} answers the mailbox service's functions under test
   * @returns {Promise<Object>} {wrapper, service, emitted}
   */
  async function editing(answers = {}) {
    const mounted = await mountComposer({
      updateScheduledEmailContent: jest.fn((id, draft, removed, date, zone) => Promise.resolve({
        draftLocalId: id, scheduledDate: date || DATE, timeZone: zone || 'Europe/Paris', status: 'SCHEDULED',
      })),
      sendScheduledEmailNow: jest.fn(() => Promise.resolve({ status: 'SENT' })),
      ...answers,
    });
    await mounted.wrapper.setData({ newEmailDrawer: false });
    await mounted.wrapper.vm.editScheduledEmail({ draftLocalId: 'draft-7', scheduledDate: DATE, timeZone: 'Europe/Paris', draft: DRAFT });
    await mounted.wrapper.vm.$nextTick();
    await mounted.wrapper.vm.$nextTick();
    mounted.emitted.length = 0;
    return mounted;
  }

  const REQUESTS = ['cancelScheduledEmail', 'saveDraft', 'deleteDraft', 'addDraftAttachment', 'removeDraftAttachment',
    'updateScheduledEmailContent', 'sendScheduledEmailNow', 'sendDraft', 'sendEmail', 'scheduleDraft'];
  const requestsMade = service => REQUESTS.filter(name => service[name].mock.calls.length > 0);

  it('opens the mail still scheduled: no cancel, "Scheduled for {date}", Update, Send now and Change time', async () => {
    const { wrapper, service } = await editing();
    expect(requestsMade(service)).toEqual([]);
    expect(wrapper.vm.newEmailDrawer).toBe(true);
    expect(wrapper.vm.title).toBe('emailConnector.mailBox.newEmail.drawer.scheduled.title');
    expect(wrapper.find('.scheduled-edit-banner').text())
      .toBe(`emailConnector.mailBox.newEmail.drawer.schedule.scheduledFor|date:${DATE}`);
    const button = wrapper.find('.composer-send-button');
    expect(button.text()).toBe('emailConnector.mailBox.newEmail.drawer.scheduled.update');
    expect(button.attributes('disabled')).toBe('disabled');
    expect(wrapper.find('.scheduled-edit-send-now').exists()).toBe(true);
    expect(wrapper.find('.schedule-send-action').text()).toContain('emailConnector.mailBox.newEmail.drawer.scheduled.changeTime');
  });

  it('reads the mail from its conversation when the caller does not hold its row', async () => {
    const { wrapper, service } = await mountComposer({ getThreadByThreadId: jest.fn(() => Promise.resolve([{ draftLocalId: 'x' }, DRAFT])) });
    await wrapper.vm.editScheduledEmail({ draftLocalId: 'draft-7', scheduledDate: DATE, threadId: 'thread-7' });
    expect(service.getThreadByThreadId).toHaveBeenCalledWith('thread-7', 'DRAFTS');
    expect(wrapper.vm.email.subject).toBe('Later');
    expect(service.cancelScheduledEmail).not.toHaveBeenCalled();
  });

  it('closes without a change leaving it exactly as it was: no request at all', async () => {
    const { wrapper, service } = await editing();
    expect(wrapper.find('[data-slot]').element.parentElement.getAttribute('confirm-close')).toBeNull();
    wrapper.vm.close();
    await flush();
    expect(requestsMade(service)).toEqual([]);
    expect(wrapper.vm.scheduledEdit).toBeNull();
  });

  it('saves nothing while edited -- no autosave, no attach, no detach -- and asks before closing over the edits', async () => {
    const { wrapper, service } = await editing();
    await wrapper.setData({ email: { ...wrapper.vm.email, subject: 'Sooner' } });
    expect(wrapper.vm.localSaveTimer).toBeNull();
    expect(await wrapper.vm.persistAttachment({ uploadId: 'up-1', name: 'new.pdf', mimeType: 'application/pdf', size: 2 })).toBeNull();
    await wrapper.vm.unpersistAttachment({ id: 5, stored: true });
    expect(wrapper.vm.scheduledEdit.removedIds).toEqual([5]);
    expect(wrapper.vm.scheduledChanged).toBe(true);
    expect(wrapper.find('.composer-send-button').attributes('disabled')).toBeUndefined();
    expect(wrapper.find('[data-slot]').element.parentElement.getAttribute('confirm-close')).toBe('true');
    wrapper.vm.saveDraft(true);
    expect(requestsMade(service)).toEqual([]);

    // Discard changes: the edits go, the mail stays as it was.
    wrapper.vm.discardScheduledChanges();
    await flush();
    expect(requestsMade(service)).toEqual([]);
    expect(wrapper.vm.newEmailDrawer).toBe(false);
  });

  it('Update sends the edits in one atomic call with the same date, files taken off and added with them', async () => {
    const { wrapper, service, emitted } = await editing();
    await wrapper.setData({
      email: { ...wrapper.vm.email, subject: 'Sooner' },
      attachments: [{ key: 'u', uploadId: 'up-1', name: 'new.pdf', mimeType: 'application/pdf', size: 2 }],
    });
    await wrapper.vm.unpersistAttachment({ id: 5, stored: true });
    await wrapper.find('.composer-send-button').trigger('click');
    await flush();

    expect(requestsMade(service)).toEqual(['updateScheduledEmailContent']);
    const [id, draft, removed, date, zone] = service.updateScheduledEmailContent.mock.calls[0];
    expect([id, removed, date, zone]).toEqual(['draft-7', [5], null, null]);
    expect(draft.subject).toBe('Sooner');
    expect(draft.to).toEqual([{ address: 'bob@host' }]);
    expect(draft.attachments).toEqual([{ uploadId: 'up-1', name: 'new.pdf', mimeType: 'application/pdf', size: 2 }]);
    expect(alerts(emitted)).toEqual([[`emailConnector.mailBox.newEmail.drawer.scheduled.updated|date:${DATE}`, 'success']]);
    expect(emitted.map(event => event[0])).toEqual(expect.arrayContaining(['scheduled-emails-changed', 'refresh-email-box']));
    expect(wrapper.vm.newEmailDrawer).toBe(false);
  });

  it('Change time sends the edits with the new date, in the same one call', async () => {
    const { wrapper, service } = await editing();
    await wrapper.find('.schedule-send-action').trigger('click');
    expect(wrapper.find('.picker-stub').vm.$props.value).toBe(DATE);
    const later = DATE + DAY_MS;
    await wrapper.vm.onScheduleConfirmed(later, 'UTC');
    expect(requestsMade(service)).toEqual(['updateScheduledEmailContent']);
    expect(service.updateScheduledEmailContent.mock.calls[0].slice(3)).toEqual([later, 'UTC']);
    expect(service.scheduleDraft).not.toHaveBeenCalled();
  });

  it('Send now writes the edits first, then sends; unchanged, it only sends', async () => {
    const { wrapper, service, emitted } = await editing();
    await wrapper.setData({ email: { ...wrapper.vm.email, subject: 'Now' } });
    await wrapper.find('.scheduled-edit-send-now').trigger('click');
    await flush();
    expect(requestsMade(service)).toEqual(['updateScheduledEmailContent', 'sendScheduledEmailNow']);
    expect(service.updateScheduledEmailContent.mock.invocationCallOrder[0])
      .toBeLessThan(service.sendScheduledEmailNow.mock.invocationCallOrder[0]);
    expect(alerts(emitted)).toEqual([['emailConnector.mailBox.scheduled.sendNow.success', 'success']]);

    const unchanged = await editing();
    await unchanged.wrapper.vm.sendScheduledNow();
    expect(requestsMade(unchanged.service)).toEqual(['sendScheduledEmailNow']);
  });

  it('a mail that went out meanwhile (409) keeps the edits as a new draft, and says so', async () => {
    const conflict = Object.assign(new Error('emailConnector.scheduled.sending'), { status: 409, code: 'emailConnector.scheduled.sending' });
    const { wrapper, service, emitted } = await editing({ updateScheduledEmailContent: jest.fn(() => Promise.reject(conflict)) });
    await wrapper.setData({ email: { ...wrapper.vm.email, subject: 'Too late' } });
    await wrapper.vm.updateScheduled();
    await flush();

    expect(alerts(emitted)).toEqual([['emailConnector.mailBox.newEmail.drawer.scheduled.alreadySent', 'warning']]);
    expect(wrapper.vm.scheduledEdit).toBeNull();
    expect(wrapper.vm.newEmailDrawer).toBe(true);
    expect(service.saveDraft).toHaveBeenCalledTimes(1);
    const saved = service.saveDraft.mock.calls[0][0];
    expect(saved.draftLocalId).toBeFalsy();
    expect(saved.subject).toBe('Too late');
    expect(wrapper.vm.draftSession.localId).toBe('draft-1');
  });

  it('never resumes a draft still scheduled as an ordinary draft: it is frozen', async () => {
    const { wrapper, emitted } = await mountComposer();
    await wrapper.setData({ newEmailDrawer: false });
    wrapper.vm.resume(DRAFT);
    expect(wrapper.vm.newEmailDrawer).toBe(false);
    expect(alerts(emitted)).toEqual([['emailConnector.scheduled.locked', 'info']]);
  });

  it('forgets the scheduled mail once the composer opens on something else', async () => {
    const { wrapper } = await editing();
    wrapper.vm.resume({ ...DRAFT, scheduled: false });
    expect(wrapper.vm.scheduledEdit).toBeNull();
    await wrapper.vm.$nextTick();
    expect(wrapper.find('.composer-send-button').text()).toBe('emailConnector.mailBox.newEmail.drawer.send.label');
  });
});

describe('the composer knows when it holds something (EXO-89337, found with EXO-90434)', () => {
  it('offers Discard and saves the draft on close once it holds text: hasContent is defined', async () => {
    const { wrapper, service } = await mountComposer();
    expect(wrapper.vm.hasContent).toBe(true);
    await wrapper.vm.$nextTick();
    expect(wrapper.text()).toContain('emailConnector.mailBox.newEmail.drawer.discard.label');
    wrapper.vm.close();
    await flush();
    expect(service.saveDraft).toHaveBeenCalledTimes(1);
    expect(service.saveDraft.mock.calls[0][0].subject).toBe('Hello');
  });
});

describe('the no-subject question serves a schedule too (EXO-90434)', () => {
  it('runs the asking action instead of handing a send payload back, in its words', () => {
    const wrapper = shallowMount(EmailConnectorNewEmailDrawerNoSubjectConfirmPopup, {
      mocks: { $t: key => key },
      stubs: { 'exo-confirm-dialog': { template: '<div />', methods: { open: jest.fn() } } },
    });
    const emitted = [];
    wrapper.vm.$root.$emit = (...args) => emitted.push(args);
    const onConfirm = jest.fn();
    wrapper.vm.open(null, { okLabel: 'schedule', message: 'schedule?', onConfirm });
    wrapper.vm.sendEmail();

    expect(onConfirm).toHaveBeenCalledTimes(1);
    expect(emitted).toEqual([]);
    expect([wrapper.vm.okLabel, wrapper.vm.message]).toEqual(['schedule', 'schedule?']);

    wrapper.vm.open({ subject: '' });
    wrapper.vm.sendEmail();
    expect(emitted).toEqual([['send-email', { subject: '' }]]);
  });
});
