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
      class="px-4 pt-2 pb-1 caption text-light-color">
      {{ statusLine }}
    </div>
    <template v-if="hasResults">
      <email-connector-mail-box-drawer-search-result-item
        v-for="result in results"
        :key="`${result.folder}-${result.mailRemoteId}`"
        :result="result"
        :row-key="rowKey(result)"
        :opened="rowKey(result) === openedKey"
        @open="$emit('open-result', result)" />
    </template>
    <div
      v-else-if="!serverSearching"
      class="px-4 py-8 text-center text-light-color">
      {{ $t('emailConnector.mailBox.search.noResults') }}
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
  },
  computed: {
    hasResults() {
      return this.results.length > 0;
    },
    // One quiet caption above the results: searching, failed, or truncated.
    statusLine() {
      if (this.serverSearching) {
        return this.$t('emailConnector.mailBox.search.searching');
      }
      if (this.serverError) {
        return this.$t('emailConnector.mailBox.search.error');
      }
      if (this.totalMatches > this.results.length) {
        return this.$t('emailConnector.mailBox.search.showingOf', {
          0: this.results.length,
          1: this.totalMatches,
        });
      }
      return null;
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
