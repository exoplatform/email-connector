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
    <v-list-item class="mb-2 px-0">
      <div class="py-0 flex-grow-1">
        {{ email.sender }}
      </div>
      <div class="py-0 text-subtitle">
        {{ sentDate }}
      </div>
    </v-list-item>
    <v-list-item class="mb-1 px-0">
      <v-list-item-content class="py-0">
        <span>{{ email.subject }}</span>
      </v-list-item-content>
    </v-list-item>
    <v-list-item class="px-0">
      <v-list-item-content class="py-0 text-subtitle">
        <span>{{ email.excerpt }}</span>
      </v-list-item-content>
    </v-list-item>
  </div>
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
      return this.$emailConnectorMailBoxService.formatDateString(this.email.sentDate);
    },
  },
  methods: {
  }
};
</script>