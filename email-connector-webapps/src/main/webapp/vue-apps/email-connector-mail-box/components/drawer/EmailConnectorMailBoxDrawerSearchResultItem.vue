<!--
Copyright (C) 2025 eXo Platform SAS.

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
  <!-- eslint-disable-next-line vuejs-accessibility/no-static-element-interactions -->
  <!-- data-thread-key is how the arrow keys find the hit they stand on, and aria-current
       tells a screen reader which hit the reader shows (EXO-90414). No outline: the row's
       own background, lit on focus, is the cue, as on the folder list's rows. -->
  <div
    :class="backgroundClass"
    class="clickable ps-7 pe-4 pt-3 pb-3 no-border"
    style="outline: none;"
    tabindex="0"
    :data-thread-key="rowKey"
    :aria-current="opened ? 'true' : null"
    :aria-label="ariaLabel"
    @mouseenter="isHover = true"
    @mouseleave="isHover = false"
    @focusin="isHover = true"
    @focusout="isHover = false"
    @click="$emit('open')"
    @keydown.enter="$emit('open')"
    @keydown.space.prevent="$emit('open')">
    <v-list-item :class="['height-auto', 'px-0', 'pb-2', { 'ms-n3': unread }]">
      <v-list-item-avatar
        v-if="unread"
        width="8"
        min-width="8"
        height="8"
        class="my-0 me-1 error-color-background" />
      <v-list-item-content :class="['py-0', { 'font-weight-bold': unread }]">
        <v-list-item-title v-text="senderName" />
      </v-list-item-content>
      <v-list-item-action class="my-0 flex-row align-center">
        <!-- Every hit knows whether it is favorited: the flag rides in the FLAGS the
             search already fetches, so a mail favorited long ago shows its star even
             when it is far outside the cached window. -->
        <v-icon
          v-if="result.starred"
          size="12"
          class="amber--text text--darken-1 me-1"
          :title="$t('emailConnector.mailBox.list.drawer.detail.favorite.label')">
          fas fa-star
        </v-icon>
        <v-list-item-subtitle v-text="receivedDate" />
      </v-list-item-action>
    </v-list-item>
    <v-list-item class="px-0 height-auto">
      <v-list-item-content class="py-0">
        <v-list-item-subtitle :class="['mb-1 text-color', { 'font-weight-bold': unread }]" v-text="subject" />
      </v-list-item-content>
      <!-- Hits outside the local cache window are fetched on demand when opened. -->
      <v-list-item-action
        v-if="!result.cached"
        :title="$t('emailConnector.mailBox.search.notCached')"
        class="my-0">
        <v-icon size="14" class="text-light-color">fa-cloud-download-alt</v-icon>
      </v-list-item-action>
    </v-list-item>
  </div>
</template>

<script>
export default {
  data() {
    return {
      isHover: false,
    };
  },
  props: {
    // One search hit: { mailRemoteId, folder, subject, sender, receivedDate, read, starred, cached }.
    result: {
      type: Object,
      default: () => null,
    },
    // The hit's key in the results (folder and UID), for the arrow keys.
    rowKey: {
      type: String,
      default: null,
    },
    // Whether the reader shows this hit.
    opened: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    /**
     * The row's background: lit like the folder list's opened row when the reader shows
     * this hit, lighter under the pointer or the keyboard focus.
     *
     * @returns {String} the class, or null
     */
    backgroundClass() {
      if (this.opened) {
        return 'grey-lighten1-background-opacity-3';
      }
      return this.isHover ? 'light-grey-background-color' : null;
    },
    unread() {
      return !this.result.read;
    },
    senderName() {
      return this.result.sender?.name || this.result.sender?.address || '';
    },
    subject() {
      return this.result.subject || this.$t('emailConnector.mailBox.list.drawer.noSubject');
    },
    receivedDate() {
      return this.$emailConnectorMailBoxService.formatDateString(this.result.receivedDate, this.$t('emailConnector.mailBox.list.drawer.yesterday'));
    },
    ariaLabel() {
      return `Open email from ${this.senderName} about ${this.subject}`;
    },
  },
};
</script>
