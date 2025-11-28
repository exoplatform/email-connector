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
  <div>
    <email-connector-mail-box-drawer-attachments-list-item
      v-for="attachment in emailAttachments"
      :key="attachment.id"
      :attachment="attachment" 
      :attachment-icon-size="attachmentIconSize" 
      :attachment-icon-margin="attachmentIconMargin" />
  </div>
</template>

<script>
export default {
  props: {
    emailAttachments: {
      type: Array,
      default: () => [],
    },
    attachmentIconSize: {
      type: Number,
      default: 36,
    },
    attachmentIconMargin: {
      type: Number,
      default: 2,
    },
  }
};
</script>