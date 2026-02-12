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
    :message="$t('emailConnector.mailBox.newEmail.drawer.confirmNoSubject.message')"
    :ok-label="$t('emailConnector.mailBox.newEmail.drawer.confirmNoSubject.button.send')"
    :cancel-label="$t('emailConnector.mailBox.newEmail.drawer.confirmNoSubject.button.cancel')"
    persistent
    @ok="sendEmail" />
</template>

<script>
export default {
  data() {
    return {
      email: null
    };
  },
  created() {
    this.$root.$on('open-no-subject-email-confirm-popup', (email) => {
      this.open(email);
    });
  },
  methods: {
    open(email) { 
      this.email = email;
      this.$refs.noSubjectEmailConfirmDialog.open(); 
    },
    sendEmail() { 
      this.$root.$emit('send-email', this.email);
    }
  }
};
</script>