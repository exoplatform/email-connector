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

describe('Edit takes a mail out of its schedule first, Gmail\'s way (EXO-90434)', () => {
  const DRAFT = { draftLocalId: 'draft-7', subject: 'Later', to: [{ address: 'bob@host' }], content: { body: '<p>x</p>', attachments: [] } };

  it('cancels the schedule, then opens the draft from Drafts with its time ready and the banner', async () => {
    const date = Date.now() + 2 * DAY_MS;
    const { wrapper, service } = await mountComposer({
      cancelScheduledEmail: jest.fn(() => Promise.resolve()),
      getEmailBox: jest.fn(() => Promise.resolve({ emails: [{ draftLocalId: 'other' }, DRAFT] })),
    });
    await wrapper.setData({ newEmailDrawer: false });
    await wrapper.vm.editScheduledEmail({ draftLocalId: 'draft-7', scheduledDate: date });

    expect(service.cancelScheduledEmail).toHaveBeenCalledWith('draft-7');
    expect(service.cancelScheduledEmail.mock.invocationCallOrder[0]).toBeLessThan(service.getEmailBox.mock.invocationCallOrder[0]);
    expect(service.getEmailBox).toHaveBeenCalledWith('DRAFTS');
    expect(wrapper.vm.newEmailDrawer).toBe(true);
    expect(wrapper.vm.draftSession.localId).toBe('draft-7');
    expect(wrapper.vm.email.subject).toBe('Later');
    expect(wrapper.vm.previousScheduledDate).toBe(date);
    await wrapper.vm.$nextTick();
    expect(wrapper.find('.unscheduled-banner').text()).toBe('emailConnector.mailBox.newEmail.drawer.schedule.unscheduled');
    // The picker starts on the previous time.
    await wrapper.setData({ scheduleMode: true });
    expect(wrapper.find('.picker-stub').exists()).toBe(true);
    expect(wrapper.find('.picker-stub').vm.$props.value).toBe(date);
  });

  it('opens nothing while the mail is being sent, and says why', async () => {
    const { wrapper, service, emitted } = await mountComposer({
      cancelScheduledEmail: jest.fn(() => {
        const error = new Error('emailConnector.scheduled.sending');
        error.code = 'emailConnector.scheduled.sending';
        error.status = 409;
        return Promise.reject(error);
      }),
    });
    await wrapper.setData({ newEmailDrawer: false });
    await wrapper.vm.editScheduledEmail({ draftLocalId: 'draft-7', scheduledDate: 1 });

    expect(service.getEmailBox).not.toHaveBeenCalled();
    expect(wrapper.vm.newEmailDrawer).toBe(false);
    expect(alerts(emitted)).toEqual([['emailConnector.scheduled.sending', 'error']]);
  });

  it('still opens the draft when its schedule was already gone', async () => {
    const { wrapper } = await mountComposer({
      cancelScheduledEmail: jest.fn(() => Promise.reject(Object.assign(new Error('gone'), { status: 404 }))),
      getEmailBox: jest.fn(() => Promise.resolve({ emails: [DRAFT] })),
    });
    await wrapper.vm.editScheduledEmail({ draftLocalId: 'draft-7', scheduledDate: 1 });
    expect(wrapper.vm.draftSession.localId).toBe('draft-7');
  });

  it('never resumes a draft still scheduled in place: it is frozen', async () => {
    const { wrapper, emitted } = await mountComposer();
    await wrapper.setData({ newEmailDrawer: false });
    wrapper.vm.resume({ ...DRAFT, scheduled: true });

    expect(wrapper.vm.newEmailDrawer).toBe(false);
    expect(alerts(emitted)).toEqual([['emailConnector.scheduled.locked', 'info']]);
  });

  it('forgets the previous time once the composer opens on something else', async () => {
    const { wrapper } = await mountComposer();
    await wrapper.setData({ previousScheduledDate: 123 });
    wrapper.vm.resume(DRAFT);
    expect(wrapper.vm.previousScheduledDate).toBeNull();
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
