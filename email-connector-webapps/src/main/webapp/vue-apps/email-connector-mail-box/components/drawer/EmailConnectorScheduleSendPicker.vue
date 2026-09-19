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
  <!-- EXO-90434 -- the date and time a mail goes at: Social's scheduled-post card
       (ActivityComposerDrawer's schedule popup), with the platform's own date-picker and
       time-picker, and the caption saying when, in which zone. Shared by the composer's
       "Schedule send" and the Scheduled view's "Reschedule". -->
  <v-card
    class="d-flex flex-column pa-2"
    flat>
    <div class="d-flex align-center flex-nowrap">
      <date-picker
        v-model="scheduledDate"
        :attach="false"
        :min-value="minScheduleDate"
        :max-value="maxScheduleDate"
        :aria-label="$t('emailConnector.mailBox.newEmail.drawer.schedule.date')"
        class="flex-grow-0 me-2"
        top
        return-iso
        required />
      <div class="d-flex ms-n4">
        <time-picker
          v-model="scheduledHour"
          :min="minScheduleHour"
          :aria-label="$t('emailConnector.mailBox.newEmail.drawer.schedule.hour')"
          class="flex-grow-0 me-3" />
      </div>
      <v-btn
        :disabled="disabled || !scheduledDateTime"
        :loading="loading"
        :aria-label="confirmLabel || $t('emailConnector.mailBox.newEmail.drawer.schedule.confirm')"
        :title="confirmLabel || $t('emailConnector.mailBox.newEmail.drawer.schedule.confirm')"
        class="schedule-picker-confirm"
        icon
        @click="confirm">
        <v-icon
          size="20"
          class="success--text">
          fas fa-check
        </v-icon>
      </v-btn>
    </div>
    <div
      v-if="caption"
      class="caption text-sub-title px-1 pt-1 schedule-picker-caption">
      {{ caption }}
    </div>
  </v-card>
</template>

<script>
import { MAX_SCHEDULE_HORIZON_MS, MIN_SCHEDULE_DELAY_MS, browserTimeZone, formatScheduledTime } from '../../js/EmailConnectorScheduledSendService.js';

/**
 * A date as the date-picker speaks it: yyyy-MM-dd in the user's own day, never the UTC
 * one toISOString would give. Social's $dateUtil.getISODate does the same but shifts the
 * Date it is given, so this one works on a copy of its own.
 *
 * @param {Date} date the date
 * @returns {String} the ISO day
 */
function isoDay(date) {
  const year = date.getFullYear();
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${year}-${month}-${day}`;
}

/**
 * Social's default for a scheduled post: tomorrow, 08:00, in the user's own day.
 *
 * @returns {Date} the default instant
 */
function defaultSchedule() {
  const date = new Date();
  date.setDate(date.getDate() + 1);
  date.setHours(8, 0, 0, 0);
  return date;
}

export default {
  props: {
    // The instant to start from, epoch milliseconds: the previous time when a mail is
    // rescheduled or edited again, else nothing for Social's tomorrow 08:00.
    value: {
      type: Number,
      default: null,
    },
    loading: {
      type: Boolean,
      default: false,
    },
    disabled: {
      type: Boolean,
      default: false,
    },
    // What the check button does, for its tooltip and its accessible name.
    confirmLabel: {
      type: String,
      default: null,
    },
  },
  data: () => ({
    scheduledDate: null,
    scheduledHour: null,
    // Read once: the zone does not change under an open picker.
    timeZone: browserTimeZone(),
  }),
  computed: {
    /**
     * The first day that may be picked: today, in the user's own day.
     *
     * @returns {String} the ISO day
     */
    minScheduleDate() {
      return isoDay(new Date());
    },
    /**
     * The last day that may be picked: a year from now, as the server bounds it.
     *
     * @returns {String} the ISO day
     */
    maxScheduleDate() {
      return isoDay(new Date(Date.now() + MAX_SCHEDULE_HORIZON_MS));
    },
    /**
     * The first hour that may be picked: only on today, a minute from now so the slot
     * the picker snaps to is still ahead when the request lands (Social's margin).
     *
     * @returns {Date} the earliest time, or null on any later day
     */
    minScheduleHour() {
      return this.scheduledDate === this.minScheduleDate ? new Date(Date.now() + MIN_SCHEDULE_DELAY_MS) : null;
    },
    /**
     * The picked instant, from the day and the time.
     *
     * @returns {Number} epoch milliseconds, or null while either is missing
     */
    scheduledDateTime() {
      if (!this.scheduledDate || !this.scheduledHour?.getHours) {
        return null;
      }
      const [year, month, day] = this.scheduledDate.split('-').map(Number);
      const dateTime = new Date(year, month - 1, day);
      dateTime.setHours(this.scheduledHour.getHours(), this.scheduledHour.getMinutes(), 0, 0);
      return dateTime.getTime();
    },
    /**
     * "Sent at {time} ({zone})", in this browser's zone -- the one the mail is scheduled
     * in. The platform exposes no profile time zone to the browser, so there is no
     * second zone to show beside it.
     *
     * @returns {String} the caption, or nothing while no instant is picked
     */
    caption() {
      if (!this.scheduledDateTime) {
        return '';
      }
      return this.$t('emailConnector.mailBox.newEmail.drawer.schedule.sentAt', {
        0: formatScheduledTime(this.scheduledDateTime),
        1: this.timeZone || 'UTC',
      });
    },
  },
  watch: {
    value: {
      immediate: true,
      handler() {
        this.reset();
      },
    },
  },
  methods: {
    /**
     * Puts the picker back on its starting instant: the one given when it is still
     * ahead, else tomorrow 08:00.
     *
     * @returns {void}
     */
    reset() {
      const start = this.value && this.value > Date.now() + MIN_SCHEDULE_DELAY_MS ? new Date(this.value) : defaultSchedule();
      this.scheduledHour = new Date(start.getTime());
      this.scheduledDate = isoDay(start);
    },
    /**
     * Hands the picked instant over, or says why it cannot be taken: too soon (the
     * server wants a minute ahead) or too far (a year at most).
     *
     * @returns {void}
     */
    confirm() {
      const dateTime = this.scheduledDateTime;
      if (!dateTime || dateTime < Date.now() + MIN_SCHEDULE_DELAY_MS) {
        this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.newEmail.drawer.schedule.mustBeInFuture'), 'warning');
        return;
      }
      if (dateTime > Date.now() + MAX_SCHEDULE_HORIZON_MS) {
        this.$root.$emit('alert-message', this.$t('emailConnector.scheduled.date.tooFar'), 'warning');
        return;
      }
      this.$emit('confirm', dateTime, this.timeZone);
    },
  },
};
</script>
