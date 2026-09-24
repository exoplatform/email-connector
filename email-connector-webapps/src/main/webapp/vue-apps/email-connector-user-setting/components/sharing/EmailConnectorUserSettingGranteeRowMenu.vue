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
  <!-- The owner's actions on one person's access: change it to a preset (the current
       one checked; "Set to ..." when the letters read as none), share the rest of the
       mailbox with an Inbox-only share, choose folder by folder, let them write mail in
       the owner's name, or remove it. -->
  <v-menu offset-y left>
    <template #activator="{ on, attrs }">
      <v-btn
        :aria-label="$t('UserSettings.emailConnector.sharing.menu')"
        :disabled="disabled"
        v-bind="attrs"
        icon
        x-small
        v-on="on">
        <v-icon size="14" class="icon-default-color">fa-ellipsis-v</v-icon>
      </v-btn>
    </template>
    <v-list dense>
      <v-subheader class="caption">{{ $t('UserSettings.emailConnector.sharing.changeAccess') }}</v-subheader>
      <v-list-item
        v-for="preset in PRESETS"
        :key="preset"
        @click="preset !== currentPreset && $emit('change-preset', preset)">
        <v-list-item-title>{{ presetChoiceLabel(preset) }}</v-list-item-title>
        <v-list-item-action v-if="preset === currentPreset" class="my-0">
          <v-icon size="12" color="primary">fa-check</v-icon>
        </v-list-item-action>
      </v-list-item>
      <template v-if="canExtend">
        <v-divider class="my-1" />
        <v-list-item @click="$emit('extend')">
          <v-list-item-title>{{ extendLabel }}</v-list-item-title>
        </v-list-item>
      </template>
      <!-- Folder by folder (EXO-90556): only on a mail server that shares that way. -->
      <template v-if="canChooseFolders">
        <v-divider class="my-1" />
        <v-list-item @click="$emit('folders')">
          <v-list-item-title>{{ $t('UserSettings.emailConnector.sharing.folders.menu') }}</v-list-item-title>
        </v-list-item>
      </template>
      <!-- Writing mail in the owner's name (EXO-90582): only the shapes the mail server
           is declared to accept; the current one checked. -->
      <template v-if="canSetSendMode">
        <v-divider class="my-1" />
        <v-subheader class="caption">{{ $t('UserSettings.emailConnector.sharing.sendMode.menu') }}</v-subheader>
        <v-list-item
          v-for="mode in sendModeChoices"
          :key="mode"
          @click="mode !== currentSendMode && $emit('change-send-mode', mode)">
          <v-list-item-title>{{ $t(`UserSettings.emailConnector.sharing.sendMode.${mode}`) }}</v-list-item-title>
          <v-list-item-action v-if="mode === currentSendMode" class="my-0">
            <v-icon size="12" color="primary">fa-check</v-icon>
          </v-list-item-action>
        </v-list-item>
      </template>
      <v-divider class="my-1" />
      <v-list-item @click="$emit('revoke')">
        <v-list-item-title class="error--text">{{ $t('UserSettings.emailConnector.sharing.revoke') }}</v-list-item-title>
      </v-list-item>
    </v-list>
  </v-menu>
</template>

<script>
// The presets the owner can set from here.
const PRESETS = ['READER', 'EDITOR'];

export default {
  props: {
    // READER or EDITOR, or null when the letters read as no preset.
    currentPreset: { type: String, default: null },
    disabled: { type: Boolean, default: false },
    // Whether the owner's mailbox has role folders the share does not cover yet (EXO-90548).
    canExtend: { type: Boolean, default: false },
    // What an Extend would add, said: "Share Spam too".
    extendLabel: { type: String, default: '' },
    // Whether the owner's mail server shares folder by folder (EXO-90556).
    canChooseFolders: { type: Boolean, default: false },
    // The owner's consent to this person writing mail in her name: NONE, ON_BEHALF or AS (EXO-90582).
    currentSendMode: { type: String, default: 'NONE' },
    // The shapes the owner's mail server is declared to accept: ON_BEHALF, AS.
    sendModes: { type: Array, default: () => [] },
    // Whether the consent may be set on this share at all.
    canSetSendMode: { type: Boolean, default: false },
  },
  data: () => ({ PRESETS }),
  computed: {
    /**
     * The choices offered: Not allowed, then each shape the server is declared to accept
     * -- As me only where it is. The current one stays listed, checked, so a consent the
     * server no longer accepts can still be seen and withdrawn.
     *
     * @returns {Array} NONE, ON_BEHALF, AS, as offered
     */
    sendModeChoices() {
      return ['NONE', 'ON_BEHALF', 'AS'].filter(mode => mode === 'NONE' || mode === this.currentSendMode || this.sendModes.includes(mode));
    },
  },
  methods: {
    /**
     * A preset's name in the menu: itself when the access reads as a preset, "Set to
     * ..." when the letters read as none, which is what choosing it then does.
     *
     * @param {String} preset READER or EDITOR
     * @returns {String} the label
     */
    presetChoiceLabel(preset) {
      const name = this.$t(`UserSettings.emailConnector.sharing.preset.${preset}`);
      return this.currentPreset ? name : this.$t('UserSettings.emailConnector.sharing.setTo', { 0: name });
    },
  },
};
</script>
