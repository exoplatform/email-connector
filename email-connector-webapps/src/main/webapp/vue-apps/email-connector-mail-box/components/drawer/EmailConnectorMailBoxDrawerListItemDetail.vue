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
    id="emailDetailDrawer"
    ref="emailDetailDrawer"
    v-model="emailDetailDrawer"
    right
    allow-expand
    :loading="loading"
    go-back-button
    :confirm-close="activeDownload"
    :confirm-close-labels="{
      title: $t('emailConnector.mailBox.attachment.download.confirmAbort.title'),
      message: $t('emailConnector.mailBox.attachment.download.confirmAbort.message'),
      ok: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.yes'),
      cancel: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.no')
    }"
    @confirm-close="onAbortDownloadConfirmed"
    @closed="close"
    @expand-updated="expandedDrawer = $event">
    <template #title>
      <span></span>
    </template>
    <template v-if="!loading" #titleIcons>
      <email-connector-mail-box-drawer-list-item-detail-actions
        v-if="email"
        :email="email" />
    </template>
    <template v-if="emailDetailDrawer && !loading && email" #content>
      <email-connector-mail-box-drawer-list-item-detail-content
        :email="email"
        :expanded-drawer="expandedDrawer" />
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      emailDetailDrawer: false,
      loading: false,
      email: null,
      expandedDrawer: false,
      activeDownload: null,
    };
  },
  created() {
    this.$root.$on('open-email-detail-drawer', (mailRemoteId) => {
      this.open(mailRemoteId); 
    });
    this.$root.$on('close-email-detail-drawer', () => {
      this.close(); 
    });
    this.$root.$on('attachment-download-started', (payload) => {
      this.activeDownload = payload;
    });
    this.$root.$on('attachment-download-finished', () => {
      this.activeDownload = null;
    });
  },
  methods: {
    open(mailRemoteId) {
      this.loading = true;
      this.emailDetailDrawer = true;
      this.$root.$emit('update-email-read-status', { mailRemoteId: mailRemoteId, read: true });
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId).then((email) => {
        this.email = email;
      }).finally(() => {
        this.loading = false;
      });
    },
    onAbortDownloadConfirmed() {
      this.$root.$emit('abort-download-attachment', this.activeDownload.mailRemoteId, this.activeDownload.attachmentRemoteId, this.activeDownload.abortController);
      this.close();
    },
    close() {
      this.emailDetailDrawer = false;
      this.$root.$emit('email-detail-drawer-closed');
    },
  }
};
</script>