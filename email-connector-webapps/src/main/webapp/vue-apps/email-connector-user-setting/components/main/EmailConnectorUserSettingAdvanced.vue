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
  <!-- Advanced settings (EXO-90559): the settings set once and rarely touched again,
       collapsed by default under a header that says what is inside, so the screen
       opens on what people change. Reset & re-sync comes last and set apart: it is
       the one control here that throws something away. -->
  <div>
    <v-divider class="mx-4" />
    <!-- A real button, not a clickable row: it is reached with the keyboard and
         announces whether the section is open. Wrapped as a list item because the
         settings screen is a list, and a bare button in it breaks what a screen reader
         announces. -->
    <div role="listitem">
      <button
        type="button"
        class="d-flex align-center width-full px-4 py-3 text-start"
        :aria-expanded="expanded ? 'true' : 'false'"
        aria-controls="emailConnectorAdvancedSettings"
        @click="toggle">
        <span class="d-flex flex-column flex-grow-1">
          <span class="text-color">{{ $t('UserSettings.emailConnector.advanced.title') }}</span>
          <span class="caption text-sub-title text-wrap">{{ summary }}</span>
        </span>
        <v-icon size="16" class="icon-default-color ms-2">
          {{ expanded ? 'fa-chevron-up' : 'fa-chevron-down' }}
        </v-icon>
      </button>
    </div>
    <div v-show="expanded" id="emailConnectorAdvancedSettings">
      <email-connector-user-setting-address-book :user-email-setting="userEmailSetting" />
      <!-- The user's own mail folders, and which of them are mirrored here: the
           discovered list with a switch each, in the settings rather than in the
           mailbox's menu, because choosing what to mirror is a preference set once,
           not an action taken on mail. -->
      <email-connector-user-setting-folders />
      <email-connector-user-setting-read-receipts />
      <v-divider class="mx-4 mt-4" />
      <v-list-item class="mt-2">
        <v-list-item-content>
          <v-list-item-title class="text-color">
            {{ $t('UserSettings.emailConnector.reset.title') }}
          </v-list-item-title>
          <v-list-item-subtitle class="text-wrap">
            {{ $t('UserSettings.emailConnector.reset.description') }}
          </v-list-item-subtitle>
        </v-list-item-content>
        <v-list-item-action>
          <v-btn
            :loading="resetting"
            :disabled="syncInProgress"
            color="error"
            outlined
            small
            @click="$refs.resetConfirmDialog.open()">
            {{ $t('UserSettings.emailConnector.reset.button') }}
          </v-btn>
        </v-list-item-action>
      </v-list-item>
    </div>
    <exo-confirm-dialog
      ref="resetConfirmDialog"
      :title="$t('UserSettings.emailConnector.reset.confirm.title')"
      :message="$t('UserSettings.emailConnector.reset.confirm.message')"
      :ok-label="$t('UserSettings.emailConnector.reset.confirm.ok')"
      :cancel-label="$t('UserSettings.emailConnector.reset.confirm.cancel')"
      @ok="doReset" />
  </div>
</template>

<script>
// Where this browser remembers whether the section was left open.
const EXPANDED_KEY = 'emailConnector.userSetting.advancedExpanded';

export default {
  props: {
    userEmailSetting: {
      type: Object,
      default: null,
    },
  },
  data: () => ({
    expanded: false,
    resetting: false,
  }),
  computed: {
    /**
     * @returns {Boolean} whether a synchronisation is running, which a reset must wait for
     */
    syncInProgress() {
      return this.userEmailSetting?.emailSyncStatus === 'IN_PROGRESS';
    },
    /**
     * What the section holds, said on its header. The address book is named only when
     * the mail server has one, as its row is shown only then.
     *
     * @returns {String} the localized summary
     */
    summary() {
      return this.userEmailSetting?.carddavAvailable
        ? this.$t('UserSettings.emailConnector.advanced.summary')
        : this.$t('UserSettings.emailConnector.advanced.summary.noAddressBook');
    },
  },
  created() {
    this.expanded = this.readExpanded();
  },
  methods: {
    /**
     * Opens or closes the section, and remembers it for this browser.
     *
     * @returns {void}
     */
    toggle() {
      this.expanded = !this.expanded;
      try {
        window.localStorage.setItem(EXPANDED_KEY, String(this.expanded));
      } catch (e) {
        // Storage refused (private window, blocked site data): the section still
        // opens, it is just not remembered.
      }
    },
    /**
     * Whether this browser left the section open. Closed when nothing was stored, or
     * when storage cannot be read at all.
     *
     * @returns {Boolean} true when it was left open
     */
    readExpanded() {
      try {
        return window.localStorage.getItem(EXPANDED_KEY) === 'true';
      } catch (e) {
        return false;
      }
    },
    /**
     * Resets the local copy of the mailbox and synchronises it again, once confirmed.
     *
     * @returns {void}
     */
    doReset() {
      this.resetting = true;
      this.$emailConnectorCommonService.resetAndResyncMailbox()
        .then(() => {
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.reset.success'), 'success');
          document.dispatchEvent(new CustomEvent('refresh-user-email-setting'));
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.reset.error'), 'error'))
        .finally(() => this.resetting = false);
    },
  },
};
</script>
