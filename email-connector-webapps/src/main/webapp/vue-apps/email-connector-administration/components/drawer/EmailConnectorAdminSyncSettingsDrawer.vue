<!--
Copyright (C) 2025 eXo Platform SAS.

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
The single place every mailbox sync setting lives: the mailbox cache size
(moved in from its former standalone row) plus the sync period and the three
folder kill switches. Each control saves on change, like the row it replaces
did — there is no drawer-wide Save, because there is nothing to batch: every
setting is independent and administration-wide. The two settings whose change
has a real cost (cache size, sync period) confirm before saving; the switches
do not, because turning one off only stops a READ (see their subtitles).
-->
<template>
  <exo-drawer
    id="emailConnectorSyncSettingsDrawer"
    ref="emailConnectorSyncSettingsDrawer"
    right
    allow-expand
    @closed="close">
    <template #title>
      <span>{{ $t('emailConnector.admin.syncSettings.drawer.title') }}</span>
    </template>
    <template v-if="drawerOpened" #content>
      <div class="mx-5 mt-5">
        <v-list-item dense class="px-0">
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('emailConnector.admin.cacheSize.title') }}
            </v-list-item-title>
            <v-list-item-subtitle class="text-wrap text-light-color">
              {{ $t('emailConnector.admin.cacheSize.subtitle') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="my-0">
            <v-select
              :value="cacheSize"
              :items="cacheSizeOptions"
              :loading="savingCacheSize"
              :disabled="savingCacheSize"
              dense
              outlined
              hide-details
              class="flex-grow-0"
              style="max-width: 120px"
              @change="confirmCacheSizeChange" />
          </v-list-item-action>
        </v-list-item>
        <v-list-item dense class="px-0 mt-6">
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('emailConnector.admin.syncSettings.period.title') }}
            </v-list-item-title>
            <v-list-item-subtitle class="text-wrap text-light-color">
              {{ $t('emailConnector.admin.syncSettings.period.subtitle') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="my-0">
            <v-select
              :value="syncPeriod"
              :items="syncPeriodOptions"
              :loading="savingSyncPeriod"
              :disabled="savingSyncPeriod"
              dense
              outlined
              hide-details
              class="flex-grow-0"
              style="max-width: 120px"
              @change="confirmSyncPeriodChange" />
          </v-list-item-action>
        </v-list-item>
        <v-list-item dense class="px-0 height-auto mt-6">
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('emailConnector.admin.syncSettings.trash.title') }}
            </v-list-item-title>
            <v-list-item-subtitle class="text-wrap text-light-color">
              {{ $t('emailConnector.admin.syncSettings.trash.subtitle') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="my-0">
            <v-switch
              :input-value="trashSyncEnabled"
              :loading="savingTrash"
              :disabled="savingTrash"
              @change="onTrashSyncChange" />
          </v-list-item-action>
        </v-list-item>
        <v-list-item dense class="px-0 height-auto mt-6">
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('emailConnector.admin.syncSettings.junk.title') }}
            </v-list-item-title>
            <v-list-item-subtitle class="text-wrap text-light-color">
              {{ $t('emailConnector.admin.syncSettings.junk.subtitle') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="my-0">
            <v-switch
              :input-value="junkSyncEnabled"
              :loading="savingJunk"
              :disabled="savingJunk"
              @change="onJunkSyncChange" />
          </v-list-item-action>
        </v-list-item>
        <v-list-item dense class="px-0 height-auto mt-6">
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('emailConnector.admin.syncSettings.draftsServer.title') }}
            </v-list-item-title>
            <v-list-item-subtitle class="text-wrap text-light-color">
              {{ $t('emailConnector.admin.syncSettings.draftsServer.subtitle') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="my-0">
            <v-switch
              :input-value="draftsServerEnabled"
              :loading="savingDraftsServer"
              :disabled="savingDraftsServer"
              @change="onDraftsServerChange" />
          </v-list-item-action>
        </v-list-item>
        <v-list-item dense class="px-0 height-auto mt-6">
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('emailConnector.admin.syncSettings.customFolders.title') }}
            </v-list-item-title>
            <v-list-item-subtitle class="text-wrap text-light-color">
              {{ $t('emailConnector.admin.syncSettings.customFolders.subtitle') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="my-0">
            <v-switch
              :input-value="customFoldersEnabled"
              :loading="savingCustomFolders"
              :disabled="savingCustomFolders"
              @change="onCustomFoldersChange" />
          </v-list-item-action>
        </v-list-item>
      </div>
      <confirm-dialog
        ref="cacheSizeConfirmDialog"
        :title="$t('emailConnector.admin.cacheSize.confirm.title')"
        :message="$t('emailConnector.admin.cacheSize.confirm.message')"
        :ok-label="$t('emailConnector.admin.syncSettings.confirm.button.confirm')"
        :cancel-label="$t('emailConnector.admin.syncSettings.confirm.button.cancel')"
        @ok="saveCacheSize"
        @closed="pendingCacheSize = null" />
      <confirm-dialog
        ref="syncPeriodConfirmDialog"
        :title="$t('emailConnector.admin.syncSettings.period.confirm.title')"
        :message="$t('emailConnector.admin.syncSettings.period.confirm.message')"
        :ok-label="$t('emailConnector.admin.syncSettings.confirm.button.confirm')"
        :cancel-label="$t('emailConnector.admin.syncSettings.confirm.button.cancel')"
        @ok="saveSyncPeriod"
        @closed="pendingSyncPeriod = null" />
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      drawerOpened: false,
      cacheSize: null,
      cacheSizeOptions: [100, 250, 500, 750, 1000],
      savingCacheSize: false,
      pendingCacheSize: null,
      syncPeriod: null,
      // Floor at 5: at 5-minute periods and 1000 connected users, that is
      // already 200 logins/minute against the shared 25-thread Quartz pool —
      // shorter is not offered.
      syncPeriodOptions: [5, 10, 15, 30, 60],
      savingSyncPeriod: false,
      pendingSyncPeriod: null,
      trashSyncEnabled: true,
      savingTrash: false,
      junkSyncEnabled: true,
      savingJunk: false,
      draftsServerEnabled: true,
      savingDraftsServer: false,
      customFoldersEnabled: true,
      savingCustomFolders: false,
    };
  },
  created() {
    this.$root.$on('open-email-sync-settings-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-sync-settings-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer and (re)loads every setting it shows, so it never
     * displays a value stale from a previous open.
     *
     * @returns {void}
     */
    open() {
      this.drawerOpened = true;
      this.loadSettings();
      this.$refs.emailConnectorSyncSettingsDrawer.open();
    },
    /**
     * Closes the drawer. Content is only rendered while open ({@code v-if} on
     * the content slot), so nothing needs resetting here beyond that flag.
     *
     * @returns {void}
     */
    close() {
      this.drawerOpened = false;
    },
    /**
     * Fetches the six current values in parallel.
     *
     * @returns {void}
     */
    loadSettings() {
      this.$emailConnectorAdministrationService.getEmailBoxCacheSize()
        .then(size => {
          this.cacheSize = size;
          if (!this.cacheSizeOptions.includes(size)) {
            this.cacheSizeOptions = [...this.cacheSizeOptions, size].sort((first, second) => first - second);
          }
        });
      this.$emailConnectorAdministrationService.getEmailBoxSyncPeriod()
        .then(period => {
          this.syncPeriod = period;
          if (!this.syncPeriodOptions.includes(period)) {
            this.syncPeriodOptions = [...this.syncPeriodOptions, period].sort((first, second) => first - second);
          }
        });
      this.$emailConnectorAdministrationService.getTrashSyncEnabled()
        .then(enabled => this.trashSyncEnabled = enabled);
      this.$emailConnectorAdministrationService.getJunkSyncEnabled()
        .then(enabled => this.junkSyncEnabled = enabled);
      this.$emailConnectorAdministrationService.getServerDraftsEnabled()
        .then(enabled => this.draftsServerEnabled = enabled);
      this.$emailConnectorAdministrationService.getCustomFoldersEnabled()
        .then(enabled => this.customFoldersEnabled = enabled);
    },
    /**
     * Opens the confirmation before applying a cache size change — every
     * mailbox re-syncs fully once this saves.
     *
     * @param {Number} value the newly selected cache size
     * @returns {void}
     */
    confirmCacheSizeChange(value) {
      this.pendingCacheSize = value;
      this.$refs.cacheSizeConfirmDialog.open();
    },
    /**
     * Applies the confirmed cache size.
     *
     * @returns {void}
     */
    saveCacheSize() {
      const value = this.pendingCacheSize;
      this.savingCacheSize = true;
      this.$emailConnectorAdministrationService.updateEmailBoxCacheSize(value)
        .then(() => {
          this.cacheSize = value;
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.cacheSize.success'), 'info');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.cacheSize.error'), 'error'))
        .finally(() => this.savingCacheSize = false);
    },
    /**
     * Opens the confirmation before applying a sync period change — it
     * reschedules every connected user's mailbox sync job.
     *
     * @param {Number} value the newly selected period, in minutes
     * @returns {void}
     */
    confirmSyncPeriodChange(value) {
      this.pendingSyncPeriod = value;
      this.$refs.syncPeriodConfirmDialog.open();
    },
    /**
     * Applies the confirmed sync period.
     *
     * @returns {void}
     */
    saveSyncPeriod() {
      const value = this.pendingSyncPeriod;
      this.savingSyncPeriod = true;
      this.$emailConnectorAdministrationService.updateEmailBoxSyncPeriod(value)
        .then(() => {
          this.syncPeriod = value;
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.period.success'), 'info');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.period.error'), 'error'))
        .finally(() => this.savingSyncPeriod = false);
    },
    /**
     * Saves the Trash sync switch on change — no confirmation, it only
     * withdraws a read (see the row's subtitle).
     *
     * @param {Boolean} enabled the new switch value
     * @returns {void}
     */
    onTrashSyncChange(enabled) {
      this.savingTrash = true;
      this.$emailConnectorAdministrationService.updateTrashSyncEnabled(enabled)
        .then(() => this.trashSyncEnabled = enabled)
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.error'), 'error'))
        .finally(() => this.savingTrash = false);
    },
    /**
     * Saves the Junk sync switch on change.
     *
     * @param {Boolean} enabled the new switch value
     * @returns {void}
     */
    onJunkSyncChange(enabled) {
      this.savingJunk = true;
      this.$emailConnectorAdministrationService.updateJunkSyncEnabled(enabled)
        .then(() => this.junkSyncEnabled = enabled)
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.error'), 'error'))
        .finally(() => this.savingJunk = false);
    },
    /**
     * Saves the server-side drafts switch on change.
     *
     * @param {Boolean} enabled the new switch value
     * @returns {void}
     */
    onDraftsServerChange(enabled) {
      this.savingDraftsServer = true;
      this.$emailConnectorAdministrationService.updateServerDraftsEnabled(enabled)
        .then(() => this.draftsServerEnabled = enabled)
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.error'), 'error'))
        .finally(() => this.savingDraftsServer = false);
    },
    /**
     * Saves the custom-folders master switch on change. Unlike the other
     * three switches, this one withdraws the whole feature at once (see the
     * row's subtitle) — but nothing is deleted, so it still saves on change
     * rather than behind a confirmation: there is no data loss to warn about,
     * only a visibility change that reverses cleanly.
     *
     * @param {Boolean} enabled the new switch value
     * @returns {void}
     */
    onCustomFoldersChange(enabled) {
      this.savingCustomFolders = true;
      this.$emailConnectorAdministrationService.updateCustomFoldersEnabled(enabled)
        .then(() => this.customFoldersEnabled = enabled)
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.syncSettings.error'), 'error'))
        .finally(() => this.savingCustomFolders = false);
    },
  },
};
</script>
