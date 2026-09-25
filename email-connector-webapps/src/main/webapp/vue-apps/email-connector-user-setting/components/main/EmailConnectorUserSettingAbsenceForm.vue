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
       is stated, not editable. Its Save and Cancel are the drawer's footer, which calls
       submit() and reads the can-save event. Laid out like the platform's drawer forms:
       a plain label above each field, the switch at the end of its label's row, the two
       optional days as two equal date pickers side by side, empty meaning no bound. -->
  <v-form
    v-model="valid"
    @submit.prevent="submit">
    <div class="d-flex align-center justify-space-between full-width mb-2">
      <div id="emailAbsenceEnabledLabel">
        {{ $t('UserSettings.emailConnector.absence.form.enabled') }}
      </div>
      <v-switch
        v-model="enabled"
        aria-labelledby="emailAbsenceEnabledLabel"
        :ripple="false"
        class="ma-0 width-fit-content"
        hide-details />
    </div>
    <div class="d-flex mt-4">
      <div class="col-6 pa-0 pe-2">
        <div class="mb-2">
          {{ $t('UserSettings.emailConnector.absence.form.start') }}
        </div>
        <date-picker
          ref="startPicker"
          v-model="start"
          :default-value="false"
          :disabled="!windowSupported"
          :max-value="end"
          :left="$vuetify.rtl"
          :placeholder="$t('UserSettings.emailConnector.absence.form.start.none')"
          :aria-label="$t('UserSettings.emailConnector.absence.form.start')"
          :attach="false"
          return-iso>
          <template #footer>
            <v-btn
              class="ms-auto"
              color="primary"
              small
              text
              @click="clearDay('start')">
              {{ $t('UserSettings.emailConnector.absence.form.day.clear') }}
            </v-btn>
          </template>
        </date-picker>
      </div>
      <div class="col-6 pa-0 ps-2">
        <div class="mb-2">
          {{ $t('UserSettings.emailConnector.absence.form.end') }}
        </div>
        <date-picker
          ref="endPicker"
          v-model="end"
          :default-value="false"
          :disabled="!windowSupported"
          :min-value="start"
          :placeholder="$t('UserSettings.emailConnector.absence.form.end.none')"
          :aria-label="$t('UserSettings.emailConnector.absence.form.end')"
          :attach="false"
          :left="!$vuetify.rtl"
          return-iso>
          <template #footer>
            <v-btn
              class="ms-auto"
              color="primary"
              small
              text
              @click="clearDay('end')">
              {{ $t('UserSettings.emailConnector.absence.form.day.clear') }}
            </v-btn>
          </template>
        </date-picker>
      </div>
    </div>
    <div class="text-subtitle">
      {{ $t('UserSettings.emailConnector.absence.form.zone', { 0: timeZone }) }}
    </div>
    <div class="mt-4 mb-2">
      {{ $t('UserSettings.emailConnector.absence.form.subject') }}
    </div>
    <v-text-field
      v-model="subject"
      :rules="[required, oneLine]"
      :counter="MAX_SUBJECT"
      :maxlength="MAX_SUBJECT"
      :aria-label="$t('UserSettings.emailConnector.absence.form.subject')"
      class="border-box-sizing width-auto pt-0"
      type="text"
      outlined
      dense />
    <div class="mt-4 mb-2">
      {{ $t('UserSettings.emailConnector.absence.form.text') }}
    </div>
    <!-- The platform's extended-textarea look (its class, a plain v-textarea with a
         counter under it), not the component itself: its counter and its rule count
         characters, while the mail server's limit counts each line break twice. -->
    <v-textarea
      v-model="text"
      :rules="[required, withinLimit]"
      :counter="MAX_TEXT"
      :counter-value="storedLength"
      :placeholder="$t('UserSettings.emailConnector.absence.form.text.placeholder')"
      :aria-label="$t('UserSettings.emailConnector.absence.form.text')"
      :rows="5"
      :row-height="24"
      class="extended-textarea pt-0"
      auto-grow />
    <div class="text-subtitle mt-4">
      {{ $t('UserSettings.emailConnector.absence.form.rules', { 0: days }) }}
    </div>
    <div class="text-subtitle mt-2">
      {{ $t('UserSettings.emailConnector.absence.form.ownMailbox') }}
    </div>
    <div
      v-if="error"
      class="error--text mt-4"
      role="alert">
      {{ error }}
    </div>
  </v-form>
</template>

<script>
export default {
  props: {
    // The reply the server holds, or null.
    vacation: { type: Object, default: null },
    // The engine's answer per element.
    capabilities: { type: Object, default: null },
    // Days between two replies to one sender.
    days: { type: Number, default: 7 },
    error: { type: String, default: null },
  },
  data: () => ({
    MAX_SUBJECT: 200,
    MAX_TEXT: 4000,
    valid: false,
    enabled: true,
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
      return !(this.start && this.end && this.end < this.start);
    },
    /**
     * Whether the value may be saved: every field valid and the window a window.
     *
     * @returns {Boolean} true when it may be saved
     */
    canSave() {
      return this.valid && this.windowValid;
    },
  },
  watch: {
    /**
     * Tells the drawer whether its Save button may be used.
     *
     * @param {Boolean} value whether the value may be saved
     * @returns {void}
     */
    canSave: {
      immediate: true,
      handler(value) {
        this.$emit('can-save', value);
      },
    },
  },
  created() {
    this.fill();
  },
  methods: {
    /**
     * Shows the reply the server holds, or a new one running from now until switched
     * off: an empty day is no bound.
     *
     * @returns {void}
     */
    fill() {
      const vacation = this.vacation;
      this.enabled = vacation ? !!vacation.enabled : true;
      this.start = vacation?.start || null;
      this.end = vacation?.end || null;
      this.subject = vacation?.subject || this.$t('UserSettings.emailConnector.absence.form.subject.default');
      this.text = vacation?.text || '';
    },
    /**
     * Empties one of the two days and closes its calendar, as the task drawer's "None"
     * does: an empty first day starts the reply now, an empty last day keeps it until it
     * is switched off.
     *
     * @param {String} day start or end
     * @returns {void}
     */
    clearDay(day) {
      this[day] = null;
      const picker = this.$refs[`${day}Picker`];
      if (picker) {
        picker.menu = false;
      }
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
     * Hands the value to the drawer, which writes it; nothing when it may not be saved.
     *
     * @returns {void}
     */
    submit() {
      if (!this.canSave) {
        return;
      }
      this.$emit('save', {
        enabled: this.enabled,
        start: this.start || null,
        end: this.end || null,
        subject: this.subject,
        text: this.text,
      });
    },
  },
};
</script>
