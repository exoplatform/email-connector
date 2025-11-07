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
  <div
    role="button"
    tabindex="0"
    @click.stop.prevent="openEmailBox"
    @keydown.enter="openEmailBox"
    @keydown.space="openEmailBox">
    <user-notification-template
      :notification="notification"
      :url="currentUrl" 
      :message="message"
      :loading="loading">
      <template #avatar>
        <div>
          <v-icon size="40">fa-envelope</v-icon>
        </div>
      </template>
      <template #actions>
        <div class="text-truncate">
          <v-icon size="14" class="me-1">fa-inbox</v-icon>
          {{ eventTitle }}
        </div>
      </template>
    </user-notification-template>
  </div>
</template>
<script>
export default {
  props: {
    notification: {
      type: Object,
      default: null,
    },
  },
  computed: {
    eventTitle() {
      return this.newEmails > 1 && this.$t('newEmails.notification.title', {
        0: this.newEmails,
      }) || this.$t('newEmail.notification.title');
    },
    newEmails() {
      return this.notification?.parameters?.NEW_EMAILS;
    },
    message() {
      return this.$t('newEmails.notification.description');
    },
    currentUrl() {
      return `${window.location.href}?openEmailBox=true`;
    },
  },
  methods: {
    openEmailBox() {
      this.$emailConnectorCommonService.openEmailBox();
    }
  }
};
</script>