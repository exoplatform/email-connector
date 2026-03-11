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
    <email-box-sync-loader
      v-if="syncInProgress"
      :label="$t('emailConnector.mailBox.list.drawer.sync.inProgress.tooltip')"
      loader-class="me-2" />
    <v-btn
      v-else
      :title="$t('emailConnector.mailBox.list.drawer.sync.tooltip')"
      @click="synchronize()"
      icon>
      <v-icon size="20" class="icon-default-color">fa-sync-alt</v-icon>
    </v-btn>
    <v-btn
      :title="$t('emailConnector.mailBox.list.drawer.newEmail.button.title')"
      @click="openNewEmailDrawer()"
      icon>
      <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
    </v-btn>
  </div>
  <div v-else>
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
      v-if="hasSelectedEmails()"
      :title="$t('emailConnector.mailBox.list.drawer.detail.archive.label')"
      @click="archiveEmails()"
      icon>
      <v-icon size="20" class="icon-default-color">fa-archive</v-icon>
    </v-btn>
    <v-btn
      v-if="hasSelectedEmails()"
      :title="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
      @click="deleteEmails()"
      icon>
      <v-icon size="20" class="error--text">fa-trash</v-icon>
    </v-btn>
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
  },
  computed: {
    emailsMap() {
      return Object.fromEntries(this.emails.map(e => [e.mailRemoteId, e]));
    }
  },
  methods: {
    canUpdateEmailsReadStatus(read) {
      return this.selectedEmails.some(emailId => {
        const email = this.emailsMap[emailId];
        return email && email.read !== read;
      });
    },
    openNewEmailDrawer() {
      this.$root.$emit('open-new-email-drawer');
    },
    synchronize() {
      this.$root.$emit('synchronize-emails');
    },
    updateEmailsReadStatus(read) {
      this.$root.$emit('update-email-read-status', { read: read });
    },
    archiveEmails() {
      this.$root.$emit('archive-email');
    },
    deleteEmails() {
      this.$root.$emit('delete-email'); 
    },
    hasSelectedEmails() {
      return this.selectedEmails.length > 0;
    }
  }
};
</script>