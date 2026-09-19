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
  <!-- Full-screen folder column (EXO-90415): the 3-dots menu's FOLDERS/CATEGORIES, same
       events and highlight. Rail: icons, tooltips, unread dot. No SFC style. -->
  <v-list
    :class="rail ? 'px-1 py-0' : 'px-2 py-2'"
    class="transparent"
    dense
    nav>
    <v-list-item-group
      :value="activeKey"
      color="primary"
      mandatory>
      <!-- One element per section: as flat siblings, VTooltip's patching put the
           CATEGORIES header above the folders after a rail. The rail hides headers and
           divider. Entries are the listbox's options, selected when lit. -->
      <div
        v-for="(section, index) in sections"
        :key="section.key"
        :aria-label="section.title"
        :data-section="section.key"
        role="group">
        <v-divider
          v-if="index > 0"
          v-show="!rail"
          class="my-2" />
        <v-subheader
          v-show="!rail"
          class="text-uppercase caption px-2">
          {{ section.title }}
        </v-subheader>
        <v-tooltip
          v-for="entry in section.entries"
          :key="entry.value"
          :disabled="!rail"
          right>
          <!-- Activator attributes only where there is a tooltip (the rail). -->
          <template #activator="{ on, attrs }">
            <v-list-item
              :value="entry.value"
              :aria-label="entry.ariaLabel"
              :aria-selected="String(entry.value === activeKey)"
              :title="rail ? null : entry.tooltip"
              role="option"
              v-bind="rail ? attrs : {}"
              v-on="rail ? on : {}"
              @click="entry.select">
              <v-list-item-icon :class="rail ? 'mx-auto' : 'ms-0 me-2'" class="my-auto align-self-center align-center">
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
                  {{ $emailConnectorMailBoxService.formatCount(entry.count) }}
                </v-list-item-action-text>
              </template>
            </v-list-item>
          </template>
          <span>{{ entry.tooltip }}</span>
        </v-tooltip>
      </div>
    </v-list-item-group>
  </v-list>
</template>

<script>
export default {
  props: {
    // The folders as the server listed them, in its order: the 3-dots menu's list.
    folders: { type: Array, default: () => [{ key: 'INBOX', type: 'BUILT_IN' }] },
    currentFolder: { type: String, default: 'INBOX' },
    // The categories offered as views ({id, name, icon}), Important included.
    categories: { type: Array, default: () => [] },
    // The category the list is switched to, or null outside any category view.
    categoryViewId: { type: [Number, String], default: null },
    // By folder key, {count, unread}: how many, and whether they are unread mail (the
    // inbox, the spam) rather than a total (the drafts). No entry, no count.
    folderCounts: { type: Object, default: () => ({}) },
    // Each category's unread mail over the loaded window, by id.
    categoryUnreadCounts: { type: Object, default: () => ({}) },
    rail: { type: Boolean, default: false },
  },
  computed: {
    /**
     * The entry lit: the category view when one is open, the listed folder otherwise
     * -- the 3-dots menu's own rule.
     *
     * @returns {String} the value of the lit entry
     */
    activeKey() {
      return this.categoryViewId ? `category:${this.categoryViewId}` : `folder:${this.currentFolder}`;
    },
    /**
     * The folders: the label and the icon the menu shows too (folderLabel, folderIcon)
     * and the count the drawer worked out.
     *
     * @returns {Array} the entries
     */
    folderEntries() {
      return this.folders.map(folder => {
        const counted = this.folderCounts[folder.key];
        const count = counted?.count > 0 ? counted.count : 0;
        return this.buildEntry(`folder:${folder.key}`, this.$emailConnectorMailBoxService.folderIcon(folder),
          this.$emailConnectorMailBoxService.folderLabel(folder, this.$t.bind(this)), count, !!(count && counted.unread),
          () => this.switchFolder(folder.key));
      });
    },
    /**
     * The categories, each with its unread mail.
     *
     * @returns {Array} the entries
     */
    categoryEntries() {
      return this.categories.map(category => {
        const count = this.categoryUnreadCounts[category.id] > 0 ? this.categoryUnreadCounts[category.id] : 0;
        return this.buildEntry(`category:${category.id}`, category.icon || 'fa-tag', category.name, count, count > 0,
          () => this.openCategoryView(category.id));
      });
    },
    /**
     * The column's sections: the folders, then the categories when there are any.
     *
     * @returns {Array} {key, title, entries}
     */
    sections() {
      const sections = [{ key: 'folders', title: this.$t('emailConnector.mailBox.list.drawer.menu.folders'), entries: this.folderEntries }];
      if (this.categoryEntries.length) {
        sections.push({ key: 'categories', title: this.$t('emailConnector.mailBox.list.drawer.menu.categories'), entries: this.categoryEntries });
      }
      return sections;
    },
  },
  methods: {
    /**
     * One entry of the column, with what it says beyond its name -- to a screen reader
     * and in the rail's tooltip: its count, as unread mail or as a total.
     *
     * @param {String} value its value in the group (folder:KEY or category:ID)
     * @param {String} icon its icon
     * @param {String} label its name
     * @param {Number} count its count, 0 for none
     * @param {Boolean} unread whether the count is unread mail
     * @param {Function} select what a click on it does
     * @returns {Object} the entry
     */
    buildEntry(value, icon, label, count, unread, select) {
      const key = unread ? 'emailConnector.mailBox.list.drawer.navigation.unread' : 'emailConnector.mailBox.list.drawer.navigation.total';
      const described = count ? this.$t(key, { 0: label, 1: count }) : label;
      return { value, icon, label, count, unread, select, ariaLabel: described, tooltip: described };
    },
    /**
     * Lists a folder with the 3-dots menu's event, the folder already listed included:
     * inside a category view it is the way back.
     *
     * @param {String} folder the folder key
     * @returns {void}
     */
    switchFolder(folder) {
      this.$root.$emit('switch-folder', folder);
    },
    /**
     * Opens a category view, or leaves the open one, with the 3-dots menu's event.
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
