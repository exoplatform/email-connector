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
     * Opens the contacts drawer — the quick action's landing point.
     *
     * @returns {void}
     */
    openDrawer() {
      this.$root.$emit('open-email-contacts-drawer');
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
