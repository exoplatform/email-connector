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
  <v-list-item dense class="d-flex px-0">
    <v-list-item-content class="py-0">
      <v-list-item-title class="text-title">
        {{ $t("emailConnector.admin.title") }}
      </v-list-item-title>
    </v-list-item-content>
    <v-list-item-action class="my-0">
      <v-btn
        height="24"
        min-width="24"
        v-if="hasConnectors"
        :aria-label="$t('emailConnector.admin.connectors.add')"
        class="btn btn-primary px-0"
        @click="$root.$emit('open-email-connector-drawer')">
        <v-icon
          size="16">
          fa-plus
        </v-icon>
      </v-btn>
    </v-list-item-action>
    <v-list-item-action class="ms-5 my-0">
      <v-switch :input-value="emailConnectorActive" @change="activate" />
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  props: {
    emailConnectorActive: {
      type: Boolean,
      default: false,
    },
    hasConnectors: {
      type: Boolean,
      default: false,
    },
  },
  methods: {
    activate(value) {
      this.$emailConnectorAdministrationService.activate(value);
      this.$emit('emailConnector-active', value);
    },
  }
};
</script>
