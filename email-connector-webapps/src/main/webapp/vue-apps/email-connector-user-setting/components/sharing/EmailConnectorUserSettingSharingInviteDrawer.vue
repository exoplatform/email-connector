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
  <!-- Giving somebody access to your mailbox (EXO-90503): a person and one of two
       right sets, and nothing else — no letter picker, because nobody can be expected
       to know what x does, and the two presets are what the mail server is asked for.
       A second-level drawer over the sharing list, the way the folder-name drawer sits
       over the folders list. -->
  <exo-drawer
    id="userSettingSharingInviteDrawer"
    ref="inviteDrawer"
    v-model="drawer"
    :loading="saving"
    right
    @closed="close">
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.sharing.invite.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <div class="pa-4">
        <!-- The platform's own people picker. Users only: a share is granted to one
             person, resolved server-side to the identifier THEIR OWN connected mailbox
             uses — a mail login is never typed here, and a space or a group has no
             mailbox to name. -->
        <identity-suggester
          ref="granteeSuggester"
          v-model="grantee"
          :labels="suggesterLabels"
          :search-options="searchOptions"
          include-users
          class="mb-4" />
        <div class="text-subtitle-2 text-color mb-2">
          {{ $t('UserSettings.emailConnector.sharing.invite.rights') }}
        </div>
        <v-radio-group v-model="preset" class="mt-0">
          <v-radio value="READER" class="align-start">
            <template #label>
              <div class="d-flex flex-column">
                <span class="text-color">{{ $t('UserSettings.emailConnector.sharing.preset.READER') }}</span>
                <span class="caption text-sub-title text-wrap">
                  {{ $t('UserSettings.emailConnector.sharing.preset.READER.description') }}
                </span>
              </div>
            </template>
          </v-radio>
          <v-radio value="EDITOR" class="align-start">
            <template #label>
              <div class="d-flex flex-column">
                <span class="text-color">{{ $t('UserSettings.emailConnector.sharing.preset.EDITOR') }}</span>
                <span class="caption text-sub-title text-wrap">
                  {{ $t('UserSettings.emailConnector.sharing.preset.EDITOR.description') }}
                </span>
              </div>
            </template>
          </v-radio>
        </v-radio-group>
        <!-- Folder by folder (EXO-90556, PO decision P-2), only on a mail server that
             shares that way: chosen before anything is written, so a folder left out is
             never shared, not shared and taken back a moment later. -->
        <template v-if="perFolder">
          <v-btn
            class="px-0 mb-2 text-none"
            color="primary"
            text
            small
            @click="toggleFolders">
            <v-icon size="12" class="me-1">{{ foldersOpen ? 'fas fa-chevron-up' : 'fas fa-chevron-down' }}</v-icon>
            {{ $t('UserSettings.emailConnector.sharing.invite.folders') }}
          </v-btn>
          <div v-if="foldersOpen" class="mb-2">
            <div class="caption text-sub-title text-wrap mb-2">{{ $t('UserSettings.emailConnector.sharing.invite.folders.hint') }}</div>
            <v-progress-linear
              v-if="foldersLoading"
              indeterminate
              color="primary" />
            <email-connector-user-setting-folder-access-list
              :folders="folders"
              :choices="choices"
              :inbox-label="$t(`UserSettings.emailConnector.sharing.preset.${preset}`)"
              :disabled="saving"
              @change="choose" />
            <div v-if="truncated" class="caption text-sub-title text-wrap mt-2">
              {{ $t('UserSettings.emailConnector.sharing.folders.truncated') }}
            </div>
            <div class="caption text-sub-title text-wrap mt-2">{{ $t('UserSettings.emailConnector.sharing.folders.inherit') }}</div>
          </div>
        </template>
        <!-- The consent, said before the button and not in a tooltip: the access is
             written on the mail server the moment this is pressed, it reaches every
             mail client the person uses and not only eXo, and declining the invitation
             will not take it back. Only this button's owner can. -->
        <v-alert
          dense
          outlined
          type="info"
          class="caption text-wrap mt-2">
          {{ consentText }}
        </v-alert>
      </div>
    </template>
    <template #footer>
      <div class="d-flex align-center">
        <v-spacer />
        <v-btn class="btn me-2" @click="close">
          {{ $t('UserSettings.emailConnector.sharing.cancel') }}
        </v-btn>
        <v-btn
          :loading="saving"
          :disabled="!granteeUsername"
          class="btn btn-primary"
          @click="share">
          {{ $t('UserSettings.emailConnector.sharing.share') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import { changesOf, chooseWithDescendants } from '../../js/EmailConnectorFolderAccess.js';
import EmailConnectorUserSettingFolderAccessList from './EmailConnectorUserSettingFolderAccessList.vue';

export default {
  components: {
    'email-connector-user-setting-folder-access-list': EmailConnectorUserSettingFolderAccessList,
  },
  data: () => ({
    drawer: false,
    saving: false,
    grantee: null,
    preset: 'READER',
    // Whether the owner's mail server shares folder by folder (EXO-90556), as the
    // sharing list read it.
    perFolder: false,
    // Whether the mail server accepts any shape of writing in the owner's name (EXO-90582).
    sendModeOffered: false,
    foldersOpen: false,
    foldersLoading: false,
    folders: [],
    truncated: false,
    // The access chosen per folder, by full name: the role folders follow the preset
    // until the owner chooses otherwise, the owner's other folders are not shared.
    choices: {},
  }),
  computed: {
    /**
     * The consent said before the Share button: with, where the mail server accepts it,
     * the sentence that writing in the owner's name is a separate choice made afterwards
     * (EXO-90582) -- a sentence that would be false on a server that accepts none.
     *
     * @returns {String} the consent
     */
    consentText() {
      return this.$t(this.sendModeOffered
        ? 'UserSettings.emailConnector.sharing.invite.consent.sendMode'
        : 'UserSettings.emailConnector.sharing.invite.consent');
    },
    /**
     * The picked person's eXo username, which is all the server is given: it resolves
     * their mail identifier from their own connected mailbox.
     *
     * @returns {String} the username, or nothing while none is picked
     */
    granteeUsername() {
      return this.grantee?.remoteId || this.grantee?.profile?.username || '';
    },
    /**
     * The suggester's query options. An object and never null on purpose: the
     * platform's suggester service dereferences this without a guard on the
     * users-only path, so a null here is a TypeError rather than a default.
     *
     * @returns {Object} the query options
     */
    searchOptions() {
      return {};
    },
    /**
     * @returns {Object} the suggester's own labels, in the user's language
     */
    suggesterLabels() {
      return {
        label: this.$t('UserSettings.emailConnector.sharing.invite.person'),
        placeholder: this.$t('UserSettings.emailConnector.sharing.invite.person.placeholder'),
        searchPlaceholder: this.$t('UserSettings.emailConnector.sharing.invite.person.search'),
        noDataLabel: this.$t('UserSettings.emailConnector.sharing.invite.person.noData'),
      };
    },
  },
  watch: {
    /**
     * The role folders that follow the preset move with it; one the owner set apart
     * stays where it is.
     *
     * @param {String} preset the new preset
     * @param {String} previous the preset before
     * @returns {void}
     */
    preset(preset, previous) {
      const choices = Object.assign({}, this.choices);
      this.folders.filter(folder => folder.role && choices[folder.folder] === previous).forEach(folder => choices[folder.folder] = preset);
      this.choices = choices;
    },
  },
  created() {
    this.$root.$on('open-email-sharing-invite-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-sharing-invite-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on an empty choice — never on the last one, which would let a
     * second share go to the first person by a misplaced press.
     *
     * @param {Object} options {perFolder, sendModes}: whether the mail server shares folder
     *   by folder, and the shapes of writing in the owner's name it accepts
     * @returns {void}
     */
    open(options) {
      this.grantee = null;
      this.preset = 'READER';
      this.perFolder = !!options?.perFolder;
      this.sendModeOffered = !!options?.sendModes?.length;
      this.foldersOpen = false;
      this.folders = [];
      this.truncated = false;
      this.choices = {};
      this.drawer = true;
      this.$refs.inviteDrawer.open();
    },
    /**
     * Shows or hides the folder choice, reading the owner's folders the first time.
     *
     * @returns {void}
     */
    toggleFolders() {
      this.foldersOpen = !this.foldersOpen;
      if (!this.foldersOpen || this.folders.length || this.foldersLoading) {
        return;
      }
      this.foldersLoading = true;
      this.$emailConnectorUserSettingService.getShareableFolders()
        .then(answer => {
          this.folders = answer?.folders || [];
          this.truncated = !!answer?.truncated;
          const choices = {};
          this.folders.filter(folder => folder.editable).forEach(folder => choices[folder.folder] = folder.role ? this.preset : 'NONE');
          this.choices = choices;
        })
        .catch(error => {
          this.foldersOpen = false;
          const code = error?.message;
          const known = !!code && typeof this.$te === 'function' && this.$te(code);
          this.showAlert(known ? this.$t(code) : this.$t('UserSettings.emailConnector.sharing.folders.error'), 'error');
        })
        .finally(() => this.foldersLoading = false);
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
     * The owner's choice for Sent, Archive, Trash and Spam where it differs from the
     * preset, by role -- what the invitation itself carries.
     *
     * @returns {Object} the choice, empty when every role folder follows the preset
     */
    roleAccess() {
      const access = {};
      this.folders.filter(folder => folder.role && folder.editable && this.choices[folder.folder] && this.choices[folder.folder] !== this.preset)
        .forEach(folder => access[folder.role] = this.choices[folder.folder]);
      return access;
    },
    /**
     * The owner's other folders chosen to be shared -- written once the share exists.
     *
     * @returns {Array} the changes, [{folder, access}]
     */
    otherFolders() {
      const none = {};
      this.folders.forEach(folder => none[folder.folder] = 'NONE');
      return changesOf(this.folders.filter(folder => !folder.role), none, this.choices);
    },
    /**
     * Shows a message on the platform's toast, through the document event it listens
     * to, whichever app this drawer is mounted in (the settings page, or the mailbox's
     * "Manage shared mailboxes" since EXO-90559).
     *
     * @param {String} message the message
     * @param {String} type success or error
     * @returns {void}
     */
    showAlert(message, type) {
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: type, alertMessage: message}}));
    },
    /**
     * Writes the access on the mail server and invites the person. The refusals worth
     * their own words are the ones the user can act on: sharing with themselves, a
     * person whose mailbox is not connected here, a share that already exists.
     *
     * @returns {void}
     */
    share() {
      if (!this.granteeUsername) {
        return;
      }
      this.saving = true;
      const others = this.otherFolders();
      this.$emailConnectorUserSettingService.inviteDelegation(this.granteeUsername, this.preset, this.roleAccess())
        .then(delegation => {
          if (!others.length || !delegation?.id) {
            return true;
          }
          // The owner's other folders chosen, once the share exists: each its own write.
          return this.$emailConnectorUserSettingService.setDelegationFolders(delegation.id, others)
            .then(answer => !(answer?.results || []).some(result => result.outcome !== 'DONE'))
            .catch(() => false);
        })
        .then(complete => {
          this.showAlert(this.$t(complete ? 'UserSettings.emailConnector.sharing.shared' : 'UserSettings.emailConnector.sharing.invite.folders.partial'),
            complete ? 'success' : 'warning');
          // The list behind this drawer is the mail server's; it just changed.
          this.$root.$emit('email-delegation-granted');
          this.$root.$emit('email-delegations-updated');
          this.close();
        })
        .catch(error => {
          const code = error?.message;
          const known = !!code && typeof this.$te === 'function' && this.$te(code);
          const message = known ? this.$t(code) : this.$t('UserSettings.emailConnector.sharing.share.error');
          this.showAlert(message, 'error');
        })
        .finally(() => this.saving = false);
    },
    /**
     * Closes this drawer, leaving the sharing list open behind it.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
      this.$refs.inviteDrawer.close();
    },
  },
};
</script>
