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
  <!-- Nothing ticked yet, nothing to offer: an enabled-looking action that
       would refuse the press is worse than an empty header, and the count in
       the title already says the mode is on. -->
  <div v-else-if="tickedCount" class="d-flex align-center">
    <v-btn
      :title="$t('emailConnector.contacts.select.publish')"
      :loading="publishing"
      :disabled="publishing"
      icon
      @click="$emit('publish')">
      <v-icon size="18" class="icon-default-color">
        fas fa-cloud-upload-alt
      </v-icon>
    </v-btn>
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
    publishable: {
      type: Boolean,
      default: false,
    },
    // Whether a vCard import is under way, greying the menu's import entry.
    importing: {
      type: Boolean,
      default: false,
    },
  },
};
</script>
