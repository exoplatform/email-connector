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
           created as a side effect. -->
      <v-btn
        :title="$t('UserSettings.emailConnector.folders.create')"
        icon
        @click="openCreate">
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
          <v-list-item-action class="flex-row align-center">
            <v-btn
              :title="$t('UserSettings.emailConnector.folders.rename')"
              icon
              :disabled="savingId !== null"
              @click="openRename(folder)">
              <v-icon size="16">fas fa-pen</v-icon>
            </v-btn>
            <v-btn
              :title="$t('UserSettings.emailConnector.folders.delete')"
              icon
              :disabled="savingId !== null"
              @click="openDelete(folder)">
              <v-icon size="16">fas fa-trash</v-icon>
            </v-btn>
            <v-switch
              :input-value="folder.syncEnabled"
              :loading="savingId === folder.id"
              :disabled="folder.missing || savingId !== null"
              @change="toggle(folder, $event)" />
          </v-list-item-action>
        </v-list-item>
      </v-list>
      <!-- The dialogs live INSIDE the content slot, not beside it. exo-drawer
           declares only named slots (title, titleIcons, content, footer): anything
           placed as a direct child of the drawer lands in a default slot it does
           not render, so it is silently dropped -- no warning, no error, the ref
           simply never exists and the click does nothing. -->
    <exo-confirm-dialog
      ref="deleteConfirmDialog"
      :title="$t('UserSettings.emailConnector.folders.delete.confirm.title')"
      :message="deleteConfirmMessage"
      :ok-label="$t('UserSettings.emailConnector.folders.delete')"
      :cancel-label="$t('UserSettings.emailConnector.folders.cancel')"
      @ok="doDelete" />
    <!-- Create and rename share one small prompt: a name, validated by the server the
         same way (blank, too long, nesting the mailbox's own separator, a provider's
         reserved namespace, a name already used), and one button that runs whichever
         action is open. Standard Vuetify form pieces, nothing bespoke. -->
    <v-dialog v-model="nameDialog" max-width="480" @keydown.esc="closeNameDialog">
      <v-card v-if="nameDialog">
        <v-card-title>{{ nameDialogTitle }}</v-card-title>
        <v-card-text>
          <v-form ref="nameForm" @submit.prevent="submitNameDialog">
            <v-text-field
              v-model="nameInput"
              autofocus
              :maxlength="maxFolderNameLength"
              :label="$t('UserSettings.emailConnector.folders.name.label')"
              :error-messages="nameError"
              @input="nameError = ''"
              @keydown.enter="submitNameDialog" />
          </v-form>
        </v-card-text>
        <v-card-actions>
          <v-spacer />
          <v-btn text @click="closeNameDialog">
            {{ $t('UserSettings.emailConnector.folders.cancel') }}
          </v-btn>
          <v-btn
            color="primary"
            text
            :loading="nameSaving"
            :disabled="!nameInput || !nameInput.trim()"
            @click="submitNameDialog">
            {{ nameDialogAction }}
          </v-btn>
        </v-card-actions>
      </v-card>
    </v-dialog>
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
    <!-- The one write this drawer offers with no undo built for it: the confirmation
         names the folder, because "delete" here means gone from every client the
         user owns, not moved to a Trash this screen could offer to restore from. -->
  </exo-drawer>
</template>

<script>
// The registry's own bound (EmailFolderService#MAX_FOLDER_NAME_LENGTH), mirrored
// here so a name too long is stopped at the keyboard rather than only after a round
// trip -- the field's own maxlength enforces it, this is the constant the two share.
const MAX_FOLDER_NAME_LENGTH = 255;

// The server's message codes this dialog knows how to say in the user's own words;
// anything else falls back to the generic "could not save" sentence.
const NAME_ERROR_KEYS = {
  'emailConnector.folder.name.blank': 'UserSettings.emailConnector.folders.name.error.blank',
  'emailConnector.folder.name.tooLong': 'UserSettings.emailConnector.folders.name.error.tooLong',
  'emailConnector.folder.name.nested': 'UserSettings.emailConnector.folders.name.error.nested',
  'emailConnector.folder.name.reserved': 'UserSettings.emailConnector.folders.name.error.reserved',
  'emailConnector.folder.name.duplicate': 'UserSettings.emailConnector.folders.name.error.duplicate',
  'emailConnector.folder.createFailed': 'UserSettings.emailConnector.folders.create.error',
  'emailConnector.folder.renameFailed': 'UserSettings.emailConnector.folders.rename.error',
};

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
    maxFolderNameLength: MAX_FOLDER_NAME_LENGTH,
    // The one prompt both Create and Rename open: which action is live, and (for a
    // rename) which folder it targets -- null means Create.
    nameDialog: false,
    nameAction: null,
    nameTarget: null,
    nameInput: '',
    nameError: '',
    nameSaving: false,
    // The folder a delete confirmation is pending on.
    deleteTarget: null,
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
     * The name prompt's title: Create when no folder is targeted, Rename when one is.
     *
     * @returns {String} the localized title
     */
    nameDialogTitle() {
      return this.nameAction === 'rename' ? this.$t('UserSettings.emailConnector.folders.rename.title')
        : this.$t('UserSettings.emailConnector.folders.create.title');
    },
    /**
     * The name prompt's submit button label, matching the title.
     *
     * @returns {String} the localized label
     */
    nameDialogAction() {
      return this.nameAction === 'rename' ? this.$t('UserSettings.emailConnector.folders.rename')
        : this.$t('UserSettings.emailConnector.folders.create');
    },
    /**
     * The delete confirmation's message, naming the folder about to be destroyed --
     * the last point at which the user can still say no.
     *
     * @returns {String} the localized message
     */
    deleteConfirmMessage() {
      return this.$t('UserSettings.emailConnector.folders.delete.confirm.message', { 0: this.deleteTarget?.displayName || '' });
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
        });
    },
    /**
     * Opens the prompt on Create -- an empty name, over the folder the user is about
     * to make on their own mail server.
     *
     * @returns {void}
     */
    openCreate() {
      this.nameAction = 'create';
      this.nameTarget = null;
      this.nameInput = '';
      this.nameError = '';
      this.nameDialog = true;
    },
    /**
     * Opens the prompt on Rename, pre-filled with the folder's current name.
     *
     * @param {Object} folder the folder to rename
     * @returns {void}
     */
    openRename(folder) {
      this.nameAction = 'rename';
      this.nameTarget = folder;
      this.nameInput = folder?.displayName || '';
      this.nameError = '';
      this.nameDialog = true;
    },
    /**
     * Closes the prompt without saving.
     *
     * @returns {void}
     */
    closeNameDialog() {
      this.nameDialog = false;
      this.nameTarget = null;
    },
    /**
     * Runs whichever action the prompt is open on. The server's own message code
     * comes back as the field's error when the name itself is the problem (blank, too
     * long, nesting, reserved, a duplicate); a create or rename that failed on the
     * server for another reason gets the toast the rest of this screen uses.
     *
     * @returns {void}
     */
    submitNameDialog() {
      const name = (this.nameInput || '').trim();
      if (!name) {
        return;
      }
      this.nameSaving = true;
      const action = this.nameAction === 'rename'
        ? this.$emailConnectorUserSettingService.renameMailFolder(this.nameTarget.id, name)
        : this.$emailConnectorUserSettingService.createMailFolder(name);
      action
        .then(() => {
          this.closeNameDialog();
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success');
          this.load(false);
        })
        .catch(error => {
          const key = NAME_ERROR_KEYS[error?.message];
          if (key && key.indexOf('.name.error.') >= 0) {
            // The name itself is the problem: said right at the field, not as a toast
            // that has already scrolled away by the time the user looks back at it.
            this.nameError = this.$t(key, { 0: this.maxFolderNameLength });
          } else {
            this.$root.$emit('alert-message', this.$t(key || 'UserSettings.emailConnector.folders.error'), 'error');
          }
        })
        .finally(() => this.nameSaving = false);
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
      this.$emailConnectorUserSettingService.deleteMailFolder(folder.id)
        .then(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.folders.delete.done'), 'success'))
        .catch(error => {
          const message = error?.message === 'emailConnector.folder.notEmpty'
            ? this.$t('UserSettings.emailConnector.folders.delete.notEmpty')
            : this.$t('UserSettings.emailConnector.folders.error');
          this.$root.$emit('alert-message', message, 'error');
        })
        .finally(() => {
          this.savingId = null;
          this.deleteTarget = null;
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
