<!--
Copyright (C) 2025 eXo Platform SAS.

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
<!--
The single row that opens the synchronization settings drawer — the mailbox
cache size, the sync period and the folder switches now live there instead of
as separate rows on this page, the same "row opens a drawer" shape as the
user-side folders row (EXO-89907).
-->
<template>
  <v-list-item
    dense
    class="px-0"
    @click="open">
    <v-list-item-content class="py-0">
      <v-list-item-title>
        {{ $t('emailConnector.admin.syncSettings.title') }}
      </v-list-item-title>
      <v-list-item-subtitle class="text-wrap text-light-color">
        {{ $t('emailConnector.admin.syncSettings.subtitle') }}
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action class="my-0">
      <v-icon size="16">fa-chevron-right</v-icon>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  methods: {
    /**
     * Asks the drawer to open. The drawer owns loading the current values —
     * this row only knows how to get there.
     *
     * @returns {void}
     */
    open() {
      this.$root.$emit('open-email-sync-settings-drawer');
    },
  },
};
</script>
