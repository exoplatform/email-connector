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
      @emailFeature-active="emailFeatureActive = $event" />
    <email-connector-admin-sync-settings v-if="emailFeatureActive" />
    <template v-if="emailFeatureActive">
      <div class="text-title mt-8 mb-3">
        {{ $t('emailConnector.admin.connectors.title') }}
      </div>
      <div class="mb-4">
        <v-btn
          :aria-label="$t('emailConnector.admin.connectors.add')"
          class="btn btn-primary"
          @click="$root.$emit('open-email-connector-drawer')">
          <v-icon size="18">fa-plus</v-icon>
          <span class="text-none ms-2">{{ $t('emailConnector.admin.connectors.add') }}</span>
        </v-btn>
      </div>
      <email-connector-admin-list :connectors="connectors" />
    </template>
    <email-connector-admin-drawer />
    <email-connector-admin-sync-settings-drawer />
  </v-app>
</template>

<script>
export default {
  data: () => ({
    featureName: 'email',
    emailFeatureActive: null,
    connectors: []
  }),
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
