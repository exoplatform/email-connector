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

// EXO-89947 — the sync tiering and executor rows of the administration drawer.
// The rows live in EmailConnectorAdminSyncTieringRows (extracted from the drawer for
// size); the drawer itself is mounted once below to pin that the four period/tiering
// rows render together and that closing it tears the rows (and their status refresh
// timer) down. The administration service is mocked: these pins are about what the
// rows show and call, not about fetch.

import { mount, shallowMount } from '@vue/test-utils';
import EmailConnectorAdminSyncTieringRows from '../EmailConnectorAdminSyncTieringRows.vue';
import EmailConnectorAdminSyncSettingsDrawer from '../EmailConnectorAdminSyncSettingsDrawer.vue';

const STATUS = {
  node: 'node-1',
  running: 3,
  queued: 0,
  threads: 10,
  claimed: 3,
  dueBacklog: 7,
  oldestDueMinutes: 12,
  connectedMailboxes: 1040,
};

/**
 * A $t that keeps the placeholders visible: 'key' alone, or 'key{"0":value}' when
 * parameters are passed, so a rendered label can be asserted on both.
 *
 * @param {String} key the message key
 * @param {Object} params the placeholders, if any
 * @returns {String} the key, with the parameters appended as JSON when given
 */
function translate(key, params) {
  return params ? `${key}${JSON.stringify(params)}` : key;
}

/**
 * A mocked administration service answering the seven calls the rows make, with
 * every function a jest spy so the tests can assert on them.
 *
 * @param {Object} values the settings to answer, {inactive, threshold, threads}
 * @returns {Object} the service mock
 */
function mockService({ inactive = 60, threshold = 14, threads = 10 } = {}) {
  return {
    getEmailBoxCacheSize: jest.fn(() => Promise.resolve(500)),
    getEmailBoxSyncPeriod: jest.fn(() => Promise.resolve(10)),
    getTrashSyncEnabled: jest.fn(() => Promise.resolve(true)),
    getJunkSyncEnabled: jest.fn(() => Promise.resolve(true)),
    getServerDraftsEnabled: jest.fn(() => Promise.resolve(true)),
    getCustomFoldersEnabled: jest.fn(() => Promise.resolve(true)),
    getEmailBoxInactiveSyncPeriod: jest.fn(() => Promise.resolve(inactive)),
    saveEmailBoxInactiveSyncPeriod: jest.fn(() => Promise.resolve()),
    getEmailBoxActivityThresholdDays: jest.fn(() => Promise.resolve(threshold)),
    saveEmailBoxActivityThresholdDays: jest.fn(() => Promise.resolve()),
    getEmailSyncThreads: jest.fn(() => Promise.resolve(threads)),
    saveEmailSyncThreads: jest.fn(() => Promise.resolve()),
    getEmailSyncStatus: jest.fn(() => Promise.resolve(STATUS)),
  };
}

// confirm-dialog and exo-drawer are platform components registered at runtime; the
// rows only need `open()` on their refs, the drawer needs its content slot rendered.
// Vue binds a component's methods, so a jest.fn() placed directly as the stub's
// `open` could not be asserted on (the bound wrapper is what gets called): the stub
// forwards to this shared spy instead.
const confirmDialogOpened = jest.fn();
const confirmDialogStub = {
  template: '<div class="confirm-dialog-stub" />',
  methods: {
    open() {
      confirmDialogOpened();
    },
  },
};
const exoDrawerStub = {
  template: '<div><slot name="title" /><slot name="content" /></div>',
  methods: { open: jest.fn() },
};

/**
 * Mounts the rows as the drawer does, with a spy standing in for $root.$emit.
 *
 * @param {Object} service the mocked administration service
 * @param {Number} activePeriod the active period the drawer passes down
 * @returns {Object} {wrapper, emit}
 */
function mountRows(service, activePeriod = 10) {
  const wrapper = shallowMount(EmailConnectorAdminSyncTieringRows, {
    propsData: { activePeriod },
    mocks: {
      $t: translate,
      $emailConnectorAdministrationService: service,
    },
    stubs: { 'confirm-dialog': confirmDialogStub },
  });
  const emit = jest.fn();
  wrapper.vm.$root.$emit = emit;
  return { wrapper, emit };
}

/**
 * Lets every pending promise callback of the mocked service run. Drains the
 * microtask queue a few times over rather than waiting on a timer: the timers are
 * faked in these tests, so a setTimeout-based flush would never fire.
 *
 * @returns {Promise<void>} resolved once the chained callbacks ran
 */
function flushPromises() {
  return Array.from({ length: 10 })
    .reduce(chain => chain.then(() => Promise.resolve()), Promise.resolve());
}

describe('EmailConnectorAdminSyncTieringRows', () => {
  beforeEach(() => {
    jest.useFakeTimers();
    confirmDialogOpened.mockClear();
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  it('renders the three rows with the loaded values and adds a stored value that is not a preset', async () => {
    const service = mockService({ inactive: 90, threshold: 14, threads: 10 });
    const { wrapper } = mountRows(service);
    await flushPromises();
    expect(wrapper.vm.inactivePeriod).toBe(90);
    expect(wrapper.vm.activityThresholdDays).toBe(14);
    expect(wrapper.vm.syncThreads).toBe(10);
    expect(wrapper.vm.inactivePeriodOptions).toEqual([30, 60, 90, 180, 360, 720, 1440]);
    expect(wrapper.vm.activityThresholdOptions).toEqual([3, 7, 14, 30, 60]);
    expect(wrapper.vm.syncThreadsOptions).toEqual([2, 4, 8, 10, 16, 24, 32, 48, 64]);
    const html = wrapper.html();
    expect(html).toContain('emailConnector.admin.syncSettings.inactivePeriod.title');
    expect(html).toContain('emailConnector.admin.syncSettings.activityThreshold.title');
    expect(html).toContain('emailConnector.admin.syncSettings.threads.title');
    // the inactive subtitle names the threshold it depends on
    expect(html).toContain('emailConnector.admin.syncSettings.inactivePeriod.subtitle{"0":14}');
  });

  it('disables the inactive period options below the active period (the server would answer 400)', async () => {
    const { wrapper } = mountRows(mockService(), 60);
    await flushPromises();
    const items = wrapper.vm.inactivePeriodItems;
    expect(items.find(item => item.value === 30).disabled).toBe(true);
    expect(items.find(item => item.value === 60).disabled).toBe(false);
    expect(items.find(item => item.value === 1440).disabled).toBe(false);
    // labels: minutes below an hour, hours below a day, days at 1440
    expect(items.find(item => item.value === 30).text).toBe('emailConnector.admin.syncSettings.duration.minutes{"0":30}');
    expect(items.find(item => item.value === 180).text).toBe('emailConnector.admin.syncSettings.duration.hours{"0":3}');
    expect(items.find(item => item.value === 1440).text).toBe('emailConnector.admin.syncSettings.duration.days{"0":1}');
  });

  it('re-reads the inactive period when the active period changes (the server raises it to match)', async () => {
    const service = mockService({ inactive: 60 });
    const { wrapper } = mountRows(service, 10);
    await flushPromises();
    expect(service.getEmailBoxInactiveSyncPeriod).toHaveBeenCalledTimes(1);
    service.getEmailBoxInactiveSyncPeriod.mockImplementation(() => Promise.resolve(180));
    await wrapper.setProps({ activePeriod: 180 });
    await flushPromises();
    expect(service.getEmailBoxInactiveSyncPeriod).toHaveBeenCalledTimes(2);
    expect(wrapper.vm.inactivePeriod).toBe(180);
  });

  it('saves "Inactive after" on change, without a confirmation', async () => {
    const service = mockService();
    const { wrapper, emit } = mountRows(service);
    await flushPromises();
    wrapper.vm.onActivityThresholdChange(30);
    await flushPromises();
    expect(service.saveEmailBoxActivityThresholdDays).toHaveBeenCalledWith(30);
    expect(wrapper.vm.activityThresholdDays).toBe(30);
    expect(emit).not.toHaveBeenCalled();
  });

  it('surfaces a refused "Inactive after" save (400) as an error alert and keeps the old value', async () => {
    const service = mockService({ threshold: 14 });
    service.saveEmailBoxActivityThresholdDays.mockImplementation(() => Promise.reject(new Error('400')));
    const { wrapper, emit } = mountRows(service);
    await flushPromises();
    wrapper.vm.onActivityThresholdChange(400);
    await flushPromises();
    expect(emit).toHaveBeenCalledWith('alert-message', 'emailConnector.admin.syncSettings.activityThreshold.error', 'error');
    expect(wrapper.vm.activityThresholdDays).toBe(14);
  });

  it('confirms before saving the inactive period and the thread count', async () => {
    const service = mockService();
    const { wrapper, emit } = mountRows(service);
    await flushPromises();
    wrapper.vm.confirmInactivePeriodChange(180);
    expect(service.saveEmailBoxInactiveSyncPeriod).not.toHaveBeenCalled();
    expect(confirmDialogOpened).toHaveBeenCalledTimes(1);
    wrapper.vm.saveInactivePeriod();
    await flushPromises();
    expect(service.saveEmailBoxInactiveSyncPeriod).toHaveBeenCalledWith(180);
    expect(wrapper.vm.inactivePeriod).toBe(180);
    expect(emit).toHaveBeenCalledWith('alert-message', 'emailConnector.admin.syncSettings.inactivePeriod.success', 'info');

    wrapper.vm.confirmSyncThreadsChange(24);
    expect(service.saveEmailSyncThreads).not.toHaveBeenCalled();
    expect(confirmDialogOpened).toHaveBeenCalledTimes(2);
    wrapper.vm.saveSyncThreads();
    await flushPromises();
    expect(service.saveEmailSyncThreads).toHaveBeenCalledWith(24);
    expect(wrapper.vm.syncThreads).toBe(24);
  });

  it('renders the status line from the dispatcher snapshot', async () => {
    const { wrapper } = mountRows(mockService());
    await flushPromises();
    expect(wrapper.find('.sync-status-line').text())
      .toBe('emailConnector.admin.syncSettings.status.line{"0":"3","1":"0","2":"7","3":"12","4":"1,040"}');
  });

  it('shows a dash when the status cannot be loaded, and the rows still work', async () => {
    const service = mockService();
    service.getEmailSyncStatus.mockImplementation(() => Promise.reject(new Error('503')));
    const { wrapper } = mountRows(service);
    await flushPromises();
    expect(wrapper.find('.sync-status-line').text()).toBe('—');
    expect(wrapper.vm.syncThreads).toBe(10);
  });

  it('refreshes the status every 30 s while mounted and stops once destroyed', async () => {
    const service = mockService();
    const { wrapper } = mountRows(service);
    await flushPromises();
    expect(service.getEmailSyncStatus).toHaveBeenCalledTimes(1);
    jest.advanceTimersByTime(30000);
    expect(service.getEmailSyncStatus).toHaveBeenCalledTimes(2);
    jest.advanceTimersByTime(30000);
    expect(service.getEmailSyncStatus).toHaveBeenCalledTimes(3);
    wrapper.destroy();
    jest.advanceTimersByTime(90000);
    expect(service.getEmailSyncStatus).toHaveBeenCalledTimes(3);
    expect(wrapper.vm.statusInterval).toBeNull();
  });
});

describe('EmailConnectorAdminSyncSettingsDrawer', () => {
  beforeEach(() => {
    jest.useFakeTimers();
  });
  afterEach(() => {
    jest.useRealTimers();
  });

  /**
   * Mounts the whole drawer with its platform wrappers stubbed, so the content
   * slot (and the tiering rows inside it) really renders.
   *
   * @param {Object} service the mocked administration service
   * @returns {Object} the mounted wrapper
   */
  function mountDrawer(service) {
    return mount(EmailConnectorAdminSyncSettingsDrawer, {
      mocks: {
        $t: translate,
        $emailConnectorAdministrationService: service,
      },
      stubs: {
        'exo-drawer': exoDrawerStub,
        'confirm-dialog': confirmDialogStub,
        // registered globally by initComponents.js at runtime, never imported by the
        // drawer itself — the real component stands in for its own tag here
        'email-connector-admin-sync-tiering-rows': EmailConnectorAdminSyncTieringRows,
      },
    });
  }

  it('shows the active period row and the three tiering rows together once opened, and tears the rows down on close', async () => {
    const service = mockService();
    const wrapper = mountDrawer(service);
    expect(wrapper.findComponent(EmailConnectorAdminSyncTieringRows).exists()).toBe(false);

    wrapper.vm.open();
    await flushPromises();
    const html = wrapper.html();
    expect(html).toContain('emailConnector.admin.syncSettings.period.title');
    expect(html).toContain('emailConnector.admin.syncSettings.inactivePeriod.title');
    expect(html).toContain('emailConnector.admin.syncSettings.activityThreshold.title');
    expect(html).toContain('emailConnector.admin.syncSettings.threads.title');
    expect(html).toContain('emailConnector.admin.syncSettings.status.title');
    const rows = wrapper.findComponent(EmailConnectorAdminSyncTieringRows);
    expect(rows.props('activePeriod')).toBe(10);
    expect(service.getEmailSyncStatus).toHaveBeenCalledTimes(1);

    wrapper.vm.close();
    await wrapper.vm.$nextTick();
    expect(wrapper.findComponent(EmailConnectorAdminSyncTieringRows).exists()).toBe(false);
    jest.advanceTimersByTime(60000);
    expect(service.getEmailSyncStatus).toHaveBeenCalledTimes(1);
  });
});
