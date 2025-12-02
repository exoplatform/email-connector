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
    @click="downloadAttachment"
    @keydown.enter="downloadAttachment"
    class="pt-3"
    :title="attachmentTitle">
    <email-connector-mail-box-drawer-attachment-item
      :downloading="downloading" 
      :attachment="attachment" 
      :attachment-icon-size="attachmentIconSize" 
      attachment-name-class="ms-3" />
  </div>
</template>

<script>
export default {
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
  beforeDestroy() {
    if (this.abortController) {
      this.$root.$emit('open-abort-download-confirm-dialog', this.attachment.mailRemoteId, this.attachment.attachmentRemoteId);
      this.$root.$on('abort-download-attachment', (mailRemoteId, attachmentRemoteId) => {
        this.abortDownloadAttachment(mailRemoteId, attachmentRemoteId) ; 
      });
    }
  },
  computed: {
    attachmentTitle() {
      return this.$t('emailConnector.mailBox.list.drawer.detail.attachment.download.title', {
        0: this.attachment.name,
      });
    },
  },
  methods: {
    downloadAttachment() {
      if (this.downloading) {
        return;
      }
      this.downloading = true;
      this.abortController = new AbortController();
      this.$emailConnectorMailBoxService.downloadAttachment(this.attachment, this.abortController.signal)
        .finally(() => {
          this.downloading = false;
          this.abortController = null;
        });
    },
    abortDownloadAttachment(mailRemoteId, attachmentRemoteId) {
      if (this.attachment.mailRemoteId === mailRemoteId && this.attachment.attachmentRemoteId === attachmentRemoteId) {
        if (this.abortController) {
          this.abortController.abort();
          this.downloading = false;
        }
      }
    }  
  }
};
</script>