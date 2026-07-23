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
    @click="openAttachment"
    :title="attachmentTitle"
    style="max-width:132px; height:24px"
    color="primary"
    class="me-2"
    outlined>
    <email-connector-mail-box-drawer-attachment-item
      :downloading="downloading || opening" 
      :attachment="attachment" 
      :loader-width="2"
      attachment-icon-size="12"
      attachment-name-class="primary--text ms-2" />
  </v-chip>
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
    loaderWidth: {
      type: Number,
      default: 4,
    },
  },
  computed: {
    attachmentTitle() {
      return this.$t('emailConnector.mailBox.attachment.open.title', {
        0: this.attachment.name,
      });
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
  methods: {
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