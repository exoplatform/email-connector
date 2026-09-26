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
       server runs at delivery. The row only summarises what the server holds, read live;
       the edit icon opens the drawer, mounted at the app's root like the other settings
       drawers, so this row can sit anywhere in the list without taking the form along.
       Under it, from the same read, the forward the server holds, read-only. -->
  <div>
    <v-list-item class="height-auto">
      <v-list-item-content>
        <v-list-item-title class="text-color">
          {{ $t('UserSettings.emailConnector.absence.title') }}
        </v-list-item-title>
        <v-list-item-subtitle class="text-wrap">
          {{ summary }}
        </v-list-item-subtitle>
        <v-list-item-subtitle
          v-if="stateMessage"
          class="caption warning--text text-wrap">
          {{ $t('UserSettings.emailConnector.absence.row.attention') }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action v-if="supported">
        <v-btn
          icon
          :title="$t('UserSettings.emailConnector.absence.edit.tooltip')"
          @click="$root.$emit(OPEN_ABSENCE_DRAWER_EVENT)">
          <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
        </v-btn>
      </v-list-item-action>
    </v-list-item>
    <!-- The forward (EXO-90650), below the reply because both are what the mail server
         does with the mail it delivers: what the server holds, and where to manage it.
         Where the deployment lets the user set a forward from eXo (EXO-90656), or eXo
         set the one on, the edit icon opens the forward's drawer; otherwise the row stays
         read-only. Hidden while nothing could be established, and when the deployment
         switched the display off (no forwarding in the answer). -->
    <v-list-item v-if="forwardingShown" class="height-auto">
      <v-list-item-content>
        <v-list-item-title class="text-color">
          {{ $t('UserSettings.emailConnector.forwarding.title') }}
        </v-list-item-title>
        <v-list-item-subtitle class="text-wrap">
          {{ forwardingSummary }}
        </v-list-item-subtitle>
        <v-list-item-subtitle
          v-for="rule in ruleForwards"
          :key="`${rule.name}-${rule.destination}`"
          class="text-wrap">
          {{ $t('UserSettings.emailConnector.forwarding.rule', { 0: rule.name, 1: rule.destination }) }}
        </v-list-item-subtitle>
        <v-list-item-subtitle
          v-if="forwardingManagedElsewhere && !forwardingEditable"
          class="text-subtitle text-wrap">
          {{ $t('UserSettings.emailConnector.forwarding.manage') }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action v-if="forwardingEditable">
        <v-btn
          :title="$t('UserSettings.emailConnector.forwarding.edit.tooltip')"
          :aria-label="$t('UserSettings.emailConnector.forwarding.edit.tooltip')"
          icon
          @click="$root.$emit(OPEN_FORWARDING_DRAWER_EVENT)">
          <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
        </v-btn>
      </v-list-item-action>
      <v-list-item-action v-else-if="forwardingManagedElsewhere && forwarding.manageUrl">
        <v-btn
          :href="forwarding.manageUrl"
          :title="$t('UserSettings.emailConnector.forwarding.webmail.tooltip')"
          :aria-label="$t('UserSettings.emailConnector.forwarding.webmail.tooltip')"
          target="_blank"
          rel="noopener noreferrer"
          icon>
          <v-icon size="18" class="icon-default-color">fa-external-link-alt</v-icon>
        </v-btn>
      </v-list-item-action>
    </v-list-item>
  </div>
</template>

<script>
import absenceMixin, { ABSENCE_UPDATED_EVENT, OPEN_ABSENCE_DRAWER_EVENT } from '../../js/EmailConnectorAbsenceMixin.js';
import { FORWARDING_UPDATED_EVENT, OPEN_FORWARDING_DRAWER_EVENT } from '../../js/EmailConnectorForwarding.js';

export default {
  mixins: [absenceMixin],
  data: () => ({
    OPEN_ABSENCE_DRAWER_EVENT,
    OPEN_FORWARDING_DRAWER_EVENT,
  }),
  computed: {
    /**
     * Whether the forward can be managed from eXo: the deployment lets the user set one,
     * or eXo set the one on, which can always be removed.
     *
     * @returns {Boolean} true when the drawer is offered
     */
    forwardingEditable() {
      return !!this.absence?.forwardingAuthoring?.enabled || !!this.forwarding?.managedByExo;
    },
    /**
     * eXo's own rules that forward a copy of the mail they match.
     *
     * @returns {Object[]} {name, destination}
     */
    ruleForwards() {
      return this.forwarding?.ruleForwards || [];
    },
    /**
     * The forward the server holds, read only; null when the display is off.
     *
     * @returns {Object} {state, destinations, keepCopy, scriptName, manageUrl}, or null
     */
    forwarding() {
      return this.absence?.forwarding || null;
    },
    /**
     * Whether there is something to say about a forward: the display is on and the
     * server's answer established something.
     *
     * @returns {Boolean} true when the row is shown
     */
    forwardingShown() {
      return !!this.forwarding?.state && (this.forwarding.state !== 'UNKNOWN' || this.forwardingEditable);
    },
    /**
     * Whether a forward is, or may be, on: then the user is told where to manage it.
     *
     * @returns {Boolean} true for a forward the server holds or a script may hold
     */
    forwardingManagedElsewhere() {
      return this.forwarding?.state === 'SERVER_FORWARD' || this.forwarding?.state === 'MAY_FORWARD_BY_SCRIPT';
    },
    /**
     * The forward in one line: where mail goes and whether a copy stays, which script may
     * forward it, or that nothing is forwarded.
     *
     * @returns {String} the localized line
     */
    forwardingSummary() {
      const forwarding = this.forwarding;
      switch (forwarding?.state) {
      case 'SERVER_FORWARD': {
        const destinations = (forwarding.destinations || []).join(', ');
        if (forwarding.keepCopy === true) {
          return this.$t('UserSettings.emailConnector.forwarding.server.copy', { 0: destinations });
        }
        if (forwarding.keepCopy === false) {
          return this.$t('UserSettings.emailConnector.forwarding.server.noCopy', { 0: destinations });
        }
        return this.$t('UserSettings.emailConnector.forwarding.server', { 0: destinations });
      }
      case 'UNKNOWN':
        return this.$t('UserSettings.emailConnector.forwarding.unknown');
      case 'MAY_FORWARD_BY_SCRIPT':
        return forwarding.scriptName
          ? this.$t('UserSettings.emailConnector.forwarding.script', { 0: forwarding.scriptName })
          : this.$t('UserSettings.emailConnector.forwarding.script.nameless');
      default:
        return this.$t('UserSettings.emailConnector.forwarding.none');
      }
    },
    /**
     * The row's one line: loading, unsupported, off, or on with its last day.
     *
     * @returns {String} the localized line
     */
    summary() {
      if (this.loading && !this.absence) {
        return this.$t('UserSettings.emailConnector.absence.loading');
      }
      if (!this.absence) {
        return this.error || this.$t('UserSettings.emailConnector.absence.description');
      }
      if (!this.supported) {
        return this.unsupportedMessage;
      }
      const vacation = this.absence.vacation;
      if (!vacation?.enabled || this.absence.vacationState === 'ELSEWHERE') {
        return this.$t('UserSettings.emailConnector.absence.off');
      }
      if (vacation.end) {
        return this.$t('UserSettings.emailConnector.absence.onUntil', { 0: this.formatAbsenceDay(vacation.end) });
      }
      return this.$t('UserSettings.emailConnector.absence.on');
    },
  },
  created() {
    this.readAbsence();
    // The drawer and the mailbox band change the reply too; each says so on the document.
    document.addEventListener(ABSENCE_UPDATED_EVENT, this.readAbsence);
    document.addEventListener(FORWARDING_UPDATED_EVENT, this.readAbsence);
  },
  beforeDestroy() {
    document.removeEventListener(ABSENCE_UPDATED_EVENT, this.readAbsence);
    document.removeEventListener(FORWARDING_UPDATED_EVENT, this.readAbsence);
  },
};
</script>
