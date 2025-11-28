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
  <exo-drawer
    id="attachmentsDrawer"
    ref="attachmentsDrawer"
    v-model="attachmentsDrawer"
    :right="!$vuetify.rtl"
    @closed="close"
    style="outline: none;"
    class="no-box-shadow"
    go-back-button>
    <template #title>
      <span class="me-3">{{ $t('emailConnector.mailBox.attachments.label') }}</span>
    </template>
    <template v-if="attachmentsDrawer" #content>
      <email-connector-mail-box-drawer-attachments-list
        class="pt-2 ps-4"
        :email-attachments="emailAttachments" 
        :attachment-icon-size="41" 
        :attachment-icon-margin="5" />
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      emailAttachments: null,
      attachmentsDrawer: false
    };
  },
  created() {
    this.$root.$on('open-email-attachments-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-attachments-drawer', this.open);
  },
  methods: {
    open(emailAttachments) {
      this.emailAttachments = emailAttachments;
      this.$refs.attachmentsDrawer.open();
    },
    close() {
      this.$refs.attachmentsDrawer.close();
    }
  }
};
</script>