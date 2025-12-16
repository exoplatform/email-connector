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
  <v-progress-circular
    :title="label"
    v-on="on"
    v-bind="attrs"
    :size="iconSize"
    :width="loaderWidth"
    color="primary"
    indeterminate 
    :class="loaderClass" />
</template>
<script>
export default {
  props: {
    label: {
      type: String,
      default: null,
    },
    loaderClass: {
      type: String,
      default: null,
    },
    loaderWidth: {
      type: Number,
      default: 4,
    },
    iconSize: {
      type: Number,
      default: 20,
    },
  },
};
</script>