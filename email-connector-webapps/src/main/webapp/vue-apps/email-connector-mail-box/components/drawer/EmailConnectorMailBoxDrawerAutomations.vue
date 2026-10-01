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
       and nothing on a mail of a mailbox somebody shared with the user. Over a
       conversation, one panel covers every message the user received in it, grouped by
       message (EXO-90669). -->
  <v-card
    v-if="allMatches.length"
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
    <!-- One group per message of the conversation a rule touched (EXO-90669): the
         groups with a suggestion waiting for the user first, then the rest, newest
         first. The groups without one fold under a single line; so does the opened
         message's when some other group waits. A single mail is one group, without a
         header and never folded: the panel as it always was. -->
    <!-- One wrapper, with no display utility class: the fold line's own d-flex would
         override the inline display:none of a v-show on it. Kept mounted, not removed, so
         the cards keep their state and a decision in flight still lands while folded. -->
    <div v-show="!collapsed">
      <template v-for="row in rows">
        <div
          v-if="row.type === 'fold'"
          v-show="!collapsed"
          :key="row.key"
          :class="row.first ? '' : 'mt-2'"
          class="d-flex align-center">
          <span class="text-caption text-sub-title">
            {{ $t('emailConnector.mailBox.automations.earlierMessages', { 0: foldedGroups.length }) }}
          </span>
          <v-btn
            :aria-label="$t(groupsOpen ? 'emailConnector.mailBox.automations.hideEarlierMessages' : 'emailConnector.mailBox.automations.showEarlierMessages')"
            :aria-expanded="String(groupsOpen)"
            :title="$t(groupsOpen ? 'emailConnector.mailBox.automations.hideEarlierMessages' : 'emailConnector.mailBox.automations.showEarlierMessages')"
            class="ms-1"
            icon
            x-small
            @click="groupsOpen = !groupsOpen">
            <v-icon size="12" class="icon-default-color">{{ groupsOpen ? 'fas fa-chevron-up' : 'fas fa-chevron-down' }}</v-icon>
          </v-btn>
        </div>
        <div
          v-else
          v-show="!collapsed"
          :key="row.key"
          :class="row.first ? '' : 'mt-2'">
          <!-- Who wrote the message and when, one line; a click brings the message
               itself into view in the conversation below. -->
          <a
            v-if="threaded"
            :aria-label="$t('emailConnector.mailBox.automations.goToMessage', { 0: senderOf(row.group.email), 1: dateOf(row.group.email) })"
            :title="$t('emailConnector.mailBox.automations.goToMessage', { 0: senderOf(row.group.email), 1: dateOf(row.group.email) })"
            class="d-block text-caption text-sub-title text-truncate"
            role="button"
            href="javascript:void(0);"
            @click.prevent="$emit('go-to-message', row.group.email)"
            @keydown.enter.prevent="$emit('go-to-message', row.group.email)">
            {{ senderOf(row.group.email) }} · {{ dateOf(row.group.email) }}
          </a>
          <div
            v-for="(match, index) in row.group.matches"
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
                @click.prevent="busy || undo(row.group, match, action.type)"
                @keydown.enter.prevent="busy || undo(row.group, match, action.type)">
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
              <span>{{ $t(`emailConnector.mailBox.automations.agent.${agentStatusKey(match)}`) }}</span>
              <a
                v-if="terminal(match)"
                :class="linkClass"
                :aria-disabled="busy"
                class="ms-2 pa-0 font-weight-regular"
                role="button"
                href="javascript:void(0);"
                @click.prevent="busy || retry(row.group, match)"
                @keydown.enter.prevent="busy || retry(row.group, match)">
                {{ $t('emailConnector.mailBox.automations.runAgain') }}
              </a>
            </div>
            <template v-if="match.agentNameId">
              <component
                :is="extension.vueComponent"
                v-for="extension in outcomeExtensions"
                :key="`${match.id}-${extension.id}`"
                :match="match"
                :email="row.group.email" />
            </template>
            <!-- A run that was offered tools and suggested nothing says so under its note, so an
                 empty panel is never read as a failure to show the suggestions -- first what its
                 lookups looked for and did not find, as the server read them from the run's own
                 conversation (EXO-90659). Text only: the values are the model's arguments. -->
            <template v-if="suggestedNothing(match)">
              <div
                v-for="(line, lineIndex) in notFoundLines(match)"
                :key="`${match.id}-not-found-${lineIndex}`"
                class="text-caption text-sub-title mt-1">
                {{ line }}
              </div>
            </template>
            <div
              v-if="suggestedNothing(match)"
              class="text-caption text-sub-title mt-1">
              {{ $t('emailConnector.mailBox.automations.proposal.none') }}
            </div>
            <!-- The tool calls the assistant proposed, one card each, oldest first (EXO-90659).
                 Only the latest run's show; the earlier runs' fold under one line, closed until
                 opened. A call still waiting for the user always shows, whatever its run. -->
            <template v-if="match.proposals && match.proposals.length">
              <div class="text-caption font-weight-bold mt-1">
                {{ $t('emailConnector.mailBox.automations.proposal.heading') }}
              </div>
              <email-connector-mail-box-proposal-card
                v-for="proposal in proposalRuns[match.id].latest"
                :key="`${match.id}-proposal-${proposal.id}`"
                :proposal="proposal"
                :match="match"
                :email="row.group.email"
                @updated="replaceProposal(row.group, match, $event)"
                @refresh="read" />
              <template v-if="proposalRuns[match.id].earlier.length">
                <div class="d-flex align-center mt-1">
                  <span class="text-caption text-sub-title">
                    {{ $t('emailConnector.mailBox.automations.proposal.earlier', { 0: proposalRuns[match.id].earlier.length }) }}
                  </span>
                  <v-btn
                    :aria-label="$t(earlierOpen[match.id] ? 'emailConnector.mailBox.automations.proposal.hideEarlier' : 'emailConnector.mailBox.automations.proposal.showEarlier')"
                    :aria-expanded="String(!!earlierOpen[match.id])"
                    :title="$t(earlierOpen[match.id] ? 'emailConnector.mailBox.automations.proposal.hideEarlier' : 'emailConnector.mailBox.automations.proposal.showEarlier')"
                    class="ms-1"
                    icon
                    x-small
                    @click="toggleEarlier(match)">
                    <v-icon size="12" class="icon-default-color">{{ earlierOpen[match.id] ? 'fas fa-chevron-up' : 'fas fa-chevron-down' }}</v-icon>
                  </v-btn>
                </div>
                <v-expand-transition>
                  <div v-if="earlierOpen[match.id]">
                    <email-connector-mail-box-proposal-card
                      v-for="proposal in proposalRuns[match.id].earlier"
                      :key="`${match.id}-proposal-${proposal.id}`"
                      :proposal="proposal"
                      :match="match"
                      :email="row.group.email"
                      @updated="replaceProposal(row.group, match, $event)"
                      @refresh="read" />
                  </div>
                </v-expand-transition>
              </template>
            </template>
          </div>
        </div>
      </template>
    </div>
  </v-card>
</template>

<script>
import { FILTER_OUTCOME_EXTENSION, filtersMessage } from '../../../email-connector-user-setting/js/EmailConnectorFilters.js';
import { isOwnMailboxMail } from '../../js/EmailConnectorMailFilters.js';

/** The assistant statuses after which it runs again only when asked. */
const TERMINAL = ['DONE', 'FAILED', 'SKIPPED_CAP', 'SKIPPED_SPAM', 'SKIPPED_DISABLED'];

/** Why a capped match was skipped when too many mails already waited, not for the day's limit. */
const PENDING_LIMIT = 'emailConnector.filters.agent.pendingLimit';

/** Why a match was skipped as spam when its spam marks could not be read, again and again. */
const SPAM_UNCHECKED_LIMIT = 'emailConnector.filters.agent.spamUncheckedLimit';

/** The brand colour, with the skin's default when the portal publishes none -- as the AI summary box reads it. */
const PRIMARY_COLOR = 'var(--allPagesPrimaryColor, #3f8487)';

/** The browser's memory of the user's choice to fold or open the panel. */
const COLLAPSED_STORAGE_KEY = 'emailAutomationsCollapsed';

/** How far apart two calls without a run id may be recorded and still count as one run, in ms. */
const RUN_GAP = 60 * 1000;

/** The actions an Undo can take back. */
const UNDOABLE = ['MOVE_TO_FOLDER', 'ADD_CATEGORY', 'MARK_READ', 'STAR', 'MARK_JUNK', 'DELETE'];

/** How many messages of a conversation are asked about at once. */
const READ_CONCURRENCY = 3;

/** The folders whose mails the user did not receive: no rule runs on them. */
const NOT_RECEIVED = ['SENT', 'DRAFTS', 'SCHEDULED'];

export default {
  props: {
    // The mail the reader opened.
    email: {
      type: Object,
      default: null,
    },
    // The messages of the conversation the mail belongs to, drafts aside; null for a
    // mail read alone (EXO-90669).
    messages: {
      type: Array,
      default: null,
    },
  },
  data: () => ({
    // One group per mail asked about: {key, email, matches}.
    groups: [],
    // Whether the groups folded under "Earlier in this conversation" are shown.
    groupsOpen: false,
    busy: false,
    error: null,
    outcomeExtensions: [],
    // The user's own choice to fold the panel, as this browser remembers it: null until made.
    collapsedChoice: null,
    // Which matches show their earlier runs' suggestions, by match id: none until opened.
    earlierOpen: {},
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
     * Whether the panel covers a conversation rather than one mail.
     *
     * @returns {Boolean} true over a conversation of several messages
     */
    threaded() {
      return Array.isArray(this.messages) && this.messages.length > 1;
    },
    /**
     * The mails the panel asks about: over a conversation, each message the user
     * received in it, of their own mailbox -- a sent reply and a draft have no rule run
     * on them --; else the opened mail, when it may be asked about.
     *
     * @returns {Object[]} the mails
     */
    sources() {
      if (!this.threaded) {
        return this.applicable ? [this.email] : [];
      }
      return this.messages.filter(message => !!message?.id && !message.draftLocalId && isOwnMailboxMail(message)
        && !NOT_RECEIVED.includes(message.folder));
    },
    /**
     * What the panel asks about, as one key: a read is made again only when it changes.
     *
     * @returns {String} the mails' ids
     */
    sourceKey() {
      return this.sources.map(source => source.id).join(',');
    },
    /**
     * The matches of every group.
     *
     * @returns {Object[]} the matches
     */
    allMatches() {
      return this.groups.flatMap(group => group.matches);
    },
    /**
     * The groups with something to show, in the panel's order: those with a suggestion
     * waiting for the user first, then the others, each side newest first.
     *
     * @returns {Object[]} the groups
     */
    orderedGroups() {
      return this.groups
        .filter(group => group.matches.length)
        .sort((first, second) => (this.waitingOf(second) > 0) - (this.waitingOf(first) > 0)
          || this.receivedOf(second) - this.receivedOf(first));
    },
    /**
     * Whether a group of the conversation has a suggestion waiting for the user.
     *
     * @returns {Boolean} true when one has
     */
    anyGroupWaiting() {
      return this.orderedGroups.some(group => this.waitingOf(group) > 0);
    },
    /**
     * Whether a group shows open: always for a single mail; over a conversation, a group
     * with a suggestion waiting, and -- when none waits anywhere -- the opened message's.
     *
     * @returns {Function} the test, given a group
     */
    isGroupOpen() {
      const openedKey = this.email?.id ? String(this.email.id) : null;
      return group => !this.threaded || this.waitingOf(group) > 0 || (!this.anyGroupWaiting && group.key === openedKey);
    },
    /**
     * The groups folded under "Earlier in this conversation".
     *
     * @returns {Object[]} the groups
     */
    foldedGroups() {
      return this.orderedGroups.filter(group => !this.isGroupOpen(group));
    },
    /**
     * The groups on screen once the panel is open: the open ones, and the folded ones
     * while the user shows them.
     *
     * @returns {Object[]} the groups
     */
    visibleGroups() {
      const open = this.orderedGroups.filter(group => this.isGroupOpen(group));
      return this.groupsOpen ? open.concat(this.foldedGroups) : open;
    },
    /**
     * What the panel's body renders, in order: the open groups, the fold line when some
     * groups are folded, and the folded groups while shown.
     *
     * @returns {Object[]} the rows: {type: 'group', key, group, first} or {type: 'fold', key, first}
     */
    rows() {
      const open = this.orderedGroups.filter(group => this.isGroupOpen(group));
      const rows = open.map(group => ({ type: 'group', key: `group-${group.key}`, group }));
      if (this.foldedGroups.length) {
        rows.push({ type: 'fold', key: 'fold' });
        if (this.groupsOpen) {
          this.foldedGroups.forEach(group => rows.push({ type: 'group', key: `group-${group.key}`, group }));
        }
      }
      return rows.map((row, index) => ({ ...row, first: index === 0 }));
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
      return this.groups.reduce((count, group) => count + this.waitingOf(group), 0);
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
     * Each match's proposals, split between its latest run's and the earlier runs'.
     *
     * @returns {Object} {latest, earlier} by match id
     */
    proposalRuns() {
      return this.allMatches.reduce((runs, match) => ({ ...runs, [match.id]: this.splitRuns(match.proposals || []) }), {});
    },
    /**
     * The actions an Undo can still take back, over every match on screen: Undo all
     * never reaches a message whose group is folded.
     *
     * @returns {Object[]} the actions
     */
    undoable() {
      return this.visibleGroups.flatMap(group => group.matches)
        .flatMap(match => (match.actions || []).filter(action => this.canUndo(action)));
    },
  },
  watch: {
    sourceKey: {
      immediate: true,
      handler() {
        this.read();
      },
    },
    'email.id'() {
      this.groupsOpen = false;
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
     * Reads what the rules did to each mail the panel asks about: one request per mail,
     * {@link READ_CONCURRENCY} at a time. A mail whose read is refused shows nothing; an
     * answer that comes back after the panel moved on to other mails is dropped.
     *
     * @returns {Promise<void>} resolved once every mail is read
     */
    read() {
      this.error = null;
      const key = this.sourceKey;
      const sources = this.sources;
      if (!sources.length) {
        this.groups = [];
        return Promise.resolve();
      }
      const groups = sources.map(source => ({ key: String(source.id), email: source, matches: [] }));
      let next = 0;
      const worker = () => {
        if (next >= groups.length) {
          return Promise.resolve();
        }
        const group = groups[next++];
        return this.$emailConnectorUserSettingService.getMailAutomations(group.email.id)
          .then(matches => group.matches = matches || [])
          .catch(() => group.matches = [])
          .then(worker);
      };
      const workers = Array.from({ length: Math.min(READ_CONCURRENCY, groups.length) }, worker);
      return Promise.all(workers).then(() => {
        if (this.sourceKey === key) {
          this.groups = groups;
        }
      });
    },
    /**
     * How many suggestions of a group wait for the user.
     *
     * @param {Object} group - the group
     * @returns {Number} how many
     */
    waitingOf(group) {
      return group.matches.reduce((count, match) => count + (match.proposals || []).filter(proposal => proposal.status === 'PROPOSED').length, 0);
    },
    /**
     * When a group's mail was received.
     *
     * @param {Object} group - the group
     * @returns {Number} the time, in ms; 0 when unknown
     */
    receivedOf(group) {
      const time = new Date(group.email?.receivedDate).getTime();
      return Number.isNaN(time) ? 0 : time;
    },
    /**
     * Who wrote a mail, as its group's header names them.
     *
     * @param {Object} email - the mail
     * @returns {String} the sender's name, else their address
     */
    senderOf(email) {
      return email?.sender?.name || email?.sender?.address || '';
    },
    /**
     * When a mail was received, as the conversation writes it.
     *
     * @param {Object} email - the mail
     * @returns {String} the date
     */
    dateOf(email) {
      return this.$emailConnectorMailBoxService.formatDateString(email?.receivedDate, this.$t('emailConnector.mailBox.list.drawer.yesterday'));
    },
    /**
     * Puts a match's new state in its group.
     *
     * @param {Object} group - the group
     * @param {Function} update - given a match of the group, answers it as it now stands
     * @returns {void}
     */
    updateGroup(group, update) {
      this.groups = this.groups.map(candidate => (candidate.key === group.key
        ? { ...candidate, matches: candidate.matches.map(update) }
        : candidate));
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
     * The key of the line saying where the assistant's run on a match stands: its status,
     * told apart for a match skipped because too many mails waited -- the daily limit is
     * the status's own line -- and for one skipped because its spam marks could never be
     * read, which is not known to be spam.
     *
     * @param {Object} match - the match
     * @returns {String} the key's last segment
     */
    agentStatusKey(match) {
      if (match.agentStatus === 'SKIPPED_CAP' && match.lastError === PENDING_LIMIT) {
        return 'SKIPPED_CAP_PENDING';
      }
      if (match.agentStatus === 'SKIPPED_SPAM' && match.lastError === SPAM_UNCHECKED_LIMIT) {
        return 'SKIPPED_SPAM_UNCHECKED';
      }
      return match.agentStatus;
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
     * Whether the match's last run was offered tools and suggested nothing: its outcome
     * lists the run's proposals -- a key only a run with tools writes -- and that list is
     * empty. A run without tools, one still going, or an outcome that is not JSON says
     * nothing.
     *
     * @param {Object} match - the match
     * @returns {Boolean} true when the run suggested nothing
     */
    suggestedNothing(match) {
      if (match.agentStatus !== 'DONE' || !match.agentOutput) {
        return false;
      }
      try {
        const output = JSON.parse(match.agentOutput);
        return Array.isArray(output?.proposals) && !output.proposals.length;
      } catch (e) {
        return false;
      }
    },
    /**
     * The lines saying what the match's last run looked for and did not find: one per
     * `notFound` entry of its outcome, with the value looked for when there is one.
     * An outcome without it, or that is not JSON, gives none.
     *
     * @param {Object} match - the match
     * @returns {Array<String>} the localized lines, plain text
     */
    notFoundLines(match) {
      let entries;
      try {
        entries = JSON.parse(match.agentOutput)?.notFound;
      } catch (e) {
        return [];
      }
      if (!Array.isArray(entries)) {
        return [];
      }
      return entries
        .filter(entry => entry && (entry.title || entry.tool))
        .map(entry => {
          const title = String(entry.title || entry.tool);
          return entry.looked_for
            ? this.$t('emailConnector.mailBox.automations.proposal.notFound', { 0: String(entry.looked_for), 1: title })
            : this.$t('emailConnector.mailBox.automations.proposal.notFoundNoValue', { 0: title });
        });
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
     * Runs a request on a match of a group, and puts its answer in place.
     *
     * @param {Object} group - the group of the match
     * @param {Function} request - the request, answering the match
     * @returns {Promise<void>} resolved once done, or once the refusal is shown
     */
    run(group, request) {
      this.busy = true;
      this.error = null;
      return request()
        .then(updated => {
          if (updated?.id) {
            this.updateGroup(group, match => (match.id === updated.id ? updated : match));
          }
          this.$root.$emit('email-automations-updated', group.email);
        })
        .catch(error => this.error = filtersMessage(this.$t.bind(this), error))
        .finally(() => this.busy = false);
    },
    /**
     * Splits proposals by the assistant run that recorded them: its conversation, else --
     * for a call recorded without one -- the calls recorded within a minute of each
     * other. The latest run is the one recorded last. Its calls, and any call still
     * waiting for the user whatever its run, are the latest; the rest are earlier. Each
     * side keeps the proposals' order.
     *
     * @param {Object[]} proposals - the match's proposals
     * @returns {Object} {latest, earlier}; earlier is empty when there is a single run
     */
    splitRuns(proposals) {
      const runOf = {};
      const lastRecorded = {};
      let cluster = null;
      let clusterEnd = 0;
      [...proposals].sort((a, b) => (a.createdDate || 0) - (b.createdDate || 0)).forEach(proposal => {
        const recorded = proposal.createdDate || 0;
        let run = proposal.conversationId ? `conversation-${proposal.conversationId}` : null;
        if (!run) {
          if (cluster === null || recorded - clusterEnd > RUN_GAP) {
            cluster = `recorded-${recorded}`;
          }
          clusterEnd = recorded;
          run = cluster;
        }
        runOf[proposal.id] = run;
        lastRecorded[run] = Math.max(lastRecorded[run] || 0, recorded);
      });
      const runs = Object.keys(lastRecorded);
      if (runs.length < 2) {
        return { latest: proposals, earlier: [] };
      }
      const latestRun = runs.reduce((latest, run) => (lastRecorded[run] > lastRecorded[latest] ? run : latest));
      return {
        latest: proposals.filter(proposal => runOf[proposal.id] === latestRun || proposal.status === 'PROPOSED'),
        earlier: proposals.filter(proposal => runOf[proposal.id] !== latestRun && proposal.status !== 'PROPOSED'),
      };
    },
    /**
     * Shows or folds a match's earlier runs' suggestions.
     *
     * @param {Object} match - the match
     * @returns {void}
     */
    toggleEarlier(match) {
      this.$set(this.earlierOpen, match.id, !this.earlierOpen[match.id]);
    },
    /**
     * Puts a proposal's new state on its match, as the decision answered it, and tells
     * the mailbox, whose list marks the mails with a suggestion waiting.
     *
     * @param {Object} group - the group of the match
     * @param {Object} match - the match
     * @param {Object} proposal - the proposal
     * @returns {void}
     */
    replaceProposal(group, match, proposal) {
      this.updateGroup(group, candidate => (candidate.id === match.id
        ? { ...candidate, proposals: (candidate.proposals || []).map(item => (item.id === proposal.id ? proposal : item)) }
        : candidate));
      this.$root.$emit('email-automations-updated', group.email);
    },
    /**
     * Undoes one action of a match.
     *
     * @param {Object} group - the group of the match
     * @param {Object} match - the match
     * @param {String} type - the action's type
     * @returns {Promise<void>} resolved once undone
     */
    undo(group, match, type) {
      return this.run(group, () => this.$emailConnectorUserSettingService.undoAutomation(match.id, type));
    },
    /**
     * Undoes every action that can be, of every match on screen.
     *
     * @returns {Promise<void>} resolved once undone
     */
    undoAll() {
      const pending = this.visibleGroups.flatMap(group => group.matches
        .filter(match => (match.actions || []).some(action => this.canUndo(action)))
        .map(match => ({ group, match })));
      return pending.reduce((chain, { group, match }) => chain.then(() => this.run(group, () => this.$emailConnectorUserSettingService.undoAutomation(match.id))),
        Promise.resolve());
    },
    /**
     * Runs the assistant again on a match.
     *
     * @param {Object} group - the group of the match
     * @param {Object} match - the match
     * @returns {Promise<void>} resolved once queued
     */
    retry(group, match) {
      return this.run(group, () => this.$emailConnectorUserSettingService.retryAutomation(match.id));
    },
  },
};
</script>
