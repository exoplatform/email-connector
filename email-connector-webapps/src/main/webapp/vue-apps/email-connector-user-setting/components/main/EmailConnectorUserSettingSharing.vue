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
  <!-- Mailbox sharing (EXO-90503, EXO-90559): one row for both sides of delegation --
       who can open YOUR mailbox, and the mailboxes shared with you -- summarised in one
       line and managed in one drawer with a tab each. The invitations waiting for an
       answer get their own line: they are the only part of this that asks something of
       the user. -->
  <v-list-item class="height-auto">
    <v-list-item-content>
      <v-list-item-title class="text-color">
        {{ $t('UserSettings.emailConnector.sharing.title') }}
      </v-list-item-title>
      <v-list-item-subtitle class="text-wrap">
        {{ summary }}
      </v-list-item-subtitle>
      <v-list-item-subtitle v-if="pendingCount" class="caption primary--text mt-1">
        {{ $t('UserSettings.emailConnector.sharedWithMe.pending', { 0: pendingCount }) }}
      </v-list-item-subtitle>
    </v-list-item-content>
    <v-list-item-action>
      <v-btn
        icon
        :title="$t('UserSettings.emailConnector.sharing.edit.tooltip')"
        @click="open">
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  data: () => ({
    // Null until known: an unread count shows nothing rather than a wrong "nobody".
    granteeCount: null,
    acceptedCount: null,
    pendingCount: 0,
  }),
  computed: {
    /**
     * The row's one-line account of both sides, from what could be read. A side that
     * could not be read (or a mail server that cannot share) is left out rather than
     * shown as zero.
     *
     * @returns {String} the localized summary
     */
    summary() {
      const sharedWithMe = this.acceptedCount
        ? this.plural('UserSettings.emailConnector.sharing.summary.sharedWithMe', this.acceptedCount)
        : null;
      // Who can open the user's mailbox is known only once the drawer read it (see
      // readCounters): until then the row speaks of the other side alone, in whole
      // sentences.
      if (this.granteeCount === null) {
        if (sharedWithMe) {
          return sharedWithMe;
        }
        return this.acceptedCount === 0
          ? this.$t('UserSettings.emailConnector.sharedWithMe.none')
          : this.$t('UserSettings.emailConnector.sharing.description');
      }
      const mine = this.plural('UserSettings.emailConnector.sharing.summary.mine', this.granteeCount);
      // The received side unread: the owner side alone, never a zero nobody read.
      if (this.acceptedCount === null) {
        return mine;
      }
      // Both sides known and both empty: one plain sentence rather than two negatives.
      if (this.granteeCount === 0 && this.acceptedCount === 0 && !this.pendingCount) {
        return this.$t('UserSettings.emailConnector.sharing.summary.empty');
      }
      return [
        mine,
        sharedWithMe || this.$t('UserSettings.emailConnector.sharing.summary.sharedWithMe.none'),
      ].join(' · ');
    },
  },
  created() {
    this.readCounters();
    // The drawer is what this row summarises: it says when it changed something.
    this.$root.$on('email-delegations-updated', this.readCounters);
    this.$root.$on('email-sharing-grantees-read', this.setGranteeCount);
  },
  beforeDestroy() {
    this.$root.$off('email-delegations-updated', this.readCounters);
    this.$root.$off('email-sharing-grantees-read', this.setGranteeCount);
  },
  methods: {
    /**
     * Opens the drawer on the tab that needs the user: "Shared with me" while an
     * invitation waits for an answer, "Who can open mine" otherwise.
     *
     * @returns {void}
     */
    open() {
      this.$root.$emit(this.pendingCount ? 'open-email-shared-with-me-drawer' : 'open-email-sharing-drawer');
    },
    /**
     * A counted sentence: the ".none", ".one" or plain key for zero, one or more.
     *
     * @param {String} key the sentence's key
     * @param {Number} count the count
     * @returns {String} the localized sentence
     */
    plural(key, count) {
      if (!count) {
        return this.$t(`${key}.none`);
      }
      return count === 1 ? this.$t(`${key}.one`) : this.$t(key, { 0: count });
    },
    /**
     * Reads how many shares wait for an answer and how many are in use, WITHOUT
     * discovery, so it costs no connection to the mail server. Who can open the user's
     * mailbox is deliberately not read here: that list exists only on the mail server,
     * and reading it reconciles eXo's rows with it (creating, updating and revoking
     * them), which must stay the owner's act of opening the drawer, not a side effect
     * of viewing the settings. The drawer's read fills it in (setGranteeCount).
     * Failing is silent: an unreadable count is not worth an error banner over the
     * whole settings screen, and the drawer says what happened when it is opened.
     *
     * @returns {void}
     */
    readCounters() {
      this.$emailConnectorUserSettingService.getReceivedDelegations(false)
        .then(delegations => {
          const rows = delegations || [];
          this.pendingCount = rows.filter(row => row.status === 'PENDING').length;
          this.acceptedCount = rows.filter(row => row.status === 'ACCEPTED').length;
        })
        .catch(() => null);
    },
    /**
     * Takes the count of people who can open the user's mailbox from the drawer's own
     * read of it.
     *
     * @param {Number} count the count, null when the mail server cannot share
     * @returns {void}
     */
    setGranteeCount(count) {
      this.granteeCount = typeof count === 'number' ? count : null;
    },
  },
};
</script>
