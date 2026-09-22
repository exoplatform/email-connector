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
  <!-- What a share lets someone do, said once and the same way on both sides of it
       (EXO-90503). A share eXo wrote is one of two presets and is named; a share the
       mail server holds may carry any rights at all — BlueMind's read share is lrp and
       its Read verb also covers free/busy and invitations — so that one is NOT squeezed
       into a preset name it does not mean. It is shown as what it is: the server's own
       words when the server has words for it, the RFC 4314 letters otherwise, with the
       list of what they actually unlock under it. -->
  <div class="d-flex flex-column">
    <div class="d-flex align-center flex-wrap">
      <v-chip
        x-small
        outlined
        class="me-2 my-1">
        {{ presetLabel }}
      </v-chip>
      <span v-if="showRaw" class="caption text-sub-title">{{ rawRights }}</span>
    </div>
    <span v-if="affordanceSummary" class="caption text-sub-title">
      {{ affordanceSummary }}
    </span>
  </div>
</template>

<script>
export default {
  props: {
    /** READER, EDITOR or CUSTOM, as the engine recognised it. */
    preset: {
      type: String,
      default: null,
    },
    /** The RFC 4314 letters the mail server answered. */
    rights: {
      type: String,
      default: null,
    },
    /** The server's own vocabulary when it has one — BlueMind's verb list. */
    nativeRights: {
      type: String,
      default: null,
    },
    /** One boolean per control, as the server's letters unlock them. */
    affordances: {
      type: Object,
      default: null,
    },
  },
  computed: {
    /**
     * The name of the share, never a lie: a preset when it IS one of the two presets
     * eXo writes, and "what your mail server granted" otherwise.
     *
     * @returns {String} the localized label
     */
    presetLabel() {
      if (this.preset === 'READER' || this.preset === 'EDITOR') {
        return this.$t(`UserSettings.emailConnector.sharing.preset.${this.preset}`);
      }
      return this.$t('UserSettings.emailConnector.sharing.preset.CUSTOM');
    },
    /**
     * The server's own words, or its letters — shown beside a CUSTOM share, which is
     * the one case where the label above does not say what the share can do.
     *
     * @returns {String} the raw rights
     */
    rawRights() {
      return this.nativeRights || this.rights || '';
    },
    /**
     * @returns {Boolean} whether the raw rights are worth showing
     */
    showRaw() {
      return !!this.rawRights && this.preset !== 'READER' && this.preset !== 'EDITOR';
    },
    /**
     * The controls the rights actually unlock, in words. Read from the affordances the
     * server-side rights model computed, never from the letters here: the letter-to-
     * control mapping is the backend's and there must be one of it.
     *
     * @returns {String} the localized list, or nothing
     */
    affordanceSummary() {
      const granted = ['read', 'markRead', 'star', 'delete', 'moveTarget']
        .filter(name => this.affordances?.[name])
        .map(name => this.$t(`UserSettings.emailConnector.sharing.affordance.${name}`));
      return granted.length ? granted.join(', ') : '';
    },
  },
};
</script>
