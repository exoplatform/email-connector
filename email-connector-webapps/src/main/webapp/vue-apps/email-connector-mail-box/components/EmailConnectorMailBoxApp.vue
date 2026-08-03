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
  <v-app
    role="main"
    id="emailConnectorMailBox">
    <email-connector-user-setting-connectors-drawer />
    <email-connector-user-setting-drawer :user-email-setting="userEmailSetting" />
    <email-connector-mail-box-drawer />
    <email-connector-mail-box-drawer-list-item-detail />
    <email-connector-new-email-drawer />
    <email-connector-new-email-drawer-no-subject-confirm-popup />
    <email-connector-mail-box-drawer-attachments-drawer />
    <email-connector-mail-box-drawer-list-item-action-menu-drawer />
    <!-- The "Reset & re-sync" confirmation, requested from the mailbox ⋮ menu.
         Hosted here (not in the drawer) so the request works from whichever
         drawer's menu raised it, and reuses the settings page's wording. -->
    <exo-confirm-dialog
      ref="resetConfirmDialog"
      :title="$t('UserSettings.emailConnector.reset.confirm.title')"
      :message="$t('UserSettings.emailConnector.reset.confirm.message')"
      :ok-label="$t('UserSettings.emailConnector.reset.confirm.ok')"
      :cancel-label="$t('UserSettings.emailConnector.reset.confirm.cancel')"
      @ok="resetEmailBox" />
  </v-app>
</template>

<script>
export default {
  data() {
    return {
      userEmailSetting: {
        emailConnectorId: '',
        emailConnectorImageUrl: '',
        emailConnectorIcon: '',
        emailAddress: '',
        emailPassword: ''
      }
    };
  },
  created() {
    this.$root.$on('reset-email-box-request', this.confirmResetEmailBox);
  },
  mounted() {
    document.addEventListener('quick-action-mailBox-drawer', this.openDrawer);
    document.addEventListener('open-email-compose-with-attachment', this.openComposeWithAttachment);
  },
  beforeDestroy() {
    this.$root.$off('reset-email-box-request', this.confirmResetEmailBox);
    document.removeEventListener('quick-action-mailBox-drawer', this.openDrawer);
    document.removeEventListener('open-email-compose-with-attachment', this.openComposeWithAttachment);
  },
  methods: {
    // Ask before clearing the local mailbox copy: the reset also drops manually
    // applied categories, which the confirmation message spells out.
    confirmResetEmailBox() {
      this.$refs.resetConfirmDialog.open();
    },
    // Clear the local copy and re-download from the server. Broadcasting the
    // sync start first puts the open mailbox drawer in its refreshing loop, so
    // the rebuilt list appears without the user reopening anything.
    resetEmailBox() {
      this.$root.$emit('synchronize-in-progress');
      this.$emailConnectorCommonService.resetAndResyncMailbox()
        .then(() => {
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.reset.success'), 'success');
          document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.reset.error'), 'error'));
    },
    openDrawer(event) {
      this.$emailConnectorCommonService.getUserEmailSetting().then(userEmailSetting => {
        this.userEmailSetting = userEmailSetting;
        if (this.userEmailSetting.connected) {
          this.$root.$emit('open-mail-box-drawer', event?.detail);
        }
        else {
          this.$root.$emit('open-user-setting-connectors-drawer');
        }
      });
    },
    // Entry point used by other apps (e.g. the Documents "Send by email" action) to
    // open a NEW email pre-seeded with a document as an attachment. Same connected
    // gate as openDrawer; once the compose drawer is open its attachments component
    // becomes active, so we seed via the existing 'attachment-added' DOM event (the
    // very path the CKEditor picker uses) on the next tick.
    openComposeWithAttachment(event) {
      const attachment = event?.detail?.attachment;
      this.$emailConnectorCommonService.getUserEmailSetting().then(userEmailSetting => {
        this.userEmailSetting = userEmailSetting;
        if (!this.userEmailSetting.connected) {
          this.$root.$emit('open-user-setting-connectors-drawer');
          return;
        }
        this.$root.$emit('open-new-email-drawer');
        if (attachment) {
          this.$nextTick(() => setTimeout(() => document.dispatchEvent(new CustomEvent('attachment-added', {
            detail: { attachment },
          })), 400));
        }
      });
    },
  }
};
</script>