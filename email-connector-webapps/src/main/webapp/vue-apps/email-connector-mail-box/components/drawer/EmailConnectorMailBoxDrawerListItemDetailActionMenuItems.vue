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
  <v-list class="pa-0">
    <v-list-item
      class="ps-2 pe-3 height-auto"
      @click="openReplyAllEmailDrawer">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          fa-reply-all
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.replyAll.label') }}
      </span>
    </v-list-item>
    <v-list-item
      class="ps-2 pe-3 height-auto"
      @click="openForwardEmailDrawer">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          fa-share
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.list.drawer.detail.forward.label') }}
      </span>
    </v-list-item>
    <!-- The AI actions an administrator has already written for a mail, on the one
         message the reader opened out of the conversation — the same seam the mail
         list's own row menu offers (see EmailConnectorMailBoxDrawerListItemActionMenuItems),
         with the same name, type and params, so an action written once appears in both
         without anything new to define.

         Only inside a thread. This component is also what a conversation of a single
         message renders, and there the drawer's header already carries the very same
         actions on the very same mail (EmailConnectorMailBoxDrawerListItemDetailActions,
         the `EmailDetail` / `email-detail-toolbar` seam) — so unscoped they would be
         offered twice, side by side, on one message. In a thread the header speaks for
         the conversation and this menu is the only way to reach one message of it,
         which is an asymmetry worth keeping rather than flattening.

         Read-only actions only, deliberately. A thread is the one view that mixes
         folders (the reply in SENT, the original in INBOX), and anything that WRITES
         from here must take the folder off the message object it is handed rather than
         off the drawer's folder lookup, which searches the listing and would not find a
         thread message at all (EXO-89367). -->
    <extension-registry-components
      v-if="inThread"
      :params="{
        email,
      }"
      name="Email"
      type="email-menu-action"
      parent-element="div"
      element="div"
      class="my-auto" />
  </v-list>
</template>

<script>
export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
    // Whether this message is one of several in the conversation on screen. See the
    // comment on the extension seam above for why the seam is scoped to that case.
    inThread: {
      type: Boolean,
      default: false,
    },
  },
  methods: {
    openForwardEmailDrawer() {
      this.$root.$emit('open-new-email-drawer', this.email, true);
    },
    openReplyAllEmailDrawer() {
      this.$root.$emit('open-new-email-drawer', this.email, false, true);
    }
  }
};
</script>