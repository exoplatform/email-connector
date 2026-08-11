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
       directory-linked row only edits what the profile does not own, a CardDAV
       row edits through its server (and only while writing is available), and
       the delete tooltip says whether it removes the row or merely stops
       collecting the person. -->
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
        <!-- The three ways of taking this contact somewhere — mail, chat, QR —
             behind one overflow, because they are the same intent in three
             shapes and a header of five icons reads as five unrelated things.
             Edit and delete stay out here: they act on the contact rather than
             hand it over. -->
        <v-menu offset-y>
          <template #activator="{on, attrs}">
            <v-btn
              v-bind="attrs"
              :title="$t('emailConnector.contacts.detail.share')"
              icon
              v-on="on">
              <v-icon size="18">
                fas fa-ellipsis-v
              </v-icon>
            </v-btn>
          </template>
          <v-list class="pa-0" dense>
            <v-list-item @click="sendByEmail">
              <v-list-item-icon class="me-2 my-2">
                <v-icon size="16">fas fa-paper-plane</v-icon>
              </v-list-item-icon>
              <v-list-item-title>
                {{ $t('emailConnector.contacts.detail.sendByEmail') }}
              </v-list-item-title>
            </v-list-item>
            <v-list-item
              v-if="chatDeployed"
              @click="sendByChat">
              <v-list-item-icon class="me-2 my-2">
                <v-icon size="16">fas fa-comments</v-icon>
              </v-list-item-icon>
              <v-list-item-title>
                {{ $t('emailConnector.contacts.detail.sendByChat') }}
              </v-list-item-title>
            </v-list-item>
            <v-list-item @click="showQrCode">
              <v-list-item-icon class="me-2 my-2">
                <v-icon size="16">fas fa-qrcode</v-icon>
              </v-list-item-icon>
              <v-list-item-title>
                {{ $t('emailConnector.contacts.qr.open') }}
              </v-list-item-title>
            </v-list-item>
            <!-- Taking the contact to the user's own address book is the same
                 "hand this person somewhere" intent as mail, chat and QR, so it
                 lives with them. Shown only when publishing can work at all:
                 manual/collected rows, a bound and already-discovered book, and
                 the admin switch on - the server re-checks every one of these. -->
            <v-list-item
              v-if="publishable"
              :disabled="publishing"
              @click="publishContact">
              <v-list-item-icon class="me-2 my-2">
                <v-icon size="16">fas fa-address-book</v-icon>
              </v-list-item-icon>
              <v-list-item-title>
                {{ $t('emailConnector.contacts.detail.publish') }}
              </v-list-item-title>
            </v-list-item>
          </v-list>
        </v-menu>
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
      publishing: false,
      addressBookPublishable: false,
      contact: null,
    };
  },
  computed: {
    /**
     * Whether the chat is on this platform. Read once per card rather than per
     * render: the chat publishes its service when it boots, well before a
     * contact card can be opened.
     *
     * @returns {boolean} true when "Send by chat" applies
     */
    chatDeployed() {
      return this.$emailConnectorContactsService.isChatDeployed();
    },
    /**
     * Whether editing applies. A directory-linked row is editable for what the
     * profile does not own — their birthday, an address, a note about where you
     * met; the form greys out the identity the profile resolves on every read.
     * A CardDAV row is editable exactly when the address book is writable at
     * all (same stored-state answer the publish action uses): its edit is
     * pushed to the server before it is kept, so with writing off the edit
     * would only be refused, and a button that can only fail must not show.
     *
     * @returns {boolean} true when the edit icon shows
     */
    editable() {
      return ['MANUAL', 'COLLECTED', 'DIRECTORY'].includes(this.contact?.source)
        || (this.contact?.source === 'CARDDAV' && this.addressBookPublishable);
    },
    /**
     * Whether the publish action shows: the row must be the user's own to give
     * away (manual or collected - a directory colleague is the platform's, a
     * CardDAV row is already there), and the settings must say a discovered
     * address book is bound with publishing allowed.
     *
     * @returns {boolean} true when the publish item shows
     */
    publishable() {
      return this.addressBookPublishable
        && ['MANUAL', 'COLLECTED'].includes(this.contact?.source);
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
      // Answered from a page-lifetime cache after the first card, so opening a
      // card costs no extra request; hidden until known, which only errs by
      // briefly not offering the action.
      this.$emailConnectorContactsService.isAddressBookPublishable()
        .then(available => this.addressBookPublishable = available);
    },
    /**
     * Opens the composer with this contact's vCard already attached and nobody
     * addressed — sharing the person is this click, choosing who gets them is
     * the user's next one. The card stays open underneath, like under the QR.
     *
     * @returns {void}
     */
    sendByEmail() {
      this.$emailConnectorContactsService.sendByEmail(this.contact);
    },
    /**
     * Hands this contact's card to the chat, which asks the user which
     * conversation it goes into. The card stays open underneath: the pick
     * happens in the chat's own drawer.
     *
     * @returns {void}
     */
    sendByChat() {
      this.$emailConnectorContactsService.sendByChat(this.contact)
        .catch(() => document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: 'error', alertMessage: this.$t('emailConnector.contacts.detail.sendByChat.error')}})));
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
     * Publishes this contact into the user's own address book — creates only,
     * server-guarded, so nothing already on the server can be overwritten. On
     * success the card simply re-renders as an address-book contact: same
     * person, new source, read-only here from now on.
     *
     * @returns {void}
     */
    publishContact() {
      this.publishing = true;
      this.$emailConnectorContactsService.publishContact(this.contact.id)
        .then(published => {
          this.contact = published;
          this.$root.$emit('email-contacts-refresh');
          document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: 'success', alertMessage: this.$t('emailConnector.contacts.detail.publish.success')}}));
        })
        .catch(error => {
          // 409 is the server refusing to create over an existing entry - the
          // one refusal worth its own words, because the fix (sync, then look
          // again) is different from "try later".
          const key = error?.status === 409
            && 'emailConnector.contacts.detail.publish.exists'
            || 'emailConnector.contacts.detail.publish.error';
          document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: 'error', alertMessage: this.$t(key)}}));
        })
        .finally(() => this.publishing = false);
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
        .catch(() => document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: 'error', alertMessage: this.$t('emailConnector.contacts.delete.error')}})))
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
