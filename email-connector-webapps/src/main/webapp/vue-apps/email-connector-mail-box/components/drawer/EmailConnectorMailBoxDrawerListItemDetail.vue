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
      <extension-registry-components
        :params="{
          email,
        }"
        name="EmailDetail"
        type="email-detail-toolbar"
        parent-element="div"
        element="div"
        class="my-auto" /> 
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.unread.label')"
        @click="updateEmailReadStatus()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-mail-bulk</v-icon>
      </v-btn>
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.archive.label')"
        @click="archiveEmail()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-archive</v-icon>
      </v-btn>
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
        color="error"
        @click="deleteEmail()"
        icon>
        <v-icon size="20">fa-trash</v-icon>
      </v-btn>
    </template>
    <template v-if="emailDetailDrawer && !loading && email" #content>
      <div
        class="fill-height overflow-y-auto specific-scrollbar">
        <v-list class="mt-5 py-0 me-4 ms-4 mb-5">
          <v-list-item
            class="px-0 pb-1 height-auto">
            <v-list-item-content class="py-0 text-title text-wrap overflow-visible">
              <v-list-item-title v-text="email.subject" class="text-wrap overflow-visible" />
            </v-list-item-content>
          </v-list-item>
          <v-list-item
            :class="['height-auto', recipientsClass]">
            <email-connector-mail-box-drawer-list-item-detail-sender-avatar 
              :email="email" 
              class="me-3 my-0" />
            <v-list-item-content class="py-0">
              <v-list-item-title class="font-weight-bold mb-3" v-text="email.sender.name" />
              <v-list-item-subtitle class="text-wrap overflow-visible d-flex">
                <span class="me-1 text-wrap text-break-all">{{ recipients }}</span>
                <v-btn
                  @click="toggleDetails()"
                  :title="recipientsToggleTooltip"
                  width="20"
                  height="20"
                  min-width="20"
                  class="mt-n1"
                  icon>
                  <v-icon size="8" class="icon-default-color">{{ chevronIcon }}</v-icon>
                </v-btn>
              </v-list-item-subtitle>
            </v-list-item-content>
            <v-list-item-action class="pt-4 my-0">
              <v-list-item-subtitle class="pb-1" v-text="receivedDate" />
              <v-btn
                @click="openReplyEmailDrawer()"
                :title="$t('emailConnector.mailBox.list.drawer.detail.reply.button.title')"
                icon>
                <v-icon size="20" class="icon-default-color">fa-reply</v-icon>
              </v-btn>
            </v-list-item-action>
          </v-list-item>
          <email-connector-mail-box-drawer-list-item-detail-header 
            v-if="expandedHeader"
            :email="email" />
          <email-connector-mail-box-drawer-list-item-detail-body
            :expanded-drawer="expandedDrawer" 
            :email-body="email.content?.body" />
          <email-connector-mail-box-drawer-list-item-detail-attachments
            :email-attachments="emailAttachments"
            v-if="hasAttachments" />
        </v-list>
      </div>
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
      expandedHeader: false,
      expandedDrawer: false,
      activeDownload: null,
    };
  },
  created() {
    this.$root.$on('open-email-detail-drawer', (mailRemoteId) => {
      this.open(mailRemoteId); 
    });
    this.$root.$on('attachment-download-started', (payload) => {
      this.activeDownload = payload;
    });
    this.$root.$on('attachment-download-finished', () => {
      this.activeDownload = null;
    });
  },
  computed: {
    receivedDate() {
      return this.$emailConnectorMailBoxService.formatDateString(this.email.receivedDate, this.$t('emailConnector.mailBox.list.drawer.yesterday'));
    },
    chevronIcon() {
      return this.expandedHeader ? 'fa-chevron-up' : 'fa-chevron-down';
    },
    recipients() {
      const recipients = [...this.email.to, ...this.email.cc, ...this.email.bcc];
      if (recipients.length === 0) {
        return '';
      }
      if (recipients.length <= 3) {
        return `${this.$t('emailConnector.mailBox.list.drawer.detail.to')} ${recipients.map(item => item.currentUser && this.$t('emailConnector.mailBox.list.drawer.detail.me') || item.name).join(', ')}`;
      }
      else {
        const firstRecipients = recipients.slice(0, 3);
        const remainingCount = recipients.length - 3; 
        return `${this.$t('emailConnector.mailBox.list.drawer.detail.to')} ${firstRecipients.map(item => item.currentUser && this.$t('emailConnector.mailBox.list.drawer.detail.me') || item.name).join(', ')}, +${remainingCount}`;
      }
    },
    recipientsClass() {
      return this.expandedHeader && 'px-0 pb-3' || 'px-0 pb-8';
    },
    recipientsToggleTooltip() {
      return this.expandedHeader ? this.$t('emailConnector.mailBox.list.drawer.detail.hideRecipients') : this.$t('emailConnector.mailBox.list.drawer.detail.displayRecipients');
    },
    hasAttachments() {
      return this.emailAttachments.length > 0;
    },
    emailAttachments() {
      return this.email.content?.attachments || [];
    }
  },
  methods: {
    open(mailRemoteId) {
      this.loading = true;
      this.$refs.emailDetailDrawer.open();
      this.$root.$emit('update-email-read-status', { mailRemoteId: mailRemoteId, read: true });
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId).then((email) => {
        this.email = email;
      }).finally(() => {
        this.loading = false;
      });
    },
    toggleDetails() {
      this.expandedHeader = !this.expandedHeader;
    },
    updateEmailReadStatus() {
      this.$root.$emit('update-email-read-status', { mailRemoteId: this.email.mailRemoteId, read: false });
      this.close();
    },
    deleteEmail() {
      this.$root.$emit('delete-email', { emailId: this.email.mailRemoteId });
      this.close();
    },
    archiveEmail() {
      this.$root.$emit('archive-email', { emailId: this.email.mailRemoteId });
      this.close();
    },
    onAbortDownloadConfirmed() {
      this.$root.$emit('abort-download-attachment', this.activeDownload.mailRemoteId, this.activeDownload.attachmentRemoteId, this.activeDownload.abortController);
      this.close();
    },
    close() {
      this.expandedHeader = false;
      this.$refs.emailDetailDrawer.close();
    },
    openReplyEmailDrawer() {
      this.$root.$emit('open-new-email-drawer', this.email);
    }
  }
};
</script>