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
  <!-- EXO-90503 - somebody gave you access to their mailbox. The sentence was written
       server-side in the reader's own language, with the owner's name already in it,
       so it is shown as it is rather than rebuilt from a second lookup here. A click
       opens the mailbox; the invitation is answered in the email settings, where
       accepting asks the mail server whether the access is still there - which can
       fail, and a notification row is the wrong place to explain that it did. -->
  <div
    role="button"
    tabindex="0"
    @click.stop.prevent="openEmailBox"
    @keydown.enter="openEmailBox"
    @keydown.space="openEmailBox">
    <user-notification-template
      :notification="notification"
      :url="link"
      :message="message"
      :loading="loading">
      <template #avatar>
        <div>
          <v-icon size="40" class="primary--text">fa-share-alt</v-icon>
        </div>
      </template>
      <template #actions>
        <div class="text-truncate">
          <v-icon size="14" class="me-1 icon-default-color">fa-envelope-open-text</v-icon>
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
     * @returns {String} who shared and with which rights
     */
    message() {
      return this.parameters.CONTENT || '';
    },
    /**
     * @returns {String} the mailbox link the server gave
     */
    link() {
      return this.parameters.LINK;
    },
  },
  methods: {
    /**
     * Opens the mailbox, from which the email settings hold the answer.
     *
     * @returns {void}
     */
    openEmailBox() {
      this.$emailConnectorCommonService.openEmailBox();
    },
  },
};
</script>
