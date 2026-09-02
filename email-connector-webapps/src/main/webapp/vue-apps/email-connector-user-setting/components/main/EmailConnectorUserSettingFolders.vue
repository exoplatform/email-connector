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
  <!-- One row, and a drawer behind it: the list of a mailbox's folders is unbounded
       and each switch in it is an action with consequences (opting out deletes the
       mirrored copy), so it gets a surface of its own rather than a scroll of switches
       in the middle of the other settings. The row says the one number worth knowing
       at a glance and opens the drawer the way the connector row opens its own. -->
  <v-list-item>
    <v-list-item-content>
      <v-list-item-title class="text-color">
        {{ $t('UserSettings.emailConnector.folders.title') }}
      </v-list-item-title>
      <v-list-item-subtitle>
        {{ $t('UserSettings.emailConnector.folders.description') }}
      </v-list-item-subtitle>
      <v-list-item-subtitle v-if="loaded" class="caption text-sub-title mt-1">
        {{ $t('UserSettings.emailConnector.folders.cap', { 0: enabledCount, 1: maxFolders }) }}
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action>
      <v-btn
        icon
        :title="$t('UserSettings.emailConnector.folders.edit.tooltip')"
        @click="$root.$emit('open-email-folders-drawer')">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  data: () => ({
    loaded: false,
    maxFolders: 0,
    enabledCount: 0,
  }),
  created() {
    this.readCounter();
    // The drawer's switches are what this row summarises, so its close re-reads it.
    this.$root.$on('email-folders-updated', this.readCounter);
  },
  beforeDestroy() {
    this.$root.$off('email-folders-updated', this.readCounter);
  },
  methods: {
    /**
     * Reads the one number the row shows. Failing is silent, like the address-book
     * status: an unreadable counter is not worth an error banner over the whole
     * settings screen.
     *
     * @returns {void}
     */
    readCounter() {
      this.$emailConnectorUserSettingService.getMailFolders(false)
        .then(list => {
          this.maxFolders = list?.maxCustomFolders || 0;
          this.enabledCount = list?.enabledCustomFolders || 0;
          this.loaded = true;
        })
        .catch(() => null);
    },
  },
};
</script>
