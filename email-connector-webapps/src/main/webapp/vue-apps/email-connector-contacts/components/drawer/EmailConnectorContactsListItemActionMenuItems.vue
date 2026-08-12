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
  <!-- The entries of a row's own menu, in the order the card taught: writing to
       the person first, then the three ways of handing them over, then the two
       that act on the row itself. Every one of them is the card's action,
       through the card's handler and the card's words — the row saves the
       detour, it does not invent a second vocabulary. -->
  <v-list class="pa-0" dense>
    <v-list-item
      v-if="contact.primaryEmail"
      @click.stop="composeTo">
      <v-list-item-icon class="me-2 my-2">
        <v-icon size="16">fas fa-envelope</v-icon>
      </v-list-item-icon>
      <v-list-item-title>
        {{ $t('emailConnector.contacts.detail.composeTo') }}
      </v-list-item-title>
    </v-list-item>
    <!-- Everything below hands over or changes the stored row, so all of it
         needs the contact to BE stored. A row without an id is a person the
         list can show and write to, and nothing else. -->
    <template v-if="contact.id">
      <v-list-item @click.stop="sendByEmail">
        <v-list-item-icon class="me-2 my-2">
          <v-icon size="16">fas fa-paper-plane</v-icon>
        </v-list-item-icon>
        <v-list-item-title>
          {{ $t('emailConnector.contacts.detail.sendByEmail') }}
        </v-list-item-title>
      </v-list-item>
      <v-list-item
        v-if="chatDeployed"
        @click.stop="sendByChat">
        <v-list-item-icon class="me-2 my-2">
          <v-icon size="16">fas fa-comments</v-icon>
        </v-list-item-icon>
        <v-list-item-title>
          {{ $t('emailConnector.contacts.detail.sendByChat') }}
        </v-list-item-title>
      </v-list-item>
      <!-- Shown only when publishing can work at all: a contact the user added
           themselves, a bound and already-discovered address book, and the
           admin switch on — the server re-checks every one of these. -->
      <v-list-item
        v-if="publishable"
        :disabled="publishing"
        @click.stop="publishContact">
        <v-list-item-icon class="me-2 my-2">
          <v-icon size="16">fas fa-address-book</v-icon>
        </v-list-item-icon>
        <v-list-item-title>
          {{ $t('emailConnector.contacts.detail.publish') }}
        </v-list-item-title>
      </v-list-item>
      <!-- The way most people will ever find picking: the header entry names it
           "several contacts", which is the one thing nobody looks for while
           looking at the one contact they want. Starting here ticks this row,
           so the mode opens on an answer rather than on an empty checklist. -->
      <v-list-item @click.stop="startSelect">
        <v-list-item-icon class="me-2 my-2">
          <v-icon size="16">fas fa-mouse-pointer</v-icon>
        </v-list-item-icon>
        <v-list-item-title>
          {{ $t('emailConnector.contacts.select.start') }}
        </v-list-item-title>
      </v-list-item>
      <v-list-item
        :disabled="deleting"
        @click.stop="removeContact">
        <v-list-item-icon class="me-2 my-2">
          <v-icon size="16" class="error--text">fas fa-trash</v-icon>
        </v-list-item-icon>
        <v-list-item-title>
          {{ contactDeleteLabel(contact) }}
        </v-list-item-title>
      </v-list-item>
    </template>
  </v-list>
</template>

<script>
import contactActionsMixin from '../../js/EmailConnectorContactActionsMixin.js';

export default {
  mixins: [contactActionsMixin],
  data() {
    return {
      addressBookPublishable: false,
    };
  },
  props: {
    // The contact this menu acts on.
    contact: {
      type: Object,
      required: true,
    },
  },
  computed: {
    /**
     * Whether the chat is on this platform, hence whether a contact can be sent
     * into a conversation at all.
     *
     * @returns {boolean} true when "Send by chat" applies
     */
    chatDeployed() {
      return this.$emailConnectorContactsService.isChatDeployed();
    },
    /**
     * Whether the publish entry shows. MANUAL is the whole source rule, and it
     * is the server's: the publish endpoint refuses a collected row and a
     * directory colleague, and an address-book row is already there.
     *
     * @returns {boolean} true when the publish entry shows
     */
    publishable() {
      return this.addressBookPublishable && this.contact?.source === 'MANUAL';
    },
  },
  created() {
    // Asked per opened menu rather than per row: this component only exists
    // while one row is hovered or focused, and the service answers every call
    // after the first from a page-lifetime cache. Hidden until known, which
    // only errs by briefly not offering the action.
    this.$emailConnectorContactsService.isAddressBookPublishable()
      .then(available => this.addressBookPublishable = available)
      .catch(() => this.addressBookPublishable = false);
  },
  methods: {
    /**
     * Writes to this contact.
     *
     * @returns {void}
     */
    composeTo() {
      this.$emit('close');
      this.composeToContact(this.contact);
    },
    /**
     * Shares this contact as a vCard attachment.
     *
     * @returns {void}
     */
    sendByEmail() {
      this.$emit('close');
      this.sendContactByEmail(this.contact);
    },
    /**
     * Shares this contact into a chat conversation.
     *
     * @returns {void}
     */
    sendByChat() {
      this.$emit('close');
      this.sendContactByChat(this.contact);
    },
    /**
     * Saves this contact to the user's own address book.
     *
     * @returns {void}
     */
    publishContact() {
      this.$emit('close');
      this.publishContactCard(this.contact);
    },
    /**
     * Enters select mode with this row already ticked.
     *
     * @returns {void}
     */
    startSelect() {
      this.$emit('close');
      this.$emit('start-select');
    },
    /**
     * Deletes or suppresses this contact, per its source. No confirmation, the
     * same as the card's own delete: a collected row comes back with the undo
     * the app already offers, and the two surfaces must not disagree about how
     * heavy the action is.
     *
     * @returns {void}
     */
    removeContact() {
      this.$emit('close');
      this.removeContactCard(this.contact);
    },
  },
};
</script>
