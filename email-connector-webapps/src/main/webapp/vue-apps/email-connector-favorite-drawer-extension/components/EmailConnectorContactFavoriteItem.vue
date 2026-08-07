<!--
 * Copyright (C) 2025 eXo Platform SAS.
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
  <v-list-item
    v-if="contact"
    @keydown.enter="open"
    @click="open">
    <v-list-item-icon class="me-3 my-auto">
      <v-card
        :min-width="iconWidth"
        class="d-flex justify-center no-border-radius"
        color="transparent"
        flat>
        <v-icon :size="iconSize" color="primary">fa-address-book</v-icon>
      </v-card>
    </v-list-item-icon>
    <v-list-item-content>
      <v-list-item-title class="text-truncate">{{ displayedName }}</v-list-item-title>
      <v-list-item-subtitle v-if="expanded && secondLine" class="text-truncate pt-2px">
        {{ secondLine }}
      </v-list-item-subtitle>
    </v-list-item-content>
  </v-list-item>
</template>
<script>
export default {
  props: {
    id: {
      type: String,
      default: () => null,
    },
    clickCallback: {
      type: Function,
      default: null,
    },
    expanded: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    contact: null,
  }),
  computed: {
    iconWidth() {
      return this.expanded ? 40 : 30;
    },
    iconSize() {
      return this.expanded ? 24 : 18;
    },
    /**
     * The line the row leads with: the name, falling back to the address.
     *
     * @returns {string} the displayed name
     */
    displayedName() {
      return this.contact?.displayName || this.contact?.primaryEmail || '';
    },
    /**
     * The expanded row's second line: the address when the first line is a
     * name, else the organization.
     *
     * @returns {string} the sub-line
     */
    secondLine() {
      if (this.contact?.displayName && this.contact?.primaryEmail) {
        return this.contact.primaryEmail;
      }
      return this.contact?.organization || '';
    },
  },
  created() {
    // Favorites are keyed by the contact's row id, which is exactly what the
    // contacts REST addresses rows by — one read, enriched server-side.
    fetch(`/email-connector/rest/contacts/${this.id}`, {
      method: 'GET',
      credentials: 'include',
    }).then(response => {
      if (!response?.ok) {
        throw new Error('Favorited contact cannot be read');
      }
      return response.json();
    }).then(contact => this.contact = contact)
      .catch(() => {
        // The contact is gone from the store (deleted, suppressed, or swept by an
        // address book sync between two cleanups). Telling the drawer drops the
        // entry instead of leaving a row that opens onto nothing.
        this.$root.$emit('favorite-removed', 'contact', this.id);
      });
  },
  methods: {
    open(event) {
      if (event?.which === 1 || event?.which === 2) {
        this.clickCallback?.('contact', this.id);
      }
      // The contacts app owns the card, so it is asked to open its drawer on this
      // contact rather than this drawer rendering a second, lesser copy of it.
      window.require(['SHARED/emailConnectorContactsQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-contacts-drawer', {
          detail: {contactId: this.id},
        })));
    },
  },
};
</script>
