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
  <div
    v-if="categories.length"
    class="d-flex align-center pb-2 category-chips-thin-scrollbar"
    style="gap: 6px; overflow-x: auto; flex-wrap: nowrap;">
    <!-- Outlined, not filled: a category is metadata about the conversation, not the
         point of it, so the chips must weigh less than the subject above them and the
         sender below. Primary keeps border and label blue, so they still read as
         categories rather than disabled text, and the row sits as one group with the
         grey "Category" button next to it.

         text-color="primary", NOT color="primary": on an outlined chip, color= adds
         both the `primary` and `primary--text` classes, and the platform skin rule
         `.VuetifyApp .v-application .primary:not([disabled]) { color: #fff !important }`
         (meant for filled primary surfaces) matches the `primary` class regardless of
         v-chip--outlined and, with its higher specificity, paints the label and the
         close icon white on a transparent chip, i.e. invisible. text-color= only adds
         `primary--text`, which that rule never matches, and the outlined border still
         renders primary because Vuetify draws it with currentColor. -->
    <v-chip
      v-for="category in assignedCategories"
      :key="category.id"
      small
      outlined
      close
      text-color="primary"
      class="flex-shrink-0"
      @click:close="toggle(category, false)">
      {{ category.name }}
    </v-chip>
    <v-menu
      offset-y
      :close-on-content-click="false">
      <template #activator="{ on, attrs }">
        <v-btn
          x-small
          text
          class="text-none flex-shrink-0"
          v-bind="attrs"
          v-on="on">
          <v-icon size="13" class="me-1 icon-default-color">fa-tag</v-icon>
          {{ $t('emailConnector.mailBox.list.drawer.category.add') }}
        </v-btn>
      </template>
      <v-list dense class="pa-0">
        <v-list-item
          v-for="category in categories"
          :key="category.id"
          class="clickable"
          @click="toggle(category, !isAssigned(category.id))">
          <v-icon
            size="14"
            class="me-2"
            :class="isAssigned(category.id) ? 'primary--text' : 'icon-default-color'">
            {{ isAssigned(category.id) ? 'fas fa-check-square' : 'far fa-square' }}
          </v-icon>
          <span :class="{ 'primary--text font-weight-bold': isAssigned(category.id) }">
            {{ category.name }}
          </span>
        </v-list-item>
      </v-list>
    </v-menu>
  </div>
</template>

<script>
import { selectionByFolder, selectionKey } from '../../js/EmailConnectorMailBoxSelection.js';

export default {
  props: {
    // The conversation's messages; the category applies to the whole thread.
    emails: {
      type: Array,
      default: () => [],
    },
  },
  data() {
    return {
      categories: [],
      assignedIds: [],
    };
  },
  computed: {
    /**
     * The conversation's UIDs grouped by the folder they are numbered in: a
     * conversation spans folders, and a UID only names a message within its own
     * (EXO-90421, the EXO-90416 wrong-message class) -- one request per folder.
     *
     * @returns {Array} [folder, ids] pairs
     */
    idsByFolder() {
      return selectionByFolder((this.emails || []).map(selectionKey));
    },
    assignedCategories() {
      return this.categories.filter(category => this.assignedIds.includes(category.id));
    },
  },
  watch: {
    emails: {
      immediate: true,
      handler() {
        this.computeAssigned();
      },
    },
  },
  created() {
    this.$emailConnectorMailBoxService.getAvailableEmailCategories()
      .then(list => this.categories = list || []);
    this.$root.$on('email-categories-updated', this.onCategoriesUpdated);
  },
  beforeDestroy() {
    this.$root.$off('email-categories-updated', this.onCategoriesUpdated);
  },
  methods: {
    /**
     * The categories any message of the conversation carries.
     *
     * @returns {void}
     */
    computeAssigned() {
      const ids = new Set();
      (this.emails || []).forEach(email => (email.categoryIds || []).forEach(id => ids.add(id)));
      this.assignedIds = Array.from(ids);
    },
    /**
     * Whether a category is on the conversation.
     *
     * @param {Number} id the category id
     * @returns {Boolean} true when it is
     */
    isAssigned(id) {
      return this.assignedIds.includes(id);
    },
    /**
     * Follows a category assigned or removed elsewhere -- a mail dropped on a category
     * of the folder column while the reader shows it (EXO-90421): patches this
     * conversation's matching messages, by folder when the update names one, and the
     * chips with them.
     *
     * @param {Object} update {mailRemoteIds, categoryId, assign, folder}
     * @returns {void}
     */
    onCategoriesUpdated({ mailRemoteIds, categoryId, assign, folder }) {
      const targetIds = new Set(mailRemoteIds || []);
      const matching = (this.emails || []).filter(email => targetIds.has(email.mailRemoteId)
        && (!folder || (email.folder || 'INBOX') === folder));
      if (!matching.length) {
        return;
      }
      matching.forEach(email => {
        const current = email.categoryIds || [];
        this.$set(email, 'categoryIds', assign
          ? Array.from(new Set([...current, categoryId]))
          : current.filter(id => id !== categoryId));
      });
      this.computeAssigned();
    },
    /**
     * Tags or untags the whole conversation, one request per folder of its messages,
     * each folder's update announced with its folder -- which patches this bar and the
     * list (onCategoriesUpdated). A request that fails leaves its messages as they were.
     *
     * @param {Object} category the category
     * @param {Boolean} assign true to tag, false to untag
     * @returns {Promise} resolved once every request has answered
     */
    toggle(category, assign) {
      const service = this.$emailConnectorMailBoxService;
      return Promise.all(this.idsByFolder.map(([folder, ids]) => (assign
        ? service.linkEmailsToCategory(ids, category.id, folder)
        : service.unlinkEmailsFromCategory(ids, category.id, folder))
        .then(() => this.$root.$emit('email-categories-updated', {
          mailRemoteIds: ids,
          categoryId: category.id,
          assign,
          folder,
        }))
        .catch(() => {
          // Leave that folder's messages as they are if the server call fails.
        })));
    },
  },
};
</script>
