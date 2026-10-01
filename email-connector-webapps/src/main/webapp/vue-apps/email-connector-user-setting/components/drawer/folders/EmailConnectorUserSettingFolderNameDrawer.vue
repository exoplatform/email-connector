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
        <!-- Label above the field, not floating inside it, and an outlined field:
             the shape every other form in this webapp uses (see the contact form
             drawer). A bare :label rides the input's own underline until the field
             has content, which reads as struck-through text rather than a label. -->
        <div class="text-sub-title mb-1">
          {{ $t('UserSettings.emailConnector.folders.name.label') }}
        </div>
        <v-text-field
          v-model="name"
          class="pt-0"
          autofocus
          outlined
          dense
          :maxlength="maxNameLength"
          :error-messages="nameError"
          @input="nameError = ''"
          @keydown.enter="save" />
        <!-- Where the folder lives (EXO-90839): at the top, or inside one of the user's
             folders; on a rename, another choice moves it, with the folders inside it.
             Offered only on a mailbox whose folders can nest. -->
        <template v-if="parentChoices.length > 1">
          <div class="text-sub-title mb-1 mt-2">
            {{ $t('UserSettings.emailConnector.folders.parent.label') }}
          </div>
          <v-select
            v-model="parentKey"
            :items="parentChoices"
            :menu-props="{ offsetY: true }"
            class="pt-0"
            item-text="label"
            item-value="value"
            outlined
            dense
            hide-details />
        </template>
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
import { buildFolderTree, descendantKeys } from '../../../../email-connector-mail-box/js/EmailConnectorFolderTree.js';
import { folderPath } from '../../../../email-connector-mail-box/js/EmailConnectorMailBoxService.js';

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
  'emailConnector.folder.parent.invalid': 'UserSettings.emailConnector.folders.parent.error.invalid',
  'emailConnector.folder.path.tooLong': 'UserSettings.emailConnector.folders.name.error.pathTooLong',
};

// The choice that stands for the top level in the parent picker: a folder key never
// takes this shape (CUSTOM:<id>).
const TOP_LEVEL = 'TOP';

// The choice that keeps a renamed folder where it is when its parent is not listed (a
// container the mail server says cannot hold mail): shown by that parent's path.
const CURRENT_PLACE = 'CURRENT';

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
    // The user's folders, for the parent picker, and the parent chosen (TOP_LEVEL or a key).
    folders: [],
    parentKey: TOP_LEVEL,
    initialParentKey: TOP_LEVEL,
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
    /**
     * Where the folder may live: the top level, then every folder of the user's that
     * can hold one, in tree order and by its path -- never, on a rename, the folder
     * itself or a folder inside it.
     *
     * @returns {Array} the choices, {value, label}
     */
    parentChoices() {
      const excluded = this.target ? [this.target.key].concat(descendantKeys(this.folders, this.target)) : [];
      const choices = buildFolderTree(this.folders)
        .map(row => row.folder)
        .filter(folder => !folder.missing && folder.delimiter && !excluded.includes(folder.key))
        .map(folder => ({ value: folder.key, label: folderPath(folder) }));
      const top = [{ value: TOP_LEVEL, label: this.$t('UserSettings.emailConnector.folders.parent.top') }];
      if (this.initialParentKey === CURRENT_PLACE) {
        top.push({ value: CURRENT_PLACE, label: folderPath({ path: this.target.path.substring(0, this.target.path.lastIndexOf(this.target.delimiter)), delimiter: this.target.delimiter }) });
      }
      return top.concat(choices);
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
      this.folders = opening?.folders || [];
      this.name = this.target?.displayName || '';
      const parent = this.action === 'rename' ? this.parentOf(this.target) : opening?.parent;
      // A parent the picker does not offer (no longer found on the server) is shown as
      // the folder's current place, never as a blank choice.
      const offered = parent && !parent.missing && parent.delimiter ? parent : null;
      const nestedUnlisted = this.action === 'rename' && !offered && !!this.target?.delimiter
        && this.target.path?.lastIndexOf(this.target.delimiter) > 0;
      this.parentKey = offered?.key || (nestedUnlisted ? CURRENT_PLACE : TOP_LEVEL);
      this.initialParentKey = this.parentKey;
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
      const parentId = this.parentId(this.parentKey);
      let action;
      if (this.action !== 'rename') {
        action = this.$emailConnectorUserSettingService.createMailFolder(typed, parentId);
      } else if (this.parentKey !== this.initialParentKey) {
        // One request moves it, and renames it in the same step when the name changed too.
        action = this.$emailConnectorUserSettingService.moveMailFolder(this.target.id, parentId,
          typed !== this.target.displayName ? typed : null);
      } else {
        action = this.$emailConnectorUserSettingService.renameMailFolder(this.target.id, typed);
      }
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
     * The folder of the list a folder lives in: the one whose full name is its own up
     * to the last delimiter.
     *
     * @param {Object} folder the folder
     * @returns {Object} the parent, or null at the top level or when it is not listed
     */
    parentOf(folder) {
      if (!folder?.path || !folder.delimiter) {
        return null;
      }
      const cut = folder.path.lastIndexOf(folder.delimiter);
      const parentPath = cut > 0 ? folder.path.substring(0, cut) : null;
      return parentPath ? this.folders.find(candidate => candidate.path === parentPath) || null : null;
    },
    /**
     * The registry id a parent choice stands for.
     *
     * @param {String} key the choice, TOP_LEVEL or a folder key
     * @returns {Number} the id, or null for the top level
     */
    parentId(key) {
      return key === TOP_LEVEL ? null : this.folders.find(folder => folder.key === key)?.id || null;
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
      this.folders = [];
      this.parentKey = TOP_LEVEL;
      this.initialParentKey = TOP_LEVEL;
    },
  },
};
</script>
