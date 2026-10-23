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
        <v-list-item class="height-auto">
          <v-list-item-content>
            <v-list-item-title class="text-color">
              {{ $t('UserSettings.emailConnector.notifications.title') }}
            </v-list-item-title>
            <v-list-item-subtitle>
              {{ $t('UserSettings.emailConnector.notifications.all') }}
            </v-list-item-subtitle>
            <!-- The badge and the notifications are one rule: the E-mail application's
                 unread count is exactly the mail these settings would have notified
                 about, inbox only. Said on this row rather than under the category
                 chips because it qualifies the whole setting, and it is true in both
                 states -- with the switch on the badge ignores Sent and Archive, with
                 it off it ignores every category but the chosen ones. Without it a
                 user sees unread rows in the list and a badge of zero, and reasonably
                 reads that as broken. -->
            <v-list-item-subtitle class="caption text-sub-title text-wrap">
              {{ $t('UserSettings.emailConnector.notifications.badgeHint') }}
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
        <v-divider class="mx-4" />
        <email-connector-user-setting-signature />
        <!-- The rarely used settings, collapsed, with the automatic reply and mailbox
             sharing among them; Reset & re-sync last. -->
        <email-connector-user-setting-advanced :user-email-setting="userEmailSetting" />
      </template>
    </v-list>
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
    notifyCategoryIds: [],
    saving: false,
  }),
  computed: {
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
    /**
     * Shows the preferences the setting carries.
     *
     * @returns {void}
     */
    initFromSetting() {
      const setting = this.userEmailSetting || {};
      this.defaultCategoryView = setting.defaultCategoryView ?? null;
      // notifyAllCategories unset (not a boolean) resolves to "All".
      this.notifyAll = typeof setting.notifyAllCategories !== 'boolean' ? true : setting.notifyAllCategories;
      this.notifyCategoryIds = setting.notifyCategories || [];
    },
    /**
     * Stores the default view and the notification preferences, which are one document.
     *
     * @returns {void}
     */
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
  },
};
</script>
