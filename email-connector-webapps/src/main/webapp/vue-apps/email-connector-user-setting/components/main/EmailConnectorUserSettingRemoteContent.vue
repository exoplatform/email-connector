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
  <!-- Images in received mail (EXO-90841): one line, like the address book's, with the
       switch on it -- whether the images a mail fetches from the internet wait until
       asked for -- and, once a sender is trusted, a pencil that opens the drawer listing
       the trusted senders, each removable there. -->
  <v-list-item class="remote-content-settings">
    <v-list-item-content>
      <v-list-item-title class="text-color">
        {{ $t('UserSettings.emailConnector.remoteContent.title') }}
      </v-list-item-title>
      <v-list-item-subtitle>
        {{ summary }}
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action class="d-flex flex-row align-center">
      <v-btn
        v-if="trustedSenders.length"
        :title="$t('UserSettings.emailConnector.remoteContent.edit.tooltip')"
        :aria-label="$t('UserSettings.emailConnector.remoteContent.edit.tooltip')"
        class="remote-content-edit-senders"
        icon
        @click="$root.$emit('open-email-trusted-senders-drawer')">
        <v-icon size="18" class="icon-default-color">fas fa-pen</v-icon>
      </v-btn>
      <v-switch
        v-model="blockRemoteContent"
        :disabled="!loaded || saving"
        :aria-label="$t('UserSettings.emailConnector.remoteContent.block')"
        class="remote-content-block ms-2"
        @change="saveBlocking" />
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  data: () => ({
    loaded: false,
    saving: false,
    blockRemoteContent: true,
    trustedSenders: [],
    // What the server last said, to go back to when a save is refused.
    stored: null,
  }),
  computed: {
    /**
     * The choice on one line, with how many senders are trusted.
     *
     * @returns {String} the localized summary
     */
    summary() {
      if (!this.loaded) {
        return this.$t('UserSettings.emailConnector.remoteContent.block');
      }
      return this.$t(`UserSettings.emailConnector.remoteContent.summary.${this.blockRemoteContent ? 'on' : 'off'}`, {
        0: this.trustedSenders.length,
      });
    },
  },
  created() {
    this.read();
    // The drawer is where senders are forgotten: what it stored is what this row shows.
    this.$root.$on('email-trusted-senders-updated', this.apply);
  },
  beforeDestroy() {
    this.$root.$off('email-trusted-senders-updated', this.apply);
  },
  methods: {
    /**
     * Reads the choices. Failing leaves the row disabled and silent, like the other rows
     * of this screen.
     *
     * @returns {Promise<void>} resolved once read or given up
     */
    read() {
      return this.$emailConnectorCommonService.getRemoteContentSettings()
        .then(settings => this.apply(settings))
        .catch(() => null);
    },
    /**
     * Stores the switch as it stands on screen; a refused save puts it back and says so.
     *
     * @returns {Promise<void>} resolved once saved or refused
     */
    saveBlocking() {
      return this.save(() => this.$emailConnectorCommonService.saveRemoteContentBlocking(this.blockRemoteContent));
    },
    /**
     * Runs one save and shows what the server kept, or puts the screen back.
     *
     * @param {Function} request the save, answering the choices as they now stand
     * @returns {Promise<void>} resolved once saved or refused
     */
    save(request) {
      this.saving = true;
      return request()
        .then(settings => this.apply(settings))
        .catch(() => {
          this.apply(this.stored);
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.remoteContent.saveError'), 'error');
        })
        .finally(() => this.saving = false);
    },
    /**
     * Shows the choices as the server answered them.
     *
     * @param {Object} settings {blockRemoteContent, trustedSenders}
     * @returns {void}
     */
    apply(settings) {
      if (!settings) {
        return;
      }
      this.stored = settings;
      this.blockRemoteContent = settings.blockRemoteContent !== false;
      this.trustedSenders = settings.trustedSenders || [];
      this.loaded = true;
    },
  },
};
</script>
