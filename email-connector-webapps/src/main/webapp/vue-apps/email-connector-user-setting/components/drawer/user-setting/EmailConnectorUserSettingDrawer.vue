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
    id="userSettingDrawer"
    ref="userSettingDrawer"
    v-model="userSettingDrawer"
    :loading="loading"
    :right="!$vuetify.rtl"
    :allow-expand="!$root.isMobile"
    @closed="close">
    <template #title>
      <span>{{ drawerTitle }}</span>
    </template>
    <template v-if="userSettingDrawer" #content>
      <form
        ref="userSettingForm"
        class="mx-4 mt-4"
        @submit.stop.prevent="0">
        <v-list-item class="pa-0 mb-5" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('UserSettings.emailConnector.userSetting.drawer.emailAddress') }}
            </v-list-item-title>
            <v-text-field
              v-model="emailSetting.emailAddress"
              :aria-label="$t('UserSettings.emailConnector.userSetting.drawer.emailAddress')"
              :placeholder="$t('UserSettings.emailConnector.userSetting.drawer.placeHolder.emailAddress')"
              class="pt-3"
              type="text"
              required="required"
              outlined
              dense />
          </v-list-item-content>
        </v-list-item>
        <v-list-item class="pa-0" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('UserSettings.emailConnector.userSetting.drawer.password') }}
            </v-list-item-title>
            <v-text-field
              v-model="emailSetting.emailPassword"
              :aria-label="$t('UserSettings.emailConnector.userSetting.drawer.password')"
              :placeholder="$t('UserSettings.emailConnector.userSetting.drawer.placeHolder.password')"
              class="pt-3"
              :type="showPassword ? 'text' : 'password'"
              :append-icon="showPassword ? 'fa-eye-slash' : 'fa-eye'"
              @click:append="togglePasswordVisibility"
              required="required"
              outlined
              dense />
          </v-list-item-content>
        </v-list-item>
      </form>
    </template>
    <template #footer>
      <div class="d-flex">
        <v-spacer />
        <v-btn
          class="btn"
          @click="close">
          {{ $t('UserSettings.emailConnector.userSetting.drawer.cancel') }}
        </v-btn>
        <v-btn
          :disabled="disabled"
          @click="connect"
          :loading="loading"
          class="btn btn-primary ms-5">
          {{ $t('UserSettings.emailConnector.userSetting.drawer.save') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  props: {
    userEmailSetting: {
      type: Object,
      default: null,
    },
  },
  data: () => ({
    loading: false,
    userSettingDrawer: false,
    drawerTitle: '',
    showPassword: false,
    emailSetting: {
      emailConnectorId: '',
      emailAddress: '',
      emailPassword: ''
    }
  }),
  computed: {
    disabled() {
      return !this.emailSetting.emailConnectorId || !this.emailSetting.emailAddress || !this.emailSetting.emailPassword;
    }
  },
  created() {
    this.$root.$on('open-user-setting-drawer', this.open);
  },
  methods: {
    open(emailConnector) {
      this.emailSetting.emailAddress = this.userEmailSetting.emailAddress;
      this.emailSetting.emailPassword = this.userEmailSetting.emailPassword;
      this.emailSetting.emailConnectorId = emailConnector.id;
      this.drawerTitle =  this.$t('UserSettings.emailConnector.userSetting.drawer.title', {
        0: emailConnector.name,
      });
      this.$refs.userSettingDrawer.open();
    },
    close() {
      this.emailSetting.emailConnectorId = '';
      this.emailSetting.emailAddress = '';
      this.emailSetting.emailPassword = '';
      this.$refs.userSettingDrawer.close();
    },
    togglePasswordVisibility() {
      this.showPassword = !this.showPassword;
    },
    connect() {
      this.loading = true;
      this.$emailConnectorUserSettingService.setUserEmailSetting(this.emailSetting).then(() =>
      {
        this.close();
        this.$root.$emit('close-user-setting-connectors-drawer');
        document.dispatchEvent(new CustomEvent('refresh-active-connectors-list'));
        document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
        this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.userSetting.drawer.connect.success'), 'success');
        document.dispatchEvent(new CustomEvent('quick-action-mailBox-drawer', {
          detail: true
        }));
      }).catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.userSetting.drawer.connect.error'), 'error')
      ).finally(() => this.loading = false);
    }
  }
};
</script>