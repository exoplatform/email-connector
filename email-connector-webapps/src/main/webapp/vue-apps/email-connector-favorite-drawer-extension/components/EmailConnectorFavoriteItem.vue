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
  <v-list-item
    v-if="email"
    @keydown.enter="open"
    @click="open">
    <v-list-item-icon class="me-3 my-auto">
      <v-card
        :min-width="iconWidth"
        class="d-flex justify-center no-border-radius"
        color="transparent"
        flat>
        <v-icon :size="iconSize" color="primary">fa-envelope</v-icon>
      </v-card>
    </v-list-item-icon>
    <v-list-item-content>
      <v-list-item-title :class="['text-truncate', {'font-weight-bold': unread}]">{{ subject }}</v-list-item-title>
      <v-list-item-subtitle v-if="expanded" class="d-flex align-center full-width overflow-hidden pt-2px">
        <span class="flex-grow-0 flex-shrink-1 text-truncate">{{ senderName }}</span>
        <v-icon class="flex-grow-0 flex-shrink-0 mx-2" size="2">fa-circle</v-icon>
        <date-format class="flex-grow-0 flex-shrink-0" :value="receivedDate" />
      </v-list-item-subtitle>
    </v-list-item-content>
  </v-list-item>
</template>
<script>
export default {
  props: {
    id: {
      type: String,
      default: () => null,
    },
    clickCallback: {
      type: Function,
      default: null,
    },
    expanded: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    email: null,
  }),
  computed: {
    iconWidth() {
      return this.expanded ? 40 : 30;
    },
    iconSize() {
      return this.expanded ? 24 : 18;
    },
    subject() {
      return this.email?.subject?.trim?.() || this.$t('UITopBarFavoritesPortlet.email.noSubject');
    },
    senderName() {
      return this.email?.sender?.name || this.email?.sender?.email || '';
    },
    receivedDate() {
      return this.email?.receivedDate;
    },
    unread() {
      return this.email && !this.email.read;
    },
  },
  created() {
    // Favorites are keyed by the mail's technical id, which is the one identifier
    // the rest of this app never uses — everything else addresses a message by its
    // IMAP UID — hence the dedicated read.
    fetch(`/email-connector/rest/email-box/favorites/${this.id}`, {
      method: 'GET',
      credentials: 'include',
    }).then(response => {
      if (!response?.ok) {
        throw new Error('Favorited email cannot be read');
      }
      return response.json();
    }).then(email => this.email = email)
      .catch(() => {
        // The mail is gone from the mailbox (deleted, or aged out of the cached
        // window between two syncs). Telling the drawer drops the entry instead of
        // leaving a row that opens onto nothing.
        this.$root.$emit('favorite-removed', 'email', this.id);
      });
  },
  methods: {
    open(event) {
      if (event?.which === 1 || event?.which === 2) {
        this.clickCallback?.('email', this.id);
      }
      // The mailbox owns the reader, so it is asked to open the conversation rather
      // than this drawer rendering a second, lesser copy of it.
      window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-email-box-mail', {
          detail: {mailRemoteId: this.email?.mailRemoteId},
        })));
    },
  },
};
</script>
