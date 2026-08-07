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
  <!-- Where a contact came from, as toggles, one chip per stored source. There
       is deliberately no "All" chip: nothing selected is what shows everything,
       the way the mailbox's category filter already behaves, and a chip selected
       by default teaches people the list is filtered when it is not. -->
  <div v-if="visible" class="d-flex px-4 pt-2">
    <v-chip-group
      :value="value"
      multiple
      column
      @change="$emit('input', $event)">
      <v-chip
        v-for="source in shownSources"
        :key="source"
        :value="source"
        filter
        small
        outlined>
        {{ $t(`emailConnector.contacts.source.${source}`) }}
      </v-chip>
    </v-chip-group>
  </div>
</template>

<script>
const SOURCES = ['collected', 'manual', 'addressBook'];

export default {
  props: {
    /** The selected sources, as REST filter values. */
    value: {
      type: Array,
      default: () => [],
    },
    /** How many contacts each source holds, keyed by REST filter value. */
    counts: {
      type: Object,
      default: () => ({}),
    },
  },
  computed: {
    /**
     * The chips worth offering: a source with no contact filters to an empty
     * list, which is a promise the bar should not make.
     *
     * @returns {Array} the source keys to render
     */
    shownSources() {
      return SOURCES.filter(source => (this.counts[source] || 0) > 0);
    },
    /**
     * Whether the bar earns its place. Below two sources it cannot: the single
     * chip would only ever select every row already on screen.
     *
     * @returns {boolean} true when at least two sources hold contacts
     */
    visible() {
      return this.shownSources.length > 1;
    },
  },
};
</script>
