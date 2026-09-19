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
  <!-- The full-screen mailbox's folder column, Gmail/Outlook style (EXO-90415): the
       FOLDERS and CATEGORIES the narrow drawer keeps in its 3-dots menu, always on
       screen beside the list. It drives the very state the menu drives -- the same two
       root events, the same highlight rule (a folder is lit only outside a category
       view) -- so the two can never disagree. As a rail it shows the icons only, each
       with its name in a tooltip and a dot for unread mail. No SFC style: the add-on's
       webpack has no CSS loader, so the layout is Vuetify props, the platform's helper
       classes and inline style. -->
  <v-list
    :class="rail ? 'px-1' : 'px-2'"
    class="py-2 transparent"
    dense
    nav>
    <v-list-item-group
      :value="activeKey"
      color="primary"
      mandatory>
      <v-subheader
        v-if="!rail"
        class="text-uppercase caption px-2">
        {{ $t('emailConnector.mailBox.list.drawer.menu.folders') }}
      </v-subheader>
      <v-tooltip
        v-for="entry in folderEntries"
        :key="entry.value"
        :disabled="!rail"
        right>
        <template #activator="{ on, attrs }">
          <v-list-item
            :value="entry.value"
            :aria-label="entry.ariaLabel"
            :title="rail ? null : entry.label"
            v-bind="attrs"
            v-on="on"
            @click="switchFolder(entry.key)">
            <v-list-item-icon :class="rail ? 'mx-auto' : 'me-3'">
              <v-badge
                :value="rail && entry.unread"
                color="primary"
                dot
                overlap>
                <v-icon size="16">{{ entry.icon }}</v-icon>
              </v-badge>
            </v-list-item-icon>
            <template v-if="!rail">
              <v-list-item-content>
                <v-list-item-title :class="{ 'font-weight-bold': entry.unread }">
                  {{ entry.label }}
                </v-list-item-title>
              </v-list-item-content>
              <v-list-item-action-text
                v-if="entry.count"
                :class="{ 'font-weight-bold': entry.unread }"
                class="text-body-2">
                {{ entry.count }}
              </v-list-item-action-text>
            </template>
          </v-list-item>
        </template>
        <span>{{ entry.tooltip }}</span>
      </v-tooltip>
      <template v-if="categoryEntries.length">
        <v-divider class="my-2" />
        <v-subheader
          v-if="!rail"
          class="text-uppercase caption px-2">
          {{ $t('emailConnector.mailBox.list.drawer.menu.categories') }}
        </v-subheader>
        <v-tooltip
          v-for="entry in categoryEntries"
          :key="entry.value"
          :disabled="!rail"
          right>
          <template #activator="{ on, attrs }">
            <v-list-item
              :value="entry.value"
              :aria-label="entry.ariaLabel"
              :title="rail ? null : entry.label"
              v-bind="attrs"
              v-on="on"
              @click="openCategoryView(entry.id)">
              <v-list-item-icon :class="rail ? 'mx-auto' : 'me-3'">
                <v-badge
                  :value="rail && entry.unread"
                  color="primary"
                  dot
                  overlap>
                  <v-icon size="16">{{ entry.icon }}</v-icon>
                </v-badge>
              </v-list-item-icon>
              <template v-if="!rail">
                <v-list-item-content>
                  <v-list-item-title :class="{ 'font-weight-bold': entry.unread }">
                    {{ entry.label }}
                  </v-list-item-title>
                </v-list-item-content>
                <v-list-item-action-text
                  v-if="entry.count"
                  class="text-body-2 font-weight-bold">
                  {{ entry.count }}
                </v-list-item-action-text>
              </template>
            </v-list-item>
          </template>
          <span>{{ entry.tooltip }}</span>
        </v-tooltip>
      </template>
    </v-list-item-group>
  </v-list>
</template>

<script>
export default {
  props: {
    // The folders to offer, as the server listed them ({key, type, displayName, ...}),
    // in the server's order: the same list the 3-dots menu offers.
    folders: {
      type: Array,
      default: () => [{ key: 'INBOX', type: 'BUILT_IN' }],
    },
    // The folder currently listed.
    currentFolder: {
      type: String,
      default: 'INBOX',
    },
    // The categories offered as views ({id, name, icon}), Important included.
    categories: {
      type: Array,
      default: () => [],
    },
    // The category the list is switched to, or null outside any category view.
    categoryViewId: {
      type: [Number, String],
      default: null,
    },
    // The count each folder shows, by folder key: {count, unread} -- how many, and
    // whether they are unread mail (the inbox, the spam) rather than a total (the
    // drafts). A folder without an entry shows none.
    folderCounts: {
      type: Object,
      default: () => ({}),
    },
    // The unread mail of each category over the loaded window, by category id.
    categoryUnreadCounts: {
      type: Object,
      default: () => ({}),
    },
    // Whether the column is folded to an icon rail.
    rail: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    /**
     * The entry lit in the column: the category view when one is open, the listed
     * folder otherwise -- the 3-dots menu's own rule.
     *
     * @returns {String} the value of the lit entry
     */
    activeKey() {
      return this.categoryViewId ? `category:${this.categoryViewId}` : `folder:${this.currentFolder}`;
    },
    /**
     * The folders as the column shows them: the one label and the one icon the menu
     * shows too (folderLabel, folderIcon), and the count the drawer worked out.
     *
     * @returns {Array} the entries
     */
    folderEntries() {
      return this.folders.map(folder => {
        const label = this.$emailConnectorMailBoxService.folderLabel(folder, this.$t.bind(this));
        const counted = this.folderCounts[folder.key];
        const count = counted?.count > 0 ? counted.count : 0;
        const unread = !!(count && counted.unread);
        return {
          key: folder.key,
          value: `folder:${folder.key}`,
          icon: this.$emailConnectorMailBoxService.folderIcon(folder),
          label,
          count,
          unread,
          ...this.describe(label, count, unread),
        };
      });
    },
    /**
     * The categories as the column shows them, each with its unread mail.
     *
     * @returns {Array} the entries
     */
    categoryEntries() {
      return this.categories.map(category => {
        const count = this.categoryUnreadCounts[category.id] > 0 ? this.categoryUnreadCounts[category.id] : 0;
        return {
          id: category.id,
          value: `category:${category.id}`,
          icon: category.icon || 'fa-tag',
          label: category.name,
          count,
          unread: count > 0,
          ...this.describe(category.name, count, count > 0),
        };
      });
    },
  },
  methods: {
    /**
     * What an entry says beyond its name, to a screen reader and in the rail's
     * tooltip: its count, as unread mail or as a total.
     *
     * @param {String} label the entry's name
     * @param {Number} count its count, 0 for none
     * @param {Boolean} unread whether the count is unread mail
     * @returns {Object} {ariaLabel, tooltip}
     */
    describe(label, count, unread) {
      if (!count) {
        return { ariaLabel: label, tooltip: label };
      }
      const key = unread ? 'emailConnector.mailBox.list.drawer.navigation.unread' : 'emailConnector.mailBox.list.drawer.navigation.total';
      const described = this.$t(key, { 0: label, 1: count });
      return { ariaLabel: described, tooltip: described };
    },
    /**
     * Lists a folder, as the 3-dots menu's entry does: the same root event, emitted
     * for the folder already listed too -- inside a category view it is the way back.
     *
     * @param {String} folder the folder key
     * @returns {void}
     */
    switchFolder(folder) {
      this.$root.$emit('switch-folder', folder);
    },
    /**
     * Opens a category view, or leaves it when it is the open one, as the 3-dots
     * menu's entry does.
     *
     * @param {Number} categoryId the category id
     * @returns {void}
     */
    openCategoryView(categoryId) {
      this.$root.$emit('open-category-view', categoryId);
    },
  },
};
</script>
