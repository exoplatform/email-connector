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
    @closed="close"
    @expand-updated="expandedDrawer = $event">
    <template #title>
      <span></span>
    </template>
    <template #titleIcons>
      <v-btn
        :title="$t('emailConnector.mailBox.list.drawer.detail.unread.tooltip')"      
        v-on="on"
        v-bind="attrs"
        @click="markAsUnread()"
        icon>
        <v-icon size="20">fa-mail-bulk</v-icon>
      </v-btn>
    </template>
    <template v-if="emailDetailDrawer && !loading && email" #content>
      <div
        class="fill-height overflow-y-auto specific-scrollbar">
        <v-list class="mt-5 py-0 me-4 ms-4">
          <v-list-item
            style="min-height: 0"
            class="px-0 pb-4">
            <v-list-item-content class="py-0 text-title text-wrap overflow-visible">
              <v-list-item-title v-text="email.subject" class="text-wrap overflow-visible" />
            </v-list-item-content>
          </v-list-item>
          <v-list-item
            style="min-height: 0"
            :class="recipientsClass">
            <email-connector-mail-box-drawer-list-item-detail-sender-avatar 
              :email="email" 
              class="me-3 my-0" />
            <v-list-item-content class="py-0">
              <v-list-item-title class="font-weight-bold mb-3" v-text="email.sender.name" />
              <v-list-item-subtitle class="text-wrap overflow-visible d-flex">
                <span class="me-1 text-wrap text-break-all">{{ recipients }}</span>
                <v-btn
                  @click="toggleDetails()"
                  width="20"
                  height="20"
                  min-width="20"
                  class="mt-n1"
                  icon>
                  <v-icon size="8">{{ chevronIcon }}</v-icon>
                </v-btn>
              </v-list-item-subtitle>
            </v-list-item-content>
            <v-list-item-action class="my-0 align-self-start">
              <v-list-item-subtitle v-text="sentDate" />
            </v-list-item-action>
          </v-list-item>
          <email-connector-mail-box-drawer-list-item-detail-header 
            v-if="expandedHeader"
            :email="email" />
          <email-connector-mail-box-drawer-list-item-detail-body 
            :expanded-drawer="expandedDrawer" 
            :email-content="email.content" />
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
    expandedDrawer: false,
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
  },
  methods: {
    open(mailRemoteId) {
      this.loading = true;
      this.$refs.emailDetailDrawer.open();
      this.$root.$emit('update-email-read-status', { emailId: mailRemoteId, read: true });
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId).then((email) => {
        this.email = email;
      }).finally(() => {
        this.loading = false;
      });
    },
    toggleDetails() {
      this.expandedHeader = !this.expandedHeader;
    },
    markAsUnread() {
      this.$root.$emit('update-email-read-status', { emailId: this.email.mailRemoteId, read: false });
      this.close();        
      this.$emailConnectorMailBoxService.updateEmailReadStatus(this.email.mailRemoteId, false);
    },
    close() {
      this.expandedHeader = false;
      this.$refs.emailDetailDrawer.close();
    },
  }
};
</script>