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
  <v-chip
    @click="downloadAttachment"
    :title="attachmentTitle"
    style="max-width:132px; height:24px"
    color="primary"
    class="me-2"
    outlined>
    <v-icon
      size="12"
      :color="attachmentColor"
      class="pe-2">
      {{ attachmentIcon }}
    </v-icon>
    <span class="text-truncate primary--text">{{ attachment.name }}</span>
  </v-chip>
</template>

<script>
export default {
  props: {
    attachment: {
      type: Object,
      default: () => null,
    }
  },
  computed: {
    attachmentIcon() {
      return this.$emailConnectorMailBoxService.getAttachmentIcon(this.attachment.mimeType).class;
    },
    attachmentColor() {
      return this.$emailConnectorMailBoxService.getAttachmentIcon(this.attachment.mimeType).color;
    },
    attachmentTitle() {
      return this.$t('emailConnector.mailBox.list.drawer.detail.attachment.download.title', {
        0: this.attachment.name,
      });
    },
  },
  methods: {
    downloadAttachment() {
      const url = `/email-connector/rest/email-box/attachments/${this.attachment.mailRemoteId}/${this.attachment.attachmentRemoteId}`;
      window.open(url, '_blank');
    }
  }
};
</script>