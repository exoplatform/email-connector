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
  <!-- Undo send (EXO-90837, EXO-90872), like the other settings drawers: opened by the
       root event from the "Undo send" row, it reads the server on every opening and
       stores nothing until Save. The waits offered are the server's, read with the
       preference: this screen keeps no copy of them. What the server kept is handed
       back to the row. -->
  <exo-drawer
    id="userSettingUndoSendDrawer"
    ref="undoSendDrawer"
    v-model="drawer"
    :loading="loading || saving"
    right>
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.undoSend.title') }}</span>
    </template>
    <template #content>
      <div class="pa-4">
        <!-- While the server is read, the drawer's own bar under its title says so. -->
        <template v-if="!loaded">
          <div
            v-if="!loading"
            class="error--text"
            role="alert">
            {{ $t('UserSettings.emailConnector.undoSend.readError') }}
          </div>
        </template>
        <template v-else>
          <div class="text-color">
            {{ $t('UserSettings.emailConnector.undoSend.label') }}
          </div>
          <v-radio-group
            v-model="delaySeconds"
            :disabled="saving"
            class="mt-2 pt-0 undo-send-delay"
            hide-details
            dense>
            <v-radio
              v-for="delay in allowedDelays"
              :key="delay"
              :value="delay"
              :label="optionLabel(delay)"
              :class="`undo-send-delay-${delay}`" />
          </v-radio-group>
        </template>
      </div>
    </template>
    <template #footer>
      <div class="d-flex align-center justify-end">
        <v-btn
          class="btn undo-send-cancel"
          @click="close">
          {{ $t('UserSettings.emailConnector.userSetting.drawer.cancel') }}
        </v-btn>
        <v-btn
          :disabled="!changed || loading"
          :loading="saving"
          class="btn btn-primary ms-5 undo-send-save"
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
    delaySeconds: null,
    allowedDelays: [],
    // What the server last said, to compare the screen with and to go back to when a
    // save is refused.
    stored: null,
  }),
  computed: {
    /**
     * Whether the wait on screen differs from the stored one, which is what Save stores.
     *
     * @returns {Boolean} true when there is something to save
     */
    changed() {
      return this.loaded && this.delaySeconds !== this.stored?.delaySeconds;
    },
  },
  /**
   * Opens on the row's edit action.
   *
   * @returns {void}
   */
  created() {
    this.$root.$on('open-email-undo-send-drawer', this.open);
  },
  /**
   * Stops listening once the drawer is gone.
   *
   * @returns {void}
   */
  beforeDestroy() {
    this.$root.$off('open-email-undo-send-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on the preference as the server holds it right now.
     *
     * @returns {Promise<void>} resolved once read or given up
     */
    open() {
      this.loaded = false;
      this.drawer = true;
      this.loading = true;
      return this.$emailConnectorCommonService.getUndoSendSettings()
        .then(settings => this.apply(settings))
        .catch(() => null)
        .finally(() => this.loading = false);
    },
    /**
     * Closes the drawer; a wait chosen and not saved is dropped, since the next opening
     * reads the server again.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
    },
    /**
     * Stores the wait chosen on screen, hands what the server kept to the row, and
     * closes. A refused save puts the screen back as it was, says so, and keeps the
     * drawer open.
     *
     * @returns {Promise<void>} resolved once saved or refused
     */
    save() {
      this.saving = true;
      return this.$emailConnectorCommonService.saveUndoSendSettings(this.delaySeconds)
        .then(settings => {
          this.apply(settings);
          this.$root.$emit('email-undo-send-updated', settings);
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success');
          this.close();
        })
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
