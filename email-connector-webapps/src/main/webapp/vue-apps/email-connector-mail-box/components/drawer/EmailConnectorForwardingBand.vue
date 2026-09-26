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
  <!-- The forward's cue (EXO-90656): while the user's mail is, or may be, forwarded, a
       persistent band in the theme's warning tint says where, in the user's own mailbox,
       with "Manage", which opens the settings' own forwarding drawer, mounted at this
       app's root. One of the forwarding safeguards: whoever set the forward, the owner
       sees it every time she opens her mailbox. Read from the status eXo caches, so
       opening the mailbox costs no connection to the mail server until it is stale.
       Never in someone else's mailbox: the parent shows it on the user's own only. -->
  <div
    v-if="shown"
    :class="{ white: sticky }"
    :style="sticky ? STICKY_STYLE : null">
    <v-alert
      :icon="false"
      class="mb-0 text-body-2 border-box-sizing"
      color="warning"
      role="status"
      dense
      text
      tile>
      <div class="d-flex align-center flex-wrap text-start" style="width: 0; min-width: 100%;">
        <v-icon
          size="16"
          color="warning"
          class="me-3">
          fa-share-square
        </v-icon>
        <span class="text--primary flex-grow-1 me-2">{{ message }}</span>
        <v-btn
          class="px-1"
          color="primary"
          text
          small
          @click="$root.$emit(OPEN_FORWARDING_DRAWER_EVENT)">
          {{ $t('emailConnector.mailBox.forwarding.band.manage') }}
        </v-btn>
      </div>
    </v-alert>
  </div>
</template>

<script>
// The user-setting bundle, which every opening of the mailbox requires first, provides
// the drawer; the event names are shared with it so neither side spells them alone.
import { FORWARDING_UPDATED_EVENT, OPEN_FORWARDING_DRAWER_EVENT } from '../../../email-connector-user-setting/js/EmailConnectorForwarding.js';

export default {
  props: {
    // Pinned to the top of the scrolling list it sits in.
    sticky: { type: Boolean, default: false },
  },
  data: () => ({
    STICKY_STYLE: { position: 'sticky', top: 0, zIndex: 3 },
    OPEN_FORWARDING_DRAWER_EVENT,
    status: null,
  }),
  computed: {
    /**
     * eXo's own rules that forward a copy of the mail they match.
     *
     * @returns {Object[]} {name, destination}
     */
    ruleForwards() {
      return this.status?.ruleForwards || [];
    },
    /**
     * Whether mail is, or may be, forwarded.
     *
     * @returns {Boolean} true when shown
     */
    shown() {
      const state = this.status?.state;
      return state === 'SERVER_FORWARD' || state === 'MAY_FORWARD_BY_SCRIPT' || this.ruleForwards.length > 0;
    },
    /**
     * "Your mail is forwarded to bob@acme.com", "may be forwarded by a script", or "a
     * filter forwards some of your mail to …".
     *
     * @returns {String} the localized sentence
     */
    message() {
      const status = this.status || {};
      if (status.state === 'SERVER_FORWARD') {
        return this.$t('emailConnector.mailBox.forwarding.band.on', { 0: (status.destinations || []).join(', ') });
      }
      if (status.state === 'MAY_FORWARD_BY_SCRIPT') {
        return status.scriptName
          ? this.$t('emailConnector.mailBox.forwarding.band.script', { 0: status.scriptName })
          : this.$t('emailConnector.mailBox.forwarding.band.script.nameless');
      }
      const destinations = [...new Set(this.ruleForwards.map(rule => rule.destination))].join(', ');
      return this.$t('emailConnector.mailBox.forwarding.band.rules', { 0: destinations });
    },
  },
  created() {
    this.read();
    // The drawer and the Settings row's own changes are said on the document.
    document.addEventListener(FORWARDING_UPDATED_EVENT, this.read);
  },
  beforeDestroy() {
    document.removeEventListener(FORWARDING_UPDATED_EVENT, this.read);
  },
  methods: {
    /**
     * Reads the cached status; silent on failure, since a missing cue is better than an
     * error over the mailbox.
     *
     * @returns {void}
     */
    read() {
      this.$emailConnectorCommonService.getForwardingStatus()
        .then(status => this.status = status)
        .catch(() => this.status = null);
    },
  },
};
</script>
