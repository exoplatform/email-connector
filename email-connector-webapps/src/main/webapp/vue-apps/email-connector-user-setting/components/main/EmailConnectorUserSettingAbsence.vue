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
  <!-- The automatic reply (EXO-90642): a setting of the user's own mailbox that the mail
       server runs at delivery. The row only summarises what the server holds, read live;
       the edit icon opens the drawer, mounted at the app's root like the other settings
       drawers, so this row can sit anywhere in the list without taking the form along. -->
  <v-list-item class="height-auto">
    <v-list-item-content>
      <v-list-item-title class="text-color">
        {{ $t('UserSettings.emailConnector.absence.title') }}
      </v-list-item-title>
      <v-list-item-subtitle class="text-wrap">
        {{ summary }}
      </v-list-item-subtitle>
      <v-list-item-subtitle
        v-if="stateMessage"
        class="caption warning--text text-wrap">
        {{ $t('UserSettings.emailConnector.absence.row.attention') }}
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action v-if="supported">
      <v-btn
        icon
        :title="$t('UserSettings.emailConnector.absence.edit.tooltip')"
        @click="$root.$emit(OPEN_ABSENCE_DRAWER_EVENT)">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
import absenceMixin, { ABSENCE_UPDATED_EVENT, OPEN_ABSENCE_DRAWER_EVENT } from '../../js/EmailConnectorAbsenceMixin.js';

export default {
  mixins: [absenceMixin],
  data: () => ({
    OPEN_ABSENCE_DRAWER_EVENT,
  }),
  computed: {
    /**
     * The row's one line: loading, unsupported, off, or on with its last day.
     *
     * @returns {String} the localized line
     */
    summary() {
      if (this.loading && !this.absence) {
        return this.$t('UserSettings.emailConnector.absence.loading');
      }
      if (!this.absence) {
        return this.error || this.$t('UserSettings.emailConnector.absence.description');
      }
      if (!this.supported) {
        return this.unsupportedMessage;
      }
      const vacation = this.absence.vacation;
      if (!vacation?.enabled || this.absence.vacationState === 'ELSEWHERE') {
        return this.$t('UserSettings.emailConnector.absence.off');
      }
      if (vacation.end) {
        return this.$t('UserSettings.emailConnector.absence.onUntil', { 0: this.formatAbsenceDay(vacation.end) });
      }
      return this.$t('UserSettings.emailConnector.absence.on');
    },
  },
  created() {
    this.readAbsence();
    // The drawer and the mailbox band change the reply too; each says so on the document.
    document.addEventListener(ABSENCE_UPDATED_EVENT, this.readAbsence);
  },
  beforeDestroy() {
    document.removeEventListener(ABSENCE_UPDATED_EVENT, this.readAbsence);
  },
};
</script>
