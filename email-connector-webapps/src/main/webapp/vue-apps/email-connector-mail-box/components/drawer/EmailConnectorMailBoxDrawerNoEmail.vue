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
  <!-- Compact, it sits at the top of the full-screen list column, under its chips
       (EXO-90415); otherwise it fills the narrow drawer. -->
  <v-list-item :class="compact ? 'pt-6 align-start' : 'full-height align-center'">
    <v-list-item-content>
      <v-icon
        :size="compact ? 32 : 60"
        class="tertiary--text">
        far fa-envelope
      </v-icon>
      <v-list-item-title :class="compact ? 'mt-3' : 'mt-5'" class="text-wrap">
        {{ $t('emailConnector.mailBox.list.drawer.noEmail') }}
      </v-list-item-title>
    </v-list-item-content>
  </v-list-item>
</template>

<script>
export default {
  props: {
    // Whether it sits at the top of the full-screen list column rather than filling
    // the narrow drawer.
    compact: {
      type: Boolean,
      default: false,
    },
  },
};
</script>
