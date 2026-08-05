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
       form never sees that as an error.

       The wrapper div is load-bearing: the platform's crop drawer is a SIBLING of
       this drawer, not a child of it. A drawer carries its own z-index and so opens
       a stacking context, inside which a second fixed-position drawer could never
       rise above the first. -->
  <div>
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
          <div
            v-if="photoEditable"
            class="d-flex justify-center pb-6">
            <div class="position-relative">
              <v-avatar
                size="88"
                :color="photoPreview ? '' : 'primary'">
                <v-img
                  v-if="photoPreview"
                  :src="photoPreview" />
                <span
                  v-else
                  class="white--text text-h6">{{ photoInitials }}</span>
              </v-avatar>
              <v-btn
                :title="$t('emailConnector.contacts.form.photo.change')"
                class="position-absolute b-0 r-0 primary white--text"
                icon
                x-small
                @click="openPhotoCropper">
                <v-icon size="14">
                  fas fa-camera
                </v-icon>
              </v-btn>
              <v-btn
                v-if="hasOwnPhoto"
                :title="$t('emailConnector.contacts.form.photo.remove')"
                class="position-absolute b-0 l-0 error white--text"
                icon
                x-small
                @click="removePhoto">
                <v-icon size="14">
                  fas fa-trash
                </v-icon>
              </v-btn>
            </div>
          </div>
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
    <!-- The platform's own profile-picture cropper, borrowed whole: it owns the
         picking, the cropping and the upload, and hands back an upload id. Nothing
         is written until the form is saved, so backing out of the drawer leaves the
         contact exactly as it was. -->
    <image-crop-drawer
      v-if="photoEditable"
      ref="contactPhotoCropDrawer"
      :drawer-title="$t('emailConnector.contacts.form.photo.title')"
      :crop-options="{aspectRatio: 1, viewMode: 1}"
      :max-file-size="maxPhotoFileSize"
      :max-image-width="maxPhotoWidth"
      circle
      @apply="onPhotoCropped" />
  </div>
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
      // The edited row's source: it is what says whether the picture is ours to
      // own. A brand-new contact is MANUAL, so it always is.
      editedSource: 'MANUAL',
      errorMessage: null,
      // null = the request says nothing about the photo, '' = remove it, an upload
      // id = set it. The same three states the server documents, held here until
      // the form is saved.
      photoUploadId: null,
      photoPreview: null,
      hasOwnPhoto: false,
      // An avatar is displayed small everywhere it appears; 350px is what the
      // platform's own profile cropper settles on, and 1 MB is generous for it.
      maxPhotoWidth: 350,
      maxPhotoFileSize: 1024 * 1024,
      form: this.emptyForm(),
    };
  },
  computed: {
    /**
     * Whether this contact's picture is ours to edit. A directory-linked row's
     * avatar IS the colleague's platform profile, and a local copy would drift from
     * it silently; a CardDAV row's belongs to the address book server. The server
     * refuses both anyway — this only keeps the form from offering what it would
     * refuse.
     *
     * @returns {boolean} true when the photo block shows
     */
    photoEditable() {
      return !this.searchMode && this.editedSource !== 'DIRECTORY' && this.editedSource !== 'CARDDAV';
    },
    /**
     * The initials standing in for a contact with no picture, read from the form as
     * it is being typed rather than from the stored contact — so the placeholder
     * follows the name the user is entering.
     *
     * @returns {string} up to two uppercased initials
     */
    photoInitials() {
      return `${this.form.givenName || ''} ${this.form.familyName || ''}`.trim()
        .split(/\s+/)
        .filter(word => word)
        .map(word => word.charAt(0).toUpperCase())
        .slice(0, 2)
        .join('') || (this.form.primaryEmail || '?').charAt(0).toUpperCase();
    },
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
      this.editedSource = contact?.source || 'MANUAL';
      this.searchMode = !contact;
      this.term = '';
      this.users = [];
      this.selected = [];
      this.errorMessage = null;
      this.resetPhoto(contact);
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
     * Puts the photo state back to "this is what is stored, and I am not touching
     * it". The preview shows whatever avatar the server resolved — our own picture
     * when there is one, otherwise the platform profile that happens to share the
     * address — but removal is only offered for a picture the contact actually
     * owns, which is what photoFileId says and avatarUrl cannot.
     *
     * @param {object} contact - the contact being edited, or nothing when adding
     * @returns {void}
     */
    resetPhoto(contact) {
      this.photoUploadId = null;
      this.photoPreview = contact?.avatarUrl || null;
      this.hasOwnPhoto = !!contact?.photoFileId;
    },
    /**
     * Opens the platform's crop drawer on top of this one.
     *
     * @returns {void}
     */
    openPhotoCropper() {
      this.$refs.contactPhotoCropDrawer?.open();
    },
    /**
     * Takes the cropped result: the upload id travels with the next save, and the
     * data URL the cropper hands back is shown at once, so the change reads as
     * applied without waiting for a round-trip.
     *
     * @param {object} cropped - the crop drawer's payload {src, uploadId, ...}
     * @returns {void}
     */
    onPhotoCropped(cropped) {
      this.photoUploadId = cropped?.uploadId || null;
      this.photoPreview = cropped?.src || this.photoPreview;
      this.hasOwnPhoto = !!this.photoUploadId;
    },
    /**
     * Drops the picture. The empty upload id is the request's way of saying
     * "remove", which an absent one could not: the client round-trips the contact
     * it read, so silence has to mean "leave it alone".
     *
     * @returns {void}
     */
    removePhoto() {
      this.photoUploadId = '';
      this.photoPreview = null;
      this.hasOwnPhoto = false;
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
        photoUploadId: this.photoUploadId,
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
      this.editedSource = 'MANUAL';
      this.errorMessage = null;
      this.resetPhoto(null);
      this.form = this.emptyForm();
      this.$refs.emailContactFormDrawer.close();
    },
  },
};
</script>
