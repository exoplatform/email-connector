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
    id="emailBoxDrawer"
    ref="emailBoxDrawer"
    v-model="emailBoxDrawer"
    :right="!$vuetify.rtl"
    @closed="close">
    <template #title>
      <span class="me-3">{{ $t('emailConnector.mailBox.list.drawer.title') }}</span>
    </template>
    <template #titleIcons>
      <email-box-sync-loader
        v-if="syncInProgress"
        :label="$t('emailConnector.mailBox.list.drawer.sync.inProgress.tooltip')"
        loader-class="align-self-center me-2"
        icon-size="20" />
      <v-tooltip
        v-else 
        bottom>
        <template #activator="{on, attrs}">
          <v-btn
            v-on="on"
            v-bind="attrs"
            @click="synchronize()"
            icon>
            <v-icon size="20">fa-sync-alt</v-icon>
          </v-btn>
        </template>
        <span>
          {{ $t('emailConnector.mailBox.list.drawer.sync.tooltip') }}
        </span>
      </v-tooltip>
    </template>
    <template v-if="emailBoxDrawer" #content>
      <div
        class="fill-height overflow-y-auto specific-scrollbar">
        <v-list-item v-if="syncBlocked" class="full-height align-center">
          <v-list-item-content>
            <v-icon
              size="60"
              class="orange--text text--darken-2">
              fas fa-exclamation-triangle
            </v-icon>
            <v-list-item-title class="text-wrap mt-5 mb-0">
              {{ $t('emailConnector.mailBox.list.drawer.sync.blocked.reconnect') }}
            </v-list-item-title>
            <div class="mt-8">
              <v-btn
                @click="checkSetting"
                class="btn btn-primary body-2">
                {{ $t('emailConnector.mailBox.list.drawer.sync.blocked.checkSetting') }}
              </v-btn>
            </div>
          </v-list-item-content>
        </v-list-item>
        <template v-else>
          <email-connector-mail-box-drawer-list
            v-if="hasEmails"
            :emails="emails" /> 
          <v-list-item v-else class="full-height align-center">
            <v-list-item-content>
              <v-icon
                size="60"
                class="tertiary--text">
                far fa-envelope
              </v-icon>
              <v-list-item-title class="text-wrap mt-5">
                {{ $t('emailConnector.mailBox.list.drawer.noEmail') }}
              </v-list-item-title>
            </v-list-item-content>
          </v-list-item>
        </template>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    emailBoxDrawer: false,
    emailBox: null,
    emails: [],
    syncInProgress: false,
    refreshInterval: null,
  }),
  created() {
    this.isRefreshing = false;
    this.$root.$on('open-mail-box-drawer', (loading) => {
      this.open(loading); 
    });
    this.$root.$on('refresh-emails', () => {
      this.loadEmailBox();
    });
  },
  computed: {
    hasEmails() {
      return this.emails?.length > 0;
    },
    syncBlocked() {
      return this.emailBox?.emailSyncStatus === 'BLOCKED';
    },
  },
  methods: {
    async open(loading) {
      if (loading) {
        this.syncInProgress = true;
        await this.$nextTick();
      }
      await this.loadEmailBox();
      this.$refs.emailBoxDrawer.open();
      if (this.syncInProgress) {
        this.startAutoRefresh();
      }
    },
    close() {
      this.stopAutoRefresh();
      document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
      this.$refs.emailBoxDrawer.close();
    },
    checkSetting() {
      this.$root.$emit('open-user-setting-drawer');
    },
    synchronize() {
      this.syncInProgress = true;
      this.$emailConnectorMailBoxService.synchronize().then(() =>
      {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.list.drawer.sync.success'), 'success');
      });
      this.startAutoRefresh();
    },
    async loadEmailBox() {
      this.emailBox = await this.$emailConnectorMailBoxService.getEmailBox();
      this.emails = this.emailBox.emails || [];
      this.syncInProgress = !this.emailBox.emailSyncStatus || this.emailBox.emailSyncStatus === 'IN_PROGRESS';
      if (!this.syncInProgress) {
        this.stopAutoRefresh();
      }
    },
    startAutoRefresh() {
      if (this.refreshInterval) {
        return;
      }
      this.isRefreshing = false;
      this.refreshInterval = setInterval(async () => {
        if (this.isRefreshing) {
          return;
        }
        this.isRefreshing = true;
        try {
          await this.loadEmailBox();
        } finally {
          this.isRefreshing = false;
        }
      }, 2000); 
    },
    stopAutoRefresh() {
      if (this.refreshInterval) {
        clearInterval(this.refreshInterval);
        this.refreshInterval = null;
      }
    }
  }
};
</script>