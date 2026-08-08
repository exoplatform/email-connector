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
  <v-app
    role="main"
    id="emailConnectorContacts">
    <email-connector-contacts-drawer />
    <email-connector-contacts-detail-drawer />
    <email-connector-contact-form-drawer />
    <email-connector-contact-qr-dialog />
    <!-- The undo toast after a suppression: the one mis-click recovery the
         tombstone model needs, instead of a trash UI. -->
    <v-snackbar
      v-model="undoSnackbar"
      timeout="6000"
      bottom>
      {{ $t('emailConnector.contacts.suppressed.toast') }}
      <template #action="{ attrs }">
        <v-btn
          v-bind="attrs"
          text
          color="primary"
          @click="undoSuppression">
          {{ $t('emailConnector.contacts.suppressed.undo') }}
        </v-btn>
      </template>
    </v-snackbar>
  </v-app>
</template>

<script>
export default {
  data() {
    return {
      undoSnackbar: false,
      suppressedContactId: null,
    };
  },
  created() {
    this.$root.$on('email-contact-suppressed', this.onContactSuppressed);
  },
  mounted() {
    document.addEventListener('quick-action-contacts-drawer', this.openDrawer);
  },
  beforeDestroy() {
    document.removeEventListener('quick-action-contacts-drawer', this.openDrawer);
    this.$root.$off('email-contact-suppressed', this.onContactSuppressed);
  },
  methods: {
    /**
     * Opens the contacts drawer — the quick action's landing point. The event
     * may name a contact to land on (the global Favorites drawer sends its
     * {contactId} this way), and the drawer opens on its card.
     *
     * @param {CustomEvent} event - optionally carrying {contactId} in its detail
     * @returns {void}
     */
    openDrawer(event) {
      const opening = event?.detail || {};
      // Somebody who asked for ONE person gets that person, not the whole store
      // with their card stacked on top of it. Opening the list first was how this
      // was wired, and it read as two drawers deep for a single click on a name.
      if (opening.contactId) {
        this.$root.$emit('open-email-contact-detail', {id: opening.contactId});
      } else if (opening.address) {
        this.openOnAddress(opening.address, opening.name);
      } else {
        this.$root.$emit('open-email-contacts-drawer', opening);
      }
    },
    /**
     * Opens whoever is reachable at an address — the mailbox's way in, where all
     * it has is what a mail header said.
     * <p>
     * An address nobody is stored at opens the form instead, carrying the name
     * and address the mail wrote, so adding a correspondent is a confirmation
     * rather than retyping what is already on screen.
     *
     * @param {string} address - the address from the mail
     * @param {string} name - the display name the mail carried, may be empty
     * @returns {void}
     */
    openOnAddress(address, name) {
      this.$emailConnectorContactsService.getContactByAddress(address)
        .then(contact => {
          if (contact) {
            this.$root.$emit('open-email-contact-detail', contact);
          } else {
            this.$root.$emit('open-email-contact-form', this.prefillFrom(address, name));
          }
        })
        .catch(() => this.$root.$emit('open-email-contact-form', this.prefillFrom(address, name)));
    },
    /**
     * The form fields a mail header can fill in on its own.
     * <p>
     * Splitting a display name into given and family is a guess — plenty of
     * senders write "DOE Jane" — which is exactly why an unknown address opens a
     * form to confirm rather than creating the contact silently.
     *
     * @param {string} address - the address from the mail
     * @param {string} name - the display name the mail carried, may be empty
     * @returns {object} what to prefill
     */
    prefillFrom(address, name) {
      const parts = (name || '').trim().split(/\s+/).filter(Boolean);
      return {
        primaryEmail: address,
        givenName: parts[0] || '',
        familyName: parts.slice(1).join(' '),
      };
    },
    /**
     * Shows the undo toast for the contact that was just suppressed.
     *
     * @param {number} contactId - the suppressed contact's id
     * @returns {void}
     */
    onContactSuppressed(contactId) {
      this.suppressedContactId = contactId;
      this.undoSnackbar = true;
    },
    /**
     * Un-suppresses the last removed contact and refreshes the list.
     *
     * @returns {void}
     */
    undoSuppression() {
      if (!this.suppressedContactId) {
        return;
      }
      this.$emailConnectorContactsService.restoreContact(this.suppressedContactId)
        .then(() => this.$root.$emit('email-contacts-refresh'))
        .finally(() => {
          this.undoSnackbar = false;
          this.suppressedContactId = null;
        });
    },
  }
};
</script>
