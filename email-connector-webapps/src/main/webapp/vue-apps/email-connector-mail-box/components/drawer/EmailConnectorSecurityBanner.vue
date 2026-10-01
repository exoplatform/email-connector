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
  <!-- The two things the reader says about a received message before its body
       (EXO-90841): why it looks suspicious, each reason in words, and that the images it
       would fetch from the internet are held back, with the two ways to load them. The
       reasons quote what the message presents (a name, a domain) as text, never as
       markup. "Always show" is not offered on a message that looks suspicious: trusting
       a sender is a decision to take on a message that gives no reason for doubt. -->
  <div v-if="hasWarnings || remoteContentBlocked" class="mail-security-banners text-start">
    <!-- Both banners are the platform's alert with a coloured left border: the icon on
         the left, the text left-aligned in the normal text colour, readable on the
         page's background, and the actions under the text. -->
    <v-alert
      v-if="hasWarnings"
      class="mail-security-warning text-start mb-3"
      type="warning"
      role="alert"
      border="left"
      colored-border
      elevation="0"
      dense>
      <div class="font-weight-bold text-color">{{ $t('emailConnector.mailBox.security.warning.title') }}</div>
      <div
        v-for="(warning, index) in warnings"
        :key="index"
        :class="`text-color text-wrap mt-1 mail-security-warning-${warning.type}`">
        {{ warningLabel(warning) }}
      </div>
    </v-alert>
    <v-alert
      v-if="remoteContentBlocked"
      class="mail-remote-content-banner text-start mb-3"
      type="info"
      role="status"
      border="left"
      colored-border
      elevation="0"
      dense>
      <div class="text-color text-wrap">{{ $t('emailConnector.mailBox.remoteContent.blocked') }}</div>
      <div class="d-flex flex-wrap justify-start mt-1">
        <v-btn
          :disabled="loading || busy"
          class="mail-remote-content-show px-0 me-4"
          color="primary"
          text
          small
          @click="$emit('show-remote-content')">
          {{ $t('emailConnector.mailBox.remoteContent.show') }}
        </v-btn>
        <v-btn
          v-if="senderAddress && !hasWarnings"
          :disabled="loading || busy"
          class="mail-remote-content-trust px-0"
          color="primary"
          text
          small
          @click="trustSender">
          {{ $t('emailConnector.mailBox.remoteContent.alwaysShow') }}
        </v-btn>
      </div>
    </v-alert>
  </div>
</template>

<script>
export default {
  props: {
    // The content on screen: the body the reader shows, whether its remote content was
    // held back (remoteContentBlocked) and why it looks suspicious (securityWarnings).
    content: {
      type: Object,
      default: null,
    },
    // The sender's address, which "Always show" trusts.
    senderAddress: {
      type: String,
      default: null,
    },
    // Whether the content is being read again with its remote content.
    loading: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    busy: false,
  }),
  computed: {
    /**
     * Why the message looks suspicious, as the server judged it.
     *
     * @returns {Array<Object>} the warnings, {type, shown, actual}
     */
    warnings() {
      return this.content?.securityWarnings || [];
    },
    /**
     * Whether there is any reason to doubt the message.
     *
     * @returns {Boolean} true when at least one warning applies
     */
    hasWarnings() {
      return this.warnings.length > 0;
    },
    /**
     * Whether the body on screen had remote content held back.
     *
     * @returns {Boolean} true when the images wait for consent
     */
    remoteContentBlocked() {
      return !!this.content?.remoteContentBlocked;
    },
  },
  methods: {
    /**
     * One warning in words, naming what the message presents and what it really is.
     *
     * @param {Object} warning {type, shown, actual}
     * @returns {String} the localized sentence
     */
    warningLabel(warning) {
      return this.$t(`emailConnector.mailBox.security.warning.${warning.type}`, {
        0: warning.shown || '',
        1: warning.actual || '',
      });
    },
    /**
     * Trusts the sender, then loads this message's remote content. A refused or failed
     * trust says so and leaves the message as it is.
     *
     * @returns {Promise<void>} resolved once trusted or refused
     */
    trustSender() {
      this.busy = true;
      return this.$emailConnectorCommonService.setSenderTrusted(this.senderAddress, true)
        .then(() => this.$emit('show-remote-content'))
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.remoteContent.trustError'), 'error'))
        .finally(() => this.busy = false);
    },
  },
};
</script>
