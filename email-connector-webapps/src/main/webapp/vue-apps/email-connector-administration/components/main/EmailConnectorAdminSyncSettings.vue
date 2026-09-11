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
user-side folders row (EXO-89907). Copies the title/subtitle/spacer/icon-button
shape of the Calendar synchronization row (agenda's
AgendaConnectorContemporaryEvents.vue), including the icon button as the
affordance that opens the drawer.
-->
<template>
  <div class="d-flex">
    <div class="d-flex flex-column width-full">
      <div class="d-flex">
        <div class="my-auto text-no-wrap text-truncate font-weight-bold text-title-color">
          {{ $t('emailConnector.admin.syncSettings.title') }}
        </div>
        <v-spacer />
        <v-btn
          :title="$t('emailConnector.admin.syncSettings.open')"
          :aria-label="$t('emailConnector.admin.syncSettings.open')"
          icon
          max-width="36"
          max-height="36"
          @click="open">
          <v-icon size="20" class="text-light-color">
            fa-sliders-h
          </v-icon>
        </v-btn>
      </div>
      <div class="text-subtitle d-flex">
        <div class="pe-6">
          {{ $t('emailConnector.admin.syncSettings.subtitle') }}
        </div>
      </div>
    </div>
  </div>
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
