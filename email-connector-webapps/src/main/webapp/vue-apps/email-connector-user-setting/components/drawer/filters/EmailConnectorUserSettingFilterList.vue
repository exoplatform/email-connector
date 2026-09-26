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
  <!-- The one list of the user's mail filters (EXO-90652, EXO-90654), in the order they
       apply: first the filters the mail server runs as mail arrives, in the server's
       order, then the ones eXo runs after its sync, in the order the user sets. The
       server's are read live from the mail server by the drawer, with the server group
       -- eXo keeps no copy, and addresses them by the server's reference; eXo's are read
       here, from eXo, by id, so a server that cannot be reached never hides them. Each
       filter says where it runs with a badge. A filter the server marks for eXo says so,
       and says when the server no longer holds its mark: eXo never repairs it silently,
       the user re-publishes.
       Each filter is laid out as the platform's settings lists show an ordered row with
       actions (the activity stream settings' categories): its name over what it does,
       its badges as the version history's small label chips, then its switch and its
       icon buttons; the arrows that cannot move it are kept in place, invisible. -->
  <div>
    <div class="text-subtitle mb-2">
      {{ $t('UserSettings.emailConnector.filters.order') }}
    </div>
    <v-alert
      v-if="error"
      type="error"
      class="text-body-2 mb-4"
      dense
      text>
      {{ error }}
    </v-alert>
    <v-progress-linear
      v-if="loading && !filters"
      indeterminate
      color="primary"
      class="mb-4" />
    <v-list
      v-else-if="items.length"
      class="pa-0"
      dense>
      <template v-for="item in items">
        <v-list-item
          :key="item.key"
          class="pa-0"
          dense>
          <v-list-item-content class="me-2 pa-0">
            <v-list-item-title class="text-truncate">{{ item.name }}</v-list-item-title>
            <v-list-item-subtitle class="text-wrap">{{ summary(item) }}</v-list-item-subtitle>
            <v-list-item-subtitle v-if="item.kind !== 'SERVER'" class="text-wrap">
              {{ $t('UserSettings.emailConnector.filters.exo.matches', { 0: item.matchCount || 0 }) }}
            </v-list-item-subtitle>
            <div class="d-flex flex-wrap mt-1">
              <v-chip
                class="ma-0 me-1 px-2 text-subtitle"
                color="primary"
                x-small
                label
                outlined>
                {{ $t(item.kind === 'SERVER' ? 'UserSettings.emailConnector.filters.badge.server' : 'UserSettings.emailConnector.filters.badge.exo') }}
              </v-chip>
              <v-chip
                v-if="item.kind === 'HOP'"
                class="ma-0 me-1 px-2 text-subtitle"
                color="primary"
                x-small
                label
                outlined>
                {{ $t('UserSettings.emailConnector.filters.exo.badge.atDelivery') }}
              </v-chip>
              <v-chip
                v-if="item.lastError"
                class="ma-0 me-1 px-2 text-subtitle"
                color="error"
                x-small
                label
                outlined>
                {{ $t('UserSettings.emailConnector.filters.exo.badge.error') }}
              </v-chip>
            </div>
            <div
              v-if="isOrphanHop(item)"
              class="text-subtitle warning--text text-wrap mt-1">
              {{ $t('UserSettings.emailConnector.filters.exo.orphan') }}
              <v-btn
                :loading="saving"
                class="px-1"
                color="primary"
                text
                x-small
                @click="republish(item)">
                {{ $t('UserSettings.emailConnector.filters.republish') }}
              </v-btn>
            </div>
          </v-list-item-content>
          <v-list-item-action class="mx-0 my-auto">
            <v-switch
              :input-value="item.enabled"
              :disabled="saving"
              :aria-label="$t('UserSettings.emailConnector.filters.form.enabled')"
              :ripple="false"
              class="ma-0 width-fit-content"
              hide-details
              @change="toggle(item, $event)" />
          </v-list-item-action>
          <!-- The server's order is the server's: only eXo's filters move, among
               themselves. -->
          <v-list-item-action :class="!canMove(item, -1) && 'invisible'" class="mx-0 my-auto">
            <v-btn
              :disabled="saving || !canMove(item, -1)"
              :title="$t('UserSettings.emailConnector.filters.exo.up')"
              :aria-label="$t('UserSettings.emailConnector.filters.exo.up')"
              icon
              @click="move(item, -1)">
              <v-icon size="18">fas fa-arrow-up</v-icon>
            </v-btn>
          </v-list-item-action>
          <v-list-item-action :class="!canMove(item, 1) && 'invisible'" class="mx-0 my-auto">
            <v-btn
              :disabled="saving || !canMove(item, 1)"
              :title="$t('UserSettings.emailConnector.filters.exo.down')"
              :aria-label="$t('UserSettings.emailConnector.filters.exo.down')"
              icon
              @click="move(item, 1)">
              <v-icon size="18">fas fa-arrow-down</v-icon>
            </v-btn>
          </v-list-item-action>
          <v-list-item-action :class="item.kind === 'SERVER' && 'invisible'" class="mx-0 my-auto">
            <v-btn
              :disabled="item.kind === 'SERVER'"
              :title="$t('UserSettings.emailConnector.filters.exo.log')"
              :aria-label="$t('UserSettings.emailConnector.filters.exo.log')"
              icon
              @click="toggleLog(item)">
              <v-icon size="18">fas fa-history</v-icon>
            </v-btn>
          </v-list-item-action>
          <v-list-item-action class="mx-0 my-auto">
            <v-btn
              :title="$t('UserSettings.emailConnector.filters.edit')"
              :aria-label="$t('UserSettings.emailConnector.filters.edit')"
              icon
              @click="$emit('edit', item)">
              <v-icon size="18">fas fa-edit</v-icon>
            </v-btn>
          </v-list-item-action>
          <v-list-item-action class="mx-0 my-auto">
            <v-btn
              :title="$t('UserSettings.emailConnector.filters.delete')"
              :aria-label="$t('UserSettings.emailConnector.filters.delete')"
              icon
              @click="askDelete(item)">
              <v-icon size="18" color="error">fas fa-trash</v-icon>
            </v-btn>
          </v-list-item-action>
        </v-list-item>
        <v-list
          v-if="item.kind !== 'SERVER' && logOf === item.id"
          :key="`log-${item.key}`"
          class="pa-0 ps-4 pb-2"
          dense>
          <div v-if="!log.length" class="text-subtitle">{{ $t('UserSettings.emailConnector.filters.exo.log.empty') }}</div>
          <v-list-item
            v-for="match in log"
            :key="match.id"
            class="pa-0"
            dense>
            <v-list-item-content class="pa-0">
              <v-list-item-title class="text-truncate">
                {{ match.subject || $t('UserSettings.emailConnector.filters.exo.noSubject') }}
              </v-list-item-title>
              <v-list-item-subtitle class="text-truncate">
                {{ formatDate(match.matchedDate) }}
                <span v-if="match.agentStatus && match.agentStatus !== 'NONE'">
                  -- {{ $t(`UserSettings.emailConnector.filters.exo.agent.${match.agentStatus}`) }}
                </span>
              </v-list-item-subtitle>
            </v-list-item-content>
          </v-list-item>
        </v-list>
      </template>
    </v-list>
    <div v-else-if="filters" class="text-sub-title mb-2">
      {{ $t('UserSettings.emailConnector.filters.empty') }}
    </div>
    <v-btn
      :disabled="saving || !filters"
      class="btn mt-2"
      @click="$emit('edit', null)">
      <v-icon size="14" class="me-2">fas fa-plus</v-icon>
      {{ $t('UserSettings.emailConnector.filters.new') }}
    </v-btn>
    <exo-confirm-dialog
      ref="deleteDialog"
      :title="$t('UserSettings.emailConnector.filters.delete.title')"
      :message="deleteMessage"
      :ok-label="$t('UserSettings.emailConnector.filters.delete')"
      :cancel-label="$t('UserSettings.emailConnector.userSetting.drawer.cancel')"
      @ok="doDelete" />
  </div>
</template>

<script>
import { filtersMessage, notifyFiltersUpdated, serverItem } from '../../../js/EmailConnectorFilters.js';

/** The refusal of a deployment that switched eXo's filters off. */
const EXO_DISABLED = 'emailConnector.filters.disabled';

export default {
  props: {
    // The rules the server holds, hops included, as the server group read them; null
    // when the server group could not be read or holds no rules of eXo's.
    serverRules: {
      type: Array,
      default: null,
    },
    // The folders a filter may file into: {key, label}.
    folders: {
      type: Array,
      default: () => [],
    },
  },
  data: () => ({
    filters: null,
    loading: false,
    saving: false,
    error: null,
    deleting: null,
    logOf: null,
    log: [],
  }),
  computed: {
    /**
     * The server's filters, in the server's order: its rules but the hops, which are the
     * server half of eXo's filters.
     *
     * @returns {Object[]} the items, kind SERVER
     */
    serverItems() {
      return (this.serverRules || [])
        .filter(rule => !(rule.actions || []).every(action => action.type === 'TAG'))
        .map(rule => ({ ...serverItem(rule), key: `server-${rule.ref}`, raw: rule }));
    },
    /**
     * eXo's filters, in the order eXo applies them.
     *
     * @returns {Object[]} the items, kind EXO or HOP
     */
    exoItems() {
      return (this.filters || []).map(filter => ({ ...filter, key: `exo-${filter.id}` }));
    },
    /**
     * The one list, in the order the filters apply: the server's first.
     *
     * @returns {Object[]} the items
     */
    items() {
      return [...this.serverItems, ...this.exoItems];
    },
    /**
     * The deletion's question: a server filter goes from the server, an eXo one keeps its
     * history in each mail.
     *
     * @returns {String} the localized question
     */
    deleteMessage() {
      const name = this.deleting ? this.deleting.name : '';
      return this.deleting?.kind === 'SERVER'
        ? this.$t('UserSettings.emailConnector.filters.delete.message', { 0: name })
        : this.$t('UserSettings.emailConnector.filters.exo.delete.message', { 0: name });
    },
  },
  created() {
    this.read();
  },
  methods: {
    /**
     * Reads eXo's filters; a deployment that switched them off is told to the drawer,
     * not shown as an error.
     *
     * @returns {Promise<void>} resolved once read, or once the refusal is shown
     */
    read() {
      this.loading = true;
      return this.$emailConnectorUserSettingService.getExoFilters()
        .then(filters => {
          this.filters = filters || [];
          this.error = null;
        })
        .catch(error => {
          this.filters = this.filters || [];
          if (error?.message === EXO_DISABLED) {
            // Not an error: the deployment runs only the filters the mail server runs.
            this.$emit('exo-disabled');
          } else {
            this.error = filtersMessage(this.$t.bind(this), error);
          }
        })
        .finally(() => this.loading = false);
    },
    /**
     * Whether a filter the server marks for eXo lost its mark on the server: switched on,
     * and its reference not among the rules the server group read.
     *
     * @param {Object} item - the filter
     * @returns {Boolean} true when the server lost it
     */
    isOrphanHop(item) {
      return item.kind === 'HOP' && item.enabled && !!this.serverRules
        && !this.serverRules.some(rule => rule.ref === item.serverRuleRef);
    },
    /**
     * Whether a filter can move one place: only eXo's filters, among themselves.
     *
     * @param {Object} item - the filter
     * @param {Number} delta - -1 up, 1 down
     * @returns {Boolean} true when it can
     */
    canMove(item, delta) {
      if (item.kind === 'SERVER') {
        return false;
      }
      const index = this.exoItems.findIndex(candidate => candidate.id === item.id) + delta;
      return index >= 0 && index < this.exoItems.length;
    },
    /**
     * Runs a write, then reads the lists again; a refusal is said in the user's words.
     *
     * @param {Function} request - the write
     * @returns {Promise<Boolean>} true when written
     */
    write(request) {
      this.saving = true;
      this.error = null;
      return request()
        .then(() => {
          notifyFiltersUpdated();
          this.$emit('changed');
          return true;
        })
        .catch(error => {
          this.error = filtersMessage(this.$t.bind(this), error);
          this.$emit('changed');
          return false;
        })
        .finally(() => {
          this.saving = false;
          this.read();
        });
    },
    /**
     * Switches a filter on or off where it runs; one the server marks for eXo publishes
     * or removes its mark.
     *
     * @param {Object} item - the filter
     * @param {Boolean} enabled - its new state
     * @returns {Promise<Boolean>} true when written
     */
    toggle(item, enabled) {
      if (item.kind === 'SERVER') {
        return this.write(() => this.$emailConnectorUserSettingService.saveServerFilter({ ...item.raw, enabled }, item.ref, {}));
      }
      const filter = { ...item };
      delete filter.key;
      return this.write(() => this.$emailConnectorUserSettingService.saveExoFilter({ ...filter, enabled }, item.id, {}));
    },
    /**
     * Moves one of eXo's filters one place up or down, among eXo's filters.
     *
     * @param {Object} item - the filter
     * @param {Number} delta - -1 up, 1 down
     * @returns {Promise<Boolean>} true when written
     */
    move(item, delta) {
      const ids = this.exoItems.map(filter => filter.id);
      const index = ids.indexOf(item.id);
      const [moved] = ids.splice(index, 1);
      ids.splice(index + delta, 0, moved);
      return this.write(() => this.$emailConnectorUserSettingService.reorderExoFilters(ids));
    },
    /**
     * Publishes the mark of a filter the server marks for eXo again.
     *
     * @param {Object} item - the filter
     * @returns {Promise<Boolean>} true when written
     */
    republish(item) {
      return this.write(() => this.$emailConnectorUserSettingService.republishExoFilter(item.id, {}));
    },
    /**
     * Asks to confirm a deletion.
     *
     * @param {Object} item - the filter
     * @returns {void}
     */
    askDelete(item) {
      this.deleting = item;
      this.$refs.deleteDialog.open();
    },
    /**
     * Deletes the confirmed filter where it runs.
     *
     * @returns {Promise<Boolean>} true when deleted
     */
    doDelete() {
      const item = this.deleting;
      this.deleting = null;
      if (!item) {
        return Promise.resolve(false);
      }
      return item.kind === 'SERVER'
        ? this.write(() => this.$emailConnectorUserSettingService.deleteServerFilter(item.ref))
        : this.write(() => this.$emailConnectorUserSettingService.deleteExoFilter(item.id));
    },
    /**
     * Shows or hides what one of eXo's filters did lately.
     *
     * @param {Object} item - the filter
     * @returns {Promise<void>} resolved once read
     */
    toggleLog(item) {
      if (this.logOf === item.id) {
        this.logOf = null;
        return Promise.resolve();
      }
      this.logOf = item.id;
      this.log = [];
      return this.$emailConnectorUserSettingService.getExoFilterLog(item.id, 20)
        .then(log => this.log = log || [])
        .catch(error => this.error = filtersMessage(this.$t.bind(this), error));
    },
    /**
     * A filter in one line: what it does.
     *
     * @param {Object} item - the filter
     * @returns {String} the localized line
     */
    summary(item) {
      const parts = (item.actions || []).map(action => {
        if (action.type === 'MOVE_TO_FOLDER') {
          const folder = this.folders.find(candidate => candidate.key === action.folderKey);
          return this.$t('UserSettings.emailConnector.filters.summary.move', { 0: folder?.label || action.folderPath || action.folderKey });
        }
        return this.$t(`UserSettings.emailConnector.filters.exo.summary.${action.type}`);
      });
      if (item.stopProcessing) {
        parts.push(this.$t('UserSettings.emailConnector.filters.summary.stop'));
      }
      return parts.join(', ');
    },
    /**
     * A date, short, in the user's language.
     *
     * @param {Number} millis - the date
     * @returns {String} the date
     */
    formatDate(millis) {
      return millis ? new Date(millis).toLocaleString(eXo.env.portal.language) : '';
    },
  },
};
</script>
