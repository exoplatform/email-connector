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
  <exo-drawer
    id="userSettingConnectorsDrawer"
    ref="userSettingConnectorsDrawer"
    v-model="userSettingConnectorsDrawer"
    :loading="loading"
    :right="!$vuetify.rtl"
    :allow-expand="!$root.isMobile"
    @closed="close">
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.connectors.drawer.title') }}</span>
    </template>
    <template v-if="userSettingConnectorsDrawer" #content>
      <email-connector-user-setting-connectors-drawer-list
        v-if="hasActiveConnectors"
        class="ma-5 py-0"
        :user-email-connectors="userEmailConnectors" />
      <v-list-item v-else class="full-height align-center">
        <v-list-item-content>
          <v-list-item-title class="text-wrap">
            {{ $t('UserSettings.emailConnector.connectors.drawer.noActiveConnectors') }}
          </v-list-item-title>
        </v-list-item-content>
      </v-list-item>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    featureName: 'email',
    userSettingConnectorsDrawer: false,
    userEmailConnectors: [],
  }),
  computed: {
    hasActiveConnectors() {
      return this.userEmailConnectors?.length > 0;
    },
  },
  created() {
    this.hideUserSetting();
    this.$root.$on('open-user-setting-connectors-drawer', this.open);
    this.$root.$on('close-user-setting-connectors-drawer', this.close);
    document.addEventListener('refresh-active-connectors-list', this.getUserEmailConnectors);
  },
  methods: {
    open() {
      this.$refs.userSettingConnectorsDrawer.open();
    },
    close() {
      this.$refs.userSettingConnectorsDrawer.close();
    },
    getUserEmailConnectors() {
      this.$emailConnectorUserSettingService.getUserEmailConnectors()
        .then(connectors => this.userEmailConnectors = connectors);
    },
    async hideUserSetting() {
      const enabled = await this.$featureService.isFeatureEnabled(this.featureName);
      const appEl = document.getElementById('emailConnectorUserSetting');
      const portletContainer = appEl?.closest('.layout-application');
      this.userEmailConnectors = await this.$emailConnectorUserSettingService.getUserEmailConnectors();
      if (portletContainer && (!enabled || this.userEmailConnectors?.length === 0)) {
        portletContainer.style.display = 'none';
      }
    }
  }
};
</script>