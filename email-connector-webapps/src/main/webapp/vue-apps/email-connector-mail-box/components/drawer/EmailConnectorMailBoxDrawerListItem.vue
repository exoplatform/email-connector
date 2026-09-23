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
  <!-- In full screen the row is dragged onto a folder of the column (EXO-90421), and
       fades while its messages are the ones dragged. No draggable attribute otherwise:
       the narrow layout keeps the browser's default for what the row holds. -->
  <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
  <div
    :style="rowStyle"
    v-bind="canDrag ? { draggable: 'true' } : {}"
    @dragstart="onDragStart"
    @dragend="onDragEnd"
    @mouseenter="!isMobile && (isHover = true)"
    @mouseleave="!isMobile && (isHover = false)"
    @focusin="!isMobile && (isHover = true)"
    @focusout="!isMobile && (isHover = false)"
    :class="[
      backgroundClass,
      selectMode ? 'ps-4' : 'ps-7'
    ]"
    class="position-relative no-border pt-3 pb-3 pe-4">
    <div
      v-if="absolute"
      :class="[
        'position-absolute',
        'my-auto',
        't-0',
        'b-0',
        'd-flex',
        'align-center',
        'justify-center',
        movingLeft ? 'blue darken-1 r-0' : 'red darken-1 l-0'
      ]"
      :style="{
        width: `${gapSize}px`,
        overflow: 'hidden'
      }">
      <v-card
        class="transparent d-flex flex-column align-center justify-center"
        :width="gapSize"
        min-width="85"
        dark
        flat>
        <v-icon size="24">{{ movingLeft && 'fa-archive' || 'fa-trash' }}</v-icon>
        <span class="text-no-wrap mt-3">{{ movingLeft && $t('emailConnector.mailBox.list.drawer.detail.archive.label') || $t('emailConnector.mailBox.list.drawer.detail.delete.label') }}</span>
      </v-card>
    </div>
    <div
      class="d-flex"
      v-touch="{
        start: moveStart,
        end: moveEnd,
        move: moveSwipe,
      }"
      :class="absolute && 'position-relative' || 'position-static'"
      :style="absolute && {
        transform: `translateX(${left}px)`,
        width: `${minWidth}px`,
        'min-width': `${minWidth}px`,
      }">
      <!-- Disabled as well as unclickable for a row the server has not listed yet
           (refreshPending): pointer-events stops the mouse, not Tab + Space, and a
           placeholder UID must not enter the selection. -->
      <v-checkbox
        v-if="selectMode"
        class="me-0 pt-0 align-self-center"
        color="#707070"
        background-color="transparent"
        :input-value="selected"
        :disabled="email.refreshPending"
        @click.stop
        @change="onSelectChange" />
      <div class="flex-grow-1 no-min-width">    
        <!-- eslint-disable vuejs-accessibility/no-static-element-interactions -->
        <!-- data-thread-key is how the arrow keys find the row they stand on, and
             aria-current tells a screen reader which conversation the reader shows --
             the one it shows, not the one the keyboard highlight is passing over. No
             outline: the arrow keys focus the row, and the row's own grey background
             (lit on focus, see isHover) is the cue; the browser's ring drawn over it
             read as a stray blue box. Inline because this webapp bundles no CSS. -->
        <div
          class="clickable"
          style="outline: none;"
          tabindex="0"
          :data-thread-key="threadKey"
          :aria-current="inReader ? 'true' : null"
          :aria-label="ariaLabel"
          @click="openDetail"
          @keydown.enter="openDetail"
          @keydown.space.prevent="openDetail"
          v-touch-hold="openActionMenuDrawer">
          <v-list-item
            ref="mail"
            :class="['height-auto', 'px-0', 'pb-2', { 'ms-n3': threadUnread }]">
            <v-list-item-avatar
              v-if="threadUnread"
              width="8"
              min-width="8"
              height="8"
              class="my-0 me-1 error-color-background" />
            <v-list-item-content :class="['py-0', { 'font-weight-bold': threadUnread }]">
              <!-- The participants line, and after them the conversation's size.
                   A conversation the user has a reply half-written in says so right
                   here, last among the participants and in the platform's error
                   colour — the point being that you can see a reply is unfinished
                   without opening anything, which is what stops it being forgotten.
                   A plain span with no listener of its own: this list streams
                   thousands of rows, and one handler per row is a real cost. -->
              <v-list-item-title>
                {{ participants }}<span
                  v-if="showDraftMarker"
                  class="error--text font-weight-regular">{{ draftMarker }}</span><span
                    v-if="threadCount > 1"
                    class="text-light-color ms-1 font-weight-regular">{{ threadCount }}</span>
              </v-list-item-title>
            </v-list-item-content>
            <v-list-item-action class="my-0 flex-row align-center">
              <!-- Quiet favorite, next to the date (the unread dot keeps the left edge):
                   always there when set, offered on hover to set it.
                   It keeps its box at all times and only fades, exactly like the row
                   actions below. Anything that takes the icon out of the layout -- v-if,
                   or v-show, which sets display:none -- resizes the row as the pointer
                   arrives: the date shifts, the pointer ends up over different content,
                   the hover drops, and the row flickers as long as the cursor rests. -->
              <email-connector-mail-box-drawer-favorite-toggle
                v-if="!selectMode"
                :style="{
                  opacity: threadFavorite || isHover ? 1 : 0,
                  pointerEvents: threadFavorite || isHover ? 'auto' : 'none'
                }"
                :favorite="threadFavorite"
                :can-toggle="canToggleFavorite && !selectMode"
                class="me-1"
                :size="18"
                @toggle="toggleThreadFavorite" />
              <v-list-item-subtitle v-text="receivedDate" />
            </v-list-item-action>
          </v-list-item>
          <v-list-item
            class="px-0 height-auto">
            <v-list-item-content class="py-0">
              <v-list-item-subtitle :class="['mb-1 text-color', { 'font-weight-bold': threadUnread }]" v-text="subject" />
              <v-list-item-subtitle v-text="excerpt" />
            </v-list-item-content>
            <email-connector-mail-box-drawer-list-item-action-menu
              v-if="!selectMode && !isMobile"
              :style="{
                opacity: isHover || menuOpen ? 1 : 0,
                pointerEvents: isHover || menuOpen ? 'auto' : 'none'
              }"
              ref="menu"
              :email="email"
              :thread="thread"
              @open="menuOpen = true"
              @close="menuOpen = false" />
          </v-list-item>
        </div>
        <email-connector-mail-box-drawer-list-item-attachments
          :email-attachments="emailAttachments"
          v-if="hasAttachments" />
      </div>
    </div>
  </div>
</template>

<script>
import { selectionKey } from '../../js/EmailConnectorMailBoxSelection.js';
import { canDragFrom, dragLabel, dragPayloadOfRow, draggedRowCount, startDrag } from '../../js/EmailConnectorMailBoxDragAndDrop.js';

export default {
  data() {
    return {
      menuOpen: false,
      isHover: false,
      absolute: false,
      left: 0,
      startEvent: null,
      minWidth: 0,
      movingLeft: false,
      isSwiping: false,
    };
  },
  props: {
    email: {
      type: Object,
      default: () => null,
    },
    thread: {
      type: Object,
      default: () => null,
    },
    selectMode: {
      type: Boolean,
      default: false,
    },
    selectedEmails: {
      type: Array,
      default: () => [],
    },
    emails: {
      type: Array,
      default: () => [],
    },
    syncInProgress: {
      type: Boolean,
      default: false,
    },
    expanded: {
      type: Boolean,
      default: false,
    },
    openedEmailId: {
      type: String,
      default: null,
    },
    // The message the full-screen reader shows beside the list; none in the narrow
    // layout, where there is no reader beside it.
    readerEmailId: {
      type: [Number, String],
      default: null,
    },
    webmailUrl: {
      type: String,
      default: null,
    },
    // The mail being dragged from the list, {folder, ids}, null when none is (EXO-90421).
    dragSource: {
      type: Object,
      default: null,
    },
  },
  computed: {
    /**
     * Whether the row may be dragged onto a folder: in full screen only, never on a
     * phone (its touch gestures stay the swipe and the long press), and only a row the
     * move, delete and spam actions are offered on (canDragFrom).
     *
     * @returns {Boolean} true when the row is draggable
     */
    canDrag() {
      return this.expanded && !this.isMobile && canDragFrom(this.email.folder, this.email);
    },
    /**
     * Whether the row's messages are among the ones being dragged, to fade it.
     *
     * @returns {Boolean} true while they are
     */
    dragged() {
      const drag = this.dragSource;
      return !!drag && drag.folder === (this.email.folder || 'INBOX')
        && this.$emailConnectorMailBoxService.threadIdsInFolder(this.email, this.thread).some(id => drag.ids.includes(id));
    },
    gapSize() {
      return Math.abs(this.left);
    },
    receivedDate() {
      return this.$emailConnectorMailBoxService.formatDateString(this.email.receivedDate, this.$t('emailConnector.mailBox.list.drawer.yesterday'));
    },
    isMobile() {
      return this.$vuetify.breakpoint.smAndDown;
    },
    hasAttachments() {
      return this.emailAttachments.length > 0;
    },
    // A thread row shows the attachments of the whole conversation, so a file on any
    // message (not only the latest) still surfaces a chip on the collapsed thread.
    emailAttachments() {
      if (this.thread) {
        return this.thread.emails.flatMap(message => message.content?.attachments || []);
      }
      return this.email.content?.attachments || [];
    },
    excerpt() {
      return this.email.content?.excerpt || this.$t('emailConnector.mailBox.list.drawer.emptyEmail');
    },
    subject() {
      return this.email.subject || this.$t('emailConnector.mailBox.list.drawer.noSubject');
    },
    threadIds() {
      return this.thread ? this.thread.mailRemoteIds : [this.email.mailRemoteId];
    },
    /**
     * The row's key, as groupEmailsByThread builds it: what the arrow keys use to find
     * the row they stand on and the one they go to.
     *
     * @returns {String} the key
     */
    threadKey() {
      return String(this.thread ? this.thread.threadId : this.email.mailRemoteId);
    },
    threadCount() {
      return this.thread ? this.thread.count : 1;
    },
    // Whether this row IS a draft, as opposed to a message whose conversation holds
    // one. The two are different rows on different screens and are labelled by
    // different rules; the local id is the same thing the reader, the swipe and the
    // context menu already key on, rather than which folder happens to be listed.
    isDraft() {
      return !!this.email.draftLocalId;
    },
    // Who the row names, which is not the same question for a draft as for a message.
    //
    // A message names its sender, as it always has. A DRAFT's sender is the account
    // owner — always, that is what a draft is — so naming it named the user to
    // themselves on every draft they had, and never named the person the
    // conversation was actually with: a reply to Véronika read "benjamin benjamin,
    // Draft 2". A draft is named after its CONVERSATION instead, by the other people
    // in it, which is what Gmail shows and what the product owner asked for.
    //
    // Out of the same per-conversation summary the count and the marker come from,
    // deliberately: three facts about one conversation rendered side by side, from
    // one server answer, so a name cannot appear beside a marker that disagrees with
    // it. NOT out of the draft's own recipients, which are what the user has typed so
    // far and say nothing about who wrote the mail being answered.
    //
    // A draft that answers nothing has no other participants and is named by nothing
    // at all — the row is then the marker alone, again Gmail's shape. The owner's own
    // name never appears: Gmail's word for that is "me", and only ever alongside
    // somebody else, which is a change to how every row of this list is labelled
    // rather than to how a draft's is. The server leaves the owner out; nothing here
    // has to know their address.
    participants() {
      return this.isDraft ? this.threadParticipants.join(', ') : this.email.sender.name;
    },
    // Server-stamped, read off the thread the grouping built or off the lone row,
    // exactly like the draft flag beside it.
    threadParticipants() {
      return (this.thread ? this.thread.participants : this.email.threadParticipants) || [];
    },
    // Whether this draft answers a conversation there is something to show of.
    //
    // Deliberately NOT "does it have a threadId": every draft has one, because a
    // draft that references nothing threads as a conversation of one, so the id is
    // present either way and says nothing. What tells the two apart is whether the
    // conversation holds anything BESIDES the draft, and that is exactly what the
    // count beside the participants already is — the server's per-conversation
    // aggregate, DISTINCT by Message-ID across every folder, so a plain draft counts
    // 1 and a reply counts the mail it answers too. Read off the same number the row
    // renders, so what the user sees and what the click does cannot disagree.
    //
    // It is also right in the awkward case rather than merely safe: when the parent
    // has fallen out of the cache window the count drops back to 1 and this says
    // "no conversation" — which is the truth, since opening the reader would show an
    // empty one.
    draftHasConversation() {
      return this.isDraft && this.threadCount > 1;
    },
    // Whether this conversation carries a reply the user never sent. Server-stamped
    // (the draft is a DRAFTS row and this list holds one folder's rows), so it is
    // read off the thread the grouping built, or off the lone row when there is no
    // thread.
    threadHasDraft() {
      return this.thread ? !!this.thread.hasDraft : !!this.email.threadHasDraft;
    },
    // Shown wherever the conversation carries one, the Drafts folder's own listing
    // included. This reverses what slice 6 chose — it suppressed the marker on a row
    // that IS a draft, on the reasoning that saying so on every row of Drafts is
    // noise — and it is a product decision, not something the code discovered: in a
    // list of CONVERSATIONS the marker says "this thread has an unfinished reply",
    // which is information about the thread rather than about the row, and that is
    // worth reading inside Drafts as much as outside it. There is nothing left to
    // decide per row, so the whole rule is now the flag.
    showDraftMarker() {
      return this.threadHasDraft;
    },
    // ", Draft" — built here rather than in the template so the separator sits
    // against the name with no margin of its own, the way a list separator reads.
    // The label is a key of its own and not the thread strip's: the two are separate
    // surfaces (the strip names a thing on screen, this qualifies a participant
    // list), and sharing one key would let a change to either silently rewrite the
    // other.
    //
    // The separator goes with a name and not without one. A draft that answers
    // nothing has nobody to be listed after, and Gmail renders it as the bare word:
    // a leading comma there would be punctuation attaching a marker to an absence.
    draftMarker() {
      const label = this.$t('emailConnector.mailBox.list.drawer.draft.label');
      return this.participants ? `, ${label}` : label;
    },
    // A thread is unread when any of its messages is unread; a lone email falls back to its own flag.
    threadUnread() {
      return this.thread ? this.thread.unreadCount > 0 : !this.email.read;
    },
    // A thread shows the favorite when any of its listed messages carries the flag,
    // the same any-of rule as unread.
    threadFavorite() {
      return this.thread ? this.thread.emails.some(message => message.starred) : !!this.email.starred;
    },
    // The favorite is pushed through the INBOX folder, so only inbox rows can toggle
    // it; in Sent/Archive/Trash it stays a read-only indicator.
    canToggleFavorite() {
      return (this.email.folder || 'INBOX') === 'INBOX';
    },
    /**
     * Whether this row sits in a folder the interface may only read (Trash, Spam),
     * which is what takes the swipe's delete/archive away from it. Off the ROW's own
     * folder, like canToggleFavorite above, and the same rule the row's context menu
     * reads.
     *
     * A mailbox somebody shared with the user takes it away too, where it has neither a
     * Trash nor an Archive to file into, or its rights do not let mail be taken out
     * (canDelete, canArchive) -- the same answers the row's context menu reads.
     *
     * @returns {Boolean} true when the swipe must offer nothing
     */
    readOnly() {
      return !this.$emailConnectorMailBoxService.canDelete(this.email.folder)
        && !this.$emailConnectorMailBoxService.canArchive(this.email.folder);
    },
    /**
     * The row's inline style: faded while its message is on its way into the mirror or
     * being dragged.
     *
     * @returns {Object} the style
     */
    rowStyle() {
      const style = {};
      if (this.email.refreshPending) {
        style.pointerEvents = 'none';
        style.opacity = 0.6;
      } else if (this.dragged) {
        style.opacity = 0.5;
      }
      return style;
    },
    /**
     * Whether the row is selected: every message it gathers, by folder and UID -- a row
     * of a search list is not selected because another folder's message shares a number
     * with it (EXO-90416).
     *
     * @returns {Boolean} true when selected
     */
    selected() {
      return this.selectionKeys.every(key => this.selectedEmails.includes(key));
    },
    /**
     * What selecting the row selects: the conversation's messages in the row's own
     * folder -- the ones its ⋮ menu's "Select" and its actions reach (threadRowsInFolder)
     * -- each under its own key. A row of a search list may gather a conversation's hits
     * from several folders; the others are rows of their own folders' concern.
     *
     * Off the ROWS rather than off their UIDs (EXO-90438): a key is not always made of a
     * UID. A DRAFT may have none at all -- and when the mailbox does not upload drafts to
     * the mail server none of them has one, so keyed by UID every unsent draft answered
     * to DRAFTS:null and ticking one lit them all. The row carries the local id the key
     * is built from; a list of UIDs has thrown it away.
     *
     * @returns {Array<String>} the selection keys
     */
    selectionKeys() {
      return this.$emailConnectorMailBoxService.threadRowsInFolder(this.email, this.thread).map(selectionKey);
    },
    opened() {
      return this.openedEmailId === this.email.mailRemoteId;
    },
    /**
     * Whether the reader beside the list shows this row's message, for aria-current.
     *
     * @returns {Boolean} true when it does
     */
    inReader() {
      return this.readerEmailId != null && this.threadIds.includes(this.readerEmailId);
    },
    backgroundClass() {
      if (this.isMobile) {
        return 'no-select';
      }
      if (this.expanded && (this.isHover || this.opened || this.selected)) {
        return 'grey-lighten1-background-opacity-3';
      }
      if (this.isHover) {
        return 'light-grey-background-color';
      }
      return '';
    },
    // A draft has no sender worth announcing — it is the user's own — and routinely
    // no subject either, so it is announced as what it is and by the fallback title
    // the row itself shows rather than by "from me about undefined".
    ariaLabel() {
      if (this.isDraft) {
        return `Open unsent draft about ${this.subject}`;
      }
      return `Open email from ${this.email.sender.name} about ${this.email.subject}`;
    },
  },
  methods: {
    /**
     * Sends a swipe's action on the row as its ⋮ menu does: the conversation's messages
     * in the row's own folder (threadIdsInFolder), with that folder -- a row of a search
     * list may gather hits from several folders, and a bare UID would be resolved in the
     * listed folder, where the same number is another message (EXO-90416).
     *
     * @param {String} event the action's event
     * @returns {void}
     */
    emitForRow(event) {
      this.$root.$emit(event, this.$emailConnectorMailBoxService.threadIdsInFolder(this.email, this.thread), this.email.folder || 'INBOX');
    },
    emitSelect(selected) {
      // A thread selects/deselects as a whole, in the row's folder (see selectionKeys):
      // one select-email per message, with that folder -- and with the draft's own local
      // id when it has one, because that, not a UID it may not have, is what keys it
      // (EXO-90438). The event names the MESSAGE; the drawer builds the key from it, so
      // a key is still built in the one place that knows how.
      const folder = this.email.folder || 'INBOX';
      this.$emailConnectorMailBoxService.threadRowsInFolder(this.email, this.thread)
        .forEach(message => this.$root.$emit('select-email', {
          emailId: message.mailRemoteId,
          draftLocalId: message.draftLocalId,
          folder,
          selected,
        }));
    },
    // Favorite/unfavorite the whole row, i.e. every listed message of the thread —
    // matching how the row's read/unread action treats a conversation.
    toggleThreadFavorite() {
      this.$root.$emit('update-email-favorite-status', !this.threadFavorite, this.threadIds);
    },
    openDetail() {
      if (this.selectMode) {
        this.emitSelect(!this.selected);
      }
      else if (this.isDraft) {
        this.openDraft();
      }
      else {
        if (this.expanded) {
          this.$root.$emit('open-email-detail-content', this.email.mailRemoteId, this.email.folder || 'INBOX');
          this.$root.$emit('set-opened', this.email.mailRemoteId);
        }
        else {
          this.$root.$emit('open-email-detail-drawer', this.email.mailRemoteId, this.emails, this.syncInProgress, this.webmailUrl,
            false, false, this.email.folder || 'INBOX');
        }
      }
    },
    /**
     * Opens a draft, which means one of two different things — Gmail's rule, asked
     * for by name.
     *
     * A plain draft, a message that answers nothing, goes straight back to the
     * composer: there is no conversation to put it in and a reader would have one
     * item in it, the unfinished thing the user came back to write.
     *
     * A reply lands in the conversation it answers instead, where the reader already
     * renders it at the bottom as its own strip and it is resumed from there. Getting
     * there means opening the reader on the DRAFT's own row — deliberately, rather
     * than hunting the conversation for a real message to open it on: the reader only
     * ever needs the row's thread id, it fetches the conversation itself, and looking
     * for an anchor would mean a second thread request and a message that may be in
     * any folder. The one thing it costs is the toolbar's mail actions, which address
     * a message by IMAP UID and are hidden for a draft anchor for exactly the reason
     * every other mail action already stays off a draft row.
     *
     * @returns {void}
     */
    openDraft() {
      if (!this.draftHasConversation) {
        this.$root.$emit('resume-draft', this.email);
      }
      else if (this.expanded) {
        this.$root.$emit('open-email-thread-content', this.email);
        this.$root.$emit('set-opened', this.email.mailRemoteId);
      }
      else {
        this.$root.$emit('open-email-thread-drawer', this.email, this.emails, this.syncInProgress, this.webmailUrl);
      }
    },
    openActionMenuDrawer() {
      // The long press is a phone's only way into the row menu — the ⋮ is not rendered
      // there (see the template) — so what it may open is what the menu has to offer.
      //
      // For a draft that used to be nothing: every action in that menu addressed a
      // message by its IMAP UID, which a draft may not have, and none of them meant
      // anything for an unsent message. Discard changed that (EXO-90438): it addresses
      // the draft by its local id and is the one action that does mean something here,
      // so a draft row opens the menu again — with Discard, and with the Select that
      // leads to the same Discard on the bar. The menu itself withholds the rest on a
      // draft (canMove, canFavorite, inDrafts there), which is why the refusal does not
      // need repeating in this guard.
      if (!this.selectMode && !this.isSwiping) {
        this.$root.$emit('open-email-action-menu-drawer', this.email, this.thread);
      }
    },
    async reset() {
      this.absolute = false;
      await this.$nextTick();
      this.left = 0;
      this.movingLeft = false;
      this.startEvent = null;
      this.minWidth = 0;
      this.isSwiping = false;
    },
    async moveStart() {
      if (this.absolute) {
        return;
      }
      await this.reset();
      this.minWidth = Math.max(this.minWidth, this.$refs?.mail?.$el?.offsetWidth);
    },
    moveEnd() {
      // Delete and archive both address messages by IMAP UID, and a draft that has
      // not been uploaded has none. Discarding a draft is its own action, in the
      // composer, where the user can see what they are throwing away.
      //
      // A Trash row is refused for a different reason: delete and archive both mean
      // moving the message somewhere it already is, and the Trash has its own two
      // actions for what can still be done to it. The row snaps back instead — the
      // same nothing a swipe on a draft already does.
      if (this.isDraft || this.readOnly) {
        this.reset();
        return;
      }
      const deleteEmail = this.left > 0;
      const confirm = Math.abs(this.left) > (this.minWidth / 2);
      // Each direction asks its own destination (EXO-90548): a shared mailbox with no
      // Trash takes no delete swipe, one with no Archive no archive swipe.
      const service = this.$emailConnectorMailBoxService;
      const allowed = deleteEmail ? service.canDelete(this.email.folder) : service.canArchive(this.email.folder);
      if (confirm && allowed) {
        if (deleteEmail) {
          this.emitForRow('delete-email');
        } else {
          this.emitForRow('archive-email');
        }
      } else {
        this.reset();
      }
    },
    moveSwipe(event) {
      if (this.selectMode) {
        return;
      }
      if (!this.startEvent) {
        this.startEvent = event;
        return;
      }
      const deltaX = event.touchmoveX - this.startEvent.touchmoveX;
      if (!this.absolute && Math.abs(deltaX) > 10) {
        this.absolute = true;
        this.isSwiping = true;
      }
      if (!this.absolute) {
        return;
      }
      this.left = deltaX;
      this.movingLeft = this.left < 0;
    },
    onSelectChange(value) {
      this.emitSelect(value);
    },
    /**
     * Starts dragging the row -- or the selection it belongs to (dragPayloadOfRow) --
     * and tells the drawer what is dragged, which the folder column needs during the
     * drag. A row that may not be dragged now (a selection across folders) refuses.
     *
     * @param {DragEvent} event the dragstart event
     * @returns {void}
     */
    onDragStart(event) {
      const row = {
        email: this.email,
        thread: this.thread,
        selectMode: this.selectMode,
        selectedEmails: this.selectedEmails,
        emails: this.emails,
      };
      const payload = this.canDrag && dragPayloadOfRow(row);
      if (!payload) {
        event.preventDefault();
        return;
      }
      // The picture counts the rows the user dragged, the payload every message they hold.
      startDrag(event, payload, dragLabel(draggedRowCount(row), this.$t.bind(this)));
      this.$root.$emit('email-drag-start', payload);
    },
    /**
     * Ends the drag, dropped or not; the pointer is no longer over the row.
     *
     * @returns {void}
     */
    onDragEnd() {
      this.isHover = false;
      this.$root.$emit('email-drag-end');
    },
  }
};
</script>
