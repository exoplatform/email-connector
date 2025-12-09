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
  <div
    role="button"
    tabindex="0"
    @mouseenter="!isMobile && (isHover = true)"
    @mouseleave="!isMobile && (isHover = false)"
    @focusin="!isMobile && (isHover = true)"
    @focusout="!isMobile && (isHover = false)"
    :class="{ 'light-grey-background-color': !isMobile && isHover }">
    <div
      role="button"
      tabindex="0"
      class="no-select"
      @click="openDetail"
      @keydown.enter="openDetail"
      v-touch-hold="openActionMenuDrawer">
      <v-list-item
        style="min-height: 0"
        :class="['px-0', 'pb-2', { 'ms-n3': !email.read }]">
        <v-list-item-avatar
          v-if="!email.read"
          width="8"
          min-width="8"
          height="8"
          class="my-0 me-1 error-color-background" />
        <v-list-item-content :class="['py-0', { 'font-weight-bold': !email.read }]">
          <v-list-item-title v-text="email.sender.name" />
        </v-list-item-content>
        <v-list-item-action class="my-0">
          <v-list-item-subtitle v-text="recievedDate" />
        </v-list-item-action>
      </v-list-item>
      <v-list-item
        style="min-height: 0"
        class="px-0">
        <v-list-item-content class="py-0">
          <v-list-item-subtitle :class="['mb-1 text-color', { 'font-weight-bold': !email.read }]" v-text="email.subject" />
          <v-list-item-subtitle v-text="email.content?.body" />
        </v-list-item-content>
        <email-connector-mail-box-drawer-list-item-action-menu
          v-if="(!isMobile && isHover) || menuOpen"
          ref="menu"
          :email="email"
          @open="menuOpen = true"
          @close="menuOpen = false" /> 
      </v-list-item>
    </div>
    <email-connector-mail-box-drawer-list-item-attachments
      :email-attachments="emailAttachments"
      v-if="hasAttachments" />
  </div>
</template>

<script>  
export default {
  data() {
    return {
      menu: false,
      menuOpen: false,
      isHover: false
    };
  },
  props: {
    email: {
      type: Object,
      default: () => null,
    },
  },
  computed: {
    recievedDate() {
      return this.$emailConnectorMailBoxService.formatDateString(this.email.recievedDate, this.$t('emailConnector.mailBox.list.drawer.yesterday'));
    },
    isMobile() {
      return this.$vuetify.breakpoint.smAndDown;
    },
    hasAttachments() {
      return this.emailAttachments.length > 0;
    },
    emailAttachments() {
      return this.email.content?.attachments || [];
    },
  },
  methods: {
    openDetail() {
      this.$root.$emit('open-email-detail-drawer', this.email.mailRemoteId);
    },
    openActionMenuDrawer() {
      this.$root.$emit('open-email-action-menu-drawer', this.email);
    }
  }
};
</script>