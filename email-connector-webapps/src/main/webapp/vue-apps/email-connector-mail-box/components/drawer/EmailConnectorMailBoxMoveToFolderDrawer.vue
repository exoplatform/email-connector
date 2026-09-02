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
  <exo-drawer
    id="emailMoveToFolderDrawer"
    ref="moveToFolderDrawer"
    v-model="drawer"
    right
    @closed="close">
    <template #title>
      <span>{{ $t('emailConnector.mailBox.list.drawer.moveTo.drawer.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <!-- The user's own mirrored folders, minus the one the messages are already in.
           A picker in a drawer rather than a submenu, because every menu this is
           reached from closes on its first click, and because on a phone the row menu
           is itself a drawer. -->
      <v-list v-if="targets.length" class="pa-0">
        <v-list-item
          v-for="folder in targets"
          :key="folder.key"
          class="ps-4 pe-3"
          @click="moveTo(folder)">
          <v-sheet
            class="d-flex"
            width="28"
            height="36">
            <v-icon
              class="icon-default-color mx-auto"
              size="16">
              fa-folder
            </v-icon>
          </v-sheet>
          <v-list-item-content class="py-1">
            <v-list-item-title>{{ label(folder) }}</v-list-item-title>
            <v-list-item-subtitle v-if="path(folder) !== label(folder)">
              {{ path(folder) }}
            </v-list-item-subtitle>
          </v-list-item-content>
        </v-list-item>
      </v-list>
      <div v-else class="pa-4 text-sub-title">
        {{ $t('emailConnector.mailBox.list.drawer.moveTo.drawer.none') }}
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      drawer: false,
      mailRemoteIds: [],
      sourceFolder: 'INBOX',
      targets: [],
    };
  },
  created() {
    this.$root.$on('open-move-to-folder-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-move-to-folder-drawer', this.open);
  },
  methods: {
    /**
     * Opens the picker for a set of messages. The folder list is read off the root at
     * this moment -- the mailbox drawer keeps it there from its last load -- so the
     * picker offers what the server listed, not a copy of its own.
     *
     * @param {Array} mailRemoteIds the IMAP UIDs, within `sourceFolder`
     * @param {String} sourceFolder the folder the messages are listed in
     * @returns {void}
     */
    open(mailRemoteIds, sourceFolder) {
      this.mailRemoteIds = mailRemoteIds || [];
      if (!this.mailRemoteIds.length) {
        return;
      }
      this.sourceFolder = sourceFolder || 'INBOX';
      this.targets = this.$emailConnectorMailBoxService.moveTargets(this.$root.mailFolders, this.sourceFolder);
      this.drawer = true;
      this.$refs.moveToFolderDrawer.open();
    },
    /**
     * Hands the move to the mailbox drawer, which owns the request, the optimistic
     * removal and the error message -- exactly as archive and delete are handed over.
     *
     * @param {Object} folder the chosen target
     * @returns {void}
     */
    moveTo(folder) {
      this.$root.$emit('move-email', this.mailRemoteIds, folder.key);
      this.$refs.moveToFolderDrawer.close();
    },
    /**
     * A folder's name, through the one labelling function.
     *
     * @param {Object} folder the folder
     * @returns {String} what to show
     */
    label(folder) {
      return this.$emailConnectorMailBoxService.folderLabel(folder, this.$t.bind(this));
    },
    /**
     * A folder's readable path.
     *
     * @param {Object} folder the folder
     * @returns {String} the path
     */
    path(folder) {
      return this.$emailConnectorMailBoxService.folderPath(folder);
    },
    close() {
      this.drawer = false;
      this.mailRemoteIds = [];
    },
  },
};
</script>
