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
       mailbox with an Inbox-only share, or remove it. -->
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
          <v-list-item-title>{{ $t('UserSettings.emailConnector.sharing.extend') }}</v-list-item-title>
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
    // Whether the share covers the Inbox only and may be extended (EXO-90548).
    canExtend: { type: Boolean, default: false },
  },
  data: () => ({ PRESETS }),
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
