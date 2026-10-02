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
  <!-- The selection's actions, their row held at the reading pane's vertical centre,
       under how many mails they act on (EXO-90891), placed just above that row so the
       count never pushes it down; the select-all row at the top of the list says it too. -->
  <div class="d-flex flex-column align-center justify-center full-height full-width pa-4">
    <div class="full-width" style="position: relative;">
      <div
        class="multi-select-count font-weight-bold text-h6 text-center full-width"
        style="position: absolute; bottom: 100%; left: 0; padding-bottom: 24px;"
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
