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
      <email-box-sync-loader
        v-if="syncInProgress"
        :label="$t('emailConnector.mailBox.list.drawer.sync.tooltip')"
        icon-size="20" />
    </template>
    <template v-if="emailBoxDrawer" #content>
      <div
        class="fill-height overflow-y-auto specific-scrollbar">
        <email-connector-mail-box-drawer-list
          v-if="hasEmails"
          :emails="emails" /> 
        <v-list-item v-else class="full-height align-center">
          <v-list-item-content>
            <email-connector-icon
              icon="far fa-envelope"
              icon-class="tertiary--text"
              icon-size="60" />
            <v-list-item-title class="text-wrap mt-5">
              {{ $t('emailConnector.mailBox.list.drawer.noEmail') }}
            </v-list-item-title>
          </v-list-item-content>
        </v-list-item>
      </div>
    </template>
    <template #footer>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    emailBoxDrawer: false,
    emails: [],
    syncInProgress: false,
  }),
  props: {
    userEmailSetting: {
      type: Object,
      default: null,
    },
  },
  created() {
    this.$root.$on('open-mail-box-drawer', (loading) => {
      this.open(loading); 
    });
  },
  computed: {
    hasEmails() {
      return this.emails?.length > 0;
    }
  },
  methods: {
    async open(loading) {
      this.emails = await this.$emailConnectorMailBoxService.getEmails();
      this.syncInProgress = loading || this.userEmailSetting?.emailSyncStatus === 'IN_PROGRESS';
      this.$refs.emailBoxDrawer.open();
    },
    close() {
      this.$refs.emailBoxDrawer.close();
    }
  }
};
</script>