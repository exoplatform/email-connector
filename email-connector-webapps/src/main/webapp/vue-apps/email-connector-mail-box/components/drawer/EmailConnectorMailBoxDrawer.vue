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
          :email="email"
          :thread="threadContext" />
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
        <div
          v-if="customFolderWindow"
          class="caption text-sub-title text-center py-2">
          {{ $t('emailConnector.mailBox.list.drawer.folder.custom.window', { 0: customFolderWindow }) }}
        </div>
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
            <!-- The reader tells the header which conversation it is showing, so the
                 title bar above can act on the exchange rather than on the one message
                 that was clicked. This drawer holds both of them and is the only place
                 the value can pass between them. -->
            <email-connector-mail-box-drawer-thread-content
              v-else
              :email="email"
              :emails="emails"
              expanded-drawer
              @thread-context="threadContext = $event" />
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
          <!-- A custom folder is a recent-activity mirror, not a copy, and the list says
               so rather than letting an older message look lost. -->
          <div
            v-if="customFolderWindow && !expanded"
            class="caption text-sub-title text-center py-2">
            {{ $t('emailConnector.mailBox.list.drawer.folder.custom.window', { 0: customFolderWindow }) }}
          </div>
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

// How long the drawer keeps a row the server has not listed yet -- one an Undo put back,
// one a "Move to..." filed into its destination -- and polls for the server's own: the
// budget the service gives the background re-read, restated -- one second's coalescing
// delay, then up to 36 retries five seconds apart while a running sync holds the
// mailbox (181 s), then the window itself (near 8.5 s at the shipped 1000 cached inbox
// rows, 42 s at the administrator's 5000). A row still unbacked past this is dropped:
// the re-read failed, or the administrator withdrew it, and an honest empty list beats
// a row nothing can act on. The message surfaces at the folder's next scheduled check
// either way (see undoMove and moveEmails).
const REFRESH_WATCH_MAX_MS = 240000;

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
      // The conversation the reader below is showing — {threadId, messages, subject} —
      // relayed to the toolbar in the title bar, which is a sibling of the reader and
      // would otherwise only ever see the single opened message. Null until the reader
      // has loaded something, and cleared whenever it stops showing one.
      threadContext: null,
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
      // The Trash actions' optimistic removals, kept apart from the two above only so
      // each list stays readable — they are filtered out of the listing all the same.
      restoredEmailIds: [],
      purgedEmailIds: [],
      junkedEmailIds: [],
      unjunkedEmailIds: [],
      // {folder, id} pairs, not bare UIDs like the lists above -- see the emails() filter.
      movedEmailIds: [],
      // The rows shown in a folder before the server's mirror holds them: the ones an
      // Undo put back where they came from, and the ones a "Move to..." filed into its
      // destination -- the move's optimistic removal above, mirrored in the folder the
      // messages went to. The server re-reads that folder in the background (seconds;
      // minutes behind a running sync); until then each remembered row carries its
      // folder and its expiry, renders inert (refreshPending), and gives way to the
      // server's row for the same message (same folder, same Message-ID) as soon as a
      // reload lists one. See undoMove and moveEmails.
      refreshPendingRows: [],
      refreshWatchDeadline: null,
      // Whether the watch polls for its whole budget rather than until the listed
      // folder holds no remembered row: set by a PARTLY failed undo, whose rows all
      // left the listing (which of them went back is not the drawer's to guess) while
      // some of them are on their way into the mirror.
      refreshWatchUntilDeadline: false,
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
    // Favorites the user toggled here, each tagged with the search generation that was
    // current when the mail server acknowledged the \Flagged push, so a search answer
    // that left before that acknowledgement cannot roll the star back. A plain field,
    // not data(): Vue 2 does not observe a Map, and nothing renders it directly —
    // withLocalFavorites() is its only reader, and it runs when a search answer lands.
    this.favoriteOverrides = new Map();
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
    // The global Favorites drawer clears a mail's flag from outside this app, in
    // its own Vue root: without this door the star it took back would stay lit in
    // a mailbox left open beside it until the next poll. The value it carries is
    // already the server's, so it is applied as acknowledged, never as a pending
    // push to roll back.
    this.onFavoriteStatusChangedOutside = event => {
      const mailRemoteIds = event?.detail?.mailRemoteIds;
      if (mailRemoteIds?.length) {
        this.$root.$emit('apply-email-favorite-status', !!event.detail.favorite, mailRemoteIds, true);
      }
    };
    document.addEventListener('email-favorite-status-changed', this.onFavoriteStatusChangedOutside);
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
    // The two Trash actions, wired exactly as delete and archive are: the rows leave
    // the listing, the reader stops showing what is no longer there, and a running
    // selection ends. The confirmation for the permanent one is asked before the event
    // is emitted, where the user clicked — by the time it arrives here the answer is in.
    this.onRestoreEmail = (emails) => {
      this.restoreEmails(emails);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return;
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.onPurgeEmail = (emails) => {
      this.purgeEmails(emails);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return;
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.$root.$on('restore-email', this.onRestoreEmail);
    this.$root.$on('purge-email', this.onPurgeEmail);
    // The two Junk actions, wired the same way. "Mark as spam" leaves from any
    // writable folder, "Not spam" from the Spam listing; a Delete out of Spam is the
    // ordinary delete-email above, addressed to the row's own folder.
    this.onJunkEmail = (emails) => {
      this.markAsJunk(emails);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return;
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.onNotJunkEmail = (emails) => {
      this.restoreFromJunk(emails);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return;
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.$root.$on('junk-email', this.onJunkEmail);
    this.$root.$on('not-junk-email', this.onNotJunkEmail);
    // "Move to..." into one of the user's own folders, wired the same way as archive:
    // the rows leave the listing at once, the reader stops showing what is no longer
    // there, and a running selection ends. The target comes from the picker drawer.
    this.onMoveEmail = (emails, target) => {
      this.moveEmails(emails, target);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return;
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.$root.$on('move-email', this.onMoveEmail);
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
    document.removeEventListener('email-favorite-status-changed', this.onFavoriteStatusChangedOutside);
    this.$root.$off('refresh-email-box', this.onRefreshEmailBox);
    this.$root.$off('email-sent', this.onEmailSent);
    this.$root.$off('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$off('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$off('delete-email', this.onDeleteEmail);
    this.$root.$off('archive-email', this.onArchiveEmail);
    this.$root.$off('restore-email', this.onRestoreEmail);
    this.$root.$off('purge-email', this.onPurgeEmail);
    this.$root.$off('junk-email', this.onJunkEmail);
    this.$root.$off('not-junk-email', this.onNotJunkEmail);
    this.$root.$off('move-email', this.onMoveEmail);
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
    /**
     * The folders as the server lists them: the built-ins this mailbox HAS -- discovered,
     * whether or not they currently hold cached mail, so a Spam folder is offered the way
     * Gmail offers it rather than appearing only once something was filtered -- plus every
     * custom folder with its opt-in. Data, not a hard-coded array: the server's
     * MailFolder.isBrowsable is the one spelling of what may be listed, and this is its
     * answer, so the menu can never offer a folder the backend refuses.
     *
     * @returns {Array} the folder descriptors ({key, type, displayName, path, syncEnabled, missing, count})
     */
    folders() {
      return this.emailBox?.folders || [{ key: 'INBOX', type: 'BUILT_IN', syncEnabled: true }];
    },
    /**
     * The folders offered in the 3-dots menu: every built-in the server listed, and the
     * custom folders the user opted in that the mailbox still has.
     *
     * @returns {Array} the folder descriptors to offer
     */
    availableFolders() {
      return this.folders.filter(folder => folder.type !== 'CUSTOM' || (folder.syncEnabled && !folder.missing));
    },
    /**
     * The listed folder's descriptor, for its name and its window.
     *
     * @returns {Object} the descriptor, or a bare built-in one when the list has not loaded
     */
    currentFolderView() {
      return this.folders.find(folder => folder.key === this.currentFolder)
        || { key: this.currentFolder, type: this.currentFolder.startsWith('CUSTOM:') ? 'CUSTOM' : 'BUILT_IN' };
    },
    /**
     * The mirror window of the listed folder when it is one of the user's own, for the
     * "showing the N most recent" line under the list; nothing for a built-in.
     *
     * @returns {Number} the window, or 0
     */
    customFolderWindow() {
      return this.currentFolderView.type === 'CUSTOM' && this.currentFolderView.windowSize || 0;
    },
    syncBlocked() {
      return this.emailBox?.emailSyncStatus === 'BLOCKED';
    },
    title() {
      if (!this.selectMode) {
        let title = this.$t('emailConnector.mailBox.list.drawer.title');
        // Any folder but the inbox names itself in the title, built-in or the user's
        // own, through the one labelling function: a custom name is shown as written.
        if (this.currentFolder !== 'INBOX') {
          title = `${title} · ${this.$emailConnectorMailBoxService.folderLabel(this.currentFolderView, this.$t.bind(this))}`;
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
        .filter(result => this.searchResultMatchesFilters(result))
        .sort((first, second) => new Date(second.receivedDate) - new Date(first.receivedDate));
    },
    canGoBack() {
      return this.selectMode && !this.expanded;
    },
    emails() {
      let emails = this.emailBox?.emails || [];
      emails = emails.filter(e => !this.deletedEmailIds.includes(e.mailRemoteId));
      emails = emails.filter(e => !this.archivedEmailIds.includes(e.mailRemoteId));
      emails = emails.filter(e => !this.restoredEmailIds.includes(e.mailRemoteId));
      emails = emails.filter(e => !this.purgedEmailIds.includes(e.mailRemoteId));
      emails = emails.filter(e => !this.junkedEmailIds.includes(e.mailRemoteId));
      emails = emails.filter(e => !this.unjunkedEmailIds.includes(e.mailRemoteId));
      // By folder as well as UID, unlike the lists above: a move's rows are looked for in
      // TWO folders, and the destination may hold a row of its own under the number the
      // moved message had in its origin.
      emails = emails.filter(e => !this.movedEmailIds.some(moved => moved.id === e.mailRemoteId
        && moved.folder === (e.folder || this.currentFolder)));
      emails = this.withRefreshPendingRows(emails);
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
        // Folder as well as UID: a UID is only unique within its folder, and the open
        // reader can now hold a message from another one (openMailFromOutside,
        // openSearchResult), so comparing the number alone would spare an unrelated
        // read message that happens to share it.
        emails = emails.filter(e => !e.read
          || (this.email
            && e.mailRemoteId === this.email.mailRemoteId
            && (e.folder || this.currentFolder) === (this.email.folder || this.currentFolder)));
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
      // A row an Undo put back, or a move filed here, is a snapshot: it carries the UID
      // it had before the move (or a placeholder, see rememberMovedRows), which the
      // server's re-read replaces, so nothing can be opened by it yet. The row renders
      // inert meanwhile (see the list item), and a click that reaches here all the same
      // is ignored rather than answered with a reader that fails to load.
      if (this.emails.find(e => e.mailRemoteId === mailRemoteId)?.refreshPending) {
        return;
      }
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
      this.$emailConnectorMailBoxService.searchEmails(this.searchTerm, this.currentFolder, SEARCH_PAGE_SIZE, this.favoriteOnly, this.unreadOnly)
        .then(page => {
          if (requestId !== this.searchRequestId) {
            return;
          }
          this.searchServerResults = this.withLocalFavorites(page?.results || [], requestId);
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
    // Adopt a server search answer without letting it undo a favorite toggled while
    // it was in flight: such an answer was built from the FLAGS the server held
    // BEFORE the \Flagged push, so its starred is stale for those messages — and
    // since every hit now carries the flag, adopting it as is puts the star back out
    // (the list row would show it, the search row would not, until the next search).
    // Only acknowledged overrides older than this request are dropped: this answer left
    // after the server took their flag -- it does reflect them -- and keeping them would
    // outlive a favorite changed meanwhile from another mail client. An unacknowledged
    // one is kept regardless, because the push it belongs to had not reached the server
    // when this answer was built, whether the search was issued before the
    // acknowledgement or merely answered before it. INBOX rows only: UIDs are
    // per-folder, so the same number elsewhere is another message.
    withLocalFavorites(results, requestId) {
      if (!this.favoriteOverrides.size) {
        return results;
      }
      this.favoriteOverrides.forEach((override, mailRemoteId) => {
        // An override whose push is still travelling is never stale, whatever
        // generation this answer carries — the server had not taken the flag when it
        // was built, so its starred cannot be the truth for this message yet.
        if (override.acknowledged && override.searchRequestId < requestId) {
          this.favoriteOverrides.delete(mailRemoteId);
        }
      });
      return results.map(result => {
        const override = (result.folder || 'INBOX') === 'INBOX' && this.favoriteOverrides.get(result.mailRemoteId);
        return override ? { ...result, starred: override.favorite } : result;
      });
    },
    // The chips narrow the search the same way they narrow the list. A lit Favorites
    // chip that stopped filtering the moment the user typed was the defect here: the
    // search read the mailbox directly and never went through the emails() computed
    // that applies them.
    // Favorites and Unread also travel to the server, since both are IMAP flags that
    // /search takes, so they hold for hits the cache has never seen. A category is
    // assigned locally after a message is cached, so an uncached hit carries none —
    // it is left alone rather than silently dropped for lacking what it cannot have.
    searchResultMatchesFilters(result) {
      if (this.favoriteOnly && !result.starred) {
        return false;
      }
      if (this.unreadOnly && result.read) {
        return false;
      }
      if (this.selectedCategoryIds.length > 0 && result.cached) {
        return this.selectedCategoryIds.some(id => (result.categoryIds || []).includes(id));
      }
      return true;
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
      this.refreshWatchDeadline = null;
      this.refreshWatchUntilDeadline = false;
      this.stopAutoRefresh();
      this.clearSearch();
      // Nothing prunes an override until a server answer lands, so a user who toggles
      // stars and never searches again would keep the entries for the page's lifetime.
      this.favoriteOverrides.clear();
      // Also empty the drawer's own header filter field for the next open.
      this.$refs.emailBoxDrawer?.resetFilter?.();
      document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
      this.cancelSelectMode();
      this.selectEmailPlaceHolder = false;
      this.email = null;
      this.threadContext = null;
      this.emailBoxDrawer = false;
      this.favoriteOnly = false;
      this.unreadOnly = false;
      this.categoryViewId = null;
      this.selectedCategoryIds = [];
      this.filtersTouched = false;
      this.deletedEmailIds = [];
      this.archivedEmailIds = [];
      this.restoredEmailIds = [];
      this.purgedEmailIds = [];
      this.junkedEmailIds = [];
      this.unjunkedEmailIds = [];
      this.movedEmailIds = [];
      this.refreshPendingRows = [];
    },
    checkSetting() {
      this.$root.$emit('open-user-setting-drawer');
    },
    /**
     * Marks listed messages read or unread, locally and on the mail server.
     *
     * The one place in the mailbox that pushes a read status, which is why the
     * read-only folders are held back HERE rather than at each of the four places
     * that ask for one.
     *
     * The push is folder-aware now (EXO-89367): it opens the folder the ROW is listed
     * in and flags the message THAT folder holds at that id, so reading a message out
     * of Sent or Archive no longer clears the unread flag of whatever inbox message
     * happened to carry the same number. What is left of the old restriction is the
     * Trash, and for its own reason: marking a message the user has thrown away read is
     * not a state worth writing to their mail server.
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
      this.byOwnFolder(emailIdsToUpdate).forEach(([folder, ids]) =>
        this.$emailConnectorMailBoxService.updateEmailsReadStatus(ids, read, folder));
    },
    /**
     * The folder a message id belongs to — the ROW's own, which is the only one the
     * server can address it by.
     *
     * Looked up in the three places this drawer holds rows, and they genuinely differ:
     * the listed window is one folder's, but a server search result carries whichever
     * folder it was found in, and the opened message keeps the folder it was opened
     * from. Sending the LISTED folder instead would be right for the list and wrong for
     * both of the others.
     *
     * Falls back to INBOX for an id from none of the three, which is what a row written
     * before the mailbox held other folders means.
     *
     * @param {Number} mailRemoteId the message's IMAP UID
     * @returns {String} the folder that id is numbered in
     */
    folderOfEmail(mailRemoteId) {
      return this.rowOfEmail(mailRemoteId)?.folder || 'INBOX';
    },
    /**
     * The row this drawer holds for a message id, from the three places it holds rows
     * -- see folderOfEmail for why they genuinely differ. Null for an id from none of
     * them.
     *
     * @param {Number} mailRemoteId the message's IMAP UID
     * @returns {Object} the row, or null
     */
    rowOfEmail(mailRemoteId) {
      return (this.emails || []).find(email => email.mailRemoteId === mailRemoteId)
        || (this.searchServerResults || []).find(result => result.mailRemoteId === mailRemoteId)
        || (this.email?.mailRemoteId === mailRemoteId ? this.email : null);
    },
    /**
     * Groups message ids by the folder each one is listed in, so one request goes out
     * per folder rather than one request carrying ids the server cannot tell apart.
     *
     * In a plain listing this yields a single group — a folder window holds one folder's
     * rows. It yields more than one when the selection came from a search across
     * folders, and that is the case the grouping exists for.
     *
     * @param {Array<Number>} emailIds the ids to act on
     * @returns {Array} [folder, ids] pairs, empty when there is nothing to do
     */
    byOwnFolder(emailIds = []) {
      const groups = new Map();
      emailIds.forEach(id => {
        const folder = this.folderOfEmail(id);
        groups.set(folder, (groups.get(folder) || []).concat(id));
      });
      return Array.from(groups.entries());
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
    /**
     * Patches the favorite flag on every copy this drawer holds: the cached folder
     * window (which the list rows and the search's local matches derive from),
     * the server search hits, and the opened message. No service call here —
     * this is also how a refused push is rolled back visually.
     *
     * @param {boolean} favorite the flag value to show
     * @param {Array<number>} emailIds the INBOX IMAP UIDs of the messages
     * @param {boolean} acknowledged whether the value carried here is already the
     *          mail server's: false for an optimistic toggle whose \Flagged push
     *          is still travelling, true for the revert broadcast below (which
     *          exists precisely because the server refused) and for a change
     *          another app made and had confirmed
     * @returns {void}
     */
    applyEmailsFavoriteStatus(favorite, emailIds = [], acknowledged = false) {
      const ids = new Set(emailIds);
      // INBOX rows only, like the two copies below: the in-app star never fires
      // while another folder is listed, but the Favorites drawer's does, and a
      // Sent row happening to share the UID is another message.
      (this.emailBox?.emails || []).forEach(email => {
        if ((email.folder || this.currentFolder) === 'INBOX' && ids.has(email.mailRemoteId)) {
          this.$set(email, 'starred', favorite);
        }
      });
      // A server hit is a snapshot of the FLAGS as they were when the search ran, so
      // the toggled rows still have to be stamped even though hits now carry the
      // flag. INBOX rows only: UIDs are per-folder, so the same number elsewhere is
      // another message.
      this.searchServerResults.forEach(result => {
        if ((result.folder || 'INBOX') === 'INBOX' && ids.has(result.mailRemoteId)) {
          this.$set(result, 'starred', favorite);
        }
      });
      // And remember it, so a search answer still in flight — which left before the
      // push and therefore reports the old flag — cannot undo the stamp when it lands.
      // While the push is unacknowledged the entry is immune from pruning whatever
      // generation an answer carries: an answer can *land* before the acknowledgement
      // as easily as it can be issued before it, and the star was lost either way.
      // restampFavoriteOverrides() marks it acknowledged and moves it onto the
      // generation current at that moment, which is when pruning may resume.
      ids.forEach(mailRemoteId => this.favoriteOverrides.set(mailRemoteId, {
        favorite,
        searchRequestId: this.searchRequestId,
        acknowledged,
      }));
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
          if (failedUpdates > 0 && failedUpdates < emailIds.length) {
            // A partial refusal is the one outcome where the correct value is NOT known
            // per message: the answer carries a count, not which ids it refused. So the
            // overrides for the batch are dropped rather than asserted — claiming the
            // requested star for a message the server rejected, and marking it the
            // server's own truth, is worse than claiming nothing. loadEmailBox() below
            // carries the truth for the listed window, and the next search answer
            // carries the server's own flags for the search rows.
            emailIds.forEach(mailRemoteId => {
              const override = this.favoriteOverrides.get(mailRemoteId);
              // Guarded exactly as the restamp is: an entry a later toggle replaced
              // belongs to that toggle's own confirmation, and dropping it here would
              // leave that toggle's optimistic star with nothing protecting it.
              if (override && override.favorite === favorite) {
                this.favoriteOverrides.delete(mailRemoteId);
              }
            });
          } else {
            // Every id settled the same way, so the value is known: acknowledge it. For
            // an all-failed batch the revert broadcast below overwrites this with the
            // rolled-back value, itself acknowledged.
            this.restampFavoriteOverrides(favorite, emailIds);
          }
          if (failedUpdates > 0) {
            this.onFavoriteUpdateFailed(favorite, emailIds, failedUpdates);
          }
        })
        .catch(() => this.onFavoriteUpdateFailed(favorite, emailIds, emailIds.length));
    },
    // Move the confirmed overrides onto the search generation in flight NOW that the
    // server has taken the flag. Until this runs an override carries the generation of
    // the optimistic toggle, which any search issued while the push travelled would
    // outrank -- and that search's answer, built from the FLAGS as they were before the
    // push, would put the star back out. An entry whose favorite no longer matches was
    // overwritten by a later toggle; it belongs to that toggle's own confirmation.
    restampFavoriteOverrides(favorite, emailIds = []) {
      emailIds.forEach(mailRemoteId => {
        const override = this.favoriteOverrides.get(mailRemoteId);
        if (override && override.favorite === favorite) {
          override.acknowledged = true;
          override.searchRequestId = this.searchRequestId;
        }
      });
    },
    // Reflect a refused push. When everything failed (or the lone message did),
    // the exact set to roll back is known: broadcast the revert so every copy —
    // list rows, reader, detail drawer — flips back. When only part of a bulk
    // toggle failed, the server does not say which ones, but its cache is
    // already truthful: reload the listed window from it.
    onFavoriteUpdateFailed(favorite, emailIds, failedUpdates) {
      if (failedUpdates >= emailIds.length) {
        // Acknowledged: the server refusing the push is itself the answer, so the
        // reverted value is the server's own. Left unacknowledged it would be immune
        // from pruning and outlive a change made later from another mail client.
        this.$root.$emit('apply-email-favorite-status', !favorite, emailIds, true);
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
    /**
     * Moves messages to the Trash, one request per folder they are listed in.
     *
     * The count in the answer is the message the user gets, and it now counts things it
     * never used to: a message the server does not have at that id is a FAILED delete,
     * not a quiet nothing (EXO-89367). Before, deleting from Sent removed the row on
     * screen, answered success, and the message was still there after a reload.
     *
     * @param {Array<Number>} emailIdsToDelete the IMAP UIDs to delete
     * @returns {void}
     */
    deleteEmails(emailIdsToDelete = []) {
      // Group BEFORE hiding the rows: the listing is a computed that filters out
      // deletedEmailIds, so pushing first would take the rows out of it and leave
      // folderOfEmail with nothing to read — every delete would then be addressed
      // to the INBOX default and refused by the server (EXO-89367).
      const groups = this.byOwnFolder(emailIdsToDelete);
      this.deletedEmailIds.push(...emailIdsToDelete);
      groups.forEach(([folder, ids]) =>
        this.$emailConnectorMailBoxService.deleteEmails(ids, folder)
          .then(deleteResult => this.alertOnActionFailures(deleteResult.failedDeletions ?? 0, 'delete'))
          .catch(() => this.alertOnActionFailures(ids.length, 'delete')));
    },
    /**
     * Puts trashed messages back into the inbox.
     *
     * Optimistic, like delete and archive: the rows leave the Trash listing at once and
     * an alert says how many did not make it. Which is all the list can honestly show —
     * the restored message does NOT appear in the inbox until the next sync imports it
     * (the backend does not chase its new inbox UID on purpose), so there is nothing to
     * add to a listing here.
     *
     * @param {Array} emailIdsToRestore the IMAP UIDs, within the Trash folder
     * @returns {void}
     */
    restoreEmails(emailIdsToRestore = []) {
      if (!emailIdsToRestore.length) {
        return;
      }
      this.restoredEmailIds.push(...emailIdsToRestore);
      this.$emailConnectorMailBoxService.restoreEmails(emailIdsToRestore)
        .then(restoreResult => this.alertOnActionFailures(restoreResult.failedRestores ?? 0, 'restore'))
        .catch(() => this.alertOnActionFailures(emailIdsToRestore.length, 'restore'));
    },
    /**
     * Removes trashed messages from the mail server for good.
     *
     * The confirmation is the caller's, not this method's: it is asked once, where the
     * user clicked, so this stays the single place that actually sends the request.
     *
     * @param {Array} emailIdsToPurge the IMAP UIDs, within the Trash folder
     * @returns {void}
     */
    purgeEmails(emailIdsToPurge = []) {
      if (!emailIdsToPurge.length) {
        return;
      }
      this.purgedEmailIds.push(...emailIdsToPurge);
      this.$emailConnectorMailBoxService.purgeEmails(emailIdsToPurge)
        .then(purgeResult => this.alertOnActionFailures(purgeResult.failedPurges ?? 0, 'purge'))
        .catch(() => this.alertOnActionFailures(emailIdsToPurge.length, 'purge'));
    },
    /**
     * Moves messages to the Spam folder — "Mark as spam" — one request per folder
     * they are listed in, exactly as delete and archive are sent.
     *
     * Optimistic like them: the rows leave the listing at once and an alert says how
     * many did not make it. The message shows up in the Spam listing at the next
     * synchronization, not at once (the backend does not chase its new UID).
     *
     * @param {Array<Number>} emailIdsToJunk the IMAP UIDs to report as spam
     * @returns {void}
     */
    markAsJunk(emailIdsToJunk = []) {
      // Group BEFORE hiding the rows — same reason as deleteEmails above.
      const groups = this.byOwnFolder(emailIdsToJunk);
      this.junkedEmailIds.push(...emailIdsToJunk);
      groups.forEach(([folder, ids]) =>
        this.$emailConnectorMailBoxService.markAsJunk(ids, folder)
          .then(junkResult => this.alertOnActionFailures(junkResult.failedJunkMoves ?? 0, 'junk'))
          .catch(() => this.alertOnActionFailures(ids.length, 'junk')));
    },
    /**
     * Puts quarantined messages back into the inbox — "Not spam".
     *
     * Optimistic like the Trash restore, with the same honest limit: the rescued
     * message reappears in the inbox at the next synchronization, so nothing is added
     * to a listing here.
     *
     * @param {Array} emailIdsToRestore the IMAP UIDs, within the Spam folder
     * @returns {void}
     */
    restoreFromJunk(emailIdsToRestore = []) {
      if (!emailIdsToRestore.length) {
        return;
      }
      this.unjunkedEmailIds.push(...emailIdsToRestore);
      this.$emailConnectorMailBoxService.restoreFromJunk(emailIdsToRestore)
        .then(restoreResult => this.alertOnActionFailures(restoreResult.failedJunkRestores ?? 0, 'notJunk'))
        .catch(() => this.alertOnActionFailures(emailIdsToRestore.length, 'notJunk'));
    },
    /**
     * The error alert every one of the six mail actions raises, and the place the
     * partial-failure story is told: a selection can fail halfway, the earlier messages
     * having already moved, so the count is what is shown rather than "it failed".
     *
     * One alert for all four rather than a copy per action, because the count they are
     * reporting only recently started meaning the same thing in all of them: a delete or
     * an archive the mail server did not perform used to be counted as nothing at all
     * and shown as a success (EXO-89367). The four i18n keys differ only by the verb.
     *
     * Nothing is put back into the listing on failure. The failed rows are back in the
     * database (the backend re-created them), so the honest way to see them again is the
     * reload the alert's link offers — re-inserting them here from client-side memory
     * would be this drawer guessing at what the server decided.
     *
     * @param {Number} failures how many messages the action could not be applied to
     * @param {String} action 'delete', 'archive', 'restore', 'purge', 'junk', 'notJunk',
     *        'move' or 'undoMove', which picks the message
     * @returns {void}
     */
    alertOnActionFailures(failures, action) {
      if (failures <= 0) {
        return;
      }
      const key = `emailConnector.mailBox.list.drawer.${action}.${failures === 1 && 'email' || 'emails'}.error`;
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {
        alertType: 'error',
        alertMessage: this.$t(key, { 0: failures }),
        alertLinkText: this.$t('emailConnector.mailBox.list.drawer.see.label'),
        alertLinkCallback: () => this.$emailConnectorCommonService.openEmailBox(),
      }}));
    },
    /**
     * Moves messages to the Archive, one request per folder they are listed in.
     *
     * @param {Array<Number>} emailIdsToArchive the IMAP UIDs to archive
     * @returns {void}
     */
    archiveEmails(emailIdsToArchive = []) {
      // Group BEFORE hiding the rows — same reason as deleteEmails above.
      const groups = this.byOwnFolder(emailIdsToArchive);
      this.archivedEmailIds.push(...emailIdsToArchive);
      groups.forEach(([folder, ids]) =>
        this.$emailConnectorMailBoxService.archiveEmails(ids, folder)
          .then(archiveResult => this.alertOnActionFailures(archiveResult.failedArchives ?? 0, 'archive'))
          .catch(() => this.alertOnActionFailures(ids.length, 'archive')));
    },
    /**
     * Moves messages into one of the user's own folders, one request per folder they
     * are listed in. Optimistic in both folders: the rows leave the listing at once,
     * and the destination shows them at once too (rememberMovedRows) -- the server
     * re-reads it in the background (EXO-89966) and lists them under the UIDs the COPY
     * gave them, at which point the server's rows take over. Until EXO-89966 the
     * destination listed nothing until its next scheduled check, so a message filed
     * and looked for right away was not there.
     *
     * Honesty first, as for the Undo: a request the server could not honour, in whole
     * or in part, takes its remembered rows out of the destination BEFORE the error
     * toast, because the server says how many did not move, not which, and a row kept
     * on a guess would be this drawer showing a message in a folder it never reached.
     * The ones that did move are listed once the destination's re-read lands.
     *
     * The one action here that says something on success -- see offerUndoMove for why
     * it is the one, and why only when every request succeeded: a batch that failed in
     * part is the error toast's alone. The server put the failed rows back, and an Undo
     * covering "the ones that moved" would be this drawer guessing which those are.
     *
     * @param {Array<Number>} emailIdsToMove the IMAP UIDs to move
     * @param {String} target the destination's key (CUSTOM:<id>, INBOX or ARCHIVE)
     * @returns {Promise} resolving once every request has answered
     */
    moveEmails(emailIdsToMove = [], target) {
      if (!target) {
        return Promise.resolve();
      }
      // Group BEFORE hiding the rows — same reason as deleteEmails above.
      const groups = this.byOwnFolder(emailIdsToMove);
      // The undo's addresses, taken now for the same reason: it names the messages by
      // Message-ID, the one thing they keep across a move (the COPY renumbers the UID),
      // and the row is the only place this drawer can read it from.
      const undoGroups = groups.map(([folder, ids]) => ({
        folder,
        mailHeaderIds: ids.map(id => this.rowOfEmail(id)?.mailHeaderId || null),
        // The rows themselves, for the Undo to put back into the listing at once (see
        // undoMove) -- stamped with their folder, which an inbox row leaves implicit.
        rows: ids.map(id => ({ ...this.rowOfEmail(id), folder })),
      }));
      // The destination's rows, remembered per request so a request the server refused
      // forgets exactly its own.
      const filed = undoGroups.map(group => this.rememberMovedRows(group.rows, target));
      this.movedEmailIds.push(...groups.flatMap(([folder, ids]) => ids.map(id => ({ folder, id }))));
      const requests = groups.map(([folder, ids], index) =>
        this.$emailConnectorMailBoxService.moveEmails(ids, folder, target)
          .then(moveResult => moveResult.failedMoves ?? 0, () => ids.length)
          .then(failures => {
            if (failures > 0) {
              this.forgetRefreshPendingRows(filed[index]);
            }
            this.alertOnActionFailures(failures, 'move');
            return failures;
          }));
      return Promise.all(requests).then(failures => {
        if (failures.every(count => count === 0)) {
          this.offerUndoMove(undoGroups, target, emailIdsToMove.length);
        }
        // The destination is usually not the folder on screen, and arms its own watch
        // when it is opened (loadEmailBox). It IS on screen when a batch picked from a
        // search across folders was filed into the one being listed.
        if (this.refreshPendingRows.some(row => row.folder === this.currentFolder)) {
          this.watchRefreshPendingRows();
        }
      });
    },
    /**
     * Remembers the rows a "Move to..." just filed, as the destination will list them
     * until the server's re-read does: the Undo's snapshot (undoMove), taken for the
     * folder the messages went TO rather than the one they came from, which changes
     * two things about the row.
     *
     * Its UID: a UID numbers a message within ONE folder, so the number the row had in
     * its origin means nothing in the destination and may well be a real row's there --
     * a lookup by it (openEmailDetailContent's guard, rowOfEmail) would answer for the
     * wrong row. It is negated instead: a number no folder holds, that no lookup by the
     * destination's own UIDs can answer with, and that the server would count as one
     * failure rather than refuse the whole request over if it ever reached it.
     *
     * Its conversation: the list groups rows by thread, and a snapshot grouped under a
     * conversation the destination already lists would hand that conversation's row
     * its placeholder UID to act on. It is listed on its own, keyed by its Message-ID,
     * until the server's re-read places the real row.
     *
     * A row with no Message-ID is not remembered at all: nothing could ever recognise
     * the server's row for it (pruneRefreshPendingRows matches by Message-ID), so it
     * would sit inert until its expiry.
     *
     * @param {Array} rows the rows as they were listed, each stamped with its origin folder
     * @param {String} target the destination's key
     * @returns {Array} the remembered rows, for the request's revert
     */
    rememberMovedRows(rows, target) {
      const refreshExpiresAt = Date.now() + REFRESH_WATCH_MAX_MS;
      const remembered = rows
        .filter(row => row.mailHeaderId)
        .map(row => ({
          ...row,
          folder: target,
          mailRemoteId: -Math.abs(row.mailRemoteId),
          threadId: null,
          threadCount: null,
          refreshPending: true,
          refreshExpiresAt,
        }));
      this.refreshPendingRows.push(...remembered);
      return remembered;
    },
    /**
     * The move's toast: "Moved to <folder>" with an Undo. Every other action here says
     * nothing on success (alertOnActionFailures returns on zero), and this one is no
     * reassurance either -- the rows leaving the list already say the move happened.
     * What earns the interruption is the Undo: a misfiled message is otherwise found
     * again in the target folder and moved back by hand. Which is why the toast is not
     * shown when the Undo could not work -- a row with no Message-ID cannot be found
     * again by identity, and a toast whose Undo fails is worse than none.
     *
     * @param {Array} undoGroups [{folder, mailHeaderIds}] per folder the rows came from
     * @param {String} target the folder the messages went to
     * @param {Number} count how many messages moved
     * @returns {void}
     */
    offerUndoMove(undoGroups, target, count) {
      if (!count || undoGroups.some(group => group.mailHeaderIds.some(id => !id))) {
        return;
      }
      const folderName = this.folderLabelOf(target);
      // Single-shot, and the toast closes on the click. The snackbar does not close
      // itself on a link click (social's Notifications leaves it up for its timeout),
      // and a second Undo would either find the message already gone and report a
      // failure for an undo that worked, or -- while the first is still running --
      // copy the message back a second time: the server looks the message up before
      // either request flags it, so nothing server-side can close that window.
      let undone = false;
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {
        alertType: 'success',
        alertMessage: count === 1
          ? this.$t('emailConnector.mailBox.list.drawer.move.email.success', { 0: folderName })
          : this.$t('emailConnector.mailBox.list.drawer.move.emails.success', { 0: count, 1: folderName }),
        alertLinkText: this.$t('emailConnector.mailBox.list.drawer.move.undo.label'),
        alertLinkCallback: () => {
          if (undone) {
            return Promise.resolve(null);
          }
          undone = true;
          document.dispatchEvent(new CustomEvent('close-alert-message'));
          return this.undoMove(undoGroups, target);
        },
      }}));
    },
    /**
     * Puts the moved messages back, each group into the folder it came from -- and back
     * into the listing at once, before the server answers: the move's optimistic
     * removal, mirrored. The server answers on the IMAP move-back and re-reads the
     * folder in the background (seconds on a large inbox -- the wait the Undo click
     * itself paid until EXO-89963), so the rows would otherwise show up at some later
     * reload, and an Undo that shows nothing reads as an Undo that did nothing.
     *
     * Honesty over comfort, in both directions. A group the server could not honour, in
     * whole or in part, leaves the listing again and takes the error toast every other
     * action takes: the server says how many did not go back, not which, and a row kept
     * on a guess would be this drawer claiming a move-back the server refused
     * (alertOnActionFailures's reason). The ones that DID go back are listed once the
     * folder's re-read lands -- which is why a PARTLY failed undo polls for its whole
     * budget (refreshWatchUntilDeadline): with no remembered row to wait for, the poll
     * would otherwise end at once and the messages that did go back would sit in the
     * mirror unlisted. And a remembered row is a snapshot: it carries the UID it had
     * before the move, which the server's re-read replaces, so nothing can act on it yet
     * (it renders inert, refreshPending), it gives way to the server's row the moment a
     * reload lists the message again (pruneRefreshPendingRows) -- the watch armed below polls
     * for exactly that -- and it is dropped once the re-read's budget is spent
     * (REFRESH_WATCH_MAX_MS) rather than kept as a row nothing can open.
     *
     * The destination's side of the same honesty: the rows the move had shown there
     * (rememberMovedRows) leave it first, whatever the server then says. An undo it
     * refuses leaves the message in the destination, where that folder's own re-read
     * -- seconds old by now -- has listed it under the server's own row; and which of a
     * group's messages went back is not the drawer's to guess, so none of the group's
     * snapshots may stay on that guess.
     *
     * @param {Array} undoGroups [{folder, mailHeaderIds, rows}] per folder the rows came from
     * @param {String} target the folder the messages are in now
     * @returns {Promise} resolving once every request has answered
     */
    undoMove(undoGroups, target) {
      const leaving = new Set(undoGroups.flatMap(group => group.mailHeaderIds));
      this.forgetRefreshPendingRows(this.refreshPendingRows
        .filter(row => row.folder === target && leaving.has(row.mailHeaderId)));
      const refreshExpiresAt = Date.now() + REFRESH_WATCH_MAX_MS;
      const remembered = undoGroups.map(group => group.rows.map(row => ({ ...row, refreshPending: true, refreshExpiresAt })));
      remembered.forEach(rows => this.refreshPendingRows.push(...rows));
      let partial = false;
      return Promise.all(undoGroups.map((group, index) =>
        this.$emailConnectorMailBoxService.undoMoveEmails(group.mailHeaderIds, target, group.folder)
          .then(undoResult => undoResult.failedUndos ?? 0, () => group.mailHeaderIds.length)
          .then(failures => {
            if (failures > 0) {
              this.forgetRefreshPendingRows(remembered[index]);
              partial = partial || failures < group.mailHeaderIds.length;
            }
            return failures;
          })))
        .then(failures => {
          this.alertOnActionFailures(failures.reduce((sum, count) => sum + count, 0), 'undoMove');
          if (this.refreshPendingRows.length || partial) {
            this.watchRefreshPendingRows(partial);
          }
        })
        // Nothing above should reject; if something did, the toast's callback would
        // leave the rejection unhandled.
        .catch(() => null);
    },
    /**
     * The listed rows with the remembered ones (an Undo's, a move's) merged in, each at
     * its place by date, for the folder being listed -- unless the server already lists
     * the message (same Message-ID), in which case the server's row is the one shown.
     *
     * @param {Array} emails the listed rows, already narrowed by the optimistic removals
     * @returns {Array} the rows to show
     */
    withRefreshPendingRows(emails) {
      const pending = this.refreshPendingRows.filter(row => row.folder === this.currentFolder
        && !emails.some(e => e.mailHeaderId === row.mailHeaderId));
      if (!pending.length) {
        return emails;
      }
      const merged = emails.slice();
      pending.forEach(row => {
        const at = merged.findIndex(e => new Date(e.receivedDate) < new Date(row.receivedDate));
        merged.splice(at < 0 ? merged.length : at, 0, row);
      });
      return merged;
    },
    /**
     * Drops remembered rows: the server refused the undo or the move that put them
     * there, or a reload lists them on its own.
     *
     * @param {Array} rows the rows to forget
     * @returns {void}
     */
    forgetRefreshPendingRows(rows) {
      this.refreshPendingRows = this.refreshPendingRows.filter(row => !rows.includes(row));
    },
    /**
     * Forgets the remembered rows a reload just listed (same folder, same Message-ID)
     * -- from here on the server's row, with the UID the re-read gave it, is the one
     * the listing shows and acts on -- and the ones whose budget is spent without the
     * server listing them: a row nothing can act on is not kept.
     *
     * @returns {void}
     */
    pruneRefreshPendingRows() {
      if (!this.refreshPendingRows.length) {
        return;
      }
      const listed = this.emailBox?.emails || [];
      const now = Date.now();
      this.forgetRefreshPendingRows(this.refreshPendingRows.filter(row => row.refreshExpiresAt <= now
        || (row.folder === this.currentFolder && listed.some(e => e.mailHeaderId === row.mailHeaderId))));
    },
    /**
     * Polls the listing until the server lists the remembered rows of the folder being
     * listed (an Undo's, a move's) -- or, for a partly failed undo, for the whole
     * budget -- and for REFRESH_WATCH_MAX_MS at most, on the post-sync category watch's
     * own interval, not a second timer (see the email-sent listener for why two timers
     * over one listing is one too many). Armed by the Undo itself, and by loadEmailBox
     * when a folder holding remembered rows is opened. Ended by loadEmailBox.
     *
     * @param {Boolean} untilDeadline whether to poll for the whole budget regardless of
     *        the remembered rows
     * @returns {void}
     */
    watchRefreshPendingRows(untilDeadline = false) {
      this.refreshWatchDeadline = Date.now() + REFRESH_WATCH_MAX_MS;
      this.refreshWatchUntilDeadline = this.refreshWatchUntilDeadline || untilDeadline;
      this.startAutoRefresh();
    },
    /**
     * Stops the polling once nothing needs it any more. The interval has three owners
     * -- a running sync (open() and the synchronize-in-progress listener), the category
     * watch and the remembered-rows watch -- and one of them ending must not cut the
     * others short.
     *
     * @returns {void}
     */
    stopAutoRefreshWhenIdle() {
      if (!this.syncInProgress && !this.categoryWatchDeadline && !this.refreshWatchDeadline) {
        this.stopAutoRefresh();
      }
    },
    /**
     * A folder's name as the listing shows it, from the descriptors the server listed
     * with the mailbox -- the raw key when it listed no such folder.
     *
     * @param {String} key the folder key
     * @returns {String} the name to show
     */
    folderLabelOf(key) {
      const folder = (this.folders || []).find(candidate => candidate.key === key);
      return folder ? this.$emailConnectorMailBoxService.folderLabel(folder, this.$t.bind(this)) : key;
    },
    async loadEmailBox() {
      const wasSyncing = this.syncInProgress;
      this.emailBox = await this.$emailConnectorMailBoxService.getEmailBox(this.currentFolder, this.favoriteOnly);
      this.pruneRefreshPendingRows();
      // A folder opened while rows a "Move to..." filed into it are still on their way:
      // the move happened with another folder on screen, so the watch the Undo arms for
      // itself (undoMove) is armed here, for exactly the reload that lists them.
      if (!this.refreshWatchDeadline && this.refreshPendingRows.some(row => row.folder === this.currentFolder)) {
        this.watchRefreshPendingRows();
      }
      // The folder list, kept on the root for the menus and the move-to picker: they
      // are created on click, deep under this drawer, and read it at that moment. Not
      // reactive, on purpose -- a property added to the root after creation is not --
      // which every reader tolerates: the pickers read it when they open, and the bulk
      // toolbar's computed re-reads it whenever the selection changes. The one case it
      // does not cover, a folder opted in from the settings while a selection is held
      // open, resolves at the next click.
      this.$root.mailFolders = this.folders;
      // `emails` is a computed off `emailBox`, so it follows the line above on its own.
      // Assigning to it did nothing except log "computed property was assigned to but it
      // has no setter" on every load, and once per poll while a watch was running.
      this.syncInProgress = !this.emailBox.emailSyncStatus || this.emailBox.emailSyncStatus === 'IN_PROGRESS';
      this.webmailUrl = this.emailBox.webmailUrl;
      this.$root.$emit('refresh-emails', this.emails);
      if (this.syncInProgress) {
        // A new sync started: any previous post-sync watch is over.
        this.categoryWatchDeadline = null;
      } else {
        this.$root.$emit('synchronize-finished');
        // Only when a sync has just finished under us, or a watch armed by one is still
        // running. The watch exists to catch the categories that land in the minute after
        // a sync, so a mailbox that was already synced when the drawer opened has nothing
        // to wait for — and arming it there was the drawer re-requesting the whole mailbox
        // every 2s for up to thirty polls, on every open, to watch a number that could not
        // change. A running sync is polled by its own path (see the synchronize-in-progress
        // listener and open()), so nothing here is what keeps that up to date.
        if (wasSyncing || this.categoryWatchDeadline) {
          this.watchIncomingCategories();
        }
      }
      // The watch ends when the listed folder holds no remembered row any more (the
      // server lists the message, or the user moved on to another folder) unless a
      // partly failed undo asked for the whole budget, and in any case once the budget
      // is spent. A row of another folder stays remembered until that folder is listed
      // or its own expiry prunes it.
      if (this.refreshWatchDeadline
        && (Date.now() > this.refreshWatchDeadline
          || (!this.refreshWatchUntilDeadline && !this.refreshPendingRows.some(row => row.folder === this.currentFolder)))) {
        this.refreshWatchDeadline = null;
        this.refreshWatchUntilDeadline = false;
        this.stopAutoRefreshWhenIdle();
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
        this.stopAutoRefreshWhenIdle();
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