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
  <div>
    <email-connector-mail-box-drawer-list-item
      v-for="email in emails"
      :key="email.id"
      :email="email"
      class="pt-3 pb-3 ps-7 pe-4" />
    <email-connector-mail-box-drawer-list-item-detail />
    <email-connector-mail-box-drawer-list-item-action-menu-drawer />
  </div>
</template>

<script>
export default {
  props: {
    emails: {
      type: Array,
      default: () => [],
    },
  }
};
</script>