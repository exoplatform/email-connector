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
    :allow-expand="!$root.isMobile"
    @closed="close">
    <template #title>
      <span class="me-3">{{ $t('emailConnector.mailBox.list.drawer.title') }}</span>
      <v-tooltip
        v-if="syncInProgress"
        bottom>
        <template #activator="{on, attrs}">
          <v-progress-circular
            v-on="on"
            v-bind="attrs"
            size="20"
            color="primary"
            indeterminate />
        </template>
        <span>
          {{ $t('emailConnector.mailBox.list.drawer.sync.tooltip') }}
        </span>
      </v-tooltip>
    </template>
    <template v-if="emailBoxDrawer" #content>
      <div
        class="fill-height overflow-y-auto specific-scrollbar">
        <email-connector-mail-box-drawer-list
          class="py-0 mt-3 ms-7 me-4"
          v-if="hasEmails"
          :emails="emails" /> 
        <div class="d-flex flex-column align-center justify-center full-width full-height" v-else>               
          <email-connector-icon
            icon="far fa-envelope"
            icon-class="tertiary--text"
            icon-size="60" />
          <div class="mt-5">
            {{ $t('emailConnector.mailBox.list.drawer.noEmail') }}
          </div>
        </div>
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
      this.syncInProgress = loading || this.userEmailSetting?.syncStatus === 'IN_PROGRESS';
      this.$refs.emailBoxDrawer.open();
    },
    close() {
      this.$refs.emailBoxDrawer.close();
    }
  }
};
</script>