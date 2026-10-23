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
  <!-- The senders whose mail always shows its images (EXO-90841), each removable. Opened
       from the "Images in received mail" row; the drawer's own bar shows while a removal
       is saved, and what the server kept is handed back to that row. -->
  <exo-drawer
    id="userSettingTrustedSendersDrawer"
    ref="trustedSendersDrawer"
    v-model="drawer"
    :loading="loading"
    right
    @closed="drawer = false">
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.remoteContent.drawer.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <div class="px-4 pt-4 pb-2 text-caption text-sub-title">
        {{ $t('UserSettings.emailConnector.remoteContent.drawer.description') }}
      </div>
      <div v-if="!loading && !senders.length" class="px-4 py-2 text-sub-title">
        {{ $t('UserSettings.emailConnector.remoteContent.drawer.none') }}
      </div>
      <v-list class="pa-0">
        <v-list-item
          v-for="sender in senders"
          :key="sender"
          class="trusted-sender">
          <v-list-item-content class="py-2">
            <v-list-item-title class="text-truncate">
              {{ sender }}
            </v-list-item-title>
          </v-list-item-content>
          <v-list-item-action>
            <v-btn
              :title="$t('UserSettings.emailConnector.remoteContent.forget', { 0: sender })"
              :aria-label="$t('UserSettings.emailConnector.remoteContent.forget', { 0: sender })"
              :disabled="loading"
              class="trusted-sender-remove"
              icon
              @click="forget(sender)">
              <v-icon size="16">fas fa-trash</v-icon>
            </v-btn>
          </v-list-item-action>
        </v-list-item>
      </v-list>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    drawer: false,
    loading: false,
    senders: [],
  }),
  created() {
    this.$root.$on('open-email-trusted-senders-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-trusted-senders-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on the senders as the server lists them now.
     *
     * @returns {Promise<void>} resolved once read or given up
     */
    open() {
      this.drawer = true;
      this.loading = true;
      return this.$emailConnectorCommonService.getRemoteContentSettings()
        .then(settings => this.apply(settings))
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.remoteContent.loadError'), 'error'))
        .finally(() => this.loading = false);
    },
    /**
     * Stops trusting a sender, and shows the list the server kept; a refusal says so
     * and leaves the list as it was.
     *
     * @param {String} sender the sender's address
     * @returns {Promise<void>} resolved once saved or refused
     */
    forget(sender) {
      this.loading = true;
      return this.$emailConnectorCommonService.setSenderTrusted(sender, false)
        .then(settings => this.apply(settings))
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.remoteContent.saveError'), 'error'))
        .finally(() => this.loading = false);
    },
    /**
     * Shows the server's list and hands it to the settings row.
     *
     * @param {Object} settings {blockRemoteContent, trustedSenders}
     * @returns {void}
     */
    apply(settings) {
      if (!settings) {
        return;
      }
      this.senders = settings.trustedSenders || [];
      this.$root.$emit('email-trusted-senders-updated', settings);
    },
  },
};
</script>
