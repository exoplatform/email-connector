<!--
Copyright (C) 2025 eXo Platform SAS.

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
  <div>
    <!-- The whole-mailbox server search still running, while the instant local matches
         are already listed below, shows as the drawer's own header loading bar — the
         platform's indicator — rather than a thinner bar of this list's own. -->
    <div
      v-if="statusLine"
      aria-live="polite"
      class="px-4 pt-2 pb-1 caption text-light-color">
      {{ statusLine }}
    </div>
    <!-- Each hit is the folder list's own row (EXO-90871): its star, its selection and
         bulk actions, its categories, its drag onto a folder, alone in its conversation,
         keyed and lit by folder and UID, naming the folder it was found in, and opened
         by this list -- an uncached hit is pulled in first (open-result). -->
    <template v-if="hasResults">
      <email-connector-mail-box-drawer-list-item
        v-for="result in results"
        :key="`${result.folder}-${result.mailRemoteId}`"
        :email="result"
        :emails="results"
        :row-key="rowKey(result)"
        :opened-key="openedKey"
        :select-mode="selectMode"
        :selected-emails="selectedEmails"
        :expanded="expanded"
        :drag-source="dragSource"
        :folders="folders"
        show-folder
        @open="$emit('open-result', result)" />
    </template>
    <div
      v-else-if="!serverSearching && !localSearching"
      class="px-4 py-8 text-center text-light-color">
      {{ $t('emailConnector.mailBox.search.noResults') }}
    </div>
    <!-- The server arm of an advanced search, asked by the user (EXO-90838): after the
         last hit, or alone when eXo's copy held none and the server has not been asked
         yet. -->
    <div
      v-if="offerServerSearch"
      class="px-4 py-2 text-center">
      <v-btn
        class="text-none"
        color="primary"
        small
        text
        @click="$emit('search-server')">
        {{ $t('emailConnector.mailBox.search.local.server') }}
      </v-btn>
    </div>
  </div>
</template>

<script>
export default {
  props: {
    // The merged (local-instant + server) search hits, newest first.
    results: {
      type: Array,
      default: () => [],
    },
    // The hit the reader shows, as rowKey keys it; null when it shows none.
    openedKey: {
      type: String,
      default: null,
    },
    // Whether the list sits in the full-screen layout: its hits are then lit as the
    // reader's and dragged onto the folder column (EXO-90421), as the folder list's rows.
    expanded: {
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
    // The mail being dragged from the list, for its hits to fade (EXO-90421).
    dragSource: {
      type: Object,
      default: null,
    },
    // The mailbox's folders, for each hit to name the one it was found in.
    folders: {
      type: Array,
      default: () => [],
    },
    // The full server-side match count, to say 'showing 20 of 1,234'.
    totalMatches: {
      type: Number,
      default: 0,
    },
    // Whether the whole-mailbox server search is still in flight.
    serverSearching: {
      type: Boolean,
      default: false,
    },
    // Whether the server search failed; the local matches stay usable.
    serverError: {
      type: Boolean,
      default: false,
    },
    // Whether the search ran in a mailbox shared with the user (EXO-90590): it reads the
    // copy of that mailbox kept in eXo, not its owner's whole mailbox, and says so.
    sharedMailbox: {
      type: Boolean,
      default: false,
    },
    // Whether the mail server can be asked for an advanced search that read eXo's copy
    // only (EXO-90838): its button then follows the hits.
    offerServerSearch: {
      type: Boolean,
      default: false,
    },
    // Whether eXo's copy is being read for an advanced search.
    localSearching: {
      type: Boolean,
      default: false,
    },
    // How many server matches were examined for an attachment, when not all; 0 else.
    scanned: {
      type: Number,
      default: 0,
    },
  },
  computed: {
    hasResults() {
      return this.results.length > 0;
    },
    /**
     * One quiet caption above the results: searching, failed, or truncated -- and, in a
     * shared mailbox, what was searched (EXO-90590): the recent mail kept in eXo, where
     * the user's own mailbox is searched on the mail server, so the same term can find
     * less there.
     *
     * @returns {String} the caption, or null for none
     */
    statusLine() {
      if (this.serverSearching) {
        return this.$t(this.sharedMailbox ? 'emailConnector.mailBox.search.shared.searching' : 'emailConnector.mailBox.search.searching');
      }
      if (this.serverError) {
        return this.$t(this.sharedMailbox ? 'emailConnector.mailBox.search.shared.error' : 'emailConnector.mailBox.search.error');
      }
      if (this.scanned) {
        return this.$t('emailConnector.mailBox.search.attachmentScanned', { 0: this.scanned });
      }
      if (this.totalMatches > this.results.length) {
        return this.$t(this.sharedMailbox ? 'emailConnector.mailBox.search.shared.showingOf' : 'emailConnector.mailBox.search.showingOf', {
          0: this.results.length,
          1: this.totalMatches,
        });
      }
      return this.sharedMailbox ? this.$t('emailConnector.mailBox.search.shared.recentOnly') : null;
    },
  },
  methods: {
    /**
     * A hit's key: its folder and UID, a UID being unique only within its folder --
     * the key the arrow keys walk the results by (searchRows).
     *
     * @param {Object} result the hit
     * @returns {String} the key
     */
    rowKey(result) {
      return `${result.folder || 'INBOX'}:${result.mailRemoteId}`;
    },
    /**
     * Gives one hit the keyboard focus and brings it into view -- the arrow keys' way of
     * walking the results (EXO-90414). Every hit is rendered, so there is nothing to
     * build first.
     *
     * @param {String} key the hit's key (rowKey)
     * @returns {Promise<void>} resolved once the hit has the focus
     */
    async revealThread(key) {
      await this.$nextTick();
      const row = Array.from(this.$el.querySelectorAll('[data-thread-key]'))
        .find(element => element.getAttribute('data-thread-key') === String(key));
      if (!row) {
        return;
      }
      row.focus({ preventScroll: true });
      if (row.scrollIntoView) {
        row.scrollIntoView({ block: 'nearest' });
      }
    },
  },
};
</script>
