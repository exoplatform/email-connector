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
  <v-list class="pa-0">
    <v-list-item
      class="ps-2 pe-3 height-auto"
      @click.stop="selectEmail">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          fa-mouse-pointer
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.select.label') }}
      </span>
    </v-list-item>
    <!-- Read/unread is a write to the mail server, so it stays off a read-only folder's
         rows. It is folder-aware now (EXO-89367), so this is no longer about the write
         landing in the wrong place — it is that a message the user threw away has no
         read state worth pushing. -->
    <v-list-item
      v-if="!readOnly"
      class="ps-2 pe-3 height-auto"
      @click.stop="updateEmailReadStatus">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          {{ threadRead ? 'fa-mail-bulk' : 'fa-envelope-open-text' }}
        </v-icon>
      </v-sheet>
      <span v-if="threadRead">
        {{ $t('emailConnector.mailBox.list.drawer.detail.unread.label') }}
      </span>
      <span v-else>
        {{ $t('emailConnector.mailBox.list.drawer.detail.read.label') }}
      </span>
    </v-list-item>
    <!-- Favorite/unfavorite the conversation: the mail server's own \Flagged flag, so it
         shows in every client. Inbox only — the flag is pushed through INBOX. -->
    <v-list-item
      v-if="canFavorite"
      class="ps-2 pe-3 height-auto"
      @click.stop="updateEmailFavoriteStatus">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          {{ threadFavorite ? 'fas fa-star' : 'far fa-star' }}
        </v-icon>
      </v-sheet>
      <span v-if="threadFavorite">
        {{ $t('emailConnector.mailBox.list.drawer.detail.removeFavorite.label') }}
      </span>
      <span v-else>
        {{ $t('emailConnector.mailBox.list.drawer.detail.addFavorite.label') }}
      </span>
    </v-list-item>
    <extension-registry-components
      :params="{
        email,
      }"
      name="Email"
      type="email-menu-action"
      parent-element="div"
      element="div"
      class="my-auto" /> 
    <!-- `restricted` is the mobile long-press drawer saying "the swipe already offers
         these two"; `canMove` is the folder saying they must not be offered at all. -->
    <v-list-item
      v-if="!restricted && canMove"
      class="ps-2 pe-3 height-auto"
      @click.stop="archiveEmail">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="mx-auto"
          size="16">
          fa-archive
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.archive.label') }}
      </span>
    </v-list-item>
    <!-- "Mark as spam", offered on the same rows as delete and archive, and placed
         before Delete: reporting a message as spam is the gentler of the two and the
         one a user reaches for first. Not gated on `restricted`: the swipe does not
         offer it, so the mobile long-press drawer is the only place a phone user can
         reach it from. -->
    <v-list-item
      v-if="canMove"
      class="ps-2 pe-3 height-auto"
      @click.stop="markAsJunk">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="mx-auto"
          size="16">
          fa-ban
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.markJunk.label') }}
      </span>
    </v-list-item>
    <v-list-item
      v-if="!restricted && canMove"
      class="ps-2 pe-3 height-auto"
      @click.stop="deleteEmail">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="error--text mx-auto"
          size="16">
          fa-trash
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.delete.label') }}
      </span>
    </v-list-item>
    <!-- The Spam folder's own two actions, where the ordinary ones above are withheld:
         "Not spam" back to the inbox, and a Delete that files into the Trash exactly
         as the ordinary delete does (the backend allows that one move out of Junk). -->
    <v-list-item
      v-if="junkActions"
      class="ps-2 pe-3 height-auto"
      @click.stop="restoreFromJunk">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="mx-auto"
          size="16">
          fa-check-circle
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.notJunk.label') }}
      </span>
    </v-list-item>
    <v-list-item
      v-if="junkActions"
      class="ps-2 pe-3 height-auto"
      @click.stop="deleteEmail">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="error--text mx-auto"
          size="16">
          fa-trash
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.delete.label') }}
      </span>
    </v-list-item>
    <!-- The Trash's own two actions, where the ordinary ones above are withheld. Not
         gated on `restricted`: the swipe offers neither of them, so there is nothing
         here for the mobile long-press drawer to be repeating. -->
    <v-list-item
      v-if="trashActions"
      class="ps-2 pe-3 height-auto"
      @click.stop="restoreEmail">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="mx-auto"
          size="16">
          fa-trash-restore
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.restore.label') }}
      </span>
    </v-list-item>
    <v-list-item
      v-if="trashActions"
      class="ps-2 pe-3 height-auto"
      @click.stop="purgeEmail">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="error--text mx-auto"
          size="16">
          fa-times-circle
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.purge.label') }}
      </span>
    </v-list-item>
  </v-list>
</template>

<script>
export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
    thread: {
      type: Object,
      default: () => null,
    },
    restricted: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    // All message ids the action applies to: the whole thread, or the lone email.
    threadIds() {
      return this.thread ? this.thread.mailRemoteIds : [this.email.mailRemoteId];
    },
    // A thread reads as read only when none of its messages is unread.
    threadRead() {
      return this.thread ? this.thread.unreadCount === 0 : this.email.read;
    },
    // A thread shows as favorite when any of its listed messages carries the flag.
    threadFavorite() {
      return this.thread ? this.thread.emails.some(message => message.starred) : !!this.email.starred;
    },
    // The favorite is pushed through the INBOX folder, so only inbox rows offer it —
    // which already keeps it off a Trash row, before readOnly below has any say.
    canFavorite() {
      return (this.email.folder || 'INBOX') === 'INBOX';
    },
    /**
     * Whether this row sits in a folder the interface may only read (Trash, Spam), in
     * which case every action that writes to the mail server stays off the menu. Read
     * off the ROW's own folder rather than off the listed one, the same way canFavorite
     * above already is: the row is the thing being acted on, and it is also what the
     * mobile long-press drawer and the search results hand over.
     *
     * @returns {Boolean} true when no mutating action may be offered
     */
    readOnly() {
      return this.$emailConnectorMailBoxService.isReadOnlyFolder(this.email.folder);
    },
    /**
     * Whether this row is one the Trash actions apply to. Off the ROW's folder for the
     * same reason readOnly above is, and asked of the same service so the two answers
     * are made in one place: a folder that offers restore must be one where the
     * ordinary actions are withheld, and nothing here can drift out of that pairing.
     *
     * @returns {Boolean} true when restore / delete permanently belong on this row
     */
    trashActions() {
      return this.$emailConnectorMailBoxService.hasTrashActions(this.email.folder);
    },
    /**
     * Whether this row is one the Spam actions apply to — the same pairing rule as
     * trashActions, for the other hidden folder.
     *
     * @returns {Boolean} true when "Not spam" / delete belong on this row
     */
    junkActions() {
      return this.$emailConnectorMailBoxService.hasJunkActions(this.email.folder);
    },
    /**
     * Whether delete, archive and mark-as-spam may be offered on this row at all. On
     * top of readOnly: all three address a message by its IMAP uid, and an unsent draft
     * has none to address -- discarding a draft is its own action, in the composer,
     * where the user can see what they are throwing away. Exactly what the swipe has
     * always refused (moveEnd in EmailConnectorMailBoxDrawerListItem), asked here so the
     * menu and the swipe agree.
     *
     * @returns {Boolean} true when the folder-changing actions belong on this row
     */
    canMove() {
      return !this.readOnly && (this.email.folder || 'INBOX') !== 'DRAFTS';
    },
  },
  methods: {
    selectEmail() {
      this.$emit('close');
      this.threadIds.forEach(emailId => this.$root.$emit('select-email', { emailId, selected: true }));
    },
    updateEmailReadStatus() {
      this.$emit('close');
      this.$root.$emit('update-email-read-status', !this.threadRead, this.threadIds);
    },
    updateEmailFavoriteStatus() {
      this.$emit('close');
      this.$root.$emit('update-email-favorite-status', !this.threadFavorite, this.threadIds);
    },
    deleteEmail() {
      this.$emit('close');
      this.$root.$emit('delete-email', this.threadIds);
    },
    archiveEmail() {
      this.$emit('close');
      this.$root.$emit('archive-email', this.threadIds);
    },
    /**
     * Reports the row (or the whole thread it stands for) as spam. No confirmation:
     * the move is undone from the Spam listing with "Not spam".
     *
     * @returns {void}
     */
    markAsJunk() {
      this.$emit('close');
      this.$root.$emit('junk-email', this.threadIds);
    },
    /**
     * Puts the row (or the whole thread it stands for) back into the inbox out of the
     * Spam folder. No confirmation, for the same reason as restoreEmail below.
     *
     * @returns {void}
     */
    restoreFromJunk() {
      this.$emit('close');
      this.$root.$emit('not-junk-email', this.threadIds);
    },
    /**
     * Puts the row (or the whole thread it stands for) back into the inbox. No
     * confirmation: a restore is undone by deleting again.
     *
     * @returns {void}
     */
    restoreEmail() {
      this.$emit('close');
      this.$root.$emit('restore-email', this.threadIds);
    },
    /**
     * Asks first, then destroys. The confirmation is opened from here, where the click
     * happened and where the count is known, rather than inside the drawer that sends
     * the request — so every entry point asks the same question in the same words.
     *
     * @returns {void}
     */
    purgeEmail() {
      this.$emit('close');
      this.$root.$emit('open-purge-email-confirm-popup', this.threadIds);
    },
  }
};
</script>