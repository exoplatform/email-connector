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
       four states they can be in. The drawer walks the mail server when it opens, so a
       share somebody made in their own webmail is offered here — proposed, never
       subscribed on your behalf: an unexpected copy of a colleague's mailbox is a
       surprise nobody wants, and it costs a synchronisation. -->
  <exo-drawer
    id="userSettingSharedWithMeDrawer"
    ref="sharedWithMeDrawer"
    v-model="drawer"
    :loading="loading"
    right
    allow-expand
    @closed="close">
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.sharedWithMe.drawer.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <div v-if="loaded && !delegations.length" class="px-4 py-4 text-sub-title text-wrap">
        {{ $t('UserSettings.emailConnector.sharedWithMe.none') }}
      </div>
      <template v-for="group in groups">
        <div
          v-if="group.rows.length"
          :key="group.status"
          class="px-4 pt-4 pb-1 text-caption text-sub-title text-wrap">
          {{ $t(group.titleKey) }}
        </div>
        <v-list
          v-if="group.rows.length"
          :key="`${group.status}-list`"
          class="pa-0">
          <v-list-item
            v-for="delegation in group.rows"
            :key="delegation.id"
            class="height-auto">
            <v-list-item-content class="py-2">
              <user-avatar
                v-if="delegation.ownerId"
                :profile-id="delegation.ownerId"
                avatar
                fullname
                class="mb-1" />
              <v-list-item-title v-else>
                {{ delegation.ownerMailbox }}
              </v-list-item-title>
              <email-connector-delegation-rights
                :preset="delegation.preset"
                :rights="delegation.rights"
                :native-rights="delegation.nativeRights"
                :affordances="delegation.affordances"
                class="my-1" />
              <!-- Declining does not take the access away, and the wording may not
                   suggest it does: the access was written when the invitation was
                   sent, and only its owner removes it. Saying otherwise would leave
                   someone believing they had closed a door that is still open. -->
              <v-list-item-subtitle v-if="group.noteKey" class="caption text-sub-title text-wrap">
                {{ $t(group.noteKey) }}
              </v-list-item-subtitle>
              <!-- Shown only on a share whose rights let read state be kept: without
                   it there is no unread count to add, so the toggle would promise a
                   number that cannot exist. Absent right, absent control. -->
              <div
                v-if="group.status === 'ACCEPTED' && delegation.affordances && delegation.affordances.markRead"
                class="d-flex align-center mt-1">
                <v-switch
                  :input-value="delegation.badgeIncluded"
                  :loading="savingId === delegation.id"
                  :disabled="savingId !== null"
                  dense
                  hide-details
                  class="mt-0 pt-0 me-2"
                  @change="saveBadge(delegation, $event)" />
                <span class="caption text-sub-title text-wrap">
                  {{ $t('UserSettings.emailConnector.sharedWithMe.badge') }}
                </span>
              </div>
            </v-list-item-content>
            <v-list-item-action class="flex-row align-center">
              <v-btn
                v-for="action in group.actions"
                :key="action"
                :title="$t(`UserSettings.emailConnector.sharedWithMe.${action}`)"
                :loading="savingId === delegation.id"
                :disabled="savingId !== null"
                small
                text
                @click="answer(delegation, action)">
                {{ $t(`UserSettings.emailConnector.sharedWithMe.${action}`) }}
              </v-btn>
            </v-list-item-action>
          </v-list-item>
        </v-list>
      </template>
    </template>
    <template #footer>
      <div class="d-flex align-center">
        <v-spacer />
        <v-btn class="btn" @click="close">
          {{ $t('UserSettings.emailConnector.sharedWithMe.drawer.close') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
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
  data: () => ({
    drawer: false,
    loading: false,
    loaded: false,
    delegations: [],
    savingId: null,
  }),
  computed: {
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
  created() {
    this.$root.$on('open-email-shared-with-me-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-shared-with-me-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer, walking the mail server for shares nobody invited from here.
     *
     * @returns {void}
     */
    open() {
      this.drawer = true;
      this.$refs.sharedWithMeDrawer.open();
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
          this.$root.$emit('alert-message', message, 'error');
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
          this.$root.$emit('alert-message', message, 'success');
        })
        .catch(error => {
          const code = error?.message;
          const known = !!code && typeof this.$te === 'function' && this.$te(code);
          const fallback = this.$t(`UserSettings.emailConnector.sharedWithMe.${action}.error`);
          const message = known ? this.$t(code) : fallback;
          this.$root.$emit('alert-message', message, 'error');
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
        .then(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.saved'), 'success'))
        .catch(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.preferences.error'), 'error'))
        .finally(() => {
          this.savingId = null;
          this.load(false);
        });
    },
    /**
     * Closes the drawer and tells the settings rows to re-read their counters.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
      this.$refs.sharedWithMeDrawer.close();
      this.$root.$emit('email-delegations-updated');
    },
  },
};
</script>
