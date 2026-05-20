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
  <div
    @mouseenter="!isMobile && (isHover = true)"
    @mouseleave="!isMobile && (isHover = false)"
    @focusin="!isMobile && (isHover = true)"
    @focusout="!isMobile && (isHover = false)"
    :class="[
      backgroundClass,
      selectMode ? 'ps-4' : 'ps-7'
    ]"
    class="position-relative no-border overflow-hidden pt-3 pb-3 pe-4">
    <div
      v-if="absolute"
      :class="[
        'position-absolute',
        'my-auto',
        't-0',
        'b-0',
        'd-flex',
        'align-center',
        'justify-center',
        movingLeft ? 'blue darken-1 r-0' : 'red darken-1 l-0'
      ]"
      :style="{
        width: `${gapSize}px`,
        overflow: 'hidden'
      }">
      <v-card
        class="transparent d-flex flex-column align-center justify-center"
        :width="gapSize"
        min-width="85"
        dark
        flat>
        <v-icon size="24">{{ movingLeft && 'fa-archive' || 'fa-trash' }}</v-icon>
        <span class="text-no-wrap mt-3">{{ movingLeft && $t('emailConnector.mailBox.list.drawer.detail.archive.label') || $t('emailConnector.mailBox.list.drawer.detail.delete.label') }}</span>
      </v-card>
    </div>
    <div
      class="d-flex"
      v-touch="{
        start: moveStart,
        end: moveEnd,
        move: moveSwipe,
      }"
      :class="absolute && 'position-relative' || 'position-static'"
      :style="absolute && {
        transform: `translateX(${left}px)`,
        width: `${minWidth}px`,
        'min-width': `${minWidth}px`,
      }">
      <v-checkbox
        v-if="selectMode"
        class="me-0 pt-0 align-self-center"
        color="#707070"
        background-color="transparent"
        :input-value="selected"
        @click.stop
        @change="onSelectChange" />
      <div class="flex-grow-1 no-min-width">    
        <!-- eslint-disable vuejs-accessibility/no-static-element-interactions -->
        <div
          class="clickable"
          tabindex="0"
          :aria-label="ariaLabel"
          @click="openDetail"
          @keydown.enter="openDetail"
          @keydown.space.prevent="openDetail"
          v-touch-hold="openActionMenuDrawer">
          <v-list-item
            ref="mail"
            :class="['height-auto', 'px-0', 'pb-2', { 'ms-n3': !email.read }]">
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
              <v-list-item-subtitle v-text="receivedDate" />
            </v-list-item-action>
          </v-list-item>
          <v-list-item
            class="px-0 height-auto">
            <v-list-item-content class="py-0">
              <v-list-item-subtitle :class="['mb-1 text-color', { 'font-weight-bold': !email.read }]" v-text="subject" />
              <v-list-item-subtitle v-text="excerpt" />
            </v-list-item-content>
            <email-connector-mail-box-drawer-list-item-action-menu
              v-if="!selectMode && !isMobile"
              :style="{
                opacity: isHover || menuOpen ? 1 : 0,
                pointerEvents: isHover || menuOpen ? 'auto' : 'none'
              }"
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
    </div>
  </div>
</template>

<script>  
export default {
  data() {
    return {
      menuOpen: false,
      isHover: false,
      absolute: false,
      left: 0,
      startEvent: null,
      minWidth: 0,
      movingLeft: false,
      isSwiping: false,
    };
  },
  props: {
    email: {
      type: Object,
      default: () => null,
    },
    selectMode: {
      type: Boolean,
      default: false,
    },
    selectedEmails: {
      type: Array,
      default: () => [],
    },
    emails: {
      type: Array,
      default: () => [],
    },
    syncInProgress: {
      type: Boolean,
      default: false,
    },
    expanded: {
      type: Boolean,
      default: false,
    },
    openedEmailId: {
      type: String,
      default: null,
    },
    webmailUrl: {
      type: String,
      default: null,
    },
  },
  computed: {
    gapSize() {
      return Math.abs(this.left);
    },
    receivedDate() {
      return this.$emailConnectorMailBoxService.formatDateString(this.email.receivedDate, this.$t('emailConnector.mailBox.list.drawer.yesterday'));
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
    excerpt() {
      return this.email.content?.excerpt || this.$t('emailConnector.mailBox.list.drawer.emptyEmail');
    },
    subject() {
      return this.email.subject || this.$t('emailConnector.mailBox.list.drawer.noSubject');
    },
    selected() {
      return this.selectedEmails.includes(this.email.mailRemoteId);
    },
    opened() {
      return this.openedEmailId === this.email.mailRemoteId;
    },
    backgroundClass() {
      if (this.isMobile) {
        return 'no-select';
      }
      if (this.expanded && (this.isHover || this.opened)) {
        return 'grey-lighten1-background-opacity-3';
      }
      if (this.isHover) {
        return 'light-grey-background-color';
      }
      return '';
    },
    ariaLabel() {
      return `Open email from ${this.email.sender.name} about ${this.email.subject}`;
    },
  },
  methods: {
    openDetail() {
      if (this.selectMode) {
        this.$root.$emit('select-email', { emailId: this.email.mailRemoteId, selected: !this.selected });
      }
      else {
        if (this.expanded) {
          this.$root.$emit('open-email-detail-content', this.email.mailRemoteId);
          this.$root.$emit('set-opened', this.email.mailRemoteId);
        }
        else {
          this.$root.$emit('open-email-detail-drawer', this.email.mailRemoteId, this.emails, this.syncInProgress, this.webmailUrl);
        }
      }
    },
    openActionMenuDrawer() {
      if (!this.selectMode && !this.isSwiping) {
        this.$root.$emit('open-email-action-menu-drawer', this.email);
      }
    },
    async reset() {
      this.absolute = false;
      await this.$nextTick();
      this.left = 0;
      this.movingLeft = false;
      this.startEvent = null;
      this.minWidth = 0;
      this.isSwiping = false;
    },
    async moveStart() {
      if (this.absolute) {
        return;
      }
      await this.reset();
      this.minWidth = Math.max(this.minWidth, this.$refs?.mail?.$el?.offsetWidth);
    },
    moveEnd() {
      const deleteEmail = this.left > 0;
      const confirm = Math.abs(this.left) > (this.minWidth / 2);
      if (confirm) {
        if (deleteEmail) {
          this.$root.$emit('delete-email', [this.email.mailRemoteId]);
        } else {
          this.$root.$emit('archive-email', [this.email.mailRemoteId]);
        }
      } else {
        this.reset();
      }
    },
    moveSwipe(event) {
      if (this.selectMode) {
        return;
      }
      if (!this.startEvent) {
        this.startEvent = event;
        return;
      }
      const deltaX = event.touchmoveX - this.startEvent.touchmoveX;
      if (!this.absolute && Math.abs(deltaX) > 10) {
        this.absolute = true;
        this.isSwiping = true;
      }
      if (!this.absolute) {
        return;
      }
      this.left = deltaX;
      this.movingLeft = this.left < 0;
    },
    onSelectChange(value) {
      this.$root.$emit('select-email', { emailId: this.email.mailRemoteId, selected: value });
    }
  }
};
</script>