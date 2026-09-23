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
  <!-- The signature: one switch and an editor behind a drawer, following the
       address-book rows' shape; its own component since EXO-90559. The switch and the
       Edit button write the same stored document, so the switch sends the stored
       markup back along with itself rather than silently blanking it. -->
  <v-list-item>
    <v-list-item-content>
      <v-list-item-title class="text-color">
        {{ $t('UserSettings.emailConnector.signature.title') }}
      </v-list-item-title>
      <v-list-item-subtitle>
        {{ $t('UserSettings.emailConnector.signature.description') }}
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action class="d-flex flex-row align-center">
      <v-btn
        icon
        :title="$t('UserSettings.emailConnector.signature.edit.tooltip')"
        @click="$root.$emit('open-email-signature-drawer')">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
      <v-switch
        v-model="signatureEnabled"
        :loading="savingSignature"
        class="ms-2"
        @change="saveSignatureEnabled" />
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  data: () => ({
    signatureEnabled: true,
    // The stored custom markup, carried so the enable switch can send it back
    // unchanged: the switch and the editor share one stored document, and a
    // toggle that omitted the markup would silently erase it.
    signatureCustomHtml: null,
    savingSignature: false,
  }),
  created() {
    this.readSignature();
    // The drawer edits the same stored document this row's switch rides on, so
    // its saves must be read back here or the switch would write stale markup.
    this.$root.$on('email-signature-updated', this.readSignature);
  },
  beforeDestroy() {
    this.$root.$off('email-signature-updated', this.readSignature);
  },
  methods: {
    /**
     * Reads the signature preference this row's switch shows and protects.
     * Failing is silent, like the address-book status: an unreadable preference
     * is not worth an error banner over the whole settings screen.
     *
     * @returns {void}
     */
    readSignature() {
      this.$emailConnectorCommonService.getEmailSignature()
        .then(signature => {
          this.signatureEnabled = signature?.enabled !== false;
          this.signatureCustomHtml = signature?.customHtml || null;
        })
        .catch(() => null);
    },
    /**
     * Stores the switch, sending the stored markup back with it so a toggle
     * never erases what the editor wrote.
     *
     * @returns {void}
     */
    saveSignatureEnabled() {
      this.savingSignature = true;
      this.$emailConnectorCommonService.saveEmailSignature({
        enabled: this.signatureEnabled,
        customHtml: this.signatureCustomHtml,
      })
        .then(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success'))
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.error'), 'error'))
        .finally(() => this.savingSignature = false);
    },
  },
};
</script>
