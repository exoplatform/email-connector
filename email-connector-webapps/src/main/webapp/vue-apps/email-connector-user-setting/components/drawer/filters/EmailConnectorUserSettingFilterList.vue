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
    <div v-if="serverFilters" class="text-subtitle mb-2">
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
    <!-- While eXo's filters are read, the drawer's own bar under its title says so. -->
    <v-list
      v-if="(filters || !loading) && items.length"
      class="pa-0"
      dense>
      <template v-for="item in items">
        <!-- The name takes up to two lines, cut after them with its full text in the
             tooltip; the switch and the icon buttons stay on the right, centered on its
             first line. What the filter does and its statistics run below, the whole
             row wide, so the buttons never squeeze them. -->
        <v-list-item
          :key="item.key"
          class="pa-0 align-start"
          dense>
          <div class="d-flex flex-column flex-grow-1 py-1 text-start" style="min-width: 0">
            <div class="d-flex align-start">
              <!-- The 36px buttons centered on the name's first 16px line. -->
              <div class="flex-grow-1 me-2" style="min-width: 0; padding-top: 10px">
                <v-list-item-title :title="item.name" class="text-wrap text-truncate-2">{{ item.name }}</v-list-item-title>
              </div>
              <div class="d-flex align-center flex-shrink-0">
                <v-switch
                  :input-value="item.enabled"
                  :disabled="saving"
                  :aria-label="$t('UserSettings.emailConnector.filters.form.enabled')"
                  :ripple="false"
                  class="ma-0 pa-0 width-fit-content"
                  hide-details
                  @change="toggle(item, $event)" />
                <!-- The server's order is the server's: only eXo's filters move, among
                     themselves. With fewer than two of them nothing moves, and the arrows
                     give their room back to the text. -->
                <v-btn
                  v-if="reorderable"
                  :class="!canMove(item, -1) && 'invisible'"
                  :disabled="saving || !canMove(item, -1)"
                  :title="$t('UserSettings.emailConnector.filters.exo.up')"
                  :aria-label="$t('UserSettings.emailConnector.filters.exo.up')"
                  icon
                  @click="move(item, -1)">
                  <v-icon size="18">fas fa-arrow-up</v-icon>
                </v-btn>
                <v-btn
                  v-if="reorderable"
                  :class="!canMove(item, 1) && 'invisible'"
                  :disabled="saving || !canMove(item, 1)"
                  :title="$t('UserSettings.emailConnector.filters.exo.down')"
                  :aria-label="$t('UserSettings.emailConnector.filters.exo.down')"
                  icon
                  @click="move(item, 1)">
                  <v-icon size="18">fas fa-arrow-down</v-icon>
                </v-btn>
                <v-btn
                  :class="item.kind === 'SERVER' && 'invisible'"
                  :disabled="item.kind === 'SERVER'"
                  :title="$t('UserSettings.emailConnector.filters.exo.log')"
                  :aria-label="$t('UserSettings.emailConnector.filters.exo.log')"
                  icon
                  @click="openLog(item)">
                  <v-icon size="18">fas fa-history</v-icon>
                </v-btn>
                <v-btn
                  :title="$t('UserSettings.emailConnector.filters.edit')"
                  :aria-label="$t('UserSettings.emailConnector.filters.edit')"
                  icon
                  @click="$emit('edit', item)">
                  <v-icon size="18">fas fa-edit</v-icon>
                </v-btn>
                <v-btn
                  :title="$t('UserSettings.emailConnector.filters.delete')"
                  :aria-label="$t('UserSettings.emailConnector.filters.delete')"
                  icon
                  @click="askDelete(item)">
                  <v-icon size="18" color="error">fas fa-trash</v-icon>
                </v-btn>
              </div>
            </div>
            <v-list-item-subtitle class="text-wrap">{{ summary(item) }}</v-list-item-subtitle>
            <!-- What the filter matched, and what the user decided on its suggestions
                 (EXO-90668) once it has any: a line each, one line in an expanded drawer. -->
            <v-list-item-subtitle
              v-for="(line, index) in statsLines(item)"
              :key="`${item.key}-stats-${index}`"
              class="text-wrap">
              {{ line }}
            </v-list-item-subtitle>
            <div v-if="item.lastError" class="d-flex flex-wrap mt-1">
              <v-chip
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
          </div>
        </v-list-item>
      </template>
    </v-list>
    <div v-else-if="filters" class="text-subtitle mb-2">
      {{ $t('UserSettings.emailConnector.filters.empty') }}
    </div>
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
import { OPEN_FILTER_LOG_DRAWER_EVENT, filtersMessage, notifyFiltersUpdated, serverItem } from '../../../js/EmailConnectorFilters.js';

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
    // Whether a filter may run on the mail server: the line on the order the server's and
    // eXo's filters apply in is shown only then.
    serverFilters: {
      type: Boolean,
      default: false,
    },
    // Whether the drawer is expanded: a filter's statistics then share one line.
    expanded: {
      type: Boolean,
      default: false,
    },
  },
  data: () => ({
    filters: null,
    // What the user decided on each rule's suggestions, by rule id.
    suggestionCounts: {},
    loading: false,
    saving: false,
    error: null,
    deleting: null,
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
     * Whether eXo's filters can be put in another order: only when there are two or more.
     *
     * @returns {Boolean} true when the arrows are shown
     */
    reorderable() {
      return this.exoItems.length > 1;
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
  watch: {
    /**
     * Tells the drawer whether eXo's filters are being read, for its bar.
     *
     * @param {Boolean} value whether they are
     * @returns {void}
     */
    loading(value) {
      this.$emit('loading', value);
    },
  },
  created() {
    this.read();
  },
  methods: {
    /**
     * Reads what the user decided on each rule's suggestions; the list shows without them
     * when they cannot be read.
     *
     * @returns {Promise<void>} resolved once read
     */
    readSuggestionCounts() {
      return this.$emailConnectorUserSettingService.getFilterSuggestionCounts()
        .then(counts => {
          this.suggestionCounts = Object.fromEntries((counts || [])
            .filter(count => count.approved || count.rejected || count.expired || count.handedOver || count.waiting)
            .map(count => [count.filterId, count]));
        })
        .catch(() => this.suggestionCounts = {});
    },
    /**
     * The line saying what the user decided on a rule's suggestions, those continued in
     * the chat included.
     *
     * @param {Object} counts - {approved, rejected, expired, handedOver, waiting}
     * @returns {String} the localized line
     */
    suggestionLine(counts) {
      return this.$t('UserSettings.emailConnector.filters.exo.suggestions', {
        0: counts.approved || 0,
        1: counts.rejected || 0,
        2: counts.expired || 0,
        3: counts.waiting || 0,
        4: counts.handedOver || 0,
      });
    },
    /**
     * A filter's statistics: what it matched, for a filter eXo runs, and what the user
     * decided on its suggestions, once it has any. A line each, joined into one when the
     * drawer is expanded and has the width for it.
     *
     * @param {Object} item - the filter
     * @returns {String[]} the localized lines
     */
    statsLines(item) {
      const lines = [];
      if (item.kind !== 'SERVER') {
        lines.push(this.$t('UserSettings.emailConnector.filters.exo.matches', { 0: item.matchCount || 0 }));
      }
      if (this.suggestionCounts[item.id]) {
        lines.push(this.suggestionLine(this.suggestionCounts[item.id]));
      }
      return this.expanded && lines.length > 1
        ? [this.$t('UserSettings.emailConnector.filters.exo.stats', { 0: lines[0], 1: lines[1] })]
        : lines;
    },
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
          this.readSuggestionCounts();
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
     * Opens what one of eXo's filters did lately in its own drawer, stacked over this one.
     *
     * @param {Object} item - the filter
     * @returns {void}
     */
    openLog(item) {
      this.$root.$emit(OPEN_FILTER_LOG_DRAWER_EVENT, { id: item.id, name: item.name });
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
        if (action.type === 'FORWARD') {
          return this.$t('UserSettings.emailConnector.filters.summary.forward', { 0: action.destination || '' });
        }
        return this.$t(`UserSettings.emailConnector.filters.exo.summary.${action.type}`);
      });
      if (item.stopProcessing) {
        parts.push(this.$t('UserSettings.emailConnector.filters.summary.stop'));
      }
      return parts.join(', ');
    },
  },
};
</script>
