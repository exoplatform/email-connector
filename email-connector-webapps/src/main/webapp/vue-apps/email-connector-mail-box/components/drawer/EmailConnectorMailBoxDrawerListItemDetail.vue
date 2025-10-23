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
    :right="!$vuetify.rtl"
    :allow-expand="!$root.isMobile"
    :loading="loading"
    go-back-button
    @closed="close">
    <template #title>
      <span></span>
    </template>
    <template v-if="emailDetailDrawer && !loading && email" #content>
      <div
        class="fill-height overflow-y-auto specific-scrollbar">
        <v-list class="mt-5 py-0 me-4 ms-4">
          <v-list-item
            style="min-height: 0"
            class="px-0 pb-4">
            <v-list-item-content class="py-0 text-title">
              <v-list-item-title v-text="email.subject" />
            </v-list-item-content>
          </v-list-item>
          <v-list-item
            style="min-height: 0"
            :class="recipientsClass">
            <email-connector-mail-box-drawer-list-item-detail-sender-avatar :email="email" />
            <v-list-item-content class="py-0">
              <v-list-item-title class="font-weight-bold mb-3" v-text="email.sender.name" />
              <v-list-item-subtitle class="text-wrap overflow-visible">
                <span class="me-2">{{ recipients }}</span>
                <v-icon @click="toggleDetails()" size="8">{{ chevronIcon }}</v-icon>
              </v-list-item-subtitle>
            </v-list-item-content>
            <v-list-item-action class="my-0 align-self-start">
              <v-list-item-subtitle v-text="sentDate" />
            </v-list-item-action>
          </v-list-item>
          <email-connector-mail-box-drawer-list-item-detail-header v-if="expandedHeader" :email="email" />
          <v-list-item
            style="min-height: 0"
            class="px-0">
            <v-list-item-content class="py-0">
              <iframe
                ref="iframe"
                :srcdoc="email.content"
                style="width: 100%; border: none; overflow: hidden;"
                :style="{height: iframeHeight + 'px'}"
                @load="onLoadIframe"
                title="iframe"></iframe>
            </v-list-item-content>
          </v-list-item>
        </v-list>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    emailDetailDrawer: false,
    loading: false,
    email: null,
    expandedHeader: false,
    iframeHeight: 0
  }),
  created() {
    this.$root.$on('open-email-detail-drawer', (mailRemoteId) => {
      this.open(mailRemoteId); 
    });
  },
  computed: {
    sentDate() {
      return this.$emailConnectorMailBoxService.formatDateString(this.email.sentDate, this.$t('emailConnector.mailBox.list.drawer.yesterday'));
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
        return `${this.$t('emailConnector.mailBox.list.drawer.detail.to')}: ${recipients.map(item => item.currentUser && this.$t('emailConnector.mailBox.list.drawer.detail.me') || item.name).join(', ')}`;
      }
      else {
        const firstRecipients = recipients.slice(0, 3);
        const remainingCount = recipients.length - 3; 
        return `${this.$t('emailConnector.mailBox.list.drawer.detail.to')}: ${firstRecipients.map(item => item.currentUser && this.$t('emailConnector.mailBox.list.drawer.detail.me') || item.name).join(', ')}, +${remainingCount}`;
      }
    },
    recipientsClass() {
      return this.expandedHeader && 'px-0 pb-3' || 'px-0 pb-8';
    },
    initials() {
      const text = (this.email.sender.name || '').trim();
      const parts = text.split(/\s+/);
      if (parts.length === 0) {
        return '';
      }
      if (parts.length === 1) {
        return parts[0].charAt(0).toUpperCase();
      }
      return (parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
    },
    bgColor() {
      const source = this.email.sender.email || this.email.sender.name || '';
      let hash = 0;
      for (let i = 0; i < source.length; i++) {
        hash = source.charCodeAt(i) + ((hash << 5) - hash);
      }
      const hue = Math.abs(hash) % 360;
      return `hsl(${hue},70%,50%)`;
    },
  },
  methods: {
    open(mailRemoteId) {
      this.loading = true;
      this.$refs.emailDetailDrawer.open();
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId).then((email) => {
        this.email = email;
      }).finally(() => {
        this.loading = false;
      });
    },
    toggleDetails() {
      this.expandedHeader = !this.expandedHeader;
    },
    close() {
      this.expandedHeader = false;
      this.$refs.emailDetailDrawer.close();
    },
    onLoadIframe() {
      const iframe = this.$refs.iframe;
      try {
        const doc = iframe.contentDocument || iframe.contentWindow.document;
        this.iframeHeight = doc.body.scrollHeight;
      } catch (e) {
        this.iframeHeight = 400;
      }
    }
  }
};
</script>