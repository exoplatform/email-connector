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
  <!-- In a thread the outer ThreadContent already provides the horizontal margin, so
       the message must not add its own or its avatar drifts right of the collapsed rows. -->
  <v-list :class="['py-0', hideSubject ? 'my-0' : 'my-5 mx-4']">
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
          v-text="senderLabel" />
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
          <!-- The message's favorite (the server's \Flagged flag): toggling pushes to
               the mail server, so it follows to phone, Gmail, everywhere. -->
          <email-connector-mail-box-drawer-favorite-toggle
            :favorite="!!email.starred"
            :can-toggle="canToggleFavorite"
            :size="18"
            @toggle="toggleFavorite" />
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
import { personLabel } from '../../js/EmailRecipientDisplay.js';

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
    // A message from the user's own Sent folder is shown as "Me", like Gmail.
    senderLabel() {
      return this.email.folder === 'SENT'
        ? this.$t('emailConnector.mailBox.list.drawer.detail.me')
        : this.email.sender.name;
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
        return `${this.$t('emailConnector.mailBox.list.drawer.detail.to')} ${recipients.map(item => this.recipientLabel(item)).join(', ')}`;
      }
      else {
        const firstRecipients = recipients.slice(0, 3);
        const remainingCount = recipients.length - 3;
        return `${this.$t('emailConnector.mailBox.list.drawer.detail.to')} ${firstRecipients.map(item => this.recipientLabel(item)).join(', ')}, +${remainingCount}`;
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
    // Only an INBOX message can be toggled: the favorite endpoint pushes the IMAP
    // \Flagged flag through the INBOX folder. A favorite Sent/Archive copy in a
    // conversation still shows its (read-only) favorite.
    canToggleFavorite() {
      return (this.email.folder || 'INBOX') === 'INBOX';
    },
  },
  methods: {
    /**
     * What one name on the recipients line reads: "Me" for the reader themselves,
     * otherwise the person's name — and their address when they have no name, which
     * this line used to render as a gap between two commas.
     *
     * @param {object} recipient - the addressed recipient
     * @returns {string} the label to show
     */
    recipientLabel(recipient) {
      return recipient.currentUser && this.$t('emailConnector.mailBox.list.drawer.detail.me') || personLabel(recipient);
    },
    toggleDetails() {
      this.expandedHeader = !this.expandedHeader;
    },
    // The optimistic flip and the service call are centralized in the mailbox
    // drawer's handler, which also rolls this very object back (through the
    // thread's own listener) if the mail server refuses the flag.
    toggleFavorite() {
      this.$root.$emit('update-email-favorite-status', !this.email.starred, [this.email.mailRemoteId]);
    },
    openReplyEmailDrawer() {
      this.$root.$emit('open-new-email-drawer', this.email);
    }
  }
};
</script>