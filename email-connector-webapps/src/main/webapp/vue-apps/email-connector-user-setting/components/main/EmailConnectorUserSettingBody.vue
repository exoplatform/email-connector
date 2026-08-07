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
<template>
  <div class="application-body">
    <v-list two-line>
      <v-list-item>
        <v-list-item-content>
          <v-list-item-title class="text-title">
            {{ $t('UserSettings.emailConnector.title') }}
          </v-list-item-title>
        </v-list-item-content>
      </v-list-item>
      <v-list-item>
        <v-list-item-content>
          <v-list-item-title v-if="!userEmailSetting.connected">
            {{ $t('UserSettings.emailConnector.description') }}
          </v-list-item-title>
          <div v-else>
            <email-connector-icon
              :image-url="userEmailSetting.emailConnectorImageUrl"
              :icon="userEmailSetting.emailConnectorIcon"
              icon-size="24"
              class="me-3" />
            <span>{{ userEmailSetting.emailAddress }}</span>
          </div>
        </v-list-item-content>
        <v-list-item-action>
          <email-box-sync-loader
            v-if="syncInProgress"
            :label="$t('UserSettings.emailConnector.sync.tooltip')" />
          <v-btn
            v-else
            icon
            :title="$t('UserSettings.emailConnector.connectors.drawer.connector.button.edit.tooltip')"
            @click="$root.$emit('open-user-setting-connectors-drawer')">
            <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
          </v-btn>
        </v-list-item-action>
      </v-list-item>
      <template v-if="userEmailSetting && userEmailSetting.connected">
        <v-divider class="mx-4" />
        <v-list-item>
          <v-list-item-content>
            <v-list-item-title class="text-color">
              {{ $t('UserSettings.emailConnector.defaultView.title') }}
            </v-list-item-title>
            <v-list-item-subtitle>
              {{ $t('UserSettings.emailConnector.defaultView.description') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action>
            <v-switch
              v-model="openOnImportant"
              :loading="saving"
              :disabled="!importantCategory"
              @change="save" />
          </v-list-item-action>
        </v-list-item>
        <v-list-item>
          <v-list-item-content>
            <v-list-item-title class="text-color">
              {{ $t('UserSettings.emailConnector.notifications.title') }}
            </v-list-item-title>
            <v-list-item-subtitle>
              {{ $t('UserSettings.emailConnector.notifications.all') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action>
            <v-switch
              v-model="notifyAll"
              :loading="saving"
              @change="save" />
          </v-list-item-action>
        </v-list-item>
        <v-list-item v-if="!notifyAll">
          <v-list-item-content>
            <v-list-item-subtitle>
              {{ $t('UserSettings.emailConnector.notifications.categories') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="my-0">
            <v-chip-group
              v-model="notifyCategoryIds"
              class="justify-end"
              multiple
              column
              @change="save">
              <v-chip
                v-for="category in categories"
                :key="category.id"
                :value="category.id"
                filter
                small
                outlined>
                {{ category.name }}
              </v-chip>
            </v-chip-group>
          </v-list-item-action>
        </v-list-item>
        <!-- Last, and after the notification categories: those chips belong to the
             Notifications switch above them, and a block inserted between the two
             separated a setting from its own detail. Shown only when the bound
             provider actually has an address book, because a switch for something
             that can never work is worse than no switch, and off by default since
             each enabled user costs the server a request per sync period. -->
        <v-list-item v-if="carddavAvailable">
          <v-list-item-content>
            <v-list-item-title class="text-color">
              {{ $t('UserSettings.emailConnector.addressBook.title') }}
            </v-list-item-title>
            <v-list-item-subtitle>
              {{ $t('UserSettings.emailConnector.addressBook.description') }}
            </v-list-item-subtitle>
            <v-list-item-subtitle
              v-if="carddavEnabled && addressBookStatus"
              class="mt-1">
              {{ addressBookStatus }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="d-flex flex-row align-center">
            <v-btn
              v-if="carddavEnabled"
              :loading="syncingAddressBook"
              :title="$t('UserSettings.emailConnector.addressBook.syncNow')"
              icon
              @click="syncAddressBook">
              <v-icon size="18">
                fas fa-sync
              </v-icon>
            </v-btn>
            <v-switch
              v-model="carddavEnabled"
              :loading="savingAddressBook"
              class="ms-2"
              @change="saveAddressBook" />
          </v-list-item-action>
        </v-list-item>
        <v-divider class="mx-4" />
        <v-list-item>
          <v-list-item-content>
            <v-list-item-title class="text-color">
              {{ $t('UserSettings.emailConnector.reset.title') }}
            </v-list-item-title>
            <v-list-item-subtitle>
              {{ $t('UserSettings.emailConnector.reset.description') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action>
            <v-btn
              :loading="resetting"
              :disabled="syncInProgress"
              color="error"
              outlined
              small
              @click="confirmReset">
              {{ $t('UserSettings.emailConnector.reset.button') }}
            </v-btn>
          </v-list-item-action>
        </v-list-item>
      </template>
    </v-list>
    <exo-confirm-dialog
      ref="resetConfirmDialog"
      :title="$t('UserSettings.emailConnector.reset.confirm.title')"
      :message="$t('UserSettings.emailConnector.reset.confirm.message')"
      :ok-label="$t('UserSettings.emailConnector.reset.confirm.ok')"
      :cancel-label="$t('UserSettings.emailConnector.reset.confirm.cancel')"
      @ok="doReset" />
  </div>
</template>

<script>
export default {
  props: {
    userEmailSetting: {
      type: Object,
      default: null,
    },
  },
  data: () => ({
    categories: [],
    defaultCategoryView: null,
    notifyAll: true,
    carddavAvailable: false,
    carddavEnabled: false,
    savingAddressBook: false,
    syncingAddressBook: false,
    addressBookSyncState: null,
    notifyCategoryIds: [],
    saving: false,
    resetting: false,
  }),
  computed: {
    /**
     * The one-line account of the last sync, or nothing when it has never run.
     *
     * @returns {string} the localized status
     */
    addressBookStatus() {
      const status = this.addressBookSyncState?.status;
      if (!status) {
        return '';
      }
      if (status === 'BLOCKED') {
        return this.$t('UserSettings.emailConnector.addressBook.status.blocked');
      }
      if (status === 'FAILURE') {
        return this.$t('UserSettings.emailConnector.addressBook.status.failed');
      }
      return this.$t('UserSettings.emailConnector.addressBook.status.ok');
    },
    syncInProgress() {
      return this.userEmailSetting?.emailSyncStatus === 'IN_PROGRESS';
    },
    // The add-on's Important category, or null while categories load. The
    // default-view toggle is disabled until it is known, since the toggle
    // stores that category's id.
    importantCategory() {
      return this.categories.find(category => category.nameId === 'emailImportantCategory') || null;
    },
    // The default-view toggle, backed by the same stored setting the select it
    // replaces used: on = the Important category's id is stored as the default
    // category view, off = nothing is stored (no migration needed).
    openOnImportant: {
      get() {
        return !!this.importantCategory && this.defaultCategoryView === this.importantCategory.id;
      },
      set(value) {
        this.defaultCategoryView = value && this.importantCategory ? this.importantCategory.id : null;
      },
    },
  },
  watch: {
    userEmailSetting: {
      immediate: true,
      handler() {
        this.initFromSetting();
      },
    },
  },
  created() {
    this.$emailConnectorCommonService.getAvailableEmailCategories()
      .then(list => this.categories = list || []);
  },
  methods: {
    initFromSetting() {
      const setting = this.userEmailSetting || {};
      this.defaultCategoryView = setting.defaultCategoryView ?? null;
      // notifyAllCategories unset (not a boolean) resolves to "All".
      this.notifyAll = typeof setting.notifyAllCategories !== 'boolean' ? true : setting.notifyAllCategories;
      this.notifyCategoryIds = setting.notifyCategories || [];
      this.carddavAvailable = !!setting.carddavAvailable;
      this.carddavEnabled = !!setting.carddavEnabled;
      if (this.carddavEnabled) {
        this.readAddressBookStatus();
      }
    },
    /**
     * Stores whether the address book should sync. That is the whole setting: the
     * sync signs in with the mailbox's own credentials, so there is nothing else
     * to ask for.
     *
     * @returns {void}
     */
    /**
     * Reads how the last address-book sync went, for the line under the switch.
     * Failing is silent: a status we cannot read is not worth an error banner.
     *
     * @returns {void}
     */
    readAddressBookStatus() {
      this.$emailConnectorCommonService.getAddressBookSyncStatus()
        .then(state => this.addressBookSyncState = state)
        .catch(() => this.addressBookSyncState = null);
    },
    /**
     * Pulls the address book now, rather than waiting for the next scheduled run.
     *
     * @returns {void}
     */
    syncAddressBook() {
      this.syncingAddressBook = true;
      this.$emailConnectorCommonService.syncAddressBook()
        // What the run DID is in the state it leaves behind, not in whether the
        // request came back. Reporting success because the call returned told
        // people their address book had synced when it had in fact failed --
        // which is worse than any error message.
        .then(() => this.$emailConnectorCommonService.getAddressBookSyncStatus())
        .then(state => {
          this.addressBookSyncState = state;
          const failed = state?.status === 'FAILURE' || state?.status === 'BLOCKED';
          const message = failed && this.$t('UserSettings.emailConnector.addressBook.syncError')
            || this.$t('UserSettings.emailConnector.addressBook.synced');
          this.$root.$emit('alert-message', message, failed && 'error' || 'success');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.addressBook.syncError'), 'error'))
        .finally(() => this.syncingAddressBook = false);
    },
    saveAddressBook() {
      this.savingAddressBook = true;
      this.$emailConnectorCommonService.updateAddressBookBinding({carddavEnabled: this.carddavEnabled})
        .then(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success'))
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.error'), 'error'))
        .finally(() => this.savingAddressBook = false);
    },
    save() {
      this.saving = true;
      this.$emailConnectorCommonService.updateEmailPreferences({
        defaultCategoryView: this.defaultCategoryView ?? null,
        notifyAllCategories: this.notifyAll,
        notifyCategories: this.notifyAll ? [] : this.notifyCategoryIds,
      })
        .then(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success'))
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.error'), 'error'))
        .finally(() => this.saving = false);
    },
    confirmReset() {
      this.$refs.resetConfirmDialog.open();
    },
    doReset() {
      this.resetting = true;
      this.$emailConnectorCommonService.resetAndResyncMailbox()
        .then(() => {
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.reset.success'), 'success');
          document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.reset.error'), 'error'))
        .finally(() => this.resetting = false);
    },
  },
};
</script>
