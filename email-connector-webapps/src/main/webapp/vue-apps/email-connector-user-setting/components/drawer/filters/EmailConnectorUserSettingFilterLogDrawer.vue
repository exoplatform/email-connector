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
  <!-- The mails one of eXo's filters matched (EXO-90654), as a SECOND-LEVEL drawer over the
       filters drawer -- the shape EmailConnectorUserSettingFolderNameDrawer takes over the
       folders drawer: mounted at the app root as a sibling of the filters drawer, which stays
       open underneath, and closed by go-back-button's arrow. Each mail is a dense list item:
       its subject over its date, what the filter did with an Undo where one is possible, the
       assistant's status when the filter has one. A mail still where the filter found it opens
       in the mailbox; one the filter moved has another UID there, so it stays plain text. -->
  <exo-drawer
    id="userSettingFilterLogDrawer"
    ref="filterLogDrawer"
    v-model="drawer"
    right
    go-back-button
    @closed="reset">
    <template #title>
      <span>{{ title }}</span>
    </template>
    <template v-if="drawer" #content>
      <div class="pa-4">
        <v-alert
          v-if="error"
          type="error"
          class="text-body-2 mb-4"
          dense
          text>
          {{ error }}
        </v-alert>
        <v-progress-linear
          v-if="loading && !log.length"
          indeterminate
          color="primary"
          class="mb-4" />
        <div v-else-if="!log.length" class="text-subtitle">
          {{ $t('UserSettings.emailConnector.filters.exo.log.empty') }}
        </div>
        <v-list
          v-else
          class="pa-0"
          dense>
          <v-list-item
            v-for="match in log"
            :key="match.id"
            :inactive="!openable(match)"
            class="pa-0"
            dense
            @click="openMail(match)">
            <v-list-item-content class="pa-0">
              <v-list-item-title
                :title="openable(match) ? $t('UserSettings.emailConnector.filters.exo.log.open') : null"
                class="text-truncate">
                {{ match.subject || $t('UserSettings.emailConnector.filters.exo.noSubject') }}
              </v-list-item-title>
              <v-list-item-subtitle class="text-truncate">
                {{ formatDate(match.matchedDate) }}
              </v-list-item-subtitle>
              <v-list-item-subtitle
                v-for="action in match.actions"
                :key="`${match.id}-${action.type}`"
                class="d-flex align-baseline">
                <span :class="action.ok ? '' : 'error--text'">{{ actionLabel(action) }}</span>
                <!-- An inline text link, as the mail's Automations panel writes its Undo:
                     a text v-btn sits lower than the line it follows. -->
                <a
                  v-if="canUndo(action)"
                  :class="busy ? 'text--disabled' : 'primary--text'"
                  :aria-disabled="busy"
                  class="ms-2 pa-0 font-weight-regular"
                  role="button"
                  href="javascript:void(0);"
                  @click.prevent.stop="busy || undo(match, action.type)"
                  @keydown.enter.prevent.stop="busy || undo(match, action.type)">
                  {{ $t('UserSettings.emailConnector.filters.exo.log.undo') }}
                </a>
              </v-list-item-subtitle>
              <v-list-item-subtitle v-if="match.agentStatus && match.agentStatus !== 'NONE'" class="text-truncate">
                {{ $t(`UserSettings.emailConnector.filters.exo.agent.${agentStatusKey(match)}`) }}
              </v-list-item-subtitle>
            </v-list-item-content>
          </v-list-item>
        </v-list>
        <div v-if="hasMore" class="d-flex justify-center mt-2">
          <v-btn
            :loading="loading"
            color="primary"
            text
            small
            @click="loadMore">
            {{ $t('UserSettings.emailConnector.filters.exo.log.loadMore') }}
          </v-btn>
        </div>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
import { CLOSE_FILTERS_DRAWER_EVENT, OPEN_FILTER_LOG_DRAWER_EVENT, filtersMessage } from '../../../js/EmailConnectorFilters.js';
import { formatDateString } from '../../../../email-connector-mail-box/js/EmailConnectorMailBoxService.js';

/** How many matches a page adds. */
const PAGE_SIZE = 20;

/** The most the log endpoint answers: it takes a limit and no offset. */
const MAX_LOG = 100;

/** The actions an Undo can take back. */
const UNDOABLE = ['MOVE_TO_FOLDER', 'ADD_CATEGORY', 'MARK_READ', 'STAR', 'MARK_JUNK', 'DELETE'];

/** The actions that give the mail another UID: once applied, the match's UID names nothing. */
const FILING = ['MOVE_TO_FOLDER', 'MARK_JUNK', 'DELETE'];

/** Why a capped match was skipped when too many mails already waited, not for the day's limit. */
const PENDING_LIMIT = 'emailConnector.filters.agent.pendingLimit';

export default {
  data: () => ({
    drawer: false,
    filter: null,
    log: [],
    limit: PAGE_SIZE,
    loading: false,
    busy: false,
    error: null,
  }),
  computed: {
    /**
     * The drawer's title: the filter's name.
     *
     * @returns {String} the localized title
     */
    title() {
      return this.$t('UserSettings.emailConnector.filters.exo.log.title', { 0: this.filter?.name || '' });
    },
    /**
     * Whether more matches may be there: the last read filled its page, and the endpoint's
     * bound is not reached.
     *
     * @returns {Boolean} true when a larger read may show more
     */
    hasMore() {
      return this.log.length >= this.limit && this.limit < MAX_LOG;
    },
  },
  created() {
    this.$root.$on(OPEN_FILTER_LOG_DRAWER_EVENT, this.open);
  },
  beforeDestroy() {
    this.$root.$off(OPEN_FILTER_LOG_DRAWER_EVENT, this.open);
  },
  methods: {
    /**
     * The key of the line saying where the assistant's run on a match stands: its status,
     * told apart for a match skipped because too many mails waited.
     *
     * @param {Object} match - the match
     * @returns {String} the key's last segment
     */
    agentStatusKey(match) {
      return match.agentStatus === 'SKIPPED_CAP' && match.lastError === PENDING_LIMIT ? 'SKIPPED_CAP_PENDING' : match.agentStatus;
    },
    /**
     * Opens the drawer on a filter's newest matches, over the filters drawer.
     *
     * @param {Object} filter - one of eXo's filters: {id, name}
     * @returns {Promise<void>} resolved once read
     */
    open(filter) {
      this.filter = filter;
      this.log = [];
      this.limit = PAGE_SIZE;
      this.error = null;
      this.drawer = true;
      return this.read();
    },
    /**
     * Forgets the filter once the drawer is closed.
     *
     * @returns {void}
     */
    reset() {
      this.filter = null;
      this.log = [];
      this.error = null;
    },
    /**
     * Reads the filter's newest matches, up to the current limit.
     *
     * @returns {Promise<void>} resolved once read, or once the refusal is shown
     */
    read() {
      const filterId = this.filter?.id;
      if (!filterId) {
        return Promise.resolve();
      }
      this.loading = true;
      return this.$emailConnectorUserSettingService.getExoFilterLog(filterId, this.limit)
        .then(log => {
          if (this.filter?.id === filterId) {
            this.log = log || [];
          }
        })
        .catch(error => this.error = filtersMessage(this.$t.bind(this), error))
        .finally(() => this.loading = false);
    },
    /**
     * Reads one page more. The endpoint has a limit and no offset, so the larger read
     * replaces the list.
     *
     * @returns {Promise<void>} resolved once read
     */
    loadMore() {
      this.limit = Math.min(this.limit + PAGE_SIZE, MAX_LOG);
      return this.read();
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
     * An action in words.
     *
     * @param {Object} action - the applied action
     * @returns {String} the localized line
     */
    actionLabel(action) {
      const label = this.$t(`UserSettings.emailConnector.filters.exo.log.action.${action.type}`);
      if (action.undone) {
        return this.$t('UserSettings.emailConnector.filters.exo.log.undone', { 0: label });
      }
      return action.ok ? label : this.$t('UserSettings.emailConnector.filters.exo.log.failed', { 0: label });
    },
    /**
     * Undoes one action of a match, and puts the match's new state in place.
     *
     * @param {Object} match - the match
     * @param {String} type - the action's type
     * @returns {Promise<void>} resolved once undone, or once the refusal is shown
     */
    undo(match, type) {
      this.busy = true;
      this.error = null;
      return this.$emailConnectorUserSettingService.undoAutomation(match.id, type)
        .then(updated => {
          if (updated?.id) {
            this.log = this.log.map(candidate => (candidate.id === updated.id ? updated : candidate));
          }
        })
        .catch(error => this.error = filtersMessage(this.$t.bind(this), error))
        .finally(() => this.busy = false);
    },
    /**
     * Whether a match still names its mail: it has a UID, and no filing action moved the
     * mail away from where the filter found it -- a move, even undone, leaves another UID.
     *
     * @param {Object} match - the match
     * @returns {Boolean} true when the mailbox can open it
     */
    openable(match) {
      return !!match?.mailRemoteId && !(match.actions || []).some(action => action.ok && FILING.includes(action.type));
    },
    /**
     * Opens a matched mail in the mailbox's own reader, as the platform's other mail links
     * do, and closes the filters drawers so the reader is what the user sees.
     *
     * @param {Object} match - the match
     * @returns {void}
     */
    openMail(match) {
      if (!this.openable(match)) {
        return;
      }
      this.drawer = false;
      this.$root.$emit(CLOSE_FILTERS_DRAWER_EVENT);
      window.require(['SHARED/emailConnectorQuickActionExtension'], () =>
        document.dispatchEvent(new CustomEvent('open-email-box-mail', {
          detail: {mailRemoteId: match.mailRemoteId, folder: match.folder || 'INBOX'},
        })));
    },
    /**
     * A date as the mailbox list shows it.
     *
     * @param {Number} millis - the date
     * @returns {String} the date, empty when there is none
     */
    formatDate(millis) {
      return millis ? formatDateString(millis, this.$t('UserSettings.emailConnector.filters.exo.log.yesterday')) : '';
    },
  },
};
</script>
