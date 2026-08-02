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
    id="emailBoxDrawer"
    ref="emailBoxDrawer"
    v-model="emailBoxDrawer"
    right
    allow-expand
    @expand-updated="updateExpand"
    :loading="loading"
    :use-filter="canSearch"
    :filter-placeholder="$t('emailConnector.mailBox.search.placeholder')"
    @filter-updated="onFilterUpdated"
    :confirm-close="activeDownload"
    :go-back-button="canGoBack"
    :confirm-close-labels="{
      title: $t('emailConnector.mailBox.attachment.download.confirmAbort.title'),
      message: $t('emailConnector.mailBox.attachment.download.confirmAbort.message'),
      ok: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.yes'),
      cancel: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.no')
    }"
    @go-back="cancelSelectMode"
    @confirm-close="onAbortDownloadConfirmed"
    @closed="close"
    style="outline: none;"
    class="no-box-shadow">
    <template #title>
      <div v-if="!hasFullAppLeft" :class="{ 'd-flex align-center': selectMode }">
        <span :class="{ 'text-body': selectMode }">
          {{ title }}
        </span>
      </div>
      <div v-else>
        <span></span>
      </div>
    </template>
    <template v-if="hasFullAppLeft" #fullAppLeftTitle>
      <v-btn
        v-if="selectMode"
        icon
        @click="cancelSelectMode">
        <v-icon size="20">
          {{ $vuetify.rtl && 'fa fa-arrow-right' || 'fa fa-arrow-left' }}
        </v-icon>
      </v-btn>
      <div class="d-flex align-center justify-space-between width-full">
        <span :class="{ 'text-body': selectMode }">
          {{ title }}
        </span>
        <email-connector-mail-box-drawer-actions
          :emails="emails"
          class="d-flex align-center"
          :webmail-url="webmailUrl"
          :selected-emails="selectedEmails"
          :select-mode="selectMode"
          :current-folder="currentFolder"
          :available-folders="availableFolders"
          :favorite-only="favoriteOnly"
          :sync-in-progress="syncInProgress" />
      </div>
    </template>
    <template #titleIcons>
      <div v-if="hasFullAppLeft">
        <email-connector-mail-box-drawer-list-item-detail-actions
          v-if="email && !selectEmailPlaceHolder"
          :email="email" />
      </div> 
      <email-connector-mail-box-drawer-actions
        v-else-if="!syncBlocked"
        class="d-flex align-center"
        :emails="emails"
        :webmail-url="webmailUrl"
        :selected-emails="selectedEmails"
        :select-mode="selectMode"
        :current-folder="currentFolder"
        :available-folders="availableFolders"
        :favorite-only="favoriteOnly"
        :sync-in-progress="syncInProgress" />
    </template>
    <template v-if="hasFullAppLeft" #fullAppLeftContent>
      <email-connector-mail-box-drawer-search-results
        v-if="searchActive"
        :results="mergedSearchResults"
        :total-matches="searchTotalMatches"
        :server-searching="searchServerRunning"
        :server-error="searchServerError"
        @open-result="openSearchResult" />
      <template v-else>
        <categories-filter
          v-model="selectedCategoryId"
          :category-ids="emailCategoryIds"
          class="full-width border-box-sizing application-border application-border-radius py-3 px-3"
          object-type="email"
          scrollable
          hide-on-empty />
        <email-connector-mail-box-drawer-content
          :emails="emails"
          :email="email"
          :selected-emails="selectedEmails"
          :select-mode="selectMode"
          :indeterminate="indeterminate"
          expanded
          @update:selected-emails="selectedEmails = $event" />
      </template>
    </template>
    <template v-if="emailBoxDrawer && !loading" #content>
      <v-list-item v-if="syncBlocked" class="full-height align-center">
        <v-list-item-content>
          <v-icon
            size="60"
            class="orange--text text--darken-2">
            fas fa-exclamation-triangle
          </v-icon>
          <v-list-item-title class="text-wrap mt-5 mb-0">
            {{ $t('emailConnector.mailBox.list.drawer.sync.blocked.reconnect') }}
          </v-list-item-title>
          <div class="mt-8">
            <v-btn
              @click="checkSetting"
              class="btn btn-primary body-2">
              {{ $t('emailConnector.mailBox.list.drawer.sync.blocked.checkSetting') }}
            </v-btn>
          </div>
        </v-list-item-content>
      </v-list-item>
      <email-connector-mail-box-drawer-search-results
        v-else-if="searchActive && !expanded"
        :results="mergedSearchResults"
        :total-matches="searchTotalMatches"
        :server-searching="searchServerRunning"
        :server-error="searchServerError"
        @open-result="openSearchResult" />
      <template v-else>
        <categories-filter
          v-if="!expanded"
          v-model="selectedCategoryId"
          :category-ids="emailCategoryIds"
          class="full-width border-box-sizing application-border application-border-radius py-3 px-3"
          object-type="email"
          scrollable
          hide-on-empty />
        <template v-if="hasEmails">
          <template v-if="expanded">
            <email-connector-mail-box-drawer-multi-select-email
              v-if="selectMode"
              :emails="emails"
              :selected-emails="selectedEmails" />
            <email-connector-mail-box-drawer-select-email v-else-if="selectEmailPlaceHolder" />
            <email-connector-mail-box-drawer-thread-content
              v-else
              :email="email"
              :emails="emails"
              expanded-drawer />
          </template>
          <email-connector-mail-box-drawer-content
            v-else
            :emails="emails"
            :selected-emails="selectedEmails"
            :select-mode="selectMode" 
            :indeterminate="indeterminate"
            :sync-in-progress="syncInProgress"
            :webmail-url="webmailUrl"
            @update:selected-emails="selectedEmails = $event" />
        </template>
        <email-connector-mail-box-drawer-no-email v-else />
      </template>
    </template>
  </exo-drawer>
</template>

<script>
// Categorization runs after the sync reports done, in batches, and a large mailbox takes
// several minutes. Two numbers govern how long the drawer keeps watching for the results.

// Give up after this long even if categories are still arriving, so a mailbox with
// categorization switched off never polls indefinitely.
const CATEGORY_WATCH_MAX_MS = 600000;

// Stop once nothing new has landed for this many consecutive polls (2s each, so ~60s).
// It has to comfortably exceed the gap between two batches finishing: at three polls the
// first lull between batches looked like the end and the drawer stopped after one batch.
const CATEGORY_WATCH_QUIET_POLLS = 30;

// How long typing must pause before the whole-mailbox server search fires; the
// instant local matches don't wait for it.
const SEARCH_DEBOUNCE_MS = 400;

// How many server hits to page in (the server returns the newest matches).
const SEARCH_PAGE_SIZE = 20;

// A fetch refused mid-sync (HTTP 409) is retried once after this pause; the sync
// window it collides with is short-lived.
const SEARCH_FETCH_RETRY_MS = 3000;

export default {
  data() {
    return {
      emailBoxDrawer: false,
      emailBox: null,
      loading: false,
      syncInProgress: false,
      categoryWatchDeadline: null,
      lastCategoryCount: 0,
      stableCategoryPolls: 0,
      webmailUrl: null,
      refreshInterval: null,
      activeDownload: null,
      selectedEmails: [],
      selectMode: false,
      expanded: false,
      email: null,
      // A mail opened from outside the mailbox (the global Favorites drawer) is
      // pinned open: it is legitimately absent from the listed window, and a list
      // reload must not take the reader back from the user. Cleared as soon as they
      // open something themselves, or close the drawer.
      pinnedEmail: false,
      selectEmailPlaceHolder: false,
      selectedCategoryId: null,
      selectedCategoryIds: [],
      emailCategoryIds: [],
      deletedEmailIds: [],
      archivedEmailIds: [],
      currentFolder: 'INBOX',
      // The favorite view: list only the messages carrying the mail server's
      // \Flagged flag, in the listed folder. Toggled from the ⋮ menu.
      favoriteOnly: false,
      searchTerm: '',
      searchServerResults: [],
      searchTotalMatches: 0,
      searchServerRunning: false,
      searchServerError: false,
      searchRequestId: 0,
      searchOpening: false
    };
  },
  created() {
    this.isRefreshing = false;
    // Plain instance field: a pending timeout id needs no reactivity.
    this.searchDebounceTimer = null;
    // The add-on's own email categories are the children of the Inbox category; feeding
    // their ids to the platform filter makes it open "inside the Inbox" — showing those
    // children directly as chips, rather than an Inbox parent the user must drill into.
    // The promise is kept so open() can await the ids before trusting a stored default view.
    this.emailCategoryIdsPromise = this.$emailConnectorMailBoxService.getAvailableEmailCategories()
      .then(list => this.emailCategoryIds = (list || []).map(category => category.id));
    this.$root.$on('switch-folder', this.onSwitchFolder);
    this.$root.$on('toggle-favorite-filter', this.onToggleFavoriteFilter);
    this.$root.$on('update-email-favorite-status', this.onUpdateEmailFavoriteStatus);
    this.$root.$on('apply-email-favorite-status', this.applyEmailsFavoriteStatus);
    this.onOpenEmailDetailContent = (mailRemoteId) => {
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return; 
      }
      this.openEmailDetailContent(mailRemoteId);
    };
    this.onUpdateEmailReadStatus = (read, emails) => {
      this.updateEmailsReadStatus(read, emails);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return; 
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.onDeleteEmail = (emails) => {
      this.deleteEmails(emails);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return; 
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.onArchiveEmail = (emails) => {
      this.archiveEmails(emails);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return; 
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.$root.$on('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$on('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$on('delete-email', this.onDeleteEmail);
    this.$root.$on('archive-email', this.onArchiveEmail);
    this.$root.$on('email-categories-updated', this.onCategoriesUpdated);
    this.$root.$on('open-email-detail-drawer', () => {
      this.email = null;
    });
    // Opening the mailbox, optionally straight onto one message — that is how the
    // global Favorites drawer hands a mail over. The payload used to be the plain
    // "loading" flag and callers still pass it that way, so an object is what marks
    // the richer form: passing one where a flag is expected would read as truthy
    // and wrongly show the drawer as synchronizing.
    this.$root.$on('open-mail-box-drawer', async (payload) => {
      const options = payload && typeof payload === 'object' ? payload : {loading: payload};
      // The message goes up first and the folder loads behind it. Opening the mailbox
      // first meant a favorited mail waited on the whole inbox — a thousand messages
      // fetched before its own single one — which felt slow for something the user
      // asked for by name. The narrow reader is a drawer of its own, mounted beside
      // this one, so it can show while this list is still empty; the wide reader is a
      // pane of this drawer and only renders once the folder is there, so that one
      // still waits.
      const readerFirst = options.mailRemoteId && !this.expanded;
      if (readerFirst) {
        this.openMailFromOutside(options.mailRemoteId);
      }
      await this.open(options.loading);
      if (options.searchTerm) {
        this.openSearchFromOutside(options.searchTerm);
      }
      if (readerFirst) {
        // Hand the reader the conversation and webmail link it opened without.
        this.$root.$emit('email-detail-context', {
          emails: this.emails,
          syncInProgress: this.syncInProgress,
          webmailUrl: this.webmailUrl,
        });
      } else if (options.mailRemoteId) {
        this.openMailFromOutside(options.mailRemoteId);
      }
    });
    this.$root.$on('attachment-download-started', (payload) => {
      this.activeDownload = payload;
    });
    this.$root.$on('attachment-download-finished', () => {
      this.activeDownload = null;
    });
    this.$root.$on('select-email', ({ emailId, selected }) => {
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return;
      }
      this.selectMode = true;
      if (selected) {
        if (!this.selectedEmails.includes(emailId)) {
          this.selectedEmails.push(emailId);
        }
      }
      else {
        this.selectedEmails = this.selectedEmails.filter(id => id !== emailId);
      }
    });
    this.$root.$on('synchronize-in-progress', () => {
      this.syncInProgress = true;
      this.startAutoRefresh();
    });
  },
  beforeDestroy() {
    this.$root.$off('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$off('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$off('delete-email', this.onDeleteEmail);
    this.$root.$off('archive-email', this.onArchiveEmail);
    this.$root.$off('email-categories-updated', this.onCategoriesUpdated);
    this.$root.$off('switch-folder', this.onSwitchFolder);
    this.$root.$off('toggle-favorite-filter', this.onToggleFavoriteFilter);
    this.$root.$off('update-email-favorite-status', this.onUpdateEmailFavoriteStatus);
    this.$root.$off('apply-email-favorite-status', this.applyEmailsFavoriteStatus);
  },
  computed: {
    hasEmails() {
      return this.emails?.length > 0;
    },
    // INBOX plus any of SENT/ARCHIVE that actually hold mail, for the ⋮ folder switch.
    availableFolders() {
      const counts = this.emailBox?.folderCounts || {};
      return ['INBOX', 'SENT', 'ARCHIVE'].filter(folder => folder === 'INBOX' || counts[folder] > 0);
    },
    syncBlocked() {
      return this.emailBox?.emailSyncStatus === 'BLOCKED';
    },
    title() {
      if (!this.selectMode) {
        let title = this.$t('emailConnector.mailBox.list.drawer.title');
        if (this.currentFolder === 'SENT') {
          title = `${title} · ${this.$t('emailConnector.mailBox.list.drawer.folder.sent')}`;
        } else if (this.currentFolder === 'ARCHIVE') {
          title = `${title} · ${this.$t('emailConnector.mailBox.list.drawer.folder.archive')}`;
        }
        // The favorite view reads as one more folder-like narrowing of the list.
        if (this.favoriteOnly) {
          title = `${title} · ${this.$t('emailConnector.mailBox.list.drawer.folder.favorites')}`;
        }
        return title;
      }
      return `${this.selectedEmails.length} ${this.selectedEmails.length === 1 ?
        this.$t('emailConnector.mailBox.list.drawer.emailSelected') :
        this.$t('emailConnector.mailBox.list.drawer.emailsSelected')}`;
    },
    indeterminate() {
      return this.selectedEmails.length > 0 && this.selectedEmails.length < this.emails.length; 
    },
    hasFullAppLeft() {
      return this.expanded && (this.hasEmails || this.selectedCategoryId || this.searchActive) && !this.syncBlocked;
    },
    // The header filter is the platform's own (exo-drawer); it hides the go-back
    // button, so it steps aside while select mode needs that button.
    canSearch() {
      return !this.syncBlocked && !this.selectMode;
    },
    searchActive() {
      return !!this.searchTerm;
    },
    // Instant matches from the emails the app already holds (the whole cached
    // window of the current folder), on the same fields the server searches —
    // subject and sender — so the instant list and the final one agree.
    localSearchMatches() {
      const term = this.searchTerm.toLowerCase();
      if (!term) {
        return [];
      }
      return (this.emailBox?.emails || [])
        .filter(e => (e.subject || '').toLowerCase().includes(term)
          || (e.sender?.name || '').toLowerCase().includes(term)
          || (e.sender?.address || '').toLowerCase().includes(term))
        .map(e => ({
          mailRemoteId: e.mailRemoteId,
          folder: e.folder || this.currentFolder,
          subject: e.subject,
          sender: e.sender,
          receivedDate: e.receivedDate,
          read: e.read,
          // The server reports the flag on its own hits, and merges last, so a
          // favorite set from another mail client wins over this cached copy.
          starred: e.starred,
          cached: true,
          // Kept because the rows double as the reader's list, whose category
          // filter dereferences categoryIds on every row.
          categoryIds: e.categoryIds || [],
        }));
    },
    // Local matches shown instantly, server hits MERGED in when they land — never
    // replacing: the server returns only the newest matches, so with many hits a
    // result the user is already reading could vanish under a replacement. Keyed
    // on (folder, uid), server fields winning.
    mergedSearchResults() {
      const merged = new Map();
      this.localSearchMatches.forEach(result => merged.set(`${result.folder}:${result.mailRemoteId}`, result));
      this.searchServerResults.forEach(result => {
        const key = `${result.folder}:${result.mailRemoteId}`;
        // Server hits carry no categoryIds; default them for the reader's list.
        merged.set(key, { categoryIds: [], ...merged.get(key), ...result });
      });
      return Array.from(merged.values())
        .sort((first, second) => new Date(second.receivedDate) - new Date(first.receivedDate));
    },
    canGoBack() {
      return this.selectMode && !this.expanded;
    },
    emails() {
      let emails = this.emailBox?.emails || [];
      emails = emails.filter(e => !this.deletedEmailIds.includes(e.mailRemoteId));
      emails = emails.filter(e => !this.archivedEmailIds.includes(e.mailRemoteId));
      // The favorite view: the server already answered with the favorite subset;
      // filtering again here makes a just-unfavorited message leave the list at
      // once instead of waiting for the next reload.
      if (this.favoriteOnly) {
        emails = emails.filter(e => e.starred);
      }
      if (this.selectedCategoryIds.length > 0) {
        emails = emails.filter(e => this.selectedCategoryIds.some(id => e.categoryIds.includes(id)));
      }
      return emails;
    }
  },
  watch: {
    async selectedCategoryId(val) {
      this.cancelSelectMode();
      this.selectedCategoryIds = val && await this.$emailConnectorMailBoxService.getSubcategoryIds(val) || [];
    },
    emails() {
      // A search hit, or a mail opened from the Favorites drawer, is often outside
      // the listed folder view; a background refresh must not knock it out for the
      // placeholder.
      if (this.searchActive || this.pinnedEmail) {
        return;
      }
      if (this.email && !this.emails.some(e => e.mailRemoteId === this.email.mailRemoteId)) {
        this.selectEmailPlaceHolder = true;
      }
    },
    selectEmailPlaceHolder() {
      if (this.selectEmailPlaceHolder) {
        this.$root.$emit('set-opened', null);
      }
    },
    selectMode() {
      if (this.selectMode) {
        this.$root.$emit('set-opened', null);
      }
      else if (!this.selectEmailPlaceHolder) {
        this.$root.$emit('set-opened', this.email.mailRemoteId);
      }
    }
  },
  methods: {
    async open(loading) {
      if (loading) {
        this.syncInProgress = true;
        await this.$nextTick();
      }
      // Always (re)open on the inbox, without a leftover search or favorite view.
      this.currentFolder = 'INBOX';
      this.favoriteOnly = false;
      this.clearSearch();
      this.loading = true;
      this.emailBoxDrawer = true;
      await this.loadEmailBox();
      this.loading = false;
      // Position the inbox on the user's chosen default category view (if any). The stored
      // view can point at a category that no longer exists (e.g. a removed default), and
      // filtering on it would reject — so apply it only once the available category ids are
      // loaded and the stored id is one of them.
      this.$emailConnectorCommonService.getUserEmailSetting()
        .then(async setting => {
          const defaultView = setting && setting.defaultCategoryView || null;
          await this.emailCategoryIdsPromise;
          this.selectedCategoryId = defaultView && this.emailCategoryIds.includes(defaultView) ? defaultView : null;
        })
        .catch(() => {
          // Keep the inbox unfiltered when the setting cannot be read.
        });
      if (this.syncInProgress) {
        this.startAutoRefresh();
      }
    },
    openEmailDetailContent(mailRemoteId) {
      // Opening from the list is the user choosing again: whatever was pinned open
      // from elsewhere gives way to it.
      this.pinnedEmail = false;
      this.loading = true;
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId).then((email) => {
        this.updateEmailsReadStatus(true, [mailRemoteId]);
        this.email = email;
        this.selectEmailPlaceHolder = false;
      }).finally(() => {
        this.loading = false;
      });
    },
    /**
     * Opens the mailbox on a search someone started elsewhere — today, in the
     * platform's unified search, which only looks at the locally held mail.
     *
     * The term is put in the drawer's own filter field rather than searched
     * silently: the user sees what is being searched, can refine it, and lands in
     * the field that reaches the whole mailbox, which is the point of coming here.
     *
     * @param {String} term the text to search for
     * @returns {void}
     */
    openSearchFromOutside(term) {
      const drawer = this.$refs.emailBoxDrawer;
      if (drawer) {
        drawer.showFilter = true;
        drawer.filterText = term;
      }
      this.runSearch(term);
    },
    /**
     * Opens one message picked outside the mailbox — today, from the platform's
     * global Favorites drawer.
     *
     * It goes through the same two doors as a search hit, and for the same reason:
     * such a message is usually not in the listed window. Wide, the reader is a pane
     * of this drawer, so the message is set here and pinned against the next list
     * refresh; narrow, the reader is a drawer of its own, which has to be told to
     * open — setting the message here would only have changed a pane that the narrow
     * layout never shows, leaving the user looking at the list.
     *
     * @param {Number} mailRemoteId the IMAP UID of the message to open
     * @returns {Promise} resolved once the message is on screen
     */
    async openMailFromOutside(mailRemoteId) {
      if (!this.expanded) {
        this.$root.$emit('open-email-detail-drawer', mailRemoteId, this.emails, this.syncInProgress, this.webmailUrl, true);
        return;
      }
      this.loading = true;
      try {
        this.pinnedEmail = true;
        this.email = await this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId);
        this.selectEmailPlaceHolder = false;
        this.$root.$emit('set-opened', mailRemoteId);
      } finally {
        this.loading = false;
      }
    },
    onAbortDownloadConfirmed() {
      this.$root.$emit('abort-download-attachment', this.activeDownload.mailRemoteId, this.activeDownload.attachmentRemoteId, this.activeDownload.abortController);
      this.close();
    },
    canDisplaySelectEmailPlaceHolder(emails) {
      return this.expanded && (!this.email || emails.includes(this.email.mailRemoteId));
    },
    // The drawer's header filter field emitted a new value: instant local matches
    // apply as soon as the debounce elapses, and the server search runs alongside.
    // Clearing the field returns to the normal folder view at once.
    onFilterUpdated(text) {
      window.clearTimeout(this.searchDebounceTimer);
      const term = (text || '').trim();
      if (!term) {
        this.clearSearch();
        return;
      }
      this.searchDebounceTimer = window.setTimeout(() => this.runSearch(term), SEARCH_DEBOUNCE_MS);
    },
    runSearch(term) {
      this.searchTerm = term;
      this.cancelSelectMode();
      this.runServerSearch();
    },
    // The whole-mailbox search (IMAP SEARCH on the server). The request id guards
    // against out-of-order answers: only the latest term's response may land.
    runServerSearch() {
      const requestId = ++this.searchRequestId;
      this.searchServerRunning = true;
      this.searchServerError = false;
      this.$emailConnectorMailBoxService.searchEmails(this.searchTerm, this.currentFolder, SEARCH_PAGE_SIZE)
        .then(page => {
          if (requestId !== this.searchRequestId) {
            return;
          }
          this.searchServerResults = page?.results || [];
          this.searchTotalMatches = page?.totalMatches || 0;
        })
        .catch(() => {
          if (requestId !== this.searchRequestId) {
            return;
          }
          // The instant local matches stay listed; only flag that the whole-mailbox
          // pass could not run.
          this.searchServerError = true;
          this.searchServerResults = [];
          this.searchTotalMatches = 0;
        })
        .finally(() => {
          if (requestId === this.searchRequestId) {
            this.searchServerRunning = false;
          }
        });
    },
    clearSearch() {
      window.clearTimeout(this.searchDebounceTimer);
      // Invalidate any in-flight server answer.
      this.searchRequestId++;
      this.searchTerm = '';
      this.searchServerResults = [];
      this.searchTotalMatches = 0;
      this.searchServerRunning = false;
      this.searchServerError = false;
    },
    // Open one search hit: a cached one goes straight to the existing reader; an
    // uncached one is first pulled into the cache through the fetch endpoint.
    async openSearchResult(result) {
      if (this.searchOpening) {
        return;
      }
      this.searchOpening = true;
      this.loading = true;
      try {
        if (!result.cached) {
          await this.fetchSearchedEmail(result);
        }
        this.markResultOpened(result);
        if (this.expanded) {
          const email = await this.$emailConnectorMailBoxService.getEmailByRemoteId(result.mailRemoteId, result.folder);
          this.email = email;
          this.selectEmailPlaceHolder = false;
          this.$root.$emit('set-opened', result.mailRemoteId);
        } else {
          this.$root.$emit('open-email-detail-drawer', result.mailRemoteId, this.mergedSearchResults, this.syncInProgress, this.webmailUrl, true);
        }
      } catch (error) {
        // A fetch refused because a synchronization is running (already retried
        // once) is a "one moment", not an error.
        const syncing = error?.status === 409;
        document.dispatchEvent(new CustomEvent('alert-message', {detail: {
          alertType: syncing ? 'warning' : 'error',
          alertMessage: this.$t(syncing ? 'emailConnector.mailBox.search.syncInProgress' : 'emailConnector.mailBox.search.openError'),
        }}));
      } finally {
        this.loading = false;
        this.searchOpening = false;
      }
    },
    // Pull an uncached hit into the local cache. A 409 means a synchronization
    // holds the mailbox for a moment — deliberately, to keep duplicate rows out —
    // so wait briefly and retry once before giving up.
    fetchSearchedEmail(result, retried) {
      return this.$emailConnectorMailBoxService.fetchSearchedEmail(result.mailRemoteId, result.folder)
        .catch(error => {
          if (error?.status === 409 && !retried) {
            return new Promise(resolve => window.setTimeout(resolve, SEARCH_FETCH_RETRY_MS))
              .then(() => this.fetchSearchedEmail(result, true));
          }
          throw error;
        });
    },
    // Reflect an open on the hit's own row: it is now cached, and read.
    markResultOpened(result) {
      const serverRow = this.searchServerResults
        .find(row => row.mailRemoteId === result.mailRemoteId && row.folder === result.folder);
      if (serverRow) {
        this.$set(serverRow, 'cached', true);
        this.$set(serverRow, 'read', true);
      }
    },
    close() {
      this.pinnedEmail = false;
      this.categoryWatchDeadline = null;
      this.stopAutoRefresh();
      this.clearSearch();
      // Also empty the drawer's own header filter field for the next open.
      this.$refs.emailBoxDrawer?.resetFilter?.();
      document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
      this.cancelSelectMode();
      this.selectEmailPlaceHolder = false;
      this.email = null;
      this.emailBoxDrawer = false;
      this.favoriteOnly = false;
      this.selectedCategoryId = null;
      this.selectedCategoryIds = [];
      this.deletedEmailIds = [];
      this.archivedEmailIds = [];
    },
    checkSetting() {
      this.$root.$emit('open-user-setting-drawer');
    },
    updateEmailsReadStatus(read, emailIds = []) {
      const emailIdsToUpdate = emailIds.filter(id => {
        const email = this.emails.find(e => e.mailRemoteId === id);
        if (email && email.read !== read) {
          this.$set(email, 'read', read);
          return true;
        }
        return false;
      });
      if (emailIdsToUpdate.length > 0) {
        this.$emailConnectorMailBoxService.updateEmailsReadStatus(
          emailIdsToUpdate,
          read
        );
      }
    },
    // Toggle the favorite-only view from the ⋮ menu, reloading the listed folder.
    onToggleFavoriteFilter() {
      this.favoriteOnly = !this.favoriteOnly;
      this.cancelSelectMode();
      this.loading = true;
      this.loadEmailBox().finally(() => this.loading = false);
    },
    // Patch the favorite flag on every copy this drawer holds: the cached folder
    // window (which the list rows and the search's local matches derive from),
    // the server search hits, and the opened message. No service call here —
    // this is also how a refused push is rolled back visually.
    applyEmailsFavoriteStatus(favorite, emailIds = []) {
      const ids = new Set(emailIds);
      (this.emailBox?.emails || []).forEach(email => {
        if (ids.has(email.mailRemoteId)) {
          this.$set(email, 'starred', favorite);
        }
      });
      // Server hits carry no favorite flag; stamping the toggled ones keeps the
      // search list truthful once the user favorites a result. INBOX rows only:
      // UIDs are per-folder, so the same number elsewhere is another message.
      this.searchServerResults.forEach(result => {
        if ((result.folder || 'INBOX') === 'INBOX' && ids.has(result.mailRemoteId)) {
          this.$set(result, 'starred', favorite);
        }
      });
      if (this.email && ids.has(this.email.mailRemoteId) && (this.email.folder || 'INBOX') === 'INBOX') {
        this.$set(this.email, 'starred', favorite);
      }
    },
    // Favorite/unfavorite messages: optimistic locally, then pushed to the mail server
    // (it is the server's own \Flagged flag). The push's outcome is REAL, not
    // assumed: the answer counts the messages the server refused — the backend
    // already reverted those in its cache, so the interface must not leave
    // their favorite lit either, or the next synchronization silently takes it
    // away after the user believed the message was favorite.
    onUpdateEmailFavoriteStatus(favorite, emailIds = []) {
      if (!emailIds.length) {
        return;
      }
      this.applyEmailsFavoriteStatus(favorite, emailIds);
      this.$emailConnectorMailBoxService.updateEmailsFavoriteStatus(emailIds, favorite)
        .then(result => {
          const failedUpdates = result?.failedUpdates ?? 0;
          if (failedUpdates > 0) {
            this.onFavoriteUpdateFailed(favorite, emailIds, failedUpdates);
          }
        })
        .catch(() => this.onFavoriteUpdateFailed(favorite, emailIds, emailIds.length));
    },
    // Reflect a refused push. When everything failed (or the lone message did),
    // the exact set to roll back is known: broadcast the revert so every copy —
    // list rows, reader, detail drawer — flips back. When only part of a bulk
    // toggle failed, the server does not say which ones, but its cache is
    // already truthful: reload the listed window from it.
    onFavoriteUpdateFailed(favorite, emailIds, failedUpdates) {
      if (failedUpdates >= emailIds.length) {
        this.$root.$emit('apply-email-favorite-status', !favorite, emailIds);
      } else {
        this.loadEmailBox();
      }
      const errorKey = favorite
        ? (failedUpdates === 1 && 'emailConnector.mailBox.list.drawer.addFavorite.email.error' || 'emailConnector.mailBox.list.drawer.addFavorite.emails.error')
        : (failedUpdates === 1 && 'emailConnector.mailBox.list.drawer.removeFavorite.email.error' || 'emailConnector.mailBox.list.drawer.removeFavorite.emails.error');
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {
        alertType: 'error',
        alertMessage: this.$t(errorKey, { 0: failedUpdates }),
      }}));
    },
    deleteEmails(emailIdsToDelete = []) {
      this.deletedEmailIds.push(...emailIdsToDelete);
      if (emailIdsToDelete.length > 0) {
        this.$emailConnectorMailBoxService.deleteEmails(emailIdsToDelete)
          .then((deleteResult) => {
            if ((deleteResult.failedDeletions ?? 0) > 0) {
              const alertMessage = this.$t(deleteResult.failedDeletions === 1 ? 'emailConnector.mailBox.list.drawer.delete.email.error' : 'emailConnector.mailBox.list.drawer.delete.emails.error', {
                0: deleteResult.failedDeletions,
              });
              document.dispatchEvent(new CustomEvent('alert-message', {detail: {
                alertType: 'error',
                alertMessage: alertMessage,
                alertLinkText: this.$t('emailConnector.mailBox.list.drawer.see.label'),
                alertLinkCallback: () => this.$emailConnectorCommonService.openEmailBox(),
              }}));
            }
          })
          .catch(() => { 
            const alertMessage = this.$t(emailIdsToDelete.length === 1 ? 'emailConnector.mailBox.list.drawer.delete.email.error' : 'emailConnector.mailBox.list.drawer.delete.emails.error', {
              0: emailIdsToDelete.length,
            });
            document.dispatchEvent(new CustomEvent('alert-message', {detail: {
              alertType: 'error',
              alertMessage: alertMessage,
              alertLinkText: this.$t('emailConnector.mailBox.list.drawer.see.label'),
              alertLinkCallback: () => this.$emailConnectorCommonService.openEmailBox(),
            }}));
          });
      }
    },
    archiveEmails(emailIdsToArchive = []) {
      this.archivedEmailIds.push(...emailIdsToArchive);
      if (emailIdsToArchive.length > 0) {
        this.$emailConnectorMailBoxService.archiveEmails(emailIdsToArchive)
          .then(archiveResult => {
            if ((archiveResult.failedArchives ?? 0) > 0) {
              const alertMessage = this.$t(archiveResult.failedArchives === 1 ? 'emailConnector.mailBox.list.drawer.archive.email.error' : 'emailConnector.mailBox.list.drawer.archive.emails.error', {
                0: archiveResult.failedArchives,
              });
              document.dispatchEvent(new CustomEvent('alert-message', {detail: {
                alertType: 'error',
                alertMessage: alertMessage,
                alertLinkText: this.$t('emailConnector.mailBox.list.drawer.see.label'),
                alertLinkCallback: () => this.$emailConnectorCommonService.openEmailBox(),
              }}));
            }
          })
          .catch(() => { 
            const alertMessage = this.$t(emailIdsToArchive.length === 1 ? 'emailConnector.mailBox.list.drawer.archive.email.error' : 'emailConnector.mailBox.list.drawer.archive.emails.error', {
              0: emailIdsToArchive.length,
            });
            document.dispatchEvent(new CustomEvent('alert-message', {detail: {
              alertType: 'error',
              alertMessage: alertMessage,
              alertLinkText: this.$t('emailConnector.mailBox.list.drawer.see.label'),
              alertLinkCallback: () => this.$emailConnectorCommonService.openEmailBox(),
            }}));
          });
      }
    },
    async loadEmailBox() {
      this.emailBox = await this.$emailConnectorMailBoxService.getEmailBox(this.currentFolder, this.favoriteOnly);
      this.emails = this.emailBox.emails || [];
      this.syncInProgress = !this.emailBox.emailSyncStatus || this.emailBox.emailSyncStatus === 'IN_PROGRESS';
      this.webmailUrl = this.emailBox.webmailUrl;
      this.$root.$emit('refresh-emails', this.emails);
      if (this.syncInProgress) {
        // A new sync started: any previous post-sync watch is over.
        this.categoryWatchDeadline = null;
      } else {
        this.$root.$emit('synchronize-finished');
        this.watchIncomingCategories();
      }
    },
    // Switch the listed folder (Inbox / Sent / Archive) from the ⋮ menu and reload.
    // A category was assigned/removed from the detail view; patch the matching
    // emails the main list holds so the categories filter reflects it live.
    onCategoriesUpdated({ mailRemoteIds, categoryId, assign }) {
      const targetIds = new Set(mailRemoteIds || []);
      (this.emailBox?.emails || []).forEach(email => {
        if (!targetIds.has(email.mailRemoteId)) {
          return;
        }
        const current = email.categoryIds || [];
        this.$set(email, 'categoryIds', assign
          ? Array.from(new Set([...current, categoryId]))
          : current.filter(id => id !== categoryId));
      });
    },
    onSwitchFolder(folder) {
      if (folder === this.currentFolder) {
        return;
      }
      this.currentFolder = folder;
      this.cancelSelectMode();
      this.loading = true;
      this.loadEmailBox().finally(() => {
        this.loading = false;
        // A running search follows the folder: local matches recompute from the
        // new list, and the server search re-runs scoped to the new folder.
        if (this.searchActive) {
          this.runServerSearch();
        }
      });
    },
    startAutoRefresh() {
      if (this.refreshInterval) {
        return;
      }
      this.isRefreshing = false;
      this.refreshInterval = setInterval(async () => {
        if (this.isRefreshing) {
          return;
        }
        this.isRefreshing = true;
        try {
          await this.loadEmailBox();
        } finally {
          this.isRefreshing = false;
        }
      }, 2000); 
    },
    // AI categorization only starts once the sync reports it is done, and then keeps writing
    // categories for a while. Stopping the refresh the moment the sync finishes left the user
    // staring at an uncategorized list until they reloaded the page by hand. So keep polling
    // past the end of the sync, and stop as soon as nothing new lands rather than on a timer
    // alone — a mailbox with categorization switched off must not poll for minutes.
    watchIncomingCategories() {
      if (!this.categoryWatchDeadline) {
        this.categoryWatchDeadline = Date.now() + CATEGORY_WATCH_MAX_MS;
        this.lastCategoryCount = this.countAppliedCategories();
        this.stableCategoryPolls = 0;
        this.startAutoRefresh();
        return;
      }
      const count = this.countAppliedCategories();
      this.stableCategoryPolls = count === this.lastCategoryCount ? this.stableCategoryPolls + 1 : 0;
      this.lastCategoryCount = count;
      if (this.stableCategoryPolls >= CATEGORY_WATCH_QUIET_POLLS || Date.now() > this.categoryWatchDeadline) {
        this.categoryWatchDeadline = null;
        this.stopAutoRefresh();
      }
    },
    // How many categories are applied across the listed emails; its only use is to notice
    // that categorization has stopped changing anything.
    countAppliedCategories() {
      return (this.emailBox?.emails || []).reduce((total, email) => total + (email.categoryIds || []).length, 0);
    },
    stopAutoRefresh() {
      if (this.refreshInterval) {
        clearInterval(this.refreshInterval);
        this.refreshInterval = null;
      }
    },
    cancelSelectMode() {
      this.selectMode = false;
      this.selectedEmails = [];
    },
    updateExpand(expanded) {
      window.setTimeout(() => this.expanded = expanded, 200);
      if (expanded) {
        if (!this.email) {
          this.selectEmailPlaceHolder = true;
        }
      }
    },
  }
};
</script>