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
  <!-- The mailbox's quick chips: Important, Favorites (the mail server's
       \Flagged flag) and Unread (the read flag).
       The Important chip is a SHORTCUT into the Important category view — the
       same state the ⋮ menu's Important entry drives, so the chip carries the
       category's own icon and lights exactly when that view is open: two
       controls, one state, and the two surfaces can never disagree. Inside the
       Important view it stays (lit — it is the view's own control); inside
       another category's view it hides, since Important is itself a view.
       Favorites and Unread show in EVERY view and combine with it — "the ones
       I kept in this category" and "the unread ones in this category" are both
       real questions: each chip narrows what the view and the other chip left.
       Same chip language as the platform's category filters (outlined, filled
       primary when active): toggling only restyles the chip, never resizes it,
       so the row cannot flicker under the pointer. -->
  <div class="d-flex align-center flex-nowrap overflow-x-auto category-chips-thin-scrollbar">
    <v-chip
      v-if="importantCategory && !inOtherCategoryView"
      :outlined="!importantViewActive"
      :color="importantViewActive ? 'primary' : ''"
      class="me-2 flex-shrink-0"
      small
      @click="$emit('toggle-important')">
      <v-icon
        size="12"
        class="me-1"
        :class="importantViewActive ? 'white--text' : 'primary--text'">
        {{ importantCategory.icon || 'fa-tag' }}
      </v-icon>
      <span :class="importantViewActive ? 'white--text' : 'primary--text'">
        {{ importantCategory.name }}
      </span>
    </v-chip>
    <v-chip
      :outlined="!favoriteOnly"
      :color="favoriteOnly ? 'primary' : ''"
      class="me-2 flex-shrink-0"
      small
      @click="$emit('toggle-favorite')">
      <span :class="favoriteOnly ? 'white--text' : 'primary--text'">
        {{ $t('emailConnector.mailBox.list.drawer.folder.favorites') }}
      </span>
    </v-chip>
    <v-chip
      :outlined="!unreadOnly"
      :color="unreadOnly ? 'primary' : ''"
      class="flex-shrink-0"
      small
      @click="$emit('toggle-unread')">
      <span :class="unreadOnly ? 'white--text' : 'primary--text'">
        {{ $t('emailConnector.mailBox.list.drawer.filter.unread') }}
      </span>
    </v-chip>
  </div>
</template>

<script>
export default {
  props: {
    // The Important category ({id, name, icon}), or null while categories load
    // or when the default categories are not seeded — the chip simply doesn't
    // show then.
    importantCategory: {
      type: Object,
      default: null,
    },
    // The category view the list is switched to (shared state with the ⋮
    // menu's Categories section), or null outside any category view.
    categoryViewId: {
      type: [Number, String],
      default: null,
    },
    // Whether the favorite view is on (server-side \Flagged filter).
    favoriteOnly: {
      type: Boolean,
      default: false,
    },
    // Whether the list is narrowed to unread messages (client-side filter).
    unreadOnly: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    /**
     * Whether the Important chip is lit — i.e. the Important category view is
     * the one the list is switched to.
     *
     * @returns {Boolean} true when the Important view is open
     */
    importantViewActive() {
      return !!this.importantCategory && this.categoryViewId === this.importantCategory.id;
    },
    /**
     * Whether the list is switched to a category view other than Important;
     * only the Important chip hides then (Important is itself a view, so
     * offering it inside another category's view would be contradictory) —
     * Favorites and Unread stay and combine with the view.
     *
     * @returns {Boolean} true inside a non-Important category view
     */
    inOtherCategoryView() {
      return !!this.categoryViewId && !this.importantViewActive;
    },
  },
};
</script>
