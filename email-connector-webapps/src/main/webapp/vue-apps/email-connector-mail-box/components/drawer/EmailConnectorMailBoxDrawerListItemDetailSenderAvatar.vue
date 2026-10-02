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
  <!-- The sender's picture, or the coloured initials the server draws for a sender with
       none (EmailConnectorUtils#getSenderDefaultAvatar), drawn here when the message
       carries no picture: a list row never does, nor a message whose full copy could not
       be read and is shown from its list row. Such a sender's picture is asked for from
       the page's avatar cache (EmailConnectorSenderAvatars, EXO-90891) once the avatar
       is in view. A plain img: the list draws one per row. -->
  <v-list-item-avatar
    :color="avatarUrl ? null : initialsColor"
    :size="size">
    <img
      v-if="avatarUrl"
      :src="avatarUrl"
      alt="">
    <span
      v-else
      :class="size < 40 ? 'caption' : 'text-h6'"
      class="white--text">{{ initials }}</span>
  </v-list-item-avatar>
</template>

<script>
import { avatarColor, personLabel, senderAvatarInitials } from '../../js/EmailRecipientDisplay.js';
import { rememberSenderAvatar, senderAvatarUrl, unwatchSenderAvatar, watchSenderAvatar } from '../../js/EmailConnectorSenderAvatars.js';

export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
    // Who to draw instead of the message's sender: a draft row names the people of
    // its conversation, not its owner ({name, address}, the address optional).
    person: {
      type: Object,
      default: null,
    },
    size: {
      type: Number,
      default: 40,
    },
  },
  computed: {
    /**
     * Who the avatar draws: the person asked for, else the message's sender.
     *
     * @returns {Object} {name, address}, or null
     */
    shown() {
      return this.person || this.email?.sender || null;
    },
    /**
     * The address whose picture the page's cache is asked for, when the message
     * carries none of its own.
     *
     * @returns {String} the address, or null
     */
    lookupAddress() {
      return this.shown?.avatarUrl ? null : this.shown?.address || null;
    },
    /**
     * The sender's picture: the message's own, else the one the page's cache holds
     * for the address.
     *
     * @returns {String} the URL, or null
     */
    avatarUrl() {
      return this.shown?.avatarUrl || (this.lookupAddress && senderAvatarUrl(this.lookupAddress)) || null;
    },
    /**
     * The name the initials and their colour are read off: the name, else the address
     * -- the label the server draws its own initials from.
     *
     * @returns {String} the label
     */
    label() {
      return personLabel(this.shown);
    },
    /**
     * The initials, as the server draws them.
     *
     * @returns {String} up to two upper-cased initials, ? for nobody
     */
    initials() {
      return senderAvatarInitials(this.label);
    },
    /**
     * The colour behind the initials, by the server's formula.
     *
     * @returns {String} the colour
     */
    initialsColor() {
      return avatarColor(this.label);
    },
  },
  watch: {
    /**
     * Asks for the picture of a sender the row now shows instead.
     *
     * @returns {void}
     */
    lookupAddress() {
      this.watchAvatar();
    },
    /**
     * Records the picture a whole message carries for its sender, for the list.
     *
     * @returns {void}
     */
    'shown.avatarUrl': {
      immediate: true,
      handler(avatarUrl) {
        if (avatarUrl) {
          rememberSenderAvatar(this.shown.address, avatarUrl);
        }
      },
    },
  },
  /**
   * Asks for the sender's picture once the avatar is in view.
   *
   * @returns {void}
   */
  mounted() {
    this.watchAvatar();
  },
  /**
   * Stops watching the avatar for coming into view.
   *
   * @returns {void}
   */
  beforeDestroy() {
    unwatchSenderAvatar(this.$el);
  },
  methods: {
    /**
     * Has the page's cache ask for the sender's picture once the avatar is in view.
     *
     * @returns {void}
     */
    watchAvatar() {
      if (this.lookupAddress) {
        watchSenderAvatar(this.$el, this.lookupAddress);
      } else {
        unwatchSenderAvatar(this.$el);
      }
    },
  },
};
</script>
