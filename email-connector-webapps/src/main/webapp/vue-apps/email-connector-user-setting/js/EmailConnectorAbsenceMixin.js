/*
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

/**
 * The document event every view of the automatic reply listens to (EXO-90642): the
 * Settings row, the drawer and the mailbox band live in two apps, so a change made in
 * one is told to the others through the document, the way the apps of this add-on
 * already speak to each other. Whoever changes the reply dispatches it; each view reads
 * the server again.
 */
export const ABSENCE_UPDATED_EVENT = 'email-absence-updated';

/**
 * The root event that opens the automatic reply's drawer, in whichever app mounts it
 * (the Settings page, the mailbox).
 */
export const OPEN_ABSENCE_DRAWER_EVENT = 'open-email-absence-drawer';

/**
 * Tells every view of the automatic reply that it changed on the mail server.
 *
 * @returns {void}
 */
export function notifyAbsenceUpdated() {
  document.dispatchEvent(new CustomEvent(ABSENCE_UPDATED_EVENT));
}

/**
 * What the Settings row and the drawer share about the automatic reply: the server's
 * answer, whether it can be edited, what state it is in, and a refusal in the user's
 * words. eXo keeps no copy of the text, so everything here is read from the server.
 */
export default {
  data: () => ({
    absence: null,
    loading: true,
    // The last refusal's message, in the user's words; null when none.
    error: null,
  }),
  computed: {
    /**
     * Why the reply cannot be managed from eXo, in words: "not available yet" when the
     * mail server's engine is there but eXo has no client for it yet (BlueMind until its
     * client library is published), else "set it in your webmail".
     *
     * @returns {String} the localized line
     */
    unsupportedMessage() {
      return this.absence?.capabilities?.reasonCode === 'emailConnector.rules.bluemind.transportMissing'
        ? this.$t('UserSettings.emailConnector.absence.row.notYetAvailable')
        : this.$t('UserSettings.emailConnector.absence.row.unsupported');
    },
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
     * Whether the form may be used: supported, no other client's reply in the way, and
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
  },
  methods: {
    /**
     * Reads the automatic reply from the server.
     *
     * @returns {Promise} resolved when read
     */
    readAbsence() {
      this.loading = true;
      return this.$emailConnectorCommonService.getAbsence()
        .then(absence => {
          this.absence = absence;
          this.error = null;
        })
        .catch(error => this.error = this.absenceMessage(error))
        .finally(() => this.loading = false);
    },
    /**
     * A refusal in the user's words: the server's code when it is a known one.
     *
     * @param {Error} error the refusal
     * @returns {String} the message
     */
    absenceMessage(error) {
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
    formatAbsenceDay(day) {
      const [year, month, date] = day.split('-').map(Number);
      return new Date(year, month - 1, date).toLocaleDateString(eXo.env.portal.language, { day: 'numeric', month: 'short', year: 'numeric' });
    },
  },
};
