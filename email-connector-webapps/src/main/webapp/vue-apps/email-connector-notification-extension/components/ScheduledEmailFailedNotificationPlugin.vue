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
  <!-- EXO-90434 -- a scheduled mail was not sent, or could not be confirmed sent: said
       from the notification's SUBJECT and REASON code, in the reader's language, and a
       click opens the mailbox on its Scheduled view, where the mail waits for the user.
       EXO-90595 -- a mail sent from a shared mailbox whose copy could not be filed in
       its owner's Sent (OWNER_COPY_FAILED) is no longer scheduled: the click opens Sent. -->
  <div
    role="button"
    tabindex="0"
    @click.stop.prevent="openScheduledView"
    @keydown.enter="openScheduledView"
    @keydown.space="openScheduledView">
    <user-notification-template
      :notification="notification"
      :url="link"
      :message="message"
      :loading="loading">
      <template #avatar>
        <div>
          <v-icon size="40" class="warning--text">fa-clock</v-icon>
        </div>
      </template>
      <template #actions>
        <div class="text-truncate">
          <v-icon size="14" class="me-1 icon-default-color">fa-clock</v-icon>
          {{ title }}
        </div>
      </template>
    </user-notification-template>
  </div>
</template>

<script>
/**
 * Escapes a text for the notification's message, which the platform renders as HTML:
 * the subject is text the user typed.
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
      return this.translated('scheduledEmailFailed.notification.title') || this.parameters.TITLE || '';
    },
    /**
     * What happened to which mail: "Your scheduled email "{subject}": {reason}", built
     * in the reader's language from the subject and the reason code; the sentence the
     * server wrote when the notification was made, when the bundle does not say it.
     *
     * @returns {String} the message, its subject escaped
     */
    message() {
      const reason = this.translated(`scheduledEmailFailed.notification.reason.${this.parameters.REASON}`);
      const content = reason && this.translated('scheduledEmailFailed.notification.content');
      if (!content) {
        return this.parameters.CONTENT || '';
      }
      const subject = this.parameters.SUBJECT
        ? escapeHtml(this.parameters.SUBJECT)
        : this.translated('scheduledEmailFailed.notification.noSubject') || '';
      return this.$t('scheduledEmailFailed.notification.content', { 0: subject, 1: reason });
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
     * Opens the mailbox on its Scheduled view.
     *
     * @returns {void}
     */
    openScheduledView() {
      const folder = this.parameters.REASON === 'OWNER_COPY_FAILED' ? 'SENT' : 'SCHEDULED';
      document.dispatchEvent(new CustomEvent('open-email-box-folder', { detail: { folder } }));
    },
  },
};
</script>
