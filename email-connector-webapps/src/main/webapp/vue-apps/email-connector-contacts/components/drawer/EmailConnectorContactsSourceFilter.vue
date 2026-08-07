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
  <!-- Where a contact came from, as toggles. There is deliberately no "All"
       chip: nothing selected is what shows everything, the way the mailbox's
       category filter already behaves — and a chip that is selected by default
       teaches people the list is filtered when it is not. -->
  <div v-if="addressBookAvailable" class="d-flex px-4 pt-2">
    <v-chip-group
      :value="value"
      multiple
      column
      @change="$emit('input', $event)">
      <v-chip
        value="collected"
        filter
        small
        outlined>
        {{ $t('emailConnector.contacts.source.collected') }}
      </v-chip>
      <v-chip
        value="addressBook"
        filter
        small
        outlined>
        {{ $t('emailConnector.contacts.source.addressBook') }}
      </v-chip>
    </v-chip-group>
  </div>
</template>

<script>
export default {
  props: {
    /** The selected sources, as REST filter values. */
    value: {
      type: Array,
      default: () => [],
    },
    /**
     * Whether an address book holds anything for this user. The whole bar hides
     * when it does not: with one source in play, "Collected" alone is a chip
     * that can only ever select every row already on screen.
     */
    addressBookAvailable: {
      type: Boolean,
      default: false,
    },
  },
};
</script>
