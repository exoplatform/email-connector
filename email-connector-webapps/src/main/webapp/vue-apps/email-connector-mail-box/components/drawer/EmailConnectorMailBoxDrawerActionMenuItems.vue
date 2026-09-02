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
    <!-- Folders: browse the inbox, your sent mail, or archived mail. -->
    <div class="ps-2 pe-3 pt-2 pb-1 text-sub-title text-uppercase caption">
      {{ $t('emailConnector.mailBox.list.drawer.menu.folders') }}
    </div>
    <v-list-item
      v-for="folder in visibleFolders"
      :key="folder.key"
      class="ps-2 pe-3 height-auto"
      @click="switchFolder(folder.key)">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="mx-auto"
          :class="folder.key === currentFolder && !categoryViewId ? 'primary--text' : 'icon-default-color'"
          size="16">
          {{ folder.icon }}
        </v-icon>
      </v-sheet>
      <span :class="{ 'primary--text font-weight-bold': folder.key === currentFolder && !categoryViewId }">
        {{ folder.label }}
      </span>
    </v-list-item>
    <!-- Categories: the complete list, Important included — its quick chip
         above the list is a shortcut to the same view this entry opens, so the
         two can never disagree. Each category is a VIEW like the folders above
         it, shown with its own declared icon — picking one switches the list to
         that category and closes the menu; picking it again, or any folder,
         leaves the view. -->
    <template v-if="categories.length">
      <v-divider class="my-1" />
      <div class="ps-2 pe-3 pt-2 pb-1 text-sub-title text-uppercase caption">
        {{ $t('emailConnector.mailBox.list.drawer.menu.categories') }}
      </div>
      <v-list-item
        v-for="category in categories"
        :key="category.id"
        class="ps-2 pe-3 height-auto"
        @click="openCategoryView(category.id)">
        <v-sheet
          class="d-flex"
          width="28"
          height="36">
          <v-icon
            class="mx-auto"
            :class="category.id === categoryViewId ? 'primary--text' : 'icon-default-color'"
            size="16">
            {{ category.icon || 'fa-tag' }}
          </v-icon>
        </v-sheet>
        <span :class="{ 'primary--text font-weight-bold': category.id === categoryViewId }">
          {{ category.name }}
        </span>
      </v-list-item>
    </template>
    <v-divider class="my-1" />
    <!-- Actions on the mailbox itself. -->
    <div class="ps-2 pe-3 pt-2 pb-1 text-sub-title text-uppercase caption">
      {{ $t('emailConnector.mailBox.list.drawer.menu.actions') }}
    </div>
    <!-- Synchronize now (progress is shown by the header spinner while it runs). -->
    <v-list-item
      class="ps-2 pe-3 height-auto"
      :disabled="syncInProgress"
      @click="synchronize()">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          fa-sync-alt
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.sync.tooltip') }}
      </span>
    </v-list-item>
    <!-- Select mode: multi-select rows to read/archive/delete in bulk. -->
    <v-list-item
      class="ps-2 pe-3 height-auto"
      @click="enterSelectMode()">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          fa-check-square
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.menu.selectSeveral') }}
      </span>
    </v-list-item>
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
    // The folders to offer, as the server listed them ({key, type, displayName, ...}):
    // the built-ins this mailbox has, and the user's own mirrored ones.
    availableFolders: {
      type: Array,
      default: () => [{ key: 'INBOX', type: 'BUILT_IN' }],
    },
    // The categories offered as views ({id, name, icon}) — the add-on's full
    // set, Important included (its chip above the list is a shortcut to the
    // same view). Empty hides the section.
    categories: {
      type: Array,
      default: () => [],
    },
    // The category the list is currently switched to (highlighted like the
    // current folder), or null outside any category view.
    categoryViewId: {
      type: [Number, String],
      default: null,
    },
    syncInProgress: {
      type: Boolean,
      default: false,
    },
  },
  data() {
    return {
      // The icon of each built-in; a folder of the user's own gets the plain folder.
      // The ORDER of the list is the server's: inbox, Sent, Archive, Drafts, then the
      // two hidden folders (Spam before Trash the way every mail client orders them),
      // then the user's own -- see EmailBoxService#buildFolderViews.
      icons: {
        INBOX: 'fa-inbox',
        SENT: 'fa-paper-plane',
        ARCHIVE: 'fa-archive',
        DRAFTS: 'fa-file-alt',
        JUNK: 'fa-ban',
        TRASH: 'fa-trash',
      },
    };
  },
  computed: {
    /**
     * The folders to display, each with its icon and its label. The label comes from
     * the one labelling function: a built-in through the bundle, a custom folder as
     * the user wrote it -- never through $t.
     *
     * @returns {Array} the folder descriptors to display
     */
    visibleFolders() {
      return this.availableFolders.map(folder => ({
        key: folder.key,
        icon: folder.type === 'CUSTOM' ? 'fa-folder' : (this.icons[folder.key] || 'fa-folder'),
        label: this.$emailConnectorMailBoxService.folderLabel(folder, this.$t.bind(this)),
      }));
    },
  },
  methods: {
    /**
     * Switches the listed folder. Always emitted, even for the folder already
     * listed: inside a category view, re-picking the current folder is the way
     * back to its plain view.
     *
     * @param {String} folder the folder key (a built-in, or CUSTOM:<id>)
     * @returns {void}
     */
    switchFolder(folder) {
      this.$root.$emit('switch-folder', folder);
    },
    /**
     * Switches the list to one category — a view like a folder, not a checkbox:
     * single selection, and the menu closes on the click. Picking the active
     * category again leaves the view.
     *
     * @param {Number} categoryId the category id to switch to
     * @returns {void}
     */
    openCategoryView(categoryId) {
      this.$root.$emit('open-category-view', categoryId);
    },
    /**
     * Triggers an immediate synchronization of the mailbox (guarded while one runs).
     *
     * @returns {void}
     */
    synchronize() {
      if (this.syncInProgress) {
        return;
      }
      this.$root.$emit('synchronize-in-progress');
      this.$emailConnectorMailBoxService.synchronize().then(() => {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.list.drawer.sync.success'), 'success');
      });
    },
    /**
     * Enters the multi-select mode on the list (same mode a row checkbox starts).
     *
     * @returns {void}
     */
    enterSelectMode() {
      this.$root.$emit('enter-select-mode');
    },
    /**
     * Opens the user's webmail in a new tab.
     *
     * @returns {void}
     */
    openWebmail() {
      this.$root.$emit('open-webmail');
    },
  }
};
</script>
