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
    <!-- The settings' own folders drawer and its name drawer, reused whole, opened from
         the full-screen folder column (EXO-90415). Like the two drawers above, they are
         registered by the user-setting bundle, which every opening of this app requires
         first (see extensions.js), and speak to this app through its root. -->
    <email-connector-user-setting-folders-drawer />
    <email-connector-user-setting-folder-name-drawer />
    <email-connector-mail-box-drawer />
    <email-connector-mail-box-drawer-list-item-detail />
    <email-connector-new-email-drawer />
    <email-connector-new-email-drawer-no-subject-confirm-popup />
    <!-- Mounted at the app root, not inside the mailbox drawer: the permanent delete is
         asked from the row menu, from the reader and from the bulk toolbar, and the
         reader lives outside that drawer. One dialog, one wording, whoever asks. -->
    <email-connector-mail-box-purge-confirm-popup />
    <!-- Mounted beside it, for the same reason: Discard is asked from the Drafts
         listing's row menu and from its bulk toolbar, and both must ask it in the same
         words. The two places that discard a draft WITHOUT asking are deliberate and
         are not these: the composer's own Discard button, which is itself the explicit
         act, and the draft strip inside a conversation (EmailConnectorMailBoxDrawer-
         ThreadContent), which predates this dialog. -->
    <email-connector-mail-box-discard-drafts-confirm-popup />
    <!-- Taking mail out of a mailbox somebody shared with the user, asked once per
         session per mailbox (delegation plan 7.6), from wherever it was asked. -->
    <email-connector-shared-mailbox-confirm-popup />
    <!-- The settings' "Mailbox sharing" drawer, reused whole, opened on its "Shared with
         me" tab by the mailbox switcher's "Manage shared mailboxes" (plan 7.3), with the
         invite drawer its other tab's Share button opens; both registered by the
         user-setting bundle, like the folders drawers above. -->
    <email-connector-user-setting-mailbox-sharing-drawer />
    <email-connector-user-setting-sharing-invite-drawer />
    <email-connector-mail-box-move-to-folder-drawer />
    <email-connector-mail-box-drawer-attachments-drawer />
    <email-connector-mail-box-drawer-list-item-action-menu-drawer />
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
  mounted() {
    document.addEventListener('quick-action-mailBox-drawer', this.openDrawer);
    document.addEventListener('open-email-compose-with-attachment', this.openComposeWithAttachment);
    document.addEventListener('open-email-composer', this.openComposer);
  },
  beforeDestroy() {
    document.removeEventListener('quick-action-mailBox-drawer', this.openDrawer);
    document.removeEventListener('open-email-compose-with-attachment', this.openComposeWithAttachment);
    document.removeEventListener('open-email-composer', this.openComposer);
  },
  methods: {
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
    // Entry point used by other apps (the Contacts drawer's "compose to") to open
    // a NEW email with the recipients prefilled. Same connected gate as the other
    // entry points; the prefill travels as the composer open event's 4th argument
    // so reply/forward stay untouched.
    openComposer(event) {
      const prefill = event?.detail;
      this.$emailConnectorCommonService.getUserEmailSetting().then(userEmailSetting => {
        this.userEmailSetting = userEmailSetting;
        if (!this.userEmailSetting.connected) {
          this.$root.$emit('open-user-setting-connectors-drawer');
          return;
        }
        this.$root.$emit('open-new-email-drawer', null, false, false, prefill);
      });
    },
  }
};
</script>