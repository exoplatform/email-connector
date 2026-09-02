<!--
Copyright (C) 2026 eXo Platform SAS.

This program is free software: you can redistribute it and/or modify
it under the terms of the GNU Affero General Public License as published by
the Free Software Foundation, either version 3 of the License, or
(at your option) any later version.

This program is distributed in the hope that it will be useful,
but WITHOUT ANY WARRANTY; without even the implied warranty of
MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
GNU Affero General Public License for more details.

You should have received a copy of the GNU Affero General Public License
along with this program. If not, see <http://www.gnu.org/licenses/>.
-->
<!--
The sync tiering and executor rows of the synchronization settings drawer
(EXO-89947): the period of the inactive mailboxes, the activity threshold that
separates them from the active ones, the executor size, and a read-only status
line refreshed while the drawer is open. Extracted from the drawer only for
size — same idioms (v-list-item + consequence in the subtitle + inline control
saving on change, confirm-dialog on the consequential ones). It is rendered
inside the drawer's v-if'd content, so it is created on open and destroyed on
close: the status refresh timer lives exactly that long.
-->
<template>
  <div>
    <v-list-item dense class="px-0 height-auto mt-6">
      <v-list-item-content class="py-0">
        <v-list-item-title>
          {{ $t('emailConnector.admin.syncSettings.inactivePeriod.title') }}
        </v-list-item-title>
        <v-list-item-subtitle class="text-wrap text-light-color">
          {{ $t('emailConnector.admin.syncSettings.inactivePeriod.subtitle', { 0: activityThresholdLabel }) }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action class="my-0">
        <v-select
          :value="inactivePeriod"
          :items="inactivePeriodItems"
          :loading="savingInactivePeriod"
          :disabled="savingInactivePeriod"
          dense
          outlined
          hide-details
          class="flex-grow-0"
          style="max-width: 120px"
          @change="confirmInactivePeriodChange" />
      </v-list-item-action>
    </v-list-item>
    <v-list-item dense class="px-0 height-auto mt-6">
      <v-list-item-content class="py-0">
        <v-list-item-title>
          {{ $t('emailConnector.admin.syncSettings.activityThreshold.title') }}
        </v-list-item-title>
        <v-list-item-subtitle class="text-wrap text-light-color">
          {{ $t('emailConnector.admin.syncSettings.activityThreshold.subtitle') }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action class="my-0">
        <v-select
          :value="activityThresholdDays"
          :items="activityThresholdItems"
          :loading="savingActivityThreshold"
          :disabled="savingActivityThreshold"
          dense
          outlined
          hide-details
          class="flex-grow-0"
          style="max-width: 120px"
          @change="onActivityThresholdChange" />
      </v-list-item-action>
    </v-list-item>
    <v-list-item dense class="px-0 height-auto mt-6">
      <v-list-item-content class="py-0">
        <v-list-item-title>
          {{ $t('emailConnector.admin.syncSettings.threads.title') }}
        </v-list-item-title>
        <v-list-item-subtitle class="text-wrap text-light-color">
          {{ $t('emailConnector.admin.syncSettings.threads.subtitle') }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action class="my-0">
        <v-select
          :value="syncThreads"
          :items="syncThreadsOptions"
          :loading="savingSyncThreads"
          :disabled="savingSyncThreads"
          dense
          outlined
          hide-details
          class="flex-grow-0"
          style="max-width: 120px"
          @change="confirmSyncThreadsChange" />
      </v-list-item-action>
    </v-list-item>
    <v-list-item dense class="px-0 height-auto mt-6">
      <v-list-item-content class="py-0">
        <v-list-item-title>
          {{ $t('emailConnector.admin.syncSettings.status.title') }}
        </v-list-item-title>
        <v-list-item-subtitle class="text-wrap text-light-color sync-status-line">
          {{ statusLine }}
        </v-list-item-subtitle>
      </v-list-item-content>
    </v-list-item>
    <confirm-dialog
      ref="inactivePeriodConfirmDialog"
      :title="$t('emailConnector.admin.syncSettings.inactivePeriod.confirm.title')"
      :message="$t('emailConnector.admin.syncSettings.inactivePeriod.confirm.message')"
      :ok-label="$t('emailConnector.admin.syncSettings.confirm.button.confirm')"
      :cancel-label="$t('emailConnector.admin.syncSettings.confirm.button.cancel')"
      @ok="saveInactivePeriod"
      @closed="pendingInactivePeriod = null" />
    <confirm-dialog
      ref="syncThreadsConfirmDialog"
      :title="$t('emailConnector.admin.syncSettings.threads.confirm.title')"
      :message="$t('emailConnector.admin.syncSettings.threads.confirm.message')"
      :ok-label="$t('emailConnector.admin.syncSettings.confirm.button.confirm')"
      :cancel-label="$t('emailConnector.admin.syncSettings.confirm.button.cancel')"
      @ok="saveSyncThreads"
      @closed="pendingSyncThreads = null" />
  </div>
</template>

<script>
const MINUTES_PER_HOUR = 60;
const MINUTES_PER_DAY = 1440;
const STATUS_REFRESH_MILLIS = 30000;

export default {
  props: {
    // The active period the drawer's own row holds: an inactive period below
    // it is refused by the server (400), so those options are disabled here.
    activePeriod: {
      type: Number,
      default: null,
    },
  },
  data() {
    return {
      inactivePeriod: null,
      inactivePeriodOptions: [30, 60, 180, 360, 720, 1440],
      savingInactivePeriod: false,
      pendingInactivePeriod: null,
      activityThresholdDays: null,
      activityThresholdOptions: [3, 7, 14, 30, 60],
      savingActivityThreshold: false,
      syncThreads: null,
      syncThreadsOptions: [2, 4, 8, 10, 16, 24, 32, 48, 64],
      savingSyncThreads: false,
      pendingSyncThreads: null,
      status: null,
      statusInterval: null,
    };
  },
  computed: {
    inactivePeriodItems() {
      return this.inactivePeriodOptions.map(minutes => ({
        text: this.formatMinutes(minutes),
        value: minutes,
        disabled: this.activePeriod !== null && minutes < this.activePeriod,
      }));
    },
    activityThresholdItems() {
      return this.activityThresholdOptions.map(days => ({
        text: this.$t('emailConnector.admin.syncSettings.duration.days', { 0: days }),
        value: days,
      }));
    },
    activityThresholdLabel() {
      return this.activityThresholdDays === null ? '…' : this.activityThresholdDays;
    },
    statusLine() {
      return this.status && this.formatStatus(this.status) || '—';
    },
  },
  watch: {
    activePeriod() {
      // Saving an active period above the inactive one raises the inactive one
      // server-side to match (never the reverse): re-read it rather than guess.
      this.loadInactivePeriod();
    },
  },
  created() {
    this.loadSettings();
    this.loadStatus();
    this.statusInterval = window.setInterval(this.loadStatus, STATUS_REFRESH_MILLIS);
  },
  beforeDestroy() {
    window.clearInterval(this.statusInterval);
    this.statusInterval = null;
  },
  methods: {
    /**
     * Fetches the three settings in parallel, adding a stored value that is
     * not a preset to its list so the select can show it.
     *
     * @returns {void}
     */
    loadSettings() {
      this.loadInactivePeriod();
      this.$emailConnectorAdministrationService.getEmailBoxActivityThresholdDays()
        .then(days => {
          this.activityThresholdDays = days;
          this.activityThresholdOptions = this.withStoredValue(this.activityThresholdOptions, days);
        });
      this.$emailConnectorAdministrationService.getEmailSyncThreads()
        .then(threads => {
          this.syncThreads = threads;
          this.syncThreadsOptions = this.withStoredValue(this.syncThreadsOptions, threads);
        });
    },
    /**
     * (Re)reads the inactive period — on load, and again whenever the active
     * period changes, since the server clamps this one to it.
     *
     * @returns {void}
     */
    loadInactivePeriod() {
      this.$emailConnectorAdministrationService.getEmailBoxInactiveSyncPeriod()
        .then(minutes => {
          this.inactivePeriod = minutes;
          this.inactivePeriodOptions = this.withStoredValue(this.inactivePeriodOptions, minutes);
        });
    },
    /**
     * Reads the dispatcher snapshot for the status line. A failure leaves the
     * previous snapshot (or the dash) in place: the settings must stay usable
     * when the status endpoint is not.
     *
     * @returns {void}
     */
    loadStatus() {
      this.$emailConnectorAdministrationService.getEmailSyncStatus()
        .then(status => this.status = status)
        .catch(() => null);
    },
    /**
     * The preset list plus the stored value when it is not one of them, sorted.
     *
     * @param {Array<Number>} options the presets
     * @param {Number} value the stored value
     * @returns {Array<Number>} the options to offer
     */
    withStoredValue(options, value) {
      if (!Number.isFinite(value) || options.includes(value)) {
        return options;
      }
      return [...options, value].sort((first, second) => first - second);
    },
    /**
     * A period in minutes as a short label: minutes below an hour, whole hours
     * below a day, "1 day" at 1440.
     *
     * @param {Number} minutes the period
     * @returns {String} the label
     */
    formatMinutes(minutes) {
      if (minutes >= MINUTES_PER_DAY && minutes % MINUTES_PER_DAY === 0) {
        return this.$t('emailConnector.admin.syncSettings.duration.days', { 0: minutes / MINUTES_PER_DAY });
      } else if (minutes >= MINUTES_PER_HOUR && minutes % MINUTES_PER_HOUR === 0) {
        return this.$t('emailConnector.admin.syncSettings.duration.hours', { 0: minutes / MINUTES_PER_HOUR });
      }
      return this.$t('emailConnector.admin.syncSettings.duration.minutes', { 0: minutes });
    },
    /**
     * The status line: what this node runs and queues, the cluster-wide
     * backlog, the longest wait and the mailbox count.
     *
     * @param {Object} status the dispatcher snapshot (EmailSyncExecutorStatus)
     * @returns {String} the line
     */
    formatStatus(status) {
      return this.$t('emailConnector.admin.syncSettings.status.line', {
        0: this.formatCount(status.running),
        1: this.formatCount(status.queued),
        2: this.formatCount(status.dueBacklog),
        3: this.formatCount(status.oldestDueMinutes),
        4: this.formatCount(status.connectedMailboxes),
      });
    },
    /**
     * A count in the viewer's locale (thousands separators), zero when absent.
     *
     * @param {Number} value the count
     * @returns {String} the formatted count
     */
    formatCount(value) {
      const language = window.eXo?.env?.portal?.language || 'en';
      return Number(value || 0).toLocaleString(language);
    },
    /**
     * Opens the confirmation before applying an inactive period change — it
     * delays every inactive user's notifications by up to the new period.
     *
     * @param {Number} value the newly selected period, in minutes
     * @returns {void}
     */
    confirmInactivePeriodChange(value) {
      this.pendingInactivePeriod = value;
      this.$refs.inactivePeriodConfirmDialog.open();
    },
    /**
     * Applies the confirmed inactive period.
     *
     * @returns {void}
     */
    saveInactivePeriod() {
      const value = this.pendingInactivePeriod;
      this.savingInactivePeriod = true;
      this.$emailConnectorAdministrationService.saveEmailBoxInactiveSyncPeriod(value)
        .then(() => {
          this.inactivePeriod = value;
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.inactivePeriod.success'), 'info');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.inactivePeriod.error'), 'error'))
        .finally(() => this.savingInactivePeriod = false);
    },
    /**
     * Saves the activity threshold on change — no confirmation: it only moves
     * users between the two periods, both of which are visible right above.
     *
     * @param {Number} days the newly selected threshold
     * @returns {void}
     */
    onActivityThresholdChange(days) {
      this.savingActivityThreshold = true;
      this.$emailConnectorAdministrationService.saveEmailBoxActivityThresholdDays(days)
        .then(() => this.activityThresholdDays = days)
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.activityThreshold.error'), 'error'))
        .finally(() => this.savingActivityThreshold = false);
    },
    /**
     * Opens the confirmation before applying an executor size change — each
     * thread costs a mail-server login and a database connection on every
     * node.
     *
     * @param {Number} value the newly selected size
     * @returns {void}
     */
    confirmSyncThreadsChange(value) {
      this.pendingSyncThreads = value;
      this.$refs.syncThreadsConfirmDialog.open();
    },
    /**
     * Applies the confirmed executor size.
     *
     * @returns {void}
     */
    saveSyncThreads() {
      const value = this.pendingSyncThreads;
      this.savingSyncThreads = true;
      this.$emailConnectorAdministrationService.saveEmailSyncThreads(value)
        .then(() => {
          this.syncThreads = value;
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.threads.success'), 'info');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.threads.error'), 'error'))
        .finally(() => this.savingSyncThreads = false);
    },
  },
};
</script>
