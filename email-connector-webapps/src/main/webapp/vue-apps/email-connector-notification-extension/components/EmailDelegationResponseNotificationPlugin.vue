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
  <!-- EXO-90503 - where a share you are party to now stands: the grantee accepted,
       declined or left it, or the owner took your access away. One renderer for the
       four, as there is one plugin: the transition is in the sentence the server wrote
       in the reader's language, and the icon says whether it is still in use. -->
  <div
    role="button"
    tabindex="0"
    @click.stop.prevent="openEmailBox"
    @keydown.enter="openEmailBox"
    @keydown.space="openEmailBox">
    <user-notification-template
      :notification="notification"
      :url="link"
      :message="message"
      :loading="loading">
      <template #avatar>
        <div>
          <v-icon size="40" :class="iconClass">{{ icon }}</v-icon>
        </div>
      </template>
      <template #actions>
        <div class="text-truncate">
          <v-icon size="14" class="me-1 icon-default-color">fa-share-alt</v-icon>
          {{ title }}
        </div>
      </template>
    </user-notification-template>
  </div>
</template>

<script>
export default {
  props: {
    notification: {
      type: Object,
      default: null,
    },
    loading: {
      type: Boolean,
      default: false,
    },
  },
  computed: {
    /**
     * @returns {Object} the notification's parameters
     */
    parameters() {
      return this.notification?.parameters || {};
    },
    /**
     * @returns {String} which transition this is: ACCEPTED, DECLINED, LEFT or REVOKED
     */
    response() {
      return this.parameters.DELEGATION_RESPONSE || '';
    },
    /**
     * @returns {String} the icon of the transition
     */
    icon() {
      return this.response === 'ACCEPTED' ? 'fa-check-circle' : 'fa-times-circle';
    },
    /**
     * @returns {String} its colour class
     */
    iconClass() {
      return this.response === 'ACCEPTED' ? 'success--text' : 'icon-default-color';
    },
    /**
     * @returns {String} the heading the server wrote in the reader's language
     */
    title() {
      return this.parameters.TITLE || '';
    },
    /**
     * @returns {String} who did what, and what it means for the access
     */
    message() {
      return this.parameters.CONTENT || '';
    },
    /**
     * @returns {String} the mailbox link the server gave
     */
    link() {
      return this.parameters.LINK;
    },
  },
  methods: {
    /**
     * Opens the mailbox, from which the email settings hold the answer.
     *
     * @returns {void}
     */
    openEmailBox() {
      this.$emailConnectorCommonService.openEmailBox();
    },
  },
};
</script>
