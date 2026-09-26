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
  <!-- The mail's Automations panel (EXO-90654): what the user's eXo rules did to this
       mail, each action with its Undo, the assistant's status and what it made of the
       mail -- the latter through the ('EmailFilter', 'email-filter-outcome') extension
       point, which the enterprise glue fills. What a server rule did at delivery is
       never here: the server does not report it. Renders nothing when no rule matched,
       and nothing on a mail of a mailbox somebody shared with the user. -->
  <div v-if="matches.length" class="my-2">
    <!-- Laid out as the platform lists a few items with their actions (the activity
         stream settings' categories): a section title with its icon, each rule as a
         dense list item, its actions as subtitles with their Undo as a small text
         button, errors in the error color. -->
    <div class="d-flex align-center">
      <v-icon size="16" class="icon-default-color me-2">fas fa-filter</v-icon>
      <span class="text-header">{{ $t('emailConnector.mailBox.automations.title') }}</span>
      <v-spacer />
      <v-btn
        v-if="undoable.length > 1"
        :loading="busy"
        color="primary"
        text
        small
        @click="undoAll">
        {{ $t('emailConnector.mailBox.automations.undoAll') }}
      </v-btn>
    </div>
    <div
      v-if="error"
      class="error--text mt-1"
      role="alert">
      {{ error }}
    </div>
    <v-list class="pa-0" dense>
      <v-list-item
        v-for="match in matches"
        :key="match.id"
        class="pa-0"
        dense>
        <v-list-item-content class="pa-0">
          <v-list-item-title class="text-truncate">
            {{ match.filterName || $t('emailConnector.mailBox.automations.deletedRule') }}
          </v-list-item-title>
          <v-list-item-subtitle
            v-for="action in match.actions"
            :key="`${match.id}-${action.type}`"
            class="d-flex align-center">
            <span :class="action.ok ? '' : 'error--text'">
              {{ actionLabel(action) }}
            </span>
            <v-btn
              v-if="canUndo(action)"
              :disabled="busy"
              class="ms-1"
              color="primary"
              text
              x-small
              @click="undo(match, action.type)">
              {{ $t('emailConnector.mailBox.automations.undo') }}
            </v-btn>
          </v-list-item-subtitle>
          <v-list-item-subtitle v-if="match.agentStatus && match.agentStatus !== 'NONE'" class="d-flex align-center">
            <span>{{ $t(`emailConnector.mailBox.automations.agent.${match.agentStatus}`) }}</span>
            <v-btn
              v-if="terminal(match)"
              :disabled="busy"
              class="ms-1"
              color="primary"
              text
              x-small
              @click="retry(match)">
              {{ $t('emailConnector.mailBox.automations.runAgain') }}
            </v-btn>
          </v-list-item-subtitle>
          <template v-if="match.agentNameId">
            <component
              :is="extension.vueComponent"
              v-for="extension in outcomeExtensions"
              :key="`${match.id}-${extension.id}`"
              :match="match"
              :email="email" />
          </template>
        </v-list-item-content>
      </v-list-item>
    </v-list>
  </div>
</template>

<script>
import { FILTER_OUTCOME_EXTENSION, filtersMessage } from '../../../email-connector-user-setting/js/EmailConnectorFilters.js';
import { isOwnMailboxMail } from '../../js/EmailConnectorMailFilters.js';

/** The assistant statuses after which it runs again only when asked. */
const TERMINAL = ['DONE', 'FAILED', 'SKIPPED_CAP', 'SKIPPED_DISABLED'];

/** The actions an Undo can take back. */
const UNDOABLE = ['MOVE_TO_FOLDER', 'ADD_CATEGORY', 'MARK_READ', 'STAR', 'MARK_JUNK', 'DELETE'];

export default {
  props: {
    // The mail the reader opened.
    email: {
      type: Object,
      default: null,
    },
  },
  data: () => ({
    matches: [],
    busy: false,
    error: null,
    outcomeExtensions: [],
  }),
  computed: {
    /**
     * Whether the panel may ask about this mail: a cached mail of the user's own
     * mailbox, never a draft.
     *
     * @returns {Boolean} true when it may
     */
    applicable() {
      return !!this.email?.id && !this.email.draftLocalId && isOwnMailboxMail(this.email);
    },
    /**
     * The actions an Undo can still take back, over every match.
     *
     * @returns {Object[]} the actions
     */
    undoable() {
      return this.matches.flatMap(match => (match.actions || []).filter(action => this.canUndo(action)));
    },
  },
  watch: {
    'email.id': {
      immediate: true,
      handler() {
        this.read();
      },
    },
  },
  created() {
    this.outcomeExtensions = extensionRegistry.loadExtensions(FILTER_OUTCOME_EXTENSION.app, FILTER_OUTCOME_EXTENSION.type) || [];
  },
  methods: {
    /**
     * Reads what the rules did to the mail.
     *
     * @returns {Promise<void>} resolved once read; a refusal shows nothing
     */
    read() {
      this.error = null;
      if (!this.applicable) {
        this.matches = [];
        return Promise.resolve();
      }
      const emailId = this.email.id;
      return this.$emailConnectorUserSettingService.getMailAutomations(emailId)
        .then(matches => {
          if (this.email?.id === emailId) {
            this.matches = matches || [];
          }
        })
        .catch(() => this.matches = []);
    },
    /**
     * Whether an action can be undone.
     *
     * @param {Object} action - the applied action
     * @returns {Boolean} true when it was applied, not undone, and is undoable
     */
    canUndo(action) {
      return action.ok && !action.undone && UNDOABLE.includes(action.type);
    },
    /**
     * Whether the assistant's run on a match is over.
     *
     * @param {Object} match - the match
     * @returns {Boolean} true for a terminal status
     */
    terminal(match) {
      return TERMINAL.includes(match.agentStatus);
    },
    /**
     * An action in words.
     *
     * @param {Object} action - the applied action
     * @returns {String} the localized line
     */
    actionLabel(action) {
      const label = this.$t(`emailConnector.mailBox.automations.action.${action.type}`);
      if (action.undone) {
        return this.$t('emailConnector.mailBox.automations.undone', { 0: label });
      }
      return action.ok ? label : this.$t('emailConnector.mailBox.automations.failed', { 0: label });
    },
    /**
     * Runs a request on a match, and puts its answer in place.
     *
     * @param {Function} request - the request, answering the match
     * @returns {Promise<void>} resolved once done, or once the refusal is shown
     */
    run(request) {
      this.busy = true;
      this.error = null;
      return request()
        .then(updated => {
          if (updated?.id) {
            this.matches = this.matches.map(match => (match.id === updated.id ? updated : match));
          }
          this.$root.$emit('email-automations-updated', this.email);
        })
        .catch(error => this.error = filtersMessage(this.$t.bind(this), error))
        .finally(() => this.busy = false);
    },
    /**
     * Undoes one action of a match.
     *
     * @param {Object} match - the match
     * @param {String} type - the action's type
     * @returns {Promise<void>} resolved once undone
     */
    undo(match, type) {
      return this.run(() => this.$emailConnectorUserSettingService.undoAutomation(match.id, type));
    },
    /**
     * Undoes every action of every match that can be.
     *
     * @returns {Promise<void>} resolved once undone
     */
    undoAll() {
      const pending = this.matches.filter(match => (match.actions || []).some(action => this.canUndo(action)));
      return pending.reduce((chain, match) => chain.then(() => this.run(() => this.$emailConnectorUserSettingService.undoAutomation(match.id))),
        Promise.resolve());
    },
    /**
     * Runs the assistant again on a match.
     *
     * @param {Object} match - the match
     * @returns {Promise<void>} resolved once queued
     */
    retry(match) {
      return this.run(() => this.$emailConnectorUserSettingService.retryAutomation(match.id));
    },
  },
};
</script>
