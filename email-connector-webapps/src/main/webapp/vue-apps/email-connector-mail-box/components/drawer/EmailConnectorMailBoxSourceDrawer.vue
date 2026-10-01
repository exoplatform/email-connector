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
  <!-- "Show original" (EXO-90842): the message's headers and RFC 822 source as the mail
       server holds them. Both are the sender's bytes, so they are only ever interpolated
       as text in a <pre>, never bound as markup. A source longer than the server's shown
       limit is cut there, and the drawer says so and offers the whole as a download. -->
  <exo-drawer
    id="emailSourceDrawer"
    ref="emailSourceDrawer"
    v-model="drawer"
    :loading="loading || downloading"
    right
    @closed="close">
    <template #title>
      <span>{{ $t('emailConnector.mailBox.source.drawer.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <div class="pa-4">
        <!-- While the source is read, the drawer's own bar under its title says so. -->
        <div
          v-if="errorMessage && !loading"
          class="text-sub-title">
          {{ errorMessage }}
        </div>
        <template v-else-if="source && !loading">
          <div class="d-flex align-center mb-2">
            <span class="font-weight-bold">{{ $t('emailConnector.mailBox.source.drawer.headers') }}</span>
            <v-spacer />
            <v-btn
              :title="$t('emailConnector.mailBox.source.drawer.copyHeaders')"
              icon
              small
              @click="copy(source.headers)">
              <v-icon size="16" class="icon-default-color">fa-copy</v-icon>
            </v-btn>
          </div>
          <pre
            class="pa-2 mb-4 border-color border-radius"
            :style="preStyle">{{ source.headers }}</pre>
          <div class="d-flex align-center mb-2">
            <span class="font-weight-bold">{{ $t('emailConnector.mailBox.source.drawer.source') }}</span>
            <v-spacer />
            <v-btn
              :title="$t('emailConnector.mailBox.source.drawer.copySource')"
              icon
              small
              @click="copy(source.source)">
              <v-icon size="16" class="icon-default-color">fa-copy</v-icon>
            </v-btn>
          </div>
          <div
            v-if="source.truncated"
            class="caption text-sub-title mb-2">
            {{ truncatedMessage }}
          </div>
          <pre
            class="pa-2 border-color border-radius"
            :style="preStyle">{{ source.source }}</pre>
        </template>
      </div>
    </template>
    <template #footer>
      <div class="d-flex">
        <v-spacer />
        <v-btn
          v-if="email"
          :loading="downloading"
          class="btn me-2"
          @click="download">
          <v-icon size="14" class="me-2">fa-download</v-icon>
          {{ $t('emailConnector.mailBox.source.download') }}
        </v-btn>
        <v-btn class="btn" @click="$refs.emailSourceDrawer.close()">
          {{ $t('emailConnector.mailBox.source.drawer.close') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import { OPEN_SOURCE_DRAWER_EVENT, downloadRawEmail, getRawEmailSource, rawEmailErrorKey } from '../../js/EmailConnectorRawEmail.js';

export default {
  data() {
    return {
      drawer: false,
      email: null,
      source: null,
      loading: false,
      downloading: false,
      errorMessage: null,
    };
  },
  computed: {
    /**
     * Monospaced, wrapped, scrolling sideways never: a source line can be a thousand
     * characters of base64.
     *
     * @returns {Object} the inline style of both blocks
     */
    preStyle() {
      return {
        fontFamily: 'monospace',
        fontSize: '12px',
        whiteSpace: 'pre-wrap',
        wordBreak: 'break-all',
        margin: 0,
      };
    },
    /**
     * What the cut source says about itself: how much of the message it shows.
     *
     * @returns {String} the sentence
     */
    truncatedMessage() {
      return this.$t('emailConnector.mailBox.source.drawer.truncated', {
        0: this.formatSize(this.source?.shownBytes || 0),
        1: this.formatSize(this.source?.size || 0),
      });
    },
  },
  created() {
    this.$root.$on(OPEN_SOURCE_DRAWER_EVENT, this.open);
  },
  beforeDestroy() {
    this.$root.$off(OPEN_SOURCE_DRAWER_EVENT, this.open);
  },
  methods: {
    /**
     * Opens the drawer on a message and reads its source.
     *
     * @param {Object} email the message: {mailRemoteId, folder}
     * @returns {void}
     */
    open(email) {
      if (!email?.mailRemoteId) {
        return;
      }
      this.email = email;
      this.source = null;
      this.errorMessage = null;
      this.loading = true;
      this.drawer = true;
      this.$refs.emailSourceDrawer.open();
      getRawEmailSource(email)
        .then(source => {
          // A drawer reopened on another message meanwhile keeps that one's answer.
          if (this.email === email) {
            this.source = source;
          }
        })
        .catch(error => {
          if (this.email === email) {
            this.errorMessage = this.errorText(error);
          }
        })
        .finally(() => {
          if (this.email === email) {
            this.loading = false;
          }
        });
    },
    /**
     * Downloads the whole message as a .eml file.
     *
     * @returns {void}
     */
    download() {
      this.downloading = true;
      downloadRawEmail(this.email)
        .catch(error => this.$root.$emit('alert-message', this.errorText(error), 'error'))
        .finally(() => this.downloading = false);
    },
    /**
     * Copies a block to the clipboard and says so.
     *
     * @param {String} text the block
     * @returns {void}
     */
    copy(text) {
      const clipboard = window.navigator?.clipboard;
      const copied = clipboard ? clipboard.writeText(text || '') : Promise.reject(new Error('No clipboard'));
      copied
        .then(() => this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.source.drawer.copied'), 'success'))
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.source.drawer.copyFailed'), 'error'));
    },
    /**
     * The sentence for a refused or failed read, by status.
     *
     * @param {Error} error the error, carrying the HTTP status
     * @returns {String} the sentence
     */
    errorText(error) {
      return this.$t(rawEmailErrorKey(error));
    },
    /**
     * A byte count in the unit a person reads it in.
     *
     * @param {Number} bytes the count
     * @returns {String} e.g. "512 KB", "3.2 MB"
     */
    formatSize(bytes) {
      if (bytes < 1024) {
        return `${bytes} B`;
      }
      if (bytes < 1024 * 1024) {
        return `${Math.round(bytes / 1024)} KB`;
      }
      return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
    },
    /**
     * Forgets the message, so a later open starts clean.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
      this.email = null;
      this.source = null;
      this.errorMessage = null;
    },
  },
};
</script>
