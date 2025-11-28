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
  <div
    class="mt-5">
    <div
      role="button"
      tabindex="0"
      @click="openAttachmentsDrawer"
      @keydown.enter="openAttachmentsDrawer"
      :class="[{ 'not-clickable': !canOpenAttachmentsDrawer }]">
      <v-icon size="20">
        fa-paperclip
      </v-icon>
      <span class="ms-3 font-weight-bold">
        {{ attachmentsLabel }}
      </span>
    </div>
    <email-connector-mail-box-drawer-attachments-list
      :email-attachments="emailAttachmentsList" />
  </div>
</template>

<script>
export default {
  props: {
    emailAttachments: {
      type: Array,
      default: () => [],
    },
  },
  computed: {
    attachmentsLabel() {
      return `${this.$t('emailConnector.mailBox.attachments.label')} (${this.emailAttachments.length})`;
    },
    emailAttachmentsList() {
      return this.emailAttachments.slice(0, 2);
    },
    canOpenAttachmentsDrawer() {
      return this.emailAttachments.length > 2;
    },
  },
  methods: {
    openAttachmentsDrawer() {
      if (this.canOpenAttachmentsDrawer) {
        this.$root.$emit('open-email-attachments-drawer', this.emailAttachments);
      }
    },
  }
};
</script>