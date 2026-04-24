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
  <v-list class="pa-0">
    <v-list-item
      class="ps-2 pe-3 height-auto"
      @click="openForwardEmailDrawer">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          fa-share
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.forward.label') }}
      </span>
    </v-list-item>
  </v-list>
</template>

<script>
export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
  },
  methods: {
    openForwardEmailDrawer() {
      this.$root.$emit('open-new-email-drawer', this.email, true);
    }
  }
};
</script>