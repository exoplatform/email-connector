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
  <div class="d-flex justify-center mt-10">    
    <v-btn
      v-if="!hasConnectors"
      :aria-label="$t('emailConnector.admin.connectors.addConnector')"
      class="btn btn-primary text-truncate mb-3"
      @click="$root.$emit('open-add-email-connector-drawer')">
      <v-icon
        size="16">
        fa-plus
      </v-icon>
      <span
        class="text-truncate text-none ms-2">
        {{ $t('emailConnector.admin.connectors.addConnector') }}
      </span>
    </v-btn>
  </div>
</template>

<script>
export default {
  props: {
    hasConnectors: {
      type: Boolean,
      default: false,
    },
  }
};
</script>
