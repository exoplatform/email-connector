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
    <!-- The same row shape as every other setting - title and summary, the control in
         the list item's action slot - so its chevron lines up with the edit icons and
         switches above it (a hand-made full-width button drifted off that column). The
         control is a real button: reached with the keyboard, named by the section's
         title, and it announces whether the section is open. -->
    <v-list-item>
      <v-list-item-content>
        <v-list-item-title class="text-color">
          {{ $t('UserSettings.emailConnector.advanced.title') }}
        </v-list-item-title>
        <v-list-item-subtitle class="text-wrap">
          {{ summary }}
        </v-list-item-subtitle>
      </v-list-item-content>
      <v-list-item-action>
        <v-btn
          :aria-expanded="expanded ? 'true' : 'false'"
          :aria-label="$t('UserSettings.emailConnector.advanced.title')"
          aria-controls="emailConnectorAdvancedSettings"
          icon
          @click="toggle">
          <v-icon size="16" class="icon-default-color">
            {{ expanded ? 'fa-chevron-up' : 'fa-chevron-down' }}
          </v-icon>
        </v-btn>
      </v-list-item-action>
    </v-list-item>
    <div v-show="expanded" id="emailConnectorAdvancedSettings">
      <email-connector-user-setting-address-book :user-email-setting="userEmailSetting" />
      <!-- The user's own mail folders, and which of them are mirrored here: the
           discovered list with a switch each, in the settings rather than in the
           mailbox's menu, because choosing what to mirror is a preference set once,
           not an action taken on mail. -->
      <email-connector-user-setting-folders />
      <email-connector-user-setting-read-receipts />
      <!-- The automatic reply (EXO-90642): a setting of the user's own mailbox, run by
           the mail server. Each of these two rows is self-contained -- it reads its own
           summary and opens a drawer mounted at the app's root -- so it can move in this
           list with no other change. -->
      <email-connector-user-setting-absence />
      <!-- Mail filters (EXO-90652): the rules the mail server runs at delivery, next to
           the automatic reply, the other thing the server does with incoming mail. -->
      <email-connector-user-setting-filters />
      <!-- Mailbox delegation (EXO-90503, EXO-90559): one row for both sides of it, over
           one drawer with a tab each; after the automatic reply, because it is the only
           row here about somebody else's mail. -->
      <email-connector-user-setting-sharing />
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
