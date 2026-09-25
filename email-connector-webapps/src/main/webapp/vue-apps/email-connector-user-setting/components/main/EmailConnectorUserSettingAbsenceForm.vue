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
  <!-- The automatic reply's form: on/off, an optional first and last day in the user's
       own time zone, a one-line subject and a plain-text message. What the mail server
       decides on its own (once per sender per N days, never to lists or automated mail)
       is stated, not editable. -->
  <v-form
    v-model="valid"
    class="d-flex flex-column"
    @submit.prevent="submit">
    <v-switch
      v-model="enabled"
      :label="$t('UserSettings.emailConnector.absence.form.enabled')"
      class="mt-0"
      hide-details />
    <div class="d-flex flex-wrap align-center mt-3">
      <v-checkbox
        v-model="hasStart"
        :label="$t('UserSettings.emailConnector.absence.form.start')"
        :disabled="!windowSupported"
        class="mt-0 me-3"
        hide-details />
      <date-picker
        v-if="hasStart"
        v-model="start"
        :attach="false"
        :aria-label="$t('UserSettings.emailConnector.absence.form.start')"
        class="flex-grow-0 me-6"
        return-iso />
      <v-checkbox
        v-model="hasEnd"
        :label="$t('UserSettings.emailConnector.absence.form.end')"
        :disabled="!windowSupported"
        class="mt-0 me-3"
        hide-details />
      <date-picker
        v-if="hasEnd"
        v-model="end"
        :attach="false"
        :min-value="hasStart ? start : null"
        :aria-label="$t('UserSettings.emailConnector.absence.form.end')"
        class="flex-grow-0"
        return-iso />
    </div>
    <div v-if="hasStart || hasEnd" class="caption text-sub-title mt-1">
      {{ $t('UserSettings.emailConnector.absence.form.zone', { 0: timeZone }) }}
    </div>
    <v-text-field
      v-model="subject"
      :label="$t('UserSettings.emailConnector.absence.form.subject')"
      :rules="[required, oneLine]"
      :counter="MAX_SUBJECT"
      :maxlength="MAX_SUBJECT"
      class="mt-4"
      outlined
      dense />
    <v-textarea
      v-model="text"
      :label="$t('UserSettings.emailConnector.absence.form.text')"
      :rules="[required, withinLimit]"
      :counter="MAX_TEXT"
      :counter-value="storedLength"
      rows="5"
      outlined
      auto-grow />
    <div class="caption text-sub-title">
      {{ $t('UserSettings.emailConnector.absence.form.rules', { 0: days }) }}
    </div>
    <div class="caption text-sub-title">
      {{ $t('UserSettings.emailConnector.absence.form.ownMailbox') }}
    </div>
    <div
      v-if="error"
      class="caption error--text mt-2"
      role="alert">
      {{ error }}
    </div>
    <div class="d-flex justify-end mt-3">
      <v-btn
        :disabled="!valid || !windowValid"
        :loading="saving"
        class="btn btn-primary"
        type="submit">
        {{ $t('UserSettings.emailConnector.absence.form.save') }}
      </v-btn>
    </div>
  </v-form>
</template>

<script>
/**
 * A day as the date-picker speaks it: yyyy-MM-dd in the user's own day.
 *
 * @param {Date} date the date
 * @returns {String} the ISO day
 */
function isoDay(date) {
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export default {
  props: {
    // The reply the server holds, or null.
    vacation: { type: Object, default: null },
    // The engine's answer per element.
    capabilities: { type: Object, default: null },
    // Days between two replies to one sender.
    days: { type: Number, default: 7 },
    saving: { type: Boolean, default: false },
    error: { type: String, default: null },
  },
  data: () => ({
    MAX_SUBJECT: 200,
    MAX_TEXT: 4000,
    valid: false,
    enabled: true,
    hasStart: false,
    hasEnd: false,
    start: null,
    end: null,
    subject: '',
    text: '',
    timeZone: new Intl.DateTimeFormat().resolvedOptions().timeZone,
  }),
  computed: {
    /**
     * Whether the server can bound the reply by days.
     *
     * @returns {Boolean} true when supported
     */
    windowSupported() {
      return !!this.capabilities?.elements?.vacationDateWindow?.supported;
    },
    /**
     * Whether the chosen days make a window: the last one not before the first.
     *
     * @returns {Boolean} true when valid
     */
    windowValid() {
      return !(this.hasStart && this.hasEnd && this.start && this.end && this.end < this.start);
    },
  },
  created() {
    this.fill();
  },
  methods: {
    /**
     * Shows the reply the server holds, or a new one starting today for a week.
     *
     * @returns {void}
     */
    fill() {
      const vacation = this.vacation;
      const today = new Date();
      const inAWeek = new Date(today.getFullYear(), today.getMonth(), today.getDate() + 7);
      this.enabled = vacation ? !!vacation.enabled : true;
      this.hasStart = !!vacation?.start;
      this.hasEnd = !!vacation?.end;
      this.start = vacation?.start || isoDay(today);
      this.end = vacation?.end || isoDay(inAWeek);
      this.subject = vacation?.subject || this.$t('UserSettings.emailConnector.absence.form.subject.default');
      this.text = vacation?.text || '';
    },
    /**
     * A required value.
     *
     * @param {String} value the value
     * @returns {Boolean|String} true, or the message
     */
    required(value) {
      return !!(value && value.trim()) || this.$t('UserSettings.emailConnector.absence.form.required');
    },
    /**
     * The text's length as the mail server stores it: each line break is two characters
     * (CRLF), which is what the limit counts.
     *
     * @param {String} value the text
     * @returns {Number} the stored length
     */
    storedLength(value) {
      const text = value || '';
      return text.length + (text.match(/\n/g) || []).length;
    },
    /**
     * A text within the limit, line breaks counted as the server stores them.
     *
     * @param {String} value the text
     * @returns {Boolean|String} true, or the message
     */
    withinLimit(value) {
      return this.storedLength(value) <= this.MAX_TEXT || this.$t('UserSettings.emailConnector.absence.text.invalid');
    },
    /**
     * A one-line value.
     *
     * @param {String} value the value
     * @returns {Boolean|String} true, or the message
     */
    oneLine(value) {
      return !/[\r\n]/.test(value || '') || this.$t('UserSettings.emailConnector.absence.form.oneLine');
    },
    /**
     * Hands the value to the section, which writes it.
     *
     * @returns {void}
     */
    submit() {
      this.$emit('save', {
        enabled: this.enabled,
        start: this.hasStart ? this.start : null,
        end: this.hasEnd ? this.end : null,
        subject: this.subject,
        text: this.text,
      });
    },
  },
};
</script>
