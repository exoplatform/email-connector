<!--
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
-->
<template>
  <v-hover>
    <template #default="{ hover }">
      <!-- The People card's exact footprint (350x80, outlined, lifted on
           hover), so the two person carousels read as one family. -->
      <v-card
        :elevation="hover ? 3 : 0"
        class="border-box-sizing fill-height clickable"
        width="350"
        height="80"
        min-height="80"
        max-height="80"
        outlined
        flat
        rounded
        @click="open"
        @keydown.enter="open">
        <div class="d-flex flex-row fill-height align-center px-3">
          <v-avatar size="48" class="me-3 flex-shrink-0">
            <v-img
              v-if="contact.avatarUrl"
              :src="contact.avatarUrl" />
            <v-avatar
              v-else
              :color="initialsColor"
              size="48">
              <span class="white--text text-subtitle-2">{{ initials }}</span>
            </v-avatar>
          </v-avatar>
          <!-- Start-aligned explicitly: the carousel's card styling centres text,
               which left every card's block beginning at a different place and
               made a three-line card look misaligned beside a two-line one.
               Growing to fill the card is what lets the long addresses truncate
               rather than decide the width. -->
          <div
            class="d-flex flex-column align-start text-start overflow-hidden flex-grow-1"
            style="min-width: 0">
            <span class="text-truncate font-weight-bold text-color">
              {{ displayedName }}
            </span>
            <span
              v-if="organizationLine"
              class="text-truncate text-caption text-sub-title">
              {{ organizationLine }}
            </span>
            <span class="text-truncate text-caption text-sub-title">
              {{ contact.primaryEmail }}
            </span>
          </div>
        </div>
      </v-card>
    </template>
  </v-hover>
</template>

<script>
// The platform's avatar accents, hashed from the name so a contact keeps its
// color across renders — the contacts drawer's own initials scheme, so the
// same person looks the same here and there.
const INITIALS_COLORS = ['#ef5350', '#1976d2', '#ab47bc', '#00897b', '#9e9d24', '#fbc02d',
  '#00bfa5', '#757575', '#f44336', '#2196f3', '#7cb342', '#303f9f', '#4527a0', '#8d6e63', '#ff6f00'];

export default {
  props: {
    // The DOM id the search page mounts this card into.
    id: {
      type: String,
      default: () => null,
    },
    // The contact row, as formatSearchResult shaped it.
    result: {
      type: Object,
      default: () => null,
    },
    // What was searched for; unused here, part of the uiComponent contract.
    term: {
      type: String,
      default: () => null,
    },
  },
  computed: {
    /**
     * The rendered contact, never null so the template stays unguarded.
     *
     * @returns {Object} the contact
     */
    contact() {
      return this.result || {};
    },
    /**
     * The line the card leads with: the name, falling back to the address.
     *
     * @returns {string} the displayed name
     */
    displayedName() {
      return this.contact.displayName || this.contact.primaryEmail || '';
    },
    /**
     * The organisation line: organisation and title when both exist, either
     * alone otherwise, empty (and not rendered) when the contact has neither.
     *
     * @returns {string} the organisation line
     */
    organizationLine() {
      return [this.contact.organization, this.contact.title]
        .filter(part => part)
        .join(' — ');
    },
    /**
     * The initials shown when no avatar exists.
     *
     * @returns {string} up to two uppercased initials
     */
    initials() {
      return this.displayedName.split(/\s+/)
        .filter(word => word)
        .map(word => word.charAt(0).toUpperCase())
        .slice(0, 2)
        .join('') || '?';
    },
    /**
     * A stable accent color for the initials avatar, hashed from the name.
     *
     * @returns {string} a hex color
     */
    initialsColor() {
      return INITIALS_COLORS[this.displayedName.length % INITIALS_COLORS.length];
    },
  },
  methods: {
    /**
     * Opens the contact's card. There is no contact page: the contacts app
     * owns the card, mounts itself on demand, and is asked to open through the
     * same document event the global Favorites drawer uses — requiring its
     * extension module first is what makes this work from a page where the
     * contacts app was never loaded.
     *
     * @returns {void}
     */
    open() {
      window.require(['SHARED/emailConnectorContactsQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-contacts-drawer', {
          detail: {contactId: this.contact.id},
        })));
    },
  },
};
</script>
