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
  <!-- The lazily mounted host of the chat actions' two drawers: the contacts
       app's own add/edit form (prefilled from a parsed card) and the picker.
       Mounted into #vuetify-apps on first use, exactly like the "+" quick
       action mounts the contacts app; driven by document events because the
       callers live in another Vue app (the chat's). -->
  <div>
    <email-connector-contact-form-drawer />
    <email-connector-chat-contact-picker-drawer ref="picker" />
  </div>
</template>

<script>
export default {
  created() {
    document.addEventListener('email-chat-open-contact-form', this.openForm);
    document.addEventListener('email-chat-open-contact-picker', this.openPicker);
    document.addEventListener('email-chat-contact-error', this.reportError);
  },
  beforeDestroy() {
    document.removeEventListener('email-chat-open-contact-form', this.openForm);
    document.removeEventListener('email-chat-open-contact-picker', this.openPicker);
    document.removeEventListener('email-chat-contact-error', this.reportError);
  },
  methods: {
    /**
     * Reports a failure the way this add-on reports everything else — the
     * module code cannot alert on its own, having no app root to emit on.
     *
     * @param {CustomEvent} event - detail carries {code}, a key of the
     *          contacts bundle
     * @returns {void}
     */
    reportError(event) {
      const code = event?.detail?.code;
      if (code) {
        this.$root.$emit('alert-message', this.$t(code), 'error');
      }
    },
    /**
     * Opens the contact form prefilled with the parsed card's fields — the
     * form drawer already listens on its own root, this only crosses the
     * app boundary for it.
     *
     * @param {CustomEvent} event - detail carries {contact}
     * @returns {void}
     */
    openForm(event) {
      this.$root.$emit('open-email-contact-form', event?.detail?.contact);
    },
    /**
     * Opens the contact picker, remembering the caller's callback.
     *
     * @param {CustomEvent} event - detail carries {onSelect}
     * @returns {void}
     */
    openPicker(event) {
      this.$refs.picker?.open(event?.detail?.onSelect);
    },
  },
};
</script>
