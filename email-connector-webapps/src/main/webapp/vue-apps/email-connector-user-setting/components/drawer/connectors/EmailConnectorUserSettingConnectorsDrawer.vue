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
        :active-email-connectors="activeEmailConnectors" />
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
    activeEmailConnectors: [],
  }),
  computed: {
    hasActiveConnectors() {
      return this.activeEmailConnectors?.length > 0;
    },
  },
  created() {
    this.hideUserSetting();
    this.$root.$on('open-user-setting-connectors-drawer', this.open);
    this.$root.$on('close-user-setting-connectors-drawer', this.close);
    document.addEventListener('refresh-active-connectors-list', this.getActiveEmailConnectors);
  },
  methods: {
    open() {
      this.$refs.userSettingConnectorsDrawer.open();
    },
    close() {
      this.$refs.userSettingConnectorsDrawer.close();
    },
    getActiveEmailConnectors() {
      this.$emailConnectorUserSettingService.getActiveEmailConnectors()
        .then(connectors => this.activeEmailConnectors = connectors);
    },
    async hideUserSetting() {
      const enabled = await this.$featureService.isFeatureEnabled(this.featureName);
      const appEl = document.getElementById('emailConnectorUserSetting');
      const portletContainer = appEl?.closest('.layout-application');
      this.activeEmailConnectors = await this.$emailConnectorUserSettingService.getActiveEmailConnectors();
      if (portletContainer && (!enabled || this.activeEmailConnectors?.length === 0)) {
        portletContainer.style.display = 'none';
      }
    }
  }
};
</script>