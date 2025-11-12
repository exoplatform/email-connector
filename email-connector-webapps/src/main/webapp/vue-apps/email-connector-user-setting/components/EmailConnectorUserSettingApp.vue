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
    id="emailConnectorUserSetting">
    <template>
      <email-connector-user-setting-body :user-email-setting="userEmailSetting" />
      <email-connector-user-setting-connectors-drawer />
      <email-connector-user-setting-drawer :user-email-setting="userEmailSetting" />
    </template>
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
    },
    displayed: true,
  }),
  watch: {
    displayed() {
      this.$root.$updateApplicationVisibility(this.displayed);
    },
  },
  mounted() {
    document.addEventListener('refresh-user-email-setting', this.getUserEmailSetting);
    this.$root.$updateApplicationVisibility(this.displayed);
  },
  beforeDestroy() {
    document.removeEventListener('refresh-user-email-setting', this.getUserEmailSetting);
  },
  created() {
    this.getUserEmailSetting();
    document.addEventListener('showSettingsApps', this.showSettingsApps);
    document.addEventListener('hideSettingsApps', this.hideSettingsApps);
  },
  methods: {
    getUserEmailSetting() {
      this.$emailConnectorCommonService.getUserEmailSetting().then(userEmailSetting => {
        this.userEmailSetting = userEmailSetting;
      });
    },
    hideSettingsApps() {
      this.displayed = false;
    },
    showSettingsApps() {
      this.displayed = true;
    },
  }
};
</script>