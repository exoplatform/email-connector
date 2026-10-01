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
  <!-- Images in received mail (EXO-90841): whether the images a mail fetches from the
       internet wait until asked for, and the senders whose mail always shows them,
       each of which can be forgotten. A one-line row under Advanced settings, like the
       read receipts, that opens on its choices. -->
  <div class="remote-content-settings">
    <v-list-item>
      <v-list-item-content>
        <v-list-item-title class="text-color">
          {{ $t('UserSettings.emailConnector.remoteContent.title') }}
        </v-list-item-title>
        <v-list-item-subtitle>
          {{ summary }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action>
        <v-btn
          :aria-expanded="expanded ? 'true' : 'false'"
          :title="$t('UserSettings.emailConnector.remoteContent.edit.tooltip')"
          aria-controls="emailConnectorRemoteContentChoices"
          icon
          @click="expanded = !expanded">
          <v-icon size="16" class="icon-default-color">
            {{ expanded ? 'fa-chevron-up' : 'fa-chevron-down' }}
          </v-icon>
        </v-btn>
      </v-list-item-action>
    </v-list-item>
    <div v-show="expanded" id="emailConnectorRemoteContentChoices">
      <v-list-item>
        <v-list-item-content class="ps-4">
          <v-list-item-subtitle class="text-wrap">
            {{ $t('UserSettings.emailConnector.remoteContent.block') }}
          </v-list-item-subtitle>
        </v-list-item-content>
        <v-list-item-action>
          <v-switch
            v-model="blockRemoteContent"
            :disabled="!loaded || saving"
            :aria-label="$t('UserSettings.emailConnector.remoteContent.block')"
            class="remote-content-block"
            @change="saveBlocking" />
        </v-list-item-action>
      </v-list-item>
      <v-list-item
        v-for="sender in trustedSenders"
        :key="sender"
        class="remote-content-trusted-sender">
        <v-list-item-content class="ps-4">
          <v-list-item-subtitle class="text-truncate">
            {{ sender }}
          </v-list-item-subtitle>
        </v-list-item-content>
        <v-list-item-action>
          <v-btn
            :disabled="saving"
            :title="$t('UserSettings.emailConnector.remoteContent.forget', { 0: sender })"
            :aria-label="$t('UserSettings.emailConnector.remoteContent.forget', { 0: sender })"
            icon
            small
            @click="forget(sender)">
            <v-icon size="14" class="icon-default-color">fa-times</v-icon>
          </v-btn>
        </v-list-item-action>
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
     * Stops trusting a sender.
     *
     * @param {String} sender the sender's address
     * @returns {Promise<void>} resolved once saved or refused
     */
    forget(sender) {
      return this.save(() => this.$emailConnectorCommonService.setSenderTrusted(sender, false));
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
