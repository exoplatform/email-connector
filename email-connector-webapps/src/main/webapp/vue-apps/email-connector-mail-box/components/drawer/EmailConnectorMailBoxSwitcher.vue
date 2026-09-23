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
  <!-- The mailbox switcher in the mail drawer's title (delegation plan 7.3): the user's
       own mailbox, then one entry per mailbox shared with them, then the way to manage
       those shares. Not there at all while nothing is shared: the title is then the
       plain title it always was. Inline styles only: the webapp has no CSS loader. -->
  <span v-if="!state.entries.length">{{ title }}</span>
  <v-menu
    v-else
    v-model="menu"
    offset-y
    max-width="360"
    min-width="280">
    <template #activator="{ on, attrs }">
      <button
        v-bind="attrs"
        :aria-label="$t('emailConnector.mailBox.switcher.label')"
        class="d-inline-flex align-center text-truncate"
        style="max-width: 100%;"
        type="button"
        v-on="on">
        <user-avatar
          v-if="current && current.ownerId"
          :profile-id="current.ownerId"
          :size="24"
          class="me-2 flex-grow-0 flex-shrink-0"
          avatar
          :popover="false"
          :url="false" />
        <span class="text-truncate">{{ label }}</span>
        <v-icon size="12" class="ms-2 icon-default-color">fa-chevron-down</v-icon>
        <span v-if="suffix" class="ms-2 text-truncate">· {{ suffix }}</span>
      </button>
    </template>
    <v-list dense class="py-1">
      <v-list-item @click="choose(null)">
        <v-list-item-icon class="me-3 my-auto">
          <v-icon size="18" class="icon-default-color">fa-inbox</v-icon>
        </v-list-item-icon>
        <v-list-item-content>
          <v-list-item-title>{{ $t('emailConnector.mailBox.switcher.own') }}</v-list-item-title>
          <v-list-item-subtitle v-if="ownAddress">{{ ownAddress }}</v-list-item-subtitle>
        </v-list-item-content>
        <v-list-item-action class="flex-row align-center my-auto">
          <span v-if="ownUnreadCount" class="font-weight-bold text-body-2 me-2">
            {{ $emailConnectorMailBoxService.formatCount(ownUnreadCount) }}
          </span>
          <v-icon
            v-if="!current"
            size="14"
            color="primary">
            fa-check
          </v-icon>
        </v-list-item-action>
      </v-list-item>
      <v-divider class="my-1" />
      <v-subheader class="text-uppercase caption px-4">
        {{ $t('emailConnector.mailBox.switcher.sharedWithYou') }}
      </v-subheader>
      <v-list-item
        v-for="entry in state.entries"
        :key="entry.delegationId"
        @click="choose(entry)">
        <v-list-item-avatar size="28" class="me-3 my-auto">
          <user-avatar
            v-if="entry.ownerId"
            :profile-id="entry.ownerId"
            :size="28"
            avatar
            :popover="false"
            :url="false" />
          <v-icon
            v-else
            size="18"
            class="icon-default-color">
            fa-user
          </v-icon>
        </v-list-item-avatar>
        <v-list-item-content>
          <v-list-item-title>{{ entry.ownerFullName }}</v-list-item-title>
          <v-list-item-subtitle>{{ presetLabel(entry) }}</v-list-item-subtitle>
        </v-list-item-content>
        <v-list-item-action class="flex-row align-center my-auto">
          <!-- An unread count only where read state is kept (s): without it the count
               is one nothing the user does here can change (plan 7.5). -->
          <span v-if="entry.unreadCount && entry.affordances && entry.affordances.markRead" class="font-weight-bold text-body-2 me-2">
            {{ $emailConnectorMailBoxService.formatCount(entry.unreadCount) }}
          </span>
          <v-icon
            v-if="current && current.delegationId === entry.delegationId"
            size="14"
            color="primary">
            fa-check
          </v-icon>
        </v-list-item-action>
      </v-list-item>
      <v-divider class="my-1" />
      <v-list-item @click="manage">
        <v-list-item-icon class="me-3 my-auto">
          <v-icon size="16" class="icon-default-color">fa-users-cog</v-icon>
        </v-list-item-icon>
        <v-list-item-title>{{ $t('emailConnector.mailBox.switcher.manage') }}</v-list-item-title>
      </v-list-item>
    </v-list>
  </v-menu>
</template>

<script>
export default {
  props: {
    // The drawer's own title, shown as it is when nothing is shared.
    title: { type: String, default: '' },
    // What narrows the list -- a folder, the favorites, a category -- after the name.
    suffix: { type: String, default: '' },
    // The user's own address and INBOX unread count, for the "My mailbox" entry.
    ownAddress: { type: String, default: '' },
    ownUnreadCount: { type: Number, default: 0 },
  },
  data: () => ({
    menu: false,
  }),
  computed: {
    /**
     * The switcher's shared state: its entries, and the mailbox the drawer is in.
     *
     * @returns {Object} {entries, current}
     */
    state() {
      return this.$emailConnectorMailBoxService.sharedMailboxState();
    },
    /**
     * The shared mailbox the drawer is in, or null in the user's own.
     *
     * @returns {Object} the switcher entry, or null
     */
    current() {
      return this.state.current;
    },
    /**
     * What the title says: "My mailbox", or whose mailbox the user is in.
     *
     * @returns {String} the label
     */
    label() {
      return this.current ? this.current.ownerFullName : this.$t('emailConnector.mailBox.switcher.own');
    },
  },
  methods: {
    /**
     * The name of the rights a share grants: the eXo preset they read as -- the words
     * the sharing settings use -- or "custom rights" for letters no preset expresses.
     *
     * @param {Object} entry the switcher entry
     * @returns {String} the label
     */
    presetLabel(entry) {
      return entry.preset
        ? this.$t(`UserSettings.emailConnector.sharing.preset.${entry.preset}`)
        : this.$t('emailConnector.mailBox.sharedMailbox.preset.CUSTOM');
    },
    /**
     * Asks the drawer to switch to a mailbox; the drawer does the switch, since the list,
     * the folders and the filters it resets are its own.
     *
     * @param {Object} entry the switcher entry, null for the user's own mailbox
     * @returns {void}
     */
    choose(entry) {
      this.menu = false;
      this.$emit('switch', entry);
    },
    /**
     * Opens the settings drawer where the shares are accepted, declined and left.
     *
     * @returns {void}
     */
    manage() {
      this.menu = false;
      this.$root.$emit('open-email-shared-with-me-drawer');
    },
  },
};
</script>
