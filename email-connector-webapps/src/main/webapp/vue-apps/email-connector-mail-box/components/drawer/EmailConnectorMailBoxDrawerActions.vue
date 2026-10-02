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
  <!-- A running sync is signalled on the drawer's header loading bar, the one progress
       indicator the mail app uses. The full-screen Synchronize button's spinner is that
       button's own busy state, not a second indicator: it says why it cannot be pressed. -->
  <div class="align-self-center" v-if="!selectMode">
    <v-btn
      :title="$t('emailConnector.mailBox.list.drawer.newEmail.button.title')"
      @click="openNewEmailDrawer()"
      icon>
      <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
    </v-btn>
    <!-- Synchronize, beside the menu in full screen rather than inside it (EXO-90624);
         the narrow drawer keeps it in the menu, its header has no room to spare. The
         wrapper carries the tooltip, which a disabled button cannot show itself (it
         takes no pointer events). -->
    <span
      v-if="syncButton"
      :title="syncTitle"
      class="d-inline-flex valign-middle">
      <v-btn
        :aria-label="syncTitle"
        :disabled="syncInProgress"
        :loading="syncInProgress"
        icon
        @click="synchronize()">
        <v-icon size="20" class="icon-default-color">fa-sync-alt</v-icon>
      </v-btn>
    </span>
    <email-connector-mail-box-drawer-action-menu
      :current-folder="currentFolder"
      :available-folders="availableFolders"
      :namespace-folders="namespaceFolders"
      :categories="categories"
      :category-view-id="categoryViewId"
      :folder-counts="folderCounts"
      :category-unread-counts="categoryUnreadCounts"
      :sync-in-progress="syncInProgress"
      :has-webmail-access="hasWebmailAccess"
      :hide-views="hideViews"
      :hide-sync="syncButton" />
  </div>
  <div v-else-if="hasSelectedEmails">
    <template v-if="top">
      <v-btn
        v-if="canUpdateEmailsReadStatus(false)"
        :title="$t('emailConnector.mailBox.list.drawer.detail.unread.label')"
        @click="updateEmailsReadStatus(false)"
        icon>
        <v-icon size="20" class="icon-default-color">fa-mail-bulk</v-icon>
      </v-btn>
      <v-btn
        v-if="canUpdateEmailsReadStatus(true)"
        :title="$t('emailConnector.mailBox.list.drawer.detail.read.label')"
        @click="updateEmailsReadStatus(true)"
        icon>
        <v-icon size="20" class="icon-default-color">fa-envelope-open-text</v-icon>
      </v-btn>
      <v-btn
        v-if="canArchiveSelection"
        :title="$t('emailConnector.mailBox.list.drawer.detail.archive.label')"
        @click="archiveEmails()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-archive</v-icon>
      </v-btn>
      <v-btn
        v-if="canMarkSelectionAsJunk"
        :title="$t('emailConnector.mailBox.list.drawer.detail.markJunk.label')"
        @click="markAsJunk()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-ban</v-icon>
      </v-btn>
      <!-- Shown for a selection across folders too, disabled: the picker moves from one
           source folder, and the wrapper carries the reason, which a disabled button
           cannot show itself (it takes no pointer events). -->
      <span
        v-if="canOfferMove"
        :title="moveTitle"
        class="d-inline-flex valign-middle">
        <v-btn
          :disabled="!canMoveSelection"
          :aria-label="moveTitle"
          @click="moveEmails()"
          icon>
          <v-icon size="20" class="icon-default-color">fa-folder-open</v-icon>
        </v-btn>
      </span>
      <!-- The selection as a .zip of .eml files (EXO-90845). -->
      <v-btn
        v-if="canDownloadSelection"
        :title="$t('emailConnector.mailBox.export.zip')"
        :loading="downloadingZip"
        @click="downloadSelection()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-file-archive</v-icon>
      </v-btn>
      <v-btn
        v-if="canDeleteSelection"
        :title="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
        @click="deleteEmails()"
        icon>
        <v-icon size="20" class="error--text">fa-trash</v-icon>
      </v-btn>
      <v-btn
        v-if="canDiscardSelection"
        :title="$t('emailConnector.mailBox.list.drawer.detail.discard.label')"
        @click="discardDrafts()"
        icon>
        <v-icon size="20" class="error--text">fa-trash</v-icon>
      </v-btn>
      <v-btn
        v-if="canApplyNotJunk"
        :title="$t('emailConnector.mailBox.list.drawer.detail.notJunk.label')"
        @click="restoreFromJunk()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-check-circle</v-icon>
      </v-btn>
      <v-btn
        v-if="canApplyJunkActions"
        :title="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
        @click="deleteEmails()"
        icon>
        <v-icon size="20" class="error--text">fa-trash</v-icon>
      </v-btn>
      <v-btn
        v-if="canApplyTrashActions"
        :title="$t('emailConnector.mailBox.list.drawer.detail.restore.label')"
        @click="restoreEmails()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-trash-restore</v-icon>
      </v-btn>
      <v-btn
        v-if="canApplyTrashActions"
        :title="$t('emailConnector.mailBox.list.drawer.detail.purge.label')"
        @click="purgeEmails()"
        icon>
        <v-icon size="20" class="error--text">fa-times-circle</v-icon>
      </v-btn>
    </template>
    <!-- The selection panel's actions, as flat tiles: an icon above a short label, all
         one width, on one row -- or wrapped evenly (3 + 3) when the pane is narrow --
         and the destructive one last, in the error colour. The full wording rides the
         aria label and the tooltip. -->
    <div
      v-else
      ref="tiles"
      :style="tilesStyle"
      class="mx-auto">
      <div
        v-for="tile in tiles"
        :key="tile.key"
        :title="tile.title"
        class="d-flex">
        <v-btn
          :aria-label="tile.title"
          :disabled="tile.disabled"
          :loading="tile.loading"
          :color="tile.danger ? 'error' : null"
          height="64"
          class="flex-grow-1 text-none px-1"
          text
          @click="tile.action()">
          <div class="d-flex flex-column align-center text-truncate">
            <v-icon
              :class="tile.danger ? 'error--text' : 'icon-default-color'"
              size="20">
              {{ tile.icon }}
            </v-icon>
            <span
              :class="{ 'error--text': tile.danger }"
              class="caption mt-1 text-truncate">
              {{ tile.label }}
            </span>
          </div>
        </v-btn>
      </div>
    </div>
  </div>
</template>

<script>
import { parseSelectionKey, selectionByFolder, selectionKey } from '../../js/EmailConnectorMailBoxSelection.js';
import { canDownloadSelection, downloadSelectionZip, exportErrorKey } from '../../js/EmailConnectorMailTransfer.js';

// The narrowest a selection tile may be before the row wraps, and the widest it grows.
const TILE_MIN_WIDTH_PX = 92;

const TILE_MAX_WIDTH_PX = 120;

// The space between two tiles.
const TILE_GAP_PX = 4;

export default {
  props: {
    emails: {
      type: Array,
      default: () => [],
    },
    selectedEmails: {
      type: Array,
      default: () => [],
    },
    selectMode: {
      type: Boolean,
      default: false,
    },
    syncInProgress: {
      type: Boolean,
      default: false,
    },
    webmailUrl: {
      type: String,
      default: null,
    },
    // The folder currently listed (INBOX / SENT / ARCHIVE), for the ⋮ folder switch.
    currentFolder: {
      type: String,
      default: 'INBOX',
    },
    // The folders to offer in the ⋮ switch, as the server listed them.
    availableFolders: {
      type: Array,
      default: () => [{ key: 'INBOX', type: 'BUILT_IN' }],
    },
    // Every folder of the user's, mirrored or not: what decides whether INBOX is the
    // namespace their folders live under, so a path reads the same on every screen
    // (EXO-90839). The offered folders when not given.
    namespaceFolders: {
      type: Array,
      default: null,
    },
    // The categories offered as views in the ⋮ menu (the add-on's full set,
    // Important included — its chip is a shortcut to the same view).
    categories: {
      type: Array,
      default: () => [],
    },
    // The category the list is currently switched to, so the ⋮ menu highlights it.
    categoryViewId: {
      type: [Number, String],
      default: null,
    },
    // The counts the ⋮ menu's folders and categories show: the full-screen folder
    // column's, so the two never disagree (EXO-90881).
    folderCounts: {
      type: Object,
      default: () => ({}),
    },
    categoryUnreadCounts: {
      type: Object,
      default: () => ({}),
    },
    top: {
      type: Boolean,
      default: true,
    },
    // Whether the ⋮ menu leaves FOLDERS and CATEGORIES out: in full screen the folder
    // column beside the list holds them (EXO-90415).
    hideViews: {
      type: Boolean,
      default: false,
    },
    // Whether Synchronize is a button beside the ⋮ menu, and out of it: in full screen
    // (EXO-90624).
    syncButton: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    return {
      downloadingZip: false,
      // The selection panel's width, measured, for how many tiles go on a row.
      tilesWidth: 0,
      tilesObserver: null,
    };
  },
  computed: {
    /**
     * The Synchronize button's tooltip and accessible name: what it does, or, while a
     * synchronization runs, that one is running -- the reason it cannot be pressed.
     *
     * @returns {String} the translated label
     */
    syncTitle() {
      return this.$t(this.syncInProgress
        ? 'emailConnector.mailBox.list.drawer.sync.running'
        : 'emailConnector.mailBox.list.drawer.sync.tooltip');
    },
    // The listed rows by selection key -- the folder, and the id the row is named by in
    // it: its UID, or its local id when it is a draft, which may have no UID at all
    // (selectionKey).
    emailsMap() {
      return Object.fromEntries(this.emails.map(e => [selectionKey(e), e]));
    },
    hasSelectedEmails() {
      return this.selectedEmails.length > 0;
    },
    hasWebmailAccess() {
      return !!this.webmailUrl;
    },
    /**
     * Whether the selected messages may be acted on at all.
     *
     * Read off the SELECTED ROWS rather than off the listed folder, because this
     * toolbar is mounted in four places — the mailbox drawer's header and its title
     * icons, the reader, and the multi-select banner — and two of them are never told
     * which folder is listed. The rows always carry their own, and they are what the
     * action would be sent for.
     *
     * Any read-only row disqualifies the whole selection: a listing holds one
     * folder's rows, so in practice it is all of them or none, and the conservative
     * reading is the one that cannot offer a Trash message an action the server will
     * refuse.
     *
     * A DRAFT row disqualifies it for the same reason and a different cause
     * (EXO-90438): Drafts is a WRITABLE folder, so the read-only test above said yes
     * and the bar offered Delete, Archive and read/unread there. Delete and Archive the
     * server refuses on purpose — an unsent draft is discarded rather than filed away
     * (EmailBoxService#canMoveOutOf) — and answered with a failure count nobody showed.
     * Read/unread is withheld for a reason of its own, and it is a product decision
     * rather than the server's hand: a draft is the user's own text, stored read and
     * never announced as new mail, so there is no read state there worth pushing. (The
     * server does not refuse it: updateEmailReadStatus never consults canMoveOutOf, so
     * the push lands for a draft that HAS been uploaded, and only fails — silently, one
     * more uncounted failure — for one that has not.) What Drafts gets is Discard.
     *
     * In a mailbox somebody shared with the user, what the rights allow decides as well
     * (canMoveOutOf): absent right, absent button. Delete and Archive each also need
     * their own destination there (canDeleteSelection, canArchiveSelection).
     *
     * @returns {Boolean} true when mail may be taken out of the selected rows' folders
     */
    canMutateSelection() {
      return this.selectedEmails.every(emailId =>
        this.$emailConnectorMailBoxService.canMoveOutOf(this.emailsMap[emailId]?.folder));
    },
    /**
     * Whether the selection may be archived: every row may be taken out of its folder,
     * and in a shared mailbox its owner shares an Archive to file into (EXO-90548).
     *
     * @returns {Boolean} true when Archive may be offered
     */
    canArchiveSelection() {
      return this.hasSelectedEmails
        && this.selectedEmails.every(emailId =>
          this.$emailConnectorMailBoxService.canArchive(this.emailsMap[emailId]?.folder));
    },
    /**
     * Whether the selection may be deleted: every row may be taken out of its folder,
     * and in a shared mailbox its owner shares a Trash to file into (EXO-90548).
     *
     * @returns {Boolean} true when Delete may be offered
     */
    canDeleteSelection() {
      return this.hasSelectedEmails
        && this.selectedEmails.every(emailId =>
          this.$emailConnectorMailBoxService.canDelete(this.emailsMap[emailId]?.folder));
    },
    /**
     * Whether the selection's read state may be changed: the rows canMutateSelection
     * would take -- no read-only row, no draft -- except that it is the right to keep
     * read state that decides in a shared mailbox, not the right to take mail out of it
     * (canMarkReadIn).
     *
     * @returns {Boolean} true when read/unread may be offered
     */
    canMarkSelectionRead() {
      return this.selectedEmails.every(emailId => {
        const folder = this.emailsMap[emailId]?.folder;
        return this.$emailConnectorMailBoxService.canMarkReadIn(folder)
          && !this.$emailConnectorMailBoxService.isDraftsFolder(folder);
      });
    },
    /**
     * The selected DRAFT rows — the rows Discard acts on, resolved from the listing
     * rather than carried by the selection.
     *
     * The discard addresses a draft by its LOCAL id, which the selection does not carry
     * -- it carries a selection key (selectionKey), whose id half is derived from that
     * local id precisely because a draft may have no UID to be keyed by. So the keys are
     * matched back to their rows and the rows are what is handed on; a row without a
     * local id is not a draft and is left out.
     *
     * Rows, not ids, for the second reason too: the confirmation has to tell scheduled
     * drafts from ordinary ones, and that is a property of the row.
     *
     * @returns {Array<Object>} the selected draft rows, in listing order
     */
    selectedDraftRows() {
      const selected = new Set(this.selectedEmails);
      return this.emails.filter(email => email.draftLocalId && selected.has(selectionKey(email)));
    },
    /**
     * Whether Discard may be offered: the selection is drafts, and the listing holds a
     * discardable row for it. Read off the rows like every other answer in this bar,
     * which is mounted in four places and told the listed folder in only two.
     *
     * @returns {Boolean} true when Discard belongs on the bar
     */
    canDiscardSelection() {
      return this.hasSelectedEmails
        && this.selectedEmails.every(emailId =>
          this.$emailConnectorMailBoxService.isDraftsFolder(this.emailsMap[emailId]?.folder))
        && this.selectedDraftRows.length > 0;
    },
    /**
     * Whether the selection may be restored or permanently deleted.
     *
     * The mirror of canMutateSelection, off the same rows and with the same all-or-none
     * reading — but requiring EVERY row to be one the Trash actions apply to rather than
     * none. A selection with one non-Trash row in it must not offer to restore it: the
     * request would be answered against the Trash folder, where that row's UID names
     * some other message entirely. `every` on an empty selection is true in JavaScript,
     * so the emptiness is ruled out explicitly.
     *
     * @returns {Boolean} true when restore / delete permanently may be offered
     */
    canApplyTrashActions() {
      return this.hasSelectedEmails
        && this.selectedEmails.every(emailId =>
          this.$emailConnectorMailBoxService.hasTrashActions(this.emailsMap[emailId]?.folder));
    },
    /**
     * Whether the selection may be marked as not spam, or deleted out of the Spam
     * folder — the same every-row rule as canApplyTrashActions, for the other hidden
     * folder, and for the same reason: a Junk restore is answered against the Junk
     * folder, where a non-Junk row's UID names some other message.
     *
     * @returns {Boolean} true when "Not spam" / delete may be offered
     */
    canApplyJunkActions() {
      return this.hasSelectedEmails
        && this.selectedEmails.every(emailId =>
          this.$emailConnectorMailBoxService.hasJunkActions(this.emailsMap[emailId]?.folder));
    },
    /**
     * Whether "Not spam" may be offered on the selection: the Spam actions apply, and
     * every row is in the user's own Spam -- out of a shared mailbox's it would file
     * into another mailbox (EXO-90548).
     *
     * @returns {Boolean} true when "Not spam" may be offered
     */
    canApplyNotJunk() {
      return this.canApplyJunkActions
        && this.selectedEmails.every(emailId =>
          this.$emailConnectorMailBoxService.canRestoreFromJunk(this.emailsMap[emailId]?.folder));
    },
    /**
     * Whether the selection may be reported as spam: every selected row must be one
     * "Mark as spam" is offered on (a writable folder's, and not a draft), so the
     * request is never sent for a row the server would refuse and count as failed.
     *
     * @returns {Boolean} true when "Mark as spam" may be offered
     */
    canMarkSelectionAsJunk() {
      return this.hasSelectedEmails
        && this.selectedEmails.every(emailId =>
          this.$emailConnectorMailBoxService.canMarkAsJunk(this.emailsMap[emailId]?.folder));
    },
    /**
     * Whether the selection may be moved: every row may be taken out of its folder, and
     * there is at least one folder other than the one the rows are listed in to move
     * them into (canMoveTo; in a shared mailbox, one of that mailbox's) -- a picker with
     * nothing to pick is a button that lies. Read off the root's folder list at
     * evaluation time (see the mailbox drawer's loadEmailBox for why it lives there).
     *
     * @returns {Boolean} true when "Move to..." may be offered
     */
    canOfferMove() {
      return this.hasSelectedEmails
        && this.canMutateSelection
        && this.$emailConnectorMailBoxService.canMoveTo(this.$root.mailFolders, this.selectionFolder);
    },
    /**
     * Whether the selection may be moved now: offered (canOfferMove), and in one folder,
     * the picker moving from a single source folder (EXO-90416).
     *
     * @returns {Boolean} true when "Move to..." is enabled
     */
    canMoveSelection() {
      return this.canOfferMove && this.selectionByFolder.length === 1;
    },
    /**
     * The selection panel's actions, in the order shown: the filing ones, then the
     * destructive ones last. Each carries its icon, its short label, its full wording
     * (aria label and tooltip), whether it is in the error colour, and its action; the
     * rules deciding which apply are the toolbar's own.
     *
     * @returns {Array<Object>} the tiles
     */
    tiles() {
      const tile = (key, icon, shortKey, fullKey, action, extra = {}) => ({
        key,
        icon,
        label: this.$t(`emailConnector.mailBox.list.drawer.multiSelect.tile.${shortKey}`),
        title: this.$t(fullKey),
        action,
        danger: false,
        disabled: false,
        loading: false,
        ...extra,
      });
      const detail = 'emailConnector.mailBox.list.drawer.detail';
      return [
        this.canUpdateEmailsReadStatus(false) && tile('unread', 'fa-mail-bulk', 'markUnread', `${detail}.unread.label`, () => this.updateEmailsReadStatus(false)),
        this.canUpdateEmailsReadStatus(true) && tile('read', 'fa-envelope-open-text', 'markRead', `${detail}.read.label`, () => this.updateEmailsReadStatus(true)),
        this.canArchiveSelection && tile('archive', 'fa-archive', 'archive', `${detail}.archive.label`, () => this.archiveEmails()),
        this.canMarkSelectionAsJunk && tile('junk', 'fa-ban', 'markSpam', `${detail}.markJunk.label`, () => this.markAsJunk()),
        this.canApplyNotJunk && tile('notJunk', 'fa-check-circle', 'notSpam', `${detail}.notJunk.label`, () => this.restoreFromJunk()),
        this.canApplyTrashActions && tile('restore', 'fa-trash-restore', 'restore', `${detail}.restore.label`, () => this.restoreEmails()),
        this.canOfferMove && Object.assign(tile('move', 'fa-folder-open', 'moveTo', `${detail}.moveTo.label`, () => this.moveEmails(),
          { disabled: !this.canMoveSelection }), { title: this.moveTitle }),
        this.canDownloadSelection && tile('zip', 'fa-file-archive', 'downloadZip', 'emailConnector.mailBox.export.zip', () => this.downloadSelection(),
          { loading: this.downloadingZip }),
        this.canDiscardSelection && tile('discard', 'fa-trash', 'discard', `${detail}.discard.label`, () => this.discardDrafts(), { danger: true }),
        (this.canDeleteSelection || this.canApplyJunkActions) && tile('delete', 'fa-trash', 'delete', `${detail}.delete.label`, () => this.deleteEmails(),
          { danger: true }),
        this.canApplyTrashActions && tile('purge', 'fa-times-circle', 'purge', `${detail}.purge.label`, () => this.purgeEmails(), { danger: true }),
      ].filter(Boolean);
    },
    /**
     * The tiles' grid: one row of equal columns when the pane is wide enough, else two
     * even rows (3 + 3), else three -- never a ragged last row of one.
     *
     * @returns {Object} the inline grid style
     */
    tilesStyle() {
      const count = this.tiles.length || 1;
      const fits = columns => !this.tilesWidth || this.tilesWidth >= columns * TILE_MIN_WIDTH_PX + (columns - 1) * TILE_GAP_PX;
      let columns = count;
      if (!fits(columns)) {
        columns = Math.ceil(count / 2);
        if (!fits(columns)) {
          columns = Math.ceil(count / 3);
        }
      }
      return {
        display: 'grid',
        gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`,
        gap: `${TILE_GAP_PX}px`,
        maxWidth: `${count * TILE_MAX_WIDTH_PX}px`,
      };
    },
    /**
     * Whether the selection can be downloaded as a .zip (EXO-90845): no draft in it.
     *
     * @returns {Boolean} true when the download is offered
     */
    canDownloadSelection() {
      return canDownloadSelection(this.selectedEmails);
    },
    /**
     * The move button's label: what it does, or why it is disabled.
     *
     * @returns {String} the label
     */
    moveTitle() {
      return this.canMoveSelection
        ? this.$t('emailConnector.mailBox.list.drawer.detail.moveTo.label')
        : this.$t('emailConnector.mailBox.list.drawer.detail.moveTo.oneFolder');
    },
    /**
     * The selected UIDs grouped by the folder they are numbered in, in selection order.
     * <p>
     * A folder's listing holds one folder, and this is one group -- the listed folder,
     * as before. A list of search results holds several (the mail drawer opened on
     * them), where one number may be two messages: the selection is kept by folder and
     * UID (selectionKey), and each action is sent once per folder, with that folder, so
     * the mailbox never resolves a bare UID against the listed folder and acts on
     * another message there (EXO-90416).
     *
     * @returns {Array} [folder, ids] pairs
     */
    selectionByFolder() {
      return selectionByFolder(this.selectedEmails);
    },
    /**
     * The selected UIDs, for the actions whose folder is fixed server-side (restore
     * and purge out of the Trash, "Not spam" out of the Spam folder).
     *
     * A draft key names no UID and is dropped rather than sent as a null (EXO-90438).
     * Nothing should reach here holding one -- those three actions are offered only on
     * Trash and Spam rows, and a draft is in neither -- so this is the belt to
     * canApplyTrashActions' braces, in the one place a key is turned back into an id a
     * request is addressed by.
     *
     * @returns {Array<Number>} the UIDs
     */
    selectedIds() {
      return this.selectedEmails.map(key => parseSelectionKey(key).id).filter(id => id !== null);
    },
    /**
     * The folder the selected rows are listed in -- a listing holds one folder's rows,
     * so the first row's folder is every row's.
     *
     * @returns {String} the folder key, INBOX when unknown
     */
    selectionFolder() {
      return this.emailsMap[this.selectedEmails[0]]?.folder || 'INBOX';
    },
  },
  created() {
    this.$root.$on('open-webmail', this.openWebmail);
  },
  watch: {
    /**
     * Follows the tiles each time they appear: a selection mode entered with nothing
     * selected yet, or a selection emptied and filled again, renders them anew.
     *
     * @returns {void}
     */
    hasSelectedEmails() {
      this.$nextTick(() => this.observeTiles());
    },
  },
  mounted() {
    this.observeTiles();
  },
  beforeDestroy() {
    this.tilesObserver?.disconnect();
  },
  methods: {
    /**
     * Measures the pane the selection tiles are laid out in -- its width, not the
     * window's: the pane is a drawer or a column of the full-screen mailbox. The previous
     * observation, of tiles no longer rendered, is dropped first.
     *
     * @returns {void}
     */
    observeTiles() {
      this.tilesObserver?.disconnect();
      this.tilesObserver = null;
      const tiles = this.$refs.tiles;
      if (tiles && typeof window.ResizeObserver === 'function') {
        this.tilesObserver = new window.ResizeObserver(entries => this.tilesWidth = entries[0]?.contentRect?.width || 0);
        this.tilesObserver.observe(tiles.parentElement || tiles);
      }
    },
    /**
     * Whether a bulk read/unread is worth offering: at least one selected message
     * would actually change, and none of them is in a read-only folder.
     *
     * @param {Boolean} read the status the button would apply
     * @returns {Boolean} true when the button should be shown
     */
    canUpdateEmailsReadStatus(read) {
      if (!this.canMarkSelectionRead) {
        return false;
      }
      return this.selectedEmails.some(emailId => {
        const email = this.emailsMap[emailId];
        return email && email.read !== read;
      });
    },
    openNewEmailDrawer() {
      this.$root.$emit('open-new-email-drawer');
    },
    /**
     * Sends a bulk action once per folder of the selection (see selectionByFolder), the
     * folder last.
     *
     * @param {String} event the action's event
     * @param {Array} before the arguments that precede the ids (the read status)
     * @returns {void}
     */
    emitPerFolder(event, ...before) {
      this.selectionByFolder.forEach(([folder, ids]) => this.$root.$emit(event, ...before, ids, folder));
    },
    /**
     * Marks the whole selection read or unread. The push and its outcome belong to the
     * mailbox drawer, which holds the rows and shows the count the server answers.
     * <p>
     * The user asked for this one, so it opts into that count being shown
     * (EXO-90444). Sent folder by folder as emitPerFolder does, written out here
     * because the opt-in follows the folder, which emitPerFolder puts last.
     *
     * @param {Boolean} read the status to apply
     * @returns {void}
     */
    updateEmailsReadStatus(read) {
      this.selectionByFolder.forEach(([folder, ids]) =>
        this.$root.$emit('update-email-read-status', read, ids, folder, null, { userInitiated: true }));
    },
    /**
     * Files the whole selection into the Archive. No confirmation — an archive is
     * undone by moving the messages back.
     *
     * @returns {void}
     */
    archiveEmails() {
      this.emitPerFolder('archive-email');
    },
    /**
     * Reports the whole selection as spam. No confirmation — undone from the Spam
     * listing with "Not spam".
     *
     * @returns {void}
     */
    markAsJunk() {
      this.emitPerFolder('junk-email');
    },
    /**
     * Opens the folder picker for the whole selection; the move itself is sent once
     * a folder is chosen there.
     *
     * @returns {void}
     */
    moveEmails() {
      const [[folder, ids] = []] = this.selectionByFolder;
      this.$root.$emit('open-move-to-folder-drawer', ids || [], folder || this.selectionFolder);
    },
    /**
     * Downloads the selection as a .zip, once the server checked it; a refusal, or a
     * selection past the cap the server holds, is told rather than downloaded.
     *
     * @returns {void}
     */
    downloadSelection() {
      this.downloadingZip = true;
      downloadSelectionZip(this.selectedEmails)
        .catch(error => this.$root.$emit('alert-message', this.$t(exportErrorKey(error), { 0: error?.max }), 'error'))
        .finally(() => this.downloadingZip = false);
    },
    /**
     * Puts the whole selection back into the inbox out of the Spam folder.
     *
     * @returns {void}
     */
    restoreFromJunk() {
      this.$root.$emit('not-junk-email', this.selectedIds);
    },
    /**
     * Puts the whole selection back into the inbox. No confirmation — a restore is
     * undone by deleting again.
     *
     * @returns {void}
     */
    restoreEmails() {
      this.$root.$emit('restore-email', this.selectedIds);
    },
    /**
     * Asks first, then destroys the whole selection. The confirmation is handed the
     * ids so it can say how many messages are about to go.
     *
     * @returns {void}
     */
    purgeEmails() {
      this.$root.$emit('open-purge-email-confirm-popup', this.selectedIds);
    },
    /**
     * Files the whole selection into the Trash. No confirmation: the Trash is where it
     * is taken back from, and the permanent delete is the one that asks (purgeEmails).
     * Offered twice in the bar — on an ordinary selection and on a Spam one, which is
     * the one folder whose Delete still means this reversible move.
     *
     * @returns {void}
     */
    deleteEmails() {
      this.emitPerFolder('delete-email');
    },
    /**
     * Asks first, then throws the selected drafts away. The confirmation is opened
     * from here, where the click happened, exactly as the permanent delete's is, and it
     * is handed the ROWS: it has to name how many drafts are going and hold back the
     * ones a scheduled send has frozen.
     *
     * @returns {void}
     */
    discardDrafts() {
      this.$root.$emit('open-discard-drafts-confirm-popup', this.selectedDraftRows);
    },
    /**
     * Triggers an immediate synchronization of the mailbox, exactly as the ⋮ menu's
     * entry does (guarded while one runs): the drawer turns its loading bar on and polls
     * the mailbox until the sync is over, which is what re-enables the button.
     *
     * @returns {void}
     */
    synchronize() {
      if (this.syncInProgress) {
        return;
      }
      this.$root.$emit('synchronize-in-progress');
      this.$emailConnectorMailBoxService.synchronize().then(() => {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.list.drawer.sync.success'), 'success');
      });
    },
    openWebmail() {
      this.$emailConnectorMailBoxService.broadcastAccessWebmail();
      window.open(this.webmailUrl, '_blank');
    }
  }
};
</script>