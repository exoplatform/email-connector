<!--
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
-->
<template>
  <!-- The way out to the whole mailbox: the connector only searched what is cached
       locally, so the user is told so and handed to the mailbox's own search. -->
  <v-card
    v-if="searchAll"
    class="d-flex align-center pa-3 my-2"
    flat
    @click="openMailboxSearch">
    <v-icon size="18" class="primary--text me-3">fas fa-search</v-icon>
    <span class="primary--text text-truncate">{{ $t('emailConnector.search.connector.searchWholeMailbox') }}</span>
  </v-card>
  <v-card
    v-else
    class="d-flex align-start pa-3 my-2"
    flat
    @click="openEmail">
    <v-icon
      size="18"
      :class="unread ? 'primary--text' : 'icon-default-color'"
      class="me-3 mt-1">
      {{ unread ? 'fas fa-envelope' : 'far fa-envelope-open' }}
    </v-icon>
    <div class="d-flex flex-column flex-grow-1 overflow-hidden">
      <div class="d-flex align-center">
        <span :class="['text-truncate', {'font-weight-bold': unread}]">{{ subject }}</span>
        <v-icon
          v-if="result.starred"
          size="12"
          class="amber--text text--darken-1 ms-2"
          :title="$t('emailConnector.mailBox.list.drawer.detail.favorite.label')">
          fas fa-star
        </v-icon>
        <v-chip
          v-if="folderLabel"
          x-small
          class="ms-2 flex-grow-0 flex-shrink-0">
          {{ folderLabel }}
        </v-chip>
      </div>
      <div class="d-flex align-center caption text-light-color">
        <span class="text-truncate">{{ senderName }}</span>
        <v-icon class="flex-grow-0 flex-shrink-0 mx-2" size="2">fa-circle</v-icon>
        <date-format class="flex-grow-0 flex-shrink-0" :value="result.receivedDate" />
      </div>
    </div>
  </v-card>
</template>
<script>
export default {
  props: {
    id: {
      type: String,
      default: () => null,
    },
    result: {
      type: Object,
      default: () => ({}),
    },
    term: {
      type: String,
      default: () => null,
    },
  },
  computed: {
    searchAll() {
      return !!this.result?.searchAll;
    },
    unread() {
      return this.result && !this.result.read;
    },
    subject() {
      return this.result?.subject?.trim?.() || this.$t('emailConnector.mailBox.list.drawer.noSubject');
    },
    senderName() {
      return this.result?.sender?.name || this.result?.sender?.address || '';
    },
    folderLabel() {
      // The inbox is where mail normally is, so only the other folders are worth a
      // chip: seeing "Sent" explains why a hit reads as something the user wrote.
      if (this.result?.folder === 'SENT') {
        return this.$t('emailConnector.mailBox.list.drawer.folder.sent');
      }
      return this.result?.folder === 'ARCHIVE' ? this.$t('emailConnector.mailBox.list.drawer.folder.archive') : '';
    },
  },
  methods: {
    openEmail() {
      // The mailbox owns the reader, wherever the user is standing. Requiring the
      // module first covers the page that has not loaded it yet; it is a no-op once
      // it has.
      window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-email-box-mail', {
          detail: {mailRemoteId: this.result?.mailRemoteId},
        })));
    },
    openMailboxSearch() {
      // Hand the term to the mailbox's own search rather than running a second one
      // here: that field already searches the whole mailbox, shows what it found and
      // what it did not, and opens what it finds.
      window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-email-box-search', {
          detail: {term: this.term || this.result?.term},
        })));
    },
  },
};
</script>
