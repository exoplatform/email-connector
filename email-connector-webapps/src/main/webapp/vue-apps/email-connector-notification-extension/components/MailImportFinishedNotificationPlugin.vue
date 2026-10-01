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
  <!-- EXO-90846 -- a mail import ended: the folder and the added, skipped and refused
       counts, said in the reader's language from the notification's parameters; a click
       opens the mailbox on that folder. -->
  <div
    role="button"
    tabindex="0"
    @click.stop.prevent="openFolder"
    @keydown.enter="openFolder"
    @keydown.space="openFolder">
    <user-notification-template
      :notification="notification"
      :url="link"
      :message="message"
      :loading="loading">
      <template #avatar>
        <div>
          <v-icon size="40" :class="failed ? 'warning--text' : 'primary--text'">fa-file-import</v-icon>
        </div>
      </template>
      <template #actions>
        <div class="text-truncate">
          <v-icon size="14" class="me-1 icon-default-color">fa-file-import</v-icon>
          {{ title }}
        </div>
      </template>
    </user-notification-template>
  </div>
</template>

<script>
/**
 * Escapes a text for the notification's message, which the platform renders as HTML:
 * a folder's name is text the user typed.
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

/**
 * A count as the notification carries it, digits only.
 *
 * @param {String} value the parameter
 * @returns {String} the count, "0" when it is not one
 */
function count(value) {
  return /^\d+$/.test(String(value)) ? String(value) : '0';
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
     * @returns {Boolean} whether the run broke
     */
    failed() {
      return this.parameters.STATUS === 'FAILURE';
    },
    /**
     * @returns {String} the heading, in the reader's language
     */
    title() {
      return this.translated('mailImportFinished.notification.title') || this.parameters.TITLE || '';
    },
    /**
     * The sentence: the folder -- its own name, escaped, for a folder of the user's, the
     * reader's word for a built-in -- and the three counts; the sentence the server wrote
     * when the bundle does not say it.
     *
     * @returns {String} the message
     */
    message() {
      let key = 'mailImportFinished.notification.content';
      if (this.failed) {
        key = 'mailImportFinished.notification.contentFailed';
      } else if (this.parameters.MESSAGE_CODE) {
        key = 'mailImportFinished.notification.contentCutShort';
      }
      if (!this.translated(key)) {
        return this.parameters.CONTENT || '';
      }
      const folder = this.parameters.FOLDER_NAME
        ? escapeHtml(this.parameters.FOLDER_NAME)
        : this.translated(`mailImportFinished.notification.folder.${this.parameters.FOLDER}`) || escapeHtml(this.parameters.FOLDER || '');
      return this.$t(key, {
        0: folder,
        1: count(this.parameters.ADDED),
        2: count(this.parameters.SKIPPED),
        3: count(this.parameters.REFUSED),
      });
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
     * Opens the mailbox on the folder the mail was imported into.
     *
     * @returns {void}
     */
    openFolder() {
      document.dispatchEvent(new CustomEvent('open-email-box-folder', { detail: { folder: this.parameters.FOLDER || 'INBOX' } }));
    },
  },
};
</script>
