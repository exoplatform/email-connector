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
  <!-- A message that asks to be notified when it is read (RFC 8098, EXO-90435): the
       platform's info alert, as the composer's "no longer scheduled" line is, naming the
       sender and offering the two final answers. Shown for ASK only; an AUTO request is
       answered on its own once the message counts as displayed (autoAllowed), and
       nothing is shown for it. -->
  <v-alert
    v-if="prompt === 'ASK'"
    class="read-receipt-banner mb-3"
    type="info"
    role="status"
    dense
    text>
    <div class="d-flex align-center flex-wrap">
      <span class="me-auto text-wrap">{{ label }}</span>
      <v-btn
        :disabled="busy"
        class="read-receipt-send ms-2"
        color="primary"
        text
        small
        @click="answer('SEND')">
        {{ $t('emailConnector.mailBox.readReceipt.send') }}
      </v-btn>
      <v-btn
        :disabled="busy"
        class="read-receipt-ignore"
        text
        small
        @click="answer('IGNORE')">
        {{ $t('emailConnector.mailBox.readReceipt.ignore') }}
      </v-btn>
    </div>
  </v-alert>
</template>

<script>
export default {
  props: {
    // The message on screen, as the reader holds it: its technical id, its sender and
    // the prompt the server computed for this user (readReceiptPrompt).
    email: {
      type: Object,
      default: null,
    },
    // Whether the message counts as displayed to the user, so an AUTO request may be
    // answered on its own: the rule the reader marks a message read by (EXO-90414) --
    // at once when the user opened it, only after the dwell when the reader opened it
    // on its own, never for a message the user walked past or left collapsed.
    autoAllowed: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    busy: false,
  }),
  computed: {
    /**
     * What the reader does about the message's request, as the server said and as the
     * answers given here changed it: ASK, AUTO or NONE.
     *
     * @returns {String} the prompt
     */
    prompt() {
      return this.email?.readReceiptPrompt || 'NONE';
    },
    /**
     * "{sender} asked for a read receipt", with the sender's name, or their address when
     * the message carries no name.
     *
     * @returns {String} the banner's sentence
     */
    label() {
      const sender = this.email?.sender?.name || this.email?.sender?.address || '';
      return this.$t('emailConnector.mailBox.readReceipt.banner', { 0: sender });
    },
    /**
     * Whether the automatic receipt should leave now: an AUTO request on a message that
     * counts as displayed.
     *
     * @returns {Boolean} true when it should
     */
    autoAnswerDue() {
      return this.prompt === 'AUTO' && this.autoAllowed && !!this.email?.id;
    },
  },
  watch: {
    autoAnswerDue: {
      immediate: true,
      handler(due) {
        if (due) {
          this.answerAutomatically();
        }
      },
    },
  },
  methods: {
    /**
     * Answers the request on the user's click: SEND or IGNORE, both final. The banner
     * goes once answered; a refusal is handled as readReceiptOutcome says.
     *
     * @param {String} action SEND or IGNORE
     * @returns {Promise<void>} resolved once answered or refused
     */
    answer(action) {
      if (this.busy || !this.email?.id) {
        return Promise.resolve();
      }
      this.busy = true;
      return this.$emailConnectorMailBoxService.respondToReadReceipt(this.email.id, action, false)
        .then(() => this.setPrompt('NONE'))
        .catch(error => this.applyRefusal(error))
        .finally(() => this.busy = false);
    },
    /**
     * Sends the receipt of an AUTO request on its own, once per message and page (the
     * service keeps count). A refusal is handled as readReceiptOutcome says: askFirst
     * brings the banner up, 409 drops everything silently.
     *
     * @returns {Promise<void>} resolved once answered, refused, or already posted
     */
    answerAutomatically() {
      const posted = this.$emailConnectorMailBoxService.answerReadReceiptAutomatically(this.email);
      if (!posted) {
        return Promise.resolve();
      }
      this.busy = true;
      return posted
        .then(() => this.setPrompt('NONE'))
        .catch(error => this.applyRefusal(error))
        .finally(() => this.busy = false);
    },
    /**
     * Applies a refused answer: the prompt it leaves, and the sentence it says, if any.
     *
     * @param {Error} error the refusal, {code, status}
     * @returns {void}
     */
    applyRefusal(error) {
      const outcome = this.$emailConnectorMailBoxService.readReceiptOutcome(error, this.prompt);
      this.setPrompt(outcome.prompt);
      if (outcome.messageKey) {
        this.$root.$emit('alert-message', this.$t(outcome.messageKey), outcome.alertType);
      }
    },
    /**
     * Records the prompt on the message itself, so every rendering of it -- collapsed
     * and expanded again, the other reader -- agrees that the request is answered.
     *
     * @param {String} prompt ASK, AUTO or NONE
     * @returns {void}
     */
    setPrompt(prompt) {
      if (this.email) {
        this.$set(this.email, 'readReceiptPrompt', prompt);
      }
    },
  },
};
</script>
