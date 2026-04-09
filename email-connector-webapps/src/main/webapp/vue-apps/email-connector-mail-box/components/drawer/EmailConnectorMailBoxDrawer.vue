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
    allow-expand
    @expand-updated="updateExpand"
    :loading="loading"
    :confirm-close="activeDownload"
    :go-back-button="canGoBack"
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
      <div v-if="!hasFullAppLeft" :class="{ 'd-flex align-center': selectMode }">
        <span :class="{ 'text-body': selectMode }">
          {{ title }}
        </span>
      </div>
      <div v-else>
        <span></span>
      </div>
    </template>
    <template v-if="hasFullAppLeft" #fullAppLeftTitle>
      <v-btn
        v-if="selectMode"
        icon
        @click="cancelSelectMode">
        <v-icon size="20">
          {{ $vuetify.rtl && 'fa fa-arrow-right' || 'fa fa-arrow-left' }}
        </v-icon>
      </v-btn>
      <div class="d-flex align-center justify-space-between width-full">
        <span :class="{ 'text-body': selectMode }">
          {{ title }}
        </span>
        <email-connector-mail-box-drawer-actions
          :emails="emails"
          :selected-emails="selectedEmails"
          :select-mode="selectMode" 
          :sync-in-progress="syncInProgress" />
      </div>
    </template>
    <template #titleIcons>
      <div v-if="hasFullAppLeft">
        <email-connector-mail-box-drawer-list-item-detail-actions
          v-if="email && !selectEmailPlaceHolder"
          :email="email" />
      </div> 
      <email-connector-mail-box-drawer-actions
        v-else-if="!syncBlocked"
        class="d-flex align-center"
        :emails="emails"
        :selected-emails="selectedEmails"
        :select-mode="selectMode"
        :sync-in-progress="syncInProgress" />
    </template>
    <template v-if="hasFullAppLeft" #fullAppLeftContent>
      <categories-filter
        v-model="selectedCategoryId"
        class="full-width border-box-sizing application-border application-border-radius py-3 pe-4 ps-7"
        object-type="email"
        hide-on-empty />
      <email-connector-mail-box-drawer-content
        :emails="emails"
        :selected-emails="selectedEmails"
        :select-mode="selectMode"
        :indeterminate="indeterminate"
        expanded
        @update:selected-emails="selectedEmails = $event" />
    </template>
    <template v-if="emailBoxDrawer && !loading" #content>
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
        <categories-filter
          v-if="!expanded"
          v-model="selectedCategoryId"
          class="full-width border-box-sizing application-border application-border-radius py-3 pe-4 ps-7"
          object-type="email"
          hide-on-empty />
        <template v-if="hasEmails">
          <template v-if="expanded">
            <email-connector-mail-box-drawer-multi-select-email
              v-if="selectMode"
              :emails="emails"
              :selected-emails="selectedEmails" />
            <email-connector-mail-box-drawer-select-email v-else-if="selectEmailPlaceHolder" />
            <email-connector-mail-box-drawer-list-item-detail-content
              v-else
              :email="email"
              expanded-drawer />
          </template>
          <email-connector-mail-box-drawer-content
            v-else
            :emails="emails"
            :selected-emails="selectedEmails"
            :select-mode="selectMode" 
            :indeterminate="indeterminate"
            :sync-in-progress="syncInProgress"
            @update:selected-emails="selectedEmails = $event" />
        </template>
        <email-connector-mail-box-drawer-no-email v-else />
      </template>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      emailBoxDrawer: false,
      emailBox: null,
      loading: false,
      syncInProgress: false,
      refreshInterval: null,
      activeDownload: null,
      selectedEmails: [],
      selectMode: false,
      expanded: false,
      email: null,
      selectEmailPlaceHolder: false,
      selectedCategoryId: this.$root.selectedCategoryId,
      deletedEmailIds: [],
      archivedEmailIds: []
    };
  },
  created() {
    this.isRefreshing = false;
    this.onOpenEmailDetailContent = (mailRemoteId) => {
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return; 
      }
      this.openEmailDetailContent(mailRemoteId);
    };
    this.onUpdateEmailReadStatus = (read, emails) => {
      this.updateEmailsReadStatus(read, emails);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return; 
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.onDeleteEmail = (emails) => {
      this.deleteEmails(emails);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return; 
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.onArchiveEmail = (emails) => {
      this.archiveEmails(emails);
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return; 
      }
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.$root.$on('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$on('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$on('delete-email', this.onDeleteEmail);
    this.$root.$on('archive-email', this.onArchiveEmail);
    this.$root.$on('open-email-detail-drawer', () => {
      this.email = null;
    });
    this.$root.$on('open-mail-box-drawer', (loading) => {
      this.open(loading); 
    });
    this.$root.$on('attachment-download-started', (payload) => {
      this.activeDownload = payload;
    });
    this.$root.$on('attachment-download-finished', () => {
      this.activeDownload = null;
    });
    this.$root.$on('select-email', ({ emailId, selected }) => {
      if (!this.emailBoxDrawer || this.$root.isDetailDrawerActive) {
        return;
      }
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
    this.$root.$on('synchronize-in-progress', () => {
      this.syncInProgress = true;
      this.startAutoRefresh();
    });
  },
  beforeDestroy() {
    this.$root.$off('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$off('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$off('delete-email', this.onDeleteEmail);
    this.$root.$off('archive-email', this.onArchiveEmail);
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
    hasFullAppLeft() {
      return this.expanded && (this.hasEmails || this.selectedCategoryId) && !this.syncBlocked;
    },
    canGoBack() {
      return this.selectMode && !this.expanded;
    },
    emails() {
      let emails = this.emailBox?.emails || [];
      emails = emails.filter(e => !this.deletedEmailIds.includes(e.mailRemoteId));
      emails = emails.filter(e => !this.archivedEmailIds.includes(e.mailRemoteId));
      if (this.selectedCategoryId) {
        emails = emails.filter(e => e.categoryIds.includes(this.selectedCategoryId));
      }
      return emails;
    }
  },
  watch: {
    selectedCategoryId(val) {
      this.$root.selectedCategoryId = val;
      if (this.email && !this.emails.some(e => e.mailRemoteId === this.email.mailRemoteId)) {
        this.selectEmailPlaceHolder = true;
      }
      this.cancelSelectMode();
    }
  },
  methods: {
    async open(loading) {
      if (loading) {
        this.syncInProgress = true;
        await this.$nextTick();
      }
      this.loading = true;
      this.emailBoxDrawer = true;
      await this.loadEmailBox();
      this.loading = false;
      if (this.syncInProgress) {
        this.startAutoRefresh();
      }
    },
    openEmailDetailContent(mailRemoteId) {
      this.loading = true;
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId).then((email) => {
        this.updateEmailsReadStatus(true, [mailRemoteId]);
        this.email = email;
        this.selectEmailPlaceHolder = false;
      }).finally(() => {
        this.loading = false;
      });
    },
    onAbortDownloadConfirmed() {
      this.$root.$emit('abort-download-attachment', this.activeDownload.mailRemoteId, this.activeDownload.attachmentRemoteId, this.activeDownload.abortController);
      this.close();
    },
    canDisplaySelectEmailPlaceHolder(emails) {
      return this.expanded && (this.selectMode || this.email && emails.includes(this.email.mailRemoteId));
    },
    close() {
      this.stopAutoRefresh();
      document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
      this.cancelSelectMode();
      this.selectEmailPlaceHolder = false;
      this.email = null;
      this.emailBoxDrawer = false;
      this.selectedCategoryId = null;
      this.deletedEmailIds.clear();
      this.archivedEmailIds.clear();
    },
    checkSetting() {
      this.$root.$emit('open-user-setting-drawer');
    },
    updateEmailsReadStatus(read, emailIds = []) {
      const emailIdsToUpdate = emailIds.filter(id => {
        const email = this.emails.find(e => e.mailRemoteId === id);
        if (email && email.read !== read) {
          this.$set(email, 'read', read);
          return true;
        }
        return false;
      });
      if (emailIdsToUpdate.length > 0) {
        this.$emailConnectorMailBoxService.updateEmailsReadStatus(
          emailIdsToUpdate,
          read
        );
      }
    },
    deleteEmails(emailIdsToDelete = []) {
      this.deletedEmailIds.push(...emailIdsToDelete);
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
    archiveEmails(emailIdsToArchive = []) {
      this.archivedEmailIds.push(...emailIdsToArchive);
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
      this.$root.$emit('refresh-emails', this.emails);
      if (!this.syncInProgress) {
        this.stopAutoRefresh();
        this.$root.$emit('synchronize-finished');
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
      this.selectMode = false;
      this.selectedEmails = [];
    },
    updateExpand(expanded) {
      window.setTimeout(() => this.expanded = expanded, 200);
      if (expanded) {
        if (!this.email) {
          this.selectEmailPlaceHolder = true;
        }
      }
    },
  }
};
</script>