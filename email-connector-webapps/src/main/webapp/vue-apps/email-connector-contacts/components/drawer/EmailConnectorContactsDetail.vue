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
  <!-- The contact card, used inline (expanded two-pane) and inside the detail
       drawer alike. Every address row is a "compose to" affordance; edit and
       delete follow the source rules. A directory-linked contact shows the
       live profile — current name, avatar, address — resolved server-side. -->
  <div class="pa-4 d-flex flex-column">
    <div class="d-flex flex-column align-center mb-4">
      <v-avatar
        size="64"
        :color="contact.avatarUrl ? '' : 'primary'"
        class="mb-2">
        <v-img
          v-if="contact.avatarUrl"
          :src="contact.avatarUrl" />
        <span
          v-else
          class="white--text text-h6">{{ initials }}</span>
      </v-avatar>
      <div class="text-h6 text-color text-center">
        {{ contact.displayName || contact.primaryEmail }}
      </div>
      <div
        v-if="subtitle"
        class="text-sub-title text-center">
        {{ subtitle }}
      </div>
      <a
        v-if="contact.profileUrl"
        :href="contact.profileUrl"
        class="text-caption mt-1">
        {{ $t('emailConnector.contacts.detail.profile') }}
      </a>
      <v-chip
        x-small
        outlined
        class="mt-2">
        {{ sourceLabel }}
      </v-chip>
    </div>
    <v-divider class="mb-2" />
    <v-list dense>
      <v-list-item
        v-for="address in addresses"
        :key="address"
        class="px-0"
        @click="composeTo(address)">
        <v-list-item-icon class="me-3 my-auto">
          <v-icon size="16">
            fas fa-envelope
          </v-icon>
        </v-list-item-icon>
        <v-list-item-content>
          <v-list-item-title>{{ address }}</v-list-item-title>
          <v-list-item-subtitle class="text-sub-title">
            {{ $t('emailConnector.contacts.detail.composeTo') }}
          </v-list-item-subtitle>
        </v-list-item-content>
      </v-list-item>
      <v-list-item
        v-for="phone in contact.phones || []"
        :key="phone"
        class="px-0">
        <v-list-item-icon class="me-3 my-auto">
          <v-icon size="16">
            fas fa-phone
          </v-icon>
        </v-list-item-icon>
        <v-list-item-content>
          <v-list-item-title>{{ phone }}</v-list-item-title>
        </v-list-item-content>
      </v-list-item>
    </v-list>
  </div>
</template>

<script>
export default {
  props: {
    // The contact to show, already resolved server-side for display.
    contact: {
      type: Object,
      required: true,
    },
  },
  data() {
    return {
    };
  },
  computed: {
    /**
     * The line under the name: organization/title, when known.
     *
     * @returns {string} the subtitle
     */
    subtitle() {
      return [this.contact.title, this.contact.organization].filter(part => part).join(' - ');
    },
    /**
     * The initials of the fallback avatar.
     *
     * @returns {string} up to two uppercased initials
     */
    initials() {
      return (this.contact.displayName || this.contact.primaryEmail || '?').split(/\s+/)
        .filter(word => word)
        .map(word => word.charAt(0).toUpperCase())
        .slice(0, 2)
        .join('');
    },
    /**
     * Every address of the contact, primary first.
     *
     * @returns {Array} the addresses
     */
    addresses() {
      return [this.contact.primaryEmail].concat(this.contact.secondaryEmails || []).filter(address => address);
    },
    /**
     * The localized source of this row.
     *
     * @returns {string} the source label
     */
    sourceLabel() {
      if (this.contact.source === 'MANUAL') {
        return this.$t('emailConnector.contacts.source.manual');
      }
      if (this.contact.source === 'DIRECTORY') {
        return this.$t('emailConnector.contacts.source.directory');
      }
      if (this.contact.source === 'CARDDAV') {
        return this.$t('emailConnector.contacts.source.addressBook');
      }
      return this.$t('emailConnector.contacts.source.collected');
    },
  },
  methods: {
    /**
     * Hands one address to the mail app's composer (mounted on demand).
     *
     * @param {string} address - the address to write to
     * @returns {void}
     */
    composeTo(address) {
      this.$emailConnectorContactsService.composeTo({
        name: this.contact.displayName || '',
        address,
      });
    },
  },
};
</script>
