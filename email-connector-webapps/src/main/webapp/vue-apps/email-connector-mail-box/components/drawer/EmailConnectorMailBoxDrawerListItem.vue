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
    <v-list :class="{ 'light-grey-background-color': hover }">
      <v-list-item
        style="min-height: 0"
        class="px-0 pb-2"
        @click="openDetail"> 
        <v-list-item-content class="py-0">
          <v-list-item-title v-text="email.sender.name" />
        </v-list-item-content>
        <v-list-item-action class="my-0">
          <v-list-item-subtitle v-text="sentDate" />
        </v-list-item-action>
      </v-list-item>
      <v-list-item
        style="min-height: 0"
        class="px-0"
        @click="openDetail">
        <v-list-item-content class="py-0">
          <v-list-item-subtitle class="mb-1 text-color" v-text="email.subject" />
          <v-list-item-subtitle v-text="email.content" />
        </v-list-item-content>
      </v-list-item>
    </v-list>
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
      return this.$emailConnectorMailBoxService.formatDateString(this.email.sentDate, this.$t('emailConnector.mailBox.list.drawer.yesterday'));
    }
  },
  methods: {
    openDetail() {
      this.$root.$emit('open-email-detail-drawer', this.email.mailRemoteId);
    }
  }
};
</script>