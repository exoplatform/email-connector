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
  <!-- The confirmation of a forwarding destination (EXO-90656), shared by the forward's
       drawer and the filter form's "Forward to": nothing forwards to an address before
       the code eXo sends there is entered here. A destination confirmed once says so and
       needs no new code. The code is sent by the platform, never from the user's
       mailbox; the server limits the sends and the tries, and says why when it refuses. -->
  <div class="mt-2">
    <div
      v-if="confirmed"
      class="d-flex align-center text-subtitle"
      role="status">
      <v-icon size="14" class="success--text me-2">fa-check-circle</v-icon>
      {{ $t('UserSettings.emailConnector.forwarding.confirm.done') }}
    </div>
    <template v-else-if="destination">
      <div class="d-flex align-center">
        <span class="text-subtitle flex-grow-1 me-2">
          {{ sentTo === destination
            ? $t('UserSettings.emailConnector.forwarding.confirm.sent', { 0: destination })
            : $t('UserSettings.emailConnector.forwarding.confirm.needed') }}
        </span>
        <v-btn
          :loading="sending"
          :disabled="disabled"
          class="btn"
          small
          @click="send">
          {{ sentTo === destination
            ? $t('UserSettings.emailConnector.forwarding.confirm.resend')
            : $t('UserSettings.emailConnector.forwarding.confirm.send') }}
        </v-btn>
      </div>
      <div v-if="sentTo === destination" class="d-flex align-center mt-2">
        <v-text-field
          v-model="code"
          :aria-label="$t('UserSettings.emailConnector.forwarding.confirm.code')"
          :placeholder="$t('UserSettings.emailConnector.forwarding.confirm.code')"
          class="border-box-sizing pt-0 me-2"
          maxlength="6"
          inputmode="numeric"
          autocomplete="one-time-code"
          hide-details
          outlined
          dense
          @keydown.enter.prevent="confirm" />
        <v-btn
          :loading="confirming"
          :disabled="disabled || !/^[0-9]{6}$/.test(code.trim())"
          class="btn btn-primary"
          small
          @click="confirm">
          {{ $t('UserSettings.emailConnector.forwarding.confirm.action') }}
        </v-btn>
      </div>
    </template>
    <div
      v-if="error"
      class="error--text mt-2"
      role="alert">
      {{ error }}
    </div>
  </div>
</template>

<script>
import { forwardingMessage } from '../../../js/EmailConnectorForwarding.js';

export default {
  props: {
    // The destination, normalised; null while what was typed is not a valid, allowed
    // address.
    destination: {
      type: String,
      default: null,
    },
    // The destinations the user already confirmed.
    confirmedDestinations: {
      type: Array,
      default: () => [],
    },
    // Whether the surrounding form is busy.
    disabled: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    code: '',
    sentTo: null,
    sending: false,
    confirming: false,
    error: null,
    // Confirmed while this component lives, beside the ones the server listed.
    justConfirmed: [],
  }),
  computed: {
    /**
     * Whether the destination needs no code.
     *
     * @returns {Boolean} true when confirmed
     */
    confirmed() {
      return !!this.destination
        && (this.confirmedDestinations.includes(this.destination) || this.justConfirmed.includes(this.destination));
    },
  },
  watch: {
    /**
     * Another destination: its own state, told to the form.
     *
     * @returns {void}
     */
    destination() {
      this.error = null;
      this.code = '';
      this.$emit('confirmed', this.confirmed);
    },
  },
  created() {
    this.$emit('confirmed', this.confirmed);
  },
  methods: {
    /**
     * Asks the platform to mail a code to the destination.
     *
     * @returns {void}
     */
    send() {
      const destination = this.destination;
      this.sending = true;
      this.error = null;
      this.$emailConnectorCommonService.sendForwardingCode(destination)
        .then(() => {
          this.sentTo = destination;
          this.code = '';
        })
        .catch(error => this.error = forwardingMessage(this.$t.bind(this), error))
        .finally(() => this.sending = false);
    },
    /**
     * Confirms the destination with the code typed.
     *
     * @returns {void}
     */
    confirm() {
      const destination = this.destination;
      if (!/^[0-9]{6}$/.test(this.code.trim())) {
        return;
      }
      this.confirming = true;
      this.error = null;
      this.$emailConnectorCommonService.confirmForwarding(destination, this.code.trim())
        .then(() => {
          this.justConfirmed.push(destination);
          this.sentTo = null;
          this.code = '';
          this.$emit('confirmed', true);
        })
        .catch(error => this.error = forwardingMessage(this.$t.bind(this), error))
        .finally(() => this.confirming = false);
    },
  },
};
</script>
