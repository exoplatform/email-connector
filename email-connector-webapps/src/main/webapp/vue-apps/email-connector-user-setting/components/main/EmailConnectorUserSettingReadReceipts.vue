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
       happens when somebody else's mail asks for one. Inline switches and choices, like
       the rows above: two preferences set once, no drawer. "Always send" is offered only
       while the administrator allows it. -->
  <div class="read-receipt-settings">
    <v-list-item>
      <v-list-item-content>
        <v-list-item-title class="text-color">
          {{ $t('UserSettings.emailConnector.readReceipt.title') }}
        </v-list-item-title>
        <v-list-item-subtitle>
          {{ $t('UserSettings.emailConnector.readReceipt.requestByDefault') }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action>
        <v-switch
          v-model="requestByDefault"
          :disabled="!loaded || saving"
          :aria-label="$t('UserSettings.emailConnector.readReceipt.requestByDefault')"
          class="read-receipt-request-by-default"
          @change="save" />
      </v-list-item-action>
    </v-list-item>
    <v-list-item class="height-auto">
      <v-list-item-content>
        <v-list-item-subtitle>
          {{ $t('UserSettings.emailConnector.readReceipt.policy.label') }}
        </v-list-item-subtitle>
        <v-radio-group
          v-model="responsePolicy"
          :disabled="!loaded || saving"
          class="mt-1 read-receipt-policy"
          hide-details
          dense
          row
          @change="save">
          <v-radio
            v-for="policy in policies"
            :key="policy"
            :value="policy"
            :label="$t(`UserSettings.emailConnector.readReceipt.policy.${policy}`)"
            :class="`read-receipt-policy-${policy}`" />
        </v-radio-group>
        <v-list-item-subtitle
          v-if="responsePolicy === 'ALWAYS'"
          class="caption text-sub-title text-wrap mt-1">
          {{ $t('UserSettings.emailConnector.readReceipt.policy.ALWAYS.hint') }}
        </v-list-item-subtitle>
      </v-list-item-content>
    </v-list-item>
  </div>
</template>

<script>
export default {
  data: () => ({
    loaded: false,
    saving: false,
    requestByDefault: false,
    responsePolicy: 'ASK',
    alwaysAllowed: false,
    // What the server last said, to go back to when a save is refused.
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
  },
  created() {
    this.read();
  },
  methods: {
    /**
     * Reads the preferences. Failing leaves the rows disabled and silent, like the other
     * rows of this screen: an unreadable preference is not worth an error banner.
     *
     * @returns {Promise<void>} resolved once read or given up
     */
    read() {
      return this.$emailConnectorCommonService.getReadReceiptSettings()
        .then(settings => this.apply(settings))
        .catch(() => null);
    },
    /**
     * Stores both preferences as they stand on screen, and shows what the server kept.
     * A refused save puts the screen back as it was and says so.
     *
     * @returns {Promise<void>} resolved once saved or refused
     */
    save() {
      this.saving = true;
      return this.$emailConnectorCommonService.saveReadReceiptSettings({
        requestByDefault: this.requestByDefault,
        responsePolicy: this.responsePolicy,
      })
        .then(settings => this.apply(settings))
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
      this.responsePolicy = settings.responsePolicy === 'ALWAYS' && !this.alwaysAllowed ? 'ASK' : (settings.responsePolicy || 'ASK');
      this.loaded = true;
    },
  },
};
</script>
