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
  <v-list class="pa-0">
    <!-- Folder switch: browse the inbox, your sent mail, or archived mail. -->
    <v-list-item
      v-for="folder in folders"
      :key="folder.id"
      class="ps-2 pe-3 height-auto"
      @click="switchFolder(folder.id)">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="mx-auto"
          :class="folder.id === currentFolder ? 'primary--text' : 'icon-default-color'"
          size="16">
          {{ folder.icon }}
        </v-icon>
      </v-sheet>
      <span :class="{ 'primary--text font-weight-bold': folder.id === currentFolder }">
        {{ $t(folder.label) }}
      </span>
    </v-list-item>
    <v-divider class="my-1" />
    <extension-registry-components
      ref="emailListToolbarExtension"
      :params="{ hasWebmailAccess: true }"
      name="EmailList"
      type="email-list-toolbar"
      parent-element="span"
      element="span"
      class="my-auto" />
    <v-list-item
      v-if="hasWebmailAccess"
      class="ps-2 pe-3 height-auto"
      @click="openWebmail()">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          fa-external-link-alt
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.webmail.button.title') }}
      </span>
    </v-list-item>
  </v-list>
</template>

<script>
export default {
  props: {
    // The folder currently listed, highlighted in the menu.
    currentFolder: {
      type: String,
      default: 'INBOX',
    },
    hasWebmailAccess: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    return {
      folders: [
        { id: 'INBOX', label: 'emailConnector.mailBox.list.drawer.folder.inbox', icon: 'fa-inbox' },
        { id: 'SENT', label: 'emailConnector.mailBox.list.drawer.folder.sent', icon: 'fa-paper-plane' },
        { id: 'ARCHIVE', label: 'emailConnector.mailBox.list.drawer.folder.archive', icon: 'fa-archive' },
      ],
    };
  },
  methods: {
    switchFolder(folder) {
      if (folder !== this.currentFolder) {
        this.$root.$emit('switch-folder', folder);
      }
    },
    openWebmail() {
      this.$root.$emit('open-webmail');
    },
  }
};
</script>
