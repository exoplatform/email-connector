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
  <div>
    <v-checkbox
      class="ps-4 my-2 pt-0"
      v-if="selectMode"
      :indeterminate="indeterminate"
      color="#707070"
      :background-color="selectAllBackground"
      hide-details
      :label="$t('emailConnector.mailBox.list.drawer.selectAll')"
      v-model="selectedAll"
      @click.stop />
    <email-connector-mail-box-drawer-list
      ref="list"
      :emails="emails"
      :current-email="email"
      :selected-emails="selectedEmails"
      :select-mode="selectMode"
      :sync-in-progress="syncInProgress"
      :expanded="expanded"
      :drag-source="dragSource"
      :webmail-url="webmailUrl" />
  </div>
</template>

<script>
import { selectionKey } from '../../js/EmailConnectorMailBoxSelection.js';

export default {
  props: {
    emails: {
      type: Array,
      default: () => [],
    },
    selectMode: {
      type: Boolean,
      default: false,
    },
    indeterminate: {
      type: Boolean,
      default: false,
    },
    selectedEmails: {
      type: Array,
      default: () => [],
    },
    expanded: {
      type: Boolean,
      default: false,
    },
    // The mail being dragged from the list, for its rows to fade (EXO-90421).
    dragSource: {
      type: Object,
      default: null,
    },
    syncInProgress: {
      type: Boolean,
      default: false,
    },
    webmailUrl: {
      type: String,
      default: null,
    },
    email: {
      type: Object,
      default: () => null,
    }
  },
  computed: {
    selectedAll: {
      get() {
        return this.emails.length > 0 && this.selectedEmails.length === this.emails.length;
      },
      set(value) {
        this.onSelectAllChange(value);
      }
    },
    /**
     * The select-all row's background: none in full screen, where the list sits on the
     * drawer's grey pane and the platform paints every input slot of a drawer white
     * (its drawer mixin's .v-input__slot) -- Vuetify's transparent class outranks that.
     * The row scrolls with the list, so it needs no opaque background. The narrow
     * layout keeps the drawer's own, as it always had (EXO-90415).
     *
     * @returns {String} the Vuetify background colour, or null for the default
     */
    selectAllBackground() {
      return this.expanded ? 'transparent' : null;
    },
  },
  methods: {
    /**
     * Brings one row of the list into view and focuses it (see the list's own
     * revealThread).
     *
     * @param {String} threadKey the row's key
     * @returns {Promise<void>} resolved once the row has the focus
     */
    revealThread(threadKey) {
      return this.$refs.list?.revealThread(threadKey);
    },
    /**
     * Selects every listed row, or none.
     *
     * @param {Boolean} value whether to select them all
     * @returns {void}
     */
    onSelectAllChange(value) {
      // A row the server has not listed yet (refreshPending: one an Undo put back, one
      // a move filed here) is not selectable: it carries a UID the server has
      // renumbered, or a placeholder, and any action on it would be counted a failure.
      const newSelection = value ? this.emails.filter(e => !e.refreshPending).map(selectionKey) : [];
      this.$emit('update:selected-emails', newSelection);
    }
  }
};
</script>