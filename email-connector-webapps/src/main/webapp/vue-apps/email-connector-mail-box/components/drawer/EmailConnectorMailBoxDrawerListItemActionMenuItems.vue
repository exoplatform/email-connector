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
         read state worth pushing.
         Off a DRAFT's row too (EXO-90438), and by decision rather than by refusal: a
         draft is the user's own text, stored read and never announced as new mail, so
         "Mark as unread" there promised a state that means nothing. The server would
         take it for a draft that has been uploaded — and on one that has not there is
         no message up there to carry the flag, so the push came back as a failure that,
         until this change, nothing showed. -->
    <v-list-item
      v-if="canMarkRead && !inDrafts"
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
         these two"; `canArchive` / `canDelete` are the folder, and in a shared mailbox
         its destinations, saying whether they may be offered at all (EXO-90548). -->
    <v-list-item
      v-if="!restricted && canArchive"
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
      v-if="canMarkAsJunk"
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
    <!-- "Move to...", where "Mark as spam" is: the user's own mirrored folders, offered
         only when they have one to move into. Not gated on `restricted` either: the
         swipe does not offer it. -->
    <v-list-item
      v-if="canMove && hasMoveTargets"
      class="ps-2 pe-3 height-auto"
      @click.stop="moveToFolder">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="mx-auto"
          size="16">
          fa-folder-open
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.moveTo.label') }}
      </span>
    </v-list-item>
    <v-list-item
      v-if="inboxOnlyHint"
      class="ps-2 pe-3 height-auto"
      inactive>
      <span class="caption text-sub-title">{{ inboxOnlyHint }}</span>
    </v-list-item>
    <v-list-item
      v-if="!restricted && canDelete"
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
    <!-- The Drafts folder's own destructive action, where every ordinary one above is
         withheld: Discard, which removes the copy on the mail server and the local row
         rather than filing the draft anywhere. Same wording and same confirmation as
         the bulk Discard of the selection toolbar — one dialog, whoever asks. -->
    <v-list-item
      v-if="canDiscard"
      class="ps-2 pe-3 height-auto"
      @click.stop="discardDraft">
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
        {{ $t('emailConnector.mailBox.list.drawer.detail.discard.label') }}
      </span>
    </v-list-item>
    <!-- The Spam folder's own two actions, where the ordinary ones above are withheld:
         "Not spam" back to the inbox, and a Delete that files into the Trash exactly
         as the ordinary delete does (the backend allows that one move out of Junk). -->
    <v-list-item
      v-if="junkActions && canRestoreFromJunk"
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
    /**
     * All message ids the action applies to: the whole thread as listed in this row's
     * own folder, or the lone email. The single definition shared with the reader's
     * header toolbar (EmailConnectorMailBoxDrawerListItemDetailActions) — see
     * $emailConnectorMailBoxService.threadIdsInFolder (EXO-89942).
     *
     * @returns {Array<Number>} the IMAP UIDs the action applies to
     */
    threadIds() {
      return this.$emailConnectorMailBoxService.threadIdsInFolder(this.email, this.thread);
    },
    /**
     * The folder the menu acts in -- the row's own, the one threadIds is scoped to --
     * sent along with the ids: in a list of search results the rows come from several
     * folders, where the same numbers are other messages (EXO-90416).
     *
     * @returns {String} the folder key
     */
    actingFolder() {
      return this.email?.folder || 'INBOX';
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
     * Whether read/unread may be offered on this row: not in a read-only folder, and in
     * a mailbox somebody shared with the user only with the right to keep read state --
     * which is the owner's read state too (canMarkReadIn).
     *
     * @returns {Boolean} true when the read-status item belongs on this row
     */
    canMarkRead() {
      return this.$emailConnectorMailBoxService.canMarkReadIn(this.email.folder);
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
     * Whether "Not spam" belongs on this row: in the user's own Spam only -- out of a
     * shared mailbox's it would file into another mailbox (EXO-90548).
     *
     * @returns {Boolean} true when "Not spam" is offered
     */
    canRestoreFromJunk() {
      return this.$emailConnectorMailBoxService.canRestoreFromJunk(this.email.folder);
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
      return this.$emailConnectorMailBoxService.canMoveOutOf(this.email.folder);
    },
    /**
     * Whether "Mark as spam" belongs on this row: where delete and archive do, and in a
     * shared mailbox only when its owner shares a Spam folder to file into (EXO-90548) --
     * the rule the toolbar and the reader already ask.
     *
     * @returns {Boolean} true when "Mark as spam" is offered
     */
    canMarkAsJunk() {
      return this.$emailConnectorMailBoxService.canMarkAsJunk(this.email.folder);
    },
    /**
     * Whether Archive belongs on this row: in a shared mailbox, only where its owner
     * shares an Archive to file into (EXO-90548).
     *
     * @returns {Boolean} true when Archive is offered
     */
    canArchive() {
      return this.$emailConnectorMailBoxService.canArchive(this.email.folder);
    },
    /**
     * Whether Delete belongs on this row: in a shared mailbox, only where its owner
     * shares a Trash to file into (EXO-90548).
     *
     * @returns {Boolean} true when Delete is offered
     */
    canDelete() {
      return this.$emailConnectorMailBoxService.canDelete(this.email.folder);
    },
    /**
     * The owner of a shared mailbox that shares only its Inbox, when the user could
     * otherwise take this row out of it -- said on the menu in place of the absent
     * Delete and Archive (EXO-90548).
     *
     * @returns {String} the hint, or empty
     */
    inboxOnlyHint() {
      const entry = this.$emailConnectorMailBoxService.inboxOnlyShareHint(this.email.folder);
      return entry ? this.$t('emailConnector.mailBox.sharedMailbox.inboxOnlyActions', { 0: entry.ownerFullName }) : '';
    },
    /**
     * Whether this row is a draft listed in the Drafts folder — asked of the same
     * service the bulk toolbar asks, so the menu and the bar cannot disagree about
     * where the ordinary actions stop and Discard starts.
     *
     * @returns {Boolean} true when the row is in Drafts
     */
    inDrafts() {
      return this.$emailConnectorMailBoxService.isDraftsFolder(this.email.folder);
    },
    /**
     * Whether Discard belongs on this row (EXO-90438): a draft, and one the discard
     * endpoint can address — it takes the draft's LOCAL id, which a row that is not a
     * draft has none of.
     *
     * @returns {Boolean} true when Discard belongs on this row
     */
    canDiscard() {
      return this.draftRows.length > 0;
    },
    /**
     * The drafts this row stands for.
     *
     * A listing row is a CONVERSATION, so two drafts answering the same exchange
     * collapse into one row (groupEmailsByThread), and ticking that row selects both —
     * the bulk Discard would then throw both away. The menu has to mean the same thing
     * as the checkbox beside it, or discarding from the ⋮ would leave a draft behind
     * and read as an action that did not work.
     *
     * Off threadRowsInFolder, which is the very list the checkbox keys its selection on
     * -- so the two cannot come to mean different sets -- and not off threadIds, its
     * sibling: that one yields UIDs, and a draft's is exactly what may be missing.
     *
     * @returns {Array<Object>} the draft rows Discard applies to, empty when this row
     *          is not a draft at all
     */
    draftRows() {
      return this.$emailConnectorMailBoxService.threadRowsInFolder(this.email, this.thread)
        .filter(message => this.$emailConnectorMailBoxService.isDraftsFolder(message.folder)
                           && !!message.draftLocalId);
    },
    /**
     * Whether the user has a mirrored folder of their own to move this row into,
     * other than the one it is listed in. Read off the root at render time: the menu
     * is created on click, and the mailbox drawer keeps the server's folder list there.
     *
     * @returns {Boolean} true when "Move to..." has somewhere to go
     */
    hasMoveTargets() {
      return this.$emailConnectorMailBoxService.moveTargets(this.$root.mailFolders, this.email.folder).length > 0;
    },
  },
  methods: {
    /**
     * Starts a selection on this row's conversation, exactly as ticking its checkbox
     * does: one select-email per message of the acting folder, naming the draft's local
     * id where there is one so the drawer keys it as the checkbox would (EXO-90438).
     *
     * @returns {void}
     */
    selectEmail() {
      this.$emit('close');
      this.$emailConnectorMailBoxService.threadRowsInFolder(this.email, this.thread)
        .forEach(message => this.$root.$emit('select-email', {
          emailId: message.mailRemoteId,
          draftLocalId: message.draftLocalId,
          folder: this.actingFolder,
          selected: true,
        }));
    },
    /**
     * Marks the row's conversation read or unread from the row menu. The user asked
     * for it, so a push the mail server would not take is shown (EXO-90444).
     *
     * @returns {void}
     */
    updateEmailReadStatus() {
      this.$emit('close');
      this.$root.$emit('update-email-read-status', !this.threadRead, this.threadIds, this.actingFolder, null, { userInitiated: true });
    },
    updateEmailFavoriteStatus() {
      this.$emit('close');
      this.$root.$emit('update-email-favorite-status', !this.threadFavorite, this.threadIds);
    },
    deleteEmail() {
      this.$emit('close');
      this.$root.$emit('delete-email', this.threadIds, this.actingFolder);
    },
    archiveEmail() {
      this.$emit('close');
      this.$root.$emit('archive-email', this.threadIds, this.actingFolder);
    },
    /**
     * Reports the row (or the whole thread it stands for) as spam. No confirmation:
     * the move is undone from the Spam listing with "Not spam".
     *
     * @returns {void}
     */
    markAsJunk() {
      this.$emit('close');
      this.$root.$emit('junk-email', this.threadIds, this.actingFolder);
    },
    /**
     * Opens the folder picker for the row (or the whole thread it stands for).
     *
     * @returns {void}
     */
    moveToFolder() {
      this.$emit('close');
      this.$root.$emit('open-move-to-folder-drawer', this.threadIds, this.email.folder || 'INBOX');
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
    /**
     * Asks first, then throws this row's drafts away — the drafts and nothing else, so
     * the menu and the checkbox on the same row mean the same thing (see draftRows).
     *
     * The rows are handed over rather than their ids: the confirmation has to tell a
     * scheduled draft from an ordinary one, and the discard addresses a draft by its
     * LOCAL id — the UID the rest of this menu works with is exactly what an unpushed
     * draft does not have.
     *
     * @returns {void}
     */
    discardDraft() {
      this.$emit('close');
      this.$root.$emit('open-discard-drafts-confirm-popup', this.draftRows);
    },
  }
};
</script>