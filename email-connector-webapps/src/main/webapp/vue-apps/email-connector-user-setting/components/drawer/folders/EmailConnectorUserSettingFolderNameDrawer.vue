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
  <!-- Create and Rename's one prompt, as a SECOND-LEVEL drawer -- not a popup: this
       add-on already chose a drawer over inline rows for the folder list itself, and a
       modal stacked on top of it would have contradicted that. Mounted at the app root
       (see EmailConnectorUserSettingApp.vue), a SIBLING of the folders list drawer and
       not a child of it, exactly the shape EmailConnectorMailBoxApp.vue already uses for
       its list -> detail drawer: both stay mounted, the list drawer is never closed to
       open this one, and go-back-button's arrow -- with no @go-back handler, so it falls
       through to exo-drawer's own default -- simply closes THIS drawer, which is all it
       takes for the list drawer underneath to be visible again; it was there the whole
       time. -->
  <exo-drawer
    id="userSettingFolderNameDrawer"
    ref="folderNameDrawer"
    v-model="drawer"
    right
    go-back-button
    @closed="reset">
    <template #title>
      <span>{{ title }}</span>
    </template>
    <template v-if="drawer" #content>
      <v-form
        ref="nameForm"
        class="pa-4"
        @submit.prevent="save">
        <v-text-field
          v-model="name"
          autofocus
          :maxlength="maxNameLength"
          :label="$t('UserSettings.emailConnector.folders.name.label')"
          :error-messages="nameError"
          @input="nameError = ''"
          @keydown.enter="save" />
      </v-form>
    </template>
    <template #footer>
      <div class="d-flex align-center">
        <v-spacer />
        <v-btn class="btn" @click="close">
          {{ $t('UserSettings.emailConnector.folders.cancel') }}
        </v-btn>
        <v-btn
          color="primary"
          class="btn btn-primary ms-2"
          :loading="saving"
          :disabled="!name || !name.trim()"
          @click="save">
          {{ actionLabel }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
// The registry's own bound (EmailFolderService#MAX_FOLDER_NAME_LENGTH), mirrored here
// so a name too long is stopped at the keyboard rather than only after a round trip --
// the field's own maxlength enforces it, this is the constant the two share.
const MAX_FOLDER_NAME_LENGTH = 255;

// The server's message codes this drawer knows how to say in the user's own words;
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
    // Which action is live -- 'create' or 'rename' -- and, for a rename, which
    // folder it targets.
    action: null,
    target: null,
    name: '',
    nameError: '',
    saving: false,
    maxNameLength: MAX_FOLDER_NAME_LENGTH,
  }),
  computed: {
    /**
     * The drawer's title: Create when no folder is targeted, Rename when one is.
     *
     * @returns {String} the localized title
     */
    title() {
      return this.action === 'rename' ? this.$t('UserSettings.emailConnector.folders.rename.title')
        : this.$t('UserSettings.emailConnector.folders.create.title');
    },
    /**
     * The save button's label, matching the title.
     *
     * @returns {String} the localized label
     */
    actionLabel() {
      return this.action === 'rename' ? this.$t('UserSettings.emailConnector.folders.rename')
        : this.$t('UserSettings.emailConnector.folders.create');
    },
  },
  created() {
    this.$root.$on('open-email-folder-name-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-folder-name-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on Create (no folder given) or Rename (pre-filled with the
     * folder's current name), over the folders list drawer -- left open behind it.
     *
     * @param {Object} opening {mode: 'create'|'rename', folder} -- folder only for rename
     * @returns {void}
     */
    open(opening) {
      this.action = opening?.mode === 'rename' ? 'rename' : 'create';
      this.target = opening?.folder || null;
      this.name = this.target?.displayName || '';
      this.nameError = '';
      this.drawer = true;
      this.$refs.folderNameDrawer.open();
    },
    /**
     * Runs whichever action the drawer is open on. The server's own message code
     * comes back as the field's error when the name itself is the problem (blank,
     * too long, nesting, reserved, a duplicate); a create or rename that failed on
     * the server for another reason gets the toast the folders list uses.
     *
     * @returns {void}
     */
    save() {
      const typed = (this.name || '').trim();
      if (!typed) {
        return;
      }
      this.saving = true;
      const action = this.action === 'rename'
        ? this.$emailConnectorUserSettingService.renameMailFolder(this.target.id, typed)
        : this.$emailConnectorUserSettingService.createMailFolder(typed);
      action
        .then(() => {
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success');
          this.$root.$emit('email-folders-list-changed');
          this.close();
        })
        .catch(error => {
          const key = NAME_ERROR_KEYS[error?.message];
          if (key && key.indexOf('.name.error.') >= 0) {
            // The name itself is the problem: said right at the field, not as a
            // toast that has already scrolled away by the time the user looks back.
            this.nameError = this.$t(key, { 0: this.maxNameLength });
          } else {
            this.$root.$emit('alert-message', this.$t(key || 'UserSettings.emailConnector.folders.error'), 'error');
          }
        })
        .finally(() => this.saving = false);
    },
    /**
     * Closes the drawer, revealing the folders list drawer that was open behind it
     * the whole time.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
      this.$refs.folderNameDrawer.close();
    },
    /**
     * Forgets the opened state, so the next open reads fresh.
     *
     * @returns {void}
     */
    reset() {
      this.action = null;
      this.target = null;
      this.name = '';
      this.nameError = '';
      this.drawer = false;
    },
  },
};
</script>
