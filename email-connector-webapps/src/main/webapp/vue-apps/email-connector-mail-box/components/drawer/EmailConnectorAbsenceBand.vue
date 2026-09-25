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
  <!-- The automatic reply's cue (EXO-90642): while the user's own reply is on, a band in
       the theme's info tint says so in the user's own mailbox, with "End now" and "Edit",
       which opens the settings' own drawer, mounted at this app's root. Read from the
       dates-only summary eXo caches, so opening the mailbox costs no connection to the
       mail server until that summary is stale. Never
       in someone else's mailbox: the parent shows it on the user's own only.
       Given a shared mailbox (EXO-90651), it says the owner's absence instead -- "Alice
       is away until 15 Oct" -- from the owner's cached dates, never her text, with no
       action: a delegate can neither end nor edit the owner's reply. The user's own
       reply is never said there. -->
  <div
    v-if="shown"
    :class="{ white: sticky }"
    :style="sticky ? STICKY_STYLE : null">
    <v-alert
      :icon="false"
      class="mb-0 text-body-2 border-box-sizing"
      color="info"
      role="status"
      dense
      text
      tile>
      <div class="d-flex align-center flex-wrap text-start" style="width: 0; min-width: 100%;">
        <v-icon
          size="16"
          color="info"
          class="me-3">
          fa-plane-departure
        </v-icon>
        <span class="text--primary flex-grow-1 me-2">{{ message }}</span>
        <template v-if="!sharedMailbox">
          <v-btn
            :loading="ending"
            class="px-1"
            color="primary"
            text
            small
            @click="endNow">
            {{ $t('emailConnector.mailBox.absence.band.endNow') }}
          </v-btn>
          <v-btn
            class="px-1"
            color="primary"
            text
            small
            @click="$root.$emit(OPEN_ABSENCE_DRAWER_EVENT)">
            {{ $t('emailConnector.mailBox.absence.band.edit') }}
          </v-btn>
        </template>
      </div>
    </v-alert>
  </div>
</template>

<script>
// The user-setting bundle, which every opening of the mailbox requires first, provides
// the drawer; the event names are shared with it so neither side spells them alone.
import { ABSENCE_UPDATED_EVENT, OPEN_ABSENCE_DRAWER_EVENT, notifyAbsenceUpdated } from '../../../email-connector-user-setting/js/EmailConnectorAbsenceMixin.js';

/**
 * Today, yyyy-MM-dd: in the zone the reply's days are in when known -- an owner's days,
 * seen by a delegate elsewhere, change at her midnight -- else in the user's own day.
 *
 * @param {String} [timeZone] the IANA zone of the reply's days
 * @returns {String} the ISO day
 */
function today(timeZone) {
  if (timeZone) {
    try {
      // Built from the parts, so no locale's date pattern decides the shape.
      const parts = {};
      new Intl.DateTimeFormat('en-US', { timeZone, year: 'numeric', month: '2-digit', day: '2-digit' })
        .formatToParts(new Date())
        .forEach(part => parts[part.type] = part.value);
      return `${parts.year}-${parts.month}-${parts.day}`;
    } catch (e) {
      // An unknown zone: the user's own day.
    }
  }
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export default {
  props: {
    // Pinned to the top of the scrolling list it sits in.
    sticky: { type: Boolean, default: false },
    // The switcher entry of the shared mailbox the user is in: the band then says its
    // owner's absence, read through that share; none for the user's own mailbox.
    sharedMailbox: { type: Object, default: null },
  },
  data: () => ({
    STICKY_STYLE: { position: 'sticky', top: 0, zIndex: 3 },
    OPEN_ABSENCE_DRAWER_EVENT,
    status: null,
    ending: false,
  }),
  computed: {
    /**
     * The share the band reads the owner's dates through, or null in the user's own
     * mailbox.
     *
     * @returns {Number} the delegation id, or null
     */
    delegationId() {
      return this.sharedMailbox?.delegationId || null;
    },
    /**
     * Whether the band shows: eXo's own reply is on and its last day has not passed.
     *
     * @returns {Boolean} true when shown
     */
    shown() {
      return !!this.status?.enabled && (!this.status.end || this.status.end >= today(this.status.timeZone));
    },
    /**
     * "Automatic reply on until 15 Oct", or "from 1 Oct" while it has not started.
     *
     * @returns {String} the localized sentence
     */
    message() {
      const status = this.status || {};
      if (this.sharedMailbox) {
        return this.ownerMessage(status);
      }
      if (status.start && status.start > today(status.timeZone)) {
        return status.end
          ? this.$t('emailConnector.mailBox.absence.band.scheduled', { 0: this.formatDay(status.start), 1: this.formatDay(status.end) })
          : this.$t('emailConnector.mailBox.absence.band.scheduledOpen', { 0: this.formatDay(status.start) });
      }
      return status.end
        ? this.$t('emailConnector.mailBox.absence.band.onUntil', { 0: this.formatDay(status.end) })
        : this.$t('emailConnector.mailBox.absence.band.on');
    },
  },
  watch: {
    /**
     * Another mailbox chosen in the switcher: the previous one's dates go at once, and
     * the new one's are read.
     *
     * @returns {void}
     */
    delegationId() {
      this.status = null;
      this.read();
    },
  },
  created() {
    this.read();
    // The drawer and the Settings row's own changes are said on the document.
    document.addEventListener(ABSENCE_UPDATED_EVENT, this.read);
  },
  beforeDestroy() {
    document.removeEventListener(ABSENCE_UPDATED_EVENT, this.read);
  },
  methods: {
    /**
     * Reads the summary; silent on failure, since a missing cue is better than an error
     * over the mailbox.
     *
     * @returns {void}
     */
    read() {
      const delegationId = this.delegationId;
      this.$emailConnectorCommonService.getAbsenceStatus(delegationId)
        .then(status => {
          // An answer for a mailbox the user has left since is dropped.
          if (delegationId === this.delegationId) {
            this.status = status;
          }
        })
        .catch(() => {
          if (delegationId === this.delegationId) {
            this.status = null;
          }
        });
    },
    /**
     * "Alice is away until 15 Oct", "from 1 Oct to 15 Oct" while it has not started, and
     * the day eXo last checked it when that is older than the server's freshness bound --
     * a delegate cannot check it again, and that check may have found the server
     * unreachable.
     *
     * @param {Object} status the owner's dates
     * @returns {String} the localized sentence
     */
    ownerMessage(status) {
      const owner = this.sharedMailbox.ownerFullName || this.sharedMailbox.ownerMailbox || '';
      let sentence;
      if (status.start && status.start > today(status.timeZone)) {
        sentence = status.end
          ? this.$t('emailConnector.mailBox.absence.band.owner.scheduled', { 0: owner, 1: this.formatDay(status.start), 2: this.formatDay(status.end) })
          : this.$t('emailConnector.mailBox.absence.band.owner.scheduledOpen', { 0: owner, 1: this.formatDay(status.start) });
      } else {
        sentence = status.end
          ? this.$t('emailConnector.mailBox.absence.band.owner.awayUntil', { 0: owner, 1: this.formatDay(status.end) })
          : this.$t('emailConnector.mailBox.absence.band.owner.away', { 0: owner });
      }
      return status.stale && status.lastServerReadDate
        ? this.$t('emailConnector.mailBox.absence.band.owner.checked', { 0: sentence, 1: this.formatInstant(status.lastServerReadDate) })
        : sentence;
    },
    /**
     * Switches the reply off on the mail server, keeping its text for next time.
     *
     * @returns {void}
     */
    endNow() {
      this.ending = true;
      this.$emailConnectorCommonService.disableVacation()
        .then(() => {
          this.status = { ...this.status, enabled: false };
          this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.absence.band.ended'), 'success');
          // The Settings row, when the page shows it, reads the server again.
          notifyAbsenceUpdated();
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.absence.band.endFailed'), 'error'))
        .finally(() => this.ending = false);
    },
    /**
     * A day as the user reads it.
     *
     * @param {String} day yyyy-MM-dd
     * @returns {String} the localized day
     */
    formatDay(day) {
      const [year, month, date] = day.split('-').map(Number);
      return new Date(year, month - 1, date).toLocaleDateString(eXo.env.portal.language, { day: 'numeric', month: 'short' });
    },
    /**
     * An instant's day as the user reads it.
     *
     * @param {Number} millis epoch milliseconds
     * @returns {String} the localized day
     */
    formatInstant(millis) {
      return new Date(millis).toLocaleDateString(eXo.env.portal.language, { day: 'numeric', month: 'short' });
    },
  },
};
</script>
