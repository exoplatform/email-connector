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
  <!-- One door for "I want a new contact".

       There were two before — add by hand, and import from the company directory —
       which made the user choose where the data comes from before typing anything.
       They type instead, and the typing says which they meant: a name searches
       colleagues, an address is unmistakably somebody new. The form stays out of
       sight until the search cannot answer, so this reads as a search box rather
       than a form. Editing an existing contact opens straight into the form.

       Adding an address the user once removed revives that contact server-side; the
       form never sees that as an error. -->
  <exo-drawer
    id="emailContactFormDrawer"
    ref="emailContactFormDrawer"
    v-model="formDrawer"
    right
    @closed="close">
    <template #title>
      <span>{{ title }}</span>
    </template>
    <template v-if="formDrawer" #content>
      <div v-if="searchMode" class="pa-4">
        <v-text-field
          v-model="term"
          :placeholder="$t('emailConnector.contacts.new.searchPlaceholder')"
          prepend-inner-icon="fas fa-search"
          class="pt-0"
          outlined
          dense
          clearable
          @input="onSearchInput" />
        <!-- Nobody in the directory matches and what was typed is an address: offer
             it as the new contact, rather than making the user find a second button
             for the very case the search just failed at. -->
        <v-list v-if="offerTypedAddress" class="pa-0">
          <v-list-item @click="startFormWithTypedAddress">
            <v-list-item-avatar
              size="36"
              class="my-1 me-3">
              <v-avatar color="primary" size="36">
                <v-icon size="16" class="white--text">
                  fas fa-user-plus
                </v-icon>
              </v-avatar>
            </v-list-item-avatar>
            <v-list-item-content class="py-1" style="min-width: 0;">
              <v-list-item-title class="text-color">
                {{ $t('emailConnector.contacts.new.addTyped') }}
              </v-list-item-title>
              <v-list-item-subtitle class="text-sub-title">
                {{ trimmedTerm }}
              </v-list-item-subtitle>
            </v-list-item-content>
          </v-list-item>
        </v-list>
        <v-list v-if="users.length" class="pa-0">
          <v-list-item
            v-for="user in users"
            :key="user.username"
            @click="toggle(user)">
            <v-list-item-avatar
              size="36"
              class="my-1 me-3">
              <v-img v-if="user.avatar" :src="user.avatar" />
              <v-avatar
                v-else
                color="primary"
                size="36">
                <span class="white--text text-caption">{{ initials(user) }}</span>
              </v-avatar>
            </v-list-item-avatar>
            <v-list-item-content class="py-1" style="min-width: 0;">
              <v-list-item-title class="text-color">
                {{ user.fullname }}
              </v-list-item-title>
              <v-list-item-subtitle class="text-sub-title">
                {{ user.position || user.email }}
              </v-list-item-subtitle>
            </v-list-item-content>
            <v-list-item-action class="my-auto">
              <v-icon
                :class="isSelected(user) && 'primary--text' || 'icon-default-color'"
                size="18">
                {{ isSelected(user) && 'fas fa-check-square' || 'far fa-square' }}
              </v-icon>
            </v-list-item-action>
          </v-list-item>
        </v-list>
        <div
          v-if="!trimmedTerm"
          class="text-sub-title text-center py-6">
          {{ $t('emailConnector.contacts.new.hint') }}
        </div>
        <div class="text-center pt-4">
          <v-btn
            text
            small
            class="primary--text"
            @click="startFormManually">
            {{ $t('emailConnector.contacts.new.manual') }}
          </v-btn>
        </div>
      </div>
      <v-form
        v-else
        ref="contactForm"
        class="pa-4"
        @submit.prevent="save">
        <div class="text-sub-title mb-1">
          {{ $t('emailConnector.contacts.form.email') }} *
        </div>
        <v-text-field
          v-model="form.primaryEmail"
          type="email"
          class="pt-0"
          outlined
          dense
          required />
        <div class="text-sub-title mb-1">
          {{ $t('emailConnector.contacts.form.givenName') }}
        </div>
        <v-text-field
          v-model="form.givenName"
          class="pt-0"
          outlined
          dense />
        <div class="text-sub-title mb-1">
          {{ $t('emailConnector.contacts.form.familyName') }}
        </div>
        <v-text-field
          v-model="form.familyName"
          class="pt-0"
          outlined
          dense />
        <div class="text-sub-title mb-1">
          {{ $t('emailConnector.contacts.form.phone') }}
        </div>
        <v-text-field
          v-model="form.phone"
          class="pt-0"
          outlined
          dense />
        <div class="text-sub-title mb-1">
          {{ $t('emailConnector.contacts.form.organization') }}
        </div>
        <v-text-field
          v-model="form.organization"
          class="pt-0"
          outlined
          dense />
        <div
          v-if="errorMessage"
          class="error--text mb-2">
          {{ errorMessage }}
        </div>
      </v-form>
    </template>
    <template #footer>
      <div class="d-flex justify-end">
        <v-btn
          class="btn me-2"
          @click="close">
          {{ $t('emailConnector.contacts.form.cancel') }}
        </v-btn>
        <v-btn
          v-if="searchMode"
          :disabled="!selected.length"
          :loading="importing"
          class="btn btn-primary"
          @click="importSelected">
          {{ $t('emailConnector.contacts.new.add', [selected.length]) }}
        </v-btn>
        <v-btn
          v-else
          :disabled="!form.primaryEmail"
          :loading="saving"
          class="btn btn-primary"
          @click="save">
          {{ $t('emailConnector.contacts.form.save') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data() {
    return {
      formDrawer: false,
      // The drawer opens searching and becomes a form only when the search cannot
      // answer — or straight away, when editing an existing contact.
      searchMode: false,
      saving: false,
      importing: false,
      term: '',
      users: [],
      selected: [],
      searchToken: 0,
      editedId: null,
      errorMessage: null,
      form: this.emptyForm(),
    };
  },
  computed: {
    /**
     * The drawer's title: searching, adding by hand, or editing.
     *
     * @returns {string} the localized title
     */
    title() {
      if (this.editedId) {
        return this.$t('emailConnector.contacts.form.edit.title');
      }
      return this.searchMode && this.$t('emailConnector.contacts.new.title')
        || this.$t('emailConnector.contacts.form.add.title');
    },
    /**
     * What was typed, trimmed — the term both the search and the offer read.
     *
     * @returns {string} the trimmed term
     */
    trimmedTerm() {
      return (this.term || '').trim();
    },
    /**
     * Whether to offer the typed text as a new contact: it looks like an address and
     * the directory has nobody by that name. Anything else is a name still being
     * typed, and offering to "add" it would be noise.
     *
     * @returns {boolean} true when the offer row shows
     */
    offerTypedAddress() {
      return !this.users.length && /^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(this.trimmedTerm);
    },
  },
  created() {
    this.$root.$on('open-email-contact-form', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-contact-form', this.open);
  },
  methods: {
    /**
     * A blank form.
     *
     * @returns {object} the form fields
     */
    emptyForm() {
      return {
        primaryEmail: '',
        givenName: '',
        familyName: '',
        phone: '',
        organization: '',
      };
    },
    /**
     * Opens the drawer: searching for a new contact, or on the filled form when
     * editing an existing one.
     *
     * @param {object} contact - the contact to edit, or nothing to add
     * @returns {void}
     */
    open(contact) {
      this.editedId = contact?.id || null;
      this.searchMode = !contact;
      this.term = '';
      this.users = [];
      this.selected = [];
      this.errorMessage = null;
      this.form = contact ? {
        primaryEmail: contact.primaryEmail || '',
        givenName: contact.givenName || '',
        familyName: contact.familyName || '',
        phone: contact.phones?.[0] || '',
        organization: contact.organization || '',
      } : this.emptyForm();
      this.formDrawer = true;
      this.$refs.emailContactFormDrawer.open();
    },
    /**
     * Debounces the directory search while the user types.
     *
     * @returns {void}
     */
    onSearchInput() {
      window.clearTimeout(this.searchTimer);
      this.searchTimer = window.setTimeout(() => this.searchDirectory(), 300);
    },
    /**
     * Searches the platform directory. A blank term shows nothing rather than
     * listing everyone: this is a search box, and a directory of tens of thousands
     * is not a list anybody browses.
     *
     * @returns {Promise} resolves once the directory answered
     */
    async searchDirectory() {
      this.searchToken++;
      const token = this.searchToken;
      if (!this.trimmedTerm) {
        this.users = [];
        return;
      }
      try {
        const data = await this.$emailConnectorContactsService.searchDirectoryUsers(this.trimmedTerm, 50);
        if (token === this.searchToken) {
          this.users = (data?.users || []).filter(user => user.username);
        }
      } catch (error) {
        if (token === this.searchToken) {
          this.users = [];
        }
      }
    },
    /**
     * Whether a directory user is picked.
     *
     * @param {object} user - the directory user
     * @returns {boolean} true when picked
     */
    isSelected(user) {
      return this.selected.includes(user.username);
    },
    /**
     * Picks or unpicks a directory user.
     *
     * @param {object} user - the directory user
     * @returns {void}
     */
    toggle(user) {
      this.selected = this.isSelected(user)
        && this.selected.filter(username => username !== user.username)
        || this.selected.concat(user.username);
    },
    /**
     * The initials of a directory user with no avatar.
     *
     * @param {object} user - the directory user
     * @returns {string} one or two letters
     */
    initials(user) {
      return (user.fullname || user.username || '')
        .split(/\s+/)
        .filter(part => part)
        .slice(0, 2)
        .map(part => part.charAt(0).toUpperCase())
        .join('');
    },
    /**
     * Imports the picked colleagues as linked contacts.
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
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.contacts.new.importError'), 'error'))
        .finally(() => this.importing = false);
    },
    /**
     * Turns the typed address into a new contact: the form opens with it filled in,
     * so the typing the user already did is not thrown away.
     *
     * @returns {void}
     */
    startFormWithTypedAddress() {
      this.form = this.emptyForm();
      this.form.primaryEmail = this.trimmedTerm;
      this.searchMode = false;
    },
    /**
     * Opens the empty form, for somebody who is neither a colleague nor an address
     * the user has to hand.
     *
     * @returns {void}
     */
    startFormManually() {
      this.form = this.emptyForm();
      this.searchMode = false;
    },
    /**
     * Creates or updates the contact, translating the server's message codes
     * (already-exists, unusable address) into the form's own error line.
     *
     * @returns {void}
     */
    save() {
      const contact = {
        id: this.editedId,
        primaryEmail: this.form.primaryEmail,
        givenName: this.form.givenName,
        familyName: this.form.familyName,
        phones: this.form.phone ? [this.form.phone] : null,
        organization: this.form.organization,
      };
      this.saving = true;
      this.errorMessage = null;
      const call = this.editedId ? this.$emailConnectorContactsService.updateContact(contact)
        : this.$emailConnectorContactsService.createContact(contact);
      call.then(() => {
        this.$root.$emit('email-contacts-refresh');
        this.close();
      }).catch(error => {
        if (error?.status === 409) {
          this.errorMessage = this.$t('emailConnector.contacts.form.alreadyExists');
        } else {
          this.errorMessage = this.$t('emailConnector.contacts.form.invalidEmail');
        }
      }).finally(() => this.saving = false);
    },
    /**
     * Closes and resets the drawer.
     *
     * @returns {void}
     */
    close() {
      this.formDrawer = false;
      this.searchMode = false;
      this.term = '';
      this.users = [];
      this.selected = [];
      this.editedId = null;
      this.errorMessage = null;
      this.form = this.emptyForm();
      this.$refs.emailContactFormDrawer.close();
    },
  },
};
</script>
