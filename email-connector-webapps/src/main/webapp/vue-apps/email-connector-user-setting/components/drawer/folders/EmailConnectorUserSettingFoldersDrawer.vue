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
    <template #titleIcons>
      <!-- The one explicit act this add-on ever writes to the mail server on its own
           behalf: the user asked for exactly this folder, by name, on this screen --
           see EmailBoxService#createCustomFolder for the distinction from a folder
           created as a side effect. Opens the second-level name drawer -- see
           EmailConnectorUserSettingFolderNameDrawer -- never a popup. -->
      <v-btn
        :title="$t('UserSettings.emailConnector.folders.create')"
        icon
        @click="openCreate(null)">
        <v-icon size="18">fas fa-plus</v-icon>
      </v-btn>
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
      <!-- One row per folder the user made, as a tree (EXO-90839): a folder inside
           another indented under it, a folder with folders inside collapsing them. A
           folder at the top whose parent is not listed shows its path. A folder the
           last walk did not find says so and cannot be switched on, but keeps its row
           until the walk after confirms it is gone. Opting OUT is a real action: the
           mirrored copy is deleted. -->
      <v-list class="pa-0">
        <v-list-item
          v-for="row in visibleRows"
          :key="row.folder.key"
          :style="{ paddingInlineStart: `${16 + Math.min(row.depth, 6) * 20}px` }"
          class="height-auto">
          <v-btn
            v-if="row.hasChildren"
            :title="toggleLabel(row.folder)"
            :aria-label="toggleLabel(row.folder)"
            :aria-expanded="String(!collapsed[row.folder.key])"
            class="ms-n2 me-1"
            icon
            x-small
            @click="toggleCollapsed(row.folder.key)">
            <v-icon size="12" class="icon-default-color">
              {{ collapsed[row.folder.key] ? 'fa-chevron-right' : 'fa-chevron-down' }}
            </v-icon>
          </v-btn>
          <v-list-item-content class="py-2">
            <v-list-item-title :class="{ 'text-sub-title': row.folder.missing }" :title="pathOf(row.folder)">
              {{ row.showPath ? row.pathLabel : row.folder.displayName }}
            </v-list-item-title>
            <v-list-item-subtitle v-if="row.folder.missing" class="error--text">
              {{ $t('UserSettings.emailConnector.folders.missing') }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="flex-row align-center">
            <!-- A folder inside this one: the name drawer, its parent chosen. -->
            <v-btn
              :title="$t('UserSettings.emailConnector.folders.createInside')"
              :aria-label="$t('UserSettings.emailConnector.folders.createInside')"
              icon
              :disabled="row.folder.missing || !row.folder.delimiter || savingId !== null"
              @click="openCreate(row.folder)">
              <v-icon size="16">fas fa-folder-plus</v-icon>
            </v-btn>
            <v-btn
              :title="$t('UserSettings.emailConnector.folders.rename')"
              icon
              :disabled="savingId !== null"
              @click="openRename(row.folder)">
              <v-icon size="16">fas fa-pen</v-icon>
            </v-btn>
            <v-btn
              :title="$t('UserSettings.emailConnector.folders.delete')"
              icon
              :disabled="savingId !== null"
              @click="openDelete(row.folder)">
              <v-icon size="16">fas fa-trash</v-icon>
            </v-btn>
            <v-switch
              :input-value="row.folder.syncEnabled"
              :loading="savingId === row.folder.id"
              :disabled="row.folder.missing || savingId !== null"
              @change="toggle(row.folder, $event)" />
          </v-list-item-action>
        </v-list-item>
      </v-list>
      <!-- The confirm dialog lives INSIDE the content slot, not beside it. exo-drawer
           declares only named slots (title, titleIcons, content, footer): anything
           placed as a direct child of the drawer lands in a default slot it does
           not render, so it is silently dropped -- no warning, no error, the ref
           simply never exists and the click does nothing. -->
      <!-- The one write this drawer offers with no undo built for it: the confirmation
           names the folder, because "delete" here means gone from every client the
           user owns, not moved to a Trash this screen could offer to restore from.
           A confirmation, not a form -- this one stays a dialog; Create and Rename
           are the second-level drawer (see #titleIcons and openRename below). -->
      <exo-confirm-dialog
        ref="deleteConfirmDialog"
        :title="$t('UserSettings.emailConnector.folders.delete.confirm.title')"
        :message="deleteConfirmMessage"
        :ok-label="$t('UserSettings.emailConnector.folders.delete')"
        :cancel-label="$t('UserSettings.emailConnector.folders.cancel')"
        @ok="doDelete" />
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
import { buildFolderTree, descendantKeys, folderPathLabel, readCollapsedFolders, toggleCollapsedFolder, visibleFolderRows } from '../../../../email-connector-mail-box/js/EmailConnectorFolderTree.js';

// The server's refusals of a delete the user can act on, in their own words.
const DELETE_ERROR_KEYS = {
  'emailConnector.folder.notEmpty': 'UserSettings.emailConnector.folders.delete.notEmpty',
  'emailConnector.folder.subFolderNotEmpty': 'UserSettings.emailConnector.folders.delete.subFolderNotEmpty',
  'emailConnector.folder.subFolderBuiltIn': 'UserSettings.emailConnector.folders.delete.subFolderBuiltIn',
};

export default {
  data: () => ({
    collapsed: readCollapsedFolders(),
    drawer: false,
    folders: [],
    maxFolders: 0,
    enabledCount: 0,
    windowSize: 0,
    loading: false,
    refreshing: false,
    savingId: null,
    // The folder a delete confirmation is pending on.
    deleteTarget: null,
    // Whether the pending confirmation is the second one, asked when the server found
    // folders inside it that this list does not show (not listed as the user's own).
    deleteUnlistedSubFolders: false,
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
    /**
     * The delete confirmation's message, naming the folder about to be destroyed --
     * the last point at which the user can still say no.
     *
     * @returns {String} the localized message
     */
    deleteConfirmMessage() {
      // A folder with folders inside says that they go too, and how many (EXO-90839).
      if (this.deleteUnlistedSubFolders) {
        return this.$t('UserSettings.emailConnector.folders.delete.confirm.messageWithUnlistedSubFolders',
          { 0: this.deleteTarget?.displayName || '' });
      }
      return this.deleteTargetSubFolders
        ? this.$t('UserSettings.emailConnector.folders.delete.confirm.messageWithSubFolders',
          { 0: this.deleteTarget?.displayName || '', 1: this.deleteTargetSubFolders })
        : this.$t('UserSettings.emailConnector.folders.delete.confirm.message', { 0: this.deleteTarget?.displayName || '' });
    },
    /**
     * How many of the listed folders are inside the folder a delete is pending on.
     *
     * @returns {Number} the count, 0 for none
     */
    deleteTargetSubFolders() {
      return this.deleteTarget ? descendantKeys(this.customFolders, this.deleteTarget).length : 0;
    },
    /**
     * The user's folders as a tree, a collapsed one's folders left out.
     *
     * @returns {Array} the rows ({folder, depth, hasChildren, showPath})
     */
    visibleRows() {
      return visibleFolderRows(buildFolderTree(this.customFolders), this.collapsed);
    },
  },
  created() {
    this.$root.$on('open-email-folders-drawer', this.open);
    // The second-level name drawer (create/rename) saved something: reload while
    // this drawer stays open behind it -- it was never closed to make room for it.
    this.$root.$on('email-folders-list-changed', this.onFoldersListChanged);
  },
  beforeDestroy() {
    this.$root.$off('open-email-folders-drawer', this.open);
    this.$root.$off('email-folders-list-changed', this.onFoldersListChanged);
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
     * Reloads the list once the name drawer created or renamed a folder -- only
     * while this drawer is the one open (the name drawer is reached from nowhere
     * else), so a reload while closed is not worth paying for.
     *
     * @returns {void}
     */
    onFoldersListChanged() {
      if (this.drawer) {
        this.load(false);
      }
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
          } else if (list?.customFoldersEnabled) {
            // Only when the mailbox was actually asked: switched off, nothing was.
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
          // For whoever lists the folders beside this drawer -- the full-screen
          // mailbox's folder column (EXO-90415).
          this.$root.$emit('email-folders-saved');
        });
    },
    /**
     * Opens the second-level name drawer on Create -- see
     * EmailConnectorUserSettingFolderNameDrawer -- at the top level, or inside a folder.
     * This drawer stays open behind it.
     *
     * @param {Object} parent the folder to create it in, or null for the top level
     * @returns {void}
     */
    openCreate(parent) {
      this.$root.$emit('open-email-folder-name-drawer', { mode: 'create', parent, folders: this.customFolders });
    },
    /**
     * Opens the second-level name drawer on Rename, pre-filled with the folder's
     * current name and parent -- changing the parent moves it. This drawer stays open
     * behind it.
     *
     * @param {Object} folder the folder to rename
     * @returns {void}
     */
    openRename(folder) {
      this.$root.$emit('open-email-folder-name-drawer', { mode: 'rename', folder, folders: this.customFolders });
    },
    /**
     * What the collapse button of a folder says: the action it takes, with the folder's name.
     *
     * @param {Object} folder the folder
     * @returns {String} the label
     */
    toggleLabel(folder) {
      const key = this.collapsed[folder.key] ? 'UserSettings.emailConnector.folders.expand'
        : 'UserSettings.emailConnector.folders.collapse';
      return this.$t(key, { 0: folder.displayName });
    },
    /**
     * Collapses or expands the folders inside a folder.
     *
     * @param {String} key the folder's key
     * @returns {void}
     */
    toggleCollapsed(key) {
      this.collapsed = toggleCollapsedFolder(this.collapsed, key);
    },
    /**
     * Asks for the confirmation a delete needs -- the folder is named in it, and
     * disabled folders and enabled ones alike may be deleted (the switch is the
     * mirror, not the folder's existence).
     *
     * @param {Object} folder the folder to delete
     * @returns {void}
     */
    openDelete(folder) {
      this.deleteTarget = folder;
      this.deleteUnlistedSubFolders = false;
      this.$refs.deleteConfirmDialog.open();
    },
    /**
     * Deletes the folder the confirmation named. A refusal because the folder still
     * holds mail gets its own sentence, telling the user to empty it first rather than
     * the generic failure.
     *
     * @returns {void}
     */
    doDelete() {
      const folder = this.deleteTarget;
      if (!folder) {
        return;
      }
      this.savingId = folder.id;
      // The folders inside it go too only when the confirmation said so.
      const withSubFolders = this.deleteUnlistedSubFolders || this.deleteTargetSubFolders > 0;
      let askedAgain = false;
      this.$emailConnectorUserSettingService.deleteMailFolder(folder.id, withSubFolders)
        .then(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.folders.delete.done'), 'success'))
        .catch(error => {
          if (error?.message === 'emailConnector.folder.hasSubFolders' && !withSubFolders) {
            // Folders inside it the list does not show: asked again, saying so.
            askedAgain = true;
            this.deleteUnlistedSubFolders = true;
            this.$refs.deleteConfirmDialog.open();
            return;
          }
          const message = DELETE_ERROR_KEYS[error?.message] ? this.$t(DELETE_ERROR_KEYS[error.message])
            : this.$t('UserSettings.emailConnector.folders.error');
          this.$root.$emit('alert-message', message, 'error');
        })
        .finally(() => {
          this.savingId = null;
          if (askedAgain) {
            return;
          }
          this.deleteTarget = null;
          this.deleteUnlistedSubFolders = false;
          this.load(false);
          this.$root.$emit('email-folders-saved');
        });
    },
    /**
     * A folder's readable path: the hierarchy separator replaced by a spaced slash.
     *
     * @param {Object} folder the folder
     * @returns {String} the path
     */
    pathOf(folder) {
      return folderPathLabel(folder) || folder?.displayName || '';
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
