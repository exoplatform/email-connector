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
  <v-list-item-action class="ma-0">
    <v-menu
      :nudge-top="-1"
      content-class="no-min-width border-radius z-index-modal overflow-hidden"
      close-on-content-click
      offset-y
      left
      bottom>
      <template #activator="{ on, attrs }">
        <v-btn
          v-bind="attrs"
          class="pa-0"
          :title="$t('emailConnector.mailBox.list.drawer.options.tooltip')"
          icon
          v-on="on"
          @click.stop.prevent>
          <v-icon
            size="20"
            class="icon-default-color">
            fa-ellipsis-v
          </v-icon>
        </v-btn>
      </template>
      <email-connector-mail-box-drawer-action-menu-items
        :current-folder="currentFolder"
        :available-folders="availableFolders"
        :favorite-only="favoriteOnly"
        :sync-in-progress="syncInProgress"
        :has-webmail-access="hasWebmailAccess" />
    </v-menu>
  </v-list-item-action>
</template>

<script>
export default {
  props: {
    currentFolder: {
      type: String,
      default: 'INBOX',
    },
    availableFolders: {
      type: Array,
      default: () => ['INBOX'],
    },
    // Whether the list is narrowed to the favorite messages, for the menu state.
    favoriteOnly: {
      type: Boolean,
      default: false,
    },
    syncInProgress: {
      type: Boolean,
      default: false,
    },
    hasWebmailAccess: {
      type: Boolean,
      default: false,
    },
    // kept for backward compatibility with the previous webmail-only menu.
    webmailUrl: {
      type: String,
      default: null,
    },
  },
};
</script>