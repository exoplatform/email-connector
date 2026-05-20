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
    <email-connector-mail-box-drawer-list-item
      v-for="email in emails"
      :key="email.mailRemoteId"
      :email="email"
      :opened-email-id="openedEmailId"
      :emails="emails"
      :webmail-url="webmailUrl"
      :sync-in-progress="syncInProgress" 
      :selected-emails="selectedEmails"
      :select-mode="selectMode" 
      :expanded="expanded" />
  </div>
</template>

<script>
export default {
  data() {
    return {
      openedEmailId: this.currentEmail?.mailRemoteId,
    };
  },
  props: {
    emails: {
      type: Array,
      default: () => [],
    },
    selectMode: {
      type: Boolean,
      default: false,
    },
    selectedEmails: {
      type: Array,
      default: () => [],
    },
    expanded: {
      type: Boolean,
      default: false,
    },
    syncInProgress: {
      type: Boolean,
      default: false,
    },
    webmailUrl: {
      type: String,
      default: null,
    },
    currentEmail: {
      type: Object,
      default: () => null,
    }
  },
  created() {
    this.$root.$on('set-opened', (mailRemoteId) => {
      this.openedEmailId = mailRemoteId;
    });
  },
};
</script>