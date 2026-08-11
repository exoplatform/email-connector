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
          <!-- One row per address the person can be reached at. The star names
               the MAIN address, the one the list, the search and the compose
               autocomplete show; making another row the main one is just
               starring it -- a row only ever disappears by its own remove
               button, so changing the main address can never lose the old one.
               The star and the remove button only appear once there are several
               rows: on a single-address contact they would be noise. -->
          <div
            v-for="(email, index) in form.emails"
            :key="index"
            class="d-flex align-start">
            <v-text-field
              v-model="email.value"
              type="email"
              class="pt-0"
              :disabled="identityFromProfile"
              outlined
              dense
              :required="index === form.primaryIndex" />
            <v-btn
              v-if="form.emails.length > 1"
              :title="index === form.primaryIndex
                ? $t('emailConnector.contacts.form.primaryEmail')
                : $t('emailConnector.contacts.form.setPrimary')"
              :disabled="identityFromProfile"
              icon
              small
              class="mt-1 ms-1"
              @click="setPrimary(index)">
              <v-icon
                size="16"
                :class="index === form.primaryIndex ? 'primary--text' : ''">
                {{ index === form.primaryIndex ? 'fas fa-star' : 'far fa-star' }}
              </v-icon>
            </v-btn>
            <v-btn
              v-if="form.emails.length > 1 && !identityFromProfile"
              :title="$t('emailConnector.contacts.form.removeEmail')"
              icon
              small
              class="mt-1"
              @click="removeEmail(index)">
              <v-icon size="14">
                fas fa-times
              </v-icon>
            </v-btn>
          </div>
          <v-btn
            v-if="!identityFromProfile"
            class="mb-2 px-0"
            color="primary"
            text
            small
            @click="addEmail">
            <v-icon size="12" class="me-1">
              fas fa-plus
            </v-icon>
            {{ $t('emailConnector.contacts.form.addEmail') }}
          </v-btn>
          <!-- Said once, under the fields it explains, rather than a tooltip on
               each: a colleague's name and address are resolved from their
               profile on every read, so editing them here would be undone by the
               next one -- which is exactly what the line tells the reader. -->
          <div
            v-if="identityFromProfile"
            class="text-caption text-sub-title mb-2">
            {{ $t('emailConnector.contacts.form.fromProfile') }}
          </div>
          <div class="text-sub-title mb-1">
            {{ $t('emailConnector.contacts.form.givenName') }}
          </div>
          <v-text-field
            v-model="form.givenName"
            class="pt-0"
            :disabled="identityFromProfile"
            outlined
            dense />
          <div class="text-sub-title mb-1">
            {{ $t('emailConnector.contacts.form.familyName') }}
          </div>
          <v-text-field
            v-model="form.familyName"
            class="pt-0"
            :disabled="identityFromProfile"
            outlined
            dense />
          <div class="text-sub-title mb-1">
            {{ $t('emailConnector.contacts.form.phone') }}
          </div>
          <!-- One row per number, like the address rows above and for the same
               reason: the card and the store hold several, so a form holding
               one silently deleted the rest on every save. Each row names its
               type from the vCard vocabulary the exporter writes back — a
               number imported as "work" stays a work number through an edit. -->
          <div
            v-for="(phone, index) in form.phones"
            :key="index"
            class="d-flex align-start">
            <v-select
              v-model="phone.type"
              :items="phoneTypeItems"
              :aria-label="$t('emailConnector.contacts.form.phoneType')"
              class="pt-0 me-2 flex-grow-0"
              style="max-width: 110px;"
              outlined
              dense />
            <v-text-field
              v-model="phone.value"
              type="tel"
              class="pt-0"
              outlined
              dense />
            <v-btn
              v-if="form.phones.length > 1"
              :title="$t('emailConnector.contacts.form.removePhone')"
              icon
              small
              class="mt-1"
              @click="removePhone(index)">
              <v-icon size="14">
                fas fa-times
              </v-icon>
            </v-btn>
          </div>
          <v-btn
            class="mb-2 px-0"
            color="primary"
            text
            small
            @click="addPhone">
            <v-icon size="12" class="me-1">
              fas fa-plus
            </v-icon>
            {{ $t('emailConnector.contacts.form.addPhone') }}
          </v-btn>
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
          <!-- A plain box, not the platform's editor: every place this note ends
               up -- the vCard, Google and Apple Contacts, the phone -- holds
               text and nothing else, so formatting would be a promise thrown
               away on the first sync. -->
          <v-textarea
            v-model="form.note"
            :placeholder="$t('emailConnector.contacts.form.note.placeholder')"
            class="pt-0"
            rows="4"
            auto-grow
            outlined
            dense />
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
            :disabled="!primaryEmail"
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
      // The types a stored phone entry can name -- the exact lowercase vCard
      // vocabulary the backend stores as a `type,` prefix and the exporter
      // writes back as a TEL TYPE parameter. Order is display order.
      phoneTypes: ['cell', 'work', 'home', 'fax', 'pager'],
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
     * Whether this contact's identity belongs to a platform profile.
     * <p>
     * Their name and address are resolved live on every read, so the form shows
     * them and refuses them: typing there would be undone by the next read. What
     * the profile does not own -- birthday, address, note, website, phones -- is
     * the user's to keep and stays editable.
     *
     * @returns {boolean} true for a contact taken from the directory
     */
    identityFromProfile() {
      return this.editedSource === 'DIRECTORY';
    },
    /**
     * The address the contact will be filed under: the starred row, trimmed.
     * Empty while the user has not typed one, which is what keeps Save
     * disabled.
     *
     * @returns {string} the main address as typed
     */
    primaryEmail() {
      return (this.form.emails[this.form.primaryIndex]?.value || '').trim();
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
        .join('') || (this.primaryEmail || '?').charAt(0).toUpperCase();
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
    /**
     * The type choices of a phone row: the vCard vocabulary plus "no type",
     * which is what a bare legacy number reads as and saves back as.
     *
     * @returns {Array<object>} the v-select items
     */
    phoneTypeItems() {
      return [
        {text: this.$t('emailConnector.contacts.phoneType.none'), value: ''},
        ...this.phoneTypes.map(type => ({
          text: this.$t(`emailConnector.contacts.phoneType.${type}`),
          value: type,
        })),
      ];
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
        // Rows are objects, not bare strings: v-model on an array INDEX writes
        // past Vue 2's reactivity, while a row object's property is watched.
        emails: [{value: ''}],
        primaryIndex: 0,
        givenName: '',
        familyName: '',
        // Same row-object shape as the emails, for the same Vue 2 reason.
        phones: [{type: '', value: ''}],
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
      this.resetPhoto(contact);
      this.form = contact ? {
        // Every address the contact holds becomes a row -- a vCard prefill's
        // secondaries included, so nothing a card carried is lost at the
        // confirm step. The stored primary is always the first row.
        emails: [contact.primaryEmail || '', ...(contact.secondaryEmails || [])].map(value => ({value})),
        primaryIndex: 0,
        givenName: contact.givenName || '',
        familyName: contact.familyName || '',
        // EVERY number becomes a row, not just the first: the one-field form
        // is what silently deleted the others on the next save. A vCard
        // prefill's numbers arrive here too, types included.
        phones: (contact.phones?.length ? contact.phones : [''])
          .map(entry => this.splitPhone(entry)),
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
     * Adds an empty address row for the user to fill.
     *
     * @returns {void}
     */
    addEmail() {
      this.form.emails.push({value: ''});
    },
    /**
     * Removes one address row. When the removed row carried the star, the first
     * remaining row inherits it — the form must always name a main address —
     * and a star sitting below the removed row slides up with its owner.
     *
     * @param {number} index - the row to remove
     * @returns {void}
     */
    removeEmail(index) {
      this.form.emails.splice(index, 1);
      if (this.form.primaryIndex === index) {
        this.form.primaryIndex = 0;
      } else if (this.form.primaryIndex > index) {
        this.form.primaryIndex--;
      }
    },
    /**
     * Stars a row as the main address. The previous main row simply keeps its
     * place as a secondary — demoting is never removing.
     *
     * @param {number} index - the row to star
     * @returns {void}
     */
    setPrimary(index) {
      this.form.primaryIndex = index;
    },
    /**
     * One stored phone entry as a form row. The store encodes a typed number
     * as `type,value` and a typeless one bare; the prefix only counts as a
     * type when it is in the vocabulary, so a legacy bare number — or one
     * containing commas of its own — can never be misread as typed.
     *
     * @param {string} entry - the stored entry
     * @returns {object} the {type, value} row
     */
    splitPhone(entry) {
      const text = entry || '';
      const comma = text.indexOf(',');
      const prefix = comma > 0 ? text.slice(0, comma).trim().toLowerCase() : '';
      if (prefix && this.phoneTypes.includes(prefix)) {
        return {type: prefix, value: text.slice(comma + 1).trim()};
      }
      return {type: '', value: text.trim()};
    },
    /**
     * Adds an empty phone row for the user to fill.
     *
     * @returns {void}
     */
    addPhone() {
      this.form.phones.push({type: '', value: ''});
    },
    /**
     * Removes one phone row. The last row is not removable — it is cleared by
     * emptying it, and an empty row saves as no number.
     *
     * @param {number} index - the row to remove
     * @returns {void}
     */
    removePhone(index) {
      this.form.phones.splice(index, 1);
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
        primaryEmail: this.primaryEmail,
        // Always sent, empty included: a present list is the authoritative set
        // server-side (a removed row is a removal), while ABSENT means "keep
        // what is stored" — a contract for clients that cannot show the rows,
        // which this form is not.
        secondaryEmails: this.form.emails
          .filter((email, index) => index !== this.form.primaryIndex)
          .map(email => (email.value || '').trim())
          .filter(value => value),
        givenName: this.form.givenName,
        familyName: this.form.familyName,
        // Same contract as the addresses: always sent, empty included — a
        // present list is authoritative, so a removed row is a removal, while
        // only clients that cannot show the rows are allowed silence.
        phones: this.form.phones
          .map(phone => ({type: phone.type, value: (phone.value || '').trim()}))
          .filter(phone => phone.value)
          .map(phone => (phone.type ? `${phone.type},${phone.value}` : phone.value)),
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
      // A CardDAV row saves through the server push: the edit is merged into
      // the server's own card and kept only once the server accepted it.
      const call = this.editedId
        ? (this.editedSource === 'CARDDAV'
          ? this.$emailConnectorContactsService.updateAddressBookContact(contact)
          : this.$emailConnectorContactsService.updateContact(contact))
        : this.$emailConnectorContactsService.createContact(contact);
      call.then(() => {
        this.$root.$emit('email-contacts-refresh');
        this.close();
      }).catch(error => {
        let message;
        if (error?.message?.includes('update.conflict') || error?.message?.includes('update.entryGone')) {
          // Somebody changed (or removed) the entry on the server first. Their
          // change has already become the local row, so the list is refreshed
          // under the form -- while the form itself stays open, the user's
          // words still on screen, theirs to retry or abandon.
          message = this.$t('emailConnector.contacts.form.carddavConflict');
          this.$root.$emit('email-contacts-refresh');
        } else if (error?.message?.includes('invalidBirthday')) {
          // The server's message code travels as the response body, which is
          // how a bad birthday is told apart from a bad address.
          message = this.$t('emailConnector.contacts.form.invalidBirthday');
        } else if (error?.message?.includes('invalidEmail')) {
          message = this.$t('emailConnector.contacts.form.invalidEmail');
        } else if (error?.status === 409) {
          message = this.$t('emailConnector.contacts.form.alreadyExists');
        } else if (this.editedSource === 'CARDDAV') {
          // The push could not happen, so nothing was changed anywhere -- said
          // plainly, because "invalid email" would send the user hunting for a
          // typo that does not exist.
          message = this.$t('emailConnector.contacts.form.carddavPushError');
        } else {
          message = this.$t('emailConnector.contacts.form.invalidEmail');
        }
        // A toast, not a line under the last field: the form is taller than the
        // drawer, so a refusal printed at the bottom lands off-screen and the
        // save just looks like it did nothing.
        this.$root.$emit('alert-message', message, 'error');
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
      this.resetPhoto(null);
      this.form = this.emptyForm();
      this.$refs.emailContactFormDrawer.close();
    },
  },
};
</script>
