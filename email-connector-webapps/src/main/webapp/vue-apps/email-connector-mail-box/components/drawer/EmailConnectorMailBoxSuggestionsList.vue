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
  <!-- The "Suggestions" view (EXO-90851), in place of a folder's list: the user's own
       mails with a suggestion of an assistant waiting for them, newest first, read from
       their own endpoint. Each one is drawn and opened as a search hit is -- it may sit
       in any folder --, and its Automations panel shows open, ready to approve or reject
       (isSuggestionsViewListed). No loading bar of its own: the drawer's header bar shows
       what it waits on (the loading event), as for the Scheduled view. -->
  <div class="suggestions-email-list">
    <div
      v-if="items.length"
      :aria-label="$t('emailConnector.mailBox.list.drawer.folder.suggestions')"
      role="group">
      <template v-for="(mail, index) in items">
        <v-divider v-if="index > 0" :key="`divider-${keyOf(mail)}`" />
        <email-connector-mail-box-drawer-search-result-item
          :key="keyOf(mail)"
          :result="mail"
          :row-key="keyOf(mail)"
          :opened="openedKey === keyOf(mail)"
          @open="open(mail)" />
      </template>
    </div>
    <div
      v-if="loaded && !items.length"
      :class="compact ? 'pt-10' : 'pt-16'"
      class="text-center px-4 suggestions-email-empty">
      <v-icon :size="compact ? 32 : 60" class="icon-default-color">fa-magic</v-icon>
      <div class="mt-2 text-subtitle text-sub-title text-wrap">
        {{ $t('emailConnector.mailBox.suggestions.empty') }}
      </div>
    </div>
  </div>
</template>

<script>
import { refreshWaitingSuggestions, setSuggestionsViewListed, waitingSuggestionTotal } from '../../js/EmailConnectorMailFilters.js';

export default {
  props: {
    // Whether it sits in the full-screen list column rather than in the narrow drawer.
    compact: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    items: [],
    loading: false,
    loaded: false,
    // The mail the reader was opened on from this view, by its folder and UID.
    openedKey: null,
  }),
  computed: {
    /**
     * How many suggestions wait over the mailbox, as last read: the view reads its mails
     * again whenever it moves -- a decision taken in a mail's panel, a new run of an
     * assistant, a suggestion expired.
     *
     * @returns {Number} the count
     */
    waitingTotal() {
      return waitingSuggestionTotal();
    },
  },
  watch: {
    waitingTotal() {
      this.reload();
    },
    loading: {
      immediate: true,
      handler(loading) {
        this.$emit('loading', loading);
      },
    },
  },
  created() {
    // Which read is current: an answer for an older one is dropped. Plain: nothing
    // renders it.
    this.readRequest = 0;
    setSuggestionsViewListed(true);
    // A suggestion decided, or a rule's work undone or run again, in the reader: the
    // count the folder column shows is read again at once, and the view with it.
    this.$root.$on('email-automations-updated', this.onAutomationsUpdated);
    // The reader moved off the mail, or its drawer closed: no row stays lit.
    this.$root.$on('set-opened', this.onSetOpened);
    this.$root.$on('email-detail-drawer-closed', this.onReaderClosed);
    this.reload();
  },
  beforeDestroy() {
    setSuggestionsViewListed(false);
    this.$root.$off('email-automations-updated', this.onAutomationsUpdated);
    this.$root.$off('set-opened', this.onSetOpened);
    this.$root.$off('email-detail-drawer-closed', this.onReaderClosed);
    // Gone with its wait: the drawer's bar must not stay on for a list nobody sees.
    this.$emit('loading', false);
  },
  methods: {
    /**
     * Reads the view's mails. A refusal says so; the list keeps what it held.
     *
     * @returns {Promise<void>} resolved once read, or dropped
     */
    reload() {
      const request = ++this.readRequest;
      this.loading = true;
      return this.$emailConnectorUserSettingService.getWaitingSuggestionEmails()
        .then(mails => {
          if (request === this.readRequest) {
            this.items = mails || [];
          }
        })
        .catch(() => {
          if (request === this.readRequest) {
            this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.suggestions.loadError'), 'error');
          }
        })
        .finally(() => {
          if (request === this.readRequest) {
            this.loading = false;
            this.loaded = true;
          }
        });
    },
    /**
     * A row's key: its folder and UID, which name one message together.
     *
     * @param {Object} mail the row
     * @returns {String} the key
     */
    keyOf(mail) {
      return `${mail.folder || 'INBOX'}:${mail.mailRemoteId}`;
    },
    /**
     * Opens a mail in the reader, as a mail picked outside the list is opened -- the
     * drawer's openMailFromOutside: in the full-screen reader beside the list, else in
     * the mail drawer.
     *
     * @param {Object} mail the row
     * @returns {void}
     */
    open(mail) {
      this.$root.$emit('open-suggested-email', { mailRemoteId: mail.mailRemoteId, folder: mail.folder, cached: true });
      this.openedKey = this.keyOf(mail);
    },
    /**
     * Reads the waiting suggestions again at once, which reads the view again when their
     * count moved, and the view itself in any case: a decision may leave the count where
     * it was while another suggestion arrived meanwhile.
     *
     * @returns {void}
     */
    onAutomationsUpdated() {
      refreshWaitingSuggestions(true);
      this.reload();
    },
    /**
     * Forgets the opened mail when the full-screen reader shows nothing any more.
     *
     * @param {Number} mailRemoteId what the reader now shows, nothing for nothing
     * @returns {void}
     */
    onSetOpened(mailRemoteId) {
      if (mailRemoteId == null) {
        this.openedKey = null;
      }
    },
    /**
     * Forgets the opened mail when the mail drawer showing it closed.
     *
     * @returns {void}
     */
    onReaderClosed() {
      this.openedKey = null;
    },
  },
};
</script>
