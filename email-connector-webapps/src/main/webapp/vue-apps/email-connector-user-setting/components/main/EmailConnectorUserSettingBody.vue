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
          <v-list-item-action class="pe-6">
            <v-select
              v-model="defaultCategoryView"
              :items="defaultViewItems"
              :menu-props="{ offsetY: true, maxHeight: 260 }"
              item-text="name"
              item-value="id"
              style="width: 200px; padding-top: 0;"
              dense
              outlined
              hide-details
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
    notifyCategoryIds: [],
    saving: false,
    resetting: false,
  }),
  computed: {
    syncInProgress() {
      return this.userEmailSetting?.emailSyncStatus === 'IN_PROGRESS';
    },
    defaultViewItems() {
      return [{ id: null, name: this.$t('UserSettings.emailConnector.defaultView.none') }, ...this.categories];
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
