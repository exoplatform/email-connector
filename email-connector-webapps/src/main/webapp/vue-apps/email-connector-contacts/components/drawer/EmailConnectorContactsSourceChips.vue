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
  <!-- The source chips, in the mailbox filter chips' language (outlined,
       filled primary when active, flex-shrink-0 so toggling restyles but never
       resizes). "All" is the user's own store — unambiguously "my contacts",
       since the directory is never browsed here, only imported from. The
       Address book chip joins in phase 3. -->
  <div class="d-flex align-center flex-nowrap overflow-x-auto">
    <v-chip
      v-for="chip in chips"
      :key="chip.id || 'all'"
      :outlined="source !== chip.id"
      :color="source === chip.id ? 'primary' : ''"
      class="me-2 flex-shrink-0"
      small
      @click="$emit('update:source', chip.id)">
      <span :class="source === chip.id ? 'white--text' : 'primary--text'">
        {{ chip.label }}
      </span>
    </v-chip>
  </div>
</template>

<script>
export default {
  props: {
    // The active source: null (All) or 'collected'.
    source: {
      type: String,
      default: null,
    },
  },
  computed: {
    /**
     * The chips to render, in their fixed order.
     *
     * @returns {Array} chip descriptors {id, label}
     */
    chips() {
      return [
        {id: null, label: this.$t('emailConnector.contacts.source.all')},
        {id: 'collected', label: this.$t('emailConnector.contacts.source.collected')},
      ];
    },
  },
};
</script>
