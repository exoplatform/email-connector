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
      <div class="d-flex align-center">
        <div class="text-h6 text-color text-center">
          {{ contact.displayName || contact.primaryEmail }}
        </div>
        <!-- The platform's shared star, so a contact is favorited exactly the way
             a document or a task is. A read-only CardDAV or directory row CAN be
             starred: the favorite writes nothing to the row, it lives in the
             platform's favorites store keyed by the row's id. -->
        <favorite-button
          v-if="contact.id"
          :id="String(contact.id)"
          :favorite="contact.favorite"
          :type-label="$t('emailConnector.contacts.detail.title')"
          type="contact"
          class="ms-1" />
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
          <!-- The main address wears a quiet caption rather than a section of
               its own: it is what the list, the search and the compose
               autocomplete file this person under, and only worth saying when
               there are several addresses to tell apart. -->
          <v-list-item-title>
            {{ address }}
            <span
              v-if="isPrimaryAddress(address)"
              class="text-caption text-sub-title">
              · {{ $t('emailConnector.contacts.detail.primary') }}
            </span>
          </v-list-item-title>
          <v-list-item-subtitle class="text-sub-title">
            {{ $t('emailConnector.contacts.detail.composeTo') }}
          </v-list-item-subtitle>
        </v-list-item-content>
      </v-list-item>
      <!-- Keyed by index, not by entry: a stored list may legitimately hold
           the same text twice mid-edit, and the index is stable enough for a
           list that only ever redraws whole. -->
      <v-list-item
        v-for="(phone, index) in contact.phones || []"
        :key="`phone-${index}`"
        class="px-0">
        <v-list-item-icon class="me-3 my-auto">
          <v-icon size="16">
            fas fa-phone
          </v-icon>
        </v-list-item-icon>
        <!-- The number reads as the platform's own does: v-autolinker with the
             call argument is the very directive the profile's contact information
             uses, so a future fix there arrives here for free. It sets innerHTML,
             which is why it sits on a leaf with nothing inside it — the type
             label lives in a sibling span, the way the primary address marks
             itself above. -->
        <v-list-item-content>
          <v-list-item-title>
            <span v-autolinker:call="phoneValue(phone)"></span>
            <span
              v-if="phoneTypeLabel(phone)"
              class="text-caption text-sub-title">
              · {{ phoneTypeLabel(phone) }}
            </span>
          </v-list-item-title>
        </v-list-item-content>
        <!-- And the action button, as the user card renders it. It is not
             decoration: a profile phone is validated to digits, so the platform's
             linker never meets a bad one, while a vCard number can carry spaces,
             parentheses or an extension -- which that linker splits in two or clips.
             This button always dials one number, and dials the right one. -->
        <v-list-item-action class="my-auto">
          <v-btn
            :href="`tel:${dialable(phone)}`"
            :aria-label="$t('emailConnector.contacts.detail.launchCall')"
            :title="$t('emailConnector.contacts.detail.launchCall')"
            icon>
            <v-icon size="18">
              fas fa-phone
            </v-icon>
          </v-btn>
        </v-list-item-action>
      </v-list-item>
      <v-list-item
        v-if="formattedBirthday"
        class="px-0">
        <v-list-item-icon class="me-3 my-auto">
          <v-icon size="16">
            fas fa-birthday-cake
          </v-icon>
        </v-list-item-icon>
        <v-list-item-content>
          <v-list-item-title>{{ formattedBirthday }}</v-list-item-title>
          <v-list-item-subtitle class="text-sub-title">
            {{ $t('emailConnector.contacts.detail.birthday') }}
          </v-list-item-subtitle>
        </v-list-item-content>
      </v-list-item>
      <v-list-item
        v-if="addressLines.length"
        class="px-0">
        <v-list-item-icon class="me-3 my-auto">
          <v-icon size="16">
            fas fa-map-marker-alt
          </v-icon>
        </v-list-item-icon>
        <!-- One div per line rather than a styled pre: the card may not carry
             <style> blocks, and the lines are already structured data. -->
        <v-list-item-content>
          <div
            v-for="line in addressLines"
            :key="line"
            class="text-color">
            {{ line }}
          </div>
        </v-list-item-content>
      </v-list-item>
      <v-list-item
        v-if="contact.website"
        class="px-0"
        :href="websiteHref"
        target="_blank"
        rel="noopener noreferrer">
        <v-list-item-icon class="me-3 my-auto">
          <v-icon size="16">
            fas fa-globe
          </v-icon>
        </v-list-item-icon>
        <v-list-item-content>
          <v-list-item-title class="primary--text">
            {{ contact.website }}
          </v-list-item-title>
        </v-list-item-content>
      </v-list-item>
      <v-list-item
        v-if="noteHtml"
        class="px-0">
        <v-list-item-icon class="me-3 my-auto">
          <v-icon size="16">
            fas fa-sticky-note
          </v-icon>
        </v-list-item-icon>
        <!-- Through the platform sanitizer: a note is written in the same rich
             editor as a mail, and one that arrived from a provider's address
             book is somebody else's text. -->
        <v-list-item-content>
          <div
            class="text-color text-break"
            v-sanitized-html="noteHtml"></div>
        </v-list-item-content>
      </v-list-item>
    </v-list>
    <!-- The recent mail exchanged with this person — the way back from the card
         to the mailbox, closing the loop the sender-click opened. Orthogonal to
         where the contact came from: an address-book row corresponds like any
         other. It loads only when unfolded, so the card itself stays as cheap
         as it was. -->
    <email-connector-contacts-correspondence :contact="contact" />
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
     * The birthday as the viewer's locale writes it. The store's canonical
     * forms are YYYY-MM-DD and --MM-DD; a year-less one is shown as month and
     * day only — never with a year the person did not give.
     *
     * @returns {string} the localized birthday, or empty when there is none
     */
    formattedBirthday() {
      const birthday = this.contact.birthday || '';
      const lang = eXo?.env?.portal?.language || 'en';
      try {
        if (birthday.startsWith('--')) {
          const [month, day] = birthday.substring(2).split('-').map(Number);
          // Any leap year does as the carrier of a year-less month+day.
          return new Intl.DateTimeFormat(lang, {month: 'long', day: 'numeric'}).format(new Date(2000, month - 1, day));
        }
        if (birthday) {
          const [year, month, day] = birthday.split('-').map(Number);
          return new Intl.DateTimeFormat(lang, {year: 'numeric', month: 'long', day: 'numeric'})
            .format(new Date(year, month - 1, day));
        }
      } catch (e) {
        // A value the formatter chokes on is still worth showing as itself.
        return birthday;
      }
      return '';
    },
    /**
     * The postal address as postal mail writes it: street, then locality line,
     * then country — built from the structured components, which is the whole
     * point of storing them apart.
     *
     * @returns {Array} the non-empty lines
     */
    addressLines() {
      const address = this.contact.postalAddress;
      if (!address) {
        return [];
      }
      const localityLine = [address.postalCode, address.city, address.region].filter(part => part).join(' ');
      return [address.street, localityLine, address.country].filter(line => line);
    },
    /**
     * The note, split on its line breaks so each paragraph renders as its own
     * block without any custom CSS.
     *
     * @returns {string} the note markup, empty when there is no note
     */
    noteHtml() {
      return this.contact.note || '';
    },
    /**
     * The website as a clickable target: a bare "janedoe.example" is given a
     * scheme, because href without one resolves inside the platform.
     *
     * @returns {string} the absolute URL
     */
    websiteHref() {
      const website = this.contact.website || '';
      return /^[a-z][a-z0-9+.-]*:/i.test(website) ? website : `https://${website}`;
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
     * Whether an address row deserves the main-address caption: it is the
     * primary AND the contact has other addresses to tell it apart from — on a
     * one-address card the caption would only be noise.
     *
     * @param {string} address - the row's address
     * @returns {boolean} true when the caption shows
     */
    isPrimaryAddress(address) {
      return this.addresses.length > 1 && address === this.contact.primaryEmail;
    },
    /**
     * The number as a phone can dial it: everything a tel: URI has no use for is
     * dropped, digits and the few meaningful symbols kept.
     *
     * The platform does not do this, and is right not to — it validates profile
     * numbers to digits, so it never meets anything else. Contacts have no such
     * luxury: a vCard carries whatever somebody typed, and "(01) 23 45 67" run
     * through the platform's linker dials "01) 23 45 67". The number is still
     * DISPLAYED exactly as it was written, because that is how its owner wrote it;
     * only what gets dialled is cleaned.
     *
     * @param {string} phone - the entry as the contact holds it
     * @returns {string} the number a dialler can use
     */
    dialable(phone) {
      // The type prefix comes off first: "work" contains no digits, but going
      // through phoneValue keeps the dial string honest whatever the entry.
      return this.phoneValue(phone).replace(/[^\d+*#]/g, '');
    },
    /**
     * The number of a stored phone entry, without its type prefix. The store
     * encodes a typed number as `type,value`; the prefix only counts as a type
     * when it is in the vCard vocabulary the backend names, so a bare legacy
     * number — or one containing commas of its own — reads whole.
     *
     * @param {string} phone - the entry as the contact holds it
     * @returns {string} the number as its owner wrote it
     */
    phoneValue(phone) {
      const text = phone || '';
      const comma = text.indexOf(',');
      const prefix = comma > 0 ? text.slice(0, comma).trim().toLowerCase() : '';
      return ['cell', 'work', 'home', 'fax', 'pager'].includes(prefix) ? text.slice(comma + 1).trim() : text.trim();
    },
    /**
     * The localized label of an entry's type, or nothing for a bare number —
     * an untyped number gets no caption rather than an invented one.
     *
     * @param {string} phone - the entry as the contact holds it
     * @returns {string} the label, or an empty string
     */
    phoneTypeLabel(phone) {
      const text = phone || '';
      const comma = text.indexOf(',');
      const prefix = comma > 0 ? text.slice(0, comma).trim().toLowerCase() : '';
      return ['cell', 'work', 'home', 'fax', 'pager'].includes(prefix)
        ? this.$t(`emailConnector.contacts.phoneType.${prefix}`) : '';
    },
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
