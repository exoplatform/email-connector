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
       point, which the enterprise glue fills --, and the tool calls it proposed, one card
       each, for the user to decide (EXO-90659). What a server rule did at delivery is
       never here: the server does not report it. Renders nothing when no rule matched,
       and nothing on a mail of a mailbox somebody shared with the user. -->
  <v-card
    v-if="matches.length"
    :style="panelStyle"
    class="pa-3 mt-2 mb-1"
    flat>
    <!-- Dressed as the mail's AI summary box right above it (the enterprise glue's
         EmailThreadAiSummary): the same light wash of the brand colour with an accent bar on
         the reading-start side, the same small bold primary title with its icon, and the
         same body text. Inline style because this webapp's webpack has no CSS loader. -->
    <!-- Undo, Undo all and Run again are inline text links, as the platform's profile
         writes its inline actions (social ProfileSingleValuedProperty): a text v-btn has a
         height and padding of its own and sits lower than the line it follows. -->
    <!-- The header folds the whole panel: open by default while a suggestion waits for the
         user, folded otherwise; the user's own choice, once made, is kept per browser. -->
    <div :class="collapsed ? '' : 'mb-1'" class="d-flex align-center">
      <v-icon size="14" class="me-2 primary--text">fas fa-filter</v-icon>
      <span class="text-caption font-weight-bold primary--text">{{ $t('emailConnector.mailBox.automations.title') }}</span>
      <span
        v-if="collapsed && waitingLine"
        class="text-caption text-sub-title ms-2 text-truncate">
        {{ waitingLine }}
      </span>
      <v-spacer />
      <a
        v-if="!collapsed && undoable.length > 1"
        :class="linkClass"
        :aria-disabled="busy"
        class="text-caption pa-0 font-weight-regular"
        role="button"
        href="javascript:void(0);"
        @click.prevent="busy || undoAll()"
        @keydown.enter.prevent="busy || undoAll()">
        {{ $t('emailConnector.mailBox.automations.undoAll') }}
      </a>
      <v-btn
        :aria-label="$t(collapsed ? 'emailConnector.mailBox.automations.expand' : 'emailConnector.mailBox.automations.collapse')"
        :aria-expanded="String(!collapsed)"
        :title="$t(collapsed ? 'emailConnector.mailBox.automations.expand' : 'emailConnector.mailBox.automations.collapse')"
        class="ms-1"
        icon
        x-small
        @click="toggleCollapsed">
        <v-icon size="12" class="primary--text">{{ collapsed ? 'fas fa-chevron-down' : 'fas fa-chevron-up' }}</v-icon>
      </v-btn>
    </div>
    <div
      v-if="error"
      class="text-body-2 error--text"
      role="alert">
      {{ error }}
    </div>
    <div
      v-for="(match, index) in matches"
      v-show="!collapsed"
      :key="match.id"
      :class="index && 'mt-2'">
      <div class="text-body-2 font-weight-bold text-truncate">
        {{ match.filterName || $t('emailConnector.mailBox.automations.deletedRule') }}
      </div>
      <div
        v-for="action in match.actions"
        :key="`${match.id}-${action.type}`"
        class="d-flex align-baseline text-body-2">
        <span :class="action.ok ? '' : 'error--text'">
          {{ actionLabel(action) }}
        </span>
        <a
          v-if="canUndo(action)"
          :class="linkClass"
          :aria-disabled="busy"
          class="ms-2 pa-0 font-weight-regular"
          role="button"
          href="javascript:void(0);"
          @click.prevent="busy || undo(match, action.type)"
          @keydown.enter.prevent="busy || undo(match, action.type)">
          {{ $t('emailConnector.mailBox.automations.undo') }}
        </a>
      </div>
      <div v-if="match.agentStatus && match.agentStatus !== 'NONE'" class="d-flex align-center text-body-2">
        <v-progress-circular
          v-if="!terminal(match)"
          :size="12"
          :width="2"
          indeterminate
          class="me-2 icon-default-color" />
        <span>{{ $t(`emailConnector.mailBox.automations.agent.${match.agentStatus}`) }}</span>
        <a
          v-if="terminal(match)"
          :class="linkClass"
          :aria-disabled="busy"
          class="ms-2 pa-0 font-weight-regular"
          role="button"
          href="javascript:void(0);"
          @click.prevent="busy || retry(match)"
          @keydown.enter.prevent="busy || retry(match)">
          {{ $t('emailConnector.mailBox.automations.runAgain') }}
        </a>
      </div>
      <template v-if="match.agentNameId">
        <component
          :is="extension.vueComponent"
          v-for="extension in outcomeExtensions"
          :key="`${match.id}-${extension.id}`"
          :match="match"
          :email="email" />
      </template>
      <!-- The tool calls the assistant proposed, one card each, oldest first (EXO-90659). -->
      <template v-if="match.proposals && match.proposals.length">
        <div class="text-caption font-weight-bold mt-1">
          {{ $t('emailConnector.mailBox.automations.proposal.heading') }}
        </div>
        <email-connector-mail-box-proposal-card
          v-for="proposal in match.proposals"
          :key="`${match.id}-proposal-${proposal.id}`"
          :proposal="proposal"
          :match="match"
          :email="email"
          @updated="replaceProposal(match, $event)"
          @refresh="read" />
      </template>
    </div>
  </v-card>
</template>

<script>
import { FILTER_OUTCOME_EXTENSION, filtersMessage } from '../../../email-connector-user-setting/js/EmailConnectorFilters.js';
import { isOwnMailboxMail } from '../../js/EmailConnectorMailFilters.js';

/** The assistant statuses after which it runs again only when asked. */
const TERMINAL = ['DONE', 'FAILED', 'SKIPPED_CAP', 'SKIPPED_DISABLED'];

/** The brand colour, with the skin's default when the portal publishes none -- as the AI summary box reads it. */
const PRIMARY_COLOR = 'var(--allPagesPrimaryColor, #3f8487)';

/** The browser's memory of the user's choice to fold or open the panel. */
const COLLAPSED_STORAGE_KEY = 'emailAutomationsCollapsed';

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
    // The user's own choice to fold the panel, as this browser remembers it: null until made.
    collapsedChoice: null,
  }),
  computed: {
    /**
     * The panel's inline style, the AI summary box's: the brand colour at 8 % as background,
     * and a 3px accent bar on the reading-start side with that side's corners squared.
     *
     * @returns {Object} the style binding of the card
     */
    panelStyle() {
      const side = this.$vuetify?.rtl ? 'Right' : 'Left';
      return {
        backgroundColor: `color-mix(in srgb, ${PRIMARY_COLOR} 8%, transparent)`,
        [`border${side}`]: `3px solid ${PRIMARY_COLOR}`,
        [`borderTop${side}Radius`]: 0,
        [`borderBottom${side}Radius`]: 0,
      };
    },
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
     * The colour of the panel's inline action links: the brand colour, muted while an
     * action is running, when a click does nothing.
     *
     * @returns {String} the colour class
     */
    linkClass() {
      return this.busy ? 'text--disabled' : 'primary--text';
    },
    /**
     * The proposals still waiting for the user's decision, over every match.
     *
     * @returns {Number} how many
     */
    waitingCount() {
      return this.matches.reduce((count, match) => count + (match.proposals || []).filter(proposal => proposal.status === 'PROPOSED').length, 0);
    },
    /**
     * Whether the panel is folded: as the user chose it last in this browser, else folded
     * unless a suggestion waits for the user.
     *
     * @returns {Boolean} true when folded
     */
    collapsed() {
      return this.collapsedChoice === null ? !this.waitingCount : this.collapsedChoice;
    },
    /**
     * The folded header's count of the suggestions waiting for the user.
     *
     * @returns {String} the count in words, or empty when none waits
     */
    waitingLine() {
      if (!this.waitingCount) {
        return '';
      }
      return this.waitingCount === 1
        ? this.$t('emailConnector.mailBox.automations.waitingOne')
        : this.$t('emailConnector.mailBox.automations.waiting', { 0: this.waitingCount });
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
    this.collapsedChoice = this.storedCollapsed();
  },
  methods: {
    /**
     * The user's last choice to fold or open the panel, as this browser keeps it.
     *
     * @returns {Boolean|null} the choice, or null when none was made or the storage is unavailable
     */
    storedCollapsed() {
      try {
        const stored = window.localStorage.getItem(COLLAPSED_STORAGE_KEY);
        return stored === null ? null : stored === 'true';
      } catch (e) {
        return null;
      }
    },
    /**
     * Folds or opens the panel, and keeps the choice in this browser when it can.
     *
     * @returns {void}
     */
    toggleCollapsed() {
      this.collapsedChoice = !this.collapsed;
      try {
        window.localStorage.setItem(COLLAPSED_STORAGE_KEY, String(this.collapsedChoice));
      } catch (e) {
        // The choice then lasts as long as the panel.
      }
    },
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
     * Puts a proposal's new state on its match, as the decision answered it.
     *
     * @param {Object} match - the match
     * @param {Object} proposal - the proposal
     * @returns {void}
     */
    replaceProposal(match, proposal) {
      this.matches = this.matches.map(candidate => (candidate.id === match.id
        ? { ...candidate, proposals: (candidate.proposals || []).map(item => (item.id === proposal.id ? proposal : item)) }
        : candidate));
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
