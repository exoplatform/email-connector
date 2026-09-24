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
  <!-- Who has access to YOUR mailbox (EXO-90503, delegation plan 7.1): the first tab of
       the "Mailbox sharing" drawer (EXO-90559), which owns the drawer itself, its title,
       its Share button and its close. The file keeps its name so the drawer's history
       stays readable. The list is the mail server's own ACL read live, not a table eXo
       keeps: a share somebody made in the mail server's own interface is in it, and so
       is one an administrator made. That is why the list can hold rows eXo cannot act on
       at all -- an identifier no eXo user holds -- and why those rows carry no button
       rather than a dead one. -->
  <div v-if="active">
    <v-progress-linear
      v-if="loading"
      indeterminate
      color="primary" />
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
        <email-connector-user-setting-grantee-row
          v-for="grantee in grantees"
          :key="grantee.identifier"
          :grantee="grantee"
          :disabled="revokingId !== null || changingId !== null"
          :per-folder="perFolder"
          :send-modes="sendModes"
          @change-preset="askChangePreset(grantee, $event)"
          @change-send-mode="askChangeSendMode(grantee, $event)"
          @extend="askExtend(grantee)"
          @folders="openFolders(grantee)"
          @revoke="openRevoke(grantee)" />
      </v-list>
    </template>
    <exo-confirm-dialog
      ref="revokeConfirmDialog"
      :title="$t('UserSettings.emailConnector.sharing.revoke.confirm.title')"
      :message="revokeConfirmMessage"
      :ok-label="$t('UserSettings.emailConnector.sharing.revoke')"
      :cancel-label="$t('UserSettings.emailConnector.sharing.cancel')"
      @ok="doRevoke" />
    <!-- Setting a preset REPLACES the person's entry on the mail server. On an access
         written there (or with letters no preset names) that drops whatever else it
         held, so it is asked first, saying so. -->
    <!-- Sharing more of the mailbox is the owner's explicit act, said as what it gives
         (EXO-90548, PO decision Q-4): never a silent widening. -->
    <exo-confirm-dialog
      ref="extendConfirmDialog"
      :title="$t('UserSettings.emailConnector.sharing.extend.confirm.title')"
      :message="extendMessage"
      :ok-label="$t('UserSettings.emailConnector.sharing.extend.confirm.ok')"
      :cancel-label="$t('UserSettings.emailConnector.sharing.cancel')"
      @ok="extend" />
    <exo-confirm-dialog
      ref="replaceConfirmDialog"
      :title="$t('UserSettings.emailConnector.sharing.replace.confirm.title')"
      :message="$t('UserSettings.emailConnector.sharing.replace.confirm.message')"
      :ok-label="$t('UserSettings.emailConnector.sharing.replace.confirm.ok')"
      :cancel-label="$t('UserSettings.emailConnector.sharing.cancel')"
      @ok="confirmChangePreset" />
    <!-- Letting someone write mail in the owner's name (EXO-90582) is asked first, with
         what recipients will see and whether the owner keeps a copy: a grant, and the
         step from on behalf to as. Narrowing it is not asked (PO decision Q-C). -->
    <exo-confirm-dialog
      ref="sendModeConfirmDialog"
      :title="sendModeConfirmTitle"
      :message="sendModeConfirmMessage"
      :ok-label="$t('UserSettings.emailConnector.sharing.sendMode.confirm.ok')"
      :cancel-label="$t('UserSettings.emailConnector.sharing.cancel')"
      @ok="confirmChangeSendMode" />
  </div>
</template>

<script>
// The row is this drawer's own part, registered here: nothing else shows it.
import EmailConnectorUserSettingGranteeRow from './EmailConnectorUserSettingGranteeRow.vue';

export default {
  components: {
    'email-connector-user-setting-grantee-row': EmailConnectorUserSettingGranteeRow,
  },
  data: () => ({
    active: false,
    loading: false,
    loaded: false,
    capabilities: null,
    // Whether an administrator left the owner's Sent copy of a delegate's mail on (EXO-90551).
    sentCopyEnabled: false,
    grantees: [],
    revokingId: null,
    revokeTarget: null,
    changingId: null,
    // The change of access waiting for the replace confirmation: {grantee, preset}.
    pendingChange: null,
    // The share whose "Share ... too" is being confirmed.
    extendTarget: null,
    // The folders the Extend being confirmed adds, by name: kept apart from extendTarget,
    // which the confirmation clears as it closes, so its text does not empty on the way out.
    extendNames: '',
    // The consent to writing in the owner's name waiting for its confirmation: {grantee, mode}.
    pendingSendMode: null,
    // What that confirmation says, kept apart from pendingSendMode for the same reason.
    sendModeConfirmTitle: '',
    sendModeConfirmMessage: '',
  }),
  computed: {
    /**
     * What the Extend confirmation says it gives: the folders it would add, by name
     * (EXO-90548).
     *
     * @returns {String} the message
     */
    extendMessage() {
      return this.$t('UserSettings.emailConnector.sharing.extendRoles.confirm.message', { 0: this.extendNames });
    },
    /**
     * @returns {Boolean} whether the connected mail server can share at all
     */
    supported() {
      return !!this.capabilities?.supported;
    },
    /**
     * Whether the mail server shares folder by folder (EXO-90556): only then are folders
     * offered one by one; a server that shares a whole mailbox at once shows the presets.
     *
     * @returns {Boolean} true on a per-folder server
     */
    perFolder() {
      return this.supported && this.capabilities?.grantGranularity === 'FOLDER';
    },
    /**
     * The shapes of writing in the owner's name her mail server is declared to accept
     * (EXO-90582): none where nothing is declared, which hides the choice.
     *
     * @returns {Array} ON_BEHALF, AS, or empty
     */
    sendModes() {
      return this.supported ? (this.capabilities?.sendModes || []) : [];
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
  watch: {
    /**
     * Tells the drawer whether to offer its Share button: it lives in the drawer's
     * title bar, which this tab does not own.
     *
     * @param {Boolean} value whether a new share may be offered
     * @returns {void}
     */
    canShare(value) {
      this.$emit('can-share', value);
    },
    /**
     * Tells the drawer whether the invitation may offer folders one by one.
     *
     * @param {Boolean} value whether the server shares folder by folder
     * @returns {void}
     */
    perFolder(value) {
      this.$emit('per-folder', value);
    },
    /**
     * Tells the drawer which shapes of writing in the owner's name the server accepts,
     * for the invitation's consent sentence (EXO-90582).
     *
     * @param {Array} value the declared shapes
     * @returns {void}
     */
    sendModes(value) {
      this.$emit('send-modes', value);
    },
  },
  created() {
    this.$root.$on('email-delegation-granted', this.onGranted);
  },
  beforeDestroy() {
    this.$root.$off('email-delegation-granted', this.onGranted);
  },
  methods: {
    /**
     * Shows the tab on the mail server's ACL as it stands now. Called by the drawer the
     * first time this tab is shown after it opens.
     *
     * @returns {void}
     */
    open() {
      this.active = true;
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
          this.sentCopyEnabled = !!answer?.sentCopyEnabled;
          this.grantees = answer?.grantees || [];
          this.loaded = true;
          // The settings row's summary counts from this read rather than making its
          // own: listing who has access reconciles eXo's rows with the mail server,
          // which the owner's own act of opening this list is for, not a page view.
          this.$root.$emit('email-sharing-grantees-read', this.supported ? this.grantees.length : null);
        })
        .catch(error => {
          this.capabilities = null;
          this.grantees = [];
          this.loaded = false;
          this.showAlert(this.messageOf(error, 'UserSettings.emailConnector.sharing.error'), 'error');
        })
        .finally(() => this.loading = false);
    },
    /**
     * Reloads once a share was granted, while this drawer stays open behind the
     * invite one.
     *
     * @returns {void}
     */
    onGranted() {
      if (this.active) {
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
        .then(() => this.showAlert(this.$t('UserSettings.emailConnector.sharing.revoked'), 'success'))
        .catch(error => {
          const message = this.messageOf(error, 'UserSettings.emailConnector.sharing.revoke.error');
          this.showAlert(message, 'error');
        })
        .finally(() => {
          this.revokingId = null;
          this.revokeTarget = null;
          this.load();
          this.$root.$emit('email-delegations-updated');
        });
    },
    /**
     * A row's "Change access": at once for an access eXo wrote as a preset; after a
     * confirmation for one written on the mail server or reading as no preset, whose
     * entry the change replaces -- rights eXo never names included.
     *
     * @param {Object} grantee the row
     * @param {String} preset READER or EDITOR
     * @returns {void}
     */
    askChangePreset(grantee, preset) {
      const serverMade = !grantee?.delegation || grantee.delegation.origin === 'SERVER';
      const custom = grantee?.preset !== 'READER' && grantee?.preset !== 'EDITOR';
      if (serverMade || custom) {
        this.pendingChange = { grantee, preset };
        this.$refs.replaceConfirmDialog.open();
        return;
      }
      this.changePreset(grantee, preset);
    },
    /**
     * Makes the change the replace confirmation was asked for.
     *
     * @returns {void}
     */
    confirmChangePreset() {
      const change = this.pendingChange;
      this.pendingChange = null;
      if (change) {
        this.changePreset(change.grantee, change.preset);
      }
    },
    /**
     * Changes a person's access to another preset, on the mail server, and shows the
     * list as the server now holds it.
     *
     * @param {Object} grantee the row
     * @param {String} preset READER or EDITOR
     * @returns {void}
     */
    changePreset(grantee, preset) {
      const id = grantee?.delegation?.id;
      if (!id) {
        return;
      }
      this.changingId = id;
      this.$emailConnectorUserSettingService.changeDelegationPreset(id, preset)
        .then(() => this.showAlert(this.$t('UserSettings.emailConnector.sharing.changed'), 'success'))
        .catch(error => this.showAlert(this.messageOf(error, 'UserSettings.emailConnector.sharing.change.error'), 'error'))
        .finally(() => {
          this.changingId = null;
          this.load();
          this.$root.$emit('email-delegations-updated');
        });
    },
    /**
     * A row's "Writing mail in your name" choice (EXO-90582): a grant, and the step from on
     * behalf to as, after the confirmation that says what it gives; a withdrawal and the
     * step back to on behalf at once (PO decision Q-C).
     *
     * @param {Object} grantee the row
     * @param {String} mode NONE, ON_BEHALF or AS
     * @returns {void}
     */
    askChangeSendMode(grantee, mode) {
      const current = grantee?.delegation?.sendMode || 'NONE';
      const widens = mode === 'AS' ? current !== 'AS' : mode === 'ON_BEHALF' && current === 'NONE';
      if (!widens) {
        this.changeSendMode(grantee, mode);
        return;
      }
      const name = this.displayName(grantee);
      const copy = this.$t(this.ownerKeepsACopy(grantee)
        ? 'UserSettings.emailConnector.sharing.sendMode.confirm.copy'
        : 'UserSettings.emailConnector.sharing.sendMode.confirm.noCopy');
      this.pendingSendMode = { grantee, mode };
      this.sendModeConfirmTitle = this.$t(`UserSettings.emailConnector.sharing.sendMode.confirm.title.${mode}`, { 0: name });
      this.sendModeConfirmMessage = this.$t(`UserSettings.emailConnector.sharing.sendMode.confirm.message.${mode}`, { 0: name, 1: copy });
      this.$refs.sendModeConfirmDialog.open();
    },
    /**
     * Makes the change the consent confirmation was asked for.
     *
     * @returns {void}
     */
    confirmChangeSendMode() {
      const change = this.pendingSendMode;
      this.pendingSendMode = null;
      if (change) {
        this.changeSendMode(change.grantee, change.mode);
      }
    },
    /**
     * Records the owner's consent to a person writing mail in her name, and shows the list
     * as it now stands. Nothing is sent and nothing changes on the mail server's ACL.
     *
     * @param {Object} grantee the row
     * @param {String} mode NONE, ON_BEHALF or AS
     * @returns {void}
     */
    changeSendMode(grantee, mode) {
      const id = grantee?.delegation?.id;
      if (!id) {
        return;
      }
      this.changingId = id;
      this.$emailConnectorUserSettingService.setDelegationSendMode(id, mode)
        .then(() => this.showAlert(this.$t(mode === 'NONE'
          ? 'UserSettings.emailConnector.sharing.sendMode.withdrawn'
          : 'UserSettings.emailConnector.sharing.sendMode.changed'), 'success'))
        .catch(error => this.showAlert(this.messageOf(error, 'UserSettings.emailConnector.sharing.sendMode.error'), 'error'))
        .finally(() => {
          this.changingId = null;
          this.load();
          this.$root.$emit('email-delegations-updated');
        });
    },
    /**
     * Whether a mail the person sends in the owner's name will be filed in the owner's own
     * Sent (EXO-90551): the share covers her Sent, with an Editor's access there, and an
     * administrator left the copy on. What the consent says, so she knows before agreeing
     * whether she will keep a copy.
     *
     * @param {Object} grantee the row
     * @returns {Boolean} true when a copy is filed
     */
    ownerKeepsACopy(grantee) {
      const delegation = grantee?.delegation;
      if (!this.sentCopyEnabled || !delegation) {
        return false;
      }
      const roles = (delegation.grantedRoles || '').split(',');
      const wholeMailbox = delegation.grantedRoles === 'MAILBOX';
      if (!wholeMailbox && !roles.includes('SENT')) {
        return false;
      }
      const exception = delegation.folderAccess?.SENT;
      return exception ? exception === 'EDITOR' : delegation.preset === 'EDITOR';
    },
    /**
     * The person a row names, as the confirmations say it: their eXo name, else their
     * identifier on the mail server.
     *
     * @param {Object} grantee the row
     * @returns {String} the name
     */
    displayName(grantee) {
      return grantee?.granteeId || grantee?.identifier || '';
    },
    /**
     * A row's "Folders and access": the drawer that reads and sets each folder's access
     * (EXO-90556), over this list.
     *
     * @param {Object} grantee the row
     * @returns {void}
     */
    openFolders(grantee) {
      this.$root.$emit('open-email-sharing-folders-drawer', grantee);
    },
    /**
     * A row's "Share ... too": asked first, with what it gives.
     *
     * @param {Object} grantee the row
     * @returns {void}
     */
    askExtend(grantee) {
      this.extendTarget = grantee;
      this.extendNames = (grantee?.extendableRoles || []).map(role => this.$t(`UserSettings.emailConnector.sharing.role.${role}`)).join(', ');
      this.$refs.extendConfirmDialog.open();
    },
    /**
     * Extends the share the confirmation was asked for, on the mail server, and shows the
     * list as the server now holds it.
     *
     * @returns {void}
     */
    extend() {
      const id = this.extendTarget?.delegation?.id;
      this.extendTarget = null;
      if (!id) {
        return;
      }
      this.changingId = id;
      this.$emailConnectorUserSettingService.extendDelegation(id)
        .then(() => this.showAlert(this.$t('UserSettings.emailConnector.sharing.extended'), 'success'))
        .catch(error => this.showAlert(this.messageOf(error, 'UserSettings.emailConnector.sharing.extend.error'), 'error'))
        .finally(() => {
          this.changingId = null;
          this.load();
          this.$root.$emit('email-delegations-updated');
        });
    },
    /**
     * Shows a message on the platform's toast, through the document event it listens
     * to.
     *
     * @param {String} message the message
     * @param {String} type success or error
     * @returns {void}
     */
    showAlert(message, type) {
      document.dispatchEvent(new CustomEvent('alert-message', {detail: {alertType: type, alertMessage: message}}));
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
  },
};
</script>
