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
    <!-- Quiet indicator that the whole-mailbox server search is still running,
         while the instant local matches are already listed below. -->
    <v-progress-linear
      v-if="serverSearching"
      indeterminate
      height="2" />
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
};
</script>
