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
  <!-- Giving somebody access to your mailbox (EXO-90503): a person and one of two
       right sets, and nothing else — no letter picker, because nobody can be expected
       to know what x does, and the two presets are what the mail server is asked for.
       A second-level drawer over the sharing list, the way the folder-name drawer sits
       over the folders list. -->
  <exo-drawer
    id="userSettingSharingInviteDrawer"
    ref="inviteDrawer"
    v-model="drawer"
    :loading="saving"
    right
    @closed="close">
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.sharing.invite.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <div class="pa-4">
        <!-- The platform's own people picker. Users only: a share is granted to one
             person, resolved server-side to the identifier THEIR OWN connected mailbox
             uses — a mail login is never typed here, and a space or a group has no
             mailbox to name. -->
        <identity-suggester
          ref="granteeSuggester"
          v-model="grantee"
          :labels="suggesterLabels"
          :search-options="searchOptions"
          include-users
          ignore-cache
          class="mb-4" />
        <div class="text-subtitle-2 text-color mb-2">
          {{ $t('UserSettings.emailConnector.sharing.invite.rights') }}
        </div>
        <v-radio-group v-model="preset" class="mt-0">
          <v-radio value="READER" class="align-start">
            <template #label>
              <div class="d-flex flex-column">
                <span class="text-color">{{ $t('UserSettings.emailConnector.sharing.preset.READER') }}</span>
                <span class="caption text-sub-title text-wrap">
                  {{ $t('UserSettings.emailConnector.sharing.preset.READER.description') }}
                </span>
              </div>
            </template>
          </v-radio>
          <v-radio value="EDITOR" class="align-start">
            <template #label>
              <div class="d-flex flex-column">
                <span class="text-color">{{ $t('UserSettings.emailConnector.sharing.preset.EDITOR') }}</span>
                <span class="caption text-sub-title text-wrap">
                  {{ $t('UserSettings.emailConnector.sharing.preset.EDITOR.description') }}
                </span>
              </div>
            </template>
          </v-radio>
        </v-radio-group>
        <!-- The consent, said before the button and not in a tooltip: the access is
             written on the mail server the moment this is pressed, it reaches every
             mail client the person uses and not only eXo, and declining the invitation
             will not take it back. Only this button's owner can. -->
        <v-alert
          dense
          outlined
          type="info"
          class="caption text-wrap mt-2">
          {{ $t('UserSettings.emailConnector.sharing.invite.consent') }}
        </v-alert>
      </div>
    </template>
    <template #footer>
      <div class="d-flex align-center">
        <v-spacer />
        <v-btn class="btn me-2" @click="close">
          {{ $t('UserSettings.emailConnector.sharing.cancel') }}
        </v-btn>
        <v-btn
          :loading="saving"
          :disabled="!granteeUsername"
          class="btn btn-primary"
          @click="share">
          {{ $t('UserSettings.emailConnector.sharing.share') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    drawer: false,
    saving: false,
    grantee: null,
    preset: 'READER',
  }),
  computed: {
    /**
     * The picked person's eXo username, which is all the server is given: it resolves
     * their mail identifier from their own connected mailbox.
     *
     * @returns {String} the username, or nothing while none is picked
     */
    granteeUsername() {
      return this.grantee?.remoteId || this.grantee?.profile?.username || '';
    },
    /**
     * The suggester's query options. An object and never null on purpose: the
     * platform's suggester service dereferences this without a guard on the
     * users-only path, so a null here is a TypeError rather than a default.
     *
     * @returns {Object} the query options
     */
    searchOptions() {
      return {};
    },
    /**
     * @returns {Object} the suggester's own labels, in the user's language
     */
    suggesterLabels() {
      return {
        label: this.$t('UserSettings.emailConnector.sharing.invite.person'),
        placeholder: this.$t('UserSettings.emailConnector.sharing.invite.person.placeholder'),
        searchPlaceholder: this.$t('UserSettings.emailConnector.sharing.invite.person.search'),
        noDataLabel: this.$t('UserSettings.emailConnector.sharing.invite.person.noData'),
      };
    },
  },
  created() {
    this.$root.$on('open-email-sharing-invite-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-sharing-invite-drawer', this.open);
  },
  methods: {
    /**
     * Opens the drawer on an empty choice — never on the last one, which would let a
     * second share go to the first person by a misplaced press.
     *
     * @returns {void}
     */
    open() {
      this.grantee = null;
      this.preset = 'READER';
      this.drawer = true;
      this.$refs.inviteDrawer.open();
    },
    /**
     * Writes the access on the mail server and invites the person. The refusals worth
     * their own words are the ones the user can act on: sharing with themselves, a
     * person whose mailbox is not connected here, a share that already exists.
     *
     * @returns {void}
     */
    share() {
      if (!this.granteeUsername) {
        return;
      }
      this.saving = true;
      this.$emailConnectorUserSettingService.inviteDelegation(this.granteeUsername, this.preset)
        .then(() => {
          this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.sharing.shared'), 'success');
          // The list behind this drawer is the mail server's; it just changed.
          this.$root.$emit('email-delegation-granted');
          this.$root.$emit('email-delegations-updated');
          this.close();
        })
        .catch(error => {
          const code = error?.message;
          const known = !!code && typeof this.$te === 'function' && this.$te(code);
          const message = known ? this.$t(code) : this.$t('UserSettings.emailConnector.sharing.share.error');
          this.$root.$emit('alert-message', message, 'error');
        })
        .finally(() => this.saving = false);
    },
    /**
     * Closes this drawer, leaving the sharing list open behind it.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
      this.$refs.inviteDrawer.close();
    },
  },
};
</script>
