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
  <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
  <div
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
      <v-checkbox
        v-if="selectMode"
        class="me-0 pt-0 align-self-center"
        color="#707070"
        background-color="transparent"
        :input-value="selected"
        @click.stop
        @change="onSelectChange" />
      <div class="flex-grow-1 no-min-width">    
        <!-- eslint-disable vuejs-accessibility/no-static-element-interactions -->
        <div
          class="clickable"
          tabindex="0"
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
                {{ email.sender.name }}<span
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
                :box-size="18"
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
    webmailUrl: {
      type: String,
      default: null,
    },
  },
  computed: {
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
    threadCount() {
      return this.thread ? this.thread.count : 1;
    },
    // Whether this conversation carries a reply the user never sent. Server-stamped
    // (the draft is a DRAFTS row and this list holds one folder's rows), so it is
    // read off the thread the grouping built, or off the lone row when there is no
    // thread.
    threadHasDraft() {
      return this.thread ? !!this.thread.hasDraft : !!this.email.threadHasDraft;
    },
    // A row that IS a draft does not get told it has one. That is what keeps the
    // Drafts folder's own listing quiet: every row there is the draft, saying so on
    // each of them is noise, and Gmail does not do it either. The rule is read off
    // the row rather than off a "which folder are we listing" prop, so it cannot
    // drift from the folder the rows actually came from — the same reason the
    // reader's own isDraft keys on the local id and not on the folder.
    showDraftMarker() {
      return this.threadHasDraft && !this.email.draftLocalId;
    },
    // ", Draft" — built here rather than in the template so the separator sits
    // against the name with no margin of its own, the way a list separator reads.
    // The label is a key of its own and not the thread strip's: the two are separate
    // surfaces (the strip names a thing on screen, this qualifies a participant
    // list), and sharing one key would let a change to either silently rewrite the
    // other.
    draftMarker() {
      return `, ${this.$t('emailConnector.mailBox.list.drawer.draft.label')}`;
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
    // it; in Sent/Archive it stays a read-only indicator.
    canToggleFavorite() {
      return (this.email.folder || 'INBOX') === 'INBOX';
    },
    selected() {
      return this.threadIds.every(id => this.selectedEmails.includes(id));
    },
    opened() {
      return this.openedEmailId === this.email.mailRemoteId;
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
    ariaLabel() {
      return `Open email from ${this.email.sender.name} about ${this.email.subject}`;
    },
  },
  methods: {
    emitSelect(selected) {
      // A thread selects/deselects as a whole: one select-email per message id.
      this.threadIds.forEach(emailId => this.$root.$emit('select-email', { emailId, selected }));
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
      // A draft has no reader: opening it means going back to writing it. It also has
      // no IMAP UID until it has been uploaded, so the detail path — which addresses
      // messages by UID — has nothing to open it with.
      else if (this.email.draftLocalId) {
        this.$root.$emit('resume-draft', this.email);
      }
      else {
        if (this.expanded) {
          this.$root.$emit('open-email-detail-content', this.email.mailRemoteId);
          this.$root.$emit('set-opened', this.email.mailRemoteId);
        }
        else {
          this.$root.$emit('open-email-detail-drawer', this.email.mailRemoteId, this.emails, this.syncInProgress, this.webmailUrl);
        }
      }
    },
    openActionMenuDrawer() {
      // Every action in that menu — reply, forward, archive, delete, categorize —
      // addresses a message by its IMAP UID, which a draft may not have yet, and none
      // of them means anything for an unsent message anyway.
      if (this.email.draftLocalId) {
        return;
      }
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
      if (this.email.draftLocalId) {
        this.reset();
        return;
      }
      const deleteEmail = this.left > 0;
      const confirm = Math.abs(this.left) > (this.minWidth / 2);
      if (confirm) {
        if (deleteEmail) {
          this.$root.$emit('delete-email', this.threadIds);
        } else {
          this.$root.$emit('archive-email', this.threadIds);
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
    }
  }
};
</script>
