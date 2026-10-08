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
  <!-- EXO-90503 - somebody gave you access to their mailbox. The sentence was written
       server-side in the reader's own language, with the owner's name already in it,
       so it is shown as it is rather than rebuilt from a second lookup here.
       EXO-90830 - the invitation is answered from here, as a space invitation is: Accept
       and Refuse while it waits, then what became of it -- written onto the stored
       notification by the server whichever screen the answer was given on, or known
       here at once from the answer just given. A click elsewhere opens the "Mailbox
       sharing" drawer on "Shared with me", where the share is managed. -->
  <div
    role="button"
    tabindex="0"
    @click.stop.prevent="openSettings"
    @keydown.enter="openSettings"
    @keydown.space="openSettings">
    <user-notification-template
      :notification="notification"
      :url="link"
      :message="message"
      :loading="loading">
      <template #avatar>
        <div>
          <v-icon size="36" class="primary--text">fa-share-alt</v-icon>
        </div>
      </template>
      <template #actions>
        <div class="text-truncate">
          <v-icon size="14" class="me-1 icon-default-color">fa-envelope-open-text</v-icon>
          {{ title }}
        </div>
        <!-- The answers are this row's own: neither their click nor their keys reach
             the row, which would open the settings over the answer being given. -->
        <div
          class="mt-2"
          @click.stop
          @keydown.stop>
          <div class="d-flex flex-wrap align-center">
            <template v-if="pending">
              <v-btn
                :loading="accepting"
                :disabled="answering"
                class="ignore-vuetify-classes me-2 mb-1"
                color="success"
                elevation="0"
                small
                outlined
                @click.stop.prevent="answer('accept')">
                <v-icon size="14" class="me-2 pt-2px">fa-check</v-icon>
                <span class="text-none">{{ $t('emailDelegationInvitation.notification.accept') }}</span>
              </v-btn>
              <v-btn
                :loading="refusing"
                :disabled="answering"
                class="ignore-vuetify-classes me-2 mb-1"
                color="error"
                elevation="0"
                small
                outlined
                @click.stop.prevent="answer('decline')">
                <v-icon size="14" class="me-2 pt-2px">fa-times</v-icon>
                <span class="text-none">{{ $t('emailDelegationInvitation.notification.refuse') }}</span>
              </v-btn>
            </template>
            <div
              v-else-if="status"
              :class="statusClass"
              class="caption text-wrap me-2">
              {{ statusText }}
            </div>
            <!-- A button, not a link: the row itself is the platform's link to the
                 notification's url, and links do not nest. -->
            <v-tooltip bottom>
              <template #activator="{ on, attrs }">
                <v-btn
                  :aria-label="$t('emailDelegationInvitation.notification.openSettings')"
                  class="mb-1"
                  icon
                  small
                  v-bind="attrs"
                  v-on="on"
                  @click.stop.prevent="openSettings">
                  <v-icon size="16" class="icon-default-color">fa-cog</v-icon>
                </v-btn>
              </template>
              <span>{{ $t('emailDelegationInvitation.notification.openSettings') }}</span>
            </v-tooltip>
          </div>
          <div
            v-if="error"
            class="caption error--text text-wrap"
            role="alert">
            {{ error }}
          </div>
        </div>
      </template>
    </user-notification-template>
  </div>
</template>

<script>
import { answerDelegation, getReceivedDelegations } from '../../email-connector-user-setting/js/EmailConnectorUserSettingService.js';

/** The server's answer code that says the share is gone. */
const REVOKED_CODE = 'emailConnector.delegation.revoked';

/**
 * The server's answer codes that say the share is no longer in a state this answer
 * applies to -- answered elsewhere, or found gone by a check that left no mark on the
 * invitation -- so the row asks where it now stands before saying anything.
 */
const NOT_WAITING_CODES = ['emailConnector.delegation.notPending', 'emailConnector.delegation.notAcceptable'];

/** How the row says each state a share can be found in once it no longer waits. */
const STANDING = {
  ACCEPTED: 'ACCEPTED',
  DECLINED: 'DECLINED',
  REVOKED: 'REVOKED',
  GONE: 'REVOKED',
};

/** The outcome each answer leaves the invitation in, as the server writes it. */
const OUTCOMES = {
  accept: 'ACCEPTED',
  decline: 'DECLINED',
};

export default {
  props: {
    notification: {
      type: Object,
      default: null,
    },
    loading: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    accepting: false,
    refusing: false,
    // What this row learnt from the answer it just gave, before the server's own
    // mark on the stored notification reaches it.
    outcome: null,
    error: null,
  }),
  computed: {
    /**
     * @returns {Object} the notification's parameters
     */
    parameters() {
      return this.notification?.parameters || {};
    },
    /**
     * @returns {String} the heading the server wrote in the reader's language
     */
    title() {
      return this.parameters.TITLE || '';
    },
    /**
     * @returns {String} who shared and with which rights
     */
    message() {
      return this.parameters.CONTENT || '';
    },
    /**
     * @returns {String} the sharing settings' link the server gave
     */
    link() {
      return this.parameters.LINK;
    },
    /**
     * @returns {String} the share the invitation is about
     */
    delegationId() {
      return this.parameters.DELEGATION_ID;
    },
    /**
     * Where the share stands: what the answer given here said, else what the server
     * wrote onto the notification; null while it waits.
     *
     * @returns {String} ACCEPTED, DECLINED, LEFT, REVOKED or ANSWERED, or null
     */
    status() {
      return this.outcome || this.parameters.DELEGATION_STATUS || null;
    },
    /**
     * @returns {Boolean} whether the invitation still waits for an answer, and names
     *   the share it is about
     */
    pending() {
      return !this.status && !!this.delegationId;
    },
    /**
     * @returns {Boolean} whether an answer is on its way
     */
    answering() {
      return this.accepting || this.refusing;
    },
    /**
     * @returns {String} what became of the invitation, in the reader's language
     */
    statusText() {
      const key = `emailDelegationInvitation.notification.status.${this.status}`;
      return typeof this.$te === 'function' && this.$te(key)
        ? this.$t(key)
        : this.$t('emailDelegationInvitation.notification.status.ANSWERED');
    },
    /**
     * @returns {String} its colour: success once accepted, the default otherwise
     */
    statusClass() {
      return this.status === 'ACCEPTED' ? 'success--text' : 'text-sub-title';
    },
  },
  methods: {
    /**
     * Opens the "Mailbox sharing" drawer on "Shared with me", where the share is
     * listed with its answers and its preferences.
     *
     * @returns {void}
     */
    openSettings() {
      window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-email-shared-with-me')));
    },
    /**
     * Accepts or refuses the share as the signed-in user, through the very call the
     * sharing settings make, with its checks. Then the notifications are re-read, as a
     * space invitation's answer does, so that the stored mark the server wrote shows.
     * A share that turned out not to wait any more says why instead of offering the
     * buttons again; any other failure keeps them, with the settings to fall back on.
     *
     * @param {String} action accept or decline
     * @returns {Promise} resolved once the row shows the result
     */
    answer(action) {
      if (this.answering || !this.delegationId) {
        return Promise.resolve();
      }
      this.error = null;
      this.accepting = action === 'accept';
      this.refusing = action === 'decline';
      return answerDelegation(this.delegationId, action)
        .then(() => {
          this.outcome = OUTCOMES[action];
          document.dispatchEvent(new CustomEvent('refresh-notifications'));
        })
        .catch(error => {
          const code = error?.message;
          if (code === REVOKED_CODE) {
            this.outcome = 'REVOKED';
          } else if (NOT_WAITING_CODES.includes(code)) {
            return this.readStanding();
          } else if (code === 'emailConnector.delegation.tooMany') {
            this.error = this.$t('emailDelegationInvitation.notification.error.tooMany');
          } else {
            this.error = this.$t('emailDelegationInvitation.notification.error');
          }
        })
        .finally(() => {
          this.accepting = false;
          this.refusing = false;
        });
    },
    /**
     * Reads where the share now stands, from eXo's own rows (no connection to the mail
     * server), after the server said it no longer waits: a share revoked or found gone
     * says so, rather than "already answered". A share that cannot be read is said to be
     * answered, which is what the server's refusal established.
     *
     * @returns {Promise} resolved once the row shows where the share stands
     */
    readStanding() {
      return getReceivedDelegations(false)
        .then(rows => (rows || []).find(row => String(row.id) === String(this.delegationId)))
        .catch(() => null)
        .then(row => this.outcome = STANDING[row?.status] || 'ANSWERED');
    },
  },
};
</script>
