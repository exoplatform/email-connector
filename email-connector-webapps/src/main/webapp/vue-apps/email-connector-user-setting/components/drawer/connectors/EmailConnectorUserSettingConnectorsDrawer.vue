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
    right
    allow-expand>
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.connectors.drawer.title') }}</span>
    </template>
    <template v-if="userSettingConnectorsDrawer" #content>
      <email-connector-user-setting-connectors-drawer-list
        v-if="hasActiveConnectors"
        class="ma-5 py-0"
        :user-email-connectors="userEmailConnectors"
        :connection-requirements="connectionRequirements" />
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
    connectionRequirements: {},
  }),
  computed: {
    hasActiveConnectors() {
      return this.userEmailConnectors?.length > 0;
    },
  },
  mounted() {
    document.addEventListener('refresh-active-connectors-list', this.getUserEmailConnectors);
  },
  beforeDestroy() {
    document.removeEventListener('refresh-active-connectors-list', this.getUserEmailConnectors);
  },
  created() {
    this.hideUserSetting();
    this.$root.$on('open-user-setting-connectors-drawer', this.open);
    this.$root.$on('close-user-setting-connectors-drawer', this.close);
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
      this.getConnectionRequirements();
    },
    /**
     * Which connectors ask their user for anything. Read by BOTH paths that fill
     * the list - this one and hideUserSetting(), which runs on created() and is
     * therefore the one serving the first render. Instrumenting a single path left
     * the map empty on opening, and every connector then showed its form.
     * <p>
     * Fails to an empty map on purpose: a requirement nobody could read must leave
     * every button opening its form, never connect silently.
     *
     * @returns {Promise} resolves once the requirements are known, or given up on
     */
    getConnectionRequirements() {
      return this.$emailConnectorUserSettingService.getConnectionRequirements()
        .then(requirements => this.connectionRequirements = requirements || {})
        .catch(() => this.connectionRequirements = {});
    },
    async hideUserSetting() {
      const enabled = await this.$featureService.isFeatureEnabled(this.featureName);
      const appEl = document.getElementById('emailConnectorUserSetting');
      const portletContainer = appEl?.closest('.layout-application');
      this.userEmailConnectors = await this.$emailConnectorUserSettingService.getUserEmailConnectors();
      await this.getConnectionRequirements();
      if (portletContainer && (!enabled || this.userEmailConnectors?.length === 0)) {
        portletContainer.style.display = 'none';
      }
    }
  }
};
</script>