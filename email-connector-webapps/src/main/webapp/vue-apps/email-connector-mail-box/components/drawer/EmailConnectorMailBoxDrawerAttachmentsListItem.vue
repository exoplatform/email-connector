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
    role="button"
    tabindex="0"
    @click="openAttachment"
    @keydown.enter="openAttachment"
    class="pt-3 d-flex align-center"
    :title="attachmentTitle">
    <email-connector-mail-box-drawer-attachment-item
      :downloading="downloading || opening" 
      :attachment="attachment" 
      :attachment-icon-size="attachmentIconSize" 
      attachment-name-class="ms-3" />
    <!-- Everything an attachment can be done with, download included, contributed as
         extensions in one menu. Rows only: a chip of the mail list has no room for a
         menu and its only job is to open the attachment. -->
    <email-connector-mail-box-drawer-attachment-action-menu
      class="ms-auto"
      :attachment="attachment"
      @download="downloadAttachment"
      @open-in-editor="openInEditor" />
  </div>
</template>

<script>
import attachmentOpenMixin from '../../js/EmailConnectorAttachmentOpenMixin.js';

export default {
  mixins: [attachmentOpenMixin],
  data() {
    return {
      downloading: false,
      abortController: null
    };
  },
  props: {
    attachment: {
      type: Object,
      default: () => null,
    },
    attachmentIconSize: {
      type: Number,
      default: 40,
    },
    attachmentNameClass: {
      type: String,
      default: null,
    },
  },
  created() {
    this.onAbortDownload = (mailRemoteId, attachmentRemoteId) => {
      this.abortDownloadAttachment(mailRemoteId, attachmentRemoteId);
    };
    this.$root.$on('abort-download-attachment', this.abortDownloadAttachment);
  },
  beforeDestroy() {
    this.$root.$off('abort-download-attachment', this.abortDownloadAttachment);
  },
  computed: {
    attachmentTitle() {
      return this.$t('emailConnector.mailBox.attachment.open.title', {
        0: this.attachment.name,
      });
    },
  },
  methods: {
    /**
     * Downloads the attachment to the device, telling the drawer about it so closing
     * it while the download runs asks for a confirmation.
     *
     * @returns {void}
     */
    downloadAttachment() {
      if (this.downloading) {
        return;
      }
      this.downloading = true;
      this.abortController = new AbortController();
      this.$root.$emit('attachment-download-started', {
        mailRemoteId: this.attachment.mailRemoteId,
        attachmentRemoteId: this.attachment.attachmentRemoteId,
        abortController: this.abortController
      });
      this.$emailConnectorMailBoxService.downloadAttachment(this.attachment, this.abortController.signal)
        .finally(() => {
          this.downloading = false;
          this.abortController = null;
          this.$root.$emit('attachment-download-finished');
        });
    },
    /**
     * Aborts the running download, when it is this row's one.
     *
     * @param {Number} mailRemoteId the mail the aborted download belongs to
     * @param {String} attachmentRemoteId the aborted attachment part id
     * @param {AbortController} abortController the controller of the running download
     * @returns {void}
     */
    abortDownloadAttachment(mailRemoteId, attachmentRemoteId, abortController) {
      if (this.attachment.mailRemoteId === mailRemoteId && this.attachment.attachmentRemoteId === attachmentRemoteId) {
        this.abortController = abortController;
        if (this.abortController) {
          this.abortController.abort();
          this.downloading = false;
          this.abortController = null;
          this.$root.$emit('attachment-download-finished');
        }
      }
    }  
  }
};
</script>