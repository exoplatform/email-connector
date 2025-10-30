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
  <v-list class="pt-0 pb-8">
    <v-divider class="pb-3" />
    <email-connector-mail-box-drawer-list-item-detail-header-item
      class="pb-3"
      v-if="email.sender"
      :label="$t('emailConnector.mailBox.list.drawer.detail.from')"
      :values="[email.sender]" />
    <email-connector-mail-box-drawer-list-item-detail-header-item
      class="pb-3"
      v-if="email.to.length > 0"
      :label="$t('emailConnector.mailBox.list.drawer.detail.to')"
      :values="email.to" />
    <email-connector-mail-box-drawer-list-item-detail-header-item
      class="pb-3"
      v-if="email.cc.length > 0"
      :label="$t('emailConnector.mailBox.list.drawer.detail.cc')"
      :values="email.cc" />
    <email-connector-mail-box-drawer-list-item-detail-header-item
      class="pb-3"
      v-if="email.bcc.length > 0"
      :label="$t('emailConnector.mailBox.list.drawer.detail.bcc')"
      :values="email.bcc" />
    <v-divider />
  </v-list>
</template>

<script>
export default {
  props: {
    email: {
      type: Object,
      default: () => null,
    },
  }
};
</script>