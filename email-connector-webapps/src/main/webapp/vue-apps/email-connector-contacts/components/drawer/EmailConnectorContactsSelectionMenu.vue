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
  <!-- What a selection can do beyond the three that earned a header icon: the
       two ways of handing the people over, the star, and the file. Labelled
       rather than iconified because these are the ones an icon alone cannot
       tell apart -- "send them by email" and "write to them" are one envelope
       away from each other, and the wrong guess mails strangers a stack of
       cards. Same rule as the icons: an entry is offered when EVERY ticked
       contact supports it, and disabled with the reason otherwise. -->
  <v-menu
    content-class="no-min-width border-radius z-index-modal overflow-hidden"
    close-on-content-click
    offset-y
    left
    bottom>
    <template #activator="{ on, attrs }">
      <v-btn
        v-bind="attrs"
        :title="$t('emailConnector.contacts.menu.tooltip')"
        icon
        v-on="on">
        <v-icon size="18">
          fas fa-ellipsis-v
        </v-icon>
      </v-btn>
    </template>
    <v-list dense>
      <!-- The wrapper carries the reason, not the item: a disabled Vuetify list
           item takes no pointer events, so a title on it would never be read. -->
      <div :title="shareReason">
        <v-list-item
          :disabled="working || !selectionShareable"
          @click="$emit('send-selection-email')">
          <v-list-item-icon class="me-2 my-auto">
            <v-icon size="16">fas fa-paper-plane</v-icon>
          </v-list-item-icon>
          <v-list-item-title>{{ $t('emailConnector.contacts.select.sendByEmail') }}</v-list-item-title>
        </v-list-item>
      </div>
      <div v-if="chatDeployed" :title="shareReason">
        <v-list-item
          :disabled="working || !selectionShareable"
          @click="$emit('send-selection-chat')">
          <v-list-item-icon class="me-2 my-auto">
            <v-icon size="16">fas fa-comments</v-icon>
          </v-list-item-icon>
          <v-list-item-title>{{ $t('emailConnector.contacts.select.sendByChat') }}</v-list-item-title>
        </v-list-item>
      </div>
      <div :title="favoriteReason">
        <v-list-item
          :disabled="working || favoriteState === 'mixed' || favoriteState === 'unknown'"
          @click="$emit('toggle-selection-favorite')">
          <v-list-item-icon class="me-2 my-auto">
            <!-- The mailbox's own convention: the icon is the ACTION, so an
                 already-starred selection offers the hollow star. -->
            <v-icon size="16">{{ favoriteState === 'all' && 'far fa-star' || 'fas fa-star' }}</v-icon>
          </v-list-item-icon>
          <v-list-item-title>{{ favoriteLabel }}</v-list-item-title>
        </v-list-item>
      </div>
      <div :title="exportReason">
        <v-list-item
          :disabled="!selectionExportable || !selectionShareable"
          @click="$emit('export-selection')">
          <v-list-item-icon class="me-2 my-auto">
            <v-icon size="16">fas fa-file-export</v-icon>
          </v-list-item-icon>
          <v-list-item-title>{{ $t('emailConnector.contacts.select.export') }}</v-list-item-title>
        </v-list-item>
      </div>
    </v-list>
  </v-menu>
</template>

<script>
import {MAX_EXPORT_IDS} from '../../js/EmailConnectorContactsService.js';

export default {
  props: {
    // Whether the selection fits one export URL, whose ids ride in the query
    // string; past the cap the whole-store export is the right tool.
    selectionExportable: {
      type: Boolean,
      default: false,
    },
    // What the ticked stars have in common: 'all', 'none', 'mixed', 'unknown'.
    // Mixed offers nothing, because neither "add" nor "remove" is then the
    // thing the user asked for.
    selectionFavoriteState: {
      type: String,
      default: 'unknown',
    },
    // Whether every ticked row is one the drawer still holds. Each of these
    // actions builds ONE file out of those rows, so an unresolved tick would
    // quietly leave somebody out of it.
    selectionShareable: {
      type: Boolean,
      default: false,
    },
    // Whether the chat add-on is deployed.
    chatDeployed: {
      type: Boolean,
      default: false,
    },
    // Whether a bulk call is in flight.
    working: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    /**
     * The favorite state, under a shorter name than the prop's.
     *
     * @returns {string} 'all', 'none', 'mixed' or 'unknown'
     */
    favoriteState() {
      return this.selectionFavoriteState;
    },
    /**
     * What the star entry offers: the state the selection is NOT in, which is
     * the only unambiguous answer when they all agree on one.
     *
     * @returns {string} the localized label
     */
    favoriteLabel() {
      return this.favoriteState === 'all' && this.$t('emailConnector.contacts.select.removeFavorite')
        || this.$t('emailConnector.contacts.select.addFavorite');
    },
    /**
     * Why the star entry is greyed, on hover — empty when it is not.
     *
     * @returns {string} the localized reason, or an empty string
     */
    favoriteReason() {
      return this.favoriteState === 'mixed' && this.$t('emailConnector.contacts.select.mixedFavorites') || '';
    },
    /**
     * Why the two sharing entries are greyed, on hover — empty when they are
     * not.
     *
     * @returns {string} the localized reason, or an empty string
     */
    shareReason() {
      return !this.selectionShareable && this.$t('emailConnector.contacts.select.notShareable') || '';
    },
    /**
     * Why the export entry is greyed, on hover — the count first, since that is
     * the one the user can act on by ticking fewer rows.
     *
     * @returns {string} the localized reason, or an empty string
     */
    exportReason() {
      return !this.selectionExportable && this.$t('emailConnector.contacts.select.tooManyToExport', [MAX_EXPORT_IDS])
        || this.shareReason;
    },
  },
};
</script>
