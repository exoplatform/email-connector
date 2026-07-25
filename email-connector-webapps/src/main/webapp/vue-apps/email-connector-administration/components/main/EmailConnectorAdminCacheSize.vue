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
      <div class="d-flex align-center">
        <v-text-field
          v-model.number="cacheSize"
          type="number"
          min="1"
          max="2000"
          :rules="rules"
          dense
          outlined
          hide-details
          class="flex-grow-0"
          style="max-width: 100px" />
        <v-btn
          class="btn btn-primary ms-3"
          :disabled="!valid || saving || cacheSize === savedValue"
          :loading="saving"
          @click="save">
          {{ $t('emailConnector.admin.cacheSize.save') }}
        </v-btn>
      </div>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  data() {
    return {
      cacheSize: null,
      savedValue: null,
      saving: false,
    };
  },
  computed: {
    rules() {
      return [
        v => (Number.isInteger(Number(v)) && Number(v) >= 1 && Number(v) <= 2000)
          || this.$t('emailConnector.admin.cacheSize.outOfRange'),
      ];
    },
    valid() {
      return Number.isInteger(this.cacheSize) && this.cacheSize >= 1 && this.cacheSize <= 2000;
    },
  },
  created() {
    this.getCacheSize();
  },
  methods: {
    getCacheSize() {
      this.$emailConnectorAdministrationService.getEmailBoxCacheSize()
        .then(size => {
          this.cacheSize = size;
          this.savedValue = size;
        });
    },
    save() {
      if (!this.valid) {
        return;
      }
      this.saving = true;
      this.$emailConnectorAdministrationService.updateEmailBoxCacheSize(this.cacheSize)
        .then(() => {
          this.savedValue = this.cacheSize;
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.cacheSize.success'), 'success');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.cacheSize.error'), 'error'))
        .finally(() => this.saving = false);
    },
  },
};
</script>
