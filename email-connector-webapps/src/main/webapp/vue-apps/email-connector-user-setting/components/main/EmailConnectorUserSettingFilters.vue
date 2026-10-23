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
  <!-- Mail filters (EXO-90652): the rules the user's mail server runs at delivery,
       authored here. Like the automatic reply's row, it only summarises what the server
       holds, read live, and opens the drawer mounted at the app's root. The rules eXo
       runs itself after each sync (EXO-90654) work on every server, so the drawer opens
       whatever the server answered, and the row counts them too. -->
  <v-list-item class="height-auto">
    <v-list-item-content>
      <v-list-item-title class="text-color">
        {{ $t('UserSettings.emailConnector.filters.title') }}
      </v-list-item-title>
      <v-list-item-subtitle class="text-wrap">
        {{ summary }}
      </v-list-item-subtitle>
      <v-list-item-subtitle
        v-if="attention"
        class="caption warning--text text-wrap">
        {{ $t('UserSettings.emailConnector.filters.row.attention') }}
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action>
      <v-btn
        icon
        :title="$t('UserSettings.emailConnector.filters.edit.tooltip')"
        @click="$root.$emit(OPEN_FILTERS_DRAWER_EVENT)">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
import { FILTERS_UPDATED_EVENT, OPEN_FILTERS_DRAWER_EVENT, filtersMessage } from '../../js/EmailConnectorFilters.js';

export default {
  data: () => ({
    OPEN_FILTERS_DRAWER_EVENT,
    group: null,
    loading: true,
    error: null,
    exoCount: 0,
  }),
  computed: {
    /**
     * Whether the mail server lets eXo manage its rules.
     *
     * @returns {Boolean} true when the engine can publish
     */
    supported() {
      return !!this.group?.capabilities?.supported;
    },
    /**
     * Whether the server's state needs the user: eXo's filters not running, or changed
     * outside eXo.
     *
     * @returns {Boolean} true for INACTIVE, MODIFIED or UNREADABLE
     */
    attention() {
      return ['INACTIVE', 'MODIFIED', 'UNREADABLE'].includes(this.group?.state);
    },
    /**
     * The row's one line: loading, none, or how many filters run, on the server and in
     * eXo; a server that lets eXo manage no rule leaves every filter to eXo.
     *
     * @returns {String} the localized line
     */
    summary() {
      if (this.loading && !this.group) {
        return this.$t('UserSettings.emailConnector.filters.loading');
      }
      const exo = this.exoCount ? ` ${this.$t('UserSettings.emailConnector.filters.exo.count', { 0: this.exoCount })}` : '';
      if (!this.group) {
        return (this.error || this.$t('UserSettings.emailConnector.filters.description')) + exo;
      }
      if (!this.supported) {
        return this.exoCount ? exo.trim() : this.$t('UserSettings.emailConnector.filters.none');
      }
      // A hop is the server half of an eXo rule, counted with the eXo rules.
      const count = (this.group.rules || [])
        .filter(rule => rule.enabled && !(rule.actions || []).every(action => action.type === 'TAG'))
        .length;
      if (!count && !this.exoCount) {
        return this.$t('UserSettings.emailConnector.filters.none');
      }
      return ((count ? this.$t('UserSettings.emailConnector.filters.count', { 0: count }) : '') + exo).trim();
    },
  },
  created() {
    this.read();
    document.addEventListener(FILTERS_UPDATED_EVENT, this.read);
  },
  beforeDestroy() {
    document.removeEventListener(FILTERS_UPDATED_EVENT, this.read);
  },
  methods: {
    /**
     * Reads the server group.
     *
     * @returns {Promise<void>} resolved once read, or once the refusal is shown
     */
    read() {
      this.loading = true;
      this.$emailConnectorUserSettingService.getExoFilters()
        .then(filters => this.exoCount = (filters || []).filter(filter => filter.enabled).length)
        .catch(() => this.exoCount = 0);
      return this.$emailConnectorUserSettingService.getServerFilters()
        .then(group => {
          this.group = group;
          this.error = null;
        })
        .catch(error => this.error = filtersMessage(this.$t.bind(this), error))
        .finally(() => this.loading = false);
    },
  },
};
</script>
