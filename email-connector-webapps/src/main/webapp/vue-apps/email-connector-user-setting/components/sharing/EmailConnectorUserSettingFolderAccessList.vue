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
  <!-- The owner's folders, one row each, with what one person may do in each
       (EXO-90556): Reader, Editor or Not shared -- presets, never letters. INBOX is the
       share itself and is only said here; a folder whose access was set in another mail
       application shows its letters raw until the owner chooses for it. Nested folders
       are indented under their parent. Used by the "Folders and access" drawer and by
       the invitation's folder choice. -->
  <v-list dense class="pa-0">
    <v-list-item
      v-for="folder in folders"
      :key="folder.folder"
      class="px-0 align-start">
      <v-list-item-content class="py-1" :style="indent(folder)">
        <v-list-item-title class="text-truncate" :title="folder.folder">
          <v-icon size="12" class="me-1 icon-default-color">{{ icon(folder) }}</v-icon>
          {{ label(folder) }}
        </v-list-item-title>
        <div v-if="hint(folder)" class="caption text-sub-title text-wrap">{{ hint(folder) }}</div>
        <div v-if="outcome(folder)" class="caption error--text text-wrap">{{ outcome(folder) }}</div>
      </v-list-item-content>
      <v-list-item-action class="my-1 ms-2">
        <span v-if="!folder.editable" class="caption text-sub-title">{{ inboxLabel }}</span>
        <v-btn-toggle
          v-else
          :value="choices[folder.folder]"
          :mandatory="!!choices[folder.folder]"
          :aria-label="label(folder)"
          dense
          @change="choose(folder, $event)">
          <v-btn
            v-for="access in ACCESSES"
            :key="access"
            :value="access"
            :disabled="disabled"
            x-small
            text>
            {{ accessLabel(access) }}
          </v-btn>
        </v-btn-toggle>
      </v-list-item-action>
    </v-list-item>
  </v-list>
</template>

<script>
// What an owner can choose for a folder, in the order the buttons show.
const ACCESSES = ['READER', 'EDITOR', 'NONE'];

export default {
  props: {
    // The folders, as the server lists them: {folder, displayName, parent, depth, role, access, rights, readable, editable}.
    folders: { type: Array, default: () => [] },
    // The access chosen for each folder, by full name; a folder absent has none chosen.
    choices: { type: Object, default: () => ({}) },
    // What became of each folder at the last save, by full name.
    results: { type: Object, default: () => ({}) },
    // What INBOX's row says: the access the share gives there.
    inboxLabel: { type: String, default: '' },
    disabled: { type: Boolean, default: false },
  },
  data: () => ({ ACCESSES }),
  methods: {
    /**
     * A folder's name as the owner reads it: the role's own name for Sent, Archive,
     * Trash and Spam, whatever the server calls it, else the folder's last segment.
     *
     * @param {Object} folder the folder
     * @returns {String} the name
     */
    label(folder) {
      if (!folder.editable) {
        return this.$t('UserSettings.emailConnector.sharing.folders.inboxName');
      }
      return folder.role ? this.$t(`UserSettings.emailConnector.sharing.role.${folder.role}`) : folder.displayName;
    },
    /**
     * @param {Object} folder the folder
     * @returns {String} the icon, a role's own or a plain folder
     */
    icon(folder) {
      switch (folder.role) {
      case 'SENT': return 'fas fa-paper-plane';
      case 'ARCHIVE': return 'fas fa-archive';
      case 'TRASH': return 'fas fa-trash';
      case 'JUNK': return 'fas fa-ban';
      default: return folder.editable ? 'fas fa-folder' : 'fas fa-inbox';
      }
    },
    /**
     * Indents a folder under its parent.
     *
     * @param {Object} folder the folder
     * @returns {Object} the style
     */
    indent(folder) {
      return { paddingInlineStart: `${Math.min(folder.depth || 0, 6) * 16}px` };
    },
    /**
     * A preset's name, or "Not shared".
     *
     * @param {String} access READER, EDITOR or NONE
     * @returns {String} the label
     */
    accessLabel(access) {
      return access === 'NONE'
        ? this.$t('UserSettings.emailConnector.sharing.folders.none')
        : this.$t(`UserSettings.emailConnector.sharing.preset.${access}`);
    },
    /**
     * What a folder's row says under its name while nothing is chosen for it: the
     * letters set in another mail application, or that the server did not say.
     *
     * @param {Object} folder the folder
     * @returns {String} the sentence, or empty
     */
    hint(folder) {
      if (!folder.editable || this.choices[folder.folder]) {
        return '';
      }
      if (!folder.readable) {
        return this.$t('UserSettings.emailConnector.sharing.folders.unreadable');
      }
      return folder.rights ? this.$t('UserSettings.emailConnector.sharing.folders.serverRights', { 0: folder.rights }) : '';
    },
    /**
     * What became of a folder at the last save, when it is not what was asked.
     *
     * @param {Object} folder the folder
     * @returns {String} the sentence, or empty
     */
    outcome(folder) {
      const outcome = this.results[folder.folder];
      return outcome && outcome !== 'DONE' ? this.$t(`UserSettings.emailConnector.sharing.folders.outcome.${outcome}`) : '';
    },
    /**
     * Tells the parent what the owner chose for a folder. Once a folder has a choice the
     * toggle is mandatory, so a second press never unselects it.
     *
     * @param {Object} folder the folder
     * @param {String} access the access pressed
     * @returns {void}
     */
    choose(folder, access) {
      if (access) {
        this.$emit('change', { folder: folder.folder, access });
      }
    },
  },
};
</script>
