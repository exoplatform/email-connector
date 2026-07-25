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
    <!-- Assign the conversation to the add-on's email categories (Important / Invitation
         / Notification / To review) and show the ones already applied. -->
    <email-connector-mail-box-drawer-category-bar :emails="messages" />
    <!-- A thin progress bar while the archived tail is fetched in the background. -->
    <v-progress-linear
      v-if="loadingOlder"
      indeterminate
      height="2"
      class="my-1" />
    <template v-for="(item, index) in renderItems">
      <!-- No separator against the count badge on either side: the badge sits on a
           rule of its own, so a divider before it and another after it drew three
           lines where one was meant. -->
      <v-divider
        v-if="index > 0 && item.type !== 'bubble' && renderItems[index - 1].type !== 'bubble'"
        :key="`divider-${item.key}`"
        class="my-2" />
      <!-- A run of consecutive collapsed messages, folded into a single Gmail-style
           round count badge sitting on a divider line; click to reveal them as strips. -->
      <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
      <div
        v-if="item.type === 'bubble'"
        :key="item.key"
        class="clickable d-flex align-center py-2"
        style="position: relative;"
        tabindex="0"
        :aria-label="$t('emailConnector.mailBox.list.drawer.thread.showHidden', [item.count])"
        :title="$t('emailConnector.mailBox.list.drawer.thread.showHidden', [item.count])"
        @click="revealBubble(item)"
        @keydown.enter="revealBubble(item)"
        @keydown.space.prevent="revealBubble(item)">
        <!-- Two hairlines rather than one: a doubled rule reads as "something is folded
             here", where a single one reads as an ordinary separator between messages. -->
        <span
          class="d-block"
          style="position: absolute; left: 0; right: 0; top: 50%; height: 3px; border-top: 1px solid var(--v-borderColor, #e1e8ee); border-bottom: 1px solid var(--v-borderColor, #e1e8ee);"></span>
        <span
          class="d-block"
          style="position: absolute; left: 0; right: 0; top: 50%; border-top: 1px solid var(--v-borderColor, #e1e8ee);"></span>
        <span
          class="d-flex align-center justify-center rounded-circle text-caption text-light-color"
          style="position: relative; z-index: 1; width: 40px; height: 40px; border: 1px solid var(--v-borderColor, #e1e8ee); background-color: var(--v-surface-base, #fff);">
          {{ item.count }}
        </span>
      </div>
      <email-connector-mail-box-drawer-thread-message
        v-else
        :key="item.key"
        :email="item.message"
        :expanded="expandedIds.includes(item.key)"
        :collapsible="!isLast(item.message)"
        :expanded-drawer="expandedDrawer"
        @expand="expand(item.key)"
        @collapse="collapse(item.key)" />
    </template>
  </v-list>
</template>

<script>
// Messages kept visible at the tail of a long thread, in addition to the last one
// (which is expanded): matches Gmail showing the message just before the latest.
const TAIL_STRIPS = 1;

export default {
  data() {
    return {
      messages: [],
      expandedIds: [],
      revealedKeys: [],
      loadingThread: false,
      loadingOlder: false,
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
    // The reader's display list: each message shown on its own, except runs of
    // consecutive collapsed middle messages, which fold into one "bubble" item
    // carrying the hidden count (Gmail's round "N" badge).
    renderItems() {
      const items = [];
      let run = [];
      const flush = () => {
        if (!run.length) {
          return;
        }
        // A lone hidden message is cheaper to show as a strip than to hide behind a badge.
        if (run.length === 1) {
          items.push({ type: 'message', message: run[0], key: this.msgKey(run[0]) });
        } else {
          items.push({ type: 'bubble', count: run.length, keys: run.map(m => this.msgKey(m)), key: `bubble-${this.msgKey(run[0])}` });
        }
        run = [];
      };
      this.messages.forEach((message, index) => {
        if (this.isShown(index)) {
          flush();
          items.push({ type: 'message', message, key: this.msgKey(message) });
        } else {
          run.push(message);
        }
      });
      flush();
      return items;
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
    // The opened message's thread id, taken from the inbox list the drawer passed in
    // (kept in sync with the last mailbox refresh) rather than the per-message detail,
    // whose thread id can lag after a thread merge and then resolve to an empty
    // conversation — leaving the reader showing only the opened message.
    resolveThreadId() {
      const openedKey = this.msgKey(this.email);
      const listRow = (this.emails || []).find(e => this.msgKey(e) === openedKey);
      return (listRow && listRow.threadId) || this.email.threadId;
    },
    // A message is identified across the reader by its folder + IMAP UID: UIDs are
    // per-folder, so the same number can appear in INBOX and SENT/ARCHIVE.
    msgKey(message) {
      return `${message.folder || 'INBOX'}-${message.mailRemoteId}`;
    },
    isLast(message) {
      const last = this.messages[this.messages.length - 1];
      return !!last && this.msgKey(last) === this.msgKey(message);
    },
    // Which messages stay visible: the first, the last few (tail), any still-unread
    // one, and any the user revealed by clicking a badge. The rest fold into badges.
    isShown(index) {
      const total = this.messages.length;
      if (index === 0 || index >= total - 1 - TAIL_STRIPS) {
        return true;
      }
      const message = this.messages[index];
      return !message.read || this.revealedKeys.includes(this.msgKey(message));
    },
    /**
     * Loads the conversation in two passes so the drawer never blocks on IMAP:
     * first the cached thread across folders (fast, pure DB), rendered immediately;
     * then, in the background, the archived tail from the provider's All Mail, merged
     * in when it arrives. Falls back to the opened message alone when it has no thread
     * id or the fetch yields nothing. Messages are stacked oldest first, newest open.
     *
     * @returns {void}
     */
    loadThread() {
      if (!this.email) {
        return;
      }
      this.revealedKeys = [];
      const threadId = this.resolveThreadId();
      this.loadingThread = true;
      const cached = threadId
        ? this.$emailConnectorMailBoxService.getThreadByThreadId(threadId).catch(() => null)
        : Promise.resolve(null);
      cached
        .then(fetched => {
          this.applyMessages(fetched);
          this.markThreadRead();
        })
        .finally(() => {
          this.loadingThread = false;
          this.completeInBackground(threadId);
        });
    },
    // Second pass: pull the archived tail from All Mail without blocking the open.
    completeInBackground(threadId) {
      if (!threadId) {
        return;
      }
      this.loadingOlder = true;
      this.$emailConnectorMailBoxService.completeThreadByThreadId(threadId)
        .then(completed => {
          // Only re-render if completion actually recovered more messages.
          if (completed && completed.length > this.messages.length) {
            this.applyMessages(completed);
          }
        })
        .catch(() => { /* best-effort: keep the cached thread on failure */ })
        .finally(() => this.loadingOlder = false);
    },
    // Normalize a fetched thread into the reader's state: dedupe by Message-ID, sort
    // oldest first, keep the latest message expanded.
    applyMessages(fetched) {
      const sorted = (fetched && fetched.length ? fetched : [this.email])
        .filter(Boolean)
        .sort((first, second) => new Date(first.receivedDate) - new Date(second.receivedDate));
      const messages = this.dedupeByHeader(sorted);
      this.messages = messages;
      const latest = messages[messages.length - 1];
      this.expandedIds = latest ? [this.msgKey(latest)] : [];
    },
    // The same message can be present in more than one folder (e.g. a provider
    // whose Archive/All-Mail overlaps the inbox), so show it once, preferring the
    // INBOX copy. Messages without a Message-ID are always kept.
    dedupeByHeader(messages) {
      const priority = { INBOX: 0, SENT: 1, ARCHIVE: 2, ALL_MAIL: 3 };
      const rank = message => (message.folder in priority ? priority[message.folder] : 9);
      const seen = new Map();
      const deduped = [];
      messages.forEach(message => {
        const header = message.mailHeaderId;
        if (!header) {
          deduped.push(message);
          return;
        }
        const existing = seen.get(header);
        if (!existing) {
          seen.set(header, message);
          deduped.push(message);
        } else if (rank(message) < rank(existing)) {
          deduped.splice(deduped.indexOf(existing), 1, message);
          seen.set(header, message);
        }
      });
      return deduped;
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
    // Reveal a folded run: its messages render as individual strips from now on.
    revealBubble(bubble) {
      this.revealedKeys = this.revealedKeys.concat(bubble.keys);
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
