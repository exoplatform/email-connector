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
  <!-- Undo send (EXO-90837): how long a sent mail waits, with an Undo, before it goes.
       A one-line row under Advanced settings that says the choice and opens on it, as
       the read receipts' does. The waits offered are the server's, read with the
       preference: this screen keeps no copy of them. -->
  <div class="undo-send-settings">
    <v-list-item>
      <v-list-item-content>
        <v-list-item-title class="text-color">
          {{ $t('UserSettings.emailConnector.undoSend.title') }}
        </v-list-item-title>
        <v-list-item-subtitle>
          {{ summary }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action>
        <v-btn
          :aria-expanded="expanded ? 'true' : 'false'"
          :title="$t('UserSettings.emailConnector.undoSend.edit.tooltip')"
          aria-controls="emailConnectorUndoSendChoices"
          icon
          @click="expanded = !expanded">
          <v-icon size="16" class="icon-default-color">
            {{ expanded ? 'fa-chevron-up' : 'fa-chevron-down' }}
          </v-icon>
        </v-btn>
      </v-list-item-action>
    </v-list-item>
    <div v-show="expanded" id="emailConnectorUndoSendChoices">
      <v-list-item class="height-auto">
        <v-list-item-content class="ps-4">
          <v-list-item-subtitle>
            {{ $t('UserSettings.emailConnector.undoSend.label') }}
          </v-list-item-subtitle>
          <v-radio-group
            v-model="delaySeconds"
            :disabled="!loaded || saving"
            class="mt-1 undo-send-delay"
            hide-details
            dense
            row
            @change="save">
            <v-radio
              v-for="delay in allowedDelays"
              :key="delay"
              :value="delay"
              :label="optionLabel(delay)"
              :class="`undo-send-delay-${delay}`" />
          </v-radio-group>
        </v-list-item-content>
      </v-list-item>
    </div>
  </div>
</template>

<script>
export default {
  data: () => ({
    expanded: false,
    loaded: false,
    saving: false,
    delaySeconds: null,
    allowedDelays: [],
    // What the server last said, to go back to when a save is refused.
    stored: null,
  }),
  computed: {
    /**
     * The choice on one line, as the row shows it folded. Until the preference is read,
     * what the row is about rather than a default that may not be the user's.
     *
     * @returns {String} the localized summary
     */
    summary() {
      if (!this.loaded) {
        return this.$t('UserSettings.emailConnector.undoSend.label');
      }
      return this.delaySeconds > 0 ? this.$t('UserSettings.emailConnector.undoSend.summary', { 0: this.delaySeconds })
        : this.$t('UserSettings.emailConnector.undoSend.summary.off');
    },
  },
  created() {
    this.read();
  },
  methods: {
    /**
     * Reads the preference. Failing leaves the row disabled and silent, like the other
     * rows of this screen.
     *
     * @returns {Promise<void>} resolved once read or given up
     */
    read() {
      return this.$emailConnectorCommonService.getUndoSendSettings()
        .then(settings => this.apply(settings))
        .catch(() => null);
    },
    /**
     * Stores the wait chosen on screen, and shows what the server kept. A refused save
     * puts the screen back as it was and says so.
     *
     * @returns {Promise<void>} resolved once saved or refused
     */
    save() {
      this.saving = true;
      return this.$emailConnectorCommonService.saveUndoSendSettings(this.delaySeconds)
        .then(settings => this.apply(settings))
        .catch(() => {
          this.apply(this.stored);
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.undoSend.saveError'), 'error');
        })
        .finally(() => this.saving = false);
    },
    /**
     * Shows the preference as the server answered it.
     *
     * @param {Object} settings {delaySeconds, allowedDelays}
     * @returns {void}
     */
    apply(settings) {
      if (!settings) {
        return;
      }
      this.stored = settings;
      this.allowedDelays = settings.allowedDelays || [];
      this.delaySeconds = settings.delaySeconds;
      this.loaded = true;
    },
    /**
     * A wait's label: "Off", or "{n} seconds".
     *
     * @param {Number} delay the wait in seconds
     * @returns {String} the localized label
     */
    optionLabel(delay) {
      return delay > 0 ? this.$t('UserSettings.emailConnector.undoSend.option', { 0: delay })
        : this.$t('UserSettings.emailConnector.undoSend.option.off');
    },
  },
};
</script>
