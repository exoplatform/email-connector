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
  <!-- The report of an ended mail import (EXO-90846): the three counts, what the refused
       mails were refused for, and what cut the run short, each in the reader's words. -->
  <div>
    <div class="d-flex align-center mb-3">
      <v-icon
        :class="failed ? 'error--text' : 'success--text'"
        size="20"
        class="me-2">
        {{ failed ? 'fa-exclamation-circle' : 'fa-check-circle' }}
      </v-icon>
      <span class="font-weight-bold">
        {{ $t(failed ? 'emailConnector.mailBox.import.report.failed' : 'emailConnector.mailBox.import.report.done') }}
      </span>
    </div>
    <div class="mb-2">{{ $t('emailConnector.mailBox.import.report.added', { 0: state.added || 0 }) }}</div>
    <div class="mb-2">{{ $t('emailConnector.mailBox.import.report.skipped', { 0: state.skipped || 0 }) }}</div>
    <div class="mb-2">{{ $t('emailConnector.mailBox.import.report.refused', { 0: state.refused || 0 }) }}</div>
    <ul v-if="refusals.length" class="mb-2 caption text-sub-title">
      <li v-for="refusal in refusals" :key="refusal.reason">
        {{ $t(`emailConnector.mailBox.import.refusal.${refusal.reason}`, { 0: refusal.count }) }}
      </li>
    </ul>
    <div v-if="state.messageCode" class="mt-3 text-sub-title">
      {{ messageText }}
    </div>
  </div>
</template>

<script>
export default {
  props: {
    // The ended import's state, as the server reports it.
    state: {
      type: Object,
      default: () => ({}),
    },
  },
  computed: {
    /**
     * @returns {Boolean} whether the run itself broke
     */
    failed() {
      return this.state?.status === 'FAILURE';
    },
    /**
     * The refused mails by reason, the reasons this interface knows only.
     *
     * @returns {Array<Object>} {reason, count} pairs
     */
    refusals() {
      return Object.entries(this.state?.refusals || {})
        .filter(([reason]) => this.$te(`emailConnector.mailBox.import.refusal.${reason}`))
        .map(([reason, count]) => ({ reason, count }));
    },
    /**
     * What cut the run short, in words; the generic sentence for a code this interface
     * does not know.
     *
     * @returns {String} the sentence
     */
    messageText() {
      const code = this.state?.messageCode;
      return this.$te(code) ? this.$t(code) : this.$t('emailConnector.import.failed');
    },
  },
};
</script>
