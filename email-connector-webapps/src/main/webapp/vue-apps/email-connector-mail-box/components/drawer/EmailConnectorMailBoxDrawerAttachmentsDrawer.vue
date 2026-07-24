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
    right
    :confirm-close="activeDownload"
    :confirm-close-labels="{
      title: $t('emailConnector.mailBox.attachment.download.confirmAbort.title'),
      message: $t('emailConnector.mailBox.attachment.download.confirmAbort.message'),
      ok: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.yes'),
      cancel: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.no')
    }"
    @confirm-close="onAbortDownloadConfirmed"
    style="outline: none;"
    class="no-box-shadow"
    go-back-button>
    <template #title>
      <span class="me-3">{{ $t('emailConnector.mailBox.attachments.label') }}</span>
      <email-connector-mail-box-drawer-attachments-save-all
        :email-attachments="emailAttachments || []" />
    </template>
    <template v-if="attachmentsDrawer" #content>
      <email-connector-mail-box-drawer-attachments-list
        class="pt-2 ps-4"
        :email-attachments="emailAttachments" />
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      emailAttachments: null,
      attachmentsDrawer: false,
      activeDownload: null,
    };
  },
  created() {
    this.$root.$on('open-email-attachments-drawer', this.open);
    this.$root.$on('attachment-download-started', (payload) => {
      this.activeDownload = payload;
    });
    this.$root.$on('attachment-download-finished', () => {
      this.activeDownload = null;
    });
  },
  beforeDestroy() {
    this.$root.$off('open-email-attachments-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on the attachments of a mail.
     *
     * @param {Array} emailAttachments the attachments to list
     * @returns {void}
     */
    open(emailAttachments) {
      this.emailAttachments = emailAttachments;
      this.attachmentsDrawer = true;
    },
    /**
     * Aborts the running download the user has just confirmed cancelling, then
     * closes the drawer.
     *
     * @returns {void}
     */
    onAbortDownloadConfirmed() {
      this.$root.$emit('abort-download-attachment', this.activeDownload.mailRemoteId, this.activeDownload.attachmentRemoteId, this.activeDownload.abortController);
      this.close();
    },
    /**
     * Closes the drawer.
     *
     * @returns {void}
     */
    close() {
      this.attachmentsDrawer = false;
    }
  }
};
</script>