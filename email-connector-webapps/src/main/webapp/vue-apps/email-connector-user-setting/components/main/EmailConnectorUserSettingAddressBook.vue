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
  <!-- The address book (CardDAV): whether it syncs, how the last sync went, and whether
       contacts added here are published to it. Its own component since EXO-90559, when
       it moved under Advanced settings. -->
  <div v-if="carddavAvailable">
    <!-- Shown only when the bound provider actually has an address book, because a
         switch for something that can never work is worse than no switch, and off by
         default since each enabled user costs the server a request per sync period. -->
    <v-list-item>
      <v-list-item-content>
        <v-list-item-title class="text-color">
          {{ $t('UserSettings.emailConnector.addressBook.title') }}
        </v-list-item-title>
        <v-list-item-subtitle>
          {{ $t('UserSettings.emailConnector.addressBook.description') }}
        </v-list-item-subtitle>
        <v-list-item-subtitle
          v-if="carddavEnabled && addressBookStatus"
          class="mt-1">
          {{ addressBookStatus }}
        </v-list-item-subtitle>
        <!-- The outbound side of the same line: publishes the server has
             not taken yet. Two counts because they mean different things --
             waiting is "will happen by itself after the next sync", parked
             is "gave up, retry it from the contact" -- and folding them
             into one number would hide exactly the entries needing a person. -->
        <v-list-item-subtitle
          v-if="carddavEnabled && pendingPublishCount"
          class="mt-1">
          {{ $t('UserSettings.emailConnector.addressBook.pendingPublish', {0: pendingPublishCount}) }}
        </v-list-item-subtitle>
        <v-list-item-subtitle
          v-if="carddavEnabled && parkedPublishCount"
          class="mt-1 error--text">
          {{ $t('UserSettings.emailConnector.addressBook.parkedPublish', {0: parkedPublishCount}) }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action class="d-flex flex-row align-center">
        <v-btn
          v-if="carddavEnabled"
          :loading="syncingAddressBook"
          :title="$t('UserSettings.emailConnector.addressBook.syncNow')"
          icon
          @click="syncAddressBook">
          <v-icon size="18">
            fas fa-sync
          </v-icon>
        </v-btn>
        <v-switch
          v-model="carddavEnabled"
          :loading="savingAddressBook"
          class="ms-2"
          @change="saveAddressBook" />
      </v-list-item-action>
    </v-list-item>
    <!-- The outbound direction, and only once the inbound one is on: there
         is nowhere to publish TO until an address book is bound, so the
         switch would be a promise the connector cannot keep. Indented
         under it, like the notification categories under their own switch,
         because it is that setting's detail rather than a setting of its
         own. Off by default and left off by an upgrade -- turning writing
         on for everybody is a decision somebody takes, not one a release
         takes for them. -->
    <v-list-item v-if="carddavEnabled">
      <v-list-item-content class="ps-4">
        <v-list-item-subtitle>
          {{ $t('UserSettings.emailConnector.addressBook.autoPublish') }}
        </v-list-item-subtitle>
        <v-list-item-subtitle class="mt-1">
          {{ $t('UserSettings.emailConnector.addressBook.autoPublish.description') }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action>
        <v-switch
          v-model="carddavAutoPublish"
          :loading="savingAutoPublish"
          @change="saveAutoPublish" />
      </v-list-item-action>
    </v-list-item>
  </div>
</template>

<script>
export default {
  props: {
    userEmailSetting: {
      type: Object,
      default: null,
    },
  },
  data: () => ({
    carddavAvailable: false,
    carddavEnabled: false,
    carddavAutoPublish: false,
    savingAddressBook: false,
    savingAutoPublish: false,
    syncingAddressBook: false,
    addressBookSyncState: null,
    publishQueue: null,
  }),
  computed: {
    /**
     * The one-line account of the last sync, or nothing when it has never run.
     *
     * @returns {string} the localized status
     */
    addressBookStatus() {
      const status = this.addressBookSyncState?.status;
      if (!status) {
        return '';
      }
      if (status === 'BLOCKED') {
        return this.$t('UserSettings.emailConnector.addressBook.status.blocked');
      }
      if (status === 'FAILURE') {
        return this.$t('UserSettings.emailConnector.addressBook.status.failed');
      }
      return this.$t('UserSettings.emailConnector.addressBook.status.ok');
    },
    /**
     * Publishes still waiting for the next successful sync — the entries the
     * queue will retry by itself.
     *
     * @returns {number} how many contacts wait to publish
     */
    pendingPublishCount() {
      return (this.publishQueue?.entries || []).filter(entry => !entry.parked).length;
    },
    /**
     * Publishes the queue gave up on — still local, still retryable from the
     * contact itself, but nothing automatic will touch them again.
     *
     * @returns {number} how many contacts could not be published
     */
    parkedPublishCount() {
      return (this.publishQueue?.entries || []).filter(entry => entry.parked).length;
    },
  },
  watch: {
    userEmailSetting: {
      immediate: true,
      handler() {
        this.initFromSetting();
      },
    },
  },
  methods: {
    /**
     * Shows the address-book preferences the setting carries, and reads the last sync's
     * status when the sync is on.
     *
     * @returns {void}
     */
    initFromSetting() {
      const setting = this.userEmailSetting || {};
      this.carddavAvailable = !!setting.carddavAvailable;
      this.carddavEnabled = !!setting.carddavEnabled;
      // Unset reads as off, exactly as the server resolves it: a settings
      // document written before this preference existed says nothing about it,
      // and "nothing" must never read as yes on a switch that writes outwards.
      this.carddavAutoPublish = !!setting.carddavAutoPublish;
      if (this.carddavEnabled) {
        this.readAddressBookStatus();
      }
    },
    /**
     * Reads how the last address-book sync went, for the line under the switch.
     * Failing is silent: a status we cannot read is not worth an error banner.
     *
     * @returns {void}
     */
    readAddressBookStatus() {
      this.$emailConnectorCommonService.getAddressBookSyncStatus()
        .then(state => this.addressBookSyncState = state)
        .catch(() => this.addressBookSyncState = null);
      // Same silence rule as the status: a queue we cannot read is not worth a
      // banner, and an unreadable answer shows nothing rather than a wrong "0".
      this.$emailConnectorCommonService.getAddressBookPublishQueue()
        .then(queue => this.publishQueue = queue)
        .catch(() => this.publishQueue = null);
    },
    /**
     * Pulls the address book now, rather than waiting for the next scheduled run.
     *
     * @returns {void}
     */
    syncAddressBook() {
      this.syncingAddressBook = true;
      this.$emailConnectorCommonService.syncAddressBook()
        // What the run DID is in the state it leaves behind, not in whether the
        // request came back. Reporting success because the call returned told
        // people their address book had synced when it had in fact failed --
        // which is worse than any error message.
        .then(() => this.$emailConnectorCommonService.getAddressBookSyncStatus())
        .then(state => {
          this.addressBookSyncState = state;
          const failed = state?.status === 'FAILURE' || state?.status === 'BLOCKED';
          const message = failed && this.$t('UserSettings.emailConnector.addressBook.syncError')
            || this.$t('UserSettings.emailConnector.addressBook.synced');
          this.$root.$emit('alert-message', message, failed && 'error' || 'success');
          // A successful run just drained the publish queue, so the waiting
          // count shown here is stale the moment the toast appears.
          this.$emailConnectorCommonService.getAddressBookPublishQueue()
            .then(queue => this.publishQueue = queue)
            .catch(() => this.publishQueue = null);
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.addressBook.syncError'), 'error'))
        .finally(() => this.syncingAddressBook = false);
    },
    /**
     * Stores whether the address book should sync. That is the whole setting: the
     * sync signs in with the mailbox's own credentials, so there is nothing else
     * to ask for.
     *
     * @returns {void}
     */
    saveAddressBook() {
      this.savingAddressBook = true;
      this.$emailConnectorCommonService.updateAddressBookBinding({carddavEnabled: this.carddavEnabled})
        .then(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success'))
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.error'), 'error'))
        .finally(() => this.savingAddressBook = false);
    },
    /**
     * Stores whether contacts added through the form should reach the address
     * book on their own. Its own call, not folded into the binding above: that
     * one releases the contacts of the book being left, and a preference about
     * future saves must not set that in motion.
     *
     * @returns {void}
     */
    saveAutoPublish() {
      this.savingAutoPublish = true;
      this.$emailConnectorCommonService.updateAddressBookAutoPublish({carddavAutoPublish: this.carddavAutoPublish})
        .then(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success'))
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.error'), 'error'))
        .finally(() => this.savingAutoPublish = false);
    },
  },
};
</script>
