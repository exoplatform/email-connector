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
       mails with a suggestion of an assistant waiting for them, newest first, from their
       own endpoint, read with the waiting suggestions (refreshWaitingSuggestions), which
       the folder column counts from, and handed over by the drawer (emails), which
       leaves out the mails an action here took out of their folder and stamps a star or
       a read status on them (EXO-90871). Each one is the folder list's own row -- star,
       selection, categories, drag, the count of its waiting suggestions --, alone in its
       conversation, keyed by folder and UID since it may sit in any folder, which the row
       names; opened as a search hit is, and its Automations panel shows open, ready to
       approve or reject (isSuggestionsViewListed). No loading bar of its own: the
       drawer's header bar shows what it waits on (the loading event), as for the
       Scheduled view. -->
  <div class="suggestions-email-list">
    <div
      v-if="emails.length"
      :aria-label="$t('emailConnector.mailBox.list.drawer.folder.suggestions')"
      role="group">
      <email-connector-mail-box-drawer-list-item
        v-for="mail in emails"
        :key="keyOf(mail)"
        :email="mail"
        :emails="emails"
        :row-key="keyOf(mail)"
        :opened-key="openedKey"
        :select-mode="selectMode"
        :selected-emails="selectedEmails"
        :expanded="compact"
        :drag-source="dragSource"
        show-folder
        @open="open(mail)" />
    </div>
    <div
      v-if="loaded && !emails.length"
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
import { refreshWaitingSuggestions, setSuggestionsViewListed, waitingSuggestionsReadFailed } from '../../js/EmailConnectorMailFilters.js';

export default {
  props: {
    // The view's mails, newest first, as the drawer reads them off the last read of the
    // waiting suggestions (its suggestionMails) -- the read the folder column's count
    // comes from too, so the two always agree, and which follows every read: a decision
    // taken in a mail's panel, a new run of an assistant, a suggestion expired.
    emails: {
      type: Array,
      default: () => [],
    },
    // Whether it sits in the full-screen list column rather than in the narrow drawer:
    // its rows are then the full-screen rows, lit as the reader's and dragged onto the
    // folder column.
    compact: {
      type: Boolean,
      default: false,
    },
    // Whether the rows are being selected, and the selection's keys (folder:uid), as the
    // folder list takes them (EXO-90871).
    selectMode: {
      type: Boolean,
      default: false,
    },
    selectedEmails: {
      type: Array,
      default: () => [],
    },
    // The mail being dragged from the list, for its rows to fade (EXO-90421).
    dragSource: {
      type: Object,
      default: null,
    },
  },
  data: () => ({
    loading: false,
    loaded: false,
    // The mail the reader was opened on from this view, by its folder and UID.
    openedKey: null,
  }),
  watch: {
    loading: {
      immediate: true,
      handler(loading) {
        this.$emit('loading', loading);
      },
    },
  },
  created() {
    setSuggestionsViewListed(true);
    // A suggestion decided, or a rule's work undone or run again, in the reader: the
    // waiting suggestions are read again at once, the view and its count with them.
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
     * Reads the waiting suggestions now, whatever the last read's age: the view opens on
     * what waits at this moment. A failed read says so; the list keeps what it held.
     *
     * @returns {Promise<void>} resolved once read
     */
    reload() {
      this.loading = true;
      return refreshWaitingSuggestions(true)
        .then(() => {
          if (waitingSuggestionsReadFailed()) {
            this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.suggestions.loadError'), 'error');
          }
        })
        .finally(() => {
          this.loading = false;
          this.loaded = true;
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
     * Reads the waiting suggestions again at once, which reads the view and its count.
     *
     * @returns {void}
     */
    onAutomationsUpdated() {
      refreshWaitingSuggestions(true);
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
