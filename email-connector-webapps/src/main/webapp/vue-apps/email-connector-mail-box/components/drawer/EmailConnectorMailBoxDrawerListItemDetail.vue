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
  <!-- There is one full-screen layout, the mailbox drawer's (EXO-90415): this drawer's
       expand button hands the mail over to it and closes, rather than widening this
       drawer -- exo-drawer's own expand is off, or the drawer would go to full width
       and then close under the user. Opened on its own -- from the platform's search,
       or the Favorites drawer -- there is no mailbox behind it to hand the mail to, and
       expanding is not offered. -->
  <exo-drawer
    id="emailDetailDrawer"
    ref="emailDetailDrawer"
    v-model="emailDetailDrawer"
    right
    :allow-expand="false"
    :loading="waitingForEmail || readerLoading || waitingForPartialEmail"
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
    @closed="close">
    <template #title>
      <span></span>
    </template>
    <!-- Unread, archive and delete all address a message by its IMAP UID, which a
         draft may not have — and none of them means anything for a message nobody has
         sent. When the conversation was opened FROM its draft, the draft is what this
         toolbar would act on, so it stays away, exactly as the row's own swipe and
         context menu already do. -->
    <template v-if="!waitingForEmail" #titleIcons>
      <email-connector-mail-box-drawer-list-item-detail-actions
        v-if="email && !email.draftLocalId"
        :email="email"
        :thread="threadContext" />
      <v-btn
        v-if="canExpandInMailBox"
        :title="$t('label.expand')"
        icon
        @click="expandInMailBox">
        <v-icon size="20">fas fa-expand-alt</v-icon>
      </v-btn>
    </template>
    <template v-if="emailDetailDrawer && !waitingForEmail" #content>
      <!-- A mail of a shared mailbox says whose it is here too (plan 7.6). -->
      <email-connector-shared-mailbox-band
        v-if="sharedMailbox"
        :entry="sharedMailbox" />
      <email-connector-mail-box-drawer-no-email v-if="emails.length === 0" />
      <template v-else>
        <email-connector-mail-box-drawer-select-email v-if="selectEmailPlaceHolder" />
        <!-- The reader tells the header which conversation it is showing; this drawer
             holds both and is the only place the value can pass between them. -->
        <email-connector-mail-box-drawer-thread-content
          v-else
          :email="email"
          :emails="emails"
          @thread-context="threadContext = $event"
          @loading="readerLoading = $event"
          @opened-partial="readerPartial = $event" />
      </template>
    </template>
  </exo-drawer>
</template>

<script>
// The dimmed page behind a drawer that opened on its own; kept by id so a second
// open can never leave two of them stacked.
const BACKDROP_ID = 'emailDetailDrawerBackdrop';

// The mail drawer is the narrow reader only: its full screen is the mailbox drawer's,
// which its expand button hands the mail over to (EXO-90415). The list, the selection,
// the category filter and the arrow keys it carried for a full screen of its own are
// the mailbox drawer's alone.
export default {
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
      activeDownload: null,
      emails: [],
      // The list on screen is a set of search results rather than the folder's
      // cached window. Search reaches the whole mailbox, so those messages are
      // routinely outside the window and must not be reconciled against it.
      detachedFromList: false,
      standalone: false,
      syncInProgress: false,
      webmailUrl: null,
      selectEmailPlaceHolder: false,
      // The conversation the reader below is showing — {threadId, messages, subject} —
      // relayed to the toolbar in the title bar, which is a sibling of the reader and
      // would otherwise only ever see the single opened message.
      threadContext: null,
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
        // Re-read in the folder the opened message is numbered in (EXO-90416): the
        // list may hold another message under its number.
        this.fetchEmail(email.mailRemoteId, { folder: this.email.folder || null });
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
    this.onCloseEmailDetailDrawer = () => this.close();
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
        if (email && this.$emailConnectorMailBoxService.canMarkReadIn(email.folder) && email.read !== read) {
          this.$set(email, 'read', read);
        }
      });
      // Marking unread puts the message away -- the toolbar closes this drawer right
      // after -- and brings no placeholder up; marking read is what opening a message
      // does, and leaves the reader as it is.
      if (!read) {
        this.selectEmailPlaceHolder = false;
      }
    };
    // The toolbar closes this drawer after a delete or an archive; its list loses the
    // messages meanwhile, and the reader keeps no placeholder up.
    this.onDeleteOrArchiveEmail = (emails, folder) => {
      if (!this.emailDetailDrawer || this.awaitsSharedMailboxConfirmation(folder)) {
        return; 
      }
      this.refreshEmails(emails, folder);
      this.selectEmailPlaceHolder = false;
    };
    // A delete or an archive in a shared mailbox is asked first (EXO-90548): the mail
    // leaves this reader once the answer is yes, and stays on a Cancel.
    this.onSharedMailboxActionConfirmed = (action, emails, folder) => {
      if (action === 'delete' || action === 'archive') {
        this.onDeleteOrArchiveEmail(emails, folder);
      }
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
    // The same opening, for a row the caller already holds in full — a draft, which
    // the UID-addressed one above cannot open because it may have no UID.
    this.onOpenEmailThreadDrawer = (email, emails, syncInProgress, webmailUrl) => {
      this.detachedFromList = false;
      this.standalone = false;
      this.openThreadOn(email, emails, syncInProgress, webmailUrl);
    };
    this.$root.$on('open-email-detail-drawer', this.onOpenEmailDetailDrawer);
    this.$root.$on('close-email-detail-drawer', this.onCloseEmailDetailDrawer);
    this.$root.$on('open-email-thread-drawer', this.onOpenEmailThreadDrawer);
    this.$root.$on('scheduled-email-updated', this.onScheduledEmailUpdated);
    this.$root.$on('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$on('update-email-favorite-status', this.onApplyEmailFavoriteStatus);
    this.$root.$on('apply-email-favorite-status', this.onApplyEmailFavoriteStatus);
    this.$root.$on('delete-email', this.onDeleteOrArchiveEmail);
    this.$root.$on('archive-email', this.onDeleteOrArchiveEmail);
    this.$root.$on('shared-mailbox-action-confirmed', this.onSharedMailboxActionConfirmed);
    this.$root.$on('attachment-download-started', (payload) => {
      this.activeDownload = payload;
    });
    this.$root.$on('attachment-download-finished', () => {
      this.activeDownload = null;
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
    this.$root.$off('open-email-detail-drawer', this.onOpenEmailDetailDrawer);
    this.$root.$off('open-email-thread-drawer', this.onOpenEmailThreadDrawer);
    this.$root.$off('scheduled-email-updated', this.onScheduledEmailUpdated);
    this.$root.$off('close-email-detail-drawer', this.onCloseEmailDetailDrawer);
    this.$root.$off('delete-email', this.onDeleteOrArchiveEmail);
    this.$root.$off('archive-email', this.onDeleteOrArchiveEmail);
    this.$root.$off('shared-mailbox-action-confirmed', this.onSharedMailboxActionConfirmed);
    this.$root.$off('update-email-favorite-status', this.onApplyEmailFavoriteStatus);
    this.$root.$off('apply-email-favorite-status', this.onApplyEmailFavoriteStatus);
  },
  computed: {
    /**
     * The shared mailbox the opened mail belongs to, for the identity band, or null
     * for a mail of the user's own mailbox.
     *
     * @returns {Object} the switcher entry, or null
     */
    sharedMailbox() {
      return this.$emailConnectorMailBoxService.sharedMailboxOfFolder(this.email?.folder);
    },
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
    /**
     * Whether the expand button is offered: opened over the mailbox list, where the
     * mailbox drawer's full screen can take the mail over, and not on a phone, where
     * no drawer expands (EXO-90415).
     *
     * @returns {Boolean} true when the mail can be expanded into the mailbox
     */
    canExpandInMailBox() {
      return !this.standalone && !this.$vuetify?.breakpoint?.smAndDown;
    },
  },
  watch: {
    emails() {
      // A message opened from outside this list -- a search hit, or one picked from
      // the global Favorites drawer -- comes from the whole mailbox, so its absence
      // here means nothing and must not send the reader back to the placeholder,
      // which is exactly what happened on the first refresh after opening one.
      if (this.detachedFromList) {
        return;
      }
      if (this.email && !(this.emails || []).some(e => e.mailRemoteId === this.email.mailRemoteId)) {
        this.selectEmailPlaceHolder = true;
      }
    },
    selectEmailPlaceHolder() {
      if (this.selectEmailPlaceHolder) {
        this.$root.$emit('set-opened', null);
      }
    },
  },
  methods: {
    /**
     * Whether a delete or an archive in this folder waits for the user's answer before
     * the mail may leave the reader: a shared mailbox's folder whose actions were not
     * confirmed yet this session (EXO-90548).
     *
     * @param {String} folder the folder acted in; the opened mail's when omitted
     * @returns {Boolean} true while the question is open
     */
    awaitsSharedMailboxConfirmation(folder) {
      const service = this.$emailConnectorMailBoxService;
      const entry = service?.sharedMailboxOfFolder?.(folder || this.email?.folder);
      return !!entry && !service.isDestructiveActionConfirmed(entry);
    },
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
      this.fetchEmail(mailRemoteId, { folder });
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
     * @param {number} mailRemoteId - the IMAP UID of the message to open
     * @param {Object} options - {folder}: the folder the UID is numbered in, when the
     *        caller knows it. The row the reader opens on is looked up in that folder
     *        too, because this drawer's list may be a search's (EXO-90416)
     * @returns {Promise<object|null>} the full message, or null when the request was
     *          superseded or failed
     */
    fetchEmail(mailRemoteId, options = {}) {
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
      return this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId, ownFolder)
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
     * The row of the list this drawer was handed for a message, if it holds one --
     * in the given folder, when one is known: the list may be a search's, where the
     * same UID may be two messages (EXO-90416).
     *
     * @param {number} mailRemoteId - the IMAP UID of the message
     * @param {String} folder - the folder the UID is numbered in, when known
     * @returns {object|undefined} the list row
     */
    listedEmail(mailRemoteId, folder = null) {
      return (this.emails || []).find(e => e.mailRemoteId === mailRemoteId
        && (!folder || (e.folder || 'INBOX') === folder));
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
    /**
     * Follows the Scheduled view's mail this drawer shows (EXO-90434): its new date or
     * state once the view was read again; the drawer closes once it is no longer
     * scheduled -- sent, cancelled, discarded, being edited.
     *
     * @param {String} draftLocalId the mail's draft local id
     * @param {Object} row the reader's row for it now, null when it left the view
     * @returns {void}
     */
    onScheduledEmailUpdated(draftLocalId, row) {
      if (!this.emailDetailDrawer || !this.email?.scheduledRow || this.email.draftLocalId !== draftLocalId) {
        return;
      }
      if (row) {
        this.email = row;
        this.emails = [row];
      } else {
        this.close();
      }
    },
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
      this.selectEmailPlaceHolder = false;
      this.email = null;
      this.threadContext = null;
      this.$root.isDetailDrawerActive = false;
      this.$root.$emit('email-detail-drawer-closed');
    },
    /**
     * Expands the mail into the mailbox drawer's full screen, the one full-screen
     * layout (EXO-90415): the mailbox drawer opens that mail in its reader and expands,
     * then this drawer closes.
     * <p>
     * Closed without exo-drawer's confirmation: an attachment download still running is
     * not given up by expanding -- the mailbox drawer tracks the same download and asks
     * before its own close -- and a "give up the download?" question would read as the
     * expand button closing the mail.
     *
     * @returns {void}
     */
    expandInMailBox() {
      this.$root.$emit('expand-mail-box-on-email', {
        email: this.email,
        folder: this.email && (this.email.folder || 'INBOX'),
      });
      const drawer = this.$refs.emailDetailDrawer;
      if (drawer?.closeEffectively) {
        drawer.closeEffectively();
      } else {
        this.close();
      }
    },
  }
};
</script>