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
  <div>
    <email-connector-mail-box-drawer-list-item
      v-for="thread in renderedThreads"
      :key="thread.threadId"
      :email="thread.latest"
      :thread="thread"
      :opened-email-id="openedEmailId"
      :emails="emails"
      :webmail-url="webmailUrl"
      :sync-in-progress="syncInProgress"
      :selected-emails="selectedEmails"
      :select-mode="selectMode"
      :expanded="expanded" />
    <!-- Sits directly under the last rendered row: scrolling it into view is what asks
         for the next slice. An empty div rather than a "load more" button, because the
         mail is already in memory — there is nothing to fetch and nothing for the user
         to decide, so a button would only put a stop where the list used to flow. -->
    <div
      v-if="hasMoreThreads"
      v-intersect="revealMoreThreads"></div>
  </div>
</template>

<script>
// How many threads are built on the first paint, and how many more each time the bottom
// of the list is reached. Comfortably more than a drawer's height on any screen, so the
// window is never the reason a scroll stops, and small enough that opening the drawer
// costs a screenful of components rather than a mailbox of them.
const THREAD_RENDER_PAGE_SIZE = 40;

export default {
  data() {
    return {
      openedEmailId: this.currentEmail?.mailRemoteId,
      renderedThreadCount: THREAD_RENDER_PAGE_SIZE,
    };
  },
  props: {
    emails: {
      type: Array,
      default: () => [],
    },
    selectMode: {
      type: Boolean,
      default: false,
    },
    selectedEmails: {
      type: Array,
      default: () => [],
    },
    expanded: {
      type: Boolean,
      default: false,
    },
    syncInProgress: {
      type: Boolean,
      default: false,
    },
    webmailUrl: {
      type: String,
      default: null,
    },
    currentEmail: {
      type: Object,
      default: () => null,
    }
  },
  computed: {
    threads() {
      return this.$emailConnectorMailBoxService.groupEmailsByThread(this.emails);
    },
    /**
     * The threads actually built as components — the head of the list, grown by
     * scrolling. Everything else stays in `threads`, so the drawer's own search and its
     * select-all keep reading the whole cached mailbox: this windows the rendering, not
     * the data.
     *
     * @returns {Array} the threads to render
     */
    renderedThreads() {
      return this.threads.slice(0, this.renderedThreadCount);
    },
    /**
     * Whether any thread is still unrendered, and so whether the sentinel below the list
     * is worth carrying at all.
     *
     * @returns {boolean} true while threads remain
     */
    hasMoreThreads() {
      return this.threads.length > this.renderedThreadCount;
    },
  },
  created() {
    this.$root.$on('set-opened', (mailRemoteId) => {
      this.openedEmailId = mailRemoteId;
    });
  },
  methods: {
    /**
     * Grows the window by one page when the sentinel comes into view.
     * <p>
     * The count only ever rises, and is deliberately not reset when the list changes.
     * A refresh of the mailbox — the drawer re-requests it while categorization is
     * settling — would otherwise yank a reader back to the top of a list they had
     * scrolled down. Filtering needs no reset either: the window is a ceiling, and a
     * search narrower than it renders whole.
     *
     * @param {Array} entries the intersection entries
     * @param {IntersectionObserver} observer the observer
     * @param {boolean} isIntersecting whether the sentinel is on screen
     * @returns {void}
     */
    revealMoreThreads(entries, observer, isIntersecting) {
      if (isIntersecting) {
        this.renderedThreadCount += THREAD_RENDER_PAGE_SIZE;
      }
    },
  },
};
</script>