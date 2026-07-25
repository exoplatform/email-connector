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
  <v-list class="my-5 py-0 mx-4">
    <v-list-item
      v-if="!hideSubject"
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
        <v-list-item-title
          :class="['font-weight-bold mb-3', { clickable: collapsible }]"
          @click="collapsible && $emit('toggle-collapse')"
          v-text="email.sender.name" />
        <v-list-item-subtitle class="text-wrap overflow-visible d-flex">
          <span class="me-1 text-wrap text-break-all">{{ recipients }}</span>
          <v-btn
            @click="toggleDetails"
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
      <v-list-item-action class="pt-4 my-0 d-flex flex-column align-end">
        <v-list-item-subtitle class="pb-1" v-text="receivedDate" />
        <div class="d-flex flex-row align-center">
          <v-btn
            @click="openReplyEmailDrawer"
            :title="$t('emailConnector.mailBox.list.drawer.detail.reply.button.title')"
            icon>
            <v-icon size="20" class="icon-default-color">fa-reply</v-icon>
          </v-btn>
          <email-connector-mail-box-drawer-list-item-detail-action-menu
            :email="email" />
        </div> 
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
</template>

<script>
export default {
  data() {
    return {
      expandedHeader: false,
    };
  },
  created() {
    this.$root.$on('email-detail-drawer-closed', () => {
      this.expandedHeader = false;
    });
  },
  props: {
    email: {
      type: Object,
      default: () => null,
    },
    expandedDrawer: {
      type: Boolean,
      default: false,
    },
    // In a thread the subject is shown once at the top, so each message hides its own.
    hideSubject: {
      type: Boolean,
      default: false,
    },
    // In a thread an expanded message collapses again when its sender line is clicked.
    collapsible: {
      type: Boolean,
      default: false,
    },
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
    },
    isMobile() {
      return this.$vuetify.breakpoint.smAndDown;
    },
  },
  methods: {
    toggleDetails() {
      this.expandedHeader = !this.expandedHeader;
    },
    openReplyEmailDrawer() {
      this.$root.$emit('open-new-email-drawer', this.email);
    }
  }
};
</script>