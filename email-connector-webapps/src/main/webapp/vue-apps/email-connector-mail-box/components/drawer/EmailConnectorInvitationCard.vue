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
  <!-- The event a message's calendar invitation describes (EXO-90840), above the body:
       what, when in the viewer's zone, where, who, and Accept / Maybe / Decline when the
       server says the user may answer. Every text comes from the sender and is
       interpolated, never bound as markup. Nothing is shown while it loads, or when the
       message turns out to carry no readable invitation. -->
  <v-card
    v-if="invitation"
    :class="['invitation-card mb-3 pa-3', { 'invitation-cancelled': invitation.cancelled }]"
    outlined
    flat>
    <div class="d-flex align-start">
      <v-icon
        :class="invitation.cancelled ? 'error--text' : 'primary--text'"
        size="20"
        class="me-3 mt-1">
        far fa-calendar-alt
      </v-icon>
      <div class="flex-grow-1">
        <div
          :class="['font-weight-bold text-wrap text-break', { 'text-decoration-line-through': invitation.cancelled }]"
          class="invitation-title">
          {{ title }}
        </div>
        <div v-if="respondentLabel" class="font-weight-bold text-wrap text-break invitation-respondent">
          {{ respondentLabel }}
        </div>
        <div v-if="invitation.exoMeeting" class="caption primary--text invitation-exo-meeting">
          {{ $t('emailConnector.mailBox.invitation.exoMeeting') }}
        </div>
        <div
          v-if="invitation.cancelled"
          class="error--text invitation-cancelled-line">
          {{ $t('emailConnector.mailBox.invitation.cancelledEvent') }}
        </div>
        <div v-if="when" class="text-sub-title invitation-when">{{ when }}</div>
        <div v-if="recurrence" class="text-sub-title caption invitation-recurrence">
          <v-icon size="12" class="icon-default-color me-1">fas fa-redo</v-icon>{{ recurrence }}
        </div>
        <div v-if="invitation.location" class="text-sub-title text-wrap text-break invitation-location">
          <v-icon size="12" class="icon-default-color me-1">fas fa-map-marker-alt</v-icon>{{ invitation.location }}
        </div>
        <div v-if="organizerLabel" class="text-sub-title caption text-wrap text-break invitation-organizer">
          {{ $t('emailConnector.mailBox.invitation.organizer', { 0: organizerLabel }) }}
        </div>
        <div v-if="invitation.attendeeCount" class="text-sub-title caption invitation-attendees">
          <v-btn
            :aria-expanded="showAttendees ? 'true' : 'false'"
            class="px-0 text-none invitation-attendees-toggle"
            text
            x-small
            @click="showAttendees = !showAttendees">
            {{ $t('emailConnector.mailBox.invitation.attendees', { 0: invitation.attendeeCount }) }}
            <v-icon size="10" class="ms-1">{{ showAttendees ? 'fa-chevron-up' : 'fa-chevron-down' }}</v-icon>
          </v-btn>
          <ul v-if="showAttendees" class="ps-4 mb-0 invitation-attendee-list">
            <li
              v-for="(attendee, index) in invitation.attendees"
              :key="index"
              class="text-wrap text-break">
              {{ personLabel(attendee) }}<span v-if="statusLabel(attendee.partStat)"> · {{ statusLabel(attendee.partStat) }}</span>
            </li>
            <li v-if="invitation.attendeeCount > invitation.attendees.length">
              {{ $t('emailConnector.mailBox.invitation.moreAttendees', { 0: invitation.attendeeCount - invitation.attendees.length }) }}
            </li>
          </ul>
        </div>
        <div v-if="answerLabel" class="mt-2 font-weight-bold invitation-answer">{{ answerLabel }}</div>
        <div
          v-if="invitation.answerable || invitation.landable || invitation.removable"
          class="d-flex flex-wrap align-center mt-2 invitation-actions">
          <template v-if="invitation.answerable">
            <v-btn
              v-for="choice in choices"
              :key="choice.answer"
              :class="['me-2 mb-1', `invitation-${choice.answer.toLowerCase()}`]"
              :color="invitation.answer === choice.answer ? 'primary' : ''"
              :outlined="invitation.answer !== choice.answer"
              :disabled="busy"
              :loading="busy && pending === choice.answer"
              small
              depressed
              @click="answer(choice.answer)">
              {{ choice.label }}
            </v-btn>
          </template>
          <v-btn
            v-if="invitation.landable"
            class="me-2 mb-1 invitation-add-to-calendar"
            :disabled="busy"
            :loading="busy && pending === 'ADD'"
            small
            text
            @click="addToCalendar()">
            <v-icon size="14" class="me-1">fa-calendar-plus</v-icon>
            {{ $t('emailConnector.mailBox.invitation.addToCalendar') }}
          </v-btn>
          <v-btn
            v-if="invitation.removable"
            class="me-2 mb-1 invitation-remove-from-calendar"
            :disabled="busy"
            :loading="busy && pending === 'REMOVE'"
            small
            text
            @click="removeFromCalendar()">
            <v-icon size="14" class="me-1">fa-calendar-minus</v-icon>
            {{ $t('emailConnector.mailBox.invitation.removeFromCalendar') }}
          </v-btn>
        </div>
        <div
          v-if="(invitation.landing === 'LANDED' || invitation.landing === 'ALREADY_HELD') && invitation.landingLink"
          class="caption invitation-landed">
          <a
            :href="invitation.landingLink"
            target="_blank"
            rel="noopener">
            {{ $t('emailConnector.mailBox.invitation.openInAgenda') }}
          </a>
        </div>
        <!-- The server rebuilt this link from the portal's own domain: an event of this
             deployment's Agenda is answered there. -->
        <v-btn
          v-if="invitation.exoMeeting && invitation.agendaUrl"
          :href="invitation.agendaUrl"
          class="mt-2 px-0 text-none invitation-open-agenda"
          color="primary"
          text
          small>
          <v-icon size="14" class="me-1">far fa-calendar-alt</v-icon>
          {{ $t('emailConnector.mailBox.invitation.openInAgenda') }}
        </v-btn>
        <div
          v-if="invitation.answerable && organizerAddress"
          class="caption text-sub-title invitation-reply-to">
          {{ $t('emailConnector.mailBox.invitation.replyTo', { 0: organizerAddress }) }}
        </div>
        <div
          v-else-if="invitation.answerRefusal"
          class="caption text-sub-title invitation-refusal">
          {{ $t('emailConnector.mailBox.invitation.sendNotAllowed') }}
        </div>
      </div>
    </div>
  </v-card>
  <div
    v-else-if="unreadable"
    class="d-flex align-center pb-3 invitation-unreadable">
    <v-icon size="14" class="icon-default-color me-2">far fa-calendar-alt</v-icon>
    <span class="text-sub-title caption">{{ $t('emailConnector.mailBox.invitation.unreadable') }}</span>
  </div>
</template>

<script>
import { personLabel } from '../../js/EmailRecipientDisplay.js';

// The codes that mean "there is an invitation, and it could not be read": said in one
// discreet line. Any other failure (no invitation after all, the server has no
// calendar library, the mailbox is unreachable) shows nothing: the mail is read as before.
const UNREADABLE_CODES = ['emailConnector.invitation.tooLarge', 'emailConnector.invitation.unreadable'];

// The methods of a message that answers rather than invites: shown as somebody's answer,
// whoever it could be said to come from.
const ANSWER_METHODS = ['REPLY', 'COUNTER', 'REFRESH', 'DECLINECOUNTER'];

export default {
  props: {
    // The message on screen, with its technical id and its attachments.
    email: {
      type: Object,
      default: null,
    },
  },
  data: () => ({
    invitation: null,
    unreadable: false,
    showAttendees: false,
    busy: false,
    pending: null,
  }),
  computed: {
    /**
     * Whether the message carries an invitation worth reading.
     *
     * @returns {Boolean} true when it does
     */
    carriesInvitation() {
      return !!this.email?.id && this.$emailConnectorMailBoxService.hasCalendarPart(this.email);
    },
    /**
     * @returns {String} the event's title, or "(No title)"
     */
    title() {
      return this.invitation?.summary || this.$t('emailConnector.mailBox.invitation.noTitle');
    },
    /**
     * @returns {String} when it takes place, in the viewer's zone
     */
    when() {
      return this.$emailConnectorMailBoxService.formatInvitationWhen(this.invitation);
    },
    /**
     * @returns {String} how it recurs, empty when it does not
     */
    recurrence() {
      return this.$emailConnectorMailBoxService.formatInvitationRecurrence(this.invitation, (key, params) => this.$t(key, params));
    },
    /**
     * @returns {String} the organiser's address, the one an answer goes to
     */
    organizerAddress() {
      return this.invitation?.organizer?.address || '';
    },
    /**
     * @returns {String} the organiser as "Name <address>", or whichever is known
     */
    organizerLabel() {
      const organizer = this.invitation?.organizer;
      if (!organizer) {
        return '';
      }
      return organizer.name && organizer.address ? `${organizer.name} <${organizer.address}>` : (organizer.name || organizer.address || '');
    },
    /**
     * @returns {String} what the user answered, empty when they have not
     */
    answerLabel() {
      const answer = this.invitation?.answer;
      return answer && !this.invitation.cancelled && !ANSWER_METHODS.includes(this.invitation.method) ? this.$t(`emailConnector.mailBox.invitation.answered.${answer}`) : '';
    },
    /**
     * What a message that answers rather than invites says: "MEYER accepted", "MEYER
     * proposed a change"… -- from the person it speaks for, their name or else their
     * address, else "Someone" when the message does not say; empty for an invitation.
     *
     * @returns {String} the sentence, empty when there is none
     */
    respondentLabel() {
      const method = this.invitation?.method;
      if (!ANSWER_METHODS.includes(method)) {
        return '';
      }
      const respondent = this.invitation.respondent;
      const who = (respondent && personLabel(respondent)) || this.$t('emailConnector.mailBox.invitation.someone');
      if (method === 'REPLY') {
        const known = ['ACCEPTED', 'TENTATIVE', 'DECLINED', 'DELEGATED'];
        const status = known.includes(respondent?.partStat) ? respondent.partStat : 'OTHER';
        return this.$t(`emailConnector.mailBox.invitation.replied.${status}`, { 0: who });
      }
      return this.$t(`emailConnector.mailBox.invitation.respondent.${method}`, { 0: who });
    },
    /**
     * @returns {Array} the three answers, {answer, label}
     */
    choices() {
      return this.$emailConnectorMailBoxService.INVITATION_ANSWERS
        .map(answer => ({ answer, label: this.$t(`emailConnector.mailBox.invitation.answer.${answer}`) }));
    },
  },
  watch: {
    'email.id': {
      immediate: true,
      handler() {
        this.load();
      },
    },
  },
  methods: {
    /**
     * Reads the message's invitation, when it carries one.
     *
     * @returns {Promise<void>} resolved once read or given up
     */
    load() {
      this.invitation = null;
      this.unreadable = false;
      this.showAttendees = false;
      if (!this.carriesInvitation) {
        return Promise.resolve();
      }
      const emailId = this.email.id;
      return this.$emailConnectorMailBoxService.getInvitation(emailId)
        .then(invitation => {
          if (this.email?.id === emailId) {
            this.invitation = invitation;
          }
        })
        .catch(error => {
          if (this.email?.id === emailId) {
            this.unreadable = UNREADABLE_CODES.includes(error?.code);
          }
        });
    },
    /**
     * Sends the user's answer, and shows it once the server took it.
     *
     * @param {String} answer ACCEPTED, TENTATIVE or DECLINED
     * @returns {Promise<void>} resolved once answered or refused
     */
    answer(answer) {
      if (this.busy || !this.email?.id) {
        return Promise.resolve();
      }
      this.busy = true;
      this.pending = answer;
      return this.$emailConnectorMailBoxService.replyToInvitation(this.email.id, answer)
        .then(invitation => this.landed(invitation, 'ANSWER'))
        .catch(error => {
          const outcome = this.$emailConnectorMailBoxService.invitationReplyOutcome(error, answer);
          if (outcome.answer) {
            this.$set(this.invitation, 'answer', outcome.answer);
          }
          this.$root.$emit('alert-message', this.$t(outcome.messageKey), outcome.alertType);
        })
        .finally(() => {
          this.busy = false;
          this.pending = null;
        });
    },
    /**
     * Adds the event to the user's calendar without answering.
     *
     * @returns {Promise<void>} resolved once added or refused
     */
    addToCalendar() {
      return this.land('ADD', () => this.$emailConnectorMailBoxService.addInvitationToCalendar(this.email.id));
    },
    /**
     * Removes the cancelled event from the user's calendar.
     *
     * @returns {Promise<void>} resolved once removed or refused
     */
    removeFromCalendar() {
      return this.land('REMOVE', () => this.$emailConnectorMailBoxService.removeInvitationFromCalendar(this.email.id));
    },
    /**
     * One landing call: the card shows the invitation as the server returns it, and
     * says what became of the event.
     *
     * @param {String} pending ADD or REMOVE, for the button's spinner
     * @param {Function} call the service call
     * @returns {Promise<void>} resolved once done or refused
     */
    land(pending, call) {
      if (this.busy || !this.email?.id) {
        return Promise.resolve();
      }
      this.busy = true;
      this.pending = pending;
      return call()
        .then(invitation => this.landed(invitation, pending))
        .catch(error => {
          this.$root.$emit('alert-message', this.$t(this.$emailConnectorMailBoxService.invitationLandingError(error)), 'error');
        })
        .finally(() => {
          this.busy = false;
          this.pending = null;
        });
    },
    /**
     * Shows the invitation as the server returns it after a click, and says what
     * became of the event in the user's calendar, when anything did.
     *
     * @param {Object} invitation the invitation returned
     * @param {String} click ANSWER, ADD or REMOVE
     * @returns {void} nothing
     */
    landed(invitation, click) {
      this.invitation = invitation;
      const landing = this.$emailConnectorMailBoxService.invitationLandingOutcome(invitation?.landing, click);
      if (landing) {
        this.$root.$emit('alert-message', this.$t(landing.messageKey), landing.alertType);
      }
    },
    /**
     * @param {Object} person an attendee
     * @returns {String} their name, else their address
     */
    personLabel(person) {
      return personLabel(person);
    },
    /**
     * @param {String} partStat an attendee's status
     * @returns {String} it in words, empty when unknown
     */
    statusLabel(partStat) {
      const known = ['ACCEPTED', 'TENTATIVE', 'DECLINED', 'NEEDS-ACTION', 'DELEGATED'];
      return known.includes(partStat) ? this.$t(`emailConnector.mailBox.invitation.status.${partStat}`) : '';
    },
  },
};
</script>
