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
  <v-list-item class="full-height align-center">
    <v-list-item-content>
      <v-list-item-title class="text-wrap text-header-title mb-8">
        {{ title }}
      </v-list-item-title>
      <email-connector-mail-box-drawer-actions
        v-if="hasSelectedEmails"
        select-mode
        :emails="emails"
        :top="false"
        :selected-emails="selectedEmails" />
    </v-list-item-content>
  </v-list-item>
</template>
<script>
export default {
  props: {
    emails: {
      type: Array,
      default: () => [],
    },
    selectedEmails: {
      type: Array,
      default: () => [],
    },
  },
  computed: {
    title() {
      return `${this.selectedEmails.length} ${this.selectedEmails.length === 1 ? 
        this.$t('emailConnector.mailBox.list.drawer.multiSelect.emailSelected') : 
        this.$t('emailConnector.mailBox.list.drawer.multiSelect.emailsSelected')}`;
    },
  },
  methods: {
    hasSelectedEmails() {
      return this.selectedEmails.length > 0;
    },
  }
};
</script>