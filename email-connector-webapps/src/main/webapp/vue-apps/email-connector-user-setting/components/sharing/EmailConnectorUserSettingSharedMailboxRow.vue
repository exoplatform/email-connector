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
  <!-- One mailbox shared with the user, laid out like its entry in the mail drawer's
       switcher: who, whose address, what the user can do there, then its setting and
       its actions. Inline styles only: the webapp has no CSS loader. -->
  <v-list-item class="height-auto align-start text-start px-4 py-2">
    <!-- Top-aligned with the name line, not centred on the whole row. -->
    <v-list-item-avatar size="36" class="me-3 mt-1 mb-0 align-self-start">
      <!-- user-avatar in its avatar-only mode: with `avatar` set it draws the picture
           and nothing else, whatever `fullname` says -- the name is on the line beside. -->
      <user-avatar
        v-if="delegation.ownerId"
        :profile-id="delegation.ownerId"
        :size="36"
        :popover="false"
        :url="false"
        avatar />
      <v-icon
        v-else
        size="20"
        class="icon-default-color">
        fa-user
      </v-icon>
    </v-list-item-avatar>
    <v-list-item-content class="py-1 text-start">
      <div class="d-flex align-center">
        <!-- The name, without the avatar this time (its name-only mode); an owner eXo
             does not know is named by the mailbox address alone. -->
        <user-avatar
          v-if="delegation.ownerId"
          :profile-id="delegation.ownerId"
          :popover="false"
          :url="false"
          class="text-truncate font-weight-bold"
          fullname />
        <span v-else class="text-truncate font-weight-bold">{{ delegation.ownerMailbox }}</span>
        <v-spacer />
        <v-chip
          class="ms-2 flex-shrink-0"
          x-small
          outlined>
          {{ presetLabel }}
        </v-chip>
        <!-- The row's actions, on its first line after the chips. -->
        <v-menu
          v-if="menuActions.length"
          offset-y
          left>
          <template #activator="{ on, attrs }">
            <v-btn
              :aria-label="$t('UserSettings.emailConnector.sharedWithMe.menu')"
              :disabled="disabled"
              v-bind="attrs"
              icon
              x-small
              v-on="on">
              <v-icon size="14" class="icon-default-color">fa-ellipsis-v</v-icon>
            </v-btn>
          </template>
          <v-list dense>
            <v-list-item
              v-for="action in menuActions"
              :key="action"
              @click="$emit('answer', action)">
              <v-list-item-title>{{ $t(`UserSettings.emailConnector.sharedWithMe.${action}`) }}</v-list-item-title>
            </v-list-item>
          </v-list>
        </v-menu>
      </div>
      <div v-if="delegation.ownerId" class="caption text-sub-title text-truncate">{{ delegation.ownerMailbox }}</div>
      <div class="caption text-sub-title text-wrap">{{ rightsSummary }}</div>
      <div v-if="note" class="caption text-sub-title text-wrap mt-1">{{ note }}</div>
      <!-- The badge choice, as a settings row: only where read state is kept (s),
           since without it there is no unread count to add. -->
      <div v-if="badgeOffered" class="d-flex align-center mt-1">
        <span class="caption text-wrap">{{ $t('UserSettings.emailConnector.sharedWithMe.badge') }}</span>
        <v-spacer />
        <v-switch
          :input-value="delegation.badgeIncluded"
          :loading="saving"
          :disabled="disabled"
          :aria-label="$t('UserSettings.emailConnector.sharedWithMe.badge')"
          class="mt-0 pt-0 ms-2 flex-grow-0"
          dense
          hide-details
          @change="$emit('badge', !!$event)" />
      </div>
      <!-- The new-mail notification (EXO-90553): for a share in use, and worded for what
           it does -- the shared mailbox is only checked while the user keeps opening it,
           so the notification cannot promise more. -->
      <template v-if="notifyOffered">
        <div class="d-flex align-center mt-1">
          <span class="caption text-wrap">{{ $t('UserSettings.emailConnector.sharedWithMe.notify') }}</span>
          <v-spacer />
          <v-switch
            :input-value="delegation.notifyNewMail"
            :loading="saving"
            :disabled="disabled"
            :aria-label="$t('UserSettings.emailConnector.sharedWithMe.notify')"
            class="mt-0 pt-0 ms-2 flex-grow-0"
            dense
            hide-details
            @change="$emit('notify', !!$event)" />
        </div>
        <div class="caption text-sub-title text-wrap">{{ $t('UserSettings.emailConnector.sharedWithMe.notify.hint') }}</div>
      </template>
      <!-- The search choice (EXO-90554): whether the platform's search returns this
           mailbox's mail, labelled with its owner. Any share in use has something to
           search. -->
      <div v-if="searchOffered" class="d-flex align-center mt-1">
        <span class="caption text-wrap">{{ $t('UserSettings.emailConnector.sharedWithMe.search') }}</span>
        <v-spacer />
        <v-switch
          :input-value="delegation.searchIncluded"
          :loading="saving"
          :disabled="disabled"
          :aria-label="$t('UserSettings.emailConnector.sharedWithMe.search')"
          class="mt-0 pt-0 ms-2 flex-grow-0"
          dense
          hide-details
          @change="$emit('search', !!$event)" />
      </div>
      <div v-if="visibleActions.length" class="d-flex justify-end mt-1">
        <v-btn
          v-for="action in visibleActions"
          :key="action"
          :loading="saving"
          :disabled="disabled"
          :class="action === 'accept' ? 'primary--text' : ''"
          small
          text
          @click="$emit('answer', action)">
          {{ $t(`UserSettings.emailConnector.sharedWithMe.${action}`) }}
        </v-btn>
      </div>
    </v-list-item-content>
  </v-list-item>
</template>

<script>
import { sharedMailboxCapabilities } from '../../../email-connector-mail-box/js/EmailConnectorSharedMailboxRules.js';

// The rare, destructive answers go in the row's menu; the others stay visible.
const MENU_ACTIONS = ['leave'];

export default {
  props: {
    delegation: { type: Object, required: true },
    // What the row's state lets the user do: accept, decline, leave.
    actions: { type: Array, default: () => [] },
    // The state's own sentence (decline keeps the access...), or empty.
    note: { type: String, default: '' },
    saving: { type: Boolean, default: false },
    disabled: { type: Boolean, default: false },
  },
  computed: {
    /**
     * The rights as the owner's preset names them, or "custom rights" for letters no
     * preset expresses -- the words of the mailbox switcher.
     *
     * @returns {String} the label
     */
    presetLabel() {
      return this.delegation.preset
        ? this.$t(`UserSettings.emailConnector.sharing.preset.${this.delegation.preset}`)
        : this.$t('UserSettings.emailConnector.sharedWithMe.preset.CUSTOM');
    },
    /**
     * One sentence of what the user can do in the mailbox, from the same rule the mail
     * drawer's controls follow (sharedMailboxCapabilities): nothing here may promise a
     * control the drawer does not show.
     *
     * @returns {String} the summary
     */
    rightsSummary() {
      const capabilities = sharedMailboxCapabilities(this.delegation.affordances);
      if (capabilities.markRead && capabilities.moveOut) {
        return this.$t('UserSettings.emailConnector.sharedWithMe.rights.markReadMoveOut');
      }
      if (capabilities.moveOut) {
        return this.$t('UserSettings.emailConnector.sharedWithMe.rights.moveOut');
      }
      return capabilities.markRead
        ? this.$t('UserSettings.emailConnector.sharedWithMe.rights.markRead')
        : this.$t('UserSettings.emailConnector.sharedWithMe.rights.read');
    },
    /**
     * Whether the unread-badge choice applies: a share in use whose rights keep read
     * state.
     *
     * @returns {Boolean} true when the switch is shown
     */
    badgeOffered() {
      return this.delegation.status === 'ACCEPTED' && sharedMailboxCapabilities(this.delegation.affordances).markRead;
    },
    /**
     * Whether the new-mail notification choice applies: a share in use. Any rights will
     * do, since reading is all it takes to see new mail arrive.
     *
     * @returns {Boolean} true when the switch is shown
     */
    notifyOffered() {
      return this.delegation.status === 'ACCEPTED';
    },
    /**
     * Whether the search choice applies: a share in use (EXO-90554).
     *
     * @returns {Boolean} true when the switch is shown
     */
    searchOffered() {
      return this.delegation.status === 'ACCEPTED';
    },
    /**
     * The answers shown as buttons.
     *
     * @returns {Array} the action names
     */
    visibleActions() {
      return this.actions.filter(action => !MENU_ACTIONS.includes(action));
    },
    /**
     * The answers kept in the row's menu.
     *
     * @returns {Array} the action names
     */
    menuActions() {
      return this.actions.filter(action => MENU_ACTIONS.includes(action));
    },
  },
};
</script>
