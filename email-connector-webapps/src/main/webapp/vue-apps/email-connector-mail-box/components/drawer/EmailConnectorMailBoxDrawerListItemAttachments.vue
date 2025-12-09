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
    class="mt-2 d-flex align-center">
    <email-connector-mail-box-drawer-list-item-attachments-list-item
      v-for="attachment in emailAttachmentsList"
      :key="attachment.id"
      :attachment="attachment" />
    <v-chip
      v-if="hasMoreAttachments"
      style="width:24px; height:24px"
      class="px-1 d-flex align-center justify-center text-subtitle font-weight-bold"
      text-color="white"
      color="#707070"
      @click="openAttachmentsDrawer">
      {{ attachmentsLabel }}
    </v-chip>
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
    hasMoreAttachments() {
      return this.emailAttachments.length > 2;
    },
    attachmentsLabel() {
      return `+ ${this.emailAttachments.length - 2}`;
    },
    emailAttachmentsList() {
      return this.emailAttachments.slice(0, 2);
    },
  },
  methods: {
    openAttachmentsDrawer() {
      this.$root.$emit('open-email-attachments-drawer', this.emailAttachments);
    },
  }
};
</script>