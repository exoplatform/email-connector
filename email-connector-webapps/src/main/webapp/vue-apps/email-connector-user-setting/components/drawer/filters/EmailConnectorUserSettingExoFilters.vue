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
  <!-- The eXo group of the filters drawer (EXO-90654): the rules eXo runs itself after
       each sync of the user's own inbox, in the order they run, read from eXo -- never
       from the mail server, so a server that cannot be reached never hides them. A rule
       that also runs at delivery says so, and says when the server no longer holds its
       half: eXo never repairs it silently, the user re-publishes. -->
  <div>
    <div class="text-subtitle-1 text-color">
      {{ $t('UserSettings.emailConnector.filters.exo.title') }}
    </div>
    <div class="caption text-sub-title mb-4">
      {{ $t('UserSettings.emailConnector.filters.exo.description') }}
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
    <!-- Each rule as the platform's settings lists show an ordered row with actions (the
         activity stream settings' categories): its name over what it does, its badges
         as the version history's, then its switch and its icon buttons; the arrows that
         cannot move it are kept in place, invisible, so the columns stay aligned. -->
    <v-list
      v-else-if="filters && filters.length"
      class="pa-0"
      dense>
      <template v-for="(filter, index) in filters">
        <v-list-item
          :key="filter.id"
          class="pa-0"
          dense>
          <v-list-item-content class="me-2 pa-0">
            <v-list-item-title class="text-truncate">{{ filter.name }}</v-list-item-title>
            <v-list-item-subtitle class="text-wrap">{{ summary(filter) }}</v-list-item-subtitle>
            <v-list-item-subtitle class="text-wrap">
              {{ $t('UserSettings.emailConnector.filters.exo.matches', { 0: filter.matchCount || 0 }) }}
            </v-list-item-subtitle>
            <div v-if="filter.kind === 'HOP' || filter.lastError" class="d-flex flex-wrap mt-1">
              <v-chip
                v-if="filter.kind === 'HOP'"
                class="ma-0 me-1 px-2 text-subtitle"
                color="primary"
                x-small
                label
                outlined>
                {{ $t('UserSettings.emailConnector.filters.exo.badge.atDelivery') }}
              </v-chip>
              <v-chip
                v-if="filter.lastError"
                class="ma-0 me-1 px-2 text-subtitle"
                color="error"
                x-small
                label
                outlined>
                {{ $t('UserSettings.emailConnector.filters.exo.badge.error') }}
              </v-chip>
            </div>
            <div
              v-if="isOrphanHop(filter)"
              class="text-subtitle warning--text text-wrap mt-1">
              {{ $t('UserSettings.emailConnector.filters.exo.orphan') }}
              <v-btn
                :loading="saving"
                class="px-1"
                color="primary"
                text
                x-small
                @click="republish(filter)">
                {{ $t('UserSettings.emailConnector.filters.republish') }}
              </v-btn>
            </div>
          </v-list-item-content>
          <v-list-item-action class="mx-0 my-auto">
            <v-switch
              :input-value="filter.enabled"
              :disabled="saving"
              :aria-label="$t('UserSettings.emailConnector.filters.form.enabled')"
              :ripple="false"
              class="ma-0 width-fit-content"
              hide-details
              @change="toggle(filter, $event)" />
          </v-list-item-action>
          <v-list-item-action :class="index === 0 && 'invisible'" class="mx-0 my-auto">
            <v-btn
              :disabled="saving || index === 0"
              :title="$t('UserSettings.emailConnector.filters.exo.up')"
              :aria-label="$t('UserSettings.emailConnector.filters.exo.up')"
              icon
              @click="move(index, -1)">
              <v-icon size="18">fas fa-arrow-up</v-icon>
            </v-btn>
          </v-list-item-action>
          <v-list-item-action :class="index === filters.length - 1 && 'invisible'" class="mx-0 my-auto">
            <v-btn
              :disabled="saving || index === filters.length - 1"
              :title="$t('UserSettings.emailConnector.filters.exo.down')"
              :aria-label="$t('UserSettings.emailConnector.filters.exo.down')"
              icon
              @click="move(index, 1)">
              <v-icon size="18">fas fa-arrow-down</v-icon>
            </v-btn>
          </v-list-item-action>
          <v-list-item-action class="mx-0 my-auto">
            <v-btn
              :title="$t('UserSettings.emailConnector.filters.exo.log')"
              :aria-label="$t('UserSettings.emailConnector.filters.exo.log')"
              icon
              @click="toggleLog(filter)">
              <v-icon size="18">fas fa-history</v-icon>
            </v-btn>
          </v-list-item-action>
          <v-list-item-action class="mx-0 my-auto">
            <v-btn
              :title="$t('UserSettings.emailConnector.filters.edit')"
              :aria-label="$t('UserSettings.emailConnector.filters.edit')"
              icon
              @click="$emit('edit', filter)">
              <v-icon size="18">fas fa-edit</v-icon>
            </v-btn>
          </v-list-item-action>
          <v-list-item-action class="mx-0 my-auto">
            <v-btn
              :title="$t('UserSettings.emailConnector.filters.delete')"
              :aria-label="$t('UserSettings.emailConnector.filters.delete')"
              icon
              @click="askDelete(filter)">
              <v-icon size="18" color="error">fas fa-trash</v-icon>
            </v-btn>
          </v-list-item-action>
        </v-list-item>
        <v-list
          v-if="logOf === filter.id"
          :key="`log-${filter.id}`"
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
      {{ $t('UserSettings.emailConnector.filters.exo.empty') }}
    </div>
    <v-btn
      :disabled="saving || !filters"
      class="btn mt-2"
      @click="$emit('edit', null)">
      <v-icon size="14" class="me-2">fa-plus</v-icon>
      {{ $t('UserSettings.emailConnector.filters.exo.new') }}
    </v-btn>
    <exo-confirm-dialog
      ref="deleteDialog"
      :title="$t('UserSettings.emailConnector.filters.delete.title')"
      :message="$t('UserSettings.emailConnector.filters.exo.delete.message', { 0: deleting ? deleting.name : '' })"
      :ok-label="$t('UserSettings.emailConnector.filters.delete')"
      :cancel-label="$t('UserSettings.emailConnector.userSetting.drawer.cancel')"
      @ok="doDelete" />
  </div>
</template>

<script>
import { filtersMessage, notifyFiltersUpdated } from '../../../js/EmailConnectorFilters.js';

export default {
  props: {
    // The rules the server holds, hops included, as the server group read them; null
    // when the server group could not be read, which says nothing about the hops.
    serverRules: {
      type: Array,
      default: null,
    },
    // The folders a rule may file into: {key, label}.
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
  created() {
    this.read();
  },
  methods: {
    /**
     * Reads the eXo group.
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
          this.error = filtersMessage(this.$t.bind(this), error);
        })
        .finally(() => this.loading = false);
    },
    /**
     * Whether a rule's server half is missing from the server: a hop rule, switched on,
     * whose reference the server group does not hold.
     *
     * @param {Object} filter - the rule
     * @returns {Boolean} true when the server lost it
     */
    isOrphanHop(filter) {
      return filter.kind === 'HOP' && filter.enabled && !!this.serverRules
        && !this.serverRules.some(rule => rule.ref === filter.serverRuleRef);
    },
    /**
     * Runs a write, then reads the group again; a refusal is said in the user's words.
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
          return false;
        })
        .finally(() => {
          this.saving = false;
          this.read();
        });
    },
    /**
     * Switches a rule on or off; a rule with a server half publishes or removes it.
     *
     * @param {Object} filter - the rule
     * @param {Boolean} enabled - its new state
     * @returns {Promise<Boolean>} true when written
     */
    toggle(filter, enabled) {
      return this.write(() => this.$emailConnectorUserSettingService.saveExoFilter({ ...filter, enabled }, filter.id, {}));
    },
    /**
     * Moves a rule one place up or down.
     *
     * @param {Number} index - its place
     * @param {Number} delta - -1 up, 1 down
     * @returns {Promise<Boolean>} true when written
     */
    move(index, delta) {
      const ids = this.filters.map(filter => filter.id);
      const [moved] = ids.splice(index, 1);
      ids.splice(index + delta, 0, moved);
      return this.write(() => this.$emailConnectorUserSettingService.reorderExoFilters(ids));
    },
    /**
     * Publishes a rule's server half again.
     *
     * @param {Object} filter - the rule
     * @returns {Promise<Boolean>} true when written
     */
    republish(filter) {
      return this.write(() => this.$emailConnectorUserSettingService.republishExoFilter(filter.id, {}));
    },
    /**
     * Asks to confirm a deletion.
     *
     * @param {Object} filter - the rule
     * @returns {void}
     */
    askDelete(filter) {
      this.deleting = filter;
      this.$refs.deleteDialog.open();
    },
    /**
     * Deletes the confirmed rule.
     *
     * @returns {Promise<Boolean>} true when deleted
     */
    doDelete() {
      const filter = this.deleting;
      this.deleting = null;
      return filter ? this.write(() => this.$emailConnectorUserSettingService.deleteExoFilter(filter.id)) : Promise.resolve(false);
    },
    /**
     * Shows or hides what a rule did lately.
     *
     * @param {Object} filter - the rule
     * @returns {Promise<void>} resolved once read
     */
    toggleLog(filter) {
      if (this.logOf === filter.id) {
        this.logOf = null;
        return Promise.resolve();
      }
      this.logOf = filter.id;
      this.log = [];
      return this.$emailConnectorUserSettingService.getExoFilterLog(filter.id, 20)
        .then(log => this.log = log || [])
        .catch(error => this.error = filtersMessage(this.$t.bind(this), error));
    },
    /**
     * A rule in one line: what it does.
     *
     * @param {Object} filter - the rule
     * @returns {String} the localized line
     */
    summary(filter) {
      const parts = (filter.actions || []).map(action => {
        if (action.type === 'MOVE_TO_FOLDER') {
          const folder = this.folders.find(candidate => candidate.key === action.folderKey);
          return this.$t('UserSettings.emailConnector.filters.summary.move', { 0: folder?.label || action.folderKey });
        }
        return this.$t(`UserSettings.emailConnector.filters.exo.summary.${action.type}`);
      });
      if (filter.stopProcessing) {
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
