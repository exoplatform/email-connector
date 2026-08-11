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
  <div class="ec-thread-message">
    <!-- Collapsed: a Gmail-style one-line row (sender, snippet, date); click to open. -->
    <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
    <div
      v-if="!expanded"
      class="clickable px-0 py-3 d-flex align-center"
      tabindex="0"
      :aria-label="ariaLabel"
      @click="$emit('expand')"
      @keydown.enter="$emit('expand')"
      @keydown.space.prevent="$emit('expand')">
      <email-connector-mail-box-drawer-list-item-detail-sender-avatar
        :email="email"
        class="me-3 my-0" />
      <div class="d-flex align-center no-min-width flex-grow-1">
        <span
          :class="['flex-shrink-0 text-truncate', { 'font-weight-bold': !email.read }]"
          style="max-width: 45%">{{ senderLabel }}</span>
        <span class="text-light-color ms-3 text-truncate">{{ snippet }}</span>
      </div>
      <v-icon
        v-if="hasAttachment"
        size="14"
        class="icon-default-color ms-2 flex-shrink-0"
        :title="$t('emailConnector.mailBox.attachments.label')">
        fa-paperclip
      </v-icon>
      <span class="text-light-color ms-2 flex-shrink-0 text-caption">{{ receivedDate }}</span>
    </div>
    <!-- Expanded: the full message. Reuses the single-message renderer without its
         subject (shown once at the thread top); its sender line toggles it closed. -->
    <email-connector-mail-box-drawer-list-item-detail-content
      v-else
      :email="email"
      :expanded-drawer="expandedDrawer"
      hide-subject
      :collapsible="collapsible"
      @toggle-collapse="$emit('collapse')" />
  </div>
</template>

<script>
export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
    expanded: {
      type: Boolean,
      default: false,
    },
    // The latest message of a thread stays open, like Gmail, so it is not collapsible.
    collapsible: {
      type: Boolean,
      default: true,
    },
    expandedDrawer: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    receivedDate() {
      return this.$emailConnectorMailBoxService.formatDateString(this.email.receivedDate, this.$t('emailConnector.mailBox.list.drawer.yesterday'));
    },
    // One-line preview of the message body for the collapsed row. Derived from the
    // body (the full message carries no pre-computed excerpt), stripped of markup.
    snippet() {
      const body = this.email.content?.body || '';
      if (typeof DOMParser === 'undefined') {
        return '';
      }
      const text = new DOMParser().parseFromString(body, 'text/html').body?.textContent || '';
      return text.replace(/\s+/g, ' ').trim();
    },
    ariaLabel() {
      return this.$t('emailConnector.mailBox.list.drawer.thread.openMessage', {
        0: this.senderLabel,
      });
    },
    // A message from the user's own Sent folder is shown as "Me", like Gmail.
    senderLabel() {
      return this.email.folder === 'SENT'
        ? this.$t('emailConnector.mailBox.list.drawer.detail.me')
        : this.email.sender.name;
    },
    hasAttachment() {
      return (this.email.content?.attachments?.length || 0) > 0;
    },
  },
};
</script>
