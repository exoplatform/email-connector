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
  <!-- Manual add/edit, opened as a sibling drawer next to the list — the way
       the mailbox opens its composer. Adding an address the user once removed
       revives that contact server-side; the form never sees that as an error. -->
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
      saving: false,
      editedId: null,
      errorMessage: null,
      form: this.emptyForm(),
    };
  },
  computed: {
    /**
     * The drawer's title, add or edit.
     *
     * @returns {string} the localized title
     */
    title() {
      return this.editedId ? this.$t('emailConnector.contacts.form.edit.title')
        : this.$t('emailConnector.contacts.form.add.title');
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
     * Opens the form, blank for an add or prefilled for an edit.
     *
     * @param {object} contact - the contact to edit, or nothing to add
     * @returns {void}
     */
    open(contact) {
      this.editedId = contact?.id || null;
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
     * Closes and resets the form.
     *
     * @returns {void}
     */
    close() {
      this.formDrawer = false;
      this.editedId = null;
      this.errorMessage = null;
      this.form = this.emptyForm();
      this.$refs.emailContactFormDrawer.close();
    },
  },
};
</script>
