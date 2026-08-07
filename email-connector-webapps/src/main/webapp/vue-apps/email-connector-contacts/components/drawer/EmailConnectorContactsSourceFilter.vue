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
  <!-- Where a contact came from, as toggles, one chip per stored source — plus
       the Favorites chip, which is not a source but a restriction: it narrows
       whatever the source chips selected to the starred rows. There is
       deliberately no "All" chip: nothing selected is what shows everything,
       the way the mailbox's category filter already behaves, and a chip selected
       by default teaches people the list is filtered when it is not. -->
  <div v-if="visible" class="d-flex px-4 pt-2">
    <v-chip-group
      :value="value"
      multiple
      column
      @change="$emit('input', $event)">
      <v-chip
        v-for="chip in chips"
        :key="chip.value"
        :value="chip.value"
        filter
        small
        outlined>
        {{ chip.label }}
      </v-chip>
    </v-chip-group>
  </div>
</template>

<script>
const SOURCES = ['collected', 'manual', 'addressBook'];
const FAVORITES = 'favorites';

export default {
  props: {
    /** The selected chips, as REST filter values ('favorites' included). */
    value: {
      type: Array,
      default: () => [],
    },
    /** How many contacts each chip selects, keyed by REST filter value. */
    counts: {
      type: Object,
      default: () => ({}),
    },
  },
  computed: {
    /**
     * The source chips worth offering: a source with no contact filters to an
     * empty list, which is a promise the bar should not make.
     *
     * @returns {Array} the source keys to render
     */
    shownSources() {
      return SOURCES.filter(source => (this.counts[source] || 0) > 0);
    },
    /**
     * How many contacts the user has starred.
     *
     * @returns {number} the favorite count
     */
    favoriteCount() {
      return this.counts[FAVORITES] || 0;
    },
    /**
     * Every chip to render, the Favorites one leading when it exists. Its label
     * lives under its own key rather than contacts.source.*, because a favorite
     * is not a place a contact came from.
     *
     * @returns {Array} the chips as {value, label}
     */
    chips() {
      const chips = this.shownSources.map(source => ({
        value: source,
        label: this.$t(`emailConnector.contacts.source.${source}`),
      }));
      if (this.favoriteCount > 0) {
        chips.unshift({
          value: FAVORITES,
          label: this.$t('emailConnector.contacts.filter.favorites'),
        });
      }
      return chips;
    },
    /**
     * Whether the bar earns its place. A single source chip cannot — it would
     * only ever select every row already on screen — but a lone Favorites chip
     * CAN: it genuinely filters, whatever the sources look like.
     *
     * @returns {boolean} true when the bar renders
     */
    visible() {
      return this.shownSources.length > 1 || this.favoriteCount > 0;
    },
  },
};
</script>
