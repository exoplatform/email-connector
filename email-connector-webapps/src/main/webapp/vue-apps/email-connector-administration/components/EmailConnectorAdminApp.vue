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
  <v-app
    role="main"
    id="emailConnectorAdministration"
    class="pa-5">
    <email-connector-admin-header
      :email-connector-active="emailConnectorActive"
      has-connectors="hasConnectors" 
      @emailConnector-active="emailConnectorActive = $event" />
    <email-connector-admin-list
      :connectors="connectors"
      class="mt-7"
      v-if="emailConnectorActive" />
    <email-connector-admin-footer has-connectors="hasConnectors" />
    <email-connector-admin-drawer @emailConnector-created="getEmailConnectors" />
  </v-app>
</template>

<script>
export default {
  data: () => ({
    featureName: 'emailConnector',
    emailConnectorActive: null,
    connectors: []
  }),
  computed: {
    hasConnectors() {
      return this.connectors?.length > 0;
    },
    isDefault() {
      return !this.emailConnector?.iconSrc && !this.emailConnector?.iconUrl;
    },
  },
  created() {
    this.$featureService.isFeatureEnabled(this.featureName)
      .then(enabled => this.emailConnectorActive = enabled);
    this.getEmailConnectors();
  },
  methods: {
    getEmailConnectors() {
      this.$emailConnectorAdministrationService.getEmailConnectors()
        .then(connectors => this.connectors = connectors);
    },
  }
};
</script>
