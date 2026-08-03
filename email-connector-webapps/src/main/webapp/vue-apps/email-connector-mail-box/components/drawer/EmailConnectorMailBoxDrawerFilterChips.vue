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
  <!-- The mailbox's quick filters: Important (one of the add-on's categories),
       Favorites (the mail server's \Flagged flag) and Unread (the read flag).
       Three independent axes, deliberately combinable: each chip narrows what
       the others left, so "unread important" or "unread favorites" just work.
       Inside a category VIEW (picked in the ⋮ menu) only Unread remains:
       Important is itself a category, so offering it inside another category's
       view would be contradictory, and Favorites is a different pile — but
       "the unread ones in this category" is the natural follow-up question.
       Same chip language as the platform's category filters (outlined, filled
       primary when active): toggling only restyles the chip, never resizes it,
       so the row cannot flicker under the pointer. -->
  <div class="d-flex align-center flex-nowrap overflow-x-auto category-chips-thin-scrollbar">
    <v-chip
      v-if="importantCategory && !inCategoryView"
      :outlined="!importantOnly"
      :color="importantOnly ? 'primary' : ''"
      class="me-2 flex-shrink-0"
      small
      @click="$emit('toggle-important')">
      <span :class="importantOnly ? 'white--text' : 'primary--text'">
        {{ importantCategory.name }}
      </span>
    </v-chip>
    <v-chip
      v-if="!inCategoryView"
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
    // The Important category ({id, name}), or null while categories load or when
    // the default categories are not seeded — the chip simply doesn't show then.
    importantCategory: {
      type: Object,
      default: null,
    },
    // Whether the Important quick filter is on.
    importantOnly: {
      type: Boolean,
      default: false,
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
    // Whether the list is switched to a category view (from the ⋮ menu); the
    // row then keeps only the Unread chip.
    inCategoryView: {
      type: Boolean,
      default: false,
    },
  },
};
</script>
