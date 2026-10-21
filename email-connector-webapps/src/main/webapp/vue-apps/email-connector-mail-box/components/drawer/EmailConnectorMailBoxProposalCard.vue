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
  <!-- Compact until opened: a pending card is one row -- the tool's title, the call's
       recipients, people and places under it, wrapped when long, the assistant's reason
       on one line, Approve, Reject and the Details chevron --,
       a decided one a single line of its status icon, title and status. The details --
       the tool's id and description, the arguments as a key/value list, the whole reason,
       the error, Continue in the chat -- open under the chevron. -->
  <!-- A failed card offers Fix it in the chat, on its folded row and in its details: the
       regular AI chat about the mail opens with the recorded call and its error as an
       unsent draft. Nothing is decided here: the proposal stays failed, and the chat's own
       approval applies to whatever it runs. -->
  <!-- A done card offers Open, on its folded row and in its details, when the tool's
       stored answer names a page of this eXo -- the task, note, event or activity the call
       created. Any other link, or none, offers nothing. -->
  <v-card
    class="px-2 py-1 mt-1"
    outlined
    flat>
    <div class="d-flex align-center text-start">
      <v-progress-circular
        v-if="proposal.status === 'RUNNING'"
        :size="12"
        :width="2"
        indeterminate
        class="me-2 flex-shrink-0 icon-default-color" />
      <v-icon
        v-else-if="statusIcon"
        :class="statusClass"
        size="12"
        class="me-2 flex-shrink-0">
        {{ statusIcon }}
      </v-icon>
      <div class="flex-grow-1 text-truncate" style="min-width: 0;">
        <div
          :title="title"
          class="text-body-2 font-weight-bold text-truncate">
          {{ title }}
        </div>
        <!-- Who and where the call reaches, before it can be approved: its recipients,
             people and places, the first few of each; the full list is in the details.
             An address outside the owner's domain shows in the warning colour, first
             of its group. The line wraps rather than cut: what it names is never hidden
             by the drawer's width. -->
        <div
          v-if="waiting && targetSegments.length"
          :title="targetsTitle"
          class="text-caption text-break"
          style="white-space: normal;">
          <span
            v-for="(segment, segmentIndex) in targetSegments"
            :key="`target-${segmentIndex}`"
            :class="segment.class"
            :title="segment.title">{{ segment.text }}</span>
        </div>
        <div
          v-if="waiting && proposal.rationale && !open"
          :title="rationaleLine"
          class="text-caption text-sub-title font-italic text-truncate">
          {{ rationaleLine }}
        </div>
      </div>
      <!-- A short status keeps its width beside Open; only a failure's reason is cut. -->
      <span
        v-if="statusLine"
        :class="[statusClass, proposal.status === 'FAILED' ? 'flex-shrink-1' : 'flex-shrink-0']"
        :title="statusLine"
        class="text-caption ms-2 text-truncate"
        style="max-width: 50%;">
        {{ statusLine }}
      </span>
      <a
        v-if="fixable && !open"
        :aria-disabled="!!busy"
        :class="busy ? 'text--disabled' : 'primary--text'"
        class="text-caption ms-2 flex-shrink-0"
        role="button"
        href="javascript:void(0);"
        @click.prevent="busy || fixInChat()"
        @keydown.enter.prevent="busy || fixInChat()">
        {{ $t('emailConnector.mailBox.automations.proposal.fix') }}
      </a>
      <a
        v-if="createdLink && !open"
        :href="createdLink"
        :title="$t('emailConnector.mailBox.automations.proposal.openTitle')"
        class="text-caption primary--text ms-2 flex-shrink-0">
        {{ $t('emailConnector.mailBox.automations.proposal.open') }}
      </a>
      <template v-if="waiting">
        <v-btn
          v-if="actions"
          :loading="busy === 'approve'"
          :disabled="!!busy"
          class="ms-2 px-3 flex-shrink-0"
          color="primary"
          elevation="0"
          small
          @click="approve">
          {{ $t('emailConnector.mailBox.automations.proposal.approve') }}
          <template #loader>
            <v-progress-circular
              size="16"
              width="2"
              indeterminate />
          </template>
        </v-btn>
        <v-btn
          :loading="busy === 'reject'"
          :disabled="!!busy"
          class="ms-1 px-3 flex-shrink-0"
          outlined
          small
          @click="reject">
          {{ $t('emailConnector.mailBox.automations.proposal.reject') }}
          <template #loader>
            <v-progress-circular
              size="16"
              width="2"
              indeterminate />
          </template>
        </v-btn>
      </template>
      <v-btn
        :aria-label="$t(open ? 'emailConnector.mailBox.automations.proposal.hideDetails' : 'emailConnector.mailBox.automations.proposal.showDetails')"
        :aria-expanded="String(open)"
        :title="$t(open ? 'emailConnector.mailBox.automations.proposal.hideDetails' : 'emailConnector.mailBox.automations.proposal.showDetails')"
        class="ms-1 flex-shrink-0"
        icon
        x-small
        @click="toggle">
        <v-icon size="12" class="icon-default-color">{{ open ? 'fas fa-chevron-up' : 'fas fa-chevron-down' }}</v-icon>
      </v-btn>
    </div>
    <div v-if="waiting" class="text-caption text-sub-title">{{ expiryLine }}</div>
    <v-expand-transition>
      <div v-show="open" class="pb-1 text-start">
        <div class="text-caption text-sub-title text-break">{{ proposal.toolName }}</div>
        <div
          v-if="proposal.toolDescription"
          ref="description"
          :style="descriptionStyle"
          class="text-caption text-sub-title text-break">
          {{ proposal.toolDescription }}
        </div>
        <a
          v-if="descriptionOpen || descriptionClamped"
          :aria-expanded="String(descriptionOpen)"
          class="text-caption primary--text"
          role="button"
          href="javascript:void(0);"
          @click.prevent.stop="toggleDescription"
          @keydown.enter.prevent.stop="toggleDescription">
          {{ $t(descriptionOpen ? 'emailConnector.mailBox.automations.proposal.less' : 'emailConnector.mailBox.automations.proposal.more') }}
        </a>
        <div
          v-if="argumentLines.length"
          class="mt-1"
          style="display: grid; grid-template-columns: fit-content(40%) minmax(0, 1fr); column-gap: 8px; row-gap: 2px;">
          <template v-for="argument in argumentLines">
            <div
              :key="`${argument.key}-key`"
              class="text-caption text-sub-title text-break">
              {{ argument.key }}
            </div>
            <div
              :key="`${argument.key}-value`"
              class="text-body-2 text-break"
              style="white-space: pre-wrap;">{{ argument.text }}</div>
          </template>
        </div>
        <div v-else class="text-caption text-sub-title mt-1">
          {{ $t('emailConnector.mailBox.automations.proposal.noArguments') }}
        </div>
        <div v-if="proposal.rationale" class="text-caption font-italic mt-1 text-break">
          {{ rationaleLine }}
        </div>
        <div
          v-if="proposal.status === 'FAILED'"
          class="text-caption error--text mt-1 text-break">
          {{ reason(proposal.lastError) }}
        </div>
        <a
          v-if="createdLink"
          :href="createdLink"
          :title="$t('emailConnector.mailBox.automations.proposal.openTitle')"
          class="d-inline-block text-caption primary--text mt-1">
          {{ $t('emailConnector.mailBox.automations.proposal.open') }}
        </a>
        <v-btn
          v-if="waiting && actions"
          :loading="busy === 'handover'"
          :disabled="!!busy"
          class="mt-1 px-1"
          color="primary"
          text
          x-small
          @click="continueInChat">
          {{ $t('emailConnector.mailBox.automations.proposal.continue') }}
        </v-btn>
        <v-btn
          v-if="fixable"
          :disabled="!!busy"
          class="mt-1 px-1"
          color="primary"
          text
          x-small
          @click="fixInChat">
          {{ $t('emailConnector.mailBox.automations.proposal.fix') }}
        </v-btn>
      </div>
    </v-expand-transition>
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
import { SHOWN_PER_GROUP, addressOf, domainOf, isExternalAddress, ownerAddress, projectName, proposalTargets } from '../../js/EmailConnectorProposalTargets.js';

/** An argument naming a space by its id, as the platform's tools name them. */
const SPACE_ID_ARGUMENT = /(^|_)space_?id$/i;

/** An argument naming one user, or several, by username. */
const USERNAME_ARGUMENT = /(^|_)(user_?name|assignee)s?$/i;

/** The icon of each decided status, on the card's folded row. */
const STATUS_ICONS = {
  DONE: 'fas fa-check-circle',
  FAILED: 'fas fa-exclamation-circle',
  REJECTED: 'fas fa-times-circle',
  EXPIRED: 'far fa-clock',
  HANDED_OVER: 'fas fa-comments',
};

/**
 * The fields of a tool's answer that link to what it created, in the order they are
 * tried on the answer's own object: the first one holding a link of this eXo wins. From
 * the answers stored on done proposals, which come as the MCP content list,
 * [{"text": "<the tool's JSON>"}]: create_personal_task
 * answers the task with "link" (/portal/dw/tasks/taskDetail/<id>), create_agenda_event
 * the event with "url" (/portal/dw/agenda?eventId=<id>); the other names cover the
 * platform's other models (a note's or an activity's permalink, a document's webUrl).
 * create_personal_note and send_kudos answer no link at all: their cards offer no Open.
 */
const LINK_FIELDS = ['link', 'permalink', 'url', 'webUrl', 'web_url', 'note_url', 'noteUrl', 'activity_url', 'activityUrl', 'href'];

/** The fields of a wrapper around a tool's answer: MCP content and text, or a result envelope. */
const WRAPPER_FIELDS = ['text', 'content', 'structuredContent', 'result', 'data'];

/** How many levels the answer is unwrapped for a link: each JSON text, list and wrapper counts one. */
const LINK_DEPTH = 6;

/** A day, in milliseconds. */
const DAY = 24 * 3600 * 1000;

export default {
  props: {
    // The proposal {id, toolName, toolTitle, toolDescription, arguments, rationale,
    // status, expiresDate, result, lastError}, as the panel's read gives it.
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
    // Whether the folded description is cut: measured, since how many characters fit in
    // two lines depends on the drawer's width.
    descriptionClamped: false,
    // Watches the description's size, to measure it again when the drawer is resized.
    descriptionObserver: null,
    // Whether the card's details are open: closed until the user opens them.
    open: false,
    // The names the platform gave the ids and usernames of the arguments, by raw value.
    names: {},
    // The names of the task projects the arguments name, by id.
    projectNames: {},
    // The domain of the mailbox owner's own address: null until read, or when unknown.
    ownerDomain: null,
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
     * The AI glue's opener of the chat on a failed call, with its call and its error as
     * the draft. Without it -- no AI add-on, or one that predates it --, a failed card
     * offers nothing more.
     *
     * @returns {Object|null} {fixInChat(proposal, match, email, t, error)}
     */
    fixer() {
      const extensions = extensionRegistry.loadExtensions(FILTER_PROPOSAL_EXTENSION.app, FILTER_PROPOSAL_EXTENSION.type) || [];
      return extensions.find(extension => extension?.fixInChat) || null;
    },
    /**
     * @returns {Boolean} whether the card offers Fix it in the chat: a failed call, with the AI glue
     */
    fixable() {
      return this.proposal.status === 'FAILED' && !!this.fixer;
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
     * @returns {String} the assistant's reason, labelled as its suggestion
     */
    rationaleLine() {
      return this.$t('emailConnector.mailBox.automations.proposal.why', { 0: this.proposal.rationale });
    },
    /**
     * The icon of a decided proposal's status, before its title on the folded row.
     *
     * @returns {String|null} the icon, or null while it waits for a decision
     */
    statusIcon() {
      return STATUS_ICONS[this.proposal.status] || null;
    },
    /**
     * The folded description's style: two lines, then an ellipsis -- a description that
     * fits is not changed by it.
     *
     * @returns {Object} the style binding
     */
    descriptionStyle() {
      if (this.descriptionOpen) {
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
     * The folded row's recipients, people and places: each group the call's arguments
     * name, its first values as shown -- the addresses outside the owner's organisation
     * first, so a long list never pushes them under "+N" --, and how many more the
     * details hold.
     *
     * @returns {Object[]} {group, shown: [{text, external}], more}
     */
    targetGroups() {
      return proposalTargets(this.parsedArguments).map(({ group, values }) => {
        const all = values.map(value => ({
          text: this.targetText(value),
          external: value.kind === 'address' && isExternalAddress(value.raw, this.ownerDomain),
        }));
        const ordered = all.filter(value => value.external).concat(all.filter(value => !value.external));
        return {
          group,
          shown: ordered.slice(0, SHOWN_PER_GROUP),
          more: Math.max(0, ordered.length - SHOWN_PER_GROUP),
        };
      });
    },
    /**
     * The folded row's targets line, as text segments: each group's label, its values
     * -- an address outside the owner's organisation in the warning colour, with its
     * tooltip and the same words for screen readers --, then "+N" when it has more.
     *
     * @returns {Object[]} {text, class, title}
     */
    targetSegments() {
      const external = this.$t('emailConnector.mailBox.automations.proposal.target.external');
      const segments = [];
      this.targetGroups.forEach((group, groupIndex) => {
        if (groupIndex) {
          segments.push({ text: ' · ', class: 'text-sub-title' });
        }
        segments.push({ text: `${this.$t(`emailConnector.mailBox.automations.proposal.target.${group.group}`)} `, class: 'text-sub-title' });
        group.shown.forEach((value, valueIndex) => {
          if (valueIndex) {
            segments.push({ text: ', ' });
          }
          if (value.external) {
            segments.push({ text: value.text, class: 'warning--text font-weight-bold', title: external });
            segments.push({ text: ` (${external})`, class: 'd-sr-only' });
          } else {
            segments.push({ text: value.text });
          }
        });
        if (group.more) {
          segments.push({ text: ` ${this.$t('emailConnector.mailBox.automations.proposal.target.more', { 0: group.more })}`, class: 'text-sub-title' });
        }
      });
      return segments;
    },
    /**
     * @returns {String} the targets line as plain text, for its tooltip
     */
    targetsTitle() {
      return this.targetSegments.filter(segment => segment.class !== 'd-sr-only').map(segment => segment.text).join('');
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
     * The page of this eXo the done call created, as the tool's stored answer names it.
     *
     * @returns {String|null} the link, or null when the call is not done or names none of this eXo
     */
    createdLink() {
      if (this.proposal.status !== 'DONE' || !this.proposal.result) {
        return null;
      }
      return this.findLink(this.proposal.result, LINK_DEPTH);
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
    this.readOwnerDomain();
  },
  mounted() {
    this.observeDescription();
  },
  beforeDestroy() {
    this.descriptionObserver?.disconnect();
  },
  methods: {
    /**
     * Opens or closes the card's details, and measures the description once shown.
     *
     * @returns {void}
     */
    toggle() {
      this.open = !this.open;
      this.measureDescription();
    },
    /**
     * Shows the whole description, or folds it back to two lines. Its click stays on the
     * link: it never opens or closes the card's details.
     *
     * @returns {void}
     */
    toggleDescription() {
      this.descriptionOpen = !this.descriptionOpen;
      this.measureDescription();
    },
    /**
     * Measures, once rendered, whether the folded description is cut -- the only case
     * where More has something to show. An open description keeps the last measure, so
     * Less stays offered.
     *
     * @returns {void}
     */
    measureDescription() {
      this.$nextTick(() => {
        const element = this.$refs.description;
        if (element && !this.descriptionOpen) {
          // Hidden details measure zero: they are measured again once shown.
          this.descriptionClamped = element.scrollHeight > element.clientHeight + 1;
        }
      });
    },
    /**
     * Measures the description again whenever its size changes: shown, hidden, or the
     * drawer resized. Where the browser has no ResizeObserver, opening the details
     * measures it.
     *
     * @returns {void}
     */
    observeDescription() {
      if (!this.$refs.description || typeof window.ResizeObserver !== 'function') {
        return;
      }
      this.descriptionObserver = new window.ResizeObserver(() => this.measureDescription());
      this.descriptionObserver.observe(this.$refs.description);
    },
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
     * by its id, a user by username or a task project by its id -- each read with the
     * user's own rights, so a space or project the user may not see stays a raw id.
     *
     * @returns {void}
     */
    resolveNames() {
      const args = this.parsedArguments || {};
      const spaces = new Set();
      const users = new Set();
      const projects = new Set();
      Object.keys(args).forEach(key => {
        const values = Array.isArray(args[key]) ? args[key] : [args[key]];
        values.filter(value => typeof value === 'string' || typeof value === 'number').forEach(value => {
          if (SPACE_ID_ARGUMENT.test(key)) {
            spaces.add(String(value));
          } else if (USERNAME_ARGUMENT.test(key)) {
            users.add(String(value));
          }
        });
      });
      proposalTargets(args).forEach(({ values }) => values.forEach(value => {
        if (value.kind === 'space') {
          spaces.add(value.raw);
        } else if (value.kind === 'user') {
          users.add(value.raw);
        } else if (value.kind === 'project') {
          projects.add(value.raw);
        }
      }));
      [...spaces].filter(value => /^\d+$/.test(value) && this.$spaceService).forEach(value => {
        this.$spaceService.getSpaceById(value)
          .then(space => space?.displayName && this.$set(this.names, value, space.displayName))
          .catch(() => null);
      });
      [...users].filter(() => this.$userService).forEach(value => {
        this.$userService.getUser(value)
          .then(user => user?.fullname && this.$set(this.names, value, user.fullname))
          .catch(() => null);
      });
      [...projects].filter(value => /^\d+$/.test(value)).forEach(value => {
        projectName(value).then(name => name && this.$set(this.projectNames, value, name));
      });
    },
    /**
     * Reads the domain of the mailbox owner's own address, against which the folded row
     * tells the recipients outside the organisation. Until read, or when it cannot be,
     * every address counts as outside.
     *
     * @returns {void}
     */
    readOwnerDomain() {
      ownerAddress(this.$emailConnectorCommonService)
        .then(address => this.ownerDomain = address ? domainOf(address) : null);
    },
    /**
     * A target as the folded row shows it: an address bare, a user, space or project by
     * the name the platform gave it, else as given.
     *
     * @param {Object} value - the target {kind, raw}
     * @returns {String} the text
     */
    targetText(value) {
      if (value.kind === 'address') {
        return addressOf(value.raw);
      }
      if (value.kind === 'project') {
        return this.projectNames[value.raw] || value.raw;
      }
      if (value.kind === 'user' || value.kind === 'space') {
        return this.names[value.raw] || value.raw;
      }
      return value.raw;
    },
    /**
     * The first link of this eXo a tool's answer carries: the answer is unwrapped first --
     * a JSON text, a list such as the MCP content list, a wrapper's text, content, result
     * or data --, then the object it carries is tried field by field in the order of
     * LINK_FIELDS. Nothing else nested is searched: an author's profile or a parent's page
     * is not what the call created.
     *
     * @param {*} value - the answer, or a part of it
     * @param {Number} depth - how many more levels may be unwrapped
     * @returns {String|null} the link, or null when none is found
     */
    findLink(value, depth) {
      if (depth < 0 || value === null || typeof value === 'undefined') {
        return null;
      }
      if (typeof value === 'string') {
        const parsed = this.parseJson(value);
        return parsed === null ? null : this.findLink(parsed, depth - 1);
      }
      if (Array.isArray(value)) {
        for (const item of value) {
          const link = this.findLink(item, depth - 1);
          if (link) {
            return link;
          }
        }
        return null;
      }
      if (typeof value !== 'object') {
        return null;
      }
      const field = LINK_FIELDS.find(name => typeof value[name] === 'string' && this.safeLink(value[name]));
      if (field) {
        return this.safeLink(value[field]);
      }
      for (const key of WRAPPER_FIELDS) {
        const link = this.findLink(value[key], depth - 1);
        if (link) {
          return link;
        }
      }
      return null;
    },
    /**
     * A text as JSON, when it is a JSON object or list.
     *
     * @param {String} text - the text
     * @returns {Object|Array|null} the parsed value, or null when the text is not one
     */
    parseJson(text) {
      const trimmed = text.trim();
      if (!trimmed.startsWith('{') && !trimmed.startsWith('[')) {
        return null;
      }
      try {
        return JSON.parse(trimmed);
      } catch (e) {
        return null;
      }
    },
    /**
     * A link the card may open in the same tab: a path starting with "/", or an absolute
     * URL, either one resolving to this page's origin. A protocol-relative or backslashed
     * path resolves to another origin and is refused, as is any other origin or scheme.
     *
     * @param {String} link - the link the tool gave
     * @returns {String|null} the resolved link, or null when refused
     */
    safeLink(link) {
      const value = link.trim();
      if (!value) {
        return null;
      }
      try {
        const url = value.startsWith('/') ? new URL(value, window.location.origin) : new URL(value);
        return url.origin === window.location.origin ? url.href : null;
      } catch (e) {
        return null;
      }
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
    /**
     * Opens the regular AI chat about this mail on the failed call: its tool, every
     * argument as recorded and the error the card shows, with a request to do it again,
     * in the composer, not sent. Nothing is asked of the server: the proposal stays
     * failed, and the chat's own approval applies to whatever it then runs.
     *
     * @returns {Promise} resolved once the chat is asked to open
     */
    fixInChat() {
      this.busy = 'fix';
      this.error = null;
      return Promise.resolve()
        .then(() => this.fixer.fixInChat(this.proposal, this.match, this.email, this.$t.bind(this), this.reason(this.proposal.lastError)))
        .catch(() => this.error = this.$t('emailConnector.mailBox.automations.proposal.error.generic'))
        .finally(() => this.busy = null);
    },
  },
};
</script>
