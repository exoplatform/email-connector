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
  <!-- The mailboxes other people let you read (EXO-90503, delegation plan 7.2), in the
       four states they can be in: the second tab of the "Mailbox sharing" drawer
       (EXO-90559), which owns the drawer itself and its close. The file keeps its name
       so the drawer's history stays readable. The tab walks the mail server when it is
       first shown, so a share somebody made in their own webmail is offered here --
       proposed, never subscribed on your behalf: an unexpected copy of a colleague's
       mailbox is a surprise nobody wants, and it costs a synchronisation. -->
  <div v-if="active">
    <v-progress-linear
      v-if="loading"
      indeterminate
      color="primary" />
    <div v-if="loaded && !delegations.length" class="px-4 py-4 text-sub-title text-wrap">
      {{ $t('UserSettings.emailConnector.sharedWithMe.none') }}
    </div>
    <template v-for="group in groups">
      <!-- A group's title only when there are several groups: alone, it would repeat
           the drawer's own title. -->
      <div
        v-if="group.rows.length && shownGroups > 1"
        :key="group.status"
        class="px-4 pt-4 pb-1 text-caption text-sub-title text-wrap">
        {{ $t(group.titleKey) }}
      </div>
      <v-list
        v-if="group.rows.length"
        :key="`${group.status}-list`"
        class="pa-0">
        <email-connector-user-setting-shared-mailbox-row
          v-for="delegation in group.rows"
          :key="delegation.id"
          :delegation="delegation"
          :actions="group.actions"
          :note="group.noteKey ? $t(group.noteKey) : ''"
          :saving="savingId === delegation.id"
          :disabled="savingId !== null"
          @answer="onAnswer(delegation, $event)"
          @badge="saveBadge(delegation, $event)"
          @notify="saveNotify(delegation, $event)" />
      </v-list>
    </template>
    <!-- Leaving is the one answer that takes a mailbox away from the user's screens,
         so it is asked first; the access itself stays, and the question says so. -->
    <exo-confirm-dialog
      ref="leaveConfirmDialog"
      :title="$t('UserSettings.emailConnector.sharedWithMe.leave.confirm.title', { 0: leavingName })"
      :message="$t('UserSettings.emailConnector.sharedWithMe.leave.confirm.message', { 0: leavingName })"
      :ok-label="$t('UserSettings.emailConnector.sharedWithMe.leave')"
      :cancel-label="$t('UserSettings.emailConnector.sharedWithMe.leave.confirm.cancel')"
      @ok="confirmLeave" />
  </div>
</template>

<script>
// The row is this drawer's own part, registered here rather than globally: nothing else
// shows it.
import EmailConnectorUserSettingSharedMailboxRow from './EmailConnectorUserSettingSharedMailboxRow.vue';

/**
 * The four states a share can be in on this side, the order they are shown in, and
 * what may be done to each. Declared once, outside the component, because it is the
 * screen's shape rather than its state: a PENDING share is answered, an ACCEPTED one
 * is left, a DECLINED or AVAILABLE one can still be taken up later.
 */
const GROUPS = [
  {
    status: 'PENDING',
    titleKey: 'UserSettings.emailConnector.sharedWithMe.group.PENDING',
    noteKey: 'UserSettings.emailConnector.sharedWithMe.declineKeepsAccess',
    actions: ['accept', 'decline'],
  },
  {
    status: 'ACCEPTED',
    titleKey: 'UserSettings.emailConnector.sharedWithMe.group.ACCEPTED',
    noteKey: null,
    actions: ['leave'],
  },
  {
    status: 'AVAILABLE',
    titleKey: 'UserSettings.emailConnector.sharedWithMe.group.AVAILABLE',
    noteKey: 'UserSettings.emailConnector.sharedWithMe.availableNote',
    actions: ['accept'],
  },
  {
    status: 'DECLINED',
    titleKey: 'UserSettings.emailConnector.sharedWithMe.group.DECLINED',
    noteKey: 'UserSettings.emailConnector.sharedWithMe.declinedNote',
    actions: ['accept'],
  },
];

export default {
  components: {
    'email-connector-user-setting-shared-mailbox-row': EmailConnectorUserSettingSharedMailboxRow,
  },
  data: () => ({
    active: false,
    loading: false,
    loaded: false,
    delegations: [],
    savingId: null,
    // The share whose "Leave" is being confirmed.
    leaving: null,
  }),
  computed: {
    /**
     * How many groups have rows to show.
     *
     * @returns {Number} the count
     */
    shownGroups() {
      return this.groups.filter(group => group.rows.length).length;
    },
    /**
     * How the share being left is named in its confirmation: the owner's mailbox, which
     * every row carries whether or not eXo knows its owner.
     *
     * @returns {String} the name
     */
    leavingName() {
      return this.leaving?.ownerMailbox || '';
    },
    /**
     * The rows grouped by state, in the order the screen shows them. REVOKED and GONE
     * rows are deliberately absent: they are history of an access that no longer
     * exists, and nothing can be done to them from here.
     *
     * @returns {Array} the groups, each with its rows
     */
    groups() {
      return GROUPS.map(group => ({
        ...group,
        rows: this.delegations.filter(delegation => delegation.status === group.status),
      }));
    },
  },
  methods: {
    /**
     * Shows a message on the platform's toast, through the document event it listens to,
     * whichever app the drawer is mounted in (the settings page, or the mailbox's
     * "Manage shared mailboxes").
     *
     * @param {String} message the message
     * @param {String} type success or error
     * @returns {void}
     */
    showAlert(message, type) {
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: type, alertMessage: message}}));
    },
    /**
     * Shows the tab, walking the mail server for shares nobody invited from here.
     * Called by the drawer the first time this tab is shown after it opens.
     *
     * @returns {void}
     */
    open() {
      this.active = true;
      this.load(true);
    },
    /**
     * Reads the shares. Discovery costs a connection to the mail server, so it is
     * asked for when the drawer opens and not on every reload after an answer.
     *
     * @param {Boolean} discover whether to walk the mail server as well
     * @returns {Promise} resolved once the list is on screen
     */
    load(discover) {
      this.loading = true;
      return this.$emailConnectorUserSettingService.getReceivedDelegations(discover)
        .then(delegations => {
          this.delegations = delegations || [];
          this.loaded = true;
        })
        .catch(() => {
          const message = this.$t('UserSettings.emailConnector.sharedWithMe.error');
          this.showAlert(message, 'error');
        })
        .finally(() => this.loading = false);
    },
    /**
     * Accepts, declines or leaves a share. None of the three touches the access
     * itself: accepting subscribes, declining and leaving unsubscribe, and the access
     * stays exactly as its owner left it in all three cases.
     *
     * @param {Object} delegation the row
     * @param {String} action accept, decline or leave
     * @returns {void}
     */
    answer(delegation, action) {
      this.savingId = delegation.id;
      this.$emailConnectorUserSettingService.answerDelegation(delegation.id, action)
        .then(() => {
          const message = this.$t(`UserSettings.emailConnector.sharedWithMe.${action}.done`);
          this.showAlert(message, 'success');
        })
        .catch(error => {
          const code = error?.message;
          const known = !!code && typeof this.$te === 'function' && this.$te(code);
          const fallback = this.$t(`UserSettings.emailConnector.sharedWithMe.${action}.error`);
          const message = known ? this.$t(code) : fallback;
          this.showAlert(message, 'error');
        })
        .finally(() => {
          this.savingId = null;
          this.load(false);
          this.$root.$emit('email-delegations-updated');
        });
    },
    /**
     * Stores whether this shared inbox counts in the reader's own unread badge. Off by
     * default, and it stays a choice: because the mail server keeps one read state for
     * the whole mailbox, the number this adds is the owner's own unread count, which a
     * standby reader has no way to bring down without marking the owner's mail read
     * for both of them.
     *
     * @param {Object} delegation the row
     * @param {Boolean} included whether it counts
     * @returns {void}
     */
    saveBadge(delegation, included) {
      this.savingId = delegation.id;
      this.$emailConnectorUserSettingService.updateDelegationPreferences(delegation.id, {badgeIncluded: !!included})
        .then(() => this.showAlert(this.$t('UserSettings.emailConnector.preferences.saved'), 'success'))
        .catch(() => this.showAlert(this.$t('UserSettings.emailConnector.preferences.error'), 'error'))
        .finally(() => {
          this.savingId = null;
          this.load(false);
        });
    },
    /**
     * Stores whether new mail in this shared inbox notifies the reader (EXO-90553). Off
     * by default; on, it notifies only while the reader keeps using the mailbox, since
     * a share nobody opens is not checked.
     *
     * @param {Object} delegation the row
     * @param {Boolean} notify whether new mail there notifies
     * @returns {void}
     */
    saveNotify(delegation, notify) {
      this.savingId = delegation.id;
      this.$emailConnectorUserSettingService.updateDelegationPreferences(delegation.id, {notifyNewMail: !!notify})
        .then(() => this.showAlert(this.$t('UserSettings.emailConnector.preferences.saved'), 'success'))
        .catch(() => this.showAlert(this.$t('UserSettings.emailConnector.preferences.error'), 'error'))
        .finally(() => {
          this.savingId = null;
          this.load(false);
        });
    },
    /**
     * A row's answer: Leave is asked first, the others are sent as they are.
     *
     * @param {Object} delegation the row
     * @param {String} action accept, decline or leave
     * @returns {void}
     */
    onAnswer(delegation, action) {
      if (action === 'leave') {
        this.leaving = delegation;
        this.$refs.leaveConfirmDialog.open();
        return;
      }
      this.answer(delegation, action);
    },
    /**
     * Leaves the share whose confirmation was just accepted.
     *
     * @returns {void}
     */
    confirmLeave() {
      const delegation = this.leaving;
      this.leaving = null;
      if (delegation) {
        this.answer(delegation, 'leave');
      }
    },
  },
};
</script>
