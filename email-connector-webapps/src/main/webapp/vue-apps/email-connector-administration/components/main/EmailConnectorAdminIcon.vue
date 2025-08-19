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
  <v-avatar
    :height="iconSize"
    :min-width="iconSize"
    :width="iconSize"
    class="d-flex justify-center align-center"
    tile>
    <v-img
      v-if="iconUrl"
      :src="iconUrl"
      :max-height="iconSize"
      :height="iconSize"
      :max-width="iconSize"
      contain
      eager />
    <v-icon
      v-else
      :size="iconSize"
      class="icon-default-color">
      {{ icon || 'fa-envelope' }}
    </v-icon>
  </v-avatar>
</template>
<script>
export default {
  data: () => ({
    iconSize: 40
  }),
  props: {
    iconUrl: {
      type: String,
      default: null,
    },
    icon: {
      type: String,
      default: null,
    },
  },
};
</script>