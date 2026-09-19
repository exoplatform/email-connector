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
  <!-- Compact, at the top of the full-screen list column under its chips (EXO-90415):
       icon and message centred as one block, in the platform's muted colours, saying
       why the list is empty -- the folder, or the filters, which it offers to clear. -->
  <div
    v-if="compact"
    class="text-center px-4 pt-10">
    <v-icon size="32" class="icon-default-color">far fa-envelope</v-icon>
    <div class="mt-2 text-subtitle text-sub-title text-wrap">
      {{ message }}
    </div>
    <v-btn
      v-if="filtered"
      class="mt-2"
      color="primary"
      small
      text
      @click="$emit('clear-filters')">
      {{ $t('emailConnector.mailBox.list.drawer.noEmail.clearFilters') }}
    </v-btn>
  </div>
  <!-- Otherwise it fills the narrow drawer, as it always did. -->
  <v-list-item v-else class="full-height align-center">
    <v-list-item-content>
      <v-icon
        size="60"
        class="tertiary--text">
        far fa-envelope
      </v-icon>
      <v-list-item-title class="text-wrap mt-5">
        {{ $t('emailConnector.mailBox.list.drawer.noEmail') }}
      </v-list-item-title>
    </v-list-item-content>
  </v-list-item>
</template>

<script>
export default {
  props: {
    // Whether it sits at the top of the full-screen list column rather than filling
    // the narrow drawer.
    compact: {
      type: Boolean,
      default: false,
    },
    // The listed folder's name, for "No email in <folder>".
    folderName: {
      type: String,
      default: null,
    },
    // Whether chips or a category view narrow the list: the message says so, and
    // offers to clear them (the clear-filters event).
    filtered: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    /**
     * Why the list is empty: its filters, or its folder.
     *
     * @returns {String} the message
     */
    message() {
      if (this.filtered) {
        return this.$t('emailConnector.mailBox.list.drawer.noEmail.filtered');
      }
      return this.folderName
        ? this.$t('emailConnector.mailBox.list.drawer.noEmail.folder', { 0: this.folderName })
        : this.$t('emailConnector.mailBox.list.drawer.noEmail');
    },
  },
};
</script>
