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
    ref="noSubjectEmailConfirmDialog"
    :title="$t('emailConnector.mailBox.newEmail.drawer.confirmNoSubject.title')"
    :message="message || $t('emailConnector.mailBox.newEmail.drawer.confirmNoSubject.message')"
    :ok-label="okLabel || $t('emailConnector.mailBox.newEmail.drawer.confirmNoSubject.button.send')"
    :cancel-label="$t('emailConnector.mailBox.newEmail.drawer.confirmNoSubject.button.cancel')"
    persistent
    @ok="sendEmail" />
</template>

<script>
export default {
  data() {
    return {
      email: null,
      // What the question says and does when it is asked for something else than a
      // send -- a schedule (EXO-90434): its wording, and what going on runs.
      message: null,
      okLabel: null,
      onConfirm: null,
    };
  },
  created() {
    this.$root.$on('open-no-subject-email-confirm-popup', (email, options) => {
      this.open(email, options);
    });
  },
  methods: {
    /**
     * Asks whether to go on without a subject.
     *
     * @param {Object} email the payload a send hands back on "go on"
     * @param {Object} options {message, okLabel, onConfirm} for another action than a
     *        send: its wording, and what to run instead of handing the payload back
     * @returns {void}
     */
    open(email, options) {
      this.email = email;
      this.message = options?.message || null;
      this.okLabel = options?.okLabel || null;
      this.onConfirm = options?.onConfirm || null;
      this.$refs.noSubjectEmailConfirmDialog.open();
    },
    /**
     * Goes on without a subject: runs the action that asked, or hands the payload back
     * to the send.
     *
     * @returns {void}
     */
    sendEmail() {
      if (this.onConfirm) {
        this.onConfirm();
        return;
      }
      this.$root.$emit('send-email', this.email);
    }
  }
};
</script>
