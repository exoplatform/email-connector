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
    :input-value="selected"
    class="px-4"
    @click="onClick"
    @mouseenter="revealed = true"
    @mouseleave="revealed = false"
    @focusin="revealed = true"
    @focusout="onFocusOut">
    <!-- Any contact can be ticked. Selecting is not publishing: it will serve
         deleting, exporting and whatever comes next, and a row locked by the
         rules of one action would be the wrong shape for all the others. What
         a selection can DO is answered in the header, where an action is
         offered only when every ticked contact supports it.

         Both the box and the row toggle it. The box stops the press from
         reaching the row, exactly as the mailbox list does; without that it
         reached the row's own handler as well and the two cancelled out. -->
    <v-checkbox
      v-if="selectMode"
      :input-value="ticked"
      class="mt-0 pt-0 me-2 align-self-center"
      color="#707070"
      background-color="transparent"
      hide-details
      dense
      @click.stop
      @change="$emit('tick')" />
    <v-list-item-avatar
      size="36"
      class="my-1 me-3">
      <v-img
        v-if="contact.avatarUrl"
        :src="contact.avatarUrl" />
      <v-avatar
        v-else
        :color="initialsColor"
        size="36">
        <span class="white--text text-caption">{{ initials }}</span>
      </v-avatar>
    </v-list-item-avatar>
    <v-list-item-content class="py-1">
      <v-list-item-title class="text-color">
        {{ displayedName }}
      </v-list-item-title>
      <v-list-item-subtitle class="text-sub-title">
        {{ secondLine }}
      </v-list-item-subtitle>
    </v-list-item-content>
    <!-- A static star, deliberately not a button: the list streams up to
         thousands of rows, and one listener per row is a real cost for an
         action the detail card already offers. Toggling lives there.
         Still true next to the actions menu below, which reads as the opposite
         but is not: what the row pays for it is four plain DOM listeners, and
         a component only for the one row being pointed at. A favorite toggle
         would be a v-btn per row — five hundred component instances for a
         click most rows never get. -->
    <v-list-item-icon
      v-if="contact.favorite"
      class="my-auto ms-2">
      <v-icon
        size="12"
        class="yellow--text text--darken-2">
        fas fa-star
      </v-icon>
    </v-list-item-icon>
    <v-list-item-icon
      v-if="sourceIcon"
      class="my-auto ms-2">
      <v-icon
        :title="sourceLabel"
        size="12"
        class="text-sub-title">
        {{ sourceIcon }}
      </v-icon>
    </v-list-item-icon>
    <!-- The row's own actions, and the one place this file spends anything per
         row. What is always here is this empty 28px box: a plain div, no
         listeners, no component. The menu itself is mounted only while the row
         is hovered or focused, so a list of five hundred rows holds ONE menu at
         a time instead of five hundred activators, five hundred v-menus and
         five hundred detached content nodes waiting to be opened.

         The mailbox solves this the other way — it keeps every row's menu
         mounted and only fades it to opacity 0 — and it is right to, for a list
         of at most a hundred heavy rows where the menu is a fraction of what a
         row already costs. Here the row is deliberately thin and the list runs
         to thousands, so the menu would be the row's biggest expense, and paid
         for rows nobody will ever point at.

         The empty box is what makes mounting on hover safe. Left to v-if alone
         the icon appears from nothing, the row's content shifts as the pointer
         arrives, the pointer ends up over different content, the hover drops,
         and the row flickers as long as the cursor rests — the very trap the
         mailbox's opacity avoids. Reserving the width up front costs 28px of
         every row and buys a row whose layout never moves. -->
    <div
      v-if="!selectMode"
      class="ms-2 d-flex align-center"
      style="width: 28px; min-width: 28px;">
      <email-connector-contacts-list-item-action-menu
        v-if="revealed || menuOpen"
        :contact="contact"
        @start-select="$emit('start-select')"
        @open="menuOpen = true"
        @close="menuOpen = false" />
    </div>
  </v-list-item>
</template>

<script>
// The platform's avatar accents, hashed from the name so a contact keeps its
// color across renders.
const INITIALS_COLORS = ['#ef5350', '#1976d2', '#ab47bc', '#00897b', '#9e9d24', '#fbc02d',
  '#00bfa5', '#757575', '#f44336', '#2196f3', '#7cb342', '#303f9f', '#4527a0', '#8d6e63', '#ff6f00'];

export default {
  data() {
    return {
      // Whether the pointer is on this row, or the keyboard is in it — the two
      // ways of asking for the row's actions, and what decides the menu is
      // worth building at all.
      revealed: false,
      // Whether this row's menu is open. Kept apart from the hover, because a
      // detached menu is not inside the row: the pointer reaching it leaves the
      // row, and unmounting on that would close the menu the user just opened.
      menuOpen: false,
    };
  },
  props: {
    // The contact to render.
    contact: {
      type: Object,
      required: true,
    },
    // Whether this row is the one open in the expanded inline detail.
    selected: {
      type: Boolean,
      default: false,
    },
    // Whether the list is picking contacts rather than browsing them.
    selectMode: {
      type: Boolean,
      default: false,
    },
    // Whether this row is ticked.
    ticked: {
      type: Boolean,
      default: false,
    },
  },
  methods: {
    /**
     * What a press on the row means, which depends on what the list is for.
     * <p>
     * Browsing, it opens the contact. Picking, it ticks.
     *
     * @returns {void}
     */
    onClick() {
      if (!this.selectMode) {
        this.$emit('select');
        return;
      }
      this.$emit('tick');
    },
    /**
     * Whether the keyboard has really left this row.
     * <p>
     * Tabbing from the row to its own menu button fires a focusout on the row
     * before the button's focusin: taken at face value it unmounts the menu the
     * user is tabbing to, and the button vanishes under the focus. The event
     * says where focus went, so the row only stops revealing when it went
     * somewhere outside.
     *
     * @param {FocusEvent} event - the row losing focus
     * @returns {void}
     */
    onFocusOut(event) {
      if (!this.$el.contains(event.relatedTarget)) {
        this.revealed = false;
      }
    },
  },
  computed: {
    /**
     * The line the row leads with: the name, falling back to the address.
     *
     * @returns {string} the displayed name
     */
    displayedName() {
      return this.contact.displayName || this.contact.primaryEmail || '';
    },
    /**
     * The second line: the address when the first line is a name, else the
     * organization.
     *
     * @returns {string} the sub-line
     */
    secondLine() {
      if (this.contact.displayName && this.contact.primaryEmail) {
        return this.contact.primaryEmail;
      }
      return this.contact.organization || '';
    },
    /**
     * The initials shown when no avatar exists.
     *
     * @returns {string} up to two uppercased initials
     */
    initials() {
      return this.displayedName.split(/\s+/)
        .filter(word => word)
        .map(word => word.charAt(0).toUpperCase())
        .slice(0, 2)
        .join('') || '?';
    },
    /**
     * A stable accent color for the initials avatar, hashed from the name.
     *
     * @returns {string} a hex color
     */
    initialsColor() {
      return INITIALS_COLORS[this.displayedName.length % INITIALS_COLORS.length];
    },
    /**
     * The small source badge of curated rows; collected rows — the normal
     * case — carry none.
     *
     * @returns {string} the badge icon, or null
     */
    sourceIcon() {
      if (this.contact.source === 'MANUAL') {
        return 'fas fa-user-edit';
      }
      if (this.contact.source === 'DIRECTORY') {
        return 'fas fa-users';
      }
      if (this.contact.source === 'CARDDAV') {
        return 'fas fa-address-book';
      }
      return null;
    },
    /**
     * The badge's tooltip.
     *
     * @returns {string} the localized source label
     */
    sourceLabel() {
      if (this.contact.source === 'MANUAL') {
        return this.$t('emailConnector.contacts.source.manual');
      }
      if (this.contact.source === 'DIRECTORY') {
        return this.$t('emailConnector.contacts.source.directory');
      }
      return this.$t('emailConnector.contacts.source.addressBook');
    },
  },
};
</script>
