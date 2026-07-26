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
          class="d-flex align-center"
          :webmail-url="webmailUrl"
          :selected-emails="selectedEmails"
          :select-mode="selectMode"
          :current-folder="currentFolder"
          :available-folders="availableFolders"
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
        :webmail-url="webmailUrl"
        :selected-emails="selectedEmails"
        :select-mode="selectMode"
        :current-folder="currentFolder"
        :available-folders="availableFolders"
        :sync-in-progress="syncInProgress" />
    </template>
    <template v-if="hasFullAppLeft" #fullAppLeftContent>
      <categories-filter
        v-model="selectedCategoryId"
        :category-ids="emailCategoryIds"
        class="full-width border-box-sizing application-border application-border-radius py-3 px-3"
        object-type="email"
        scrollable
        hide-on-empty />
      <email-connector-mail-box-drawer-content
        :emails="emails"
        :email="email"
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
          :category-ids="emailCategoryIds"
          class="full-width border-box-sizing application-border application-border-radius py-3 px-3"
          object-type="email"
          scrollable
          hide-on-empty />
        <template v-if="hasEmails">
          <template v-if="expanded">
            <email-connector-mail-box-drawer-multi-select-email
              v-if="selectMode"
              :emails="emails"
              :selected-emails="selectedEmails" />
            <email-connector-mail-box-drawer-select-email v-else-if="selectEmailPlaceHolder" />
            <email-connector-mail-box-drawer-thread-content
              v-else
              :email="email"
              :emails="emails"
              expanded-drawer />
          </template>
          <email-connector-mail-box-drawer-content
            v-else
            :emails="emails"
            :selected-emails="selectedEmails"
            :select-mode="selectMode" 
            :indeterminate="indeterminate"
            :sync-in-progress="syncInProgress"
            :webmail-url="webmailUrl"
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
      webmailUrl: null,
      refreshInterval: null,
      activeDownload: null,
      selectedEmails: [],
      selectMode: false,
      expanded: false,
      email: null,
      selectEmailPlaceHolder: false,
      selectedCategoryId: null,
      selectedCategoryIds: [],
      emailCategoryIds: [],
      deletedEmailIds: [],
      archivedEmailIds: [],
      currentFolder: 'INBOX'
    };
  },
  created() {
    this.isRefreshing = false;
    // The add-on's own email categories are the children of the Inbox category; feeding
    // their ids to the platform filter makes it open "inside the Inbox" — showing those
    // children directly as chips, rather than an Inbox parent the user must drill into.
    this.$emailConnectorMailBoxService.getAvailableEmailCategories()
      .then(list => this.emailCategoryIds = (list || []).map(category => category.id));
    this.$root.$on('switch-folder', this.onSwitchFolder);
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
    this.$root.$on('email-categories-updated', this.onCategoriesUpdated);
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
    this.$root.$off('email-categories-updated', this.onCategoriesUpdated);
    this.$root.$off('switch-folder', this.onSwitchFolder);
  },
  computed: {
    hasEmails() {
      return this.emails?.length > 0;
    },
    // INBOX plus any of SENT/ARCHIVE that actually hold mail, for the ⋮ folder switch.
    availableFolders() {
      const counts = this.emailBox?.folderCounts || {};
      return ['INBOX', 'SENT', 'ARCHIVE'].filter(folder => folder === 'INBOX' || counts[folder] > 0);
    },
    syncBlocked() {
      return this.emailBox?.emailSyncStatus === 'BLOCKED';
    },
    title() {
      if (!this.selectMode) {
        const base = this.$t('emailConnector.mailBox.list.drawer.title');
        if (this.currentFolder === 'SENT') {
          return `${base} · ${this.$t('emailConnector.mailBox.list.drawer.folder.sent')}`;
        }
        if (this.currentFolder === 'ARCHIVE') {
          return `${base} · ${this.$t('emailConnector.mailBox.list.drawer.folder.archive')}`;
        }
        return base;
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
      if (this.selectedCategoryIds.length > 0) {
        emails = emails.filter(e => this.selectedCategoryIds.some(id => e.categoryIds.includes(id)));
      }
      return emails;
    }
  },
  watch: {
    async selectedCategoryId(val) {
      this.cancelSelectMode();
      this.selectedCategoryIds = val && await this.$emailConnectorMailBoxService.getSubcategoryIds(val) || [];
    },
    emails() {
      if (this.email && !this.emails.some(e => e.mailRemoteId === this.email.mailRemoteId)) {
        this.selectEmailPlaceHolder = true;
      }
    },
    selectEmailPlaceHolder() {
      if (this.selectEmailPlaceHolder) {
        this.$root.$emit('set-opened', null);
      }
    },
    selectMode() {
      if (this.selectMode) {
        this.$root.$emit('set-opened', null);
      }
      else if (!this.selectEmailPlaceHolder) {
        this.$root.$emit('set-opened', this.email.mailRemoteId);
      }
    }
  },
  methods: {
    async open(loading) {
      if (loading) {
        this.syncInProgress = true;
        await this.$nextTick();
      }
      // Always (re)open on the inbox.
      this.currentFolder = 'INBOX';
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
      return this.expanded && (!this.email || emails.includes(this.email.mailRemoteId));
    },
    close() {
      this.stopAutoRefresh();
      document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
      this.cancelSelectMode();
      this.selectEmailPlaceHolder = false;
      this.email = null;
      this.emailBoxDrawer = false;
      this.selectedCategoryId = null;
      this.selectedCategoryIds = [];
      this.deletedEmailIds = [];
      this.archivedEmailIds = [];
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
      this.emailBox = await this.$emailConnectorMailBoxService.getEmailBox(this.currentFolder);
      this.emails = this.emailBox.emails || [];
      this.syncInProgress = !this.emailBox.emailSyncStatus || this.emailBox.emailSyncStatus === 'IN_PROGRESS';
      this.webmailUrl = this.emailBox.webmailUrl;
      this.$root.$emit('refresh-emails', this.emails);
      if (!this.syncInProgress) {
        this.stopAutoRefresh();
        this.$root.$emit('synchronize-finished');
      }
    },
    // Switch the listed folder (Inbox / Sent / Archive) from the ⋮ menu and reload.
    // A category was assigned/removed from the detail view; patch the matching
    // emails the main list holds so the categories filter reflects it live.
    onCategoriesUpdated({ mailRemoteIds, categoryId, assign }) {
      const targetIds = new Set(mailRemoteIds || []);
      (this.emailBox?.emails || []).forEach(email => {
        if (!targetIds.has(email.mailRemoteId)) {
          return;
        }
        const current = email.categoryIds || [];
        this.$set(email, 'categoryIds', assign
          ? Array.from(new Set([...current, categoryId]))
          : current.filter(id => id !== categoryId));
      });
    },
    onSwitchFolder(folder) {
      if (folder === this.currentFolder) {
        return;
      }
      this.currentFolder = folder;
      this.cancelSelectMode();
      this.loading = true;
      this.loadEmailBox().finally(() => this.loading = false);
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