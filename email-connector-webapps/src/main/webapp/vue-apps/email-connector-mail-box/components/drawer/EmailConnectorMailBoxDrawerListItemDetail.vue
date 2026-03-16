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
          :emails="emails"
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
      <email-connector-mail-box-drawer-content
        :emails="emails"
        :selected-emails="selectedEmails"
        :select-mode="selectMode"
        @update:selected-emails="selectedEmails = $event"
        expanded />
    </template>
    <template v-if="emailDetailDrawer && !loading" #content>
      <email-connector-mail-box-drawer-select-email v-if="expanded && selectEmailPlaceHolder" />
      <email-connector-mail-box-drawer-list-item-detail-content
        v-else
        :email="email"
        :expanded-drawer="expanded" />
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
      selectedEmails: [],
      syncInProgress: false,
      selectMode: false,
      selectEmailPlaceHolder: false,
    };
  },
  created() {
    this.onOpenEmailDetailDrawer = (mailRemoteId, emails, syncInProgress) => {
      this.open(mailRemoteId, emails, syncInProgress);
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
      if (this.selectMode) {
        this.cancelSelectMode();
      }
      if (this.expanded) {
        this.selectEmailPlaceHolder = true;
      }
    };
    this.onDeleteEmail = (emails) => {
      if (!this.emailDetailDrawer) {
        return; 
      }
      this.refreshEmails(emails);
      if (this.expanded && this.email && emails.includes(this.email.mailRemoteId)) {
        this.initializeEmail();
      }
    };
    this.onArchiveEmail = (emails) => {
      if (!this.emailDetailDrawer) {
        return; 
      }
      this.refreshEmails(emails);
      if (this.expanded && this.email && emails.includes(this.email.mailRemoteId)) {
        this.initializeEmail();
      }
    };
    this.$root.$on('open-email-detail-drawer', this.onOpenEmailDetailDrawer);
    this.$root.$on('close-email-detail-drawer', this.onCloseEmailDetailDrawer);
    this.$root.$on('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$on('update-email-read-status', this.onUpdateEmailReadStatus);
    this.$root.$on('delete-email', this.onDeleteEmail);
    this.$root.$on('archive-email', this.onArchiveEmail);
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
      this.emails = emails;
    });
  },
  beforeDestroy() {
    this.$root.$off('open-email-detail-content', this.onOpenEmailDetailContent);
    this.$root.$off('open-email-detail-drawer', this.onOpenEmailDetailDrawer);
    this.$root.$off('close-email-detail-drawer', this.onCloseEmailDetailDrawer);
    this.$root.$off('delete-email', this.onDeleteEmail);
    this.$root.$off('archive-email', this.onArchiveEmail);
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
  },
  methods: {
    open(mailRemoteId, emails, syncInProgress) {
      this.loading = true;
      this.emailDetailDrawer = true;
      this.emails = emails;
      this.syncInProgress = syncInProgress;
      this.$root.isDetailDrawerActive = true;
      this.$root.$emit('update-email-read-status', true, [mailRemoteId]);
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId).then((email) => {
        this.email = email;
      }).finally(() => {
        this.loading = false;
      });
    },
    openEmailDetailContent(mailRemoteId) {
      this.loading = true;
      this.$emailConnectorMailBoxService.getEmailByRemoteId(mailRemoteId).then((email) => {
        this.email = email;
        this.$root.$emit('update-email-read-status', true, [mailRemoteId]);
        this.selectEmailPlaceHolder = false;
      }).finally(() => {
        this.loading = false;
      });
    },
    initializeEmail() {
      if (this.emails?.[0]) {
        this.openEmailDetailContent(this.emails[0].mailRemoteId);
      }
    },
    refreshEmails(emailIds = []) {
      this.emails = this.emails.filter(
        e => !emailIds.includes(e.mailRemoteId)
      );
      if (this.selectMode) {
        this.cancelSelectMode();
      }
    },
    cancelSelectMode() {
      this.selectMode = false;
      this.selectedEmails = [];
    },
    onAbortDownloadConfirmed() {
      this.$root.$emit('abort-download-attachment', this.activeDownload.mailRemoteId, this.activeDownload.attachmentRemoteId, this.activeDownload.abortController);
      this.close();
    },
    close() {
      this.emailDetailDrawer = false;
      this.cancelSelectMode();
      this.selectEmailPlaceHolder = false;
      this.$root.isDetailDrawerActive = false;
      this.$root.$emit('email-detail-drawer-closed');
    },
    updateExpand(expanded) {
      window.setTimeout(() => this.expanded = expanded, 200);
      if (!expanded && this.selectEmailPlaceHolder) {
        this.close();
      }
    },
  }
};
</script>