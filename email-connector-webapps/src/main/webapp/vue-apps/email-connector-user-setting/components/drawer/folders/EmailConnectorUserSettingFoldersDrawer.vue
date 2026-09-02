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
    id="userSettingFoldersDrawer"
    ref="foldersDrawer"
    v-model="drawer"
    :loading="loading"
    right
    allow-expand
    @closed="close">
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.folders.drawer.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <!-- The two numbers that bound the feature, said where the switches are: how
           many folders may be mirrored (live, so the user sees the slot they are about
           to use) and how deep each mirror goes. Both come from the server, because
           both are tunable per deployment. -->
      <div class="px-4 pt-4 pb-2 text-caption text-sub-title">
        {{ $t('UserSettings.emailConnector.folders.cap', { 0: enabledCount, 1: maxFolders }) }}
        · {{ $t('UserSettings.emailConnector.folders.windowHint', { 0: windowSize }) }}
      </div>
      <div v-if="!loading && !customFolders.length" class="px-4 py-2 text-sub-title">
        {{ $t('UserSettings.emailConnector.folders.none') }}
      </div>
      <!-- One row per folder the user made, the name as they wrote it and the path
           under it when the folder is nested. A folder the last walk did not find says
           so and cannot be switched on, but keeps its row until the walk after confirms
           it is gone. Opting OUT is a real action: the mirrored copy is deleted. -->
      <v-list class="pa-0">
        <v-list-item
          v-for="folder in customFolders"
          :key="folder.key"
          class="height-auto">
          <v-list-item-content class="py-2">
            <v-list-item-title :class="{ 'text-sub-title': folder.missing }">
              {{ folder.displayName }}
            </v-list-item-title>
            <v-list-item-subtitle v-if="pathOf(folder) !== folder.displayName">
              {{ pathOf(folder) }}
            </v-list-item-subtitle>
            <v-list-item-subtitle v-if="folder.missing" class="error--text">
              {{ $t('UserSettings.emailConnector.folders.missing') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action>
            <v-switch
              :input-value="folder.syncEnabled"
              :loading="savingId === folder.id"
              :disabled="folder.missing || savingId !== null"
              @change="toggle(folder, $event)" />
          </v-list-item-action>
        </v-list-item>
      </v-list>
    </template>
    <template #footer>
      <div class="d-flex align-center">
        <v-btn
          :loading="refreshing"
          class="btn"
          @click="refresh">
          <v-icon size="14" class="me-2">fas fa-sync</v-icon>
          {{ $t('UserSettings.emailConnector.folders.refresh') }}
        </v-btn>
        <v-spacer />
        <v-btn
          class="btn"
          @click="close">
          {{ $t('UserSettings.emailConnector.folders.drawer.close') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    drawer: false,
    folders: [],
    maxFolders: 0,
    enabledCount: 0,
    windowSize: 0,
    loading: false,
    refreshing: false,
    savingId: null,
  }),
  computed: {
    /**
     * The user's own folders, as registered -- the built-ins are not theirs to switch.
     *
     * @returns {Array} the custom folder descriptors
     */
    customFolders() {
      return this.folders.filter(folder => folder.type === 'CUSTOM');
    },
  },
  created() {
    this.$root.$on('open-email-folders-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-folders-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on the list as the server holds it now.
     *
     * @returns {void}
     */
    open() {
      this.drawer = true;
      this.$refs.foldersDrawer.open();
      this.load(false);
    },
    /**
     * Reads the folder list, walking the mailbox first when asked.
     *
     * @param {Boolean} refresh whether to walk the mailbox before answering
     * @returns {Promise} resolved once the list is on screen
     */
    load(refresh) {
      this.loading = true;
      return this.$emailConnectorUserSettingService.getMailFolders(refresh)
        .then(list => {
          this.folders = list?.folders || [];
          this.maxFolders = list?.maxCustomFolders || 0;
          this.enabledCount = list?.enabledCustomFolders || 0;
          this.windowSize = list?.windowSize || 0;
          return list;
        })
        .catch(() => {
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.folders.error'), 'error');
          return null;
        })
        .finally(() => this.loading = false);
    },
    /**
     * Walks the mailbox's folder list now -- for the folder the user just created
     * elsewhere and does not want to wait a day for. The answer says whether the walk
     * actually ran: the list comes back either way, from the registry as it stands,
     * and "refreshed" over a mailbox that could not be reached would send the user
     * looking for a folder that was never asked about.
     *
     * @returns {void}
     */
    refresh() {
      this.refreshing = true;
      this.load(true)
        .then(list => {
          if (list?.walked) {
            this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.folders.refreshed'), 'success');
          } else if (list) {
            this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.folders.walkFailed'), 'error');
          }
        })
        .finally(() => this.refreshing = false);
    },
    /**
     * Flips one folder's mirror. The cap's refusal is the one error worth its own
     * words; anything else is the generic one. The list is re-read after a save so the
     * counter and the switch state are the server's, not a guess.
     *
     * @param {Object} folder the folder
     * @param {Boolean} enabled the new opt-in
     * @returns {void}
     */
    toggle(folder, enabled) {
      this.savingId = folder.id;
      this.$emailConnectorUserSettingService.setMailFolderMirror(folder.id, !!enabled)
        .then(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success'))
        .catch(error => {
          const message = error?.message === 'emailConnector.folder.tooMany'
            ? this.$t('UserSettings.emailConnector.folders.tooMany', { 0: this.maxFolders })
            : this.$t('UserSettings.emailConnector.folders.error');
          this.$root.$emit('alert-message', message, 'error');
        })
        .finally(() => {
          this.savingId = null;
          this.load(false);
        });
    },
    /**
     * A folder's readable path: the hierarchy separator replaced by a spaced slash.
     *
     * @param {Object} folder the folder
     * @returns {String} the path
     */
    pathOf(folder) {
      if (!folder?.path) {
        return folder?.displayName || '';
      }
      return folder.delimiter ? folder.path.split(folder.delimiter).join(' / ') : folder.path;
    },
    /**
     * Closes the drawer and tells the settings row to re-read its counter, since the
     * switches in here are what the row summarises.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
      this.$refs.foldersDrawer.close();
      this.$root.$emit('email-folders-updated');
    },
  },
};
</script>
