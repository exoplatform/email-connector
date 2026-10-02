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
  <!-- The mailbox's advanced search (EXO-90838), a drawer over the mailbox like its other
       pickers: the sender, a recipient, words of the subject or the message, a range of
       days, the folder, the messages with an attachment, and the categories eXo filed
       them under (EXO-90888). Unread and Favorites are the search row's own chips, never
       set here.
       Laid out like the platform's drawer forms: a plain label above each field, the two
       days as two date pickers side by side, empty meaning no bound. "Search" hands the
       criteria to the mailbox drawer, which shows them as chips under the search row;
       "Reset" empties the form. -->
  <exo-drawer
    id="emailAdvancedSearchDrawer"
    ref="drawer"
    v-model="drawer"
    right>
    <template #title>
      <span>{{ $t('emailConnector.mailBox.search.advanced.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <v-form
        class="pa-4"
        @submit.prevent="apply">
        <div class="mb-2">{{ $t('emailConnector.mailBox.search.advanced.from') }}</div>
        <v-text-field
          v-model="criteria.from"
          :placeholder="$t('emailConnector.mailBox.search.advanced.from.placeholder')"
          :aria-label="$t('emailConnector.mailBox.search.advanced.from')"
          :maxlength="MAX_TEXT_LENGTH"
          class="border-box-sizing width-auto pt-0"
          type="text"
          outlined
          dense
          hide-details />
        <div class="mt-4 mb-2">{{ $t('emailConnector.mailBox.search.advanced.to') }}</div>
        <v-text-field
          v-model="criteria.to"
          :placeholder="$t('emailConnector.mailBox.search.advanced.to.placeholder')"
          :aria-label="$t('emailConnector.mailBox.search.advanced.to')"
          :maxlength="MAX_TEXT_LENGTH"
          class="border-box-sizing width-auto pt-0"
          type="text"
          outlined
          dense
          hide-details />
        <div class="mt-4 mb-2">{{ $t('emailConnector.mailBox.search.advanced.words') }}</div>
        <v-text-field
          v-model="criteria.words"
          :placeholder="$t('emailConnector.mailBox.search.advanced.words.placeholder')"
          :aria-label="$t('emailConnector.mailBox.search.advanced.words')"
          :maxlength="MAX_TEXT_LENGTH"
          class="border-box-sizing width-auto pt-0"
          type="text"
          outlined
          dense
          hide-details />
        <div class="d-flex mt-4">
          <div class="col-6 pa-0 pe-2">
            <div class="mb-2">{{ $t('emailConnector.mailBox.search.advanced.after') }}</div>
            <date-picker
              ref="afterPicker"
              v-model="criteria.after"
              :default-value="false"
              :max-value="latestAfter"
              :left="$vuetify.rtl"
              :placeholder="$t('emailConnector.mailBox.search.advanced.day.none')"
              :aria-label="$t('emailConnector.mailBox.search.advanced.after')"
              return-iso>
              <template #footer>
                <v-btn
                  class="ms-auto"
                  color="primary"
                  small
                  text
                  @click="clearDay('after')">
                  {{ $t('emailConnector.mailBox.search.advanced.day.clear') }}
                </v-btn>
              </template>
            </date-picker>
          </div>
          <div class="col-6 pa-0 ps-2">
            <div class="mb-2">{{ $t('emailConnector.mailBox.search.advanced.before') }}</div>
            <date-picker
              ref="beforePicker"
              v-model="criteria.before"
              :default-value="false"
              :min-value="earliestBefore"
              :left="!$vuetify.rtl"
              :placeholder="$t('emailConnector.mailBox.search.advanced.day.none')"
              :aria-label="$t('emailConnector.mailBox.search.advanced.before')"
              return-iso>
              <template #footer>
                <v-btn
                  class="ms-auto"
                  color="primary"
                  small
                  text
                  @click="clearDay('before')">
                  {{ $t('emailConnector.mailBox.search.advanced.day.clear') }}
                </v-btn>
              </template>
            </date-picker>
          </div>
        </div>
        <template v-if="folders.length > 1">
          <div class="mt-4 mb-2">{{ $t('emailConnector.mailBox.search.advanced.folder') }}</div>
          <v-select
            ref="folderSelect"
            v-model="criteria.folder"
            :items="folders"
            :menu-props="{ bottom: true, offsetY: true }"
            :aria-label="$t('emailConnector.mailBox.search.advanced.folder')"
            item-text="label"
            item-value="key"
            class="pa-0"
            dense
            outlined
            hide-details
            @blur="$refs.folderSelect.blur()" />
        </template>
        <template v-if="categories.length">
          <!-- The mailbox's categories, as the folder column lists them, several at once:
               a mail filed under one of them matches, a category taking its subcategories
               along. -->
          <div class="mt-4 mb-2">{{ $t('emailConnector.mailBox.search.advanced.category') }}</div>
          <v-select
            ref="categorySelect"
            v-model="criteria.categoryIds"
            :items="categories"
            :menu-props="{ bottom: true, offsetY: true }"
            :placeholder="$t('emailConnector.mailBox.search.advanced.category.none')"
            :aria-label="$t('emailConnector.mailBox.search.advanced.category')"
            item-text="name"
            item-value="id"
            class="pa-0"
            multiple
            small-chips
            deletable-chips
            dense
            outlined
            hide-details
            @blur="$refs.categorySelect.blur()" />
        </template>
        <v-checkbox
          v-model="criteria.attachment"
          :label="$t('emailConnector.mailBox.search.advanced.attachment')"
          class="mt-4"
          hide-details
          dense />
        <!-- Submitted by the Enter key from a text field. -->
        <button type="submit" class="d-none"></button>
      </v-form>
    </template>
    <template #footer>
      <div class="d-flex align-center justify-end">
        <v-btn
          class="btn"
          @click="reset">
          {{ $t('emailConnector.mailBox.search.advanced.reset') }}
        </v-btn>
        <v-btn
          class="btn btn-primary ms-5"
          @click="apply">
          {{ $t('emailConnector.mailBox.search.advanced.apply') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import { OPEN_ADVANCED_SEARCH_EVENT, APPLY_ADVANCED_SEARCH_EVENT } from '../../js/EmailConnectorMailBoxAdvancedSearchMixin.js';
import { emptySearchCriteria, nextSearchDay } from '../../js/EmailConnectorMailBoxSearchCriteria.js';

// The longest text a field takes: a criterion, not a document.
const MAX_TEXT_LENGTH = 200;

export default {
  data: () => ({
    MAX_TEXT_LENGTH,
    drawer: false,
    // The criteria being edited: {from, to, words, after, before, attachment, folder,
    // categoryIds}.
    criteria: emptySearchCriteria(),
    // The folders the search offers, [{key, label}], and the one shown when it opened.
    folders: [],
    // The categories the search offers, [{id, name, icon}] (EXO-90888).
    categories: [],
    shownFolder: null,
  }),
  computed: {
    /**
     * The latest first day: the day before the excluded last one.
     *
     * @returns {String} yyyy-MM-dd, or null with no last day
     */
    latestAfter() {
      if (!this.criteria.before) {
        return null;
      }
      const [year, month, day] = this.criteria.before.split('-').map(Number);
      return new Date(Date.UTC(year, month - 1, day - 1)).toISOString().substring(0, 10);
    },
    /**
     * The earliest last day: the day after the first one, since the last is excluded.
     *
     * @returns {String} yyyy-MM-dd, or null with no first day
     */
    earliestBefore() {
      return nextSearchDay(this.criteria.after);
    },
  },
  created() {
    this.$root.$on(OPEN_ADVANCED_SEARCH_EVENT, this.open);
  },
  beforeDestroy() {
    this.$root.$off(OPEN_ADVANCED_SEARCH_EVENT, this.open);
  },
  methods: {
    /**
     * Opens the drawer on the search as the mailbox drawer has it.
     *
     * @param {Object} search {criteria, folders, categories, shownFolder}
     * @returns {void}
     */
    open(search) {
      this.criteria = { ...emptySearchCriteria(), ...(search?.criteria || {}) };
      // A copy: the list is edited here, the search's own only once applied.
      this.criteria.categoryIds = [...(this.criteria.categoryIds || [])];
      this.shownFolder = search?.shownFolder || this.criteria.folder;
      this.folders = search?.folders || [];
      this.categories = search?.categories || [];
      this.$refs.drawer.open();
    },
    /**
     * Hands the criteria to the mailbox drawer, which searches, and closes.
     *
     * @returns {void}
     */
    apply() {
      this.$root.$emit(APPLY_ADVANCED_SEARCH_EVENT, {
        criteria: { ...this.criteria, categoryIds: [...(this.criteria.categoryIds || [])] },
      });
      this.$refs.drawer.close();
    },
    /**
     * Empties the form; the folder goes back to the one the drawer was opened on.
     *
     * @returns {void}
     */
    reset() {
      this.criteria = { ...emptySearchCriteria(), folder: this.shownFolder };
    },
    /**
     * Empties one day and closes its calendar.
     *
     * @param {String} day after or before
     * @returns {void}
     */
    clearDay(day) {
      this.criteria[day] = null;
      const picker = this.$refs[`${day}Picker`];
      if (picker) {
        picker.menu = false;
      }
    },
  },
};
</script>
