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
  <v-list-item-avatar
    class="me-4"
    size="40"
    :color="!email.sender.avatarUrl ? bgColor : undefined">
    <template v-if="email.sender.avatarUrl">
      <v-img :src="email.sender.avatarUrl" />
    </template>
    <template v-else>
      {{ initials }}
    </template>
  </v-list-item-avatar>
</template>

<script>
export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
  },
  computed: {
    initials() {
      const text = (this.email.sender.name || '').trim();
      const parts = text.split(/\s+/);
      if (parts.length === 0) {
        return '';
      }
      if (parts.length === 1) {
        return parts[0].charAt(0).toUpperCase();
      }
      return (parts[0].charAt(0) + parts[1].charAt(0)).toUpperCase();
    },
    bgColor() {
      const source = this.email.sender.email || this.email.sender.name || '';
      let hash = 0;
      for (let i = 0; i < source.length; i++) {
        hash = source.charCodeAt(i) + ((hash << 5) - hash);
      }
      const hue = Math.abs(hash) % 360;
      return `hsl(${hue},70%,50%)`;
    },
  }
};
</script>