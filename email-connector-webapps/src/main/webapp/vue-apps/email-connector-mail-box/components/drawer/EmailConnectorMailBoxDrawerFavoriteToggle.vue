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
  <!-- The favorite is the mail server's own \Flagged flag: toggling it here changes it
       in every mail client the user reads this mailbox with, and vice versa. -->
  <v-btn
    v-if="canToggle"
    :title="toggleTitle"
    :width="buttonSize"
    :height="buttonSize"
    :min-width="buttonSize"
    icon
    @click.stop.prevent="$emit('toggle')">
    <v-icon :size="size" :class="favoriteColorClass">
      {{ favorite ? 'fas fa-star' : 'far fa-star' }}
    </v-icon>
  </v-btn>
  <!-- Read only (e.g. a Sent/Archive copy, whose favorite cannot be pushed from here):
       show the flag when it is set, nothing otherwise. -->
  <v-icon
    v-else-if="favorite"
    :size="size"
    :title="$t('emailConnector.mailBox.list.drawer.detail.favorite.label')"
    :class="favoriteColorClass">
    fas fa-star
  </v-icon>
</template>

<script>
export default {
  props: {
    // Whether the message (or thread) currently carries the flag.
    favorite: {
      type: Boolean,
      default: false,
    },
    // Whether the star may be toggled here (canStar): an INBOX message, or one of a
    // shared mailbox where the user holds w (EXO-90550); elsewhere the favorite is
    // shown as a plain read-only indicator.
    canToggle: {
      type: Boolean,
      default: false,
    },
    // The owner's name when the message is in a mailbox shared with the user: the star
    // is theirs too, and the button says so (EXO-90550).
    sharedOwner: {
      type: String,
      default: '',
    },
    size: {
      type: Number,
      default: 14,
    },
  },
  computed: {
    /**
     * The button's title: add or remove, and in a shared mailbox that the star is its
     * owner's too -- it stars the message for them and adds it to their favorites.
     *
     * @returns {String} the title
     */
    toggleTitle() {
      const action = this.favorite
        ? this.$t('emailConnector.mailBox.list.drawer.detail.removeFavorite.label')
        : this.$t('emailConnector.mailBox.list.drawer.detail.addFavorite.label');
      return this.sharedOwner
        ? `${action}. ${this.$t('emailConnector.mailBox.list.drawer.detail.favorite.sharedOwner', { 0: this.sharedOwner })}`
        : action;
    },
    // A lit favorite is amber everywhere (the color every mail client uses);
    // an unlit, toggleable one stays as quiet as the other secondary icons.
    favoriteColorClass() {
      return this.favorite ? 'amber--text text--darken-1' : 'icon-default-color';
    },
    // Keep the click target slightly larger than the glyph without inflating rows.
    buttonSize() {
      return this.size + 10;
    },
  },
};
</script>
