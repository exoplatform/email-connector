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
  <!-- The list, with the "select all" box riding above it while picking — the
       mailbox's arrangement, and for its reason: the box belongs to the rows it
       ticks, not to the header, so it scrolls in the same plane as they do.
       Both drawer layouts mount this, which is what keeps them identical. -->
  <div>
    <v-checkbox
      v-if="selectMode"
      v-model="selectedAll"
      :indeterminate="indeterminate"
      :label="$t('emailConnector.contacts.select.all')"
      class="ps-4 my-2 pt-0"
      color="#707070"
      hide-details
      @click.stop />
    <email-connector-contacts-list
      :contacts="contacts"
      :letter-index="letterIndex"
      :rail-visible="railVisible"
      :selected-id="selectedId"
      :empty-label="emptyLabel"
      :select-mode="selectMode"
      :ticked-ids="tickedIds"
      @select="$emit('select', $event)"
      @tick="$emit('tick', $event)"
      @start-select="$emit('start-select', $event)" />
  </div>
</template>

<script>
export default {
  props: {
    // The rows to render, already in server order.
    contacts: {
      type: Array,
      default: () => [],
    },
    // The subset "all" is allowed to mean: the loaded rows the server would
    // accept. Ticking the others is a promise the next screen breaks, so the
    // Whether the list is picking contacts rather than browsing them.
    selectMode: {
      type: Boolean,
      default: false,
    },
    // The ids ticked so far.
    tickedIds: {
      type: Array,
      default: () => [],
    },
    // Whether the box shows its third state — some but not all ticked. Computed
    // by the drawer, which owns the selection.
    indeterminate: {
      type: Boolean,
      default: false,
    },
    // The ordered letter → count map of the whole filtered set.
    letterIndex: {
      type: Object,
      default: () => ({}),
    },
    // Whether the A–Z rail renders.
    railVisible: {
      type: Boolean,
      default: false,
    },
    // The inline-detail selection, to highlight the open row in expanded mode.
    selectedId: {
      type: Number,
      default: null,
    },
    // The empty-state label.
    emptyLabel: {
      type: String,
      default: '',
    },
  },
  computed: {
    /**
     * Ticks or clears every loaded row.
     * <p>
     * Every row, not only the ones a given action accepts: selecting is not
     * publishing, and what a selection can do is decided in the header, per
     * action, once it exists.
     */
    selectedAll: {
      get() {
        return this.contacts.length > 0 && this.tickedIds.length === this.contacts.length;
      },
      set(value) {
        this.$emit('update:ticked-ids', value ? this.contacts.map(contact => contact.id) : []);
      },
    },
  },
};
</script>
