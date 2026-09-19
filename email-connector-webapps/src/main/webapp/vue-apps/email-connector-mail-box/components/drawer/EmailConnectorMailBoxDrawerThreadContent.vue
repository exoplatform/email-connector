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
         / Notification) and show the ones already applied. Drafts are kept out of it:
         categories are assigned by IMAP UID, which a draft may not have, and an unsent
         message is not a thing anyone means to categorise. -->
    <email-connector-mail-box-drawer-category-bar :emails="categorizableMessages" />
    <!-- Room for somebody else to say something about this conversation as a whole:
         under its subject and its categories, and above everything that is about the
         individual messages — the same seam the mail toolbars already offer, in the
         same shape (see EmailConnectorMailBoxDrawerListItemDetailActions). A module
         that can summarise a conversation fills it with
         extensionRegistry.registerComponent('EmailThread', 'email-thread-summary', …)
         and is rendered here with the params below as props.

         Below the categories rather than above them: the categories are chips that
         belong with the subject as the conversation's labelling, while a summary is a
         block of prose, and pushing the chips away from the title left the header
         reading as two separate things. Ordered by weight, the light identifying line
         stays next to what it identifies.

         Nothing in this add-on registers into it, so the component renders NOTHING at
         all — not even its own wrapper — and the category bar sits against the first
         message exactly as before. -->
    <extension-registry-components
      :params="summaryExtensionParams"
      name="EmailThread"
      type="email-thread-summary"
      parent-element="div"
      element="div"
      class="my-auto" />
    <!-- The messages of this conversation the folder list does not hold (a sent reply,
         an archived message), counted from the list row's conversation total and held
         by a skeleton strip each until the conversation lands. No progress bar here:
         the drawer's own header bar says the reader is loading, and the archived tail
         fetched after it is nothing the user is waiting on. -->
    <v-skeleton-loader
      v-for="index in pendingSkeletons"
      :key="`pending-${index}`"
      type="list-item-avatar"
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
          class="d-flex align-center justify-center rounded-circle text-caption text-light-color"
          style="position: relative; z-index: 1; width: 40px; height: 40px; border: 1px solid var(--v-borderColor, #e1e8ee); background-color: var(--v-surface-base, #fff);">
          {{ item.count }}
        </span>
      </div>
      <!-- The reply in progress, in place at the bottom of the conversation it
           answers — the whole point of keeping drafts in the same table as mail. -->
      <email-connector-mail-box-drawer-thread-draft
        v-else-if="item.type === 'draft'"
        :key="item.key"
        :draft="item.message"
        @resume="resumeDraft(item.message)"
        @discard="discardDraft(item.message)" />
      <email-connector-mail-box-drawer-thread-message
        v-else
        :key="item.key"
        :email="item.message"
        :expanded="expandedIds.includes(item.key)"
        :collapsible="!isLast(item.message)"
        :expanded-drawer="expandedDrawer"
        :in-thread="isThread"
        :loading="isPartial(item.message)"
        @expand="expand(item.key)"
        @collapse="collapse(item.key)" />
    </template>
  </v-list>
</template>

<script>
// Messages kept visible at the tail of a long thread, in addition to the last one
// (which is expanded): matches Gmail showing the message just before the latest.
const TAIL_STRIPS = 1;
// Skeleton strips shown at most for the messages of a conversation the folder list
// does not hold, while the conversation is being read.
const MAX_PENDING_SKELETONS = 3;

export default {
  data() {
    return {
      messages: [],
      expandedIds: [],
      revealedKeys: [],
      loadingThread: false,
      // Whether `messages` is the conversation as the server returned it, rather than
      // the list rows it was seeded with while that answer is on its way.
      threadLanded: false,
      // Whether the server had nothing for this conversation, so `messages` is the
      // opened message alone — which then follows that message when the drawer swaps
      // its list row for the full one.
      fallback: false,
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
    // What the reader is open ON, as an identity the row cannot be without: a draft's
    // local id, any other message's folder + UID. See the watcher.
    openedKey() {
      return this.email ? this.msgKey(this.email) : null;
    },
    // The folder the reader was opened from -- the opened message's own. Sent along
    // with every conversation read (EXO-89942): opened from the Trash or the Junk
    // folder, the server includes the conversation's copies in that folder, which every
    // other read hides; a conversation deleted whole would otherwise read back as the
    // one message that was clicked.
    openedFrom() {
      return this.email?.folder || 'INBOX';
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
    // What a component registered into the conversation slot is handed: the thread id,
    // its messages and its subject. No callbacks and no service — everything else is
    // reachable from the thread id through the add-on's own REST, and a contributor
    // handed a function would be coupled to this component's internals rather than to
    // the conversation.
    //
    // Also what the DRAWER'S HEADER is handed, through the `thread-context` event below.
    // The header sits in the drawer's title bar, a sibling of this reader rather than a
    // parent or a child of it, so it has no way of its own to know whether what is open
    // is a conversation — and this is the one component that does. One shape for both
    // seams deliberately: a second, slightly different object describing the same
    // conversation is how the two of them would come to disagree about what a thread is.
    //
    // `categorizableMessages` and not `messages`, for the reason the category bar is
    // given the same list: a draft is a sentence the user is in the middle of writing,
    // and nothing that describes the conversation back to them should be describing
    // that.
    summaryExtensionParams() {
      return {
        threadId: this.email && this.resolveThreadId(),
        // Nothing before the conversation has landed: the seeded rows carry no body,
        // and a contributor describing the conversation must not describe those.
        messages: this.threadLanded ? this.categorizableMessages : [],
        subject: this.subject,
      };
    },
    // Everything in the conversation that is actual mail. Drafts are in `messages`
    // because they belong in the conversation, but they are not messages: anything
    // that acts on one by its IMAP UID has to work off this instead.
    categorizableMessages() {
      return this.messages.filter(message => !this.isDraft(message));
    },
    // Whether what is on screen is a conversation rather than one mail. Every opened
    // message comes through this reader, so a lone mail is rendered by the very same
    // components a thread is -- the difference is only how many messages there are.
    // Told apart here because it is the one place that knows, and because a message
    // of a real conversation offers things a lone mail must not repeat from the
    // drawer's header (see the 3-dots menu items).
    //
    // Counted on the mail, not on `messages`: a draft is not a message of the exchange
    // -- a mail with an unsent reply under it is still one mail being read, and its
    // header already carries everything the menu would.
    isThread() {
      return this.categorizableMessages.length > 1;
    },
    // Whether the opened message is still only its list row in the reader: the body
    // the user opened is not on screen yet, whichever answer brings it.
    openedPartial() {
      const key = this.openedKey;
      return !!key && this.messages.some(message => this.msgKey(message) === key && this.isPartial(message));
    },
    // How many skeleton strips stand for the messages the folder list does not hold:
    // the conversation total the server stamped on the opened row, minus the rows
    // already seeded. Capped, because it only has to say "more is coming" — the middle
    // of a long conversation folds into a count badge once it lands anyway.
    pendingSkeletons() {
      if (this.threadLanded || !this.email) {
        return 0;
      }
      const openedKey = this.msgKey(this.email);
      const listRow = (this.emails || []).find(e => this.msgKey(e) === openedKey);
      const total = listRow?.threadCount || this.email.threadCount || 0;
      return Math.min(Math.max(total - this.categorizableMessages.length, 0), MAX_PENDING_SKELETONS);
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
          items.push({ type: this.isDraft(message) ? 'draft' : 'message', message, key: this.msgKey(message) });
        } else {
          run.push(message);
        }
      });
      flush();
      return items;
    },
  },
  watch: {
    // Reload whenever a different conversation is opened. Keyed on the row's own
    // identity rather than on its IMAP UID: a conversation can now be opened from
    // its draft, and a draft may have no UID at all — two unpushed ones would then
    // both watch null, and opening the second would leave the first one's
    // conversation on screen.
    openedKey: {
      immediate: true,
      handler() {
        this.loadThread();
      },
    },
    // The drawer opens the reader on the list row and swaps in the full message when
    // its own request answers — same identity, so the watcher above does not reload.
    // Until the conversation lands, the full message takes its seeded row's place, so
    // the opened body shows as soon as either answer is here; after a fallback it
    // replaces the partial row the fallback was built from.
    email(newEmail, oldEmail) {
      if (!newEmail || !oldEmail || newEmail === oldEmail || this.msgKey(newEmail) !== this.msgKey(oldEmail)) {
        return;
      }
      if (!this.threadLanded) {
        const key = this.msgKey(newEmail);
        this.messages = this.messages.map(message => (this.msgKey(message) === key ? newEmail : message));
      } else if (this.fallback) {
        this.applyMessages(null);
      }
    },
    // The drawer shows the platform's loading bar in its header; the reader only tells
    // it when the conversation is being read, which is what the user is waiting on.
    loadingThread: {
      immediate: true,
      handler(loading) {
        this.$emit('loading', loading);
      },
    },
    // Told apart from the loading above so the drawer can keep its bar on for the
    // opened message's own request only while that body is really missing here — not
    // when the conversation already brought it in full.
    openedPartial: {
      immediate: true,
      handler(partial) {
        this.$emit('opened-partial', partial);
      },
    },
  },
  beforeCreate() {
    // Which load is current. A response to an earlier one — the user opened another
    // conversation before it answered — is dropped rather than painted over the
    // conversation now on screen. Not reactive: nothing renders from it.
    //
    // Set here and not in created(): the openedKey watcher is immediate, and Vue runs
    // immediate watchers before created(), so the first load would read an undefined
    // counter, get NaN as its id and drop its own answer.
    this.loadSeq = 0;
    this.seedExpandedKey = null;
  },
  created() {
    // The reader's messages are fetched apart from the folder list, so a favorite
    // toggled anywhere (a list row, this very reader) — or rolled back after
    // the mail server refused the push — must be mirrored on them here.
    this.$root.$on('update-email-favorite-status', this.applyFavoriteStatus);
    this.$root.$on('apply-email-favorite-status', this.applyFavoriteStatus);
    // The same event the folder list reloads on, for the same reason: the composer is
    // the one writer outside the sync, and what it writes lands in this conversation
    // as well as in the list. Reusing it rather than inventing a second signal is what
    // keeps the two views from ever disagreeing about whether a draft exists.
    this.$root.$on('refresh-email-box', this.reloadFromCache);
  },
  beforeDestroy() {
    this.$root.$off('update-email-favorite-status', this.applyFavoriteStatus);
    this.$root.$off('apply-email-favorite-status', this.applyFavoriteStatus);
    this.$root.$off('refresh-email-box', this.reloadFromCache);
    this.$emit('loading', false);
    this.$emit('opened-partial', false);
    // Nothing is being read any more — the drawer switched to its multi-select mode, to
    // the "pick a message" placeholder, or closed. Say so, or the header keeps offering
    // the conversation's actions on a conversation nobody has open.
    this.emitThreadContext(true);
  },
  methods: {
    /**
     * Tells the drawer around this reader which conversation is on screen, so its header
     * can act on the exchange rather than on the message that happens to be selected.
     *
     * Emitted from the two places that actually know — a load starting and messages
     * landing — rather than watched off the params: while the next conversation is
     * fetched the reader shows it seeded from the folder list's rows, which are not
     * the whole exchange, and a watcher would hand the header a conversation that is
     * still missing its sent replies and archived messages — which is exactly how a
     * lone mail is mistaken for a conversation, or a conversation for a lone mail. Clearing first and re-announcing on arrival means the header is
     * briefly right-but-narrow instead of momentarily wrong. While the conversation
     * is on its way, the seeded rows are announced as a PROVISIONAL context (see
     * emitProvisionalThreadContext), so the header's actions reach what is on screen.
     *
     * @param {boolean} clear - true to announce that nothing is open
     * @returns {void}
     */
    emitThreadContext(clear) {
      this.$emit('thread-context', clear ? null : this.summaryExtensionParams);
    },
    /**
     * Tells the header which conversation is on screen while it is only seeded: the
     * rows of it the folder list holds. The header's actions scope a conversation to
     * the acting folder, and those are exactly the rows the list holds, so a delete
     * started now reaches the conversation on screen rather than the opened message
     * alone. Marked provisional; the extension that describes the conversation
     * (`email-thread-summary`) is never given it — its params stay the landed
     * conversation only.
     *
     * @returns {void}
     */
    emitProvisionalThreadContext() {
      this.$emit('thread-context', {
        threadId: this.email && this.resolveThreadId(),
        messages: this.categorizableMessages,
        subject: this.subject,
        provisional: true,
      });
    },
    // Patch the favorite flag on this conversation's INBOX messages (favorite ids are
    // INBOX UIDs; the same number in another folder is a different message).
    applyFavoriteStatus(favorite, mailRemoteIds = []) {
      const ids = new Set(mailRemoteIds);
      this.messages.forEach(message => {
        if ((message.folder || 'INBOX') === 'INBOX' && ids.has(message.mailRemoteId)) {
          this.$set(message, 'starred', favorite);
        }
      });
    },
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
    // Whether a row is a draft rather than mail. Keyed on the local id, not on the
    // folder: the local id is the one thing only a draft has, and it is what the
    // composer addresses it by.
    isDraft(message) {
      return !!message.draftLocalId;
    },
    // A message is identified across the reader by its folder + IMAP UID: UIDs are
    // per-folder, so the same number can appear in INBOX and SENT/ARCHIVE. A draft
    // takes its local id instead — it may have no UID at all yet, and two unpushed
    // drafts of the same conversation would otherwise share one key and render once.
    msgKey(message) {
      if (this.isDraft(message)) {
        return `DRAFT-${message.draftLocalId}`;
      }
      return `${message.folder || 'INBOX'}-${message.mailRemoteId}`;
    },
    /**
     * Whether a message is only known by its list row so far: no body and no
     * recipients, which is what the folder listing leaves out. Such a message renders
     * as a skeleton when expanded, and as its ordinary strip when collapsed — the
     * strip only needs the sender, the excerpt and the date the row does carry.
     * <p>
     * Judged on the recipients rather than on the body, because a full message may
     * legitimately have an empty body, while every full read carries its recipients
     * as a list, empty or not. A draft is always read whole.
     *
     * @param {object} message - a message of the reader
     * @returns {boolean} true when only the list row of the message is known
     */
    isPartial(message) {
      return this.$emailConnectorMailBoxService.isListingRow(message);
    },
    /**
     * Shows the conversation at once from what the drawer already holds — the opened
     * message and its siblings in the folder list — while the server is asked for the
     * whole of it. Collapsed messages render as their usual strip (sender, excerpt,
     * date); the expanded one renders as a skeleton until its body is here.
     * <p>
     * Announced to the drawer's header as a provisional context only (see
     * emitProvisionalThreadContext): the list holds one folder, so this is not the
     * exchange yet, only its visible part.
     *
     * @returns {void}
     */
    seedMessages() {
      const threadKey = this.resolveThreadId() || this.threadKey(this.email);
      const known = [this.email].concat((this.emails || []).filter(e => this.threadKey(e) === threadKey));
      const seen = new Set();
      const unique = known.filter(message => {
        const key = this.msgKey(message);
        if (seen.has(key)) {
          return false;
        }
        seen.add(key);
        return true;
      });
      const sorted = unique.sort((first, second) => new Date(first.receivedDate) - new Date(second.receivedDate));
      this.threadLanded = false;
      this.fallback = false;
      this.messages = this.positionDrafts(this.dedupeByHeader(sorted));
      const readable = this.messages.filter(message => !this.isDraft(message));
      const latest = readable[readable.length - 1];
      // Remembered so that landing does not mistake this default for a message the
      // user opened: the seeded latest is often not the conversation's latest (the
      // list holds no sent reply), and keeping it open would open two messages.
      this.seedExpandedKey = latest ? this.msgKey(latest) : null;
      this.expandedIds = this.seedExpandedKey ? [this.seedExpandedKey] : [];
      this.emitProvisionalThreadContext();
    },
    isLast(message) {
      const last = this.messages[this.messages.length - 1];
      return !!last && this.msgKey(last) === this.msgKey(message);
    },
    // Which messages stay visible: the first, the last few (tail), any still-unread
    // one, and any the user revealed by clicking a badge. The rest fold into badges.
    isShown(index) {
      const message = this.messages[index];
      // A draft is never folded away behind a count badge. It is the one item here the
      // user is in the middle of writing, and hiding it inside "3 more messages" would
      // lose exactly the thing this feature exists to show. In practice a draft is
      // last and would be shown anyway; this is the rule, not the coincidence.
      if (this.isDraft(message)) {
        return true;
      }
      const total = this.messages.length;
      if (index === 0 || index >= total - 1 - TAIL_STRIPS) {
        return true;
      }
      return !message.read || this.revealedKeys.includes(this.msgKey(message));
    },
    /**
     * Reopens a draft in the composer, which is what "opening" one means — a draft
     * has no reader.
     *
     * @param {object} draft - the draft row
     * @returns {void}
     */
    resumeDraft(draft) {
      this.$root.$emit('resume-draft', draft);
    },
    /**
     * Throws a draft away from inside the conversation it sits in.
     *
     * The row is only removed from the list once the server says it is gone, so a
     * discard that fails leaves the draft where it is rather than making it vanish
     * from the screen and come back on the next read.
     *
     * @param {object} draft - the draft row
     * @returns {void}
     */
    discardDraft(draft) {
      this.$emailConnectorMailBoxService.deleteDraft(draft.draftLocalId).then(() => {
        this.$root.$emit('refresh-email-box');
      }).catch(() => {
        document.dispatchEvent(new CustomEvent('alert-message', {detail: {
          alertType: 'error',
          alertMessage: this.$t('emailConnector.mailBox.newEmail.drawer.draft.discard.error'),
        }}));
      });
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
      const load = ++this.loadSeq;
      if (!this.email) {
        this.loadingThread = false;
        return;
      }
      // The header stops speaking for the previous conversation the moment another one
      // is asked for, and starts speaking for this one only once its messages are here.
      this.emitThreadContext(true);
      this.revealedKeys = [];
      this.seedMessages();
      const threadId = this.resolveThreadId();
      this.loadingThread = true;
      const cached = threadId
        ? this.$emailConnectorMailBoxService.getThreadByThreadId(threadId, this.openedFrom).catch(() => null)
        : Promise.resolve(null);
      cached
        .then(fetched => {
          if (load !== this.loadSeq) {
            return;
          }
          // The seeded strips are clickable: what the user opened while the
          // conversation was on its way stays open when it lands.
          const wasExpanded = this.expandedIds.filter(key => key !== this.seedExpandedKey);
          this.applyMessages(fetched);
          this.keepExpanded(wasExpanded);
          this.markThreadRead();
        })
        .finally(() => {
          if (load !== this.loadSeq) {
            return;
          }
          this.loadingThread = false;
          this.completeInBackground(threadId, load);
        });
    },
    /**
     * Re-reads the conversation after the composer has written to it — a draft saved,
     * discarded, or sent out from under it.
     *
     * The cached pass only, deliberately, where opening the reader runs both. The
     * second pass exists to recover an archived tail from the provider's All Mail,
     * which cannot have changed because someone typed a sentence here; running it on
     * every draft push would put an IMAP round-trip behind an autosave.
     *
     * @returns {void}
     */
    reloadFromCache() {
      // Nothing is open here: the reader sits between conversations, or the drawer
      // behind the composer has been closed. There is no conversation to bring up to
      // date, and resolveThreadId reads a message that is not there.
      if (!this.email) {
        return;
      }
      const threadId = this.resolveThreadId();
      if (!threadId) {
        return;
      }
      // What the user had opened is theirs, not ours to close: a draft being autosaved
      // must not collapse the message they are reading it against.
      const wasExpanded = this.expandedIds.slice();
      const load = this.loadSeq;
      this.$emailConnectorMailBoxService.getThreadByThreadId(threadId, this.openedFrom)
        .then(fetched => {
          // Another conversation was opened while this one was being re-read.
          if (load !== this.loadSeq) {
            return;
          }
          if (!fetched?.length) {
            // An answer with nothing in it is not this conversation being emptied — the
            // messages on screen were read from the same table a moment ago. It is the
            // id they were read under no longer naming them: saving a draft resolves
            // its conversation the way a synced message does, and a resolution that
            // finds the thread split collapses every part of it into the OLDEST id.
            // Applying this would fall back to the single opened message and take the
            // rest of the conversation off the screen. Keeping what is shown is both
            // truthful and recoverable — reopening the conversation reads it whole,
            // under the id the folder list now carries.
            return;
          }
          this.applyMessages(fetched);
          const stillHere = wasExpanded.filter(key => this.messages.some(message => this.msgKey(message) === key));
          this.expandedIds = Array.from(new Set(this.expandedIds.concat(stillHere)));
        })
        .catch(() => { /* best-effort: keep what is on screen */ });
    },
    /**
     * Second pass: pulls the archived tail from All Mail without blocking the open.
     * <p>
     * Silent on purpose. It may cost an IMAP round-trip of several seconds and usually
     * recovers nothing, so a loading indicator tied to it stayed on long after the
     * conversation was on screen, telling the user to wait for something they were
     * not waiting for. Recovered messages simply join the conversation, and what the
     * user had expanded in the meantime stays expanded.
     *
     * @param {string} threadId - the conversation id
     * @param {number} load - the load this pass belongs to, to drop it when stale
     * @returns {void}
     */
    completeInBackground(threadId, load) {
      if (!threadId) {
        return;
      }
      this.$emailConnectorMailBoxService.completeThreadByThreadId(threadId, this.openedFrom)
        .then(completed => {
          // Dropped when another conversation was opened meanwhile, and only
          // re-rendered if completion actually recovered more messages.
          if (load !== this.loadSeq || !completed || completed.length <= this.messages.length) {
            return;
          }
          const wasExpanded = this.expandedIds.slice();
          this.applyMessages(completed);
          this.keepExpanded(wasExpanded);
        })
        .catch(() => { /* best-effort: keep the cached thread on failure */ });
    },
    /**
     * Re-opens, after the messages were replaced, the ones that were open before and
     * are still part of the conversation.
     *
     * @param {Array<string>} wasExpanded - the keys of the messages open before
     * @returns {void}
     */
    keepExpanded(wasExpanded) {
      const stillHere = wasExpanded.filter(key => this.messages.some(message => this.msgKey(message) === key));
      this.expandedIds = Array.from(new Set(this.expandedIds.concat(stillHere)));
    },
    // Normalize a fetched thread into the reader's state: dedupe by Message-ID, sort
    // oldest first, keep the latest message expanded.
    //
    // Mail sorts by date. A DRAFT does not, and the date sort is exactly what put it
    // wrong: its row is re-dated every time the user types, so a reply to Monday's
    // message resumed tonight sorted below a mail that arrived at 20:57 and answered
    // nothing. The server places drafts under what they answer and this repeats the
    // rule rather than trusting the order it was handed, because the list here is not
    // always the server's: a single opened message and a background completion are
    // merged into it, and a sort of the union has to know where a draft belongs.
    applyMessages(fetched) {
      const sorted = (fetched && fetched.length ? fetched : [this.email])
        .filter(Boolean)
        .sort((first, second) => new Date(first.receivedDate) - new Date(second.receivedDate));
      // Positioned AFTER the dedupe, not before it: a sent copy takes the place of the
      // draft it came from (see dedupeByHeader), and it is mail once it has — its place
      // is the one its date earns, not the one the draft was holding.
      const messages = this.positionDrafts(this.dedupeByHeader(sorted));
      this.messages = messages;
      this.threadLanded = true;
      this.fallback = !(fetched && fetched.length);
      // The last real message stays open, not the draft: the draft renders as its own
      // strip with no expanded form, and expanding nothing would leave the reader with
      // every message collapsed.
      const readable = messages.filter(message => !this.isDraft(message));
      const latest = readable[readable.length - 1];
      this.expandedIds = latest ? [this.msgKey(latest)] : [];
      // The single place messages are set, so the single place the drawer's header is
      // told what the conversation now holds — including the archived tail, which turns
      // an apparently lone mail into a conversation a second after it opened.
      this.emitThreadContext();
    },
    /**
     * Moves each draft directly under the message it answers, and leaves the mail
     * around it in the order it was given.
     *
     * A draft with nothing to answer, or one whose parent is not in this
     * conversation — evicted from the cache, or not yet completed from the archive —
     * stays at the end, which is also where a draft answering the newest message
     * lands. That is what most replies are, so the common conversation looks
     * unchanged.
     *
     * @param {Array} messages - the conversation, mail in date order
     * @returns {Array} the same messages in reading order
     */
    positionDrafts(messages) {
      if (messages.length < 2 || !messages.some(message => this.isDraft(message))) {
        return messages;
      }
      const ordered = messages.filter(message => !this.isDraft(message));
      messages.filter(message => this.isDraft(message)).forEach(draft => {
        // The LAST copy of the parent: the same mail can be cached once per folder,
        // and a reply belongs after all of its copies rather than between two of them.
        const parentIndex = draft.inReplyTo
          ? ordered.map(message => message.mailHeaderId).lastIndexOf(draft.inReplyTo)
          : -1;
        if (parentIndex < 0) {
          ordered.push(draft);
          return;
        }
        // Past the drafts already placed under this same parent, so two unsent replies
        // to one message keep the order they were written in.
        let insertAt = parentIndex + 1;
        while (insertAt < ordered.length && this.isDraft(ordered[insertAt])) {
          insertAt++;
        }
        ordered.splice(insertAt, 0, draft);
      });
      return ordered;
    },
    // The same message can be present in more than one folder (e.g. a provider
    // whose Archive/All-Mail overlaps the inbox), so show it once, preferring the
    // INBOX copy. Messages without a Message-ID are always kept.
    //
    // DRAFTS ranks last on purpose, and that is what makes a sent draft disappear
    // cleanly. A draft goes out under the Message-ID it minted at its first save, so
    // the copy that lands in Sent carries the SAME header as the draft row — for as
    // long as both exist, they are two rows for one message. Ranking the draft lowest
    // means the sent copy wins the moment it arrives, and the reply the user was
    // writing turns into the reply they sent, in place.
    dedupeByHeader(messages) {
      const priority = { INBOX: 0, SENT: 1, ARCHIVE: 2, ALL_MAIL: 3, DRAFTS: 4 };
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
