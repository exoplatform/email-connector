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
  <!-- Full-screen folder column (EXO-90415): the menu's FOLDERS/CATEGORIES, same events
       and highlight; the pen opens the settings' folders drawer. No SFC style. -->
  <v-list
    :class="rail ? 'px-1' : 'px-2'"
    :style="{ paddingTop: rail ? RAIL_TOP_PADDING : 0 }"
    class="pb-2"
    dense
    nav>
    <v-list-item-group
      :value="activeKey"
      color="primary"
      mandatory>
      <!-- One element per section (VTooltip reorders loose siblings on a rail). -->
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
          :style="index === 0 ? { height: TOP_ROW_HEIGHT } : null"
          class="text-uppercase caption px-2">
          <span class="flex-grow-1">{{ section.title }}</span>
          <v-btn
            v-if="section.key === 'folders'"
            :title="$t('emailConnector.mailBox.list.drawer.navigation.manageFolders')"
            :aria-label="$t('emailConnector.mailBox.list.drawer.navigation.manageFolders')"
            icon
            x-small
            @click="$root.$emit('open-email-folders-drawer')">
            <v-icon size="12" class="icon-default-color">fa-pen</v-icon>
          </v-btn>
        </v-subheader>
        <v-tooltip
          v-for="entry in section.entries"
          :key="entry.value"
          :disabled="!rail"
          right>
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
// The first row sits on the list's chips row (the FOLDERS header, or the rail's first entry).
import { LIST_TOP_ROW_HEIGHT as TOP_ROW_HEIGHT, RAIL_TOP_PADDING } from '../../js/EmailConnectorMailBoxService.js';

export default {
  data: () => ({ TOP_ROW_HEIGHT, RAIL_TOP_PADDING }),
  props: {
    // The menu's folders, categories, listed folder, open view; counts by key / id.
    folders: { type: Array, default: () => [{ key: 'INBOX', type: 'BUILT_IN' }] },
    currentFolder: { type: String, default: 'INBOX' },
    categories: { type: Array, default: () => [] },
    categoryViewId: { type: [Number, String], default: null },
    folderCounts: { type: Object, default: () => ({}) },
    categoryUnreadCounts: { type: Object, default: () => ({}) },
    rail: { type: Boolean, default: false },
  },
  computed: {
    /**
     * The entry lit: the category view when open, else the listed folder (the menu's rule).
     *
     * @returns {String} the value of the lit entry
     */
    activeKey() {
      return this.categoryViewId ? `category:${this.categoryViewId}` : `folder:${this.currentFolder}`;
    },
    /**
     * The folders, labelled and drawn as the menu draws them, with their counts.
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
    /** @returns {Array} the categories, each with its unread mail */
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
     * One entry, with its count said to a screen reader and in its tooltip.
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
     * Lists a folder with the menu's event, the listed one too (the way out of a view).
     *
     * @param {String} folder the folder key
     * @returns {void}
     */
    switchFolder(folder) {
      this.$root.$emit('switch-folder', folder);
    },
    /**
     * Opens a category view, or leaves the open one, with the menu's event.
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
