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
          :categories="emailCategories"
          :category-view-id="categoryViewId"
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
        :categories="emailCategories"
        :category-view-id="categoryViewId"
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
        <email-connector-mail-box-drawer-filter-chips
          :important-category="importantCategory"
          :category-view-id="categoryViewId"
          :favorite-only="favoriteOnly"
          :unread-only="unreadOnly"
          class="full-width border-box-sizing application-border application-border-radius py-3 px-3"
          @toggle-important="toggleImportantView"
          @toggle-favorite="onToggleFavoriteFilter"
          @toggle-unread="toggleUnreadFilter" />
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
        <email-connector-mail-box-drawer-filter-chips
          v-if="!expanded"
          :important-category="importantCategory"
          :category-view-id="categoryViewId"
          :favorite-only="favoriteOnly"
          :unread-only="unreadOnly"
          class="full-width border-box-sizing application-border application-border-radius py-3 px-3"
          @toggle-important="toggleImportantView"
          @toggle-favorite="onToggleFavoriteFilter"
          @toggle-unread="toggleUnreadFilter" />
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
      // The category VIEW: picking a category in the ⋮ menu switches the list to
      // it, the way picking Sent or Archive does — single selection, left by
      // selecting a folder (or re-picking the same category). Holds the id, or
      // null outside any category view. The Important chip above the list is a
      // SHORTCUT to this same state for the Important category — one state, two
      // controls, so the chip, the menu highlight and the title always agree.
      categoryViewId: null,
      // The active category view expanded to its subcategories, which is what
      // rows are matched against.
      selectedCategoryIds: [],
      // The add-on's own categories ({id, name, nameId, icon}), fetched once.
      emailCategories: [],
      // Whether the user toggled any filter or view (chip, folder or menu
      // category) since the drawer opened; the "open on Important" default
      // only applies while false.
      filtersTouched: false,
      deletedEmailIds: [],
      archivedEmailIds: [],
      currentFolder: 'INBOX',
      // The favorite view: list only the messages carrying the mail server's
      // \Flagged flag, in the listed folder. Toggled from the chip row.
      favoriteOnly: false,
      // The unread view: hide already-read messages. Purely client-side over the
      // loaded window, like the category filters. Toggled from the chip row.
      unreadOnly: false,
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
    // Plain instance field guarding the async subcategory expansion against
    // out-of-order answers: only the latest toggle's expansion may land.
    this.categoryExpansionToken = 0;
    // The add-on's own categories (Important / Invitation / Notification, each
    // {id, name, nameId, icon}). All of them are views in the ⋮ menu; Important
    // additionally gets a shortcut chip above the list. The promise is kept so
    // open() can await the categories before trusting the "open on Important"
    // default.
    this.emailCategoryIdsPromise = this.$emailConnectorMailBoxService.getAvailableEmailCategories()
      .then(list => this.emailCategories = list || []);
    // Read the "Default view" setting from here rather than from open(), for the
    // same reason the categories are read from here: both are needed to know
    // WHICH list to show, and asking for them only once the drawer is opening is
    // what made the mailbox render the whole inbox and then narrow it to
    // Important in front of the user. Started at creation, they are settled long
    // before the first click, so open() awaits nothing in practice.
    this.readDefaultCategoryView();
    // The setting is cached, so it has to be re-read when it changes -- otherwise
    // switching the toggle in the user settings would only take effect on the
    // next page load.
    this.onRefreshUserEmailSetting = () => this.readDefaultCategoryView();
    document.addEventListener('refresh-user-email-setting', this.onRefreshUserEmailSetting);
    this.$root.$on('switch-folder', this.onSwitchFolder);
    this.$root.$on('open-category-view', this.openCategoryView);
    this.$root.$on('enter-select-mode', this.onEnterSelectMode);
    this.$root.$on('update-email-favorite-status', this.onUpdateEmailFavoriteStatus);
    this.$root.$on('apply-email-favorite-status', this.applyEmailsFavoriteStatus);
    this.onRefreshEmailBox = () => {
      if (!this.emailBoxDrawer) {
        return;
      }
      this.loadEmailBox();
    };
    // A message was just sent. Its copy in Sent is written by the mail server, and the
    // add-on re-reads that folder in the background a second or two later — so the ONE
    // reload the send triggers is always too early to show it, and a user standing in
    // their Sent folder would watch nothing happen. Re-arming the watch below is what
    // makes the list re-read itself for the next minute, until the copy lands.
    //
    // Re-arming rather than starting a second timer, and that is deliberate: the drawer
    // already polls itself after a load (that is how categories appear), and a parallel
    // timer with its own stop condition would give the two of them one interval to fight
    // over. Clearing the deadline makes the reload below open a fresh watch instead of
    // counting towards the end of the one already running — which, landing on the last
    // quiet poll of a watch about to expire, would otherwise stop the polling at exactly
    // the moment the sent copy needed it.
    this.onEmailSent = () => {
      this.categoryWatchDeadline = null;
      this.stableCategoryPolls = 0;
    };
    this.$root.$on('email-sent', this.onEmailSent);
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
    // A draft was saved to (or discarded from) the Drafts folder. The list is a
    // mirror of the local cache and the composer has just changed it, so it has to
    // be re-read — this is the only writer outside the sync.
    this.$root.$on('refresh-email-box', this.onRefreshEmailBox);
    this.$root.$on('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$on('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$on('delete-email', this.onDeleteEmail);
    this.$root.$on('archive-email', this.onArchiveEmail);
    this.$root.$on('email-categories-updated', this.onCategoriesUpdated);
    this.$root.$on('open-email-detail-drawer', () => {
      this.email = null;
    });
    // The reader opened on a row it was handed rather than on a UID — a draft's
    // conversation. Same consequence here: this drawer is no longer the one showing a
    // message.
    this.$root.$on('open-email-thread-drawer', () => {
      this.email = null;
    });
    // Opening the mailbox, optionally straight onto one message — that is how the
    // global Favorites drawer hands a mail over. The payload used to be the plain
    // "loading" flag and callers still pass it that way, so an object is what marks
    // the richer form: passing one where a flag is expected would read as truthy
    // and wrongly show the drawer as synchronizing.
    this.$root.$on('open-mail-box-drawer', async (payload) => {
      const options = payload && typeof payload === 'object' ? payload : {loading: payload};
      // Opening the mailbox, on a message, on a search, or on nothing in particular.
      // The payload used to be the plain "loading" flag and callers still pass it
      // that way, so an object is what marks the richer form: passing one where a
      // flag is expected would read as truthy and wrongly show the drawer as
      // synchronizing.
      if (options.mailRemoteId) {
        // One message asked for by name -- from the platform's search, or from the
        // Favorites drawer -- opens the reader on its own. Opening the mailbox behind
        // it would leave the user standing in a folder they never asked for the
        // moment they close the message, instead of back where they were looking.
        await this.openMailFromOutside(options);
        return;
      }
      await this.open(options.loading);
      if (options.searchTerm) {
        this.openSearchFromOutside(options.searchTerm);
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
    document.removeEventListener('refresh-user-email-setting', this.onRefreshUserEmailSetting);
    this.$root.$off('refresh-email-box', this.onRefreshEmailBox);
    this.$root.$off('email-sent', this.onEmailSent);
    this.$root.$off('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$off('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$off('delete-email', this.onDeleteEmail);
    this.$root.$off('archive-email', this.onArchiveEmail);
    this.$root.$off('email-categories-updated', this.onCategoriesUpdated);
    this.$root.$off('switch-folder', this.onSwitchFolder);
    this.$root.$off('open-category-view', this.openCategoryView);
    this.$root.$off('enter-select-mode', this.onEnterSelectMode);
    this.$root.$off('update-email-favorite-status', this.onUpdateEmailFavoriteStatus);
    this.$root.$off('apply-email-favorite-status', this.applyEmailsFavoriteStatus);
  },
  computed: {
    hasEmails() {
      return this.emails?.length > 0;
    },
    // INBOX plus any of SENT/ARCHIVE/DRAFTS/TRASH that actually hold mail, for the ⋮ folder switch.
    /**
     * The folders offered in the 3-dots menu: the inbox always, the others only
     * once they hold something.
     *
     * This list and the browsable-folder check in EmailBoxService#getEmailBox are
     * the same list expressed twice, with no shared constant between them — change
     * one and the other has to change with it, or this offers a folder the backend
     * refuses (or hides one it would happily serve).
     *
     * Trash is count-gated like the rest, which is what keeps it off the menu of a
     * mailbox that has no Trash folder at all, or an empty one — the entry appears
     * only once the sync has actually cached something to look at.
     *
     * @returns {Array} the folder ids to offer
     */
    availableFolders() {
      const counts = this.emailBox?.folderCounts || {};
      return ['INBOX', 'SENT', 'ARCHIVE', 'DRAFTS', 'TRASH'].filter(folder => folder === 'INBOX' || counts[folder] > 0);
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
        } else if (this.currentFolder === 'DRAFTS') {
          title = `${title} · ${this.$t('emailConnector.mailBox.list.drawer.folder.drafts')}`;
        } else if (this.currentFolder === 'TRASH') {
          title = `${title} · ${this.$t('emailConnector.mailBox.list.drawer.folder.trash')}`;
        }
        // The favorite view reads as one more folder-like narrowing of the list.
        if (this.favoriteOnly) {
          title = `${title} · ${this.$t('emailConnector.mailBox.list.drawer.folder.favorites')}`;
        }
        // A category view is a view like a folder, and titles like one.
        if (this.categoryView) {
          title = `${title} · ${this.categoryView.name}`;
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
      return this.expanded && (this.hasEmails || this.hasActiveFilters || this.searchActive) && !this.syncBlocked;
    },
    // The Important category, which gets a shortcut chip above the list on top
    // of its ⋮ menu entry; null until categories load (or when the default
    // categories are not seeded) — the chip simply doesn't show then.
    importantCategory() {
      return this.emailCategories.find(category => category.nameId === 'emailImportantCategory') || null;
    },
    // The category the list is switched to, or null outside any category view.
    categoryView() {
      return this.emailCategories.find(category => category.id === this.categoryViewId) || null;
    },
    // Whether any filter or view narrows the list (used to keep the expanded
    // left pane on screen when a filter empties it).
    hasActiveFilters() {
      return this.favoriteOnly || this.unreadOnly || !!this.categoryViewId;
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
      // The filters combine: each one narrows what the others left, so
      // "unread favorites in this category" is just everything toggled on. A
      // category VIEW is single selection (categoryViewId), so at most one
      // category ever narrows the list; Favorites and Unread apply on top of
      // whichever view is open.
      // The favorite view: the server already answered with the favorite subset;
      // filtering again here makes a just-unfavorited message leave the list at
      // once instead of waiting for the next reload.
      if (this.favoriteOnly) {
        emails = emails.filter(e => e.starred);
      }
      if (this.unreadOnly) {
        // The one message being read stays listed even once marked read:
        // opening a mail under the Unread filter must not yank the reader
        // out from under the user for the select-an-email placeholder.
        emails = emails.filter(e => !e.read || (this.email && e.mailRemoteId === this.email.mailRemoteId));
      }
      if (this.selectedCategoryIds.length > 0) {
        emails = emails.filter(e => this.selectedCategoryIds.some(id => e.categoryIds.includes(id)));
      }
      return emails;
    }
  },
  watch: {
    // The category view changed (opened, replaced or left — from the ⋮ menu or
    // the Important shortcut chip): expand it to its subcategories, which is
    // what rows are matched against. The token drops an expansion that
    // finishes after a newer change already superseded it.
    async categoryViewId(id) {
      this.cancelSelectMode();
      const token = ++this.categoryExpansionToken;
      const expanded = id ? await this.$emailConnectorMailBoxService.getSubcategoryIds(id) : [];
      if (token === this.categoryExpansionToken) {
        this.selectedCategoryIds = expanded;
      }
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
        // No open message when select mode was entered from the ⋮ menu in the
        // narrow drawer — nothing to re-highlight then.
        this.$root.$emit('set-opened', this.email?.mailRemoteId || null);
      }
    }
  },
  methods: {
    /**
     * Re-reads the user's "Default view" setting into the cached promise open()
     * awaits. Failing is silent and answers null: an unreadable setting means an
     * unfiltered inbox, never a blocked drawer.
     *
     * @returns {void}
     */
    readDefaultCategoryView() {
      this.defaultCategoryViewPromise = this.$emailConnectorCommonService.getUserEmailSetting()
        .then(setting => setting && setting.defaultCategoryView || null)
        .catch(() => null);
    },
    /**
     * Seeds the view the mailbox opens on, before anything is rendered.
     *
     * When the user settings' "Default view" toggle is on, the stored default
     * holds the Important category's id and the mailbox opens on the Important
     * view — chip lit, menu entry highlighted, title suffixed, one state. When
     * it is off, or when the stored id is not Important's (one left behind by
     * the former category select, say), the inbox opens unfiltered.
     *
     * Awaited BEFORE the first load on purpose. Doing it afterwards showed the
     * user the whole inbox and then narrowed it under them, which read as the
     * mailbox being slow and changing its mind. Both promises are started when
     * the component is created, so by the time anyone opens the drawer there is
     * nothing left to wait for.
     *
     * A filter the user has already touched still wins: the default only seeds.
     *
     * @returns {Promise<void>} resolves once the view is decided
     */
    async applyDefaultCategoryView() {
      const [defaultView] = await Promise.all([this.defaultCategoryViewPromise, this.emailCategoryIdsPromise]);
      if (this.filtersTouched) {
        return;
      }
      this.categoryViewId = defaultView && this.importantCategory && defaultView === this.importantCategory.id
        ? this.importantCategory.id : null;
    },
    async open(loading) {
      if (loading) {
        this.syncInProgress = true;
        await this.$nextTick();
      }
      // Always (re)open on the inbox, without leftover search, filter or view state.
      this.currentFolder = 'INBOX';
      this.favoriteOnly = false;
      this.unreadOnly = false;
      this.categoryViewId = null;
      this.filtersTouched = false;
      this.clearSearch();
      this.loading = true;
      this.emailBoxDrawer = true;
      await this.applyDefaultCategoryView();
      await this.loadEmailBox();
      this.loading = false;
      if (this.syncInProgress) {
        this.startAutoRefresh();
      }
    },
    /**
     * Opens one listed message in the expanded reader.
     *
     * The folder comes off the clicked ROW. An IMAP UID numbers messages within one
     * folder, and this read is answered from the cache under the folder it is asked
     * for — so letting it default to the inbox, as it did, asked for a message that
     * is not there and the click did nothing at all. It was invisible while the
     * expanded pane only ever listed the inbox; it takes any non-inbox folder to
     * show, and Trash is the third to arrive. Both paths that open a message from
     * OUTSIDE the list (a search hit, a favorite) already pass the folder — this is
     * the same lookup the narrow drawer does in its own folderOf.
     *
     * @param {Number} mailRemoteId the message's IMAP UID within the listed folder
     * @returns {void}
     */
    openEmailDetailContent(mailRemoteId) {
      // Opening from the list is the user choosing again: whatever was pinned open
      // from elsewhere gives way to it.
      this.pinnedEmail = false;
      this.loading = true;
      const listed = this.emails.find(e => e.mailRemoteId === mailRemoteId);
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId, listed?.folder || 'INBOX').then((email) => {
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
     * Opens one message picked outside the mailbox — from the platform's unified
     * search, or from the global Favorites drawer.
     *
     * Such a message is a search hit in everything but name: it may sit in another
     * folder, and it may not be held locally at all. So an uncached one is pulled in
     * before it is opened, and a mailbox busy synchronizing says "one moment" rather
     * than failing.
     *
     * The reader is handed a list containing the message itself. That is not a
     * detail: the reader works out which folder to read from by looking the id up in
     * the list it was given, and a UID is only unique within its folder. Handed
     * nothing, it assumed the inbox -- and every hit that was not a cached inbox
     * message opened as an empty reader titled "(no subject)".
     *
     * @param {Object} opening what to open: {mailRemoteId, folder, cached}
     * @returns {Promise} resolved once the message is on screen
     */
    async openMailFromOutside(opening) {
      // No folder means the inbox, and anything that does not say otherwise is
      // already cached: that is the Favorites drawer, which only holds cached inbox
      // mail.
      const hit = {
        mailRemoteId: opening.mailRemoteId,
        folder: opening.folder || 'INBOX',
        cached: opening.cached !== false,
      };
      this.pinnedEmail = true;
      this.loading = true;
      try {
        if (!hit.cached) {
          await this.fetchSearchedEmail(hit);
        }
        if (this.expanded && this.emailBoxDrawer) {
          this.email = await this.$emailConnectorMailBoxService.getEmailByRemoteId(hit.mailRemoteId, hit.folder);
          this.selectEmailPlaceHolder = false;
          this.$root.$emit('set-opened', hit.mailRemoteId);
        } else {
          this.$root.$emit('open-email-detail-drawer', hit.mailRemoteId, [hit], this.syncInProgress, this.webmailUrl, true, !this.emailBoxDrawer);
        }
      } catch (error) {
        // A mailbox held by a running synchronization is a "one moment", not a
        // failure.
        const syncing = error?.status === 409;
        const messageKey = syncing && 'emailConnector.mailBox.search.syncInProgress'
          || 'emailConnector.mailBox.search.openError';
        document.dispatchEvent(new CustomEvent('alert-message', {detail: {
          alertType: syncing && 'warning' || 'error',
          alertMessage: this.$t(messageKey),
        }}));
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
      this.unreadOnly = false;
      this.categoryViewId = null;
      this.selectedCategoryIds = [];
      this.filtersTouched = false;
      this.deletedEmailIds = [];
      this.archivedEmailIds = [];
    },
    checkSetting() {
      this.$root.$emit('open-user-setting-drawer');
    },
    /**
     * Marks listed messages read or unread, locally and on the mail server.
     *
     * The one place in the mailbox that pushes a read status, which is why the
     * read-only folders are held back HERE rather than at each of the four places
     * that ask for one. And they have to be held back, for a reason stronger than
     * the write being pointless: the push opens the INBOX and sets \Seen on whatever
     * message carries that UID there (EXO-89367). Since simply OPENING a message
     * marks it read, browsing the Trash would otherwise quietly clear the unread
     * flag of unrelated inbox mail, one message per trashed mail read — with nothing
     * on any screen to account for it.
     *
     * Not marked read locally either, deliberately: showing a trashed message turn
     * read would promise a state nothing is saving, and the next sync would take it
     * back.
     *
     * @param {Boolean} read the status to apply
     * @param {Array} emailIds the IMAP UIDs to apply it to
     * @returns {void}
     */
    updateEmailsReadStatus(read, emailIds = []) {
      const emailIdsToUpdate = emailIds.filter(id => {
        const email = this.emails.find(e => e.mailRemoteId === id);
        if (!email || this.$emailConnectorMailBoxService.isReadOnlyFolder(email.folder)) {
          return false;
        }
        if (email.read !== read) {
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
    // Toggle the favorite-only view from the chip row, reloading the listed
    // folder (the favorite subset is answered server-side).
    onToggleFavoriteFilter() {
      this.filtersTouched = true;
      this.favoriteOnly = !this.favoriteOnly;
      this.cancelSelectMode();
      this.loading = true;
      this.loadEmailBox().finally(() => this.loading = false);
    },
    // Toggle the unread-only view from the chip row; purely client-side, so no
    // reload — the list recomputes from the loaded window.
    toggleUnreadFilter() {
      this.filtersTouched = true;
      this.unreadOnly = !this.unreadOnly;
      this.cancelSelectMode();
    },
    // The Important chip: a shortcut into (and out of) the Important category
    // view — the very state the ⋮ menu's Important entry drives, so the chip,
    // the menu highlight and the title can never disagree.
    toggleImportantView() {
      if (this.importantCategory) {
        this.openCategoryView(this.importantCategory.id);
      }
    },
    // Switch the list to one category — from the ⋮ menu or the Important
    // shortcut chip — a view like Sent or Archive, not a checkbox: single
    // selection, replacing whatever category view was active. Picking the
    // active category again leaves the view, as does selecting any folder.
    // The Favorites and Unread chips survive the switch and combine with the
    // view (their chips stay on screen inside it, so nothing narrows the list
    // invisibly): Favorites' server-answered subset stays loaded and the
    // category narrows it client-side — no reload needed either way.
    openCategoryView(categoryId) {
      this.filtersTouched = true;
      this.categoryViewId = this.categoryViewId === categoryId ? null : categoryId;
    },
    // "Select several" from the ⋮ menu: enter the same multi-select mode a row
    // checkbox starts, with nothing selected yet.
    onEnterSelectMode() {
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return;
      }
      this.selectMode = true;
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
      // Selecting a folder always leaves the category view — that is the way
      // back from one, so it works even for the folder already listed.
      if (this.categoryViewId) {
        this.filtersTouched = true;
        this.categoryViewId = null;
      }
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