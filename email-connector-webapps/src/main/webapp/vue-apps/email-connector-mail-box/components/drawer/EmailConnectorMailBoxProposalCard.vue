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
  <!-- One tool call a filter's assistant proposed instead of running it (EXO-90659), in
       the mail's Automations panel. Rendered by eXo from the recorded call, never from the
       assistant's wording: the tool's own title and description, as its definition gave
       them, then every argument as the model gave it -- a space id or a username shown
       with its name where the platform's own services resolve it, the raw value always
       beside it --, then the assistant's one-line reason, labelled as its suggestion.
       Everything is text: no markup, no link. One decision per card: Approve runs the
       call as the user, through the platform's own tool path; Reject; Continue in the
       chat hands it to the regular AI chat, after which it never runs from here. -->
  <v-card
    class="pa-2 mt-2"
    outlined
    flat>
    <div class="d-flex align-baseline">
      <span class="text-body-2 font-weight-bold text-break">{{ title }}</span>
      <v-spacer />
      <span
        v-if="statusLine"
        :class="statusClass"
        class="text-caption ms-2 text-no-wrap">
        {{ statusLine }}
      </span>
    </div>
    <div v-if="proposal.toolTitle" class="text-caption text-sub-title">{{ proposal.toolName }}</div>
    <div
      v-if="proposal.toolDescription"
      :style="descriptionStyle"
      class="text-caption text-sub-title text-break">
      {{ proposal.toolDescription }}
    </div>
    <a
      v-if="longDescription"
      class="text-caption primary--text"
      role="button"
      href="javascript:void(0);"
      @click.prevent="descriptionOpen = !descriptionOpen">
      {{ $t(descriptionOpen ? 'emailConnector.mailBox.automations.proposal.less' : 'emailConnector.mailBox.automations.proposal.more') }}
    </a>
    <div class="mt-1">
      <div
        v-for="argument in argumentLines"
        :key="argument.key"
        class="text-body-2 text-break"
        style="white-space: pre-wrap;">
        <span class="font-weight-bold">{{ argument.key }}:</span>
        {{ argument.text }}
      </div>
      <div v-if="!argumentLines.length" class="text-body-2 text-sub-title">
        {{ $t('emailConnector.mailBox.automations.proposal.noArguments') }}
      </div>
    </div>
    <div v-if="proposal.rationale" class="text-caption font-italic mt-1 text-break">
      {{ $t('emailConnector.mailBox.automations.proposal.why', { 0: proposal.rationale }) }}
    </div>
    <div v-if="waiting" class="text-caption text-sub-title mt-1">{{ expiryLine }}</div>
    <div v-if="waiting" class="d-flex flex-wrap align-center mt-1">
      <v-btn
        v-if="actions"
        :loading="busy === 'approve'"
        :disabled="!!busy"
        class="me-2 mb-1"
        color="primary"
        elevation="0"
        small
        @click="approve">
        {{ $t('emailConnector.mailBox.automations.proposal.approve') }}
      </v-btn>
      <v-btn
        :loading="busy === 'reject'"
        :disabled="!!busy"
        class="me-2 mb-1"
        outlined
        small
        @click="reject">
        {{ $t('emailConnector.mailBox.automations.proposal.reject') }}
      </v-btn>
      <v-btn
        v-if="actions"
        :loading="busy === 'handover'"
        :disabled="!!busy"
        class="mb-1 px-1"
        color="primary"
        text
        small
        @click="continueInChat">
        {{ $t('emailConnector.mailBox.automations.proposal.continue') }}
      </v-btn>
    </div>
    <div
      v-if="error"
      class="text-caption error--text"
      role="alert">
      {{ error }}
    </div>
  </v-card>
</template>

<script>
import { FILTER_PROPOSAL_EXTENSION } from '../../../email-connector-user-setting/js/EmailConnectorFilters.js';

/** How many characters of a tool's description show before "More". */
const DESCRIPTION_PREVIEW = 160;

/** An argument naming a space by its id, as the platform's tools name them. */
const SPACE_ID_ARGUMENT = /(^|_)space_?id$/i;

/** An argument naming one user, or several, by username. */
const USERNAME_ARGUMENT = /(^|_)(user_?name|assignee)s?$/i;

/** A day, in milliseconds. */
const DAY = 24 * 3600 * 1000;

export default {
  props: {
    // The proposal {id, toolName, toolTitle, toolDescription, arguments, rationale,
    // status, expiresDate, lastError}, as the panel's read gives it.
    proposal: {
      type: Object,
      required: true,
    },
    // The match whose assistant proposed it.
    match: {
      type: Object,
      default: null,
    },
    // The mail.
    email: {
      type: Object,
      default: null,
    },
  },
  data: () => ({
    busy: null,
    error: null,
    descriptionOpen: false,
    // The names the platform gave the ids and usernames of the arguments, by raw value.
    names: {},
  }),
  computed: {
    /**
     * The AI glue's part of the card: running an approved call and opening the chat.
     * Without it -- no AI add-on --, nothing could run the call, and the card offers
     * only Reject.
     *
     * @returns {Object|null} {approve(proposal, request), continueInChat(proposal, match, email, t)}
     */
    actions() {
      const extensions = extensionRegistry.loadExtensions(FILTER_PROPOSAL_EXTENSION.app, FILTER_PROPOSAL_EXTENSION.type) || [];
      return extensions.find(extension => extension?.approve && extension?.continueInChat) || null;
    },
    /**
     * @returns {String} the tool's own title, else its name
     */
    title() {
      return this.proposal.toolTitle || this.proposal.toolName;
    },
    /**
     * @returns {Boolean} whether the proposal still waits for a decision
     */
    waiting() {
      return this.proposal.status === 'PROPOSED';
    },
    /**
     * @returns {Boolean} whether the description is long enough to fold
     */
    longDescription() {
      return (this.proposal.toolDescription || '').length > DESCRIPTION_PREVIEW;
    },
    /**
     * The folded description's style: two lines, then an ellipsis.
     *
     * @returns {Object} the style binding
     */
    descriptionStyle() {
      if (!this.longDescription || this.descriptionOpen) {
        return {};
      }
      return { display: '-webkit-box', WebkitLineClamp: 2, WebkitBoxOrient: 'vertical', overflow: 'hidden' };
    },
    /**
     * The call's arguments, as the model gave them.
     *
     * @returns {Object|null} the arguments object, or null when they are not a JSON object
     */
    parsedArguments() {
      try {
        const value = JSON.parse(this.proposal.arguments || '{}');
        return value && typeof value === 'object' && !Array.isArray(value) ? value : null;
      } catch (e) {
        return null;
      }
    },
    /**
     * One line per argument: its key, then its value as text -- with the name the
     * platform gives it, where it resolves one, before the raw value.
     *
     * @returns {Object[]} {key, text}
     */
    argumentLines() {
      if (!this.parsedArguments) {
        return this.proposal.arguments ? [{ key: '-', text: this.proposal.arguments }] : [];
      }
      return Object.keys(this.parsedArguments).map(key => ({ key, text: this.valueText(this.parsedArguments[key]) }));
    },
    /**
     * @returns {String} how long the proposal still waits
     */
    expiryLine() {
      const days = Math.max(0, Math.ceil(((this.proposal.expiresDate || 0) - Date.now()) / DAY));
      return days <= 1
        ? this.$t('emailConnector.mailBox.automations.proposal.expiresToday')
        : this.$t('emailConnector.mailBox.automations.proposal.expiresIn', { 0: days });
    },
    /**
     * @returns {String|null} the decided proposal's status, in words
     */
    statusLine() {
      const status = this.proposal.status;
      if (!status || status === 'PROPOSED') {
        return null;
      }
      if (status === 'EXPIRED' && this.proposal.lastError === 'emailConnector.filters.proposal.superseded') {
        return this.$t('emailConnector.mailBox.automations.proposal.status.superseded');
      }
      if (status === 'FAILED') {
        return this.$t('emailConnector.mailBox.automations.proposal.status.FAILED', { 0: this.reason(this.proposal.lastError) });
      }
      return this.$t(`emailConnector.mailBox.automations.proposal.status.${status}`);
    },
    /**
     * @returns {String} the status line's colour
     */
    statusClass() {
      if (this.proposal.status === 'FAILED') {
        return 'error--text';
      }
      return this.proposal.status === 'DONE' ? 'success--text' : 'text-sub-title';
    },
  },
  created() {
    this.resolveNames();
  },
  methods: {
    /**
     * An argument's value as text: a string as it is, a list joined, anything else as
     * its JSON; a value the platform named shows its name first.
     *
     * @param {*} value - the value
     * @returns {String} the text
     */
    valueText(value) {
      if (Array.isArray(value) && value.every(item => typeof item !== 'object' || item === null)) {
        return value.map(item => this.named(item)).join(', ');
      }
      if (value !== null && typeof value === 'object') {
        return JSON.stringify(value, null, 1);
      }
      return this.named(value);
    },
    /**
     * A raw value, with the name the platform gave it when it has one.
     *
     * @param {*} value - the value
     * @returns {String} "Name (value)", or the value
     */
    named(value) {
      const raw = String(value);
      return this.names[raw] ? `${this.names[raw]} (${raw})` : raw;
    },
    /**
     * Asks the platform's own services for the names of the arguments that name a space
     * by its id or a user by username -- each read with the user's own rights, so a space
     * the user may not see stays a raw id.
     *
     * @returns {void}
     */
    resolveNames() {
      const args = this.parsedArguments || {};
      Object.keys(args).forEach(key => {
        const values = Array.isArray(args[key]) ? args[key] : [args[key]];
        values.filter(value => typeof value === 'string' || typeof value === 'number').forEach(value => {
          if (SPACE_ID_ARGUMENT.test(key) && /^\d+$/.test(String(value)) && this.$spaceService) {
            this.$spaceService.getSpaceById(value)
              .then(space => space?.displayName && this.$set(this.names, String(value), space.displayName))
              .catch(() => null);
          } else if (USERNAME_ARGUMENT.test(key) && this.$userService) {
            this.$userService.getUser(value)
              .then(user => user?.fullname && this.$set(this.names, String(value), user.fullname))
              .catch(() => null);
          }
        });
      });
    },
    /**
     * A refusal or failure in words: a message code of this add-on, else the tool's own
     * message as it gave it.
     *
     * @param {String} code - the code or the message
     * @returns {String} the text
     */
    reason(code) {
      const prefix = 'emailConnector.filters.proposal.';
      if (code?.startsWith(prefix)) {
        const key = `emailConnector.mailBox.automations.proposal.error.${code.substring(prefix.length)}`;
        return this.$te(key) ? this.$t(key) : this.$t('emailConnector.mailBox.automations.proposal.error.generic');
      }
      return code || this.$t('emailConnector.mailBox.automations.proposal.error.generic');
    },
    /**
     * Runs a decision and hands the proposal it answers to the panel.
     *
     * @param {String} kind - approve, reject or handover
     * @param {Function} request - the decision, answering the proposal
     * @returns {Promise<Object|null>} the proposal, or null when refused
     */
    decide(kind, request) {
      this.busy = kind;
      this.error = null;
      return request()
        .then(updated => {
          if (updated?.id) {
            this.$emit('updated', updated);
          }
          return updated;
        })
        .catch(error => {
          this.error = this.reason(error?.message);
          if (error?.status === 409) {
            this.$emit('refresh');
          }
          return null;
        })
        .finally(() => this.busy = null);
    },
    /**
     * Approves the call: the AI glue answers the platform's own approval of this very
     * call as the server runs it, as the user.
     *
     * @returns {Promise} resolved once run or refused
     */
    approve() {
      return this.decide('approve', () => this.actions.approve(this.proposal,
        () => this.$emailConnectorUserSettingService.approveProposal(this.proposal.id)));
    },
    /**
     * Rejects the call.
     *
     * @returns {Promise} resolved once rejected
     */
    reject() {
      return this.decide('reject', () => this.$emailConnectorUserSettingService.rejectProposal(this.proposal.id));
    },
    /**
     * Hands the call to the regular AI chat about this mail, then opens it with the
     * proposal written in the composer, not sent.
     *
     * @returns {Promise} resolved once the chat is asked to open
     */
    continueInChat() {
      return this.decide('handover', () => this.$emailConnectorUserSettingService.handOverProposal(this.proposal.id))
        .then(updated => updated && this.actions.continueInChat(updated, this.match, this.email, this.$t.bind(this)));
    },
  },
};
</script>
