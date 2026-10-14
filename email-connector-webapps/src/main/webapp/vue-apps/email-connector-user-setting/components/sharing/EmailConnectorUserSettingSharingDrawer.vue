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
  <!-- Who has access to YOUR mailbox (EXO-90503, delegation plan 7.1). The list is the
       mail server's own ACL read live, not a table eXo keeps: a share somebody made in
       the mail server's own interface is in it, and so is one an administrator made.
       That is why the list can hold rows eXo cannot act on at all — an identifier no
       eXo user holds — and why those rows carry no button rather than a dead one. -->
  <exo-drawer
    id="userSettingSharingDrawer"
    ref="sharingDrawer"
    v-model="drawer"
    :loading="loading"
    right
    allow-expand
    @closed="close">
    <template #title>
      <span>{{ $t('UserSettings.emailConnector.sharing.drawer.title') }}</span>
    </template>
    <template #titleIcons>
      <!-- Absent right, absent control: a mail server that cannot share offers no
           Share button at all, rather than a disabled one the user keeps pressing. -->
      <v-btn
        v-if="canShare"
        :title="$t('UserSettings.emailConnector.sharing.share')"
        icon
        @click="openInvite">
        <v-icon size="18">fas fa-plus</v-icon>
      </v-btn>
    </template>
    <template v-if="drawer" #content>
      <!-- Said before the list and not after it: on a mail server without sharing
           there is nothing to read, and the reason is the whole answer. -->
      <div v-if="loaded && !supported" class="px-4 py-4 text-sub-title text-wrap">
        {{ unsupportedMessage }}
      </div>
      <template v-else>
        <div class="px-4 pt-4 pb-2 text-caption text-sub-title text-wrap">
          {{ $t('UserSettings.emailConnector.sharing.hint') }}
        </div>
        <div v-if="loaded && !grantees.length" class="px-4 py-2 text-sub-title">
          {{ $t('UserSettings.emailConnector.sharing.none') }}
        </div>
        <v-list class="pa-0">
          <v-list-item
            v-for="grantee in grantees"
            :key="grantee.identifier"
            class="height-auto">
            <v-list-item-content class="py-2">
              <!-- The platform's own avatar component resolves the person from the
                   username: this screen has a username and nothing else, and it is
                   not the place to learn how to look somebody up. An ACL identifier
                   that maps to no eXo user is written out as the server holds it. -->
              <user-avatar
                v-if="grantee.granteeId"
                :profile-id="grantee.granteeId"
                avatar
                fullname
                class="mb-1" />
              <template v-else>
                <v-list-item-title>{{ grantee.identifier }}</v-list-item-title>
                <v-list-item-subtitle class="caption text-sub-title text-wrap">
                  {{ $t('UserSettings.emailConnector.sharing.notAnExoUser') }}
                </v-list-item-subtitle>
              </template>
              <email-connector-delegation-rights
                :preset="grantee.preset"
                :rights="grantee.rights"
                :native-rights="grantee.nativeRights"
                :affordances="grantee.affordances"
                class="my-1" />
              <!-- A share the mail server holds and eXo never wrote is said to be
                   exactly that. Presenting it as something eXo granted would invite
                   the owner to reason about it with eXo's two presets, which is the
                   one thing it may not be. -->
              <v-list-item-subtitle v-if="discovered(grantee)" class="caption text-sub-title text-wrap">
                {{ $t('UserSettings.emailConnector.sharing.origin.SERVER') }}
              </v-list-item-subtitle>
              <v-list-item-subtitle v-if="statusLabel(grantee)" class="caption text-sub-title">
                {{ statusLabel(grantee) }}
              </v-list-item-subtitle>
            </v-list-item-content>
            <v-list-item-action>
              <!-- Only a row eXo can name has a Remove button: revoking goes through
                   the delegation id, and a raw ACL identifier has none. -->
              <v-btn
                v-if="grantee.delegation && grantee.delegation.id"
                :title="$t('UserSettings.emailConnector.sharing.revoke')"
                icon
                :disabled="revokingId !== null"
                @click="openRevoke(grantee)">
                <v-icon size="16">fas fa-trash</v-icon>
              </v-btn>
            </v-list-item-action>
          </v-list-item>
        </v-list>
      </template>
      <!-- Inside the content slot: exo-drawer renders named slots only, and anything
           placed beside them is silently dropped. -->
      <exo-confirm-dialog
        ref="revokeConfirmDialog"
        :title="$t('UserSettings.emailConnector.sharing.revoke.confirm.title')"
        :message="revokeConfirmMessage"
        :ok-label="$t('UserSettings.emailConnector.sharing.revoke')"
        :cancel-label="$t('UserSettings.emailConnector.sharing.cancel')"
        @ok="doRevoke" />
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
export default {
  data: () => ({
    drawer: false,
    loading: false,
    loaded: false,
    capabilities: null,
    grantees: [],
    revokingId: null,
    revokeTarget: null,
  }),
  computed: {
    /**
     * @returns {Boolean} whether the connected mail server can share at all
     */
    supported() {
      return !!this.capabilities?.supported;
    },
    /**
     * @returns {Boolean} whether a new share may be offered
     */
    canShare() {
      return this.loaded && this.supported;
    },
    /**
     * Why this mail server cannot share, in the user's words. The server answers a
     * message code; an unknown one falls back to the general sentence rather than to
     * the code itself, which means nothing to the person reading it.
     *
     * @returns {String} the localized reason
     */
    unsupportedMessage() {
      const code = this.capabilities?.reasonCode;
      return this.known(code) ? this.$t(code) : this.$t('emailConnector.delegation.unsupported');
    },
    /**
     * @returns {String} the revoke confirmation, naming who loses access
     */
    revokeConfirmMessage() {
      const name = this.revokeTarget?.granteeId || this.revokeTarget?.identifier || '';
      return this.$t('UserSettings.emailConnector.sharing.revoke.confirm.message', { 0: name });
    },
  },
  created() {
    this.$root.$on('open-email-sharing-drawer', this.open);
    this.$root.$on('email-delegation-granted', this.onGranted);
  },
  beforeDestroy() {
    this.$root.$off('open-email-sharing-drawer', this.open);
    this.$root.$off('email-delegation-granted', this.onGranted);
  },
  methods: {
    /**
     * Opens the drawer on the mail server's ACL as it stands now.
     *
     * @returns {void}
     */
    open() {
      this.drawer = true;
      this.$refs.sharingDrawer.open();
      this.load();
    },
    /**
     * Reads who has access, from the mail server. A failure is said here rather than
     * left as an empty list: an empty list means "nobody has access", which is a very
     * different thing from "we could not ask".
     *
     * @returns {Promise} resolved once the list is on screen
     */
    load() {
      this.loading = true;
      return this.$emailConnectorUserSettingService.getGrantedDelegations()
        .then(answer => {
          this.capabilities = answer?.capabilities || null;
          this.grantees = answer?.grantees || [];
          this.loaded = true;
        })
        .catch(error => {
          this.capabilities = null;
          this.grantees = [];
          this.loaded = false;
          this.$root.$emit('alert-message', this.messageOf(error, 'UserSettings.emailConnector.sharing.error'), 'error');
        })
        .finally(() => this.loading = false);
    },
    /**
     * Whether a row is a share the mail server holds and eXo did not write.
     *
     * @param {Object} grantee the row
     * @returns {Boolean} true when it came from the server
     */
    discovered(grantee) {
      return !grantee?.delegation || grantee.delegation.origin === 'SERVER';
    },
    /**
     * Where an eXo-issued invitation stands. A share discovered on the server has no
     * invitation and therefore no status to show.
     *
     * @param {Object} grantee the row
     * @returns {String} the localized status, or nothing
     */
    statusLabel(grantee) {
      const status = grantee?.delegation?.status;
      if (!status || this.discovered(grantee)) {
        return '';
      }
      return this.$t(`UserSettings.emailConnector.sharing.status.${status}`);
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
     * Reloads once a share was granted, while this drawer stays open behind the
     * invite one.
     *
     * @returns {void}
     */
    onGranted() {
      if (this.drawer) {
        this.load();
      }
    },
    /**
     * Asks for the confirmation removing access needs — the person is named in it,
     * because this is the one action on this screen that takes something away.
     *
     * @param {Object} grantee the row
     * @returns {void}
     */
    openRevoke(grantee) {
      this.revokeTarget = grantee;
      this.$refs.revokeConfirmDialog.open();
    },
    /**
     * Removes the access on the mail server. Unlike declining and leaving, this one
     * really does revoke.
     *
     * @returns {void}
     */
    doRevoke() {
      const id = this.revokeTarget?.delegation?.id;
      if (!id) {
        return;
      }
      this.revokingId = id;
      this.$emailConnectorUserSettingService.revokeDelegation(id)
        .then(() => this.$root.$emit('alert-message', this.$t('UserSettings.emailConnector.sharing.revoked'), 'success'))
        .catch(error => {
          const message = this.messageOf(error, 'UserSettings.emailConnector.sharing.revoke.error');
          this.$root.$emit('alert-message', message, 'error');
        })
        .finally(() => {
          this.revokingId = null;
          this.revokeTarget = null;
          this.load();
          this.$root.$emit('email-delegations-updated');
        });
    },
    /**
     * The server's own message code in the user's words when the bundle has it, the
     * screen's general sentence otherwise. A refusal nobody can read is a bug.
     *
     * @param {Error} error the rejection
     * @param {String} fallbackKey the general sentence's key
     * @returns {String} the localized message
     */
    messageOf(error, fallbackKey) {
      const code = error?.message;
      return this.known(code) ? this.$t(code) : this.$t(fallbackKey);
    },
    /**
     * Whether the bundle holds a sentence for a server message code. Guarded on $te
     * itself: a screen whose bundle failed to load must still say something rather
     * than throw over an error it was reporting.
     *
     * @param {String} key the message code
     * @returns {Boolean} true when it can be translated
     */
    known(key) {
      return !!key && typeof this.$te === 'function' && this.$te(key);
    },
    /**
     * Closes the drawer and tells the settings rows to re-read their counters.
     *
     * @returns {void}
     */
    close() {
      this.drawer = false;
      this.$refs.sharingDrawer.close();
      this.$root.$emit('email-delegations-updated');
    },
  },
};
</script>
