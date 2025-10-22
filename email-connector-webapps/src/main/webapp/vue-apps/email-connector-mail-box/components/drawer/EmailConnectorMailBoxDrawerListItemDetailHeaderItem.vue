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
    class="px-0 align-start"
    style="min-height: 0;">
    <v-list-item-action class="my-0 align-self-start me-2">
      <v-list-item-subtitle class="text-color">
        {{ label }}:
      </v-list-item-subtitle>
    </v-list-item-action>
    <v-list-item-content class="py-0">
      <v-list-item-subtitle
        v-for="value in values"
        :key="value.address"
        class="mb-2"
        v-html="parsedValue(value)" />
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
  },
  methods: {
    parsedValue(value) {
      return value.profileUrl && `<a href="${value.profileUrl}" target="_blank" rel="noopener noreferrer">${value.name}</a> ${value.address}` || `${value.name} ${value.address}`;
    },
  },
};
</script>