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
  <!-- The contact form, and nothing else: adding a contact means filling it in,
       editing one means finding it filled in.

       Browsing the company directory used to live here too, so that colleagues
       could be added as contacts by hand. It is gone on purpose. Colleagues
       already arrive on their own -- writing to one, or hearing from one, collects
       them -- and a contact whose address matches a platform profile is linked to
       that profile when it is read, so it shows their name, their picture and a way
       to their page without anybody importing anything. Offering an import as well
       meant two ways to end up with the same person, and a mode to choose between
       before any typing could start.

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
        <v-form
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
          <div class="text-sub-title mb-1">
            {{ $t('emailConnector.contacts.form.birthday') }}
          </div>
          <!-- A plain text field, NOT a date picker: vCard allows a birthday
               without a year (12-31), which no date input can hold, and forcing
               a year here is exactly the corruption the backend refuses to
               store. The server validates and answers invalidBirthday. -->
          <v-text-field
            v-model="form.birthday"
            :placeholder="$t('emailConnector.contacts.form.birthday.hint')"
            class="pt-0"
            outlined
            dense />
          <div class="text-sub-title mb-1">
            {{ $t('emailConnector.contacts.form.website') }}
          </div>
          <v-text-field
            v-model="form.website"
            type="url"
            class="pt-0"
            outlined
            dense />
          <!-- The address stays structured all the way down - five fields, the
               way the store and the vCard ADR keep it - so an export writes
               each component back into its own slot. -->
          <div class="text-sub-title mb-1">
            {{ $t('emailConnector.contacts.form.street') }}
          </div>
          <v-text-field
            v-model="form.street"
            class="pt-0"
            outlined
            dense />
          <div class="d-flex">
            <div class="flex-grow-1 me-2">
              <div class="text-sub-title mb-1">
                {{ $t('emailConnector.contacts.form.postalCode') }}
              </div>
              <v-text-field
                v-model="form.postalCode"
                class="pt-0"
                outlined
                dense />
            </div>
            <div class="flex-grow-1">
              <div class="text-sub-title mb-1">
                {{ $t('emailConnector.contacts.form.city') }}
              </div>
              <v-text-field
                v-model="form.city"
                class="pt-0"
                outlined
                dense />
            </div>
          </div>
          <div class="d-flex">
            <div class="flex-grow-1 me-2">
              <div class="text-sub-title mb-1">
                {{ $t('emailConnector.contacts.form.region') }}
              </div>
              <v-text-field
                v-model="form.region"
                class="pt-0"
                outlined
                dense />
            </div>
            <div class="flex-grow-1">
              <div class="text-sub-title mb-1">
                {{ $t('emailConnector.contacts.form.country') }}
              </div>
              <v-text-field
                v-model="form.country"
                class="pt-0"
                outlined
                dense />
            </div>
          </div>
          <div class="text-sub-title mb-1">
            {{ $t('emailConnector.contacts.form.note') }}
          </div>
          <!-- The platform's own editor, with the options compose uses: a note
               is prose, and typing it should feel like typing anywhere else in
               the platform rather than into a bare box. What reaches a vCard is
               still text -- the exporter flattens it -- because NOTE has no
               notion of markup. -->
          <rich-editor
            v-model="form.note"
            :placeholder="$t('emailConnector.contacts.form.note.placeholder')"
            ck-editor-type="contactNote"
            :tag-enabled="false"
            disable-suggester
            hide-chars-count />
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
      saving: false,
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
      return this.editedSource !== 'DIRECTORY' && this.editedSource !== 'CARDDAV';
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
     * The drawer's title: adding, or editing.
     *
     * @returns {string} the localized title
     */
    title() {
      return this.editedId && this.$t('emailConnector.contacts.form.edit.title')
        || this.$t('emailConnector.contacts.form.add.title');
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
        birthday: '',
        website: '',
        street: '',
        city: '',
        region: '',
        postalCode: '',
        country: '',
        note: '',
      };
    },
    /**
     * Opens the drawer: an empty form for a new contact, a filled one for an
     * existing contact.
     *
     * @param {object} contact - the contact to edit, or nothing to add
     * @returns {void}
     */
    open(contact) {
      this.editedId = contact?.id || null;
      this.editedSource = contact?.source || 'MANUAL';
      this.errorMessage = null;
      this.resetPhoto(contact);
      this.form = contact ? {
        primaryEmail: contact.primaryEmail || '',
        givenName: contact.givenName || '',
        familyName: contact.familyName || '',
        phone: contact.phones?.[0] || '',
        organization: contact.organization || '',
        birthday: contact.birthday || '',
        website: contact.website || '',
        street: contact.postalAddress?.street || '',
        city: contact.postalAddress?.city || '',
        region: contact.postalAddress?.region || '',
        postalCode: contact.postalAddress?.postalCode || '',
        country: contact.postalAddress?.country || '',
        note: contact.note || '',
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
     * The mimetype has to be handed over here, and cannot be left out: the
     * cropper only ever reads it from this call, while picking a file sets the
     * image but not the mimetype. Opened empty, Apply runs `mimetype.split('/')`
     * on null inside its upload promise, which then never settles -- the button
     * spins for good and no request is ever made. PNG is the honest answer
     * regardless of what the user picked, because the cropper re-encodes through
     * `canvas.toBlob()`, whose default is PNG.
     *
     * @returns {void}
     */
    openPhotoCropper() {
      this.$refs.contactPhotoCropDrawer?.open({mimetype: 'image/png'});
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
     * Creates or updates the contact, translating the server's message codes
     * (already-exists, unusable address) into the form's own error line.
     *
     * @returns {void}
     */
    save() {
      const hasAddress = this.form.street || this.form.city || this.form.region
        || this.form.postalCode || this.form.country;
      const contact = {
        id: this.editedId,
        primaryEmail: this.form.primaryEmail,
        givenName: this.form.givenName,
        familyName: this.form.familyName,
        phones: this.form.phone ? [this.form.phone] : null,
        organization: this.form.organization,
        birthday: this.form.birthday || null,
        website: this.form.website || null,
        // Null when every component is blank, so emptying the five fields
        // removes the stored address rather than saving a shell of blanks.
        postalAddress: hasAddress ? {
          street: this.form.street || null,
          city: this.form.city || null,
          region: this.form.region || null,
          postalCode: this.form.postalCode || null,
          country: this.form.country || null,
        } : null,
        note: this.form.note || null,
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
        } else if (error?.message?.includes('invalidBirthday')) {
          // The server's message code travels as the response body, which is
          // how a bad birthday is told apart from a bad address.
          this.errorMessage = this.$t('emailConnector.contacts.form.invalidBirthday');
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
