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
  <v-hover v-slot="{ hover }">
    <div :class="{'light-grey-background-color': hover}">
      <div class="d-flex mb-2">
        <div class="flex-grow-1">
          {{ email.sender }}
        </div>
        <div class="text-subtitle">
          {{ sentDate }}
        </div>
      </div>
      <div class="mb-1 text-subtitle-font-size">
        {{ email.subject }}
      </div>
      <div class="text-subtitle">
        {{ email.excerpt }}
      </div>
    </div>
  </v-hover>
</template>

<script>
export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
  },
  computed: {
    sentDate() {
      const sentDate = this.$emailConnectorMailBoxService.formatDateString(this.email.sentDate);
      return sentDate === 'yesterday' && this.$t('emailConnector.mailBox.list.drawer.yesterday') || sentDate;
    },
  },
  methods: {
  }
};
</script>