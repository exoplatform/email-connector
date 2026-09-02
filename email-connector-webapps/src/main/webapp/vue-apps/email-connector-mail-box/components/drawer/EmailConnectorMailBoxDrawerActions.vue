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
  <div class="align-self-center" v-if="!selectMode">
    <v-tooltip v-if="syncInProgress" bottom>
      <template #activator="{ on, attrs }">
        <div
          v-on="on"
          v-bind="attrs"
          class="d-inline-flex align-center">
          <email-box-sync-loader loader-class="me-2" />
        </div>
      </template>
      <span>{{ $t('emailConnector.mailBox.list.drawer.sync.inProgress.tooltip') }}</span>
    </v-tooltip>
    <v-btn
      :title="$t('emailConnector.mailBox.list.drawer.newEmail.button.title')"
      @click="openNewEmailDrawer()"
      icon>
      <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
    </v-btn>
    <email-connector-mail-box-drawer-action-menu
      :current-folder="currentFolder"
      :available-folders="availableFolders"
      :categories="categories"
      :category-view-id="categoryViewId"
      :sync-in-progress="syncInProgress"
      :has-webmail-access="hasWebmailAccess" />
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
        v-if="canMutateSelection"
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
      <v-btn
        v-if="canMoveSelection"
        :title="$t('emailConnector.mailBox.list.drawer.detail.moveTo.label')"
        @click="moveEmails()"
        icon>
        <v-icon size="20" class="icon-default-color">fa-folder-open</v-icon>
      </v-btn>
      <v-btn
        v-if="canMutateSelection"
        :title="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
        @click="deleteEmails()"
        icon>
        <v-icon size="20" class="error--text">fa-trash</v-icon>
      </v-btn>
      <v-btn
        v-if="canApplyJunkActions"
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
    <template v-else>
      <v-btn
        v-if="canUpdateEmailsReadStatus(false)"
        @click="updateEmailsReadStatus(false)"
        outlined
        class="btn btn-primary font-weight-bold">
        <v-icon
          size="16"
          class="pe-3"
          color="primary">
          fa-mail-bulk
        </v-icon>
        {{ $t('emailConnector.mailBox.list.drawer.detail.unread.label') }}
      </v-btn>
      <v-btn
        v-if="canUpdateEmailsReadStatus(true)"
        @click="updateEmailsReadStatus(true)"
        outlined
        class="btn btn-primary font-weight-bold">
        <v-icon
          size="16"
          class="pe-3"
          color="primary">
          fa-envelope-open-text
        </v-icon>
        {{ $t('emailConnector.mailBox.list.drawer.detail.read.label') }}
      </v-btn>
      <v-btn
        v-if="canMutateSelection"
        @click="archiveEmails()"
        outlined
        class="btn btn-primary font-weight-bold">
        <v-icon
          size="16"
          class="pe-3"
          color="primary">
          fa-archive
        </v-icon>
        {{ $t('emailConnector.mailBox.list.drawer.detail.archive.label') }}
      </v-btn>
      <v-btn
        v-if="canMarkSelectionAsJunk"
        @click="markAsJunk()"
        outlined
        class="btn btn-primary font-weight-bold">
        <v-icon
          size="16"
          class="pe-3"
          color="primary">
          fa-ban
        </v-icon>
        {{ $t('emailConnector.mailBox.list.drawer.detail.markJunk.label') }}
      </v-btn>
      <v-btn
        v-if="canMoveSelection"
        @click="moveEmails()"
        outlined
        class="btn btn-primary font-weight-bold">
        <v-icon
          size="16"
          class="pe-3"
          color="primary">
          fa-folder-open
        </v-icon>
        {{ $t('emailConnector.mailBox.list.drawer.detail.moveTo.label') }}
      </v-btn>
      <v-btn
        v-if="canMutateSelection"
        @click="deleteEmails()"
        outlined
        class="btn error font-weight-bold">
        <v-icon size="16" class="error--text pe-3">fa-trash</v-icon>
        <span class="error--text"> {{ $t('emailConnector.mailBox.list.drawer.detail.delete.label') }} </span>
      </v-btn>
      <v-btn
        v-if="canApplyJunkActions"
        @click="restoreFromJunk()"
        outlined
        class="btn btn-primary font-weight-bold">
        <v-icon
          size="16"
          class="pe-3"
          color="primary">
          fa-check-circle
        </v-icon>
        {{ $t('emailConnector.mailBox.list.drawer.detail.notJunk.label') }}
      </v-btn>
      <v-btn
        v-if="canApplyJunkActions"
        @click="deleteEmails()"
        outlined
        class="btn error font-weight-bold">
        <v-icon size="16" class="error--text pe-3">fa-trash</v-icon>
        <span class="error--text"> {{ $t('emailConnector.mailBox.list.drawer.detail.delete.label') }} </span>
      </v-btn>
      <v-btn
        v-if="canApplyTrashActions"
        @click="restoreEmails()"
        outlined
        class="btn btn-primary font-weight-bold">
        <v-icon
          size="16"
          class="pe-3"
          color="primary">
          fa-trash-restore
        </v-icon>
        {{ $t('emailConnector.mailBox.list.drawer.detail.restore.label') }}
      </v-btn>
      <v-btn
        v-if="canApplyTrashActions"
        @click="purgeEmails()"
        outlined
        class="btn error font-weight-bold">
        <v-icon size="16" class="error--text pe-3">fa-times-circle</v-icon>
        <span class="error--text"> {{ $t('emailConnector.mailBox.list.drawer.detail.purge.label') }} </span>
      </v-btn>
    </template>
  </div>
</template>

<script>
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
    top: {
      type: Boolean,
      default: true,
    }
  },
  computed: {
    emailsMap() {
      return Object.fromEntries(this.emails.map(e => [e.mailRemoteId, e]));
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
     * toolbar is mounted twice — once over the mailbox list, once over the reader —
     * and only one of the two is told which folder is listed. The rows always carry
     * their own, and they are what the action would be sent for.
     *
     * Any read-only row disqualifies the whole selection: a listing holds one
     * folder's rows, so in practice it is all of them or none, and the conservative
     * reading is the one that cannot offer a Trash message an action the server will
     * refuse.
     *
     * @returns {Boolean} true when archive/delete/read-status may be offered
     */
    canMutateSelection() {
      return !this.selectedEmails.some(emailId =>
        this.$emailConnectorMailBoxService.isReadOnlyFolder(this.emailsMap[emailId]?.folder));
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
     * Whether the selection may be moved into one of the user's own folders: the same
     * rows "Mark as spam" is offered on, and only when the user has at least one
     * mirrored folder other than the one the rows are listed in -- a picker with
     * nothing to pick is a button that lies.
     *
     * @returns {Boolean} true when "Move to..." may be offered
     */
    canMoveSelection() {
      return this.canMarkSelectionAsJunk
        && this.$emailConnectorMailBoxService.moveTargets(this.$root.mailFolders, this.selectionFolder).length > 0;
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
  methods: {
    /**
     * Whether a bulk read/unread is worth offering: at least one selected message
     * would actually change, and none of them is in a read-only folder.
     *
     * @param {Boolean} read the status the button would apply
     * @returns {Boolean} true when the button should be shown
     */
    canUpdateEmailsReadStatus(read) {
      if (!this.canMutateSelection) {
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
    updateEmailsReadStatus(read) {
      this.$root.$emit('update-email-read-status', read, this.selectedEmails);
    },
    archiveEmails() {
      this.$root.$emit('archive-email', this.selectedEmails);
    },
    /**
     * Reports the whole selection as spam. No confirmation — undone from the Spam
     * listing with "Not spam".
     *
     * @returns {void}
     */
    markAsJunk() {
      this.$root.$emit('junk-email', this.selectedEmails);
    },
    /**
     * Opens the folder picker for the whole selection; the move itself is sent once
     * a folder is chosen there.
     *
     * @returns {void}
     */
    moveEmails() {
      this.$root.$emit('open-move-to-folder-drawer', this.selectedEmails, this.selectionFolder);
    },
    /**
     * Puts the whole selection back into the inbox out of the Spam folder.
     *
     * @returns {void}
     */
    restoreFromJunk() {
      this.$root.$emit('not-junk-email', this.selectedEmails);
    },
    /**
     * Puts the whole selection back into the inbox. No confirmation — a restore is
     * undone by deleting again.
     *
     * @returns {void}
     */
    restoreEmails() {
      this.$root.$emit('restore-email', this.selectedEmails);
    },
    /**
     * Asks first, then destroys the whole selection. The confirmation is handed the
     * ids so it can say how many messages are about to go.
     *
     * @returns {void}
     */
    purgeEmails() {
      this.$root.$emit('open-purge-email-confirm-popup', this.selectedEmails);
    },
    deleteEmails() {
      this.$root.$emit('delete-email', this.selectedEmails); 
    },
    synchronize() {
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