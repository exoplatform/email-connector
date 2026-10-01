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
  <!-- The row above the list (EXO-90838), in the drawer, in full screen and in a shared
       mailbox alike. At rest: the Important, Favorites and Unread chips, and on the right
       the search (the platform's filter funnel) and the advanced search buttons.
       Searching: a back arrow, the search field across the whole column and the advanced
       search button; a lit Favorites or Unread then shows on the criteria line below,
       closable, so it never narrows the search unseen. The arrow closes the field and
       empties it, and the row's chips come back. "/" opens the field from anywhere on
       the page that is not itself a field. Under the row, the criteria line: one chip
       per criterion of the search. -->
  <div>
    <div
      :aria-label="$t('emailConnector.mailBox.search.bar')"
      :class="rowClass"
      :style="rowStyle"
      role="toolbar"
      class="d-flex align-center flex-nowrap">
      <template v-if="searchOpen">
        <v-btn
          :title="$t('emailConnector.mailBox.search.close')"
          :aria-label="$t('emailConnector.mailBox.search.close')"
          class="flex-shrink-0"
          icon
          small
          @click="$emit('close-search')">
          <v-icon size="16" class="icon-default-color">
            {{ $vuetify.rtl && 'fa-arrow-right' || 'fa-arrow-left' }}
          </v-icon>
        </v-btn>
        <!-- Flat and transparent on the row's grey, with the funnel inside, as the
           platform's application toolbar draws its filter field. -->
        <v-text-field
          ref="field"
          :value="searchText"
          :placeholder="$t('emailConnector.mailBox.search.placeholder')"
          :aria-label="$t('emailConnector.mailBox.search.placeholder')"
          :prepend-inner-icon="searchText && 'fa-filter primary--text' || 'fa-filter icon-default-color'"
          class="flex-grow-1 mx-2 mt-0 pt-0"
          background-color="transparent"
          type="search"
          autocomplete="off"
          solo
          flat
          dense
          hide-details
          @input="$emit('search-input', $event)"
          @keydown.esc="$emit('close-search')" />
      </template>
      <email-connector-mail-box-drawer-filter-chips
        v-else
        :important-category="importantCategory"
        :category-view-id="categoryViewId"
        :favorite-only="favoriteOnly"
        :unread-only="unreadOnly"
        class="flex-grow-1"
        style="min-width: 0;"
        @toggle-important="$emit('toggle-important')"
        @toggle-favorite="$emit('toggle-favorite')"
        @toggle-unread="$emit('toggle-unread')" />
      <v-btn
        v-if="searchable && !searchOpen"
        :title="$t('emailConnector.mailBox.search.open')"
        :aria-label="$t('emailConnector.mailBox.search.open')"
        aria-keyshortcuts="/"
        class="ms-1 flex-shrink-0"
        icon
        small
        @click="openSearch">
        <v-icon size="16" class="icon-default-color">fa-filter</v-icon>
      </v-btn>
      <v-btn
        v-if="searchable"
        :title="$t('emailConnector.mailBox.search.advanced.open')"
        :aria-label="$t('emailConnector.mailBox.search.advanced.open')"
        class="ms-1 flex-shrink-0"
        icon
        small
        @click="$emit('advanced-search')">
        <v-icon size="16" class="icon-default-color">fa-sliders-h</v-icon>
      </v-btn>
    </div>
    <email-connector-mail-box-search-criteria-chips
      :chips="criteriaChips"
      @remove="$emit('remove-criterion', $event)"
      @clear="$emit('clear-criteria')" />
  </div>
</template>

<script>
export default {
  props: {
    // The Important category ({id, name, icon}), or null; see the filter chips.
    importantCategory: {
      type: Object,
      default: null,
    },
    // The category view the list is switched to, or null.
    categoryViewId: {
      type: [Number, String],
      default: null,
    },
    // Whether the Favorites chip is lit.
    favoriteOnly: {
      type: Boolean,
      default: false,
    },
    // Whether the Unread chip is lit.
    unreadOnly: {
      type: Boolean,
      default: false,
    },
    // Whether the folder shown has a search: the search and advanced search buttons
    // show only then.
    searchable: {
      type: Boolean,
      default: false,
    },
    // Whether the search field replaces the chips.
    searchOpen: {
      type: Boolean,
      default: false,
    },
    // The search field's text.
    searchText: {
      type: String,
      default: '',
    },
    // The criteria line's chips, [{key, label}].
    criteriaChips: {
      type: Array,
      default: () => [],
    },
    // The row's own classes and style, the drawer's to choose.
    rowClass: {
      type: String,
      default: '',
    },
    rowStyle: {
      type: Object,
      default: null,
    },
  },
  mounted() {
    document.addEventListener('keydown', this.onKeydown);
  },
  beforeDestroy() {
    document.removeEventListener('keydown', this.onKeydown);
  },
  methods: {
    /**
     * Opens the search field and gives it the focus once it is drawn.
     *
     * @returns {void}
     */
    openSearch() {
      this.$emit('open-search');
      // After the drawer has drawn the field it was just asked for.
      this.$nextTick(() => this.$refs.field?.focus());
    },
    /**
     * "/" opens and focuses the search field, as in the platform's other lists -- not
     * while the user types in a field, nor with a modifier, nor when this row is not on
     * screen or the folder has no search.
     *
     * @param {KeyboardEvent} event the key pressed
     * @returns {void}
     */
    onKeydown(event) {
      if (event.key !== '/' || event.ctrlKey || event.metaKey || event.altKey || !this.searchable || !this.$el?.offsetParent) {
        return;
      }
      const target = event.target;
      const tag = (target?.tagName || '').toLowerCase();
      if (tag === 'input' || tag === 'textarea' || tag === 'select' || target?.isContentEditable) {
        return;
      }
      event.preventDefault();
      if (this.searchOpen) {
        this.$refs.field?.focus();
      } else {
        this.openSearch();
      }
    },
  },
};
</script>
