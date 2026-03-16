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
      :background-color="backgroundColor"
      hide-details
      :label="$t('emailConnector.mailBox.list.drawer.selectAll')"
      v-model="selectedAll"
      @click.stop />
    <email-connector-mail-box-drawer-list
      :emails="emails"
      :selected-emails="selectedEmails"
      :select-mode="selectMode"
      :sync-in-progress="syncInProgress"
      :expanded="expanded" />
  </div>
</template>

<script>
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
    syncInProgress: {
      type: Boolean,
      default: false,
    },
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
    backgroundColor() {
      return this.expanded && '#f2f2f2';
    }
  },
  methods: {
    onSelectAllChange(value) {
      const newSelection = value ? this.emails.map(e => e.mailRemoteId) : [];
      this.$emit('update:selected-emails', newSelection);
    }
  }
};
</script>