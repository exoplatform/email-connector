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
  <!-- One mail of the "Scheduled" view (EXO-90434): who it goes to, what it says, when
       it goes, and where it stands. No checkbox, no category, no drag: a scheduled mail
       is acted on one at a time, through its own menu. -->
  <v-list-item
    :data-draft-local-id="scheduled.draftLocalId"
    class="px-3 py-1 scheduled-email-row"
    two-line>
    <v-list-item-icon class="me-3 my-auto">
      <v-icon size="18" :class="stateLine ? stateLine.color : 'icon-default-color'">fa-clock</v-icon>
    </v-list-item-icon>
    <v-list-item-content class="py-1">
      <v-list-item-title class="d-flex align-center">
        <span class="text-truncate font-weight-bold scheduled-email-recipients">{{ recipientsLabel }}</span>
      </v-list-item-title>
      <v-list-item-subtitle class="text-truncate text-color scheduled-email-subject">
        {{ subject }}
        <span v-if="scheduled.snippet" class="text-light-color"> — {{ scheduled.snippet }}</span>
      </v-list-item-subtitle>
      <v-list-item-subtitle class="caption scheduled-email-date">
        <v-icon size="11" class="me-1 icon-default-color">far fa-clock</v-icon>
        {{ dateLabel }}
      </v-list-item-subtitle>
      <v-list-item-subtitle
        v-if="stateText"
        :class="stateLine.color"
        class="caption text-wrap scheduled-email-state">
        {{ stateText }}
      </v-list-item-subtitle>
      <v-progress-linear
        v-if="busy"
        class="mt-1"
        color="primary"
        height="2"
        indeterminate />
    </v-list-item-content>
    <v-list-item-action class="my-auto ms-1">
      <v-menu
        v-if="actions.length"
        v-model="menu"
        offset-y
        left>
        <template #activator="{ on, attrs }">
          <v-btn
            :disabled="busy"
            :aria-label="$t('emailConnector.mailBox.scheduled.actions')"
            :title="$t('emailConnector.mailBox.scheduled.actions')"
            class="scheduled-email-menu"
            icon
            small
            v-bind="attrs"
            v-on="on">
            <v-icon size="16" class="icon-default-color">fas fa-ellipsis-v</v-icon>
          </v-btn>
        </template>
        <v-list dense>
          <v-list-item
            v-for="action in actions"
            :key="action.name"
            :data-action="action.name"
            class="scheduled-email-action"
            @click="$emit('action', action.name, scheduled)">
            <v-list-item-icon class="me-2 my-auto">
              <v-icon size="14" class="icon-default-color">{{ action.icon }}</v-icon>
            </v-list-item-icon>
            <v-list-item-title>{{ action.label }}</v-list-item-title>
          </v-list-item>
        </v-list>
      </v-menu>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
import { personLabel } from '../../js/EmailRecipientDisplay.js';

// Each action's icon and label key; the menu shows the ones the mail's state offers
// (scheduledActions), in that order.
const ACTIONS = {
  edit: { icon: 'fa-pen', label: 'emailConnector.mailBox.scheduled.action.edit' },
  reschedule: { icon: 'fa-calendar-alt', label: 'emailConnector.mailBox.scheduled.action.reschedule' },
  sendNow: { icon: 'fa-paper-plane', label: 'emailConnector.mailBox.scheduled.action.sendNow' },
  retry: { icon: 'fa-redo', label: 'emailConnector.mailBox.scheduled.action.retry' },
  sendAgain: { icon: 'fa-redo', label: 'emailConnector.mailBox.scheduled.action.sendAgain' },
  cancel: { icon: 'fa-ban', label: 'emailConnector.mailBox.scheduled.action.cancel' },
  moveToDrafts: { icon: 'fa-file-alt', label: 'emailConnector.mailBox.scheduled.action.moveToDrafts' },
  discard: { icon: 'fa-trash', label: 'emailConnector.mailBox.scheduled.action.discard' },
};

export default {
  props: {
    // The scheduled mail, as GET /scheduled lists it.
    scheduled: {
      type: Object,
      required: true,
    },
    // Whether an action on it is running: its menu waits, a bar says so.
    busy: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    menu: false,
  }),
  computed: {
    /**
     * Who the mail goes to, or a placeholder when nobody -- which the server refuses to
     * schedule, but a row read back from a damaged draft must still render.
     *
     * @returns {String} the recipients line
     */
    recipientsLabel() {
      const names = (this.scheduled.to || []).map(recipient => personLabel(recipient)).filter(Boolean);
      return names.length
        ? this.$t('emailConnector.mailBox.scheduled.to', { 0: names.join(', ') })
        : this.$t('emailConnector.mailBox.scheduled.noRecipient');
    },
    /**
     * @returns {String} the subject, or the "(no subject)" placeholder
     */
    subject() {
      return this.scheduled.subject || this.$t('emailConnector.mailBox.scheduled.noSubject');
    },
    /**
     * @returns {String} "Scheduled for {date}", in the zone the date was chosen in
     */
    dateLabel() {
      return this.$t('emailConnector.mailBox.scheduled.at', {
        0: this.$emailConnectorMailBoxService.formatScheduledDate(this.scheduled.scheduledDate, this.scheduled.timeZone),
      });
    },
    /**
     * @returns {Object} what the mail's state says, or null while it simply waits
     */
    stateLine() {
      return this.$emailConnectorMailBoxService.scheduledStateLine(this.scheduled);
    },
    /**
     * @returns {String} the state line's words: "Not sent: {reason}", "Couldn't confirm
     *          it was sent", "Sending..."; nothing while it waits
     */
    stateText() {
      if (!this.stateLine) {
        return '';
      }
      return this.stateLine.reasonKey
        ? this.$t(this.stateLine.key, { 0: this.$t(this.stateLine.reasonKey) })
        : this.$t(this.stateLine.key);
    },
    /**
     * @returns {Array} the actions the mail's state offers, {name, icon, label}
     */
    actions() {
      return this.$emailConnectorMailBoxService.scheduledActions(this.scheduled)
        .map(name => ({ name, icon: ACTIONS[name].icon, label: this.$t(ACTIONS[name].label) }));
    },
  },
};
</script>
