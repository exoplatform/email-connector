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
  <v-list-item
    class="height-auto px-0 align-start">
    <v-list-item-action class="my-0 align-self-start me-2" :style="{ width: labelWidth + 'px' }">
      <v-list-item-subtitle class="text-color">
        {{ label }}
      </v-list-item-subtitle>
    </v-list-item-action>
    <v-list-item-content class="py-0">
      <v-list-item-subtitle
        v-for="(value, index) in values"
        :key="value.address"
        :class="{'mb-2': index !== values.length - 1}">
        <!-- Name and address are interpolated, never built as an HTML string: both
             come straight from the mail's headers, so whoever sent the mail chooses
             them. Rendered as markup, a From name is script the reader runs on the
             portal page — and the drawer is not the sandboxed frame the body gets.
             The link around the name is ours, so it stays real markup; only the href
             is bound, and only to a platform profile URL resolved on our side. -->
        <a
          v-if="value.profileUrl"
          :href="value.profileUrl"
          target="_blank"
          rel="noopener noreferrer">{{ value.name }}</a>
        <span
          v-else
          class="text-color">{{ value.name }}</span>
        <!-- A real interpolated space, not a margin: margin is box geometry, not text
             content, so selection/copy, find-in-page and screen readers would glue the
             name to the address. A literal blank between elements is condensed away by
             the template compiler; an interpolated one cannot be. -->
        {{ ' ' }}
        <span>{{ value.address }}</span>
      </v-list-item-subtitle>
    </v-list-item-content>
  </v-list-item>
</template>

<script>
export default {
  props: {
    label: {
      type: String,
      default: null,
    },
    values: {
      type: Array,
      default: () => [],
    },
    labelWidth: {
      type: Number,
      default: null,
    },
  },
};
</script>