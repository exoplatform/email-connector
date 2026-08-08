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
  <!-- The take-away QR: the contact's vCard, text fields only, on screen for a
       phone camera. No app, no account, nothing emailed - the phone's own
       camera saves the person. The server strips the photo (a picture is far
       past what a scannable code holds); this side owns the other half of the
       honesty: a card too big even as text says so instead of rendering an
       unscannable code. -->
  <v-dialog
    v-model="dialog"
    max-width="360"
    @input="onToggle">
    <v-card class="pa-4 d-flex flex-column align-center">
      <div class="text-h6 text-color mb-1">
        {{ contactName }}
      </div>
      <div class="text-sub-title mb-3">
        {{ $t('emailConnector.contacts.qr.hint') }}
      </div>
      <v-progress-circular
        v-if="loading"
        indeterminate
        color="primary"
        class="my-8" />
      <div
        v-else-if="message"
        class="text-sub-title text-center my-6">
        {{ message }}
      </div>
      <!-- The library draws into this element; keyed on the contact so a
           reopened dialog never shows the previous person's code. -->
      <div
        v-show="!loading && !message"
        ref="qrCanvas"
        :key="contactId"></div>
      <v-btn
        class="btn mt-4"
        @click="close">
        {{ $t('emailConnector.contacts.qr.close') }}
      </v-btn>
    </v-card>
  </v-dialog>
</template>

<script>
// The hard ceiling of a QR code in byte mode at version 40 with correction
// level M: 2331 bytes. A vCard past it cannot be encoded at that level at all,
// and even approaching it makes a screen-to-phone scan flaky - so past the
// ceiling the dialog says "too large" instead of trying.
const QR_MAX_BYTES = 2331;

export default {
  data() {
    return {
      dialog: false,
      loading: false,
      contactId: null,
      contactName: '',
      message: null,
    };
  },
  created() {
    this.$root.$on('open-email-contact-qr', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-contact-qr', this.open);
  },
  methods: {
    /**
     * Opens the dialog on a contact and builds its code: the text-only vCard
     * from the server, measured against the QR ceiling, then drawn by the
     * platform's own qrcode module - loaded on demand, so pages that never
     * show a QR never pay for the library.
     *
     * @param {object} contact - the contact whose card to encode
     * @returns {void}
     */
    open(contact) {
      if (!contact?.id) {
        return;
      }
      this.contactId = contact.id;
      this.contactName = contact.displayName || contact.primaryEmail || '';
      this.message = null;
      this.loading = true;
      this.dialog = true;
      this.$emailConnectorContactsService.getContactVCard(contact.id)
        .then(vcard => this.render(vcard))
        .catch(() => this.message = this.$t('emailConnector.contacts.qr.error'))
        .finally(() => this.loading = false);
    },
    /**
     * Draws the code, or says why it cannot. The size gate measures BYTES, not
     * characters - the QR capacity is a byte budget and an accented name costs
     * two of them.
     *
     * @param {string} vcard - the text-only vCard the server answered
     * @returns {void}
     */
    render(vcard) {
      if (new TextEncoder().encode(vcard).length > QR_MAX_BYTES) {
        this.message = this.$t('emailConnector.contacts.qr.tooLarge');
        return;
      }
      window.require(['SHARED/qrcode'], loaded => {
        // The platform ships easy.qrcode as a plain script with no AMD alias, so
        // requiring it runs the file but hands back nothing: the library
        // publishes itself on the window. Taking whichever of the two is there
        // costs a line and survives the module being aliased properly one day.
        const QRCode = loaded || window.QRCode;
        if (!QRCode) {
          this.message = this.$t('emailConnector.contacts.qr.unavailable');
          return;
        }
        // $nextTick so the target div exists once the spinner is gone.
        this.$nextTick(() => {
          if (this.$refs.qrCanvas) {
            this.$refs.qrCanvas.innerHTML = '';
            new QRCode(this.$refs.qrCanvas, {
              text: vcard,
              width: 280,
              height: 280,
              // M keeps the code readable with a bit of screen glare while
              // leaving room for a full card - L would fit more but forgives
              // nothing, H would halve the budget.
              correctLevel: QRCode.CorrectLevel.M,
            });
          }
        });
      });
    },
    /**
     * Clears the dialog when it is toggled off by a click outside.
     *
     * @param {boolean} opened - the dialog's new state
     * @returns {void}
     */
    onToggle(opened) {
      if (!opened) {
        this.close();
      }
    },
    /**
     * Closes and resets the dialog.
     *
     * @returns {void}
     */
    close() {
      this.dialog = false;
      this.contactId = null;
      this.contactName = '';
      this.message = null;
      this.loading = false;
    },
  },
};
</script>
