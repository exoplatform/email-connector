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
       A one-line row under Advanced settings that says the choice, with the edit action
       that opens the drawer mounted at the app's root, like the other rows of this
       screen (EXO-90872). -->
  <v-list-item class="undo-send-settings">
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
        icon
        :title="$t('UserSettings.emailConnector.undoSend.edit.tooltip')"
        class="undo-send-edit"
        @click="$root.$emit('open-email-undo-send-drawer')">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  data: () => ({
    loaded: false,
    delaySeconds: null,
  }),
  computed: {
    /**
     * The choice on one line. Until the preference is read, what the row is about rather
     * than a default that may not be the user's.
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
  /**
   * Reads the summary, and follows what the drawer saves.
   *
   * @returns {void}
   */
  created() {
    this.read();
    // The drawer is where the wait is saved: what it stored is what this row shows.
    this.$root.$on('email-undo-send-updated', this.apply);
  },
  /**
   * Stops following the drawer once the row is gone.
   *
   * @returns {void}
   */
  beforeDestroy() {
    this.$root.$off('email-undo-send-updated', this.apply);
  },
  methods: {
    /**
     * Reads the preference the row summarises. Failing leaves the row on what it is
     * about, silent like the other rows of this screen.
     *
     * @returns {Promise<void>} resolved once read or given up
     */
    read() {
      return this.$emailConnectorCommonService.getUndoSendSettings()
        .then(settings => this.apply(settings))
        .catch(() => null);
    },
    /**
     * Summarises the preference as the server answered it.
     *
     * @param {Object} settings {delaySeconds, allowedDelays}
     * @returns {void}
     */
    apply(settings) {
      if (!settings) {
        return;
      }
      this.delaySeconds = settings.delaySeconds;
      this.loaded = true;
    },
  },
};
</script>
