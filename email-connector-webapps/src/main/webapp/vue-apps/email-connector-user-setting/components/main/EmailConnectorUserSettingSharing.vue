<!--
Copyright (C) 2026 eXo Platform SAS.

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
  <!-- The two sides of mailbox delegation (EXO-90503), each a row over a drawer, like
       the folders row above: who may read YOUR mailbox, and whose mailbox you may read.
       Two rows and not one because they are two different decisions with two different
       risks — giving access away, and taking somebody else's on. -->
  <div>
    <v-list-item>
      <v-list-item-content>
        <v-list-item-title class="text-color">
          {{ $t('UserSettings.emailConnector.sharing.title') }}
        </v-list-item-title>
        <v-list-item-subtitle class="text-wrap">
          {{ $t('UserSettings.emailConnector.sharing.description') }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action>
        <v-btn
          icon
          :title="$t('UserSettings.emailConnector.sharing.edit.tooltip')"
          @click="$root.$emit('open-email-sharing-drawer')">
          <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
        </v-btn>
      </v-list-item-action>
    </v-list-item>
    <v-list-item>
      <v-list-item-content>
        <v-list-item-title class="text-color">
          {{ $t('UserSettings.emailConnector.sharedWithMe.title') }}
        </v-list-item-title>
        <v-list-item-subtitle class="text-wrap">
          {{ $t('UserSettings.emailConnector.sharedWithMe.description') }}
        </v-list-item-subtitle>
        <!-- The one number worth a row: invitations waiting for an answer. Read
             WITHOUT discovery, so opening the settings screen costs no connection to
             the mail server; the drawer is where the server is walked. -->
        <v-list-item-subtitle v-if="pendingCount" class="caption primary--text mt-1">
          {{ $t('UserSettings.emailConnector.sharedWithMe.pending', { 0: pendingCount }) }}
        </v-list-item-subtitle>
        <v-list-item-subtitle v-else-if="loaded && acceptedCount" class="caption text-sub-title mt-1">
          {{ $t('UserSettings.emailConnector.sharedWithMe.accepted', { 0: acceptedCount }) }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action>
        <v-btn
          icon
          :title="$t('UserSettings.emailConnector.sharedWithMe.edit.tooltip')"
          @click="$root.$emit('open-email-shared-with-me-drawer')">
          <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
        </v-btn>
      </v-list-item-action>
    </v-list-item>
  </div>
</template>

<script>
export default {
  data: () => ({
    loaded: false,
    pendingCount: 0,
    acceptedCount: 0,
  }),
  created() {
    this.readCounters();
    // The drawers behind these rows are what the counters summarise.
    this.$root.$on('email-delegations-updated', this.readCounters);
  },
  beforeDestroy() {
    this.$root.$off('email-delegations-updated', this.readCounters);
  },
  methods: {
    /**
     * Reads how many shares wait for an answer and how many are in use. Failing is
     * silent, like the folders counter above: an unreadable count is not worth an
     * error banner over the whole settings screen, and the drawer says what happened
     * when the user actually opens it.
     *
     * @returns {void}
     */
    readCounters() {
      this.$emailConnectorUserSettingService.getReceivedDelegations(false)
        .then(delegations => {
          const rows = delegations || [];
          this.pendingCount = rows.filter(row => row.status === 'PENDING').length;
          this.acceptedCount = rows.filter(row => row.status === 'ACCEPTED').length;
          this.loaded = true;
        })
        .catch(() => null);
    },
  },
};
</script>
