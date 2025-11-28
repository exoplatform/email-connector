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
    class="pt-3 text-truncate"
    :title="attachmentTitle">
    <v-icon :size="attachmentIconSize" :color="attachmentColor">
      {{ attachmentIcon }}
    </v-icon>
    <span :class="[`ms-${attachmentIconMargin}`]">
      {{ attachment.name }}
    </span>
  </div>
</template>

<script>
export default {
  props: {
    attachment: {
      type: Object,
      default: () => null,
    },
    attachmentIconSize: {
      type: Number,
      default: 36,
    },
    attachmentIconMargin: {
      type: Number,
      default: 2,
    },
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
   
  }
};
</script>