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
  <!-- Read receipts (EXO-90435, EXO-90872), like the other settings drawers: opened by
       the root event from the "Read receipts" row, it reads the server on every opening
       and stores nothing until Save. "Always send" is offered only while the
       administrator allows it. What the server kept is handed back to the row. -->
  <exo-drawer
    id="userSettingReadReceiptsDrawer"
    ref="readReceiptsDrawer"
    v-model="drawer"
    :loading="loading || saving"
    right>
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.readReceipt.title') }}</span>
    </template>
    <template #content>
      <div class="pa-4">
        <!-- While the server is read, the drawer's own bar under its title says so. -->
        <template v-if="!loaded">
          <div
            v-if="!loading"
            class="error--text"
            role="alert">
            {{ $t('UserSettings.emailConnector.readReceipt.readError') }}
          </div>
        </template>
        <template v-else>
          <div class="d-flex align-center">
            <div class="text-color text-start flex-grow-1">
              {{ $t('UserSettings.emailConnector.readReceipt.requestByDefault') }}
            </div>
            <v-switch
              v-model="requestByDefault"
              :disabled="saving"
              :aria-label="$t('UserSettings.emailConnector.readReceipt.requestByDefault')"
              class="read-receipt-request-by-default mt-0 pt-0 ms-2"
              hide-details />
          </div>
          <div class="text-color mt-6">
            {{ $t('UserSettings.emailConnector.readReceipt.policy.label') }}
          </div>
          <v-radio-group
            v-model="responsePolicy"
            :disabled="saving"
            class="mt-2 pt-0 read-receipt-policy"
            hide-details
            dense>
            <v-radio
              v-for="policy in policies"
              :key="policy"
              :value="policy"
              :label="$t(`UserSettings.emailConnector.readReceipt.policy.${policy}`)"
              :class="`read-receipt-policy-${policy}`" />
          </v-radio-group>
          <div
            v-if="responsePolicy === 'ALWAYS'"
            class="caption text-sub-title mt-2">
            {{ $t('UserSettings.emailConnector.readReceipt.policy.ALWAYS.hint') }}
          </div>
        </template>
      </div>
    </template>
    <template #footer>
      <div class="d-flex align-center justify-end">
        <v-btn
          class="btn read-receipt-cancel"
          @click="close">
          {{ $t('UserSettings.emailConnector.userSetting.drawer.cancel') }}
        </v-btn>
        <v-btn
          :disabled="!changed || loading"
          :loading="saving"
          class="btn btn-primary ms-5 read-receipt-save"
          @click="save">
          {{ $t('UserSettings.emailConnector.userSetting.drawer.save') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    drawer: false,
    loading: false,
    saving: false,
    loaded: false,
    requestByDefault: false,
    responsePolicy: 'ASK',
    alwaysAllowed: false,
    // What the server last said, to compare the screen with and to go back to when a
    // save is refused.
    stored: null,
  }),
  computed: {
    /**
     * The answers offered: Ask me, Never send, and Always send only while the
     * administrator allows it.
     *
     * @returns {Array<String>} the policies, in the order they are shown
     */
    policies() {
      return this.alwaysAllowed ? ['ASK', 'NEVER', 'ALWAYS'] : ['ASK', 'NEVER'];
    },
    /**
     * Whether the screen differs from what the server holds, which is what Save stores.
     *
     * @returns {Boolean} true when there is something to save
     */
    changed() {
      return this.loaded && (this.requestByDefault !== this.storedRequestByDefault
        || this.responsePolicy !== this.storedResponsePolicy);
    },
    /**
     * @returns {Boolean} the stored "request by default", as the screen shows it
     */
    storedRequestByDefault() {
      return !!this.stored?.requestByDefault;
    },
    /**
     * @returns {String} the stored answer, as the screen shows it
     */
    storedResponsePolicy() {
      return this.policyShown(this.stored);
    },
  },
  /**
   * Opens on the row's edit action.
   *
   * @returns {void}
   */
  created() {
    this.$root.$on('open-email-read-receipts-drawer', this.open);
  },
  /**
   * Stops listening once the drawer is gone.
   *
   * @returns {void}
   */
  beforeDestroy() {
    this.$root.$off('open-email-read-receipts-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on the preferences as the server holds them right now.
     *
     * @returns {Promise<void>} resolved once read or given up
     */
    open() {
      this.loaded = false;
      this.drawer = true;
      this.loading = true;
      return this.$emailConnectorCommonService.getReadReceiptSettings()
        .then(settings => this.apply(settings))
        .catch(() => null)
        .finally(() => this.loading = false);
    },
    /**
     * Closes the drawer; what was changed and not saved is dropped, since the next
     * opening reads the server again.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
    },
    /**
     * Stores both preferences as they stand on screen, hands what the server kept to the
     * row, and closes. A refused save puts the screen back as it was, says so, and keeps
     * the drawer open.
     *
     * @returns {Promise<void>} resolved once saved or refused
     */
    save() {
      this.saving = true;
      return this.$emailConnectorCommonService.saveReadReceiptSettings({
        requestByDefault: this.requestByDefault,
        responsePolicy: this.responsePolicy,
      })
        .then(settings => {
          this.apply(settings);
          this.$root.$emit('email-read-receipts-updated', settings);
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success');
          this.close();
        })
        .catch(() => {
          this.apply(this.stored);
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.readReceipt.saveError'), 'error');
        })
        .finally(() => this.saving = false);
    },
    /**
     * Shows preferences as the server answered them.
     *
     * @param {Object} settings {requestByDefault, responsePolicy, alwaysAllowed}
     * @returns {void}
     */
    apply(settings) {
      if (!settings) {
        return;
      }
      this.stored = settings;
      this.requestByDefault = !!settings.requestByDefault;
      this.alwaysAllowed = !!settings.alwaysAllowed;
      this.responsePolicy = this.policyShown(settings);
      this.loaded = true;
    },
    /**
     * The answer a stored preference is shown as. A stored ALWAYS is shown as ASK while
     * the administrator disables it. No live response can carry that pair -- the server
     * already answers a stored ALWAYS as ASK whenever the switch is off -- so this guard
     * is for a response that predates the switch being turned off. Without it the screen
     * would offer a choice the server would then refuse.
     *
     * @param {Object} settings {responsePolicy, alwaysAllowed}, or null
     * @returns {String} the policy shown
     */
    policyShown(settings) {
      const policy = settings?.responsePolicy || 'ASK';
      return policy === 'ALWAYS' && !settings.alwaysAllowed ? 'ASK' : policy;
    },
  },
};
</script>
