<!--
Copyright (C) 2025 eXo Platform SAS.

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
  <!-- What the import poll feeds: an indeterminate strip with a running count
       while the file is walked, and the four-number report once it ended. A
       banner rather than a toast because the report is worth reading — "312
       already known" is the answer to "why did my 350-card file add 38" — and
       a toast is gone before the question forms. -->
  <div v-if="visible" class="px-4 pt-2">
    <v-alert
      :type="alertType"
      text
      dense
      class="mb-0 caption"
      :dismissible="!running"
      @input="$emit('dismiss')">
      <template v-if="running">
        {{ $t('emailConnector.contacts.import.progress') }}
        <span v-if="processed" class="text-sub-title ms-1">
          {{ $t('emailConnector.contacts.import.progressCount', {0: processed}) }}
        </span>
        <v-progress-linear
          indeterminate
          height="2"
          class="mt-1" />
      </template>
      <template v-else-if="failed">
        {{ $t('emailConnector.contacts.import.failed') }}
      </template>
      <template v-else>
        {{ $t('emailConnector.contacts.import.report', {
          0: state.imported || 0,
          1: state.alreadyKnown || 0,
          2: state.noAddress || 0,
          3: state.unreadable || 0,
        }) }}
        <div v-if="truncated">
          {{ $t('emailConnector.contacts.import.tooManyCards') }}
        </div>
      </template>
    </v-alert>
  </div>
</template>

<script>
export default {
  props: {
    // The polled import state, exactly as the REST answers it; null hides the
    // banner entirely.
    state: {
      type: Object,
      default: null,
    },
  },
  computed: {
    /**
     * Whether there is anything to say at all.
     *
     * @returns {boolean} true when a run is going or a report is showing
     */
    visible() {
      return !!this.state?.status;
    },
    /**
     * Whether the run is still walking the file.
     *
     * @returns {boolean} true while importing
     */
    running() {
      return this.state?.status === 'IN_PROGRESS';
    },
    /**
     * Whether the run itself broke — as opposed to skipping cards, which is
     * ordinary and reported in the numbers.
     *
     * @returns {boolean} true for a failed run
     */
    failed() {
      return this.state?.status === 'FAILURE';
    },
    /**
     * Whether the card cap cut the file short, worth its own sentence under
     * the report.
     *
     * @returns {boolean} true when the run stopped at the cap
     */
    truncated() {
      return this.state?.messageCode === 'emailConnector.contacts.import.tooManyCards';
    },
    /**
     * How many cards have landed in a counter so far — the only progress
     * number an unread file allows, there being no total without pre-reading.
     *
     * @returns {number} the processed count
     */
    processed() {
      return (this.state?.imported || 0) + (this.state?.alreadyKnown || 0)
        + (this.state?.noAddress || 0) + (this.state?.unreadable || 0);
    },
    /**
     * The alert's tone: busy is informative, a broken run is an error, a
     * report is success — whatever its numbers, the run itself worked.
     *
     * @returns {string} the vuetify alert type
     */
    alertType() {
      if (this.running) {
        return 'info';
      }
      return this.failed ? 'error' : 'success';
    },
  },
};
</script>
