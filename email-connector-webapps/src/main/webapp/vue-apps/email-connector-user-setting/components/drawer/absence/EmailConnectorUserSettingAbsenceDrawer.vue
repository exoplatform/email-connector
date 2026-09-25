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
  <!-- The automatic reply's drawer (EXO-90642), like the other settings drawers: opened
       by the root event OPEN_ABSENCE_DRAWER_EVENT from the Settings row and from the
       mailbox band, since both apps mount it at their root. It reads the server on every
       opening -- eXo keeps no copy of the text -- and holds the whole form, the state the
       server is in with its one action, and Save / Cancel in the footer. The content is
       not bound to the drawer's visibility, so it stays on screen while the drawer slides
       out after a save; open() resets it for the next opening. -->
  <exo-drawer
    id="userSettingAbsenceDrawer"
    ref="absenceDrawer"
    v-model="drawer"
    right>
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.absence.title') }}</span>
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
          {{ error || $t('UserSettings.emailConnector.absence.error') }}
        </div>
        <div v-else-if="!supported" class="text-subtitle">
          {{ unsupportedMessage }}
        </div>
        <template v-else>
          <v-alert
            v-if="stateMessage"
            :type="stateType"
            class="text-body-2 mb-4"
            dense
            text>
            <div>{{ stateMessage }}</div>
            <div v-if="stateAction" class="d-flex justify-end mt-2">
              <v-btn
                :loading="saving"
                class="btn"
                small
                @click="stateAction.run">
                {{ stateAction.label }}
              </v-btn>
            </div>
          </v-alert>
          <email-connector-user-setting-absence-form
            v-if="editable"
            ref="absenceForm"
            :key="formKey"
            :vacation="absence.vacation"
            :capabilities="absence.capabilities"
            :days="absence.vacationDays || 7"
            :engine="absence.engine"
            :error="error"
            @can-save="canSave = $event"
            @save="save($event, false)" />
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
          :disabled="!editable || !canSave || loading"
          :loading="saving"
          class="btn btn-primary ms-5"
          @click="submit">
          {{ $t('UserSettings.emailConnector.absence.form.save') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import absenceMixin, { OPEN_ABSENCE_DRAWER_EVENT, notifyAbsenceUpdated } from '../../../js/EmailConnectorAbsenceMixin.js';

export default {
  mixins: [absenceMixin],
  data: () => ({
    // The drawer does not show the forward: the Settings row does.
    absenceWithForwarding: false,
    drawer: false,
    saving: false,
    canSave: false,
    // Bumped on every opening, so the form fills again from what the server answered.
    formKey: 1,
    // The last form value, re-sent by "Re-publish" and "Re-activate".
    lastValue: null,
  }),
  computed: {
    /**
     * The alert's tint.
     *
     * @returns {String} info or warning
     */
    stateType() {
      return this.absence?.vacationState === 'ELSEWHERE' ? 'info' : 'warning';
    },
    /**
     * The state's one action: "Re-publish" overwrites eXo's own script changed outside
     * eXo, "Re-activate" publishes it again; never done silently.
     *
     * @returns {Object} {label, run}, or null
     */
    stateAction() {
      // What the user last typed wins over the server's copy: Re-publish replaces the
      // script changed outside eXo with it.
      const vacation = this.lastValue || this.absence?.vacation;
      if (!vacation) {
        return null;
      }
      if (this.absence.vacationState === 'MODIFIED' && !this.absence.foreignScriptName) {
        return { label: this.$t('UserSettings.emailConnector.absence.republish'), run: () => this.save(vacation, true) };
      }
      if (this.absence.vacationState === 'INACTIVE') {
        return { label: this.$t('UserSettings.emailConnector.absence.reactivate'), run: () => this.save(vacation, false) };
      }
      return null;
    },
  },
  created() {
    this.loading = false;
    this.$root.$on(OPEN_ABSENCE_DRAWER_EVENT, this.open);
  },
  beforeDestroy() {
    this.$root.$off(OPEN_ABSENCE_DRAWER_EVENT, this.open);
  },
  methods: {
    /**
     * Opens the drawer on the reply as the server holds it right now; what the previous
     * opening left (a refusal, a typed value kept for Re-publish) is forgotten here rather
     * than on closing, so the drawer never empties while it slides out.
     *
     * @returns {void}
     */
    open() {
      this.absence = null;
      this.error = null;
      this.lastValue = null;
      this.saving = false;
      this.canSave = false;
      this.formKey++;
      this.drawer = true;
      this.readAbsence();
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
     * The footer's Save: the form hands its value back through its save event.
     *
     * @returns {void}
     */
    submit() {
      this.$refs.absenceForm?.submit();
    },
    /**
     * Writes the reply, tells every view of it, and closes; a refusal is said in the
     * user's words and the reply is read again when it may have changed.
     *
     * @param {Object} vacation the form's value
     * @param {Boolean} republish whether to overwrite eXo's script changed outside eXo
     * @returns {void}
     */
    save(vacation, republish) {
      this.lastValue = vacation;
      this.saving = true;
      this.error = null;
      this.$emailConnectorCommonService.saveVacation(vacation, republish)
        .then(() => {
          this.lastValue = null;
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.absence.saved'), 'success');
          notifyAbsenceUpdated();
          this.close();
        })
        .catch(error => {
          this.error = this.absenceMessage(error);
          if (error?.status === 409) {
            // The state changed on the server; what the user typed is kept for Re-publish.
            const typed = this.lastValue;
            this.readAbsence().then(() => {
              this.lastValue = typed;
              this.error = this.error || this.absenceMessage(error);
            });
          }
        })
        .finally(() => this.saving = false);
    },
  },
};
</script>
