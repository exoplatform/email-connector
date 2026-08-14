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
  <exo-confirm-dialog
    ref="purgeEmailConfirmDialog"
    :title="$t('emailConnector.mailBox.list.drawer.purge.confirm.title')"
    :message="message"
    :ok-label="$t('emailConnector.mailBox.list.drawer.purge.confirm.button.delete')"
    :cancel-label="$t('emailConnector.mailBox.list.drawer.purge.confirm.button.cancel')"
    persistent
    @ok="purgeEmails" />
</template>

<script>
export default {
  data() {
    return {
      mailRemoteIds: [],
      onConfirmed: null,
    };
  },
  computed: {
    /**
     * What the dialog says. Two wordings rather than one with a "1 message(s)" in it:
     * the singular is by far the common case and reads as a sentence, and the plural
     * carries the count, which is the whole reason to ask again when several rows are
     * selected. Both say plainly that this cannot be undone — it is the only action in
     * the mailbox for which that is true.
     *
     * @returns {String} the confirmation message
     */
    message() {
      const count = this.mailRemoteIds.length;
      return count === 1
        ? this.$t('emailConnector.mailBox.list.drawer.purge.confirm.message')
        : this.$t('emailConnector.mailBox.list.drawer.purge.confirm.messages', { 0: count });
    },
  },
  created() {
    this.$root.$on('open-purge-email-confirm-popup', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-purge-email-confirm-popup', this.open);
  },
  methods: {
    /**
     * Opens the confirmation for a set of trashed messages.
     *
     * @param {Array} mailRemoteIds the IMAP UIDs, within the Trash folder, at stake
     * @param {Function} onConfirmed optional, run only if the user goes through with
     *        it — the reader uses it to close itself, which must not happen on a cancel
     * @returns {void}
     */
    open(mailRemoteIds, onConfirmed) {
      this.mailRemoteIds = mailRemoteIds || [];
      this.onConfirmed = onConfirmed || null;
      if (!this.mailRemoteIds.length) {
        return;
      }
      this.$refs.purgeEmailConfirmDialog.open();
    },
    /**
     * Sends the permanent delete. The mailbox drawer owns the request and the error
     * alert; this component only ever asks the question.
     *
     * @returns {void}
     */
    purgeEmails() {
      this.$root.$emit('purge-email', this.mailRemoteIds);
      if (this.onConfirmed) {
        this.onConfirmed();
      }
    },
  }
};
</script>
