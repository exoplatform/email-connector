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
  <!-- The contact picker of the chat's "+" menu: the same alphabetical list the
       contacts drawer renders, in its select role — the list component already
       emits the picked row, so this drawer only feeds it and hands the pick to
       whoever opened it. No detail view, no editing: picking is the whole job. -->
  <exo-drawer
    id="emailChatContactPickerDrawer"
    ref="pickerDrawer"
    v-model="drawer"
    right
    @closed="reset">
    <template #title>
      <span>{{ $t('emailConnector.contacts.chatPicker.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <div class="px-4 pt-4">
        <v-text-field
          v-model="term"
          :placeholder="$t('emailConnector.contacts.chatPicker.searchPlaceholder')"
          prepend-inner-icon="fa-search"
          class="pt-0 mt-0"
          clearable
          hide-details
          @input="onTermInput" />
      </div>
      <v-progress-linear
        v-if="loading"
        indeterminate />
      <email-connector-contacts-list
        :contacts="contacts"
        :letter-index="letterIndex"
        :empty-label="loading && ' ' || $t('emailConnector.contacts.chatPicker.empty')"
        @select="pick" />
    </template>
  </exo-drawer>
</template>

<script>
// One page of the store per request; every page is loaded, the way the
// contacts drawer loads, so the letter headers are always complete.
const PAGE_SIZE = 100;

// The pages a single listing may fetch — 5000 rows, the store's own import cap.
const MAX_PAGES = 50;

export default {
  data() {
    return {
      drawer: false,
      term: null,
      contacts: [],
      letterIndex: {},
      loading: false,
      searchTimeout: null,
      // What to do with the picked contact — handed to open() by the caller,
      // because the picker neither knows nor cares what a pick is for.
      onSelect: null,
      // Stamps a reload so a slow page of a superseded search cannot land on
      // top of a newer one.
      loadStamp: 0,
    };
  },
  methods: {
    /**
     * Opens the picker on the caller's whole store, remembering what to do
     * with the pick.
     *
     * @param {Function} onSelect - called with the picked contact
     * @returns {void}
     */
    open(onSelect) {
      this.onSelect = onSelect || null;
      this.term = null;
      this.drawer = true;
      this.$refs.pickerDrawer.open();
      this.load();
    },
    /**
     * Debounces typing into one reload, the same 300ms the contacts drawer
     * gives a search.
     *
     * @returns {void}
     */
    onTermInput() {
      window.clearTimeout(this.searchTimeout);
      this.searchTimeout = window.setTimeout(() => this.load(), 300);
    },
    /**
     * Loads the filtered store whole, first page then the rest — the list
     * groups by the server-computed letter, and complete groups need every
     * page. Chained the way the contacts drawer chains its own pages.
     *
     * @returns {Promise<void>} resolves when every page landed, or the load was
     *          superseded by a newer one
     */
    load() {
      const stamp = ++this.loadStamp;
      this.loading = true;
      return this.$emailConnectorContactsService.getContacts(this.term, 0, PAGE_SIZE)
        .then(firstPage => {
          if (stamp !== this.loadStamp) {
            return;
          }
          this.contacts = firstPage?.contacts || [];
          this.letterIndex = firstPage?.letterIndex || {};
          const size = Math.min(firstPage?.size || 0, PAGE_SIZE * MAX_PAGES);
          return this.loadRemainingPages(stamp, this.contacts.length, size);
        })
        .catch(error => {
          console.error('The contacts could not be listed for the chat picker', error);
          this.$root.$emit('alert-message', this.$t('emailConnector.contacts.chatPicker.error'), 'error');
        })
        .finally(() => {
          if (stamp === this.loadStamp) {
            this.loading = false;
          }
        });
    },
    /**
     * Streams the pages after the first one in, one after the other, stopping
     * the moment a newer reload superseded this one.
     *
     * @param {number} stamp - the load this chain belongs to
     * @param {number} offset - the next page's offset
     * @param {number} total - how many rows to load in all
     * @returns {Promise<void>} resolves when the last page landed
     */
    loadRemainingPages(stamp, offset, total) {
      if (offset >= total || stamp !== this.loadStamp) {
        return Promise.resolve();
      }
      return this.$emailConnectorContactsService.getContacts(this.term, offset, PAGE_SIZE)
        .then(page => {
          if (stamp !== this.loadStamp || !page?.contacts?.length) {
            return;
          }
          this.contacts = this.contacts.concat(page.contacts);
          return this.loadRemainingPages(stamp, offset + PAGE_SIZE, total);
        });
    },
    /**
     * Hands the picked contact to the caller and closes — the pick is the
     * drawer's whole outcome.
     *
     * @param {object} contact - the picked row
     * @returns {void}
     */
    pick(contact) {
      const onSelect = this.onSelect;
      this.close();
      if (onSelect) {
        onSelect(contact);
      }
    },
    /**
     * Closes the drawer.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
      this.$refs.pickerDrawer.close();
    },
    /**
     * Forgets the closed drawer's state, so the next open starts clean.
     *
     * @returns {void}
     */
    reset() {
      this.drawer = false;
      this.term = null;
      this.contacts = [];
      this.letterIndex = {};
      this.onSelect = null;
      this.loading = false;
      this.loadStamp++;
    },
  },
};
</script>
