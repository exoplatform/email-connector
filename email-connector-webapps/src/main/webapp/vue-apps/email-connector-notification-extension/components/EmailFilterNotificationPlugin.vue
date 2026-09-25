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
  <!-- EXO-90654 -- one of the user's own mail filters handled new mail: said from the
       notification's CONTENT, the sentence the server built in the receiver's language
       (the filter's name escaped, and the assistant's line when it wrote one), rebuilt
       here from FILTER_NAME and FILTER_COUNT when it is missing; a click opens the
       mailbox on its inbox. -->
  <div
    role="button"
    tabindex="0"
    @click.stop.prevent="openMailbox"
    @keydown.enter="openMailbox"
    @keydown.space="openMailbox">
    <user-notification-template
      :notification="notification"
      :url="link"
      :message="message"
      :loading="loading">
      <template #avatar>
        <div>
          <v-icon size="40" class="primary--text">fa-filter</v-icon>
        </div>
      </template>
      <template #actions>
        <div class="text-truncate">
          <v-icon size="14" class="me-1 icon-default-color">fa-filter</v-icon>
          {{ title }}
        </div>
      </template>
    </user-notification-template>
  </div>
</template>

<script>
/**
 * Escapes a text for the notification's message, which the platform renders as HTML:
 * a filter's name is text the user typed.
 *
 * @param {String} text the text
 * @returns {String} the escaped text
 */
function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

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
      return this.translated('emailFilter.notification.title') || this.parameters.TITLE || '';
    },
    /**
     * The sentence the server built, or one built here from the filter's name and count.
     *
     * @returns {String} the message, the name escaped
     */
    message() {
      if (this.parameters.CONTENT) {
        return this.parameters.CONTENT;
      }
      const count = Number(this.parameters.FILTER_COUNT) || 1;
      const key = count === 1 ? 'emailFilter.notification.content.one' : 'emailFilter.notification.content.many';
      return this.translated(key) ? this.$t(key, { 0: escapeHtml(this.parameters.FILTER_NAME || ''), 1: count }) : '';
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
     * A key's words in the reader's language, or nothing when the bundle lacks it.
     *
     * @param {String} key the key
     * @returns {String} the words, or null
     */
    translated(key) {
      return typeof this.$te === 'function' && this.$te(key) ? this.$t(key) : null;
    },
    /**
     * Opens the mailbox on its inbox.
     *
     * @returns {void}
     */
    openMailbox() {
      document.dispatchEvent(new CustomEvent('open-email-box-folder', { detail: { folder: 'INBOX' } }));
    },
  },
};
</script>
