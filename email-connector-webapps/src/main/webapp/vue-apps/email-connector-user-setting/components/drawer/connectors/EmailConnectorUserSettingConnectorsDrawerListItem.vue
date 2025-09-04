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
  <v-list-item>
    <email-connector-admin-icon
      :image-url="activeEmailConnector.imageUrl"
      :icon="activeEmailConnector.icon" 
      class="me-5" />
    <v-list-item-content>
      <span>{{ activeEmailConnector.name }}</span>
    </v-list-item-content>
    <v-list-item-action class="ml-3">
      <v-btn
        class="btn"
        @click="$root.$emit('open-user-setting-drawer', activeEmailConnector)">
        {{ $t('UserSettings.emailConnector.connectors.drawer.connector.button.connect') }}
      </v-btn>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  props: {
    activeEmailConnector: {
      type: Object,
      default: () => null,
    },
  }
};
</script>