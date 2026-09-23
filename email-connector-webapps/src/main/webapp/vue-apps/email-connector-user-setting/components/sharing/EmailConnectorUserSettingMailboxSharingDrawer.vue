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
  <!-- Mailbox sharing (EXO-90559): both sides of delegation in one drawer, one tab each
       -- who can open YOUR mailbox, and the mailboxes shared with you. Every way in that
       used to open one of the two drawers still works and lands on its tab: the
       'open-email-sharing-drawer' event on the first, 'open-email-shared-with-me-drawer'
       (the mailbox switcher's "Manage shared mailboxes") on the second. -->
  <exo-drawer
    id="userSettingMailboxSharingDrawer"
    ref="mailboxSharingDrawer"
    v-model="drawer"
    right
    allow-expand
    @opened="onOpened"
    @closed="close">
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.sharing.title') }}</span>
    </template>
    <template #titleIcons>
      <!-- Absent right, absent control: a mail server that cannot share offers no
           Share button at all, and it belongs to the first tab only. -->
      <v-btn
        v-if="tab === 0 && canShare"
        :title="$t('UserSettings.emailConnector.sharing.share')"
        icon
        @click="openInvite">
        <v-icon size="18">fas fa-plus</v-icon>
      </v-btn>
    </template>
    <template v-if="drawer" #content>
      <v-tabs
        v-model="tab"
        grow
        @change="showTab">
        <v-tab>{{ $t('UserSettings.emailConnector.sharing.tab.mine') }}</v-tab>
        <v-tab>
          {{ $t('UserSettings.emailConnector.sharing.tab.sharedWithMe') }}
          <!-- The invitations waiting for an answer, on the tab itself, so they are
               seen from the first tab too. -->
          <v-chip
            v-if="pendingCount"
            :aria-label="$t('UserSettings.emailConnector.sharedWithMe.pending', { 0: pendingCount })"
            color="primary"
            class="ms-2 px-2"
            x-small>
            {{ pendingCount }}
          </v-chip>
        </v-tab>
      </v-tabs>
      <!-- Both tabs stay mounted while the drawer is open, so a confirmation opened in
           one survives a look at the other; each loads the first time it is shown. -->
      <email-connector-user-setting-sharing-drawer
        v-show="tab === 0"
        ref="mine"
        @can-share="canShare = $event" />
      <email-connector-user-setting-shared-with-me-drawer
        v-show="tab === 1"
        ref="sharedWithMe" />
    </template>
    <template #footer>
      <div class="d-flex align-center">
        <v-spacer />
        <v-btn class="btn" @click="close">
          {{ $t('UserSettings.emailConnector.sharing.drawer.close') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
// The two tabs are this drawer's own parts, registered here: nothing else shows them.
import EmailConnectorUserSettingSharingDrawer from './EmailConnectorUserSettingSharingDrawer.vue';
import EmailConnectorUserSettingSharedWithMeDrawer from './EmailConnectorUserSettingSharedWithMeDrawer.vue';

/** The refs of the tabs, in the order they are shown. */
const TABS = ['mine', 'sharedWithMe'];

export default {
  components: {
    'email-connector-user-setting-sharing-drawer': EmailConnectorUserSettingSharingDrawer,
    'email-connector-user-setting-shared-with-me-drawer': EmailConnectorUserSettingSharedWithMeDrawer,
  },
  data: () => ({
    drawer: false,
    tab: 0,
    canShare: false,
    pendingCount: 0,
    // The tabs already loaded since the drawer opened.
    shown: [],
  }),
  created() {
    this.$root.$on('open-email-sharing-drawer', this.openMine);
    this.$root.$on('open-email-shared-with-me-drawer', this.openSharedWithMe);
    this.$root.$on('email-delegations-updated', this.onDelegationsUpdated);
  },
  beforeDestroy() {
    this.$root.$off('open-email-sharing-drawer', this.openMine);
    this.$root.$off('open-email-shared-with-me-drawer', this.openSharedWithMe);
    this.$root.$off('email-delegations-updated', this.onDelegationsUpdated);
  },
  methods: {
    /**
     * Opens the drawer on "Who can open mine".
     *
     * @returns {void}
     */
    openMine() {
      this.open(0);
    },
    /**
     * Opens the drawer on "Shared with me".
     *
     * @returns {void}
     */
    openSharedWithMe() {
      this.open(1);
    },
    /**
     * Opens the drawer on a tab. The tabs are rendered with the drawer's content, so the
     * tab is shown once they exist.
     *
     * @param {Number} index the tab to show
     * @returns {void}
     */
    open(index) {
      // A fresh open recreates both tabs; one arriving while the drawer is open keeps
      // the loaded tabs, and the Share button that goes with the first.
      if (!this.drawer) {
        this.shown = [];
        this.canShare = false;
      }
      this.tab = index;
      this.drawer = true;
      this.$refs.mailboxSharingDrawer.open();
      this.readPendingCount();
      // Already open: the tabs exist and the requested one is shown now. Otherwise
      // exo-drawer renders its content only once it is open, which is what onOpened
      // waits for -- the refs do not exist before.
      this.$nextTick(() => this.showTab(index));
    },
    /**
     * Shows the current tab once the drawer is open and its content rendered: on a
     * first open the tabs do not exist before this point.
     *
     * @returns {void}
     */
    onOpened() {
      this.$nextTick(() => this.showTab(this.tab));
    },
    /**
     * Re-reads the pending count while the drawer is open; a closed drawer shows no
     * count, so its own close does not cost a request.
     *
     * @returns {void}
     */
    onDelegationsUpdated() {
      if (this.drawer) {
        this.readPendingCount();
      }
    },
    /**
     * Shows a tab, loading it the first time it is shown since the drawer opened:
     * the first tab asks the mail server who has access, the second walks it for new
     * shares, and neither is worth doing for a tab nobody looks at.
     *
     * @param {Number} index the tab
     * @returns {void}
     */
    showTab(index) {
      const ref = TABS[index];
      const panel = ref && this.$refs[ref];
      if (!panel || this.shown.includes(ref)) {
        return;
      }
      this.shown.push(ref);
      panel.open();
    },
    /**
     * Reads how many invitations wait for an answer, for the second tab's label.
     * Without discovery, so it costs no connection to the mail server; failing is
     * silent and shows no number rather than a wrong one.
     *
     * @returns {void}
     */
    readPendingCount() {
      this.$emailConnectorUserSettingService.getReceivedDelegations(false)
        .then(rows => this.pendingCount = (rows || []).filter(row => row.status === 'PENDING').length)
        .catch(() => this.pendingCount = 0);
    },
    /**
     * Opens the second-level drawer that picks a person and a preset. This drawer
     * stays open behind it, as the folders drawer does for its name drawer.
     *
     * @returns {void}
     */
    openInvite() {
      this.$root.$emit('open-email-sharing-invite-drawer');
    },
    /**
     * Closes the drawer and tells the settings row to re-read its summary.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
      this.$refs.mailboxSharingDrawer.close();
      this.$root.$emit('email-delegations-updated');
    },
  },
};
</script>
