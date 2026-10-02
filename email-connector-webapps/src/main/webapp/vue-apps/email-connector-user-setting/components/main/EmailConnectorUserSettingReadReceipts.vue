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
  <!-- Read receipts (EXO-90435): whether a new mail asks for one by default, and what
       happens when somebody else's mail asks for one. A one-line row under Advanced
       settings that says both choices, with the edit action that opens the drawer
       mounted at the app's root, like the other rows of this screen (EXO-90872). -->
  <v-list-item class="read-receipt-settings">
    <v-list-item-content>
      <v-list-item-title class="text-color">
        {{ $t('UserSettings.emailConnector.readReceipt.title') }}
      </v-list-item-title>
      <v-list-item-subtitle>
        {{ summary }}
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action>
      <v-btn
        icon
        :title="$t('UserSettings.emailConnector.readReceipt.edit.tooltip')"
        class="read-receipt-edit"
        @click="$root.$emit('open-email-read-receipts-drawer')">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  data: () => ({
    loaded: false,
    requestByDefault: false,
    responsePolicy: 'ASK',
  }),
  computed: {
    /**
     * Both choices on one line: "Ask me · requested by default: off". Until the
     * preferences are read, what the row is about rather than defaults that may not be
     * the user's.
     *
     * @returns {String} the localized summary
     */
    summary() {
      if (!this.loaded) {
        return this.$t('UserSettings.emailConnector.readReceipt.policy.label');
      }
      return this.$t('UserSettings.emailConnector.readReceipt.summary', {
        0: this.$t(`UserSettings.emailConnector.readReceipt.policy.${this.responsePolicy}`),
        1: this.$t(`UserSettings.emailConnector.readReceipt.summary.${this.requestByDefault ? 'on' : 'off'}`),
      });
    },
  },
  /**
   * Reads the summary, and follows what the drawer saves.
   *
   * @returns {void}
   */
  created() {
    this.read();
    // The drawer is where the choices are saved: what it stored is what this row shows.
    this.$root.$on('email-read-receipts-updated', this.apply);
  },
  /**
   * Stops following the drawer once the row is gone.
   *
   * @returns {void}
   */
  beforeDestroy() {
    this.$root.$off('email-read-receipts-updated', this.apply);
  },
  methods: {
    /**
     * Reads the preferences the row summarises. Failing leaves the row on what it is
     * about, silent like the other rows of this screen: an unreadable preference is not
     * worth an error banner.
     *
     * @returns {Promise<void>} resolved once read or given up
     */
    read() {
      return this.$emailConnectorCommonService.getReadReceiptSettings()
        .then(settings => this.apply(settings))
        .catch(() => null);
    },
    /**
     * Summarises preferences as the server answered them. A stored ALWAYS reads as ASK
     * while the administrator disables it, as the drawer shows it.
     *
     * @param {Object} settings {requestByDefault, responsePolicy, alwaysAllowed}
     * @returns {void}
     */
    apply(settings) {
      if (!settings) {
        return;
      }
      const policy = settings.responsePolicy || 'ASK';
      this.requestByDefault = !!settings.requestByDefault;
      this.responsePolicy = policy === 'ALWAYS' && !settings.alwaysAllowed ? 'ASK' : policy;
      this.loaded = true;
    },
  },
};
</script>
