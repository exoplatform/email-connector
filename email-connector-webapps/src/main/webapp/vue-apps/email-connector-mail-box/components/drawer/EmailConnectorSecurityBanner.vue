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
       text size and tight padding so it reads as a light notice. The images notice is one
       line with its links at the end when the pane is wide enough, its links under the
       text otherwise. The reasons quote what the message presents as text, never as markup.
       "Always show" is not offered on a message that looks suspicious. Inline style for
       the accent because this webapp's webpack has no CSS loader. -->
  <div
    v-if="hasWarnings || remoteContentBlocked"
    :style="{ paddingBottom: '12px' }"
    class="mail-security-banners text-start">
    <div
      v-if="hasWarnings"
      :style="frameStyle(WARNING_COLOR)"
      :class="{ 'mb-2': remoteContentBlocked }"
      class="mail-security-warning d-flex align-start px-2 py-1"
      role="alert">
      <v-icon
        size="14"
        class="warning--text me-2 mt-1 flex-shrink-0">
        fas fa-exclamation-triangle
      </v-icon>
      <div :style="TEXT_BLOCK_STYLE" class="text-caption text-color text-start">
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
      class="mail-remote-content-banner d-flex align-start px-2 py-1"
      role="status">
      <v-icon
        size="14"
        class="primary--text me-2 mt-1 flex-shrink-0">
        fas fa-image
      </v-icon>
      <!-- The text and the actions share one line when the reading pane has room for
           both; when it has not, the actions wrap under the text, which then takes the
           whole line. The text grows to push the actions to the end of a shared line and
           never wraps beside them: they wrap first. The platform's core.css
           gives the align-center class text-align: center, so the line is centred
           vertically by an inline style instead, and the text sets its own start
           alignment. -->
      <div
        :style="LINE_STYLE"
        class="d-flex flex-wrap flex-grow-1">
        <div
          :style="MESSAGE_STYLE"
          class="text-caption text-color text-wrap text-start">
          {{ $t('emailConnector.mailBox.remoteContent.blocked') }}
        </div>
        <div
          :style="ACTIONS_STYLE"
          class="d-flex flex-wrap">
          <v-btn
            :disabled="loading || busy"
            class="mail-remote-content-show text-caption px-0"
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

/**
 * A text block of a notice: it takes the flexible space of its line, may shrink below
 * its content's width so long words wrap, and reads from the start whatever alignment
 * the surrounding page sets.
 */
const TEXT_BLOCK_STYLE = { flex: '1 1 auto', minWidth: 0, textAlign: 'start' };

/**
 * The line of the images notice: it takes the flexible space after the icon, centres its
 * text and actions vertically, and puts a gap between the text and actions that share it.
 */
const LINE_STYLE = { minWidth: 0, alignItems: 'center', columnGap: '8px' };

/**
 * The text of the images notice: it grows to fill its line, and asks for the width of its
 * text on one line, so the actions wrap under it as soon as both no longer fit, rather
 * than squeezing it beside them; alone on its line, it may then wrap itself.
 */
const MESSAGE_STYLE = { flex: '1 1 auto', minWidth: 0, textAlign: 'start' };

/**
 * The actions of the images notice: as wide as their labels on a shared line, and, once
 * wrapped under the text, free to shrink so a pane narrower than both labels puts the
 * second under the first.
 */
const ACTIONS_STYLE = { flex: '0 1 auto', minWidth: 0, columnGap: '16px' };

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
  },
  data: () => ({
    busy: false,
    PRIMARY_COLOR,
    WARNING_COLOR,
    TEXT_BLOCK_STYLE,
    LINE_STYLE,
    MESSAGE_STYLE,
    ACTIONS_STYLE,
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
        // Set here rather than by the rounded class, whose important radius would round
        // the accent's corners too.
        borderRadius: '4px',
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
