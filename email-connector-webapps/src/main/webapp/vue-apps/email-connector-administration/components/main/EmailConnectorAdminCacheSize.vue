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
  <v-list-item dense class="px-0">
    <v-list-item-content class="py-0">
      <v-list-item-title>
        {{ $t('emailConnector.admin.cacheSize.title') }}
      </v-list-item-title>
      <v-list-item-subtitle class="text-wrap text-light-color">
        {{ $t('emailConnector.admin.cacheSize.subtitle') }}
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action class="my-0">
      <v-select
        :value="cacheSize"
        :items="options"
        :loading="saving"
        :disabled="saving"
        dense
        outlined
        hide-details
        class="flex-grow-0"
        style="max-width: 120px"
        @change="onChange" />
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  data() {
    return {
      cacheSize: null,
      options: [100, 250, 500, 750, 1000, 5000],
      saving: false,
    };
  },
  created() {
    this.getCacheSize();
  },
  methods: {
    getCacheSize() {
      this.$emailConnectorAdministrationService.getEmailBoxCacheSize()
        .then(size => {
          this.cacheSize = size;
          // Keep the currently configured value (e.g. a non-standard default set via
          // the system property) selectable even when it is not one of the presets.
          if (!this.options.includes(size)) {
            this.options = [...this.options, size].sort((first, second) => first - second);
          }
        });
    },
    onChange(value) {
      this.saving = true;
      this.$emailConnectorAdministrationService.updateEmailBoxCacheSize(value)
        .then(() => {
          this.cacheSize = value;
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.cacheSize.success'), 'info');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.cacheSize.error'), 'error'))
        .finally(() => this.saving = false);
    },
  },
};
</script>
