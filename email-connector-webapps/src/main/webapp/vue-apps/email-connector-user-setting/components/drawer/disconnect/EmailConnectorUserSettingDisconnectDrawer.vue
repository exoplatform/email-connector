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
    id="userSettingDisconnectDrawer"
    ref="userSettingDisconnectDrawer"
    v-model="disconnectDrawer"
    :loading="loading"
    right
    allow-expand
    @closed="reset">
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.userSetting.switch.disconnect.title', [connectorName]) }}</span>
    </template>
    <template v-if="disconnectDrawer" #content>
      <email-connector-contacts-choice-step
        v-model="contactsChoice"
        :count="contactsCount"
        disconnecting />
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
          :disabled="!contactsChoice"
          :loading="loading"
          class="btn btn-primary ms-5"
          @click="applyChoiceThenDisconnect">
          {{ contactsChoice === 'fresh'
            ? $t('UserSettings.emailConnector.userSetting.switch.fresh.confirm.disconnect')
            : $t('UserSettings.emailConnector.connectors.drawer.connector.button.disconnect') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    loading: false,
    disconnectDrawer: false,
    connectorName: '',
    contactsChoice: 'keep',
    contactsCount: 0,
  }),
  created() {
    this.$root.$on('open-user-setting-disconnect-drawer', this.open);
  },
  methods: {
    /**
     * Disconnects, asking first only when the answer can change anything.
     * <p>
     * Disconnecting keeps every contact today, which is a sane default but is
     * nobody's decision: whoever leaves a provider for good never learns they
     * could have taken a backup and started clean. So the question is raised
     * here, before the account is released -- but only once the store is known
     * to be non-empty, and the count is fetched before the drawer is shown so
     * that the common case never flashes a drawer it is about to skip. An empty
     * store stays exactly one click, as it always was.
     *
     * @param {Object} emailConnector the connector being disconnected
     * @returns {void}
     */
    open(emailConnector) {
      this.connectorName = emailConnector?.name || '';
      this.$emailConnectorUserSettingService.getContactsCount()
        .then(count => {
          this.contactsCount = count;
          if (count > 0) {
            this.$refs.userSettingDisconnectDrawer.open();
            return;
          }
          // Nothing stored means nothing to decide about.
          return this.disconnect();
        })
        // An unreadable count is not a reason to hold the disconnect hostage:
        // fall back to the behaviour that predates the question, which loses
        // nothing.
        .catch(() => this.disconnect());
    },
    /**
     * Closes the drawer, leaving the account connected.
     *
     * @returns {void}
     */
    close() {
      this.$refs.userSettingDisconnectDrawer.close();
    },
    /**
     * Clears the answer once the drawer is gone, so the next disconnect starts
     * from the safe default rather than from whatever was last picked.
     *
     * @returns {void}
     */
    reset() {
      this.loading = false;
      this.contactsChoice = 'keep';
      this.contactsCount = 0;
      this.connectorName = '';
    },
    /**
     * Carries out the chosen handling, then disconnects.
     * <p>
     * The ordering is the whole point and is enforced by the promise chain: on
     * "start fresh" the backup download must resolve before anything is deleted
     * and before the account is released, so a failed or refused download leaves
     * both the contacts and the connection exactly as they were -- the drawer
     * stays open on the same question. The server enforces the same order on its
     * side (it deletes nothing until its last byte is flushed); this is the half
     * of that promise the user can actually see.
     *
     * @returns {void}
     */
    applyChoiceThenDisconnect() {
      if (this.contactsChoice !== 'fresh') {
        // Keeping is the absence of an action: the address book is released and
        // the collected contacts are handed over, as they already were.
        this.disconnect();
        return;
      }
      this.loading = true;
      this.$emailConnectorUserSettingService.downloadContactsBackupThenStartFresh()
        .then(() => this.disconnect())
        .catch(() => {
          this.loading = false;
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.userSetting.switch.fresh.error.disconnect'), 'error');
        });
    },
    /**
     * Releases the account and closes, the original behaviour untouched.
     *
     * @returns {Promise} resolved once the account has been released
     */
    disconnect() {
      this.loading = true;
      return this.$emailConnectorUserSettingService.deleteUserEmailSetting()
        .then(() => {
          if (this.disconnectDrawer) {
            this.close();
          }
          document.dispatchEvent(new CustomEvent('refresh-active-connectors-list'));
          document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.connectors.drawer.connector.disconnect.success'), 'success');
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.connectors.drawer.connector.disconnect.error'), 'error'))
        .finally(() => this.loading = false);
    },
  }
};
</script>
