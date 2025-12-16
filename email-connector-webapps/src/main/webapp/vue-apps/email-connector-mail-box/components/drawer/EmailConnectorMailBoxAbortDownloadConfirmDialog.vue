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
  <exo-confirm-dialog
    ref="abortDownloadConfirmDialog"
    :title="$t('emailConnector.mailBox.attachment.download.confirmAbort.title')"
    :message="$t('emailConnector.mailBox.attachment.download.confirmAbort.message')"
    :ok-label="$t('emailConnector.mailBox.attachment.download.confirmAbort.button.yes')"
    :cancel-label="$t('emailConnector.mailBox.attachment.download.confirmAbort.button.no')"
    @ok="abortDownloadAttachment" />
</template>

<script>
export default {
  data() {
    return {
      abortMailRemoteId: null,
      abortAttachmentRemoteId: null
    };
  },
  created() {
    this.$root.$on('open-abort-download-confirm-dialog', this.openAbortDownloadConfirmDialog);
  },
  methods: {
    openAbortDownloadConfirmDialog(mailRemoteId, attachmentRemoteId) {
      this.abortMailRemoteId = mailRemoteId;
      this.abortAttachmentRemoteId = attachmentRemoteId;
      this.$refs.abortDownloadConfirmDialog.open();
    },
    abortDownloadAttachment() {
      this.$root.$emit('abort-download-attachment', this.abortMailRemoteId, this.abortAttachmentRemoteId);
      this.abortMailRemoteId = null;
      this.abortAttachmentRemoteId = null;
    }
  }
};
</script>