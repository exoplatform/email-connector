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
    <!-- min-width="240": the entries used to be fixed labels ("Inbox", "Drafts") and
         shrinking to them was fine, but the folder names are the user's own now, so the
         menu's width followed whatever they happened to call things and felt pinched. A
         floor stops that; longer names still widen it past it.
         Deliberately WITHOUT the no-min-width class every other menu in this webapp
         carries: `.no-min-width { min-width: 0 !important; }` (platform-ui helpers.less)
         beats an inline min-width style with !important regardless of the prop, which is
         exactly why min-width="240" alone had no effect the first time. Vuetify's own
         unforced default here (VMenu#calculatedMinWidth, no minWidth prop) falls back to
         the ACTIVATOR's width -- this activator is a bare icon button, tens of pixels
         wide -- so relying on the platform default instead of the prop would still leave
         the menu pinched; the prop is load-bearing, not decorative. -->
    <v-menu
      :nudge-top="-1"
      min-width="240"
      content-class="border-radius z-index-modal overflow-hidden"
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
        :categories="categories"
        :category-view-id="categoryViewId"
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
    // The folders to offer, as the server listed them.
    availableFolders: {
      type: Array,
      default: () => [{ key: 'INBOX', type: 'BUILT_IN' }],
    },
    // The categories offered as views (the add-on's full set, Important included).
    categories: {
      type: Array,
      default: () => [],
    },
    // The category the list is currently switched to, so the menu highlights it.
    categoryViewId: {
      type: [Number, String],
      default: null,
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