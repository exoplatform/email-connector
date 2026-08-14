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
  <v-layout>
    <extension-registry-components
      :params="{
        email,
      }"
      name="EmailDetail"
      type="email-detail-toolbar"
      parent-element="div"
      element="div"
      class="my-auto" />
    <!-- All three write to the mail server by IMAP UID, which the backend resolves
         against the inbox — so a message opened out of a read-only folder offers
         none of them. The extension seam above stays: it is somebody else's toolbar
         and its actions are not this one's to withdraw. -->
    <template v-if="!readOnly">
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.unread.label')"
        @click="updateEmailReadStatus()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-mail-bulk</v-icon>
      </v-btn>
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.archive.label')"
        @click="archiveEmail()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-archive</v-icon>
      </v-btn>
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
        color="error"
        @click="deleteEmail()"
        icon>
        <v-icon size="20">fa-trash</v-icon>
      </v-btn>
    </template>
  </v-layout>
</template>

<script>
export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
  },
  computed: {
    /**
     * Whether the opened message sits in a folder the interface may only read
     * (Trash), in which case this toolbar offers nothing that writes.
     *
     * @returns {Boolean} true when the mail actions must stay hidden
     */
    readOnly() {
      return this.$emailConnectorMailBoxService.isReadOnlyFolder(this.email?.folder);
    },
  },
  methods: {
    updateEmailReadStatus() {
      this.$root.$emit('update-email-read-status', false, [this.email.mailRemoteId]);
      this.$root.$emit('close-email-detail-drawer');
    },
    deleteEmail() {
      this.$root.$emit('delete-email', [this.email.mailRemoteId]);
      this.$root.$emit('close-email-detail-drawer');
    },
    archiveEmail() {
      this.$root.$emit('archive-email', [this.email.mailRemoteId]);
      this.$root.$emit('close-email-detail-drawer');
    },
  }
};
</script>