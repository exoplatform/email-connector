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

// EXO-90434 -- the web notification of a scheduled mail that was not sent: said from
// its SUBJECT and REASON code in the reader's language, opening the Scheduled view.

import Vue from 'vue';
import { shallowMount } from '@vue/test-utils';
import ScheduledEmailFailedNotificationPlugin from '../ScheduledEmailFailedNotificationPlugin.vue';

Vue.config.ignoredElements.push('user-notification-template');

const BUNDLE = {
  'scheduledEmailFailed.notification.title': 'A scheduled email needs your attention',
  'scheduledEmailFailed.notification.content': 'Your scheduled email "{0}": {1}',
  'scheduledEmailFailed.notification.noSubject': '(no subject)',
  'scheduledEmailFailed.notification.reason.RECIPIENT_REFUSED': 'it was not sent, the mail server refused a recipient.',
};

/**
 * Mounts the notification.
 *
 * @param {Object} parameters the notification's parameters
 * @returns {Object} the wrapper
 */
function mountNotification(parameters) {
  return shallowMount(ScheduledEmailFailedNotificationPlugin, {
    propsData: { notification: { parameters } },
    mocks: {
      $te: key => key in BUNDLE,
      $t: (key, params) => Object.entries(params || {}).reduce((text, [name, value]) => text.replace(`{${name}}`, value), BUNDLE[key] || key),
    },
    stubs: {
      'user-notification-template': {
        props: ['notification', 'url', 'message', 'loading'],
        template: '<div class="template"><slot name="avatar" /><slot name="actions" /></div>',
      },
      'v-icon': { template: '<i><slot /></i>' },
    },
  });
}

describe('the web notification of a scheduled mail not sent (EXO-90434)', () => {
  it('names the mail and the reason in the reader\'s language, the subject escaped', () => {
    const wrapper = mountNotification({ SUBJECT: '<b>Q3</b> & more', REASON: 'RECIPIENT_REFUSED', LINK: '/portal/x?openEmailBox=true' });
    expect(wrapper.find('.template').vm.$props.message).toBe(wrapper.vm.message);
    expect(wrapper.vm.message).toBe('Your scheduled email "&lt;b&gt;Q3&lt;/b&gt; &amp; more": it was not sent, the mail server refused a recipient.');
    expect(wrapper.vm.title).toBe('A scheduled email needs your attention');
    expect(wrapper.text()).toContain('A scheduled email needs your attention');
    expect(wrapper.vm.link).toBe('/portal/x?openEmailBox=true');
  });

  it('says "(no subject)" for a mail without one', () => {
    expect(mountNotification({ SUBJECT: '', REASON: 'RECIPIENT_REFUSED' }).vm.message)
      .toBe('Your scheduled email "(no subject)": it was not sent, the mail server refused a recipient.');
  });

  it('falls back on the sentence the server wrote for a reason the bundle does not know', () => {
    expect(mountNotification({ SUBJECT: 'x', REASON: 'SOMETHING_NEW', CONTENT: 'server sentence' }).vm.message).toBe('server sentence');
  });

  it('opens the mailbox on its Scheduled view', () => {
    const listener = jest.fn();
    document.addEventListener('open-email-box-folder', listener);
    mountNotification({ SUBJECT: 'x', REASON: 'NETWORK' }).trigger('click');
    document.removeEventListener('open-email-box-folder', listener);
    expect(listener).toHaveBeenCalledTimes(1);
    expect(listener.mock.calls[0][0].detail).toEqual({ folder: 'SCHEDULED' });
  });
});

describe('the notification is registered for its plugin (EXO-90434)', () => {
  it('joins the email group, and renders with its own component', () => {
    const registered = [];
    global.extensionRegistry = { registerExtension: (...args) => registered.push(args) };
    global.Vue = { options: { components: { 'user-notification-scheduled-email-failed': ScheduledEmailFailedNotificationPlugin } } };
    jest.isolateModules(() => {
      require('../../extensions.js');
    });
    delete global.extensionRegistry;
    delete global.Vue;

    const group = registered.find(([, type]) => type === 'notification-group-extension');
    expect(group[2].plugins).toContain('ScheduledEmailFailedNotificationPlugin');
    const content = registered.find(([, type, extension]) => type === 'notification-content-extension'
      && extension.type === 'ScheduledEmailFailedNotificationPlugin');
    expect(content[2].vueComponent).toBe(ScheduledEmailFailedNotificationPlugin);
  });
});
