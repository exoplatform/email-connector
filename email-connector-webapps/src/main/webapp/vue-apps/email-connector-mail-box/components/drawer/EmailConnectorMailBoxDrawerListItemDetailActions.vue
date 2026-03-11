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
  methods: {
    updateEmailReadStatus() {
      this.$root.$emit('update-email-read-status', { mailRemoteId: this.email.mailRemoteId, read: false });
      this.$root.$emit('close-email-detail-drawer');
    },
    deleteEmail() {
      this.$root.$emit('delete-email', this.email.mailRemoteId);
      this.$root.$emit('close-email-detail-drawer');
    },
    archiveEmail() {
      this.$root.$emit('archive-email', this.email.mailRemoteId);
      this.$root.$emit('close-email-detail-drawer');
    },
  }
};
</script>