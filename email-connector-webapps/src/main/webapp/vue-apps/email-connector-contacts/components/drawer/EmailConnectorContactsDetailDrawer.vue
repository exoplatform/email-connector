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
       the way the mailbox opens its reader next to its list.

       The actions live in the drawer's header, where this drawer's siblings keep
       theirs (the mail reader does the same), rather than as buttons stranded under
       the card. What they may do is the contact's business, not the header's: a
       contact whose truth is a directory profile or a CardDAV server cannot be
       edited here, and the delete tooltip says whether it removes the row or merely
       stops collecting the person. -->
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
    <template #titleIcons>
      <div
        v-if="contact"
        class="d-flex align-center">
        <v-btn
          :title="$t('emailConnector.contacts.qr.open')"
          icon
          @click="showQrCode">
          <v-icon size="18">
            fas fa-qrcode
          </v-icon>
        </v-btn>
        <v-btn
          v-if="editable"
          :title="$t('emailConnector.contacts.detail.edit')"
          icon
          @click="editContact">
          <v-icon size="18">
            fas fa-edit
          </v-icon>
        </v-btn>
        <v-btn
          :title="deleteLabel"
          :loading="deleting"
          icon
          @click="removeContact">
          <v-icon size="18">
            fas fa-trash
          </v-icon>
        </v-btn>
      </div>
    </template>
    <template v-if="contact" #content>
      <email-connector-contacts-detail :contact="contact" />
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      detailDrawer: false,
      loading: false,
      deleting: false,
      contact: null,
    };
  },
  computed: {
    /**
     * Whether editing applies: manual and collected rows only. A CardDAV row is the
     * server's to edit, and a directory-linked row is the platform profile's.
     *
     * @returns {boolean} true when the edit icon shows
     */
    editable() {
      return this.contact?.source === 'MANUAL' || this.contact?.source === 'COLLECTED';
    },
    /**
     * The delete icon's honest tooltip. A manual or directory-linked contact deletes
     * for real, both existing by an explicit act; a collected one is only removed
     * from the list, because the next mail from that person would bring it straight
     * back.
     *
     * @returns {string} the localized tooltip
     */
    deleteLabel() {
      return (this.contact?.source === 'MANUAL' || this.contact?.source === 'DIRECTORY')
        && this.$t('emailConnector.contacts.detail.delete')
        || this.$t('emailConnector.contacts.detail.remove');
    },
  },
  created() {
    this.$root.$on('open-email-contact-detail', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-contact-detail', this.open);
  },
  methods: {
    /**
     * Opens the drawer on a contact. Stored rows are re-read so the card carries the
     * read-time enrichment (profile avatar and link, live directory data for
     * imported colleagues).
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
     * Opens the take-away QR on this contact — the dialog overlays the drawer,
     * which stays open underneath: scanning is a glance, not a navigation.
     *
     * @returns {void}
     */
    showQrCode() {
      this.$root.$emit('open-email-contact-qr', this.contact);
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
     * Deletes or suppresses the contact, per its source, and reports which of the two
     * happened so the app can offer undo after a suppression.
     *
     * @returns {void}
     */
    removeContact() {
      this.deleting = true;
      this.$emailConnectorContactsService.deleteContact(this.contact.id)
        .then(result => {
          if (result.suppressed) {
            this.$root.$emit('email-contact-suppressed', result.id);
          }
          this.$root.$emit('email-contacts-refresh');
          this.close();
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.contacts.delete.error'), 'error'))
        .finally(() => this.deleting = false);
    },
    /**
     * Closes and clears the drawer.
     *
     * @returns {void}
     */
    close() {
      this.detailDrawer = false;
      this.contact = null;
      this.deleting = false;
      this.$refs.emailContactDetailDrawer.close();
    },
  },
};
</script>
