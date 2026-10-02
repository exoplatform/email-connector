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
       is in view. A company sender's brand logo (EXO-90893) comes after a person's photo
       and before the initials, and falls back to them when it does not load. A plain
       img: the list draws one per row, and its error event is what the fallback needs. -->
  <v-list-item-avatar
    :color="avatarUrl ? null : initialsColor"
    :size="size">
    <img
      v-if="avatarUrl"
      :src="avatarUrl"
      alt=""
      @error="onImageError">
    <span
      v-else
      :class="size < 40 ? 'caption' : 'text-h6'"
      class="white--text">{{ initials }}</span>
  </v-list-item-avatar>
</template>

<script>
import { avatarColor, personLabel, senderAvatarInitials } from '../../js/EmailRecipientDisplay.js';
import { isSenderLogoUrl, rememberSenderAvatar, senderAvatarUrl, unwatchSenderAvatar, watchSenderAvatar } from '../../js/EmailConnectorSenderAvatars.js';

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
  data: () => ({
    logoFailed: false,
  }),
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
     * The picture the page's cache holds for the address, when the message carries
     * none of its own: a person's photo, or the address's brand logo.
     *
     * @returns {String} the URL, or null
     */
    cachedUrl() {
      return (this.lookupAddress && senderAvatarUrl(this.lookupAddress)) || null;
    },
    /**
     * A person's photo: the message's own (never the server's generated initials, a
     * data: URL), else the one the page's cache holds.
     *
     * @returns {String} the URL, or null
     */
    photoUrl() {
      const own = this.shown?.avatarUrl;
      if (own && !own.startsWith('data:')) {
        return own;
      }
      return this.cachedUrl && !isSenderLogoUrl(this.cachedUrl) ? this.cachedUrl : null;
    },
    /**
     * The sender's brand logo (EXO-90893), unless it failed to load: the one the
     * reader's server offered for this message, else the one the page's cache holds for
     * the address, shown on a row the server vouched for only -- a spoofed mail from the
     * same address keeps its initials. Never for a draft row's people.
     *
     * @returns {String} the URL, or null
     */
    logoUrl() {
      if (this.logoFailed || this.person) {
        return null;
      }
      const sender = this.email?.sender;
      if (sender?.logoUrl) {
        return sender.logoUrl;
      }
      return sender?.domainVerified && isSenderLogoUrl(this.cachedUrl) ? this.cachedUrl : null;
    },
    /**
     * What the avatar draws: a person's photo, else the brand logo, else the server's
     * generated initials, else nothing (the initials are drawn here).
     *
     * @returns {String} the URL, or null
     */
    avatarUrl() {
      return this.photoUrl || this.logoUrl || this.shown?.avatarUrl || null;
    },
    /**
     * What the reader teaches the page about its sender: the logo the server offered,
     * else the picture it gave.
     *
     * @returns {String} the URL, or null
     */
    learntUrl() {
      return (!this.person && this.email?.sender?.logoUrl) || this.shown?.avatarUrl || null;
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
    learntUrl: {
      immediate: true,
      handler(url) {
        if (url) {
          rememberSenderAvatar(this.shown.address, url);
        }
      },
    },
    /**
     * Gives the logo of another message its chance.
     *
     * @returns {void}
     */
    'email.sender.logoUrl'() {
      this.logoFailed = false;
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
     * Falls back on the sender's other picture when the brand logo cannot be loaded:
     * the server restarted since it offered it, or logos were switched off.
     *
     * @returns {void}
     */
    onImageError() {
      if (!this.logoFailed && this.avatarUrl === this.logoUrl) {
        this.logoFailed = true;
      }
    },
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
