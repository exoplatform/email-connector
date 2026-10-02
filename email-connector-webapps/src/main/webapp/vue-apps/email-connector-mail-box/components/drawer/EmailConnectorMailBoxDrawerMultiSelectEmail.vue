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
<template>
  <!-- The selection's actions, centred in the reading pane, under how many mails they
       act on (EXO-90891); the select-all row at the top of the list says it too. -->
  <div class="d-flex flex-column align-center justify-center full-height full-width pa-4">
    <div
      class="multi-select-count font-weight-bold text-subtitle-1 text-center mb-4"
      aria-live="polite">
      {{ countLabel }}
    </div>
    <email-connector-mail-box-drawer-actions
      class="full-width"
      select-mode
      :emails="emails"
      :top="false"
      :selected-emails="selectedEmails" />
  </div>
</template>
<script>
export default {
  props: {
    emails: {
      type: Array,
      default: () => [],
    },
    selectedEmails: {
      type: Array,
      default: () => [],
    },
  },
  computed: {
    /**
     * How many mails the actions below act on.
     *
     * @returns {String} "1 selected email", "N selected emails"
     */
    countLabel() {
      return this.selectedEmails.length === 1
        ? this.$t('emailConnector.mailBox.list.drawer.multiSelect.selectedOne')
        : this.$t('emailConnector.mailBox.list.drawer.multiSelect.selectedMany', { 0: this.selectedEmails.length });
    },
  },
};
</script>
