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
  <!-- The header's actions, which is where what the drawer is FOR right now is
       said: browsing offers adding and the whole-store menu, picking offers the
       one thing a selection is for. One component for both, mounted by whichever
       header slot is live (narrow or expanded), so the two layouts cannot drift
       apart the way two copies of an inline bar already did. -->
  <div v-if="!selectMode" class="d-flex align-center">
    <v-btn
      :title="$t('emailConnector.contacts.add')"
      icon
      @click="$emit('add')">
      <v-icon size="18">
        fas fa-user-plus
      </v-icon>
    </v-btn>
    <email-connector-contacts-transfer-menu
      :importing="importing"
      :publishable="publishable"
      @import="$emit('import', $event)"
      @export="$emit('export')"
      @bulk-publish="$emit('bulk-publish')" />
  </div>
  <!-- The actions a selection can take, which is what every ticked contact has
       in common -- not what some of them could manage. Publishing refuses a
       mixed selection whole on the server, so offering it half-lit here would
       promise something the next screen refuses; disabled with the reason on
       hover is the honest shape, and it generalises to the actions that come
       after this one.
       Three buttons and a menu, not seven buttons: writing to the selection,
       saving it and removing it are what people came for, and a narrow drawer
       header holding a count and seven icons is a row of guesses. The rest
       keeps its words in the overflow, where a label says what an icon could
       only hint. -->
  <div v-else-if="tickedCount" class="d-flex align-center">
    <v-btn
      :title="selectionComposable
        ? $t('emailConnector.contacts.select.composeTo')
        : $t('emailConnector.contacts.select.notComposable')"
      :disabled="!selectionComposable"
      icon
      @click="$emit('compose-selection')">
      <v-icon size="18" class="icon-default-color">
        fas fa-envelope
      </v-icon>
    </v-btn>
    <v-btn
      v-if="publishable"
      :title="selectionPublishable
        ? $t('emailConnector.contacts.select.publish')
        : $t('emailConnector.contacts.select.notPublishable')"
      :loading="publishing"
      :disabled="publishing || !selectionPublishable"
      icon
      @click="$emit('publish')">
      <v-icon size="18" class="icon-default-color">
        fas fa-cloud-upload-alt
      </v-icon>
    </v-btn>
    <v-btn
      :title="selectionDeletable
        ? $t('emailConnector.contacts.select.delete')
        : $t('emailConnector.contacts.select.notDeletable')"
      :loading="deleting"
      :disabled="working || !selectionDeletable"
      icon
      @click="$emit('delete-selection')">
      <v-icon size="18" class="error--text">
        fas fa-trash
      </v-icon>
    </v-btn>
    <email-connector-contacts-selection-menu
      :selection-exportable="selectionExportable"
      :selection-shareable="selectionShareable"
      :selection-favorite-state="selectionFavoriteState"
      :chat-deployed="chatDeployed"
      :working="working"
      @export-selection="$emit('export-selection')"
      @send-selection-email="$emit('send-selection-email')"
      @send-selection-chat="$emit('send-selection-chat')"
      @toggle-selection-favorite="$emit('toggle-selection-favorite')" />
  </div>
</template>

<script>
export default {
  props: {
    // Whether the drawer is picking contacts rather than browsing them.
    selectMode: {
      type: Boolean,
      default: false,
    },
    // How many rows are ticked, which is what decides the publish action is
    // worth showing at all.
    tickedCount: {
      type: Number,
      default: 0,
    },
    // Whether a publish is in flight, so the action says so rather than
    // inviting a second press that would queue the same cards twice.
    publishing: {
      type: Boolean,
      default: false,
    },
    // Whether this user has a reachable address book; passed through to the
    // menu, which hides its bulk-publish entry without one.
    // Whether EVERY ticked contact can be published, which is what decides the
    // action -- the server refuses a mixed selection whole, so a half-offered
    // action here would be a promise it breaks.
    selectionPublishable: {
      type: Boolean,
      default: false,
    },
    publishable: {
      type: Boolean,
      default: false,
    },
    // Whether a vCard import is under way, greying the menu's import entry.
    importing: {
      type: Boolean,
      default: false,
    },
    // Whether EVERY ticked contact has an address. Some of them is not enough:
    // a composer opened with three of five recipients silently drops two people
    // the user meant to write to.
    selectionComposable: {
      type: Boolean,
      default: false,
    },
    // Whether the selection fits one export URL, whose ids ride in the query
    // string.
    selectionExportable: {
      type: Boolean,
      default: false,
    },
    // Whether every ticked row is one we still hold. Exporting and sharing all
    // build one file out of those rows, and a file quietly missing somebody is
    // the one outcome they may not have.
    selectionShareable: {
      type: Boolean,
      default: false,
    },
    // Whether NO ticked contact is an address-book row. Deleting one of those
    // would remove it here while it lives on at the provider, and the next sync
    // would bring it back -- which reads exactly like a bug.
    selectionDeletable: {
      type: Boolean,
      default: false,
    },
    // What the ticked stars have in common: 'all', 'none', 'mixed', 'unknown'.
    selectionFavoriteState: {
      type: String,
      default: 'unknown',
    },
    // Whether the chat is deployed, hence whether a selection can be sent into
    // a conversation at all.
    chatDeployed: {
      type: Boolean,
      default: false,
    },
    // Whether ANY bulk call is in flight, so a second press cannot run the same
    // writes twice -- whichever action started it.
    working: {
      type: Boolean,
      default: false,
    },
    // Whether the running one is the delete, which is the only action that
    // spins its own button: a trash spinning while a star is being written
    // would say the wrong thing about what is happening.
    deleting: {
      type: Boolean,
      default: false,
    },
  },
};
</script>
