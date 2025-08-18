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
    <h4 class="text-title mt-0">
      {{ $t("emailConnector.admin.title") }}
    </h4>
    <v-list-item class="px-0">
      <v-list-item-action class="me-3">
        <v-switch v-model="emailConnectorEnabled" @change="enable(emailConnectorEnabled)" />
      </v-list-item-action>
      <v-list-item-content class="py-0">
        <v-list-item-title class="subtitle-1 pt-2">
          {{ $t("emailConnector.admin.activate.label") }}
        </v-list-item-title>
      </v-list-item-content>
    </v-list-item>
    <v-data-table v-if="emailConnectorEnabled" :headers="headers" />
  </v-app>
</template>

<script>
export default {
  data: () => ({
    headers: [],
    featureName: 'emailConnector',
    emailConnectorEnabled: null,
  }),
  created() {
    this.headers = [
      { text: this.$t('emailConnector.admin.connectors.list.name'), align: 'center' },
      { text: this.$t('emailConnector.admin.connectors.list.activate'), align: 'center' },
      { text: this.$t('emailConnector.admin.connectors.list.actions'), align: 'center' }
    ];
    this.$featureService.isFeatureEnabled(this.featureName)
      .then(enabled => this.emailConnectorEnabled = enabled);
  },
  methods: {
    enable(emailConnectorEnabled) {
      this.$emailConnectorAdministrationService.enable(emailConnectorEnabled);
    },
  }
};
</script>
