<!--
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
-->
<template>
  <!-- The rest of the mailbox, continued quietly. The connector answered from the
       locally held mail so that no search in the platform waits on IMAP; this row
       then asks the mail server for what the cache could not know, once the page is
       already drawn, and shows whatever else it finds. The user sees one list
       filling in, not two searches. -->
  <div v-if="continuation">
    <!-- Both of these sit in the same list skeleton as a result row, so the icon
         gutter and the text start line up with the rows above them. -->
    <v-list v-if="searching" class="pa-0">
      <v-list-item>
        <v-list-item-icon class="ms-1 me-3">
          <v-progress-circular
            indeterminate
            size="18"
            width="2"
            class="mt-2 icon-default-color" />
        </v-list-item-icon>
        <v-list-item-content>
          <v-list-item-subtitle>{{ $t('emailConnector.mailBox.search.searching') }}</v-list-item-subtitle>
        </v-list-item-content>
      </v-list-item>
    </v-list>
    <email-search-row
      v-for="extra in extras"
      :key="extra.id"
      :result="extra"
      :term="searchedTerm" />
    <!-- The mailbox could not be reached. The cached hits are still on screen, so
         this is not an error to shout about -- but the way through should not
         disappear with it. -->
    <v-card
      v-if="failed"
      flat
      class="pa-0"
      @click.stop.prevent="openMailboxSearch">
      <v-list class="pa-0">
        <v-list-item>
          <v-list-item-icon class="ms-1 me-3">
            <v-icon size="18" class="primary--text mt-2">fas fa-search</v-icon>
          </v-list-item-icon>
          <v-list-item-content>
            <v-list-item-subtitle class="primary--text">
              {{ $t('emailConnector.search.connector.searchWholeMailbox') }}
            </v-list-item-subtitle>
          </v-list-item-content>
        </v-list-item>
      </v-list>
    </v-card>
  </div>
  <email-search-row
    v-else
    :result="result"
    :term="term" />
</template>
<script>
// The mail server is asked for a little more than the section shows, because the
// newest hits it returns are often ones the cache already had.
const REMOTE_LIMIT = 10;

export default {
  props: {
    id: {
      type: String,
      default: () => null,
    },
    result: {
      type: Object,
      default: () => ({}),
    },
    term: {
      type: String,
      default: () => null,
    },
  },
  data: () => ({
    extras: [],
    searching: false,
    failed: false,
  }),
  computed: {
    continuation() {
      return !!this.result?.continuation;
    },
    searchedTerm() {
      return this.term || this.result?.term;
    },
  },
  created() {
    if (this.continuation) {
      this.continueOnTheServer();
    }
  },
  methods: {
    /**
     * Runs the whole-mailbox search and appends what the cached answer could not
     * contain.
     *
     * It runs from a row rather than from the connector because the search app owns
     * its result list and hands each row over one at a time: a row can grow, the list
     * cannot. Failing is silent on purpose — the cached results are already on
     * screen, and a mailbox that cannot be reached is not something the person
     * searching the platform asked about.
     *
     * @returns {void}
     */
    continueOnTheServer() {
      const term = this.searchedTerm;
      if (!term) {
        return;
      }
      const alreadyShown = new Set(this.result?.shownIds || []);
      this.searching = true;
      const favorites = this.result?.favoritesOnly && '&favorites=true' || '';
      fetch(`/email-connector/rest/email-box/search?query=${encodeURIComponent(term)}${favorites}&limit=${REMOTE_LIMIT}`, {
        method: 'GET',
        credentials: 'include',
      }).then(response => {
        if (!response?.ok) {
          throw new Error('The mailbox could not be searched');
        }
        return response.json();
      }).then(page => {
        this.extras = (page?.results || []).map(hit => ({
          ...hit,
          id: `${hit.folder}:${hit.mailRemoteId}`,
        })).filter(hit => !alreadyShown.has(hit.id));
      }).catch(() => {
        // The section keeps the results it already showed, and offers the mailbox's
        // own search instead of ending on silence.
        this.extras = [];
        this.failed = true;
      }).finally(() => {
        this.searching = false;
      });
    },
    /**
     * Hands the term to the mailbox's own search field, which is the other way to
     * reach the whole mailbox when this one could not.
     *
     * @returns {void}
     */
    openMailboxSearch() {
      window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-email-box-search', {
          detail: {term: this.searchedTerm},
        })));
    },
  },
};
</script>
