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
  <!-- One person with access to the owner's mailbox, in the shape of the "Shared with
       you" rows: who, their address, what they can do, then the owner's actions in the
       row's menu. The raw letters are for the curious, in the chip's tooltip. -->
  <v-list-item class="height-auto align-start text-start px-4 py-2">
    <!-- Top-aligned with the name line, not centred on the whole row. -->
    <v-list-item-avatar size="36" class="me-3 mt-1 mb-0 align-self-start">
      <!-- user-avatar draws the picture alone when `avatar` is set; the name is the
           instance on the line beside, in its name-only mode. -->
      <user-avatar
        v-if="grantee.granteeId"
        :profile-id="grantee.granteeId"
        :size="36"
        :popover="false"
        :url="false"
        avatar />
      <v-icon
        v-else
        size="20"
        class="icon-default-color">
        fa-user
      </v-icon>
    </v-list-item-avatar>
    <v-list-item-content class="py-1 text-start">
      <div class="d-flex align-center">
        <user-avatar
          v-if="grantee.granteeId"
          :profile-id="grantee.granteeId"
          :popover="false"
          :url="false"
          class="text-truncate font-weight-bold"
          fullname />
        <span v-else class="text-truncate font-weight-bold">{{ grantee.identifier }}</span>
        <v-spacer />
        <v-chip
          :title="grantee.nativeRights || grantee.rights"
          class="ms-2 flex-shrink-0"
          x-small
          outlined>
          {{ presetLabel }}
        </v-chip>
        <v-chip
          v-if="statusLabel"
          :title="statusDescription"
          class="ms-1 flex-shrink-0"
          x-small>
          {{ statusLabel }}
        </v-chip>
        <!-- The row's actions, on its first line after the chips. -->
        <email-connector-user-setting-grantee-row-menu
          v-if="actionable"
          :current-preset="currentPreset"
          :disabled="disabled"
          :can-extend="canExtend"
          :extend-label="extendLabel"
          :can-choose-folders="actionable && perFolder"
          :current-send-mode="currentSendMode"
          :send-modes="sendModes"
          :can-set-send-mode="canSetSendMode"
          @change-preset="$emit('change-preset', $event)"
          @change-send-mode="$emit('change-send-mode', $event)"
          @extend="$emit('extend')"
          @folders="$emit('folders')"
          @revoke="$emit('revoke')" />
      </div>
      <div class="caption text-sub-title text-truncate">
        {{ grantee.granteeId ? grantee.identifier : $t('UserSettings.emailConnector.sharing.notAnExoUser') }}
      </div>
      <div class="caption text-sub-title text-wrap">{{ rightsSummary }}</div>
      <!-- What the grant covers beside the Inbox (EXO-90548). -->
      <div v-if="inboxOnlyShare" class="caption text-sub-title">{{ $t('UserSettings.emailConnector.sharing.inboxOnly') }}</div>
      <div v-if="notShared" class="caption warning--text text-wrap">{{ notShared }}</div>
      <!-- The owner's own choices on the role folders (EXO-90556); the owner's other
           folders are listed, read live, in "Folders and access". -->
      <div v-if="folderExceptions" class="caption text-sub-title text-wrap">{{ folderExceptions }}</div>
      <!-- Whether they may write mail in the owner's name (EXO-90582), and what stands in
           the way when the mail server does not follow. -->
      <div v-if="sendModeCaption" class="caption text-sub-title text-wrap">{{ sendModeCaption }}</div>
      <div v-if="sendModeWarning" class="caption warning--text text-wrap">{{ sendModeWarning }}</div>
      <!-- Where the access was written: a small marker, not the chip -- a share made
           in the mail server's own interface is the server's, and says so. -->
      <div v-if="discovered" class="caption text-sub-title text-wrap">
        <v-icon size="10" class="me-1 icon-default-color">fa-server</v-icon>
        {{ $t('UserSettings.emailConnector.sharing.origin.SERVER') }}
      </div>
    </v-list-item-content>
  </v-list-item>
</template>

<script>
import { sharedMailboxCapabilities } from '../../../email-connector-mail-box/js/EmailConnectorSharedMailboxRules.js';
import EmailConnectorUserSettingGranteeRowMenu from './EmailConnectorUserSettingGranteeRowMenu.vue';

// The presets the owner can set from here.
const PRESETS = ['READER', 'EDITOR'];

// The role folders a grant covers beside the Inbox, in the order they are said.
const ROLES = ['SENT', 'ARCHIVE', 'TRASH', 'JUNK'];

export default {
  components: {
    'email-connector-user-setting-grantee-row-menu': EmailConnectorUserSettingGranteeRowMenu,
  },
  props: {
    // One entry of the owner's ACL: {identifier, granteeId, delegation, preset, rights, nativeRights, affordances}.
    grantee: { type: Object, required: true },
    disabled: { type: Boolean, default: false },
    // Whether the owner's mail server shares folder by folder (EXO-90556).
    perFolder: { type: Boolean, default: false },
    // The shapes of writing in the owner's name her mail server is declared to accept (EXO-90582).
    sendModes: { type: Array, default: () => [] },
  },
  computed: {
    /**
     * The preset the letters read as, null when they read as none.
     *
     * @returns {String} READER, EDITOR, or null
     */
    currentPreset() {
      return PRESETS.includes(this.grantee.preset) ? this.grantee.preset : null;
    },
    /**
     * The chip: Reader, Editor, or Custom for letters no preset expresses.
     *
     * @returns {String} the label
     */
    presetLabel() {
      return this.currentPreset
        ? this.$t(`UserSettings.emailConnector.sharing.preset.${this.currentPreset}`)
        : this.$t('UserSettings.emailConnector.sharedWithMe.preset.CUSTOM');
    },
    /**
     * Whether the access was written on the mail server rather than from eXo.
     *
     * @returns {Boolean} true for a server-made share
     */
    discovered() {
      return !this.grantee.delegation || this.grantee.delegation.origin === 'SERVER';
    },
    /**
     * The short status of an eXo invitation, none for a server-made share.
     *
     * @returns {String} the label, or empty
     */
    statusLabel() {
      const status = this.grantee.delegation?.status;
      return status && !this.discovered ? this.$t(`UserSettings.emailConnector.sharing.status.short.${status}`) : '';
    },
    /**
     * The status's full sentence, for the chip's tooltip.
     *
     * @returns {String} the sentence, or empty
     */
    statusDescription() {
      const status = this.grantee.delegation?.status;
      return status ? this.$t(`UserSettings.emailConnector.sharing.status.${status}`) : '';
    },
    /**
     * What the person can do in the owner's mailbox, from the rule the mail drawer's
     * controls follow -- never a move the drawer does not offer -- and, with w, that what
     * they mark as favorite is the owner's favorite too (EXO-90550).
     *
     * @returns {String} the sentence
     */
    rightsSummary() {
      const capabilities = sharedMailboxCapabilities(this.grantee.affordances);
      let sentence;
      if (capabilities.moveOut) {
        sentence = this.$t(capabilities.markRead
          ? 'UserSettings.emailConnector.sharing.rights.markReadMoveOut'
          : 'UserSettings.emailConnector.sharing.rights.moveOut');
      } else {
        sentence = this.$t(capabilities.markRead
          ? 'UserSettings.emailConnector.sharing.rights.markRead'
          : 'UserSettings.emailConnector.sharing.rights.read');
      }
      // With w, what they mark as favorite is a favorite for the owner too (EXO-90550).
      return capabilities.star ? `${sentence} ${this.$t('UserSettings.emailConnector.sharing.rights.star')}` : sentence;
    },
    /**
     * Whether the owner can act on the row: one eXo holds, still on the server.
     *
     * @returns {Boolean} true when the menu is offered
     */
    actionable() {
      const delegation = this.grantee.delegation;
      return !!delegation?.id && delegation.status !== 'REVOKED' && delegation.status !== 'GONE';
    },
    /**
     * Whether eXo may extend the share: the owner's mailbox has role folders (Sent,
     * Archive, Trash, Spam) the share does not cover yet -- the server says which
     * (extendableRoles), and only for a share eXo wrote, accepted or still on offer
     * (EXO-90548, decision 3b). A share made on the server is never rewritten from here.
     *
     * @returns {Boolean} true when "Share ... too" is offered
     */
    canExtend() {
      return this.actionable && !this.discovered && this.extendableRoles.length > 0;
    },
    /**
     * The owner's role folders an Extend would add, as the server listed them.
     *
     * @returns {Array} the roles, possibly empty
     */
    extendableRoles() {
      return this.grantee.extendableRoles || [];
    },
    /**
     * "Share Spam too", naming what an Extend would add, as the owner reads the roles.
     *
     * @returns {String} the menu label, or empty
     */
    extendLabel() {
      if (!this.canExtend) {
        return '';
      }
      const names = this.extendableRoles.map(role => this.$t(`UserSettings.emailConnector.sharing.role.${role}`)).join(', ');
      return this.$t('UserSettings.emailConnector.sharing.extendRoles', { 0: names });
    },
    /**
     * Whether the share covers the owner's Inbox alone -- written by eXo before folders
     * were shared (EXO-90548).
     *
     * @returns {Boolean} true when "Sees your Inbox only" is said
     */
    inboxOnlyShare() {
      return this.actionable && !this.discovered && !!this.grantee.delegation?.inboxOnly;
    },
    /**
     * The owner's per-folder exceptions to the preset on the role folders, said on the
     * row -- "Trash: Reader · Spam: Not shared" -- or nothing (EXO-90556).
     *
     * @returns {String} the sentence, or empty
     */
    folderExceptions() {
      const exceptions = this.actionable ? this.grantee.delegation.folderAccess || {} : {};
      return ROLES.filter(role => exceptions[role])
        .map(role => this.$t('UserSettings.emailConnector.sharing.folders.exception', {
          0: this.$t(`UserSettings.emailConnector.sharing.role.${role}`),
          1: exceptions[role] === 'NONE'
            ? this.$t('UserSettings.emailConnector.sharing.folders.none')
            : this.$t(`UserSettings.emailConnector.sharing.preset.${exceptions[role]}`),
        }))
        .join(' · ');
    },
    /**
     * Whether the share is on offer or in use: the only states a consent to writing in
     * the owner's name lives in (EXO-90582).
     *
     * @returns {Boolean} true for a pending or accepted share
     */
    live() {
      const status = this.grantee.delegation?.status;
      return this.actionable && (status === 'PENDING' || status === 'ACCEPTED');
    },
    /**
     * The owner's consent to this person writing mail in her name, as recorded.
     *
     * @returns {String} NONE, ON_BEHALF or AS
     */
    currentSendMode() {
      const mode = this.live ? this.grantee.delegation.sendMode : null;
      return mode === 'ON_BEHALF' || mode === 'AS' ? mode : 'NONE';
    },
    /**
     * Whether the menu offers the consent: a live share eXo made, on a server declared to
     * accept a shape -- or one that carries a consent, which can always be withdrawn.
     *
     * @returns {Boolean} true when the section is offered
     */
    canSetSendMode() {
      return this.live && !this.discovered && (this.sendModes.length > 0 || this.currentSendMode !== 'NONE');
    },
    /**
     * "May send mail on your behalf" / "as you", or nothing.
     *
     * @returns {String} the sentence, or empty
     */
    sendModeCaption() {
      return this.currentSendMode === 'NONE' ? '' : this.$t(`UserSettings.emailConnector.sharing.sendMode.caption.${this.currentSendMode}`);
    },
    /**
     * What stands in the way of a consent: the mail server refused a mail in the owner's
     * name since she set it, or no longer is declared to accept that shape.
     *
     * @returns {String} the sentence, or empty
     */
    sendModeWarning() {
      if (this.currentSendMode === 'NONE') {
        return '';
      }
      const refused = this.grantee.delegation.sendRefusedDate;
      if (refused) {
        const language = window.eXo?.env?.portal?.language || 'en';
        return this.$t('UserSettings.emailConnector.sharing.sendMode.refused', { 0: new Date(refused).toLocaleDateString(language) });
      }
      return this.sendModes.includes(this.currentSendMode) ? '' : this.$t('UserSettings.emailConnector.sharing.sendMode.unavailable');
    },
    /**
     * The owner's folders the grant found but the server refused to share, said on the
     * row -- "Trash could not be shared" -- or nothing.
     *
     * @returns {String} the sentence, or empty
     */
    notShared() {
      const roles = this.actionable ? this.grantee.delegation.rolesNotShared || [] : [];
      return roles.length
        ? this.$t('UserSettings.emailConnector.sharing.notShared', { 0: roles.map(role => this.$t(`UserSettings.emailConnector.sharing.role.${role}`)).join(', ') })
        : '';
    },
  },
};
</script>
