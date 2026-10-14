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
  <!-- Taking mail out of somebody else's mailbox is confirmed once per session per
       mailbox, naming the mailbox (delegation plan 7.6). The platform's
       exo-confirm-dialog is followed for its shape; it has no room for the "don't ask
       again" choice, which is what makes this its own dialog. Mounted at the app root,
       like the permanent-delete confirmation: the row menu, the reader and the bulk
       toolbar all lead here. -->
  <v-dialog
    v-model="dialog"
    content-class="uiPopup"
    width="500"
    max-width="100vw"
    persistent>
    <v-card class="elevation-12 transparent">
      <div class="ignore-vuetify-classes popupHeader ClearFix">
        <span class="ignore-vuetify-classes text-title">
          {{ $t(`emailConnector.mailBox.sharedMailbox.confirm.title.${action}`, { 0: ownerName }) }}
        </span>
      </div>
      <v-card-text class="pb-0">
        <p class="mb-2">{{ $t('emailConnector.mailBox.sharedMailbox.confirm.message', { 0: ownerName }) }}</p>
        <v-checkbox
          v-model="dontAskAgain"
          :label="$t('emailConnector.mailBox.sharedMailbox.confirm.dontAskAgain', { 0: ownerName })"
          class="mt-0"
          dense
          hide-details />
      </v-card-text>
      <v-card-actions>
        <v-spacer />
        <button class="ignore-vuetify-classes btn btn-primary me-2" @click="answer(true)">
          {{ $t('emailConnector.mailBox.sharedMailbox.confirm.ok') }}
        </button>
        <button class="ignore-vuetify-classes btn ms-2" @click="answer(false)">
          {{ $t('emailConnector.mailBox.sharedMailbox.confirm.cancel') }}
        </button>
        <v-spacer />
      </v-card-actions>
    </v-card>
  </v-dialog>
</template>

<script>
export default {
  data: () => ({
    dialog: false,
    entry: null,
    action: 'delete',
    // Ticked by default: the plan asks once per session per mailbox, and the box is
    // there for whoever wants to keep being asked.
    dontAskAgain: true,
    resolve: null,
  }),
  computed: {
    /**
     * Whose mailbox the action would take mail out of.
     *
     * @returns {String} the owner's name
     */
    ownerName() {
      return this.entry?.ownerFullName || '';
    },
  },
  created() {
    this.$root.$on('open-shared-mailbox-confirm-popup', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-shared-mailbox-confirm-popup', this.open);
  },
  methods: {
    /**
     * Asks, for one shared mailbox. A question still open is answered "no" first: two
     * actions racing for the dialog must not leave the first one waiting forever.
     *
     * @param {Object} entry the switcher entry of the mailbox
     * @param {String} action delete, archive, junk or move -- what the title names
     * @param {Function} resolve called with true to go ahead, false to leave the mail
     * @returns {void}
     */
    open(entry, action, resolve) {
      if (this.resolve) {
        this.resolve(false);
      }
      this.entry = entry;
      this.action = action || 'delete';
      this.resolve = resolve;
      this.dontAskAgain = true;
      this.dialog = true;
    },
    /**
     * Closes with the user's answer, remembering "don't ask again" only on a yes: a
     * cancelled action has nothing to stop asking about.
     *
     * @param {Boolean} confirmed whether the user went ahead
     * @returns {void}
     */
    answer(confirmed) {
      if (confirmed && this.dontAskAgain) {
        this.$emailConnectorMailBoxService.rememberDestructiveActionConfirmed(this.entry);
      }
      const resolve = this.resolve;
      this.resolve = null;
      this.dialog = false;
      if (resolve) {
        resolve(confirmed);
      }
    },
  },
};
</script>
