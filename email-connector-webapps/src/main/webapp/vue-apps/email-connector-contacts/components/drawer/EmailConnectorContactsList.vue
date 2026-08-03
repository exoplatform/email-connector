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
  <!-- The alphabetical list: letter buckets under sticky headers, the A–Z rail
       floating at the end edge when it earns its pixels. The grouping key is
       the server-computed letter, so the client groups exactly the way the
       server orders. Inline styles, not a <style> block: this webapp's
       webpack has no CSS loader. -->
  <div
    v-if="groups.length"
    class="d-flex flex-row"
    style="position: relative;">
    <v-list
      dense
      class="pt-0 flex-grow-1"
      :style="railVisible ? 'padding-inline-end: 18px;' : ''">
      <template v-for="group in groups">
        <div
          :key="`header-${group.letter}`"
          :id="`contact-letter-${group.letter}`"
          class="px-4 py-1 text-caption font-weight-bold primary--text"
          style="position: sticky; top: 0; z-index: 2; background: var(--allPagesBaseBackground, #fff);">
          {{ group.letter }}
        </div>
        <email-connector-contacts-list-item
          v-for="contact in group.contacts"
          :key="`${group.letter}-${contact.id || contact.primaryEmail}`"
          :contact="contact"
          :selected="!!selectedId && contact.id === selectedId"
          @select="$emit('select', contact)" />
      </template>
    </v-list>
    <email-connector-contacts-alphabet-rail
      v-if="railVisible"
      :letter-index="letterIndex"
      @jump="jumpToLetter" />
  </div>
  <div
    v-else
    class="d-flex flex-column align-center justify-center text-sub-title py-10">
    <v-icon
      size="42"
      class="text-sub-title mb-4">
      far fa-address-book
    </v-icon>
    <span class="px-6 text-center">{{ emptyLabel }}</span>
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
     * The letter buckets, in list order. Colleagues (no letter) fall into one
     * unlettered group rendered without a header.
     *
     * @returns {Array} groups of {letter, contacts}
     */
    groups() {
      const groups = [];
      let current = null;
      for (const contact of this.contacts) {
        const letter = contact.letter || '';
        if (!current || current.letter !== letter) {
          current = {letter, contacts: []};
          groups.push(current);
        }
        current.contacts.push(contact);
      }
      return groups.filter(group => group.contacts.length);
    },
  },
  methods: {
    /**
     * Scrolls the list to a letter's bucket header. Every page is loaded, so
     * the header always exists when the rail says the letter does.
     *
     * @param {string} letter - the rail letter
     * @returns {void}
     */
    jumpToLetter(letter) {
      document.getElementById(`contact-letter-${letter}`)?.scrollIntoView({block: 'start'});
    },
  },
};
</script>
