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
  <v-list class="pa-0">
    <v-list-item
      class="ps-2 pe-3 height-auto"
      @click.stop="selectEmail">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          fa-mouse-pointer
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.select.label') }}
      </span>
    </v-list-item>
    <v-list-item
      class="ps-2 pe-3 height-auto"
      @click.stop="updateEmailReadStatus">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          {{ threadRead ? 'fa-mail-bulk' : 'fa-envelope-open-text' }}
        </v-icon>
      </v-sheet>
      <span v-if="threadRead">
        {{ $t('emailConnector.mailBox.list.drawer.detail.unread.label') }}
      </span>
      <span v-else>
        {{ $t('emailConnector.mailBox.list.drawer.detail.read.label') }}
      </span>
    </v-list-item>
    <!-- Favorite/unfavorite the conversation: the mail server's own \Flagged flag, so it
         shows in every client. Inbox only — the flag is pushed through INBOX. -->
    <v-list-item
      v-if="canFavorite"
      class="ps-2 pe-3 height-auto"
      @click.stop="updateEmailFavoriteStatus">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          {{ threadFavorite ? 'fas fa-star' : 'far fa-star' }}
        </v-icon>
      </v-sheet>
      <span v-if="threadFavorite">
        {{ $t('emailConnector.mailBox.list.drawer.detail.removeFavorite.label') }}
      </span>
      <span v-else>
        {{ $t('emailConnector.mailBox.list.drawer.detail.addFavorite.label') }}
      </span>
    </v-list-item>
    <extension-registry-components
      :params="{
        email,
      }"
      name="Email"
      type="email-menu-action"
      parent-element="div"
      element="div"
      class="my-auto" /> 
    <v-list-item
      v-if="!restricted"
      class="ps-2 pe-3 height-auto"
      @click.stop="archiveEmail">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="mx-auto"
          size="16">
          fa-archive
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.archive.label') }}
      </span>
    </v-list-item>
    <v-list-item
      v-if="!restricted"
      class="ps-2 pe-3 height-auto"
      @click.stop="deleteEmail">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="error--text mx-auto"
          size="16">
          fa-trash
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.delete.label') }}
      </span>
    </v-list-item>
  </v-list>
</template>

<script>
export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
    thread: {
      type: Object,
      default: () => null,
    },
    restricted: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    // All message ids the action applies to: the whole thread, or the lone email.
    threadIds() {
      return this.thread ? this.thread.mailRemoteIds : [this.email.mailRemoteId];
    },
    // A thread reads as read only when none of its messages is unread.
    threadRead() {
      return this.thread ? this.thread.unreadCount === 0 : this.email.read;
    },
    // A thread shows as favorite when any of its listed messages carries the flag.
    threadFavorite() {
      return this.thread ? this.thread.emails.some(message => message.starred) : !!this.email.starred;
    },
    // The favorite is pushed through the INBOX folder, so only inbox rows offer it.
    canFavorite() {
      return (this.email.folder || 'INBOX') === 'INBOX';
    },
  },
  methods: {
    selectEmail() {
      this.$emit('close');
      this.threadIds.forEach(emailId => this.$root.$emit('select-email', { emailId, selected: true }));
    },
    updateEmailReadStatus() {
      this.$emit('close');
      this.$root.$emit('update-email-read-status', !this.threadRead, this.threadIds);
    },
    updateEmailFavoriteStatus() {
      this.$emit('close');
      this.$root.$emit('update-email-favorite-status', !this.threadFavorite, this.threadIds);
    },
    deleteEmail() {
      this.$emit('close');
      this.$root.$emit('delete-email', this.threadIds);
    },
    archiveEmail() {
      this.$emit('close');
      this.$root.$emit('archive-email', this.threadIds);
    },
  }
};
</script>