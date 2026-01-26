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
          {{ email.read ? 'fa-mail-bulk' : 'fa-envelope-open-text' }}
        </v-icon>
      </v-sheet>
      <span v-if="email.read">
        {{ $t('emailConnector.mailBox.list.drawer.detail.unread.label') }}
      </span>
      <span v-else>
        {{ $t('emailConnector.mailBox.list.drawer.detail.read.label') }}
      </span>
    </v-list-item>
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
    restricted: {
      type: Boolean,
      default: false,
    },
  },
  methods: {
    selectEmail() {
      this.$emit('close');
      this.$root.$emit('select-email', { emailId: this.email.mailRemoteId, selected: true });
    },
    updateEmailReadStatus() {
      this.$emit('close');
      this.$root.$emit('update-email-read-status', { mailRemoteId: this.email.mailRemoteId, read: !this.email.read });
    },
    deleteEmail() {
      this.$emit('close');
      this.$root.$emit('delete-email', { emailId: this.email.mailRemoteId });
    },
    archiveEmail() {
      this.$emit('close');
      this.$root.$emit('archive-email', { emailId: this.email.mailRemoteId });
    },
  }
};
</script>