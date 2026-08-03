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
  <!-- The contact card as a sibling drawer — the non-expanded reading path,
       the way the mailbox opens its reader next to its list. -->
  <exo-drawer
    id="emailContactDetailDrawer"
    ref="emailContactDetailDrawer"
    v-model="detailDrawer"
    right
    go-back-button
    :loading="loading"
    @closed="close">
    <template #title>
      <span>{{ $t('emailConnector.contacts.detail.title') }}</span>
    </template>
    <template v-if="contact" #content>
      <email-connector-contacts-detail
        :contact="contact"
        @edit="editContact"
        @removed="close" />
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      detailDrawer: false,
      loading: false,
      contact: null,
    };
  },
  created() {
    this.$root.$on('open-email-contact-detail', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-contact-detail', this.open);
  },
  methods: {
    /**
     * Opens the drawer on a contact. Stored rows are re-read so the card
     * carries the read-time profile enrichment; colleagues show as the
     * directory handed them over.
     *
     * @param {object} contact - the selected row
     * @returns {void}
     */
    open(contact) {
      this.contact = contact;
      this.detailDrawer = true;
      this.$refs.emailContactDetailDrawer.open();
      if (contact?.id) {
        this.loading = true;
        this.$emailConnectorContactsService.getContact(contact.id)
          .then(full => this.contact = full)
          .catch(() => null)
          .finally(() => this.loading = false);
      }
    },
    /**
     * Hands the contact to the edit form and closes the card under it.
     *
     * @returns {void}
     */
    editContact() {
      this.$root.$emit('open-email-contact-form', this.contact);
      this.close();
    },
    /**
     * Closes and clears the drawer.
     *
     * @returns {void}
     */
    close() {
      this.detailDrawer = false;
      this.contact = null;
      this.$refs.emailContactDetailDrawer.close();
    },
  },
};
</script>
