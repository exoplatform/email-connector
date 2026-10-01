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
  <!-- The search's criteria above its results (EXO-90838): one chip each, closed to take
       that criterion off and search again, in the chip language of the list's own filter
       chips (small, primary), on one line that scrolls sideways; "Clear all" once there
       are two. The search box's own text is in the box, not a chip, and the advanced
       search's button is on the row above. -->
  <div v-if="chips.length" class="d-flex align-center px-3 pt-2">
    <!-- One line: the chips scroll sideways instead of wrapping, so the results never move
         down when a criterion is added; "Clear all" stays in view at the end. -->
    <div class="d-flex align-center flex-nowrap overflow-x-auto flex-grow-1">
      <v-chip
        v-for="chip in chips"
        :key="chip.key"
        :aria-label="chip.label"
        class="me-2 mb-1 flex-shrink-0"
        color="primary"
        close
        :close-label="$t('emailConnector.mailBox.search.chip.remove', { 0: chip.label })"
        small
        @click:close="$emit('remove', chip.key)">
        <span class="text-truncate white--text" style="max-width: 220px;">{{ chip.label }}</span>
      </v-chip>
    </div>
    <v-btn
      v-if="chips.length > 1"
      class="mb-1 px-1 flex-shrink-0"
      color="primary"
      small
      text
      @click="$emit('clear')">
      {{ $t('emailConnector.mailBox.search.chips.clear') }}
    </v-btn>
  </div>
</template>

<script>
export default {
  props: {
    // The criteria, as the drawer labels them: [{key, label}].
    chips: {
      type: Array,
      default: () => [],
    },
  },
};
</script>
