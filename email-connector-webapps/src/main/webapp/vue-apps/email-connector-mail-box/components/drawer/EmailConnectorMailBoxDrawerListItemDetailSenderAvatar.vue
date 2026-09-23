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
  <!-- The sender's picture, or their initial when there is none to show: a message
       whose full copy could not be read is shown from its list row, which carries no
       avatar, and an empty 40 px image left a gap the header's layout collapsed into. -->
  <v-list-item-avatar
    :color="avatarUrl ? null : 'primary'"
    size="40">
    <v-img v-if="avatarUrl" :src="avatarUrl" />
    <span v-else class="white--text text-h6">{{ initial }}</span>
  </v-list-item-avatar>
</template>

<script>
import { personLabel } from '../../js/EmailRecipientDisplay.js';

export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
  },
  computed: {
    /**
     * The sender's picture, when the message carries one.
     *
     * @returns {String} the URL, or null
     */
    avatarUrl() {
      return this.email?.sender?.avatarUrl || null;
    },
    /**
     * The first letter of the sender's name, or of their address when they have none.
     *
     * @returns {String} the initial, upper-cased
     */
    initial() {
      // personLabel: the name, else the address -- never the word null.
      const label = (personLabel(this.email?.sender) || '').trim() || '?';
      return (label.charAt(0) || '?').toUpperCase();
    },
  },
};
</script>