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
    <!-- Print, Show original and Download as .eml (EXO-90842): reads, offered wherever the
         message can be read, a shared mailbox's included -- the server answers whether
         this reader may. The last two need the message on the mail server. -->
    <v-list-item
      class="ps-2 pe-3 height-auto"
      @click="printEmail">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          fa-print
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.print.label') }}
      </span>
    </v-list-item>
    <template v-if="hasRawSource">
      <v-list-item
        class="ps-2 pe-3 height-auto"
        @click="showSource">
        <v-sheet
          class="d-flex"
          width="28"
          height="36">
          <v-icon
            class="icon-default-color mx-auto"
            size="16">
            fa-code
          </v-icon>
        </v-sheet>
        <span>
          {{ $t('emailConnector.mailBox.source.label') }}
        </span>
      </v-list-item>
      <v-list-item
        class="ps-2 pe-3 height-auto"
        @click="downloadEml">
        <v-sheet
          class="d-flex"
          width="28"
          height="36">
          <v-icon
            class="icon-default-color mx-auto"
            size="16">
            fa-file-download
          </v-icon>
        </v-sheet>
        <span>
          {{ $t('emailConnector.mailBox.source.download') }}
        </span>
      </v-list-item>
    </template>
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
    <!-- "Create a filter from this mail" (EXO-90654): the filters drawer opens on the
         rule this mail suggests. Never on a row of a mailbox somebody shared with the
         user: rules run on the user's own mailbox, and a rule made from someone else's
         mail would not mean what the click meant. -->
    <v-list-item
      v-if="canCreateFilter"
      class="ps-2 pe-3 height-auto"
      @click.stop="createFilter">
      <v-sheet
        class="d-flex"
        width="28"
        height="36">
        <v-icon
          class="icon-default-color mx-auto"
          size="16">
          fa-filter
        </v-icon>
      </v-sheet>
      <span>
        {{ $t('emailConnector.mailBox.filters.createFromMail') }}
      </span>
    </v-list-item>
  </v-list>
</template>

<script>
import { OPEN_FILTERS_DRAWER_EVENT, ruleFromMail } from '../../../email-connector-user-setting/js/EmailConnectorFilters.js';
import { canCreateFilterFrom } from '../../js/EmailConnectorMailFilters.js';
import { OPEN_SOURCE_DRAWER_EVENT, downloadRawEmail, hasRawSource, printEmails, printLabels, rawEmailErrorKey } from '../../js/EmailConnectorRawEmail.js';

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
  computed: {
    /**
     * Whether "Create a filter from this mail" belongs on this row: a received mail of
     * the user's own mailbox, never a draft, never a shared mailbox's row (EXO-90654).
     *
     * @returns {Boolean} true when offered
     */
    canCreateFilter() {
      return canCreateFilterFrom(this.email);
    },
    /**
     * Whether "Show original" and "Download as .eml" apply: the message is on the mail
     * server (EXO-90842).
     *
     * @returns {Boolean} true when offered
     */
    hasRawSource() {
      return hasRawSource(this.email);
    },
  },
  methods: {
    /**
     * Prints this one message (EXO-90842).
     *
     * @returns {void}
     */
    printEmail() {
      printEmails([this.email], printLabels(this.$t.bind(this)), eXo.env.portal.language)
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.mailBox.print.error'), 'error'));
    },
    /**
     * Opens the "Show original" drawer on this message (EXO-90842).
     *
     * @returns {void}
     */
    showSource() {
      this.$root.$emit(OPEN_SOURCE_DRAWER_EVENT, this.email);
    },
    /**
     * Downloads this message as a .eml file (EXO-90842).
     *
     * @returns {void}
     */
    downloadEml() {
      downloadRawEmail(this.email)
        .catch(error => this.$root.$emit('alert-message', this.$t(rawEmailErrorKey(error)), 'error'));
    },
    /**
     * Opens the filters drawer on the rule this mail suggests (EXO-90654).
     *
     * @returns {void}
     */
    createFilter() {
      this.$root.$emit(OPEN_FILTERS_DRAWER_EVENT, { prefill: ruleFromMail(this.email) });
    },
    openForwardEmailDrawer() {
      this.$root.$emit('open-new-email-drawer', this.email, true);
    },
    openReplyAllEmailDrawer() {
      this.$root.$emit('open-new-email-drawer', this.email, false, true);
    }
  }
};
</script>