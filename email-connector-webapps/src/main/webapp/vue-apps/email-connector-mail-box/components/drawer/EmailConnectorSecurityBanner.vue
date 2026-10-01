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
  <!-- The two notices the reader gives about a received message (EXO-90841), placed
       between its header and its body: why it looks suspicious, each reason in words,
       and that the images it would fetch from the internet are held back, with the two
       ways to load them. Dressed like the assistant card above the mail: a thin frame in
       the platform's border colour with a thicker accent on the reading-start side (the
       warning colour for the reasons, the primary colour for the images), in secondary
       text size so it reads as a notice. Wide (the full-screen reader), each notice is
       one line with its links at the end; narrow (the drawer), the links wrap under the
       text. The reasons quote what the message presents as text, never as markup.
       "Always show" is not offered on a message that looks suspicious. Inline style for
       the accent because this webapp's webpack has no CSS loader. -->
  <div v-if="hasWarnings || remoteContentBlocked" class="mail-security-banners text-start">
    <div
      v-if="hasWarnings"
      :style="frameStyle(WARNING_COLOR)"
      class="mail-security-warning rounded d-flex align-start px-3 py-2 mb-3"
      role="alert">
      <v-icon
        size="14"
        class="warning--text me-2 mt-1 flex-shrink-0">
        fas fa-exclamation-triangle
      </v-icon>
      <div class="text-caption text-color">
        <div class="font-weight-bold">{{ $t('emailConnector.mailBox.security.warning.title') }}</div>
        <div
          v-for="(warning, index) in warnings"
          :key="index"
          :class="`text-wrap mail-security-warning-${warning.type}`">
          {{ warningLabel(warning) }}
        </div>
      </div>
    </div>
    <div
      v-if="remoteContentBlocked"
      :style="frameStyle(PRIMARY_COLOR)"
      class="mail-remote-content-banner rounded d-flex align-start px-3 py-2 mb-3"
      role="status">
      <v-icon
        size="14"
        class="primary--text me-2 mt-1 flex-shrink-0">
        fas fa-image
      </v-icon>
      <div :class="wide ? 'd-flex align-center flex-grow-1' : 'flex-grow-1'">
        <div :class="wide ? 'text-caption text-color text-wrap flex-grow-1 me-2' : 'text-caption text-color text-wrap'">
          {{ $t('emailConnector.mailBox.remoteContent.blocked') }}
        </div>
        <div :class="wide ? 'd-flex flex-shrink-0' : 'd-flex flex-wrap'">
          <v-btn
            :disabled="loading || busy"
            class="mail-remote-content-show text-caption px-0 me-4"
            color="primary"
            text
            x-small
            @click="$emit('show-remote-content')">
            {{ $t('emailConnector.mailBox.remoteContent.show') }}
          </v-btn>
          <v-btn
            v-if="senderAddress && !hasWarnings"
            :disabled="loading || busy"
            class="mail-remote-content-trust text-caption px-0"
            color="primary"
            text
            x-small
            @click="trustSender">
            {{ $t('emailConnector.mailBox.remoteContent.alwaysShow') }}
          </v-btn>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
/** The platform's primary colour, the accent of the images notice, as the assistant card reads it. */
const PRIMARY_COLOR = 'var(--allPagesPrimaryColor, #3f8487)';

/** The platform's warning colour (platform-ui's @warningColor), the accent of the reasons. */
const WARNING_COLOR = '#ffb441';

/** The platform's light border colour, as its border-color class reads it. */
const BORDER_COLOR = 'var(--allPagesBtnBorder, var(--allPagesGreyColor, #e1e8ee))';

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
    // Whether the reader is wide (the full-screen reader): each notice is then one line.
    wide: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    busy: false,
    PRIMARY_COLOR,
    WARNING_COLOR,
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
     * The frame of a notice: one pixel in the platform's border colour, and a
     * three-pixel accent in the notice's colour on the reading-start side, whose corners
     * are square, as the assistant card's are. The frame is set here rather than by the
     * platform's border-color class, whose important shorthand would hide the accent.
     *
     * @param {String} accent the accent colour
     * @returns {Object} the inline style
     */
    frameStyle(accent) {
      const side = this.$vuetify?.rtl ? 'Right' : 'Left';
      return {
        border: `1px solid ${BORDER_COLOR}`,
        [`border${side}`]: `3px solid ${accent}`,
        [`borderTop${side}Radius`]: 0,
        [`borderBottom${side}Radius`]: 0,
      };
    },
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
