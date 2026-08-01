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
    id="emailDetailDrawer"
    ref="emailDetailDrawer"
    v-model="emailDetailDrawer"
    right
    allow-expand
    @expand-updated="updateExpand"
    :loading="loading"
    go-back-button
    :confirm-close="activeDownload"
    :confirm-close-labels="{
      title: $t('emailConnector.mailBox.attachment.download.confirmAbort.title'),
      message: $t('emailConnector.mailBox.attachment.download.confirmAbort.message'),
      ok: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.yes'),
      cancel: $t('emailConnector.mailBox.attachment.download.confirmAbort.button.no')
    }"
    @confirm-close="onAbortDownloadConfirmed"
    @closed="close">
    <template #title>
      <span></span>
    </template>
    <template v-if="expanded" #fullAppLeftTitle>
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
          class="d-flex align-center"
          :emails="filteredEmails"
          :webmail-url="webmailUrl"
          :selected-emails="selectedEmails"
          :select-mode="selectMode" 
          :sync-in-progress="syncInProgress" />
      </div>
    </template>
    <template v-if="!loading" #titleIcons>
      <email-connector-mail-box-drawer-list-item-detail-actions
        v-if="email && (!expanded || !selectEmailPlaceHolder)"
        :email="email" />
    </template>
    <template v-if="expanded" #fullAppLeftContent>
      <categories-filter
        v-model="selectedCategoryId"
        class="full-width border-box-sizing application-border application-border-radius py-3 pe-4 ps-7"
        object-type="email"
        hide-on-empty />
      <email-connector-mail-box-drawer-content
        :emails="filteredEmails"
        :selected-emails="selectedEmails"
        :select-mode="selectMode"
        :email="email"
        @update:selected-emails="selectedEmails = $event"
        expanded />
    </template>
    <template v-if="emailDetailDrawer && !loading" #content>
      <email-connector-mail-box-drawer-multi-select-email
        v-if="selectMode"
        :emails="filteredEmails"
        :selected-emails="selectedEmails" />
      <template v-else>
        <email-connector-mail-box-drawer-no-email v-if="filteredEmails.length === 0" />
        <template v-else>
          <email-connector-mail-box-drawer-select-email v-if="selectEmailPlaceHolder" />
          <email-connector-mail-box-drawer-thread-content
            v-else
            :email="email"
            :emails="filteredEmails"
            :expanded-drawer="expanded" />
        </template>
      </template>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      emailDetailDrawer: false,
      loading: false,
      email: null,
      expanded: false,
      activeDownload: null,
      emails: [],
      // The list on screen is a set of search results rather than the folder's
      // cached window. Search reaches the whole mailbox, so those messages are
      // routinely outside the window and must not be reconciled against it.
      detachedFromList: false,
      selectedEmails: [],
      syncInProgress: false,
      webmailUrl: null,
      selectMode: false,
      selectEmailPlaceHolder: false,
      selectedCategoryId: null,
      selectedCategoryIds: [],
    };
  },
  created() {
    this.onOpenEmailDetailDrawer = (mailRemoteId, emails, syncInProgress, webmailUrl, detachedFromList) => {
      this.detachedFromList = !!detachedFromList;
      this.open(mailRemoteId, emails, syncInProgress, webmailUrl);
    };
    this.onCloseEmailDetailDrawer = () => {
      if (!this.expanded) {
        this.close();
      }
    };
    this.onOpenEmailDetailContent = (mailRemoteId) => {
      if (!this.emailDetailDrawer) {
        return; 
      }
      this.openEmailDetailContent(mailRemoteId);
    };
    this.onUpdateEmailReadStatus = (read, emails) => {
      if (!this.emailDetailDrawer) {
        return; 
      }
      emails.filter(id => {
        const email = this.emails.find(e => e.mailRemoteId === id);
        if (email && email.read !== read) {
          this.$set(email, 'read', read);
        }
      });
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    this.onDeleteOrArchiveEmail = (emails) => {
      if (!this.emailDetailDrawer) {
        return; 
      }
      this.refreshEmails(emails);
      this.selectEmailPlaceHolder = this.canDisplaySelectEmailPlaceHolder(emails);
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    };
    // Mirror favorite changes (and their rollback after a refused push) onto this
    // drawer's own copies: the list it was opened with — a snapshot when it
    // shows search results, which the periodic refresh never overwrites — and
    // the opened message.
    this.onApplyEmailFavoriteStatus = (favorite, mailRemoteIds = []) => {
      const ids = new Set(mailRemoteIds);
      (this.emails || []).forEach(email => {
        if ((email.folder || 'INBOX') === 'INBOX' && ids.has(email.mailRemoteId)) {
          this.$set(email, 'starred', favorite);
        }
      });
      if (this.email && (this.email.folder || 'INBOX') === 'INBOX' && ids.has(this.email.mailRemoteId)) {
        this.$set(this.email, 'starred', favorite);
      }
    };
    this.$root.$on('open-email-detail-drawer', this.onOpenEmailDetailDrawer);
    this.$root.$on('close-email-detail-drawer', this.onCloseEmailDetailDrawer);
    this.$root.$on('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$on('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$on('update-email-favorite-status', this.onApplyEmailFavoriteStatus);
    this.$root.$on('apply-email-favorite-status', this.onApplyEmailFavoriteStatus);
    this.$root.$on('delete-email', this.onDeleteOrArchiveEmail);
    this.$root.$on('archive-email', this.onDeleteOrArchiveEmail);
    this.$root.$on('attachment-download-started', (payload) => {
      this.activeDownload = payload;
    });
    this.$root.$on('attachment-download-finished', () => {
      this.activeDownload = null;
    });
    this.$root.$on('select-email', ({ emailId, selected }) => {
      if (!this.emailDetailDrawer) {
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
    });
    this.$root.$on('synchronize-finished', () => {
      this.syncInProgress = false;
    });
    this.$root.$on('refresh-emails', (emails) => {
      // The mailbox refreshes every couple of seconds while categories are still
      // landing, carrying the folder's cached window. That is not what is on screen
      // during a search: overwriting would drop every result found outside the window.
      if (this.detachedFromList) {
        return;
      }
      this.emails = emails;
    });
  },
  beforeDestroy() {
    this.$root.$off('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$off('open-email-detail-drawer', this.onOpenEmailDetailDrawer);
    this.$root.$off('close-email-detail-drawer', this.onCloseEmailDetailDrawer);
    this.$root.$off('delete-email', this.onDeleteOrArchiveEmail);
    this.$root.$off('archive-email', this.onDeleteOrArchiveEmail);
    this.$root.$off('update-email-favorite-status', this.onApplyEmailFavoriteStatus);
    this.$root.$off('apply-email-favorite-status', this.onApplyEmailFavoriteStatus);
  },
  computed: {
    title() {
      if (!this.selectMode) {
        return this.$t('emailConnector.mailBox.list.drawer.title');
      }
      return `${this.selectedEmails.length} ${this.selectedEmails.length === 1 ? 
        this.$t('emailConnector.mailBox.list.drawer.emailSelected') : 
        this.$t('emailConnector.mailBox.list.drawer.emailsSelected')}`;
    },
    filteredEmails() {
      let filteredEmails = this.emails || [];
      if (this.selectedCategoryIds.length > 0) {
        filteredEmails = filteredEmails.filter(e => this.selectedCategoryIds.some(id => e.categoryIds.includes(id)));
      }
      return filteredEmails;
    }
  },
  watch: {
    async selectedCategoryId(val) {
      this.cancelSelectMode();
      this.selectedCategoryIds = val && await this.$emailConnectorMailBoxService.getSubcategoryIds(val) || [];
    },
    filteredEmails() {
      // A message opened from outside this list -- a search hit, or one picked from
      // the global Favorites drawer -- comes from the whole mailbox, so its absence
      // here means nothing and must not send the reader back to the placeholder,
      // which is exactly what happened on the first refresh after opening one.
      if (this.detachedFromList) {
        return;
      }
      if (this.email && !this.filteredEmails.some(e => e.mailRemoteId === this.email.mailRemoteId)) {
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
    open(mailRemoteId, emails, syncInProgress, webmailUrl) {
      this.loading = true;
      this.emailDetailDrawer = true;
      this.email = null;
      this.selectEmailPlaceHolder = false;
      this.emails = emails;
      this.webmailUrl = webmailUrl;
      this.syncInProgress = syncInProgress;
      this.$root.isDetailDrawerActive = true;
      this.$root.$emit('update-email-read-status', true, [mailRemoteId]);
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId, this.folderOf(mailRemoteId)).then((email) => {
        this.email = email;
        this.selectEmailPlaceHolder = false;
      }).finally(() => {
        this.loading = false;
      });
    },
    // IMAP UIDs are per-folder, so opening a Sent/Archive message needs its folder,
    // taken from the currently-listed emails.
    folderOf(mailRemoteId) {
      const email = (this.emails || []).find(e => e.mailRemoteId === mailRemoteId);
      return email && email.folder || 'INBOX';
    },
    openEmailDetailContent(mailRemoteId) {
      this.loading = true;
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId, this.folderOf(mailRemoteId)).then((email) => {
        this.email = email;
        this.$root.$emit('update-email-read-status', true, [mailRemoteId]);
        this.selectEmailPlaceHolder = false;
      }).finally(() => {
        this.loading = false;
      });
    },
    refreshEmails(emailIds = []) {
      this.emails = this.emails.filter(
        e => !emailIds.includes(e.mailRemoteId)
      );
    },
    cancelSelectMode() {
      this.selectMode = false;
      this.selectedEmails = [];
    },
    canDisplaySelectEmailPlaceHolder(emails) {
      return this.expanded && (!this.email || emails.includes(this.email.mailRemoteId));
    },
    onAbortDownloadConfirmed() {
      this.$root.$emit('abort-download-attachment', this.activeDownload.mailRemoteId, this.activeDownload.attachmentRemoteId, this.activeDownload.abortController);
      this.close();
    },
    close() {
      this.detachedFromList = false;
      this.emailDetailDrawer = false;
      this.cancelSelectMode();
      this.selectEmailPlaceHolder = false;
      this.email = null;
      this.$root.isDetailDrawerActive = false;
      this.$root.$emit('email-detail-drawer-closed');
      this.selectedCategoryId = null;
      this.selectedCategoryIds = [];
    },
    updateExpand(expanded) {
      window.setTimeout(() => this.expanded = expanded, 200);
      if (!expanded && (this.selectEmailPlaceHolder || this.selectMode)) {
        this.close();
      }
    },
  }
};
</script>