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
  <!-- EXO-90656 -- where the user's mail is forwarded changed, or a forward eXo did not
       set was found on the mail server: said from the notification's CONTENT, the
       sentence the server built in the receiver's language (the destination and the
       rule's or script's name escaped); a click opens the forwarding drawer, where the
       forward is shown and managed -- the one the mailbox band's Manage opens. -->
  <div
    role="button"
    tabindex="0"
    @click.stop.prevent="openSettings"
    @keydown.enter="openSettings"
    @keydown.space="openSettings">
    <user-notification-template
      :notification="notification"
      :url="settingsUrl"
      :message="message"
      :loading="loading">
      <template #avatar>
        <div>
          <v-icon size="36" class="primary--text">fa-share-square</v-icon>
        </div>
      </template>
      <template #actions>
        <div class="text-truncate">
          <v-icon size="14" class="me-1 icon-default-color">fa-share-square</v-icon>
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
     * @returns {String} the heading, in the reader's language
     */
    title() {
      return this.translated('emailForwarding.notification.title') || this.parameters.TITLE || '';
    },
    /**
     * The sentence the server built; it escaped the destination and the name.
     *
     * @returns {String} the message
     */
    message() {
      return this.parameters.CONTENT || '';
    },
    /**
     * @returns {String} the user's settings page, where the forward is managed
     */
    settingsUrl() {
      return `${eXo.env.portal.context}/${eXo.env.portal.metaPortalName || eXo.env.portal.portalName}/settings`;
    },
  },
  methods: {
    /**
     * A key's words in the reader's language, or nothing when the bundle lacks it.
     *
     * @param {String} key the key
     * @returns {String} the words, or null
     */
    translated(key) {
      return typeof this.$te === 'function' && this.$te(key) ? this.$t(key) : null;
    },
    /**
     * Opens the forwarding drawer, where the forward is shown and managed.
     *
     * @returns {void}
     */
    openSettings() {
      window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-email-forwarding')));
    },
  },
};
</script>
