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
    <v-list>
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
  computed: {
    syncInProgress() {
      return this.userEmailSetting?.emailSyncStatus === 'IN_PROGRESS';
    },
  }
};
</script>
