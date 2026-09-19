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

// EXO-90435 -- read receipts, the reader's half: the "{sender} asked for a read receipt"
// banner and its two final answers, the automatic answer of an AUTO request once the
// message counts as displayed (and only then), and every refusal the server can give:
// askFirst brings the banner up, 409 drops it silently, the others say why.

import { mount, shallowMount } from '@vue/test-utils';
import EmailConnectorReadReceiptBanner from '../EmailConnectorReadReceiptBanner.vue';
import EmailConnectorMailBoxDrawerThreadContent from '../EmailConnectorMailBoxDrawerThreadContent.vue';
import EmailConnectorMailBoxDrawerListItemDetailContent from '../EmailConnectorMailBoxDrawerListItemDetailContent.vue';
import * as readReceiptService from '../../../js/EmailConnectorReadReceiptService.js';

const flush = () => new Promise(resolve => setTimeout(resolve, 0));

const V_BTN = { template: '<button type="button" v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' };
const V_ALERT = { template: '<div class="v-alert-stub" v-bind="$attrs"><slot /></div>' };

let nextId = 1000;

/**
 * A message asking for a read receipt, with a fresh technical id.
 *
 * @param {String} prompt the prompt the server computed
 * @returns {Object} the message
 */
function asking(prompt) {
  return {
    id: nextId++,
    mailRemoteId: 7,
    folder: 'INBOX',
    sender: { name: 'Bob', address: 'bob@partner.example' },
    readReceiptPrompt: prompt,
  };
}

/**
 * A refusal as the service's fetch turns it into an Error.
 *
 * @param {Number} status the HTTP status
 * @param {String} code the message code, if any
 * @returns {Error} the refusal
 */
function refused(status, code = null) {
  const error = new Error(code || 'refused');
  error.status = status;
  error.code = code;
  return error;
}

/**
 * Mounts the banner over a message.
 *
 * @param {Object} email the message
 * @param {Boolean} autoAllowed whether the message counts as displayed
 * @param {Function} respond what the answer's request does
 * @returns {Object} {wrapper, respond, emitted}
 */
function mountBanner(email, autoAllowed, respond = jest.fn(() => Promise.resolve(null))) {
  const service = {
    respondToReadReceipt: respond,
    // The once-per-page guard on the request double, and the real refusal rules.
    answerReadReceiptAutomatically: message => onceGuard(message, respond),
    readReceiptOutcome: readReceiptService.readReceiptOutcome,
  };
  const wrapper = mount(EmailConnectorReadReceiptBanner, {
    propsData: { email, autoAllowed },
    mocks: {
      $t: (key, params) => (params ? `${key}|${Object.values(params).join('|')}` : key),
      $emailConnectorMailBoxService: service,
    },
    stubs: { 'v-btn': V_BTN, 'v-alert': V_ALERT },
  });
  const emitted = [];
  wrapper.vm.$root.$emit = (...args) => emitted.push(args);
  return { wrapper, respond, emitted };
}

// The service's once-per-page guard, routed to the test's request double so its calls
// can be read: the same Set semantics as answerReadReceiptAutomatically.
const postedOnce = new Set();

/**
 * answerReadReceiptAutomatically, on the test's request double.
 *
 * @param {Object} email the message
 * @param {Function} respond the request double
 * @returns {Promise|null} the answer, or null when already posted
 */
function onceGuard(email, respond) {
  if (!email?.id || postedOnce.has(email.id)) {
    return null;
  }
  postedOnce.add(email.id);
  return respond(email.id, 'SEND', true);
}

describe('the read-receipt banner (EXO-90435)', () => {
  it('names the sender for an ASK request, and Send receipt answers SEND by hand', async () => {
    const email = asking('ASK');
    const { wrapper, respond } = mountBanner(email, true);
    expect(wrapper.find('.read-receipt-banner').text()).toContain('emailConnector.mailBox.readReceipt.banner|Bob');
    expect(respond).not.toHaveBeenCalled();

    await wrapper.find('.read-receipt-send').trigger('click');
    await flush();
    expect(respond).toHaveBeenCalledWith(email.id, 'SEND', false);
    expect(email.readReceiptPrompt).toBe('NONE');
    expect(wrapper.find('.read-receipt-banner').exists()).toBe(false);
  });

  it('answers IGNORE on Ignore, and falls back on the address when the sender has no name', async () => {
    const email = asking('ASK');
    email.sender = { address: 'bob@partner.example' };
    const { wrapper, respond } = mountBanner(email, true);
    expect(wrapper.text()).toContain('|bob@partner.example');
    await wrapper.find('.read-receipt-ignore').trigger('click');
    await flush();
    expect(respond).toHaveBeenCalledWith(email.id, 'IGNORE', false);
    expect(wrapper.find('.read-receipt-banner').exists()).toBe(false);
  });

  it('shows nothing and posts nothing for NONE', () => {
    const { wrapper, respond } = mountBanner(asking('NONE'), true);
    expect(wrapper.find('.read-receipt-banner').exists()).toBe(false);
    expect(respond).not.toHaveBeenCalled();
  });

  it('answers an AUTO request on its own, once, when the message counts as displayed', async () => {
    const email = asking('AUTO');
    const { wrapper, respond } = mountBanner(email, true);
    await flush();
    expect(respond).toHaveBeenCalledTimes(1);
    expect(respond).toHaveBeenCalledWith(email.id, 'SEND', true);
    expect(wrapper.find('.read-receipt-banner').exists()).toBe(false);

    // Rendered again with a stale AUTO (collapsed and expanded, a cached response):
    // never posted twice from this page.
    const again = mountBanner({ ...email, readReceiptPrompt: 'AUTO' }, true, respond);
    await flush();
    expect(respond).toHaveBeenCalledTimes(1);
    again.wrapper.destroy();
  });

  it('waits while the message does not count as displayed, and answers once it does', async () => {
    const email = asking('AUTO');
    const { wrapper, respond } = mountBanner(email, false);
    await flush();
    expect(respond).not.toHaveBeenCalled();
    expect(wrapper.find('.read-receipt-banner').exists()).toBe(false);

    await wrapper.setProps({ autoAllowed: true });
    await flush();
    expect(respond).toHaveBeenCalledWith(email.id, 'SEND', true);
  });

  it('shows the banner instead when the server will not answer on its own (askFirst)', async () => {
    const email = asking('AUTO');
    const { wrapper, emitted } = mountBanner(email, true, jest.fn(() => Promise.reject(refused(400, 'emailConnector.readReceipt.askFirst'))));
    await flush();
    await wrapper.vm.$nextTick();
    expect(email.readReceiptPrompt).toBe('ASK');
    expect(wrapper.find('.read-receipt-banner').exists()).toBe(true);
    expect(emitted).toEqual([]);
  });

  it('drops the banner silently when the request was answered elsewhere (409)', async () => {
    const email = asking('ASK');
    const { wrapper, emitted } = mountBanner(email, true, jest.fn(() => Promise.reject(refused(409, 'emailConnector.readReceipt.alreadyHandled'))));
    await wrapper.find('.read-receipt-send').trigger('click');
    await flush();
    expect(wrapper.find('.read-receipt-banner').exists()).toBe(false);
    expect(emitted).toEqual([]);

    const auto = asking('AUTO');
    const second = mountBanner(auto, true, jest.fn(() => Promise.reject(refused(409))));
    await flush();
    expect(auto.readReceiptPrompt).toBe('NONE');
    expect(second.emitted).toEqual([]);
  });

  it('says a failed send and keeps the banner so the user can try again', async () => {
    const email = asking('ASK');
    const { wrapper, emitted } = mountBanner(email, true, jest.fn(() => Promise.reject(refused(500, 'emailConnector.readReceipt.sendFailed'))));
    await wrapper.find('.read-receipt-send').trigger('click');
    await flush();
    expect(emitted).toEqual([['alert-message', 'emailConnector.mailBox.readReceipt.sendFailed', 'error']]);
    expect(wrapper.find('.read-receipt-banner').exists()).toBe(true);

    // An automatic one that failed becomes a question: the banner comes up.
    const auto = asking('AUTO');
    const second = mountBanner(auto, true, jest.fn(() => Promise.reject(refused(500, 'emailConnector.readReceipt.sendFailed'))));
    await flush();
    expect(auto.readReceiptPrompt).toBe('ASK');
    expect(second.emitted).toEqual([['alert-message', 'emailConnector.mailBox.readReceipt.sendFailed', 'error']]);
  });

  it('says an unconfirmed receipt and drops the banner: it is not sent again', async () => {
    const email = asking('ASK');
    const { wrapper, emitted } = mountBanner(email, true, jest.fn(() => Promise.reject(refused(500, 'emailConnector.readReceipt.unconfirmed'))));
    await wrapper.find('.read-receipt-send').trigger('click');
    await flush();
    expect(emitted).toEqual([['alert-message', 'emailConnector.mailBox.readReceipt.unconfirmed', 'warning']]);
    expect(wrapper.find('.read-receipt-banner').exists()).toBe(false);
  });

  it('says why a request cannot be answered any more, and drops the banner', async () => {
    const email = asking('ASK');
    const { wrapper, emitted } = mountBanner(email, true, jest.fn(() => Promise.reject(refused(400, 'emailConnector.readReceipt.notAllowed'))));
    await wrapper.find('.read-receipt-ignore').trigger('click');
    await flush();
    expect(emitted).toEqual([['alert-message', 'emailConnector.mailBox.readReceipt.notAllowed', 'info']]);
    expect(wrapper.find('.read-receipt-banner').exists()).toBe(false);
  });
});

describe('the read-receipt REST calls (EXO-90435)', () => {
  afterEach(() => {
    delete global.fetch;
  });

  it('posts the answer to the message\'s technical id, automatic flag included', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ ok: true, status: 204 }));
    await readReceiptService.respondToReadReceipt(42, 'IGNORE');
    expect(global.fetch).toHaveBeenCalledWith('/email-connector/rest/email-box/42/read-receipt', expect.objectContaining({
      method: 'POST',
      credentials: 'include',
      body: JSON.stringify({ action: 'IGNORE', automatic: false }),
    }));
  });

  it('turns a refusal into its code and status', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ ok: false, status: 400, text: () => Promise.resolve('{"message":"emailConnector.readReceipt.askFirst"}') }));
    await expect(readReceiptService.respondToReadReceipt(42, 'SEND', true)).rejects.toMatchObject({
      code: 'emailConnector.readReceipt.askFirst',
      status: 400,
    });
  });

  it('posts an automatic receipt once per message and page', async () => {
    global.fetch = jest.fn(() => Promise.resolve({ ok: true, status: 204 }));
    const email = { id: 4242, readReceiptPrompt: 'AUTO' };
    await readReceiptService.answerReadReceiptAutomatically(email);
    expect(readReceiptService.answerReadReceiptAutomatically({ ...email })).toBeNull();
    expect(readReceiptService.answerReadReceiptAutomatically({ readReceiptPrompt: 'AUTO' })).toBeNull();
    expect(global.fetch).toHaveBeenCalledTimes(1);
    expect(JSON.parse(global.fetch.mock.calls[0][1].body)).toEqual({ action: 'SEND', automatic: true });
  });
});

describe('when a conversation counts as displayed (EXO-90435, the EXO-90414 rule)', () => {
  /**
   * Mounts the reader on one message.
   *
   * @param {Boolean} deferThreadRead whether the drawer opened it on its own
   * @returns {Object} the wrapper
   */
  function mountReader(deferThreadRead) {
    const email = { id: 5, mailRemoteId: 7, folder: 'INBOX', threadId: 't', subject: 'S', to: [], sender: { name: 'Bob' } };
    return shallowMount(EmailConnectorMailBoxDrawerThreadContent, {
      propsData: { email, emails: [email], deferThreadRead },
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: new Proxy({}, { get: () => jest.fn(() => Promise.resolve(null)) }),
      },
    });
  }

  it('at once when the user opened it', () => {
    expect(mountReader(false).vm.receiptsReleased).toBe(true);
  });

  it('only once the drawer read the message it opened on its own, and for that message only', async () => {
    const wrapper = mountReader(true);
    expect(wrapper.vm.receiptsReleased).toBe(false);
    // Walking past it ends the wait without reading it: still not displayed.
    await wrapper.setProps({ deferThreadRead: false });
    expect(wrapper.vm.receiptsReleased).toBe(false);

    wrapper.vm.$root.$emit('email-read-on-display', { mailRemoteId: 7, folder: 'SENT' });
    expect(wrapper.vm.receiptsReleased).toBe(false);
    wrapper.vm.$root.$emit('email-read-on-display', { mailRemoteId: 7 });
    expect(wrapper.vm.receiptsReleased).toBe(true);
  });

  it('hands the rule down to the messages it renders expanded', async () => {
    const wrapper = mountReader(true);
    await flush();
    const message = () => wrapper.find('email-connector-mail-box-drawer-thread-message');
    expect(message().exists()).toBe(true);
    expect(message().attributes('receipt-auto-allowed')).toBeFalsy();
    wrapper.vm.$root.$emit('email-read-on-display', { mailRemoteId: 7, folder: 'INBOX' });
    await wrapper.vm.$nextTick();
    expect(message().attributes('receipt-auto-allowed')).toBe('true');
  });
});

describe('where the banner sits (EXO-90435)', () => {
  /**
   * Mounts the single-message renderer.
   *
   * @param {Object} email the message
   * @param {Object} props its other props
   * @returns {Object} the wrapper
   */
  function mountMessage(email, props = {}) {
    return shallowMount(EmailConnectorMailBoxDrawerListItemDetailContent, {
      propsData: { email, ...props },
      mocks: {
        $t: key => key,
        $emailConnectorMailBoxService: new Proxy({}, { get: () => jest.fn(() => '') }),
      },
    });
  }

  it('on every message the reader renders in full, carrying whether it counts as displayed', () => {
    const email = { ...asking('AUTO'), subject: 'S', to: [], cc: [], content: { body: '' } };
    const banner = mountMessage(email, { receiptAutoAllowed: true }).find('email-connector-read-receipt-banner');
    expect(banner.exists()).toBe(true);
    expect(banner.attributes('auto-allowed')).toBe('true');
    expect(mountMessage(email).find('email-connector-read-receipt-banner').attributes('auto-allowed')).toBeFalsy();
  });

  it('never on a mail scheduled to be sent: it is the user\'s own', () => {
    const draft = { draftLocalId: 'd', scheduled: true, subject: 'S', to: [], content: { body: '' }, sender: {} };
    expect(mountMessage(draft).find('email-connector-read-receipt-banner').exists()).toBe(false);
  });
});
