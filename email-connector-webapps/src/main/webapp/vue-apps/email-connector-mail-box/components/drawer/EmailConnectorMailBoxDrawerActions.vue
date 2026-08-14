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
        v-if="canMutateSelection"
        :title="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
        @click="deleteEmails()"
        icon>
        <v-icon size="20" class="error--text">fa-trash</v-icon>
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
        v-if="canMutateSelection"
        @click="deleteEmails()"
        outlined
        class="btn error font-weight-bold">
        <v-icon size="16" class="error--text pe-3">fa-trash</v-icon>
        <span class="error--text"> {{ $t('emailConnector.mailBox.list.drawer.detail.delete.label') }} </span>
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
    // Folders that have mail, so the ⋮ switch hides empty ones.
    availableFolders: {
      type: Array,
      default: () => ['INBOX'],
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
     * reading is the one that cannot fire an inbox-keyed delete on a Trash message.
     *
     * @returns {Boolean} true when archive/delete/read-status may be offered
     */
    canMutateSelection() {
      return !this.selectedEmails.some(emailId =>
        this.$emailConnectorMailBoxService.isReadOnlyFolder(this.emailsMap[emailId]?.folder));
    },
  },
  created() {
    this.$root.$on('open-webmail', this.openWebmail);
  },
  methods: {
    /**
     * Whether a bulk read/unread is worth offering: at least one selected message
     * would actually change, and none of them is in a read-only folder (read-status
     * writes are inbox-scoped server-side, so offering it there would do nothing).
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