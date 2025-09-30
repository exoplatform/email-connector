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
    <email-connector-mail-box-drawer :user-email-setting="userEmailSetting" />
  </v-app>
</template>

<script>
export default {
  data: () => ({
    userEmailSetting: {
      emailConnectorId: '',
      emailConnectorImageUrl: '',
      emailConnectorIcon: '',
      emailAddress: '',
      emailPassword: ''
    }
  }),
  mounted() {
    document.addEventListener('quick-action-mailBox-drawer', this.openDrawer);
  },
  beforeDestroy() {
    document.removeEventListener('quick-action-mailBox-drawer', this.openDrawer);
  },
  methods: {
    openDrawer(event) {
      this.$emailConnectorCommonService.getUserEmailSetting().then(userEmailSetting => {
        this.userEmailSetting = userEmailSetting;
        if (this.userEmailSetting.emailConnectorId) {
          this.$root.$emit('open-mail-box-drawer', event?.detail);
        }
        else {
          this.$root.$emit('open-user-setting-connectors-drawer');
        }
      });
    }
  }
};
</script>