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
  <!-- "Folders and access" (EXO-90556): what one person may do in each of the owner's
       folders, as the mail server holds it now -- one GETACL per folder, read when this
       opens, never from what eXo recorded. A second-level drawer over the sharing list,
       the way the invitation sits over it. Only offered on a mail server that shares
       folder by folder; a server that shares a whole mailbox at once never shows it. -->
  <exo-drawer
    id="userSettingSharingFoldersDrawer"
    ref="foldersDrawer"
    v-model="drawer"
    :loading="loading || saving"
    right
    @closed="close">
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.sharing.folders.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <div class="pa-4">
        <!-- The consent, said before the list: what each choice lets this person do. -->
        <div class="text-wrap text-color mb-2">{{ $t('UserSettings.emailConnector.sharing.folders.intro', { 0: personName }) }}</div>
        <div class="caption text-sub-title text-wrap mb-3">{{ $t('UserSettings.emailConnector.sharing.folders.inherit') }}</div>
        <div v-if="loaded && !folders.length" class="text-sub-title">{{ $t('UserSettings.emailConnector.sharing.folders.empty') }}</div>
        <email-connector-user-setting-folder-access-list
          :folders="folders"
          :choices="choices"
          :results="results"
          :inbox-label="inboxLabel"
          :disabled="saving"
          @change="choose" />
        <div v-if="truncated" class="caption text-sub-title text-wrap mt-2">
          {{ $t('UserSettings.emailConnector.sharing.folders.truncated') }}
        </div>
        <v-alert
          v-if="unsharing"
          dense
          outlined
          type="warning"
          class="caption text-wrap mt-3">
          {{ $t('UserSettings.emailConnector.sharing.folders.unsharing', { 0: personName }) }}
        </v-alert>
      </div>
      <!-- Setting a preset REPLACES the person's entry on that folder: on an access set
           in the mail server's own interface, or with letters no preset names, that
           drops whatever else it held, so it is asked first (PO decision P-6). -->
      <exo-confirm-dialog
        ref="replaceConfirmDialog"
        :title="$t('UserSettings.emailConnector.sharing.replace.confirm.title')"
        :message="$t('UserSettings.emailConnector.sharing.replace.confirm.message')"
        :ok-label="$t('UserSettings.emailConnector.sharing.replace.confirm.ok')"
        :cancel-label="$t('UserSettings.emailConnector.sharing.cancel')"
        @ok="save" />
    </template>
    <template #footer>
      <div class="d-flex align-center">
        <v-spacer />
        <v-btn class="btn me-2" @click="close">
          {{ $t('UserSettings.emailConnector.sharing.cancel') }}
        </v-btn>
        <v-btn
          :loading="saving"
          :disabled="!changed || loading"
          class="btn btn-primary"
          @click="askSave">
          {{ $t('UserSettings.emailConnector.sharing.folders.save') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import { changesOf, chooseWithDescendants, hasChanges } from '../../js/EmailConnectorFolderAccess.js';
import EmailConnectorUserSettingFolderAccessList from './EmailConnectorUserSettingFolderAccessList.vue';

export default {
  components: {
    'email-connector-user-setting-folder-access-list': EmailConnectorUserSettingFolderAccessList,
  },
  data: () => ({
    drawer: false,
    loading: false,
    loaded: false,
    saving: false,
    // The owner's list entry this drawer is for: {identifier, granteeId, delegation, preset}.
    grantee: null,
    folders: [],
    truncated: false,
    // The access the server said per folder, and the access chosen, by full name.
    initial: {},
    choices: {},
    // What became of each folder at the last save, by full name.
    results: {},
  }),
  computed: {
    /**
     * @returns {String} who this drawer is about, as the owner's list names them
     */
    personName() {
      return this.grantee?.granteeId || this.grantee?.identifier || '';
    },
    /**
     * What INBOX's row says: the share's own access there, changed from the access menu.
     *
     * @returns {String} the label
     */
    inboxLabel() {
      const preset = this.grantee?.preset;
      return preset === 'READER' || preset === 'EDITOR'
        ? this.$t(`UserSettings.emailConnector.sharing.preset.${preset}`)
        : this.$t('UserSettings.emailConnector.sharedWithMe.preset.CUSTOM');
    },
    /**
     * @returns {Boolean} whether a save would change anything
     */
    changed() {
      return hasChanges(this.folders, this.initial, this.choices);
    },
    /**
     * Whether the owner is about to stop sharing a folder that is shared now: said
     * before the save, because the person's copy of it goes too.
     *
     * @returns {Boolean} true when a folder goes from shared to not shared
     */
    unsharing() {
      return this.folders.some(folder => folder.editable && this.choices[folder.folder] === 'NONE'
        && this.initial[folder.folder] && this.initial[folder.folder] !== 'NONE');
    },
    /**
     * Whether the save replaces an access this screen does not name: a share made in the
     * mail server's own interface, or a folder whose letters read as no preset.
     *
     * @returns {Boolean} true when the replace confirmation is owed
     */
    replaces() {
      const serverMade = this.grantee?.delegation?.origin === 'SERVER';
      return serverMade || this.folders.some(folder => folder.editable && folder.readable && !folder.access
        && this.choices[folder.folder]);
    },
  },
  created() {
    this.$root.$on('open-email-sharing-folders-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-sharing-folders-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on one person's access, read from the mail server now.
     *
     * @param {Object} grantee the owner's list entry
     * @returns {void}
     */
    open(grantee) {
      this.grantee = grantee;
      this.folders = [];
      this.initial = {};
      this.choices = {};
      this.results = {};
      this.loaded = false;
      this.drawer = true;
      this.$refs.foldersDrawer.open();
      this.load();
    },
    /**
     * Reads each folder's access from the mail server.
     *
     * @returns {Promise} resolved once the list is on screen
     */
    load() {
      const id = this.grantee?.delegation?.id;
      if (!id) {
        return Promise.resolve();
      }
      this.loading = true;
      return this.$emailConnectorUserSettingService.getDelegationFolders(id)
        .then(answer => {
          this.folders = answer?.folders || [];
          this.truncated = !!answer?.truncated;
          const initial = {};
          this.folders.forEach(folder => initial[folder.folder] = folder.access || null);
          this.initial = initial;
          this.choices = Object.assign({}, initial);
          this.loaded = true;
        })
        .catch(error => this.showAlert(this.messageOf(error, 'UserSettings.emailConnector.sharing.folders.error'), 'error'))
        .finally(() => this.loading = false);
    },
    /**
     * The owner chose an access for a folder: it, and the folders inside it.
     *
     * @param {Object} change {folder, access}
     * @returns {void}
     */
    choose(change) {
      this.choices = chooseWithDescendants(this.folders, this.choices, change.folder, change.access);
    },
    /**
     * Saves, after the replace confirmation when one is owed.
     *
     * @returns {void}
     */
    askSave() {
      if (this.replaces) {
        this.$refs.replaceConfirmDialog.open();
        return;
      }
      this.save();
    },
    /**
     * Writes the choices on the mail server, folder by folder, and shows what became of
     * each: a folder refused keeps its row's error until the next save.
     *
     * @returns {void}
     */
    save() {
      const id = this.grantee?.delegation?.id;
      const changes = changesOf(this.folders, this.initial, this.choices);
      if (!id || !changes.length) {
        return;
      }
      this.saving = true;
      this.$emailConnectorUserSettingService.setDelegationFolders(id, changes)
        .then(answer => {
          const results = {};
          (answer?.results || []).forEach(result => results[result.folder] = result.outcome);
          this.results = results;
          const failed = Object.values(results).some(outcome => outcome !== 'DONE');
          this.showAlert(this.$t(failed ? 'UserSettings.emailConnector.sharing.folders.partial' : 'UserSettings.emailConnector.sharing.folders.saved'),
            failed ? 'warning' : 'success');
          // The list behind this drawer is the mail server's; it just changed.
          this.$root.$emit('email-delegation-granted');
          this.$root.$emit('email-delegations-updated');
          return this.load();
        })
        .catch(error => this.showAlert(this.messageOf(error, 'UserSettings.emailConnector.sharing.folders.save.error'), 'error'))
        .finally(() => this.saving = false);
    },
    /**
     * Shows a message on the platform's toast.
     *
     * @param {String} message the message
     * @param {String} type success, warning or error
     * @returns {void}
     */
    showAlert(message, type) {
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: type, alertMessage: message}}));
    },
    /**
     * The server's own message code in the user's words when the bundle has it, the
     * screen's general sentence otherwise.
     *
     * @param {Error} error the rejection
     * @param {String} fallbackKey the general sentence's key
     * @returns {String} the localized message
     */
    messageOf(error, fallbackKey) {
      const code = error?.message;
      return code && typeof this.$te === 'function' && this.$te(code) ? this.$t(code) : this.$t(fallbackKey);
    },
    /**
     * Closes this drawer, leaving the sharing list open behind it.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
      this.$refs.foldersDrawer.close();
    },
  },
};
</script>
