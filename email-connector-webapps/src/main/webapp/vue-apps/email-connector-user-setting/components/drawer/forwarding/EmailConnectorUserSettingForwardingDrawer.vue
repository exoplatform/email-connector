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
  <!-- The forward's drawer (EXO-90656), like the automatic reply's: opened by the root
       event OPEN_FORWARDING_DRAWER_EVENT from the Settings row and from the mailbox band,
       since both apps mount it at their root. It reads the server on every opening. Where
       the deployment lets the user set a forward: one address in the allowed domains,
       confirmed by the code eXo sends there, a copy always kept, every change told to the
       user; "Stop forwarding" for the forward eXo set, allowed even once the deployment
       switched forwarding off. A forward eXo did not set is shown with where to manage
       it, and eXo adds none next to it. -->
  <exo-drawer
    id="userSettingForwardingDrawer"
    ref="forwardingDrawer"
    v-model="drawer"
    right>
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.forwarding.title') }}</span>
    </template>
    <template #content>
      <div class="pa-4">
        <v-progress-linear
          v-if="loading && !absence"
          indeterminate
          color="primary"
          class="mb-4" />
        <div
          v-else-if="!absence"
          class="error--text"
          role="alert">
          {{ error || $t('UserSettings.emailConnector.forwarding.error') }}
        </div>
        <template v-else>
          <v-alert
            v-if="currentMessage"
            :type="forwardingOn ? 'info' : 'warning'"
            class="text-body-2 mb-4"
            dense
            text>
            <div>{{ currentMessage }}</div>
            <div v-if="exoForward" class="d-flex justify-end mt-2">
              <v-btn
                :loading="removing"
                class="btn"
                small
                @click="remove(removeAnyway)">
                {{ removeAnyway
                  ? $t('UserSettings.emailConnector.forwarding.stopAnyway')
                  : $t('UserSettings.emailConnector.forwarding.stop') }}
              </v-btn>
            </div>
            <div v-else-if="forwarding.manageUrl" class="d-flex justify-end mt-2">
              <v-btn
                :href="forwarding.manageUrl"
                class="btn"
                target="_blank"
                rel="noopener noreferrer"
                small>
                {{ $t('UserSettings.emailConnector.forwarding.manage') }}
              </v-btn>
            </div>
          </v-alert>
          <div v-if="!authoringEnabled" class="text-subtitle">
            {{ $t('UserSettings.emailConnector.forwarding.form.disabled') }}
          </div>
          <v-form
            v-else-if="!foreignForward"
            @submit.prevent="save">
            <div class="mb-2">
              {{ $t('UserSettings.emailConnector.forwarding.form.destination') }}
            </div>
            <v-text-field
              v-model="destinationInput"
              :rules="[destinationRule]"
              :aria-label="$t('UserSettings.emailConnector.forwarding.form.destination')"
              :placeholder="$t('UserSettings.emailConnector.forwarding.form.placeholder')"
              class="border-box-sizing width-auto pt-0"
              type="email"
              maxlength="254"
              outlined
              dense />
            <div class="text-subtitle">
              {{ $t('UserSettings.emailConnector.forwarding.form.allowed', { 0: allowedDomains.join(', ') }) }}
            </div>
            <email-connector-forwarding-confirm
              :destination="acceptedDestination"
              :confirmed-destinations="authoring.confirmedDestinations || []"
              :disabled="saving"
              @confirmed="confirmed = $event" />
            <div class="text-subtitle mt-4">
              {{ $t('UserSettings.emailConnector.forwarding.form.copyKept') }}
            </div>
            <div class="text-subtitle mt-2">
              {{ $t('UserSettings.emailConnector.forwarding.form.told') }}
            </div>
          </v-form>
          <div
            v-if="error"
            class="error--text mt-4"
            role="alert">
            {{ error }}
          </div>
        </template>
      </div>
    </template>
    <template #footer>
      <div class="d-flex align-center justify-end">
        <v-btn
          class="btn"
          @click="close">
          {{ $t('UserSettings.emailConnector.userSetting.drawer.cancel') }}
        </v-btn>
        <v-btn
          :disabled="!canSave"
          :loading="saving"
          class="btn btn-primary ms-5"
          @click="save">
          {{ $t('UserSettings.emailConnector.forwarding.form.save') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import {
  OPEN_FORWARDING_DRAWER_EVENT,
  forwardingMessage,
  isAllowedDestination,
  normalizeDestination,
  notifyForwardingUpdated,
} from '../../../js/EmailConnectorForwarding.js';
import { notifyAbsenceUpdated } from '../../../js/EmailConnectorAbsenceMixin.js';

export default {
  data: () => ({
    drawer: false,
    loading: false,
    saving: false,
    removing: false,
    absence: null,
    error: null,
    destinationInput: '',
    confirmed: false,
    // eXo's script changed outside eXo: "Stop forwarding" then rewrites it without the
    // forward, whatever it holds, as the reply's drawer re-publishes.
    removeAnyway: false,
  }),
  computed: {
    /**
     * The forward the server holds.
     *
     * @returns {Object} the forward, or an empty one
     */
    forwarding() {
      return this.absence?.forwarding || {};
    },
    /**
     * The bounds of forwarding from eXo.
     *
     * @returns {Object} {enabled, reasonCode, allowedDomains, confirmedDestinations}
     */
    authoring() {
      return this.absence?.forwardingAuthoring || {};
    },
    /**
     * Whether the user may set a forward from eXo here.
     *
     * @returns {Boolean} true when enabled
     */
    authoringEnabled() {
      return !!this.authoring.enabled;
    },
    /**
     * @returns {String[]} the allowed domains
     */
    allowedDomains() {
      return this.authoring.allowedDomains || [];
    },
    /**
     * Whether eXo set the forward the server holds.
     *
     * @returns {Boolean} true for eXo's own
     */
    exoForward() {
      return this.forwarding.state === 'SERVER_FORWARD' && !!this.forwarding.managedByExo;
    },
    /**
     * Whether another client holds, or may hold, a forward: eXo adds none next to it.
     *
     * @returns {Boolean} true for a forward eXo did not set
     */
    foreignForward() {
      return this.forwarding.state === 'MAY_FORWARD_BY_SCRIPT'
        || (this.forwarding.state === 'SERVER_FORWARD' && !this.forwarding.managedByExo)
        || (this.exoForward && this.forwarding.scriptName != null);
    },
    /**
     * Whether a forward is on.
     *
     * @returns {Boolean} true when mail leaves the mailbox
     */
    forwardingOn() {
      return this.forwarding.state === 'SERVER_FORWARD';
    },
    /**
     * What the server holds now, in one sentence.
     *
     * @returns {String} the sentence, or null when nothing is forwarded
     */
    currentMessage() {
      const forwarding = this.forwarding;
      if (forwarding.state === 'SERVER_FORWARD') {
        const destinations = (forwarding.destinations || []).join(', ');
        return forwarding.keepCopy === false
          ? this.$t('UserSettings.emailConnector.forwarding.server.noCopy', { 0: destinations })
          : this.$t('UserSettings.emailConnector.forwarding.server.copy', { 0: destinations });
      }
      if (forwarding.state === 'MAY_FORWARD_BY_SCRIPT') {
        return forwarding.scriptName
          ? this.$t('UserSettings.emailConnector.forwarding.script', { 0: forwarding.scriptName })
          : this.$t('UserSettings.emailConnector.forwarding.script.nameless');
      }
      return null;
    },
    /**
     * The destination typed, when it is a plain address in an allowed domain.
     *
     * @returns {String} the normalised address, or null
     */
    acceptedDestination() {
      const destination = normalizeDestination(this.destinationInput);
      return destination && isAllowedDestination(destination, this.allowedDomains) ? destination : null;
    },
    /**
     * Whether the forward can be saved: an accepted, confirmed destination other than the
     * current one.
     *
     * @returns {Boolean} true when it can
     */
    canSave() {
      return this.authoringEnabled && !this.foreignForward && !!this.acceptedDestination && this.confirmed && !this.loading
        && !(this.exoForward && (this.forwarding.destinations || [])[0] === this.acceptedDestination);
    },
  },
  created() {
    this.$root.$on(OPEN_FORWARDING_DRAWER_EVENT, this.open);
  },
  beforeDestroy() {
    this.$root.$off(OPEN_FORWARDING_DRAWER_EVENT, this.open);
  },
  methods: {
    /**
     * Opens the drawer on the forward as the server holds it right now.
     *
     * @returns {void}
     */
    open() {
      this.absence = null;
      this.error = null;
      this.destinationInput = '';
      this.confirmed = false;
      this.removeAnyway = false;
      this.drawer = true;
      this.read();
    },
    /**
     * Reads the forward and the bounds of forwarding from the server.
     *
     * @returns {Promise} resolved when read
     */
    read() {
      this.loading = true;
      return this.$emailConnectorCommonService.getAbsence(true)
        .then(absence => this.absence = absence)
        .catch(error => this.error = forwardingMessage(this.$t.bind(this), error))
        .finally(() => this.loading = false);
    },
    /**
     * Closes the drawer; what was typed and not saved is dropped.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
    },
    /**
     * A destination's rule: a plain address in an allowed domain.
     *
     * @param {String} value - the address as typed
     * @returns {Boolean|String} true, or why not
     */
    destinationRule(value) {
      if (!value) {
        return true;
      }
      const destination = normalizeDestination(value);
      if (!destination) {
        return this.$t('UserSettings.emailConnector.forwarding.destination.invalid');
      }
      return isAllowedDestination(destination, this.allowedDomains)
        || this.$t('UserSettings.emailConnector.forwarding.destination.notAllowed');
    },
    /**
     * Sets the forward, tells every view of it, and closes.
     *
     * @returns {void}
     */
    save() {
      if (!this.canSave) {
        return;
      }
      this.saving = true;
      this.error = null;
      this.$emailConnectorCommonService.setForwarding(this.acceptedDestination)
        .then(() => {
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.forwarding.saved'), 'success');
          notifyForwardingUpdated();
          notifyAbsenceUpdated();
          this.close();
        })
        .catch(error => {
          this.error = forwardingMessage(this.$t.bind(this), error);
          if (error?.status === 409) {
            this.read();
          }
        })
        .finally(() => this.saving = false);
    },
    /**
     * Removes the forward eXo set; once eXo's script was found changed outside eXo, the
     * user may remove it anyway.
     *
     * @param {Boolean} anyway - rewrite eXo's script although it changed outside eXo
     * @returns {void}
     */
    remove(anyway) {
      this.removing = true;
      this.error = null;
      this.$emailConnectorCommonService.removeForwarding(!!anyway)
        .then(() => {
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.forwarding.removed'), 'success');
          this.removeAnyway = false;
          notifyForwardingUpdated();
          notifyAbsenceUpdated();
          this.read();
        })
        .catch(error => {
          this.removeAnyway = error?.status === 409 && error?.message === 'emailConnector.absence.modifiedOutside';
          this.error = this.removeAnyway
            ? this.$t('UserSettings.emailConnector.forwarding.modifiedOutside')
            : forwardingMessage(this.$t.bind(this), error);
        })
        .finally(() => this.removing = false);
    },
  },
};
</script>
