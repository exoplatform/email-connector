<!--
Copyright (C) 2025 eXo Platform SAS.

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
  <!-- "Add from directory": a search-driven picker over the platform's people
       directory — search-first because that is how anyone uses a large
       directory; there is deliberately no browse. The chosen people are
       imported as LINKED contacts (the platform identity travels), so their
       name and avatar keep following the directory. -->
  <exo-drawer
    id="emailContactDirectoryPickerDrawer"
    ref="emailContactDirectoryPickerDrawer"
    v-model="pickerDrawer"
    right
    go-back-button
    :loading="searching"
    use-filter
    :filter-placeholder="$t('emailConnector.contacts.directoryPicker.searchPlaceholder')"
    @filter-updated="onSearchUpdated"
    @closed="close">
    <template #title>
      <span>{{ $t('emailConnector.contacts.directoryPicker.title') }}</span>
    </template>
    <template v-if="pickerDrawer" #content>
      <v-list
        v-if="users.length"
        dense
        class="pt-0">
        <v-list-item
          v-for="user in users"
          :key="user.username"
          class="px-4"
          @click="toggle(user)">
          <v-list-item-avatar
            size="32"
            class="my-1 me-3">
            <v-img
              v-if="user.avatar"
              :src="user.avatar" />
            <v-avatar
              v-else
              color="primary"
              size="32">
              <span class="white--text text-caption">{{ initialsOf(user) }}</span>
            </v-avatar>
          </v-list-item-avatar>
          <v-list-item-content class="py-1">
            <v-list-item-title class="text-color">
              {{ user.fullname }}
            </v-list-item-title>
            <v-list-item-subtitle class="text-sub-title">
              {{ user.position || user.email }}
            </v-list-item-subtitle>
          </v-list-item-content>
          <v-list-item-action class="my-auto">
            <v-icon
              size="16"
              :class="isSelected(user) ? 'primary--text' : 'text-light-color'">
              {{ isSelected(user) ? 'fas fa-check-square' : 'far fa-square' }}
            </v-icon>
          </v-list-item-action>
        </v-list-item>
      </v-list>
      <div
        v-else
        class="d-flex flex-column align-center justify-center text-sub-title py-10">
        <v-icon
          size="42"
          class="text-sub-title mb-4">
          fas fa-users
        </v-icon>
        <span class="px-6 text-center">{{ emptyLabel }}</span>
      </div>
    </template>
    <template #footer>
      <div class="d-flex justify-end">
        <v-btn
          class="btn me-2"
          @click="close">
          {{ $t('emailConnector.contacts.form.cancel') }}
        </v-btn>
        <v-btn
          :disabled="!selected.length"
          :loading="importing"
          class="btn btn-primary"
          @click="importSelected">
          {{ $t('emailConnector.contacts.directoryPicker.add', {0: selected.length}) }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      pickerDrawer: false,
      searching: false,
      importing: false,
      term: null,
      users: [],
      selected: [],
      searchTimeout: null,
      searchToken: 0,
    };
  },
  computed: {
    /**
     * The empty state: an invitation to type before any search, a no-match
     * message after one.
     *
     * @returns {string} the localized label
     */
    emptyLabel() {
      return this.term ? this.$t('emailConnector.contacts.directoryPicker.noResult')
        : this.$t('emailConnector.contacts.directoryPicker.typeToSearch');
    },
  },
  created() {
    this.$root.$on('open-email-contact-directory-picker', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-contact-directory-picker', this.open);
  },
  methods: {
    /**
     * Opens the picker, blank: the directory is searched, never listed.
     *
     * @returns {void}
     */
    open() {
      this.term = null;
      this.users = [];
      this.selected = [];
      this.pickerDrawer = true;
      this.$refs.emailContactDirectoryPickerDrawer.open();
    },
    /**
     * Debounces the search field then queries the platform's people directory
     * live — its paging, its ACLs.
     *
     * @param {string} term - the typed text
     * @returns {void}
     */
    onSearchUpdated(term) {
      window.clearTimeout(this.searchTimeout);
      this.searchTimeout = window.setTimeout(() => {
        this.term = term?.trim() || null;
        this.search();
      }, 300);
    },
    /**
     * Runs the directory search; a blank term empties the list rather than
     * listing everyone.
     *
     * @returns {Promise<void>} resolves when the directory answered
     */
    async search() {
      this.searchToken++;
      const token = this.searchToken;
      if (!this.term) {
        this.users = [];
        return;
      }
      this.searching = true;
      try {
        const data = await this.$emailConnectorContactsService.searchDirectoryUsers(this.term, 50);
        if (token === this.searchToken) {
          this.users = (data?.users || []).filter(user => user.username);
        }
      } finally {
        if (token === this.searchToken) {
          this.searching = false;
        }
      }
    },
    /**
     * Whether a directory user is currently picked.
     *
     * @param {object} user - the directory user
     * @returns {boolean} true when picked
     */
    isSelected(user) {
      return this.selected.includes(user.username);
    },
    /**
     * Toggles a directory user in the selection.
     *
     * @param {object} user - the directory user
     * @returns {void}
     */
    toggle(user) {
      if (this.isSelected(user)) {
        this.selected = this.selected.filter(username => username !== user.username);
      } else {
        this.selected = this.selected.concat(user.username);
      }
    },
    /**
     * Imports the picked colleagues as linked contacts, refreshes the list and
     * closes.
     *
     * @returns {void}
     */
    importSelected() {
      this.importing = true;
      this.$emailConnectorContactsService.importDirectoryContacts(this.selected)
        .then(() => {
          this.$root.$emit('email-contacts-refresh');
          this.close();
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.contacts.directoryPicker.error'), 'error'))
        .finally(() => this.importing = false);
    },
    /**
     * The initials of a directory user without an avatar.
     *
     * @param {object} user - the directory user
     * @returns {string} up to two uppercased initials
     */
    initialsOf(user) {
      return (user.fullname || '?').split(/\s+/)
        .filter(word => word)
        .map(word => word.charAt(0).toUpperCase())
        .slice(0, 2)
        .join('');
    },
    /**
     * Closes and resets the picker.
     *
     * @returns {void}
     */
    close() {
      this.pickerDrawer = false;
      this.users = [];
      this.selected = [];
      this.term = null;
      this.$refs.emailContactDirectoryPickerDrawer.close();
    },
  },
};
</script>
