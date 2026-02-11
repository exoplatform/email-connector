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
  <exo-drawer
    id="emailBoxDrawer"
    ref="emailBoxDrawer"
    v-model="emailBoxDrawer"
    right
    :loading="loading"
    :confirm-close="activeDownload"
    :go-back-button="selectMode"
    :confirm-close-labels="{
      title: $t('emailConnector.mailBox.attachment.download.confirmAbort.title'),
      message: $t('emailConnector.mailBox.attachment.download.confirmAbort.message'),
      ok: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.yes'),
      cancel: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.no')
    }"
    @go-back="cancelSelectMode"
    @confirm-close="onAbortDownloadConfirmed"
    @closed="close"
    style="outline: none;"
    class="no-box-shadow">
    <template #title>
      <div :class="{ 'd-flex align-center': selectMode }">
        <span :class="{ 'text-body': selectMode }">
          {{ title }}
        </span>
      </div>
    </template>
    <template #titleIcons>
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
          :title="$t('emailConnector.mailBox.list.drawer.newEmail.label')"
          @click="openNewEmailDrawer()"
          icon>
          <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
        </v-btn>
      </div>
      <div class="me-3" v-else>
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
          :title="$t('emailConnector.mailBox.list.drawer.detail.archive.label')"
          @click="archiveEmails()"
          icon>
          <v-icon size="20" class="icon-default-color">fa-archive</v-icon>
        </v-btn>
        <v-btn
          :title="$t('emailConnector.mailBox.list.drawer.detail.delete.label')"
          @click="deleteEmails()"
          icon>
          <v-icon size="20" class="error--text">fa-trash</v-icon>
        </v-btn>
      </div>
    </template>
    <template v-if="emailBoxDrawer && !loading" #content>
      <div
        class="fill-height overflow-y-auto specific-scrollbar">
        <v-list-item v-if="syncBlocked" class="full-height align-center">
          <v-list-item-content>
            <v-icon
              size="60"
              class="orange--text text--darken-2">
              fas fa-exclamation-triangle
            </v-icon>
            <v-list-item-title class="text-wrap mt-5 mb-0">
              {{ $t('emailConnector.mailBox.list.drawer.sync.blocked.reconnect') }}
            </v-list-item-title>
            <div class="mt-8">
              <v-btn
                @click="checkSetting"
                class="btn btn-primary body-2">
                {{ $t('emailConnector.mailBox.list.drawer.sync.blocked.checkSetting') }}
              </v-btn>
            </div>
          </v-list-item-content>
        </v-list-item>
        <template v-else>
          <v-checkbox
            class="ps-4 my-2 pt-0"
            v-if="selectMode"
            :indeterminate="indeterminate"
            color="#707070"
            hide-details
            :label="$t('emailConnector.mailBox.list.drawer.selectAll')"
            v-model="selectedAll"
            @click.stop />
          <email-connector-mail-box-drawer-list
            v-if="hasEmails"
            :emails="emails" 
            :select-mode="selectMode" /> 
          <v-list-item v-else class="full-height align-center">
            <v-list-item-content>
              <v-icon
                size="60"
                class="tertiary--text">
                far fa-envelope
              </v-icon>
              <v-list-item-title class="text-wrap mt-5">
                {{ $t('emailConnector.mailBox.list.drawer.noEmail') }}
              </v-list-item-title>
            </v-list-item-content>
          </v-list-item>
        </template>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      emailBoxDrawer: false,
      emailBox: null,
      emails: [],
      loading: false,
      syncInProgress: false,
      refreshInterval: null,
      activeDownload: null,
      selectedEmails: [],
      selectMode: false,
    };
  },
  created() {
    this.isRefreshing = false;
    this.$root.$on('open-mail-box-drawer', (loading) => {
      this.open(loading); 
    });
    this.$root.$on('update-email-read-status', ({ read, mailRemoteId }) => {
      this.updateEmailsReadStatus(read, mailRemoteId);
    });
    this.$root.$on('delete-email', ({ emailId }) => {
      this.deleteEmails(emailId);
    });
    this.$root.$on('archive-email', ({ emailId }) => {
      this.archiveEmails(emailId);
    });
    this.$root.$on('attachment-download-started', (payload) => {
      this.activeDownload = payload;
    });
    this.$root.$on('attachment-download-finished', () => {
      this.activeDownload = null;
    });
    this.$root.$on('select-email', ({ emailId, selected }) => {
      this.selectMode = true;
      if (selected) {
        if (!this.selectedEmails.includes(emailId)) {
          this.selectedEmails.push(emailId);
        }
      }
      else {
        this.selectedEmails = this.selectedEmails.filter(id => id !== emailId);
      }
    });
  },
  computed: {
    hasEmails() {
      return this.emails?.length > 0;
    },
    syncBlocked() {
      return this.emailBox?.emailSyncStatus === 'BLOCKED';
    },
    title() {
      if (!this.selectMode) {
        return this.$t('emailConnector.mailBox.list.drawer.title');
      }
      return `${this.selectedEmails.length} ${this.selectedEmails.length === 1 ? 
        this.$t('emailConnector.mailBox.list.drawer.emailSelected') : 
        this.$t('emailConnector.mailBox.list.drawer.emailsSelected')}`;
    },
    indeterminate() {
      return this.selectedEmails.length > 0 && this.selectedEmails.length < this.emails.length; 
    },
    selectedAll: {
      get() {
        return this.emails.length > 0 && this.selectedEmails.length === this.emails.length;
      },
      set(value) {
        this.onSelectAllChange(value);
      }
    }
  },
  methods: {
    async open(loading) {
      if (loading) {
        this.syncInProgress = true;
        await this.$nextTick();
      }
      this.loading = true;
      this.$refs.emailBoxDrawer.open();
      await this.loadEmailBox();
      this.loading = false;
      if (this.syncInProgress) {
        this.startAutoRefresh();
      }
    },
    openNewEmailDrawer() {
      this.$root.$emit('open-new-email-drawer');
    },
    onAbortDownloadConfirmed() {
      this.$root.$emit('abort-download-attachment', this.activeDownload.mailRemoteId, this.activeDownload.attachmentRemoteId, this.activeDownload.abortController);
      this.close();
    },
    close() {
      this.stopAutoRefresh();
      document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
      this.cancelSelectMode();
      this.$refs.emailBoxDrawer.close();
    },
    checkSetting() {
      this.$root.$emit('open-user-setting-drawer');
    },
    synchronize() {
      this.syncInProgress = true;
      this.$emailConnectorMailBoxService.synchronize().then(() =>
      {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.list.drawer.sync.success'), 'success');
      });
      this.startAutoRefresh();
    },
    updateEmailsReadStatus(read, mailRemoteId = null) {
      const remoteEmailIdsSource = mailRemoteId ? [mailRemoteId] : this.selectedEmails;
      const remoteEmailIdsToUpdate = remoteEmailIdsSource.filter(id => {
        const email = this.emails.find(e => e.mailRemoteId === id);
        if (email && email.read !== read) {
          this.$set(email, 'read', read);
          return true;
        }
        return false;
      });
      if (!mailRemoteId) {
        this.cancelSelectMode();
      }
      if (remoteEmailIdsToUpdate.length > 0) {
        this.$emailConnectorMailBoxService.updateEmailsReadStatus(
          remoteEmailIdsToUpdate,
          read
        );
      }
    },
    canUpdateEmailsReadStatus(read) {
      return this.selectedEmails.some(emailId => {
        const email = this.emails.find(e => e.mailRemoteId === emailId);
        return email && email.read !== read;
      });
    },
    deleteEmails(emailId = null) {
      const emailIdsToDelete = emailId ? [emailId] : this.selectedEmails;
      this.emails = this.emails.filter(
        e => !emailIdsToDelete.includes(e.mailRemoteId)
      );
      if (!emailId) {
        this.cancelSelectMode();
      }
      if (emailIdsToDelete.length > 0) {
        this.$emailConnectorMailBoxService.deleteEmails(emailIdsToDelete)
          .then((deleteResult) => {
            if ((deleteResult.failedDeletions ?? 0) > 0) {
              const alertMessage = this.$t(deleteResult.failedDeletions === 1 ? 'emailConnector.mailBox.list.drawer.delete.email.error' : 'emailConnector.mailBox.list.drawer.delete.emails.error', {
                0: deleteResult.failedDeletions,
              });
              document.dispatchEvent(new CustomEvent('alert-message', {detail: {
                alertType: 'error',
                alertMessage: alertMessage,
                alertLinkText: this.$t('emailConnector.mailBox.list.drawer.see.label'),
                alertLinkCallback: () => this.$emailConnectorCommonService.openEmailBox(),
              }}));
            }
          })
          .catch(() => { 
            const alertMessage = this.$t(emailIdsToDelete.length === 1 ? 'emailConnector.mailBox.list.drawer.delete.email.error' : 'emailConnector.mailBox.list.drawer.delete.emails.error', {
              0: emailIdsToDelete.length,
            });
            document.dispatchEvent(new CustomEvent('alert-message', {detail: {
              alertType: 'error',
              alertMessage: alertMessage,
              alertLinkText: this.$t('emailConnector.mailBox.list.drawer.see.label'),
              alertLinkCallback: () => this.$emailConnectorCommonService.openEmailBox(),
            }}));
          });
      }
    },
    archiveEmails(emailId = null) {
      const emailIdsToArchive = emailId ? [emailId] : this.selectedEmails;
      this.emails = this.emails.filter(
        e => !emailIdsToArchive.includes(e.mailRemoteId)
      );
      if (!emailId) {
        this.cancelSelectMode();
      }
      if (emailIdsToArchive.length > 0) {
        this.$emailConnectorMailBoxService.archiveEmails(emailIdsToArchive)
          .then(archiveResult => {
            if ((archiveResult.failedArchives ?? 0) > 0) {
              const alertMessage = this.$t(archiveResult.failedArchives === 1 ? 'emailConnector.mailBox.list.drawer.archive.email.error' : 'emailConnector.mailBox.list.drawer.archive.emails.error', {
                0: archiveResult.failedArchives,
              });
              document.dispatchEvent(new CustomEvent('alert-message', {detail: {
                alertType: 'error',
                alertMessage: alertMessage,
                alertLinkText: this.$t('emailConnector.mailBox.list.drawer.see.label'),
                alertLinkCallback: () => this.$emailConnectorCommonService.openEmailBox(),
              }}));
            }
          })
          .catch(() => { 
            const alertMessage = this.$t(emailIdsToArchive.length === 1 ? 'emailConnector.mailBox.list.drawer.archive.email.error' : 'emailConnector.mailBox.list.drawer.archive.emails.error', {
              0: emailIdsToArchive.length,
            });
            document.dispatchEvent(new CustomEvent('alert-message', {detail: {
              alertType: 'error',
              alertMessage: alertMessage,
              alertLinkText: this.$t('emailConnector.mailBox.list.drawer.see.label'),
              alertLinkCallback: () => this.$emailConnectorCommonService.openEmailBox(),
            }}));
          });
      }
    },
    async loadEmailBox() {
      this.emailBox = await this.$emailConnectorMailBoxService.getEmailBox();
      this.emails = this.emailBox.emails || [];
      this.syncInProgress = !this.emailBox.emailSyncStatus || this.emailBox.emailSyncStatus === 'IN_PROGRESS';
      if (!this.syncInProgress) {
        this.stopAutoRefresh();
      }
    },
    startAutoRefresh() {
      if (this.refreshInterval) {
        return;
      }
      this.isRefreshing = false;
      this.refreshInterval = setInterval(async () => {
        if (this.isRefreshing) {
          return;
        }
        this.isRefreshing = true;
        try {
          await this.loadEmailBox();
        } finally {
          this.isRefreshing = false;
        }
      }, 2000); 
    },
    stopAutoRefresh() {
      if (this.refreshInterval) {
        clearInterval(this.refreshInterval);
        this.refreshInterval = null;
      }
    },
    cancelSelectMode() {
      this.selectedEmails = [];
      this.selectMode = false;
      this.$root.$emit('unselect-all-emails');
    },
    onSelectAllChange(value) {
      if (!value) {
        this.$root.$emit('unselect-all-emails');
        this.selectedEmails = [];
      } else {
        this.$root.$emit('select-all-emails');
      }
    }
  }
};
</script>