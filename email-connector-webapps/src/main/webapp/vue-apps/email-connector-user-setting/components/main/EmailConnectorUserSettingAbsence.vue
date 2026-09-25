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
  <!-- The automatic reply (EXO-90642): a setting of the user's own mailbox that the mail
       server runs at delivery. The row summarises what the server holds, read live; the
       form opens inline under it. eXo keeps no copy of the text: what the form shows is
       what the server answered. -->
  <div>
    <v-list-item class="height-auto">
      <v-list-item-content>
        <v-list-item-title class="text-color">
          {{ $t('UserSettings.emailConnector.absence.title') }}
        </v-list-item-title>
        <v-list-item-subtitle class="text-wrap">
          {{ summary }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action v-if="editable">
        <v-btn
          icon
          :title="$t('UserSettings.emailConnector.absence.edit.tooltip')"
          @click="expanded = !expanded">
          <v-icon size="20" class="icon-default-color">{{ expanded ? 'fa-chevron-up' : 'fa-edit' }}</v-icon>
        </v-btn>
      </v-list-item-action>
    </v-list-item>
    <v-alert
      v-if="stateMessage"
      :type="stateType"
      class="mx-4 mb-2 text-body-2"
      dense
      text>
      <div class="d-flex align-center flex-wrap">
        <span class="flex-grow-1 me-2">{{ stateMessage }}</span>
        <v-btn
          v-if="stateAction"
          :loading="saving"
          class="btn"
          small
          @click="stateAction.run">
          {{ stateAction.label }}
        </v-btn>
      </div>
    </v-alert>
    <v-expand-transition>
      <email-connector-user-setting-absence-form
        v-if="expanded && editable"
        :vacation="absence && absence.vacation"
        :capabilities="absence && absence.capabilities"
        :days="(absence && absence.vacationDays) || 7"
        :saving="saving"
        :error="error"
        class="mx-4 mb-4"
        @save="save($event, false)" />
    </v-expand-transition>
  </div>
</template>

<script>
export default {
  data: () => ({
    absence: null,
    loading: true,
    saving: false,
    expanded: false,
    // The last refusal's message, in the user's words; null when none.
    error: null,
    // The last form value, re-sent by "Re-publish" and "Re-activate".
    lastValue: null,
  }),
  computed: {
    /**
     * Whether the engine of the user's connector can hold a reply.
     *
     * @returns {Boolean} true when the probe answered the reply supported
     */
    supported() {
      const capabilities = this.absence?.capabilities;
      return !!capabilities?.supported && !!capabilities?.elements?.vacation?.supported;
    },
    /**
     * Whether the form may be opened: supported, no other client's reply in the way, and
     * eXo's wrapper not changed outside eXo (a save could only be refused until it is
     * repaired).
     *
     * @returns {Boolean} true when editable
     */
    editable() {
      return this.supported && this.absence?.vacationState !== 'ELSEWHERE'
        && !(this.absence?.vacationState === 'MODIFIED' && this.absence?.foreignScriptName);
    },
    /**
     * The row's one line: loading, unsupported, off, or on with its days.
     *
     * @returns {String} the localized line
     */
    summary() {
      if (this.loading) {
        return this.$t('UserSettings.emailConnector.absence.loading');
      }
      if (!this.absence) {
        return this.error || this.$t('UserSettings.emailConnector.absence.description');
      }
      if (!this.supported) {
        return this.$t('UserSettings.emailConnector.absence.row.unsupported');
      }
      const vacation = this.absence.vacation;
      if (!vacation?.enabled || this.absence.vacationState === 'ELSEWHERE') {
        return this.$t('UserSettings.emailConnector.absence.off');
      }
      if (vacation.end) {
        return this.$t('UserSettings.emailConnector.absence.onUntil', { 0: this.formatDay(vacation.end) });
      }
      return this.$t('UserSettings.emailConnector.absence.on');
    },
    /**
     * What the server holds that is not simply eXo's own reply, in words.
     *
     * @returns {String} the message, or null
     */
    stateMessage() {
      switch (this.absence?.vacationState) {
      case 'ELSEWHERE':
        return this.absence.foreignScriptName
          ? this.$t('UserSettings.emailConnector.absence.state.elsewhere', { 0: this.absence.foreignScriptName })
          : this.$t('UserSettings.emailConnector.absence.state.elsewhere.nameless');
      case 'MODIFIED':
        return this.absence.foreignScriptName
          ? this.$t('UserSettings.emailConnector.absence.state.wrapperModified', { 0: this.absence.foreignScriptName })
          : this.$t('UserSettings.emailConnector.absence.state.modified');
      case 'INACTIVE':
        return this.$t('UserSettings.emailConnector.absence.state.inactive');
      default:
        return null;
      }
    },
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
    this.read();
  },
  methods: {
    /**
     * Reads the section from the server.
     *
     * @returns {Promise} resolved when read
     */
    read() {
      this.loading = true;
      return this.$emailConnectorCommonService.getAbsence()
        .then(absence => {
          this.absence = absence;
          this.error = null;
        })
        .catch(error => this.error = this.message(error))
        .finally(() => this.loading = false);
    },
    /**
     * Writes the reply, then shows what the server holds; a refusal is said in the
     * user's words and the section is read again when it may have changed.
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
        .then(written => {
          this.absence = { ...this.absence, ...written, capabilities: this.absence?.capabilities };
          this.lastValue = null;
          this.expanded = false;
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.absence.saved'), 'success');
          document.dispatchEvent(new CustomEvent('email-absence-updated'));
        })
        .catch(error => {
          this.error = this.message(error);
          if (error?.status === 409) {
            this.read();
          }
        })
        .finally(() => this.saving = false);
    },
    /**
     * A refusal in the user's words: the server's code when it is a known one.
     *
     * @param {Error} error the refusal
     * @returns {String} the message
     */
    message(error) {
      const code = error?.message || '';
      if (code === 'emailConnector.absence.modifiedOutside' && error.scriptName === 'exo-main') {
        return this.$t('UserSettings.emailConnector.absence.state.wrapperModified', { 0: error.scriptName });
      }
      if (code.startsWith('emailConnector.absence.')) {
        const key = `UserSettings.${code}`;
        const text = this.$t(key, { 0: error.scriptName || '' });
        if (text !== key) {
          return text;
        }
      }
      return this.$t('UserSettings.emailConnector.absence.error');
    },
    /**
     * A day as the user reads it.
     *
     * @param {String} day yyyy-MM-dd
     * @returns {String} the localized day
     */
    formatDay(day) {
      const [year, month, date] = day.split('-').map(Number);
      return new Date(year, month - 1, date).toLocaleDateString(eXo.env.portal.language, { day: 'numeric', month: 'short', year: 'numeric' });
    },
  },
};
</script>
