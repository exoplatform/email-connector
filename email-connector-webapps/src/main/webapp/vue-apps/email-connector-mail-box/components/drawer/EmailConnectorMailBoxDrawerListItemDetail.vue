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
  <!-- Expanding puts the mailbox list beside the message. Opened on its own — from
       the platform's search, or the Favorites drawer — there is no list to put there,
       so the wide layout has nothing to show on its left half and expanding is not
       offered. -->
  <exo-drawer
    id="emailDetailDrawer"
    ref="emailDetailDrawer"
    v-model="emailDetailDrawer"
    right
    :allow-expand="!standalone"
    @expand-updated="updateExpand"
    :loading="waitingForEmail || readerLoading || waitingForPartialEmail || (expanded && syncInProgress)"
    go-back-button
    :confirm-close="activeDownload"
    :confirm-close-labels="{
      title: $t('emailConnector.mailBox.attachment.download.confirmAbort.title'),
      message: $t('emailConnector.mailBox.attachment.download.confirmAbort.message'),
      ok: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.yes'),
      cancel: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.no')
    }"
    @confirm-close="onAbortDownloadConfirmed"
    @opened="onDrawerOpened"
    @closed="close"
    @mousedown.native="onListNavigationPointerDown">
    <template #title>
      <span></span>
    </template>
    <template v-if="expanded" #fullAppLeftTitle>
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
          class="d-flex align-center"
          :emails="filteredEmails"
          :webmail-url="webmailUrl"
          :selected-emails="selectedEmails"
          :select-mode="selectMode" 
          :sync-in-progress="syncInProgress" />
      </div>
    </template>
    <!-- Unread, archive and delete all address a message by its IMAP UID, which a
         draft may not have — and none of them means anything for a message nobody has
         sent. When the conversation was opened FROM its draft, the draft is what this
         toolbar would act on, so it stays away, exactly as the row's own swipe and
         context menu already do. -->
    <template v-if="!waitingForEmail" #titleIcons>
      <email-connector-mail-box-drawer-list-item-detail-actions
        v-if="email && !email.draftLocalId && (!expanded || !selectEmailPlaceHolder)"
        :email="email"
        :thread="threadContext" />
    </template>
    <template v-if="expanded" #fullAppLeftContent>
      <categories-filter
        v-model="selectedCategoryId"
        class="full-width border-box-sizing application-border application-border-radius py-3 pe-4 ps-7"
        object-type="email"
        hide-on-empty />
      <email-connector-mail-box-drawer-content
        ref="listContent"
        :emails="filteredEmails"
        :selected-emails="selectedEmails"
        :select-mode="selectMode"
        :email="email"
        @update:selected-emails="selectedEmails = $event"
        expanded />
    </template>
    <template v-if="emailDetailDrawer && !waitingForEmail" #content>
      <email-connector-mail-box-drawer-multi-select-email
        v-if="selectMode"
        :emails="filteredEmails"
        :selected-emails="selectedEmails" />
      <template v-else>
        <email-connector-mail-box-drawer-no-email v-if="filteredEmails.length === 0" />
        <template v-else>
          <email-connector-mail-box-drawer-select-email v-if="selectEmailPlaceHolder" />
          <!-- The reader tells the header which conversation it is showing; this drawer
               holds both and is the only place the value can pass between them. -->
          <email-connector-mail-box-drawer-thread-content
            v-else
            :email="email"
            :emails="filteredEmails"
            :expanded-drawer="expanded"
            :defer-thread-read="autoOpenReadPending"
            @thread-context="threadContext = $event"
            @loading="readerLoading = $event"
            @opened-partial="readerPartial = $event" />
        </template>
      </template>
    </template>
  </exo-drawer>
</template>

<script>
import { selectionKey } from '../../js/EmailConnectorMailBoxSelection.js';
import listNavigationMixin from '../../js/EmailConnectorMailBoxListNavigation.js';

// The dimmed page behind a drawer that opened on its own; kept by id so a second
// open can never leave two of them stacked.
const BACKDROP_ID = 'emailDetailDrawerBackdrop';

// The other actions that take messages out of the list, each carrying their ids first
// (the move, handled apart, carries its target after them). Delete and archive
// are followed in both layouts, as they always were; these and the move only in the expanded one,
// where the list is beside the reader (EXO-90414). In the narrow layout the toolbar
// closes the drawer after most of them -- but not after a move, and following one
// there would leave the reader on the "select an email" placeholder with no list.
const EXPANDED_LIST_REMOVAL_EVENTS = ['junk-email', 'not-junk-email', 'restore-email', 'purge-email'];

export default {
  // Expanded, this drawer shows the list beside the reader like the mailbox drawer
  // does, and moves through it the same way (EXO-90414).
  mixins: [listNavigationMixin],
  data() {
    return {
      emailDetailDrawer: false,
      // The opened message's own request is on its way. It only holds the reader back
      // (and shows the loading bar) when there is nothing to open the reader on yet —
      // see waitingForEmail.
      loadingEmail: false,
      // The reader is reading the conversation: the one wait the user sees once the
      // reader is open, relayed here so the drawer's own header bar shows it.
      readerLoading: false,
      // The reader shows the opened message as its list row only (no body yet).
      readerPartial: false,
      email: null,
      expanded: false,
      activeDownload: null,
      emails: [],
      // The list on screen is a set of search results rather than the folder's
      // cached window. Search reaches the whole mailbox, so those messages are
      // routinely outside the window and must not be reconciled against it.
      detachedFromList: false,
      standalone: false,
      selectedEmails: [],
      syncInProgress: false,
      webmailUrl: null,
      selectMode: false,
      selectEmailPlaceHolder: false,
      // The conversation the reader below is showing — {threadId, messages, subject} —
      // relayed to the toolbar in the title bar, which is a sibling of the reader and
      // would otherwise only ever see the single opened message.
      threadContext: null,
      selectedCategoryId: null,
      selectedCategoryIds: [],
    };
  },
  created() {
    // Which request for the opened message is current. Opening another message before
    // the previous one answered must neither paint the previous one over it nor turn
    // the loading bar off while the new one is still on its way. Not reactive.
    this.emailRequest = 0;
    // Leaving the opened message for the "select an email" placeholder — a delete, an
    // archive, a move, a spam report, the message dropping out of the list — drops the
    // request still reading it, whichever handler did it: its answer would otherwise
    // put the removed message straight back on screen. Synchronous, so no answer can
    // land between the switch and the drop.
    this.$watch('selectEmailPlaceHolder', placeholder => {
      if (placeholder) {
        this.supersedeEmailRequest();
      }
    }, { sync: true });
    // "Retry" on a message whose full copy could not be read.
    this.onRetryEmailRead = (email) => {
      if (this.emailDetailDrawer && this.email?.unavailable && email?.mailRemoteId === this.email.mailRemoteId) {
        // In the message's own folder: the list may hold another under its number.
        this.fetchEmail(email.mailRemoteId, { folder: this.email.folder });
      }
    };
    this.$root.$on('retry-email-read', this.onRetryEmailRead);
    // Each opening and each action below may say which folder its UIDs are numbered in,
    // as its last argument: this drawer's list may be a search's, holding several
    // folders where one number may be several messages, and a bare UID used to open --
    // or read, or act on -- the wrong one (EXO-90416).
    this.onOpenEmailDetailDrawer = (mailRemoteId, emails, syncInProgress, webmailUrl, detachedFromList, standalone, folder) => {
      this.detachedFromList = !!detachedFromList;
      // Opened with the mailbox behind it, this drawer sits on an already-dimmed page
      // and the platform's shared overlay covers it. Opened on its own -- from the
      // platform's search, or from the Favorites drawer -- nothing is dimming the
      // page underneath, so it draws its own.
      this.standalone = !!standalone;
      this.open(mailRemoteId, emails, syncInProgress, webmailUrl, folder);
    };
    this.onCloseEmailDetailDrawer = () => {
      if (!this.expanded) {
        this.close();
      }
    };
    this.onOpenEmailDetailContent = (mailRemoteId, folder) => {
      if (!this.emailDetailDrawer) {
        return; 
      }
      this.openEmailDetailContent(mailRemoteId, { folder });
    };
    this.onUpdateEmailReadStatus = (read, emails, folder) => {
      if (!this.emailDetailDrawer) {
        return; 
      }
      emails.filter(id => {
        const email = this.emails.find(e => e.mailRemoteId === id && (!folder || (e.folder || 'INBOX') === folder));
        // A row in a read-only folder is left bold. The mailbox drawer refuses to
        // push a read status for one (see its updateEmailsReadStatus), so showing it
        // turn read here would show a state nothing is saving — and opening a mail
        // is enough to get here, so every trashed mail merely LOOKED at would have
        // lost its unread mark until the list next reloaded.
        if (email && !this.$emailConnectorMailBoxService.isReadOnlyFolder(email.folder) && email.read !== read) {
          this.$set(email, 'read', read);
        }
      });
      this.selectEmailPlaceHolder = this.placeholderAfterReadStatus(read, emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.onDeleteOrArchiveEmail = (emails, folder) => {
      if (!this.emailDetailDrawer) {
        return; 
      }
      const listedBefore = this.filteredEmails;
      this.refreshEmails(emails, folder);
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
      // Expanded, the reader moves on to the conversation that took the removed one's
      // place rather than to the placeholder (listNavigationMixin) -- search results
      // included, the list this drawer was handed for them.
      this.openNextAfterRemoval(emails, listedBefore, folder);
    };
    // Mirror favorite changes (and their rollback after a refused push) onto this
    // drawer's own copies: the list it was opened with — a snapshot when it
    // shows search results, which the periodic refresh never overwrites — and
    // the opened message.
    this.onApplyEmailFavoriteStatus = (favorite, mailRemoteIds = []) => {
      const ids = new Set(mailRemoteIds);
      (this.emails || []).forEach(email => {
        if ((email.folder || 'INBOX') === 'INBOX' && ids.has(email.mailRemoteId)) {
          this.$set(email, 'starred', favorite);
        }
      });
      if (this.email && (this.email.folder || 'INBOX') === 'INBOX' && ids.has(this.email.mailRemoteId)) {
        this.$set(this.email, 'starred', favorite);
      }
    };
    // The same two openings, for a row the caller already holds in full — a draft,
    // which the UID-addressed pair above cannot open because it may have no UID.
    this.onOpenEmailThreadDrawer = (email, emails, syncInProgress, webmailUrl) => {
      this.detachedFromList = false;
      this.standalone = false;
      this.openThreadOn(email, emails, syncInProgress, webmailUrl);
    };
    this.onOpenEmailThreadContent = (email) => {
      if (!this.emailDetailDrawer) {
        return;
      }
      // Nothing to fetch: whatever message request was still on its way is superseded.
      this.supersedeEmailRequest();
      this.email = email;
      this.selectEmailPlaceHolder = false;
    };
    this.$root.$on('open-email-detail-drawer', this.onOpenEmailDetailDrawer);
    this.$root.$on('close-email-detail-drawer', this.onCloseEmailDetailDrawer);
    this.$root.$on('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$on('open-email-thread-drawer', this.onOpenEmailThreadDrawer);
    this.$root.$on('open-email-thread-content', this.onOpenEmailThreadContent);
    this.$root.$on('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$on('update-email-favorite-status', this.onApplyEmailFavoriteStatus);
    this.$root.$on('apply-email-favorite-status', this.onApplyEmailFavoriteStatus);
    this.$root.$on('delete-email', this.onDeleteOrArchiveEmail);
    this.$root.$on('archive-email', this.onDeleteOrArchiveEmail);
    this.onExpandedListRemoval = (emails, folder) => {
      if (this.expanded) {
        this.onDeleteOrArchiveEmail(emails, folder);
      }
    };
    // A move carries its target before the folder its ids are numbered in.
    this.onExpandedListMove = (emails, target, folder) => this.onExpandedListRemoval(emails, folder);
    EXPANDED_LIST_REMOVAL_EVENTS.forEach(event => this.$root.$on(event, this.onExpandedListRemoval));
    this.$root.$on('move-email', this.onExpandedListMove);
    this.$root.$on('attachment-download-started', (payload) => {
      this.activeDownload = payload;
    });
    this.$root.$on('attachment-download-finished', () => {
      this.activeDownload = null;
    });
    // "Select several" from the ⋮ menu: when this drawer is the one on screen,
    // enter its multi-select mode (the mailbox drawer ignores the event then).
    this.$root.$on('enter-select-mode', () => {
      if (this.emailDetailDrawer) {
        this.selectMode = true;
      }
    });
    this.$root.$on('select-email', ({ emailId, folder, selected }) => {
      if (!this.emailDetailDrawer) {
        return;
      }
      this.selectMode = true;
      // Kept by folder and UID (EXO-90416): in a list of search results one number may
      // be two messages, and ticking one must not tick the other.
      const key = selectionKey({ mailRemoteId: emailId, folder });
      if (selected) {
        if (!this.selectedEmails.includes(key)) {
          this.selectedEmails.push(key);
        }
      }
      else {
        this.selectedEmails = this.selectedEmails.filter(selected => selected !== key);
      }
    });
    this.$root.$on('synchronize-in-progress', () => {
      this.syncInProgress = true;
    });
    this.$root.$on('synchronize-finished', () => {
      this.syncInProgress = false;
    });
    this.$root.$on('refresh-emails', (emails) => {
      // The mailbox refreshes every couple of seconds while categories are still
      // landing, carrying the folder's cached window. That is not what is on screen
      // during a search: overwriting would drop every result found outside the window.
      if (this.detachedFromList) {
        return;
      }
      this.emails = emails;
    });
  },
  beforeDestroy() {
    this.hideStandaloneBackdrop();
    this.$root.$off('retry-email-read', this.onRetryEmailRead);
    this.$root.$off('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$off('open-email-detail-drawer', this.onOpenEmailDetailDrawer);
    this.$root.$off('open-email-thread-content', this.onOpenEmailThreadContent);
    this.$root.$off('open-email-thread-drawer', this.onOpenEmailThreadDrawer);
    this.$root.$off('close-email-detail-drawer', this.onCloseEmailDetailDrawer);
    this.$root.$off('delete-email', this.onDeleteOrArchiveEmail);
    this.$root.$off('archive-email', this.onDeleteOrArchiveEmail);
    EXPANDED_LIST_REMOVAL_EVENTS.forEach(event => this.$root.$off(event, this.onExpandedListRemoval));
    this.$root.$off('move-email', this.onExpandedListMove);
    this.$root.$off('update-email-favorite-status', this.onApplyEmailFavoriteStatus);
    this.$root.$off('apply-email-favorite-status', this.onApplyEmailFavoriteStatus);
  },
  computed: {
    // Nothing to open the reader on yet: the message was not found in the list the
    // drawer was handed (a search hit, a favorite), so the reader waits for the
    // server's copy of it, under the loading bar.
    waitingForEmail() {
      return this.loadingEmail && !this.email;
    },
    // The reader still shows the opened message as its bare list row while this
    // drawer's request for it is pending: that body is what the user is waiting for.
    // Asked of the reader rather than of `email`, which stays the list row until this
    // request answers even when the conversation already brought the message in full.
    // Ends with the request either way, so a failed fetch cannot leave the bar stuck.
    waitingForPartialEmail() {
      return this.loadingEmail && this.readerPartial;
    },
    title() {
      if (!this.selectMode) {
        return this.$t('emailConnector.mailBox.list.drawer.title');
      }
      return `${this.selectedEmails.length} ${this.selectedEmails.length === 1 ? 
        this.$t('emailConnector.mailBox.list.drawer.emailSelected') : 
        this.$t('emailConnector.mailBox.list.drawer.emailsSelected')}`;
    },
    filteredEmails() {
      let filteredEmails = this.emails || [];
      if (this.selectedCategoryIds.length > 0) {
        filteredEmails = filteredEmails.filter(e => this.selectedCategoryIds.some(id => e.categoryIds.includes(id)));
      }
      return filteredEmails;
    },
    /**
     * The listed messages, for listNavigationMixin.
     *
     * @returns {Array} the list beside the reader
     */
    navigationEmails() {
      return this.filteredEmails;
    },
    /**
     * Whether Up and Down walk the list: only expanded, where there is a list beside
     * the reader -- search results included -- and not during a multi-selection.
     *
     * @returns {Boolean} true when the arrow keys drive the list
     */
    canNavigateList() {
      return this.emailDetailDrawer && this.expanded && !this.selectMode && this.filteredEmails.length > 0;
    },
    /**
     * Whether the drawer is open, for listNavigationMixin: the arrow keys are listened
     * to on the whole page only then.
     *
     * @returns {Boolean} true while the drawer is open
     */
    navigationDrawerOpen() {
      return this.emailDetailDrawer;
    },
  },
  watch: {
    async selectedCategoryId(val) {
      this.cancelSelectMode();
      this.selectedCategoryIds = val && await this.$emailConnectorMailBoxService.getSubcategoryIds(val) || [];
    },
    filteredEmails() {
      // A message opened from outside this list -- a search hit, or one picked from
      // the global Favorites drawer -- comes from the whole mailbox, so its absence
      // here means nothing and must not send the reader back to the placeholder,
      // which is exactly what happened on the first refresh after opening one.
      if (this.detachedFromList) {
        return;
      }
      if (this.email && !this.filteredEmails.some(e => e.mailRemoteId === this.email.mailRemoteId)) {
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
    /**
     * Opens the drawer on one message of a list.
     *
     * @param {Number} mailRemoteId the message's IMAP UID
     * @param {Array} emails the list it was opened from
     * @param {Boolean} syncInProgress whether a synchronization is running
     * @param {String} webmailUrl the account's webmail, for the toolbar
     * @param {String} folder the folder the UID is numbered in, when the opener knows it
     * @returns {void}
     */
    open(mailRemoteId, emails, syncInProgress, webmailUrl, folder = null) {
      this.emailDetailDrawer = true;
      this.selectEmailPlaceHolder = false;
      this.emails = emails;
      this.webmailUrl = webmailUrl;
      this.syncInProgress = syncInProgress;
      this.$root.isDetailDrawerActive = true;
      const ownFolder = this.folderOf(mailRemoteId, folder);
      // With what the list knows of it, so a message already read is not pushed again.
      const listed = (emails || []).find(e => e.mailRemoteId === mailRemoteId && (e.folder || 'INBOX') === ownFolder);
      this.$root.$emit('update-email-read-status', true, [mailRemoteId], ownFolder, listed?.read);
      this.fetchEmail(mailRemoteId, { folder: ownFolder });
    },
    /**
     * Opens the reader on a message and fetches the server's full copy of it.
     * <p>
     * The reader opens AT ONCE on the list row when the drawer holds one: the row
     * carries the subject, the conversation id and what each collapsed strip shows,
     * so the reader starts reading the conversation in parallel with this request
     * instead of after it. The full copy replaces the row when it answers. Only a
     * message the list does not hold — a search hit, a favorite, both detached from
     * the list — has to wait for it, and that wait is the loading bar.
     * <p>
     * A response to an earlier request is dropped: it would otherwise put the previous
     * message back on screen, or end the loading bar while the current one is pending.
     *
     * An automatic opening (options.automatic, EXO-90414) reads the message without
     * counting it as opened; listNavigationMixin counts it, and reads it, once the user
     * stayed on it. Any opening ends the wait of the one before.
     *
     * @param {number} mailRemoteId - the IMAP UID of the message to open
     * @param {object} options - {automatic}: whether the user did not ask for this mail;
     *          {folder}: the folder it is numbered in, when the caller knows it
     *          (EXO-90416: a search list holds several folders)
     * @returns {Promise<object|null>} the full message, or null when the request was
     *          superseded or failed
     */
    fetchEmail(mailRemoteId, options = {}) {
      this.cancelAutoOpenDwell();
      const request = ++this.emailRequest;
      const ownFolder = this.folderOf(mailRemoteId, options.folder);
      const row = !this.detachedFromList && this.listedEmail(mailRemoteId, ownFolder) || null;
      // Clicking the message already open keeps its full copy on screen while it is
      // re-read, rather than stepping back to the bare list row.
      const alreadyOpen = row && this.email && !this.email.unavailable && !this.$emailConnectorMailBoxService.isListingRow(this.email)
        && this.email.mailRemoteId === row.mailRemoteId && (this.email.folder || 'INBOX') === (row.folder || 'INBOX');
      if (!alreadyOpen) {
        this.email = row;
      }
      if (row) {
        // The reader is showing this message from now on, so the placeholder is down —
        // and leaving the message again (a delete, a move) is a real switch the
        // request drop above can see.
        this.selectEmailPlaceHolder = false;
      }
      this.loadingEmail = true;
      const read = options.automatic
        ? this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId, ownFolder, { broadcast: false })
        : this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId, ownFolder);
      return read
        .then(email => {
          if (request !== this.emailRequest) {
            return null;
          }
          this.email = email;
          this.selectEmailPlaceHolder = false;
          return email;
        })
        .catch(() => {
          if (request === this.emailRequest && row) {
            this.settleFailedRow(row);
          }
          return null;
        })
        .finally(() => {
          if (request === this.emailRequest) {
            this.loadingEmail = false;
          }
        });
    },
    /**
     * Drops whatever request for the opened message is still on its way, so its answer
     * can neither put a message the user has left back on screen nor end the loading
     * state of a later one.
     *
     * @returns {void}
     */
    supersedeEmailRequest() {
      this.emailRequest++;
      this.loadingEmail = false;
      // The previous mail's wait is over, whatever comes next (EXO-90414).
      this.cancelAutoOpenDwell();
    },
    /**
     * The full copy of the opened message could not be read. When the reader was
     * still waiting on it for the body, say so, and let the row stand as the message
     * rather than as a skeleton of it that nothing will ever fill.
     *
     * @param {object} row - the list row the reader was opened on
     * @returns {void}
     */
    settleFailedRow(row) {
      if (this.readerPartial) {
        document.dispatchEvent(new CustomEvent('alert-message', {detail: {
          alertType: 'error',
          alertMessage: this.$t('emailConnector.mailBox.search.openError'),
        }}));
      }
      if (this.email === row) {
        this.email = this.$emailConnectorMailBoxService.settleListingRow(row);
      }
    },
    /**
     * The row of the list this drawer was handed for a message, if it holds one.
     *
     * @param {number} mailRemoteId - the IMAP UID of the message
     * @param {string} folder - the folder it is numbered in, when known
     * @returns {object|undefined} the list row
     */
    listedEmail(mailRemoteId, folder = null) {
      return (this.emails || []).find(e => e.mailRemoteId === mailRemoteId && (!folder || (e.folder || 'INBOX') === folder));
    },
    /**
     * Opens the reader on a row the caller already holds in full, without going back
     * to the server for it.
     *
     * The ordinary open re-fetches by IMAP UID, which a draft may simply not have —
     * it has none until it has been uploaded, and the number moves under it when it
     * is. The row the list is holding is the whole row, and the reader only reads its
     * thread id and its subject off it before fetching the conversation itself, so
     * there is nothing left for a fetch to add.
     *
     * @param {object} email - the row to open the conversation of
     * @param {Array} emails - the list it came from
     * @param {boolean} syncInProgress - whether a synchronization is running
     * @param {string} webmailUrl - the account's webmail, for the toolbar
     * @returns {void}
     */
    openThreadOn(email, emails, syncInProgress, webmailUrl) {
      this.emailDetailDrawer = true;
      // Nothing to fetch: whatever message request was still on its way is superseded.
      this.supersedeEmailRequest();
      this.emails = emails;
      this.webmailUrl = webmailUrl;
      this.syncInProgress = syncInProgress;
      this.selectEmailPlaceHolder = false;
      this.$root.isDetailDrawerActive = true;
      this.email = email;
    },
    // IMAP UIDs are per-folder, so opening a Sent/Archive message needs its folder,
    // taken from the currently-listed emails.
    //
    // A folder the caller gives wins: in a search list the first row carrying the number
    // may be another folder's message (EXO-90416).
    folderOf(mailRemoteId, folder = null) {
      if (folder) {
        return folder;
      }
      const email = (this.emails || []).find(e => e.mailRemoteId === mailRemoteId);
      return email && email.folder || 'INBOX';
    },
    /**
     * Switches the open reader to another message of the list, the wide layout's
     * click on a row beside it.
     *
     * An automatic opening (options.automatic) does not mark it read: listNavigationMixin
     * does, once the user stayed on it.
     *
     * @param {number} mailRemoteId - the IMAP UID of the message to open
     * @param {object} options - {automatic, folder}: see fetchEmail
     * @returns {Promise<void>} resolved once the message is on screen, or dropped
     */
    openEmailDetailContent(mailRemoteId, options = {}) {
      this.selectEmailPlaceHolder = false;
      const ownFolder = this.folderOf(mailRemoteId, options.folder);
      return this.fetchEmail(mailRemoteId, { ...options, folder: ownFolder }).then(email => {
        if (email && !options.automatic) {
          this.$root.$emit('update-email-read-status', true, [mailRemoteId], ownFolder, email.read);
          // After the emit, as it always was: the read-status handler recomputes the
          // placeholder for the list it was handed, and in the wide layout that
          // answer is "show the placeholder" for the very message just opened.
          this.selectEmailPlaceHolder = false;
        }
      });
    },
    /**
     * Opens a listed message in the reader and lights its row, as a click on the row
     * does -- for listNavigationMixin, which passes {automatic} for an opening the user
     * did not ask for.
     *
     * @param {Object} row the listed message
     * @param {Object} options {automatic}: whether the user did not ask for this mail
     * @returns {Promise} resolved once the message is on screen
     */
    openListedEmail(row, options = {}) {
      this.$root.$emit('set-opened', row.mailRemoteId);
      return this.openEmailDetailContent(row.mailRemoteId, { ...options, folder: row.folder || 'INBOX' }).catch(() => null);
    },
    /**
     * The drawer, for listNavigationMixin to tell whether it is the one on top.
     *
     * @returns {Object} the exo-drawer
     */
    navigationDrawer() {
      return this.$refs.emailDetailDrawer;
    },
    /**
     * The list beside the reader, for listNavigationMixin.
     *
     * @returns {Object} the list content component, or null when not expanded
     */
    navigationList() {
      return this.$refs.listContent || null;
    },
    /**
     * Takes messages out of this drawer's list -- only the given folder's when the
     * emitter says which: a search list holds several folders (EXO-90416).
     *
     * @param {Array<Number>} emailIds the IMAP UIDs
     * @param {String} folder the folder they are numbered in, when known
     * @returns {void}
     */
    refreshEmails(emailIds = [], folder = null) {
      this.emails = this.emails.filter(
        e => !emailIds.includes(e.mailRemoteId) || (folder && (e.folder || 'INBOX') !== folder)
      );
    },
    cancelSelectMode() {
      this.selectMode = false;
      this.selectedEmails = [];
    },
    /**
     * Whether the wide layout shows the "select an email" placeholder after a read
     * status was applied to some messages.
     * <p>
     * Marking UNREAD is the "mark unread and put it away" intent, and puts away the
     * opened message when it is among them. Marking READ never does: it is what
     * opening a message does to its conversation — this drawer marks the opened
     * message read as it opens it, and the reader marks the rest read as soon as the
     * conversation lands, possibly before the opened message's own request has
     * answered. Sending the reader to the placeholder then dropped that request and
     * left the user facing "Select an email". It only shows the placeholder when
     * nothing is open.
     *
     * @param {boolean} read - the read status applied
     * @param {Array<Number>} emails - the messages it was applied to
     * @returns {boolean} whether to show the placeholder
     */
    placeholderAfterReadStatus(read, emails) {
      if (read) {
        return this.selectEmailPlaceHolder || (this.expanded && !this.email);
      }
      return this.canDisplaySelectEmailPlaceHolder(emails);
    },
    canDisplaySelectEmailPlaceHolder(emails) {
      return this.expanded && (!this.email || emails.includes(this.email.mailRemoteId));
    },
    onAbortDownloadConfirmed() {
      this.$root.$emit('abort-download-attachment', this.activeDownload.mailRemoteId, this.activeDownload.attachmentRemoteId, this.activeDownload.abortController);
      this.close();
    },
    /**
     * Puts the backdrop up once the drawer reports itself open.
     *
     * Not before: the drawer is given its z-index as it opens, so a backdrop placed
     * on the same tick was measured against a drawer that did not have one yet, and
     * landed too low to be seen -- which is why the dimming only showed from the
     * second open onwards.
     *
     * @returns {void}
     */
    onDrawerOpened() {
      if (this.standalone && !this.pageAlreadyDimmed()) {
        this.showStandaloneBackdrop();
      }
    },
    /**
     * Whether a scrim is already covering the page.
     * <p>
     * Narrow on purpose: this is not the drawer taking over the platform's
     * dimming, it is the backdrop we paint ourselves refusing to be a second one.
     * "Standalone" is judged from whether the mailbox LIST is a drawer, which is
     * a different question -- opened from the platform search, the page is
     * already dimmed by the search overlay, and ours landed on top of it.
     * <p>
     * Present is not the same as covering: an overlay on its way out keeps its
     * class while its scrim fades, so this asks what is displayed, opaque and
     * sized.
     *
     * @returns {boolean} true when something else is dimming the page
     */
    pageAlreadyDimmed() {
      return Array.from(document.querySelectorAll('.v-overlay--active')).some(overlay => {
        const style = window.getComputedStyle(overlay);
        const scrim = overlay.querySelector('.v-overlay__scrim');
        const opacity = parseFloat((scrim && window.getComputedStyle(scrim).opacity) || style.opacity || '1');
        return style.display !== 'none' && style.visibility !== 'hidden' && opacity > 0.05
            && overlay.getBoundingClientRect().height > 0;
      });
    },
    /**
     * Dims the page behind a drawer that opened with nothing behind it.
     *
     * The platform's shared overlay is driven by the page's own drawer stack and
     * does not cover this one when it opens alone, from the platform's search or the
     * Favorites drawer. Asking exo-drawer for its overlay instead is worse: that
     * renders Vuetify's scrim, which stacks ABOVE this drawer because the drawer's
     * z-index is set by hand. So the backdrop is placed here, one step below whatever
     * z-index the drawer ended up with, and clicking it closes the message like any
     * other drawer.
     *
     * @returns {void}
     */
    showStandaloneBackdrop() {
      this.hideStandaloneBackdrop();
      const drawer = this.$refs.emailDetailDrawer;
      const drawerZIndex = Number(drawer?.zIndex)
        || Number(drawer?.$el && window.getComputedStyle(drawer.$el).zIndex)
        || 2000;
      const backdrop = document.createElement('div');
      backdrop.id = BACKDROP_ID;
      backdrop.style.cssText = `position:fixed;top:0;left:0;right:0;bottom:0;background-color:rgba(0,0,0,0.46);z-index:${drawerZIndex - 1};`;
      backdrop.addEventListener('click', () => this.close());
      document.body.appendChild(backdrop);
    },
    /**
     * Removes the backdrop, whether the message was closed or the drawer destroyed.
     *
     * @returns {void}
     */
    hideStandaloneBackdrop() {
      document.getElementById(BACKDROP_ID)?.remove();
    },
    close() {
      // A message request still on its way belongs to a reader that is gone.
      this.supersedeEmailRequest();
      this.readerLoading = false;
      this.readerPartial = false;
      this.detachedFromList = false;
      this.standalone = false;
      this.hideStandaloneBackdrop();
      this.emailDetailDrawer = false;
      this.cancelSelectMode();
      this.selectEmailPlaceHolder = false;
      this.email = null;
      this.threadContext = null;
      this.$root.isDetailDrawerActive = false;
      this.$root.$emit('email-detail-drawer-closed');
      this.selectedCategoryId = null;
      this.selectedCategoryIds = [];
    },
    updateExpand(expanded) {
      window.setTimeout(() => this.expanded = expanded, 200);
      if (!expanded && (this.selectEmailPlaceHolder || this.selectMode)) {
        this.close();
      }
    },
  }
};
</script>