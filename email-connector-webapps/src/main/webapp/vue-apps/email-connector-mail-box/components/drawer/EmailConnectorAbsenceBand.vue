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
       the theme's info tint says so in the user's own mailbox, with "End now" and a link
       to the settings. Read from the dates-only summary eXo caches, so opening the
       mailbox costs no connection to the mail server until that summary is stale. Never
       in someone else's mailbox: the parent shows it on the user's own only. -->
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
          :href="settingsUrl"
          class="px-1"
          color="primary"
          text
          small>
          {{ $t('emailConnector.mailBox.absence.band.edit') }}
        </v-btn>
      </div>
    </v-alert>
  </div>
</template>

<script>
/**
 * Today in the user's own day, yyyy-MM-dd.
 *
 * @returns {String} the ISO day
 */
function today() {
  const date = new Date();
  return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`;
}

export default {
  props: {
    // Pinned to the top of the scrolling list it sits in.
    sticky: { type: Boolean, default: false },
  },
  data: () => ({
    STICKY_STYLE: { position: 'sticky', top: 0, zIndex: 3 },
    status: null,
    ending: false,
  }),
  computed: {
    /**
     * Whether the band shows: eXo's own reply is on and its last day has not passed.
     *
     * @returns {Boolean} true when shown
     */
    shown() {
      return !!this.status?.enabled && (!this.status.end || this.status.end >= today());
    },
    /**
     * "Automatic reply on until 15 Oct", or "from 1 Oct" while it has not started.
     *
     * @returns {String} the localized sentence
     */
    message() {
      const status = this.status || {};
      if (status.start && status.start > today()) {
        return status.end
          ? this.$t('emailConnector.mailBox.absence.band.scheduled', { 0: this.formatDay(status.start), 1: this.formatDay(status.end) })
          : this.$t('emailConnector.mailBox.absence.band.scheduledOpen', { 0: this.formatDay(status.start) });
      }
      return status.end
        ? this.$t('emailConnector.mailBox.absence.band.onUntil', { 0: this.formatDay(status.end) })
        : this.$t('emailConnector.mailBox.absence.band.on');
    },
    /**
     * The user's settings page, where the reply is edited.
     *
     * @returns {String} the URL
     */
    settingsUrl() {
      return `${eXo.env.portal.context}/${eXo.env.portal.metaPortalName}/settings`;
    },
  },
  created() {
    this.read();
    document.addEventListener('email-absence-updated', this.read);
  },
  beforeDestroy() {
    document.removeEventListener('email-absence-updated', this.read);
  },
  methods: {
    /**
     * Reads the summary; silent on failure, since a missing cue is better than an error
     * over the mailbox.
     *
     * @returns {void}
     */
    read() {
      this.$emailConnectorCommonService.getAbsenceStatus()
        .then(status => this.status = status)
        .catch(() => this.status = null);
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
  },
};
</script>
