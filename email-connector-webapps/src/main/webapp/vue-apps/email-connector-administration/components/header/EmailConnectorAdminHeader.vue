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
  <div>
    <div class="text-title mb-4">
      {{ $t("emailConnector.admin.title") }}
    </div>
    <v-list-item dense class="px-0">
      <v-list-item-content class="py-0">
        <v-list-item-title>
          {{ $t("emailConnector.admin.enable") }}
        </v-list-item-title>
      </v-list-item-content>
      <v-list-item-action class="my-0">
        <v-switch
          :input-value="emailFeatureActive"
          class="mt-0 pt-0"
          hide-details
          @change="activate" />
      </v-list-item-action>
    </v-list-item>
  </div>
</template>

<script>
export default {
  props: {
    emailFeatureActive: {
      type: Boolean,
      default: false,
    },
  },
  methods: {
    activate(value) {
      this.$emailConnectorAdministrationService.activateEmailFeature(value);
      this.$emit('emailFeature-active', value);
    },
  }
};
</script>
