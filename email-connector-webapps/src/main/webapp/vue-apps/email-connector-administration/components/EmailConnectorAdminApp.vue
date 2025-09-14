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
      :email-feature-active="emailFeatureActive"
      :has-connectors="hasConnectors" 
      @emailFeature-active="emailFeatureActive = $event" />
    <email-connector-admin-list
      :connectors="connectors"
      class="mt-7"
      v-if="emailFeatureActive" />
    <email-connector-admin-footer v-if="emailFeatureActive && !hasConnectors" />
    <email-connector-admin-drawer />
  </v-app>
</template>

<script>
export default {
  data: () => ({
    featureName: 'email',
    emailFeatureActive: null,
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
      .then(enabled => this.emailFeatureActive = enabled);
    this.getEmailConnectors();
    this.$root.$on('refresh-connectors-list', () => {
      this.getEmailConnectors();
    });
  },
  methods: {
    getEmailConnectors() {
      this.$emailConnectorAdministrationService.getEmailConnectors()
        .then(connectors => this.connectors = connectors);
    },
  }
};
</script>
