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

// EXO-90434 -- the Scheduled view as the PO found it on the rig: its rows on the pane's
// own background, no loading bar of its own, a row that opens, and a Reschedule that
// works in the platform's popup.

import Vue from 'vue';
import { shallowMount } from '@vue/test-utils';
import EmailConnectorMailBoxScheduledList from '../EmailConnectorMailBoxScheduledList.vue';
import EmailConnectorMailBoxScheduledListItem from '../EmailConnectorMailBoxScheduledListItem.vue';
import EmailConnectorMailBoxDrawer from '../EmailConnectorMailBoxDrawer.vue';
import * as emailConnectorMailBoxService from '../../../js/EmailConnectorMailBoxService.js';

Vue.config.ignoredElements.push(/^email-connector-/, 'extension-registry-components', 'exo-confirm-dialog', 'exo-drawer', 'exo-modal');

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
 * Mounts the list, its reads answered by the given functions.
 *
 * @param {Object} answers the service's functions under test
 * @param {Object} propsData the list's props
 * @returns {Promise<Object>} {wrapper, service, emitted}
 */
async function mountList(answers = {}, propsData = {}) {
  const service = serviceStub({
    getScheduledEmails: jest.fn(() => Promise.resolve([scheduledRow('d1'), scheduledRow('d2')])),
    ...answers,
  });
  const wrapper = shallowMount(EmailConnectorMailBoxScheduledList, {
    propsData,
    mocks: { $t: translate, $te: () => false, $emailConnectorMailBoxService: service },
    stubs: {
      'exo-confirm-dialog': { template: '<div class="confirm" />', methods: { open: jest.fn() } },
    },
  });
  const emitted = [];
  const emit = wrapper.vm.$root.$emit.bind(wrapper.vm.$root);
  wrapper.vm.$root.$emit = (...args) => {
    emitted.push(args);
    return emit(...args);
  };
  await flush();
  return { wrapper, service, emitted };
}

/**
 * Mounts a row.
 *
 * @param {Object} propsData the row's props
 * @returns {Object} the wrapper
 */
function mountRow(propsData) {
  return shallowMount(EmailConnectorMailBoxScheduledListItem, {
    propsData,
    mocks: { $t: translate, $emailConnectorMailBoxService: emailConnectorMailBoxService },
    stubs: {
      'v-menu': { template: '<div><slot name="activator" :on="{}" :attrs="{}" /><slot /></div>' },
      'v-btn': { template: '<button type="button" v-bind="$attrs" @click="$emit(\'click\', $event)"><slot /></button>' },
    },
  });
}

describe('the Scheduled view sits on the pane\'s background and has no loading bar of its own (EXO-90434)', () => {
  it('paints no white surface: its list is transparent, like a folder\'s rows', async () => {
    const { wrapper } = await mountList();
    expect(wrapper.find('v-list').classes()).toContain('transparent');
  });

  it('shows no bar while it reads, and tells the drawer instead', async () => {
    let answerRead;
    const { wrapper } = await mountList({ getScheduledEmails: jest.fn(() => new Promise(resolve => answerRead = resolve)) });
    expect(wrapper.find('v-progress-linear').exists()).toBe(false);
    expect(wrapper.emitted('loading').slice(-1)).toEqual([[true]]);
    answerRead([scheduledRow('d1')]);
    await flush();
    expect(wrapper.emitted('loading').slice(-1)).toEqual([[false]]);
    wrapper.destroy();
    expect(wrapper.emitted('loading').slice(-1)).toEqual([[false]]);
  });

  it('tells the drawer while an action runs on a row, and the row shows no bar, its menu disabled', async () => {
    let answerCancel;
    const { wrapper } = await mountList({ cancelScheduledEmail: jest.fn(() => new Promise(resolve => answerCancel = resolve)) });
    wrapper.vm.cancel(scheduledRow('d1'));
    await wrapper.vm.$nextTick();
    expect(wrapper.emitted('loading').slice(-1)).toEqual([[true]]);
    answerCancel();
    await flush();
    expect(wrapper.emitted('loading').slice(-1)).toEqual([[false]]);

    const busy = mountRow({ scheduled: scheduledRow('d1'), busy: true });
    expect(busy.find('v-progress-linear').exists()).toBe(false);
    expect(busy.find('.scheduled-email-menu').attributes('disabled')).toBe('disabled');
  });

  it('feeds the drawer\'s own header bar, the only one', async () => {
    const service = serviceStub({
      getEmailBox: jest.fn(() => Promise.resolve({ emails: [], folders: [], emailSyncStatus: 'SUCCESS' })),
      getAvailableEmailCategories: jest.fn(() => Promise.resolve([])),
    });
    const drawerStub = { props: ['loading'], template: '<div><slot name="content" /></div>', methods: { open: jest.fn() } };
    const listStub = { template: '<div />' };
    const wrapper = shallowMount(EmailConnectorMailBoxDrawer, {
      mocks: {
        $t: translate,
        $emailConnectorMailBoxService: service,
        $emailConnectorCommonService: serviceStub({}),
        $vuetify: { breakpoint: {}, rtl: false },
      },
      stubs: {
        'exo-drawer': drawerStub,
        'email-connector-mail-box-scheduled-list': listStub,
      },
    });
    await flush();
    await wrapper.setData({ emailBoxDrawer: true, currentFolder: 'SCHEDULED', loading: false, syncInProgress: false });
    const drawer = wrapper.findComponent(drawerStub);
    expect(drawer.props('loading')).toBe(false);
    wrapper.findComponent(listStub).vm.$emit('loading', true);
    await wrapper.vm.$nextTick();
    expect(drawer.props('loading')).toBe(true);
  });
});
