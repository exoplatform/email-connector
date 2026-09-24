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
       is acted on one at a time, through its own menu. A click, Enter or Space opens it
       read-only in the reader, as a folder's row opens its mail; the listeners are
       native so the item does not turn into a Vuetify link with a hover tint of its own:
       its background follows a folder row's (backgroundClass). No outline, for the same
       reason as a folder row: the grey lit on focus is the cue. -->
  <v-list-item
    :data-draft-local-id="scheduled.draftLocalId"
    :class="backgroundClass"
    :aria-current="opened ? 'true' : null"
    :aria-label="openLabel"
    class="px-3 py-1 clickable scheduled-email-row"
    style="outline: none;"
    tabindex="0"
    two-line
    @mouseenter.native="hover = true"
    @mouseleave.native="hover = false"
    @focusin.native="hover = true"
    @focusout.native="hover = false"
    @click.native="open"
    @keydown.native.enter="onKey"
    @keydown.native.space="onKey">
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
      <!-- The shared mailbox the mail was written in and goes from (EXO-90595); nothing
           for the user's own. Warned once it is no longer shared: the mail cannot go. -->
      <v-list-item-subtitle
        v-if="mailboxLabel"
        :class="scheduled.mailbox.shared ? 'text-light-color' : 'warning--text'"
        class="caption text-truncate scheduled-email-mailbox">
        <v-icon
          :class="scheduled.mailbox.shared ? 'icon-default-color' : 'warning--text'"
          size="11"
          class="me-1">
          fas fa-user-friends
        </v-icon>
        {{ mailboxLabel }}
      </v-list-item-subtitle>
      <v-list-item-subtitle
        v-if="stateText"
        :class="stateLine.color"
        class="caption text-wrap scheduled-email-state">
        {{ stateText }}
      </v-list-item-subtitle>
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
            v-on="on"
            @click.stop
            @keydown.stop>
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
import { SCHEDULED_ACTIONS } from '../../js/EmailConnectorScheduledSendService.js';

export default {
  props: {
    // The scheduled mail, as GET /scheduled lists it.
    scheduled: {
      type: Object,
      required: true,
    },
    // Whether an action on it is running: its menu waits; the drawer's header bar says
    // so (EXO-90412: no loading bar of the view's own).
    busy: {
      type: Boolean,
      default: false,
    },
    // Whether the reader shows this mail.
    opened: {
      type: Boolean,
      default: false,
    },
    // Whether it sits in the full-screen list column, where the opened row stays lit.
    expanded: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    menu: false,
    hover: false,
  }),
  computed: {
    /**
     * The row's background, a folder row's own (EmailConnectorMailBoxDrawerListItem):
     * nothing at rest, so the pane shows through; lit under the pointer or the focus,
     * and in full screen while the reader shows it.
     *
     * @returns {String} the background class, or nothing
     */
    backgroundClass() {
      if (this.expanded && (this.hover || this.opened)) {
        return 'grey-lighten1-background-opacity-3';
      }
      return this.hover ? 'light-grey-background-color' : '';
    },
    /**
     * @returns {String} what a screen reader announces the row as: opening it
     */
    openLabel() {
      return this.$t('emailConnector.mailBox.scheduled.open', { 0: this.subject });
    },
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
     * The shared mailbox the mail was written in, by its owner's name (EXO-90595):
     * "From Anne's mailbox", with "no longer shared with you" once the share has ended;
     * nothing for the user's own mailbox.
     *
     * @returns {String} the line, or an empty string
     */
    mailboxLabel() {
      const mailbox = this.scheduled.mailbox;
      if (!mailbox) {
        return '';
      }
      const owner = mailbox.ownerFullName || mailbox.ownerMailbox;
      if (!owner) {
        return this.$t('emailConnector.mailBox.scheduled.mailbox.unknown');
      }
      return this.$t(mailbox.shared ? 'emailConnector.mailBox.scheduled.mailbox' : 'emailConnector.mailBox.scheduled.mailbox.unshared',
        { 0: owner });
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
        .map(name => ({ name, icon: SCHEDULED_ACTIONS[name].icon, label: this.$t(SCHEDULED_ACTIONS[name].label) }));
    },
  },
  methods: {
    /**
     * Opens the mail read-only in the reader.
     *
     * @returns {void}
     */
    open() {
      this.$emit('open', this.scheduled);
    },
    /**
     * Opens the mail on Enter or Space pressed on the row itself -- not on its menu
     * button, whose own keys open the menu.
     *
     * @param {KeyboardEvent} event the key
     * @returns {void}
     */
    onKey(event) {
      if (event.target !== event.currentTarget) {
        return;
      }
      event.preventDefault();
      this.open();
    },
  },
};
</script>
