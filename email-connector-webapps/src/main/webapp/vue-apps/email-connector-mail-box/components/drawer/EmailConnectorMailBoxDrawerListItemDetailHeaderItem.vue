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
  <v-list-item
    class="height-auto px-0 align-start">
    <v-list-item-action class="my-0 align-self-start me-2" :style="{ width: labelWidth + 'px' }">
      <v-list-item-subtitle class="text-color">
        {{ label }}
      </v-list-item-subtitle>
    </v-list-item-action>
    <v-list-item-content class="py-0">
      <v-list-item-subtitle
        v-for="(value, index) in values"
        :key="value.address"
        :class="{'mb-2': index !== values.length - 1}"
        class="d-flex align-center">
        <!-- Truncated rather than wrapped or overflowing: a long address must not
             push the card button out of the row, which is exactly what a button
             placed after free-flowing text does. -->
        <span
          class="text-truncate"
          style="min-width: 0"
          v-html="parsedValue(value)"></span>
        <!-- The way from a mail to the person who sent it. Added beside the
             existing name rather than replacing it: a colleague's name already
             links to their profile, and taking that away to gain a contact card
             would cost more than it gives.
             An icon, not a button: a button's padding makes the line taller than
             the label beside it, and the two stop lining up.
             Offered only where there is no platform profile behind the address:
             for a colleague the profile IS the card, their name already links to
             it, and inviting the user to file a person the platform already
             knows would duplicate them for nothing. -->
        <v-icon
          v-if="!value.profileUrl"
          :title="$t('emailConnector.mailBox.list.drawer.detail.openContact')"
          size="14"
          class="ms-2 flex-shrink-0"
          @click="openContact(value)">
          fas fa-address-card
        </v-icon>
      </v-list-item-subtitle>
    </v-list-item-content>
  </v-list-item>
</template>

<script>
export default {
  props: {
    label: {
      type: String,
      default: null,
    },
    values: {
      type: Array,
      default: () => [],
    },
    labelWidth: {
      type: Number,
      default: null,
    },
  },
  methods: {
    /**
     * Opens the contact card for one of the addresses on this mail, or the
     * create form when nobody is stored there yet.
     * <p>
     * Dispatched rather than called: the contacts app mounts itself on demand
     * and may not exist on the page yet, which is what this document event is
     * for. Whether the address is known is the contacts app's question to
     * answer, not the mailbox's.
     *
     * @param {object} value - the address entry, carrying address and name
     * @returns {void}
     */
    openContact(value) {
      document.dispatchEvent(new CustomEvent('open-contacts-drawer', {
        detail: {
          address: value.address,
          name: value.name,
        },
      }));
    },
    parsedValue(value) {
      return value.profileUrl && `<a href="${value.profileUrl}" target="_blank" rel="noopener noreferrer">${value.name}</a> ${value.address}` || `<span class="text-color">${value.name}</span> ${value.address}`;
    },
  },
};
</script>