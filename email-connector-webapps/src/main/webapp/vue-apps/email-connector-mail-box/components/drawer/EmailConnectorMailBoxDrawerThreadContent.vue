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
    <v-list-item class="px-0 pb-1 height-auto">
      <v-list-item-content class="py-0 text-title text-wrap overflow-visible">
        <v-list-item-title class="text-wrap overflow-visible">
          {{ subject }}
        </v-list-item-title>
      </v-list-item-content>
    </v-list-item>
    <template v-for="(message, index) in messages">
      <v-divider
        v-if="index > 0"
        :key="`divider-${msgKey(message)}`"
        class="my-2" />
      <email-connector-mail-box-drawer-thread-message
        :key="msgKey(message)"
        :email="message"
        :expanded="expandedIds.includes(msgKey(message))"
        :collapsible="index !== messages.length - 1"
        :expanded-drawer="expandedDrawer"
        @expand="expand(msgKey(message))"
        @collapse="collapse(msgKey(message))" />
    </template>
  </v-list>
</template>

<script>
export default {
  data() {
    return {
      messages: [],
      expandedIds: [],
      loadingThread: false,
    };
  },
  props: {
    // The opened message (its threadId + subject anchor the conversation).
    email: {
      type: Object,
      default: () => null,
    },
    // The flat inbox list, used to find the sibling message ids of this thread.
    emails: {
      type: Array,
      default: () => [],
    },
    expandedDrawer: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    subject() {
      return this.email?.subject || this.$t('emailConnector.mailBox.list.drawer.noSubject');
    },
    // The message ids of this conversation, matching Phase 2's grouping key so the
    // reader and the collapsed list row agree on what a thread is.
    threadMailRemoteIds() {
      if (!this.email) {
        return [];
      }
      const key = this.threadKey(this.email);
      const ids = (this.emails || []).filter(e => this.threadKey(e) === key).map(e => e.mailRemoteId);
      return ids.length ? ids : [this.email.mailRemoteId];
    },
  },
  watch: {
    // Reload whenever a different conversation is opened.
    'email.mailRemoteId': {
      immediate: true,
      handler() {
        this.loadThread();
      },
    },
  },
  methods: {
    threadKey(email) {
      return email.threadId || email.mailHeaderId || String(email.mailRemoteId);
    },
    // A message is identified across the reader by its folder + IMAP UID: UIDs are
    // per-folder, so the same number can appear in INBOX and SENT/ARCHIVE.
    msgKey(message) {
      return `${message.folder || 'INBOX'}-${message.mailRemoteId}`;
    },
    /**
     * Loads the whole conversation across folders (INBOX + SENT + ARCHIVE) from the
     * server by thread id, so the user's own replies and previously-archived
     * messages show inline. Falls back to the opened message alone when it has no
     * thread id or the fetch yields nothing. Messages are stacked oldest first with
     * the newest expanded.
     *
     * @returns {void}
     */
    loadThread() {
      if (!this.email) {
        return;
      }
      this.loadingThread = true;
      const threadId = this.email.threadId;
      const promise = threadId
        ? this.$emailConnectorMailBoxService.getThreadByThreadId(threadId).catch(() => null)
        : Promise.resolve(null);
      promise
        .then(fetched => {
          const messages = (fetched && fetched.length ? fetched : [this.email])
            .filter(Boolean)
            .sort((first, second) => new Date(first.receivedDate) - new Date(second.receivedDate));
          this.messages = messages;
          const latest = messages[messages.length - 1];
          this.expandedIds = latest ? [this.msgKey(latest)] : [];
          this.markThreadRead();
        })
        .finally(() => this.loadingThread = false);
    },
    // Opening a conversation reads all of its messages, via the existing bulk endpoint.
    markThreadRead() {
      const unread = (this.emails || [])
        .filter(e => this.threadMailRemoteIds.includes(e.mailRemoteId) && !e.read)
        .map(e => e.mailRemoteId);
      if (unread.length) {
        this.$root.$emit('update-email-read-status', true, unread);
      }
    },
    expand(key) {
      if (!this.expandedIds.includes(key)) {
        this.expandedIds.push(key);
      }
    },
    collapse(key) {
      this.expandedIds = this.expandedIds.filter(id => id !== key);
    },
  },
};
</script>
