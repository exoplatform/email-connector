<!--
Copyright (C) 2026 eXo Platform SAS.

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
  <!-- EXO-90553 - new mail in a mailbox shared with the reader: the sentence names the
       owner first, and a click opens that mailbox, not the reader's own. -->
  <div
    role="button"
    tabindex="0"
    @click.stop.prevent="openSharedMailbox"
    @keydown.enter="openSharedMailbox"
    @keydown.space="openSharedMailbox">
    <user-notification-template
      :notification="notification"
      :url="link"
      :message="message"
      :loading="loading">
      <template #avatar>
        <div>
          <v-icon size="40" class="icon-default-color">fa-envelope</v-icon>
        </div>
      </template>
      <template #actions>
        <div class="text-truncate">
          <v-icon size="14" class="me-1 icon-default-color">fa-share-alt</v-icon>
          {{ title }}
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
    loading: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    /**
     * @returns {Object} the notification's parameters
     */
    parameters() {
      return this.notification?.parameters || {};
    },
    /**
     * @returns {String} the heading the server wrote in the reader's language
     */
    title() {
      return this.parameters.TITLE || '';
    },
    /**
     * @returns {String} whose mailbox, and how many new messages
     */
    message() {
      return this.parameters.CONTENT || '';
    },
    /**
     * @returns {String} the shared mailbox's link the server gave
     */
    link() {
      return this.parameters.LINK;
    },
  },
  methods: {
    /**
     * Opens the mail drawer on the shared mailbox the notification is about; a share
     * gone since is answered by the drawer, which says so and opens the reader's own.
     *
     * @returns {void}
     */
    openSharedMailbox() {
      const mailbox = this.parameters.DELEGATION_ID;
      if (mailbox) {
        document.dispatchEvent(new CustomEvent('open-email-box-mailbox', { detail: { mailbox } }));
      } else {
        this.$emailConnectorCommonService.openEmailBox();
      }
    },
  },
};
</script>
