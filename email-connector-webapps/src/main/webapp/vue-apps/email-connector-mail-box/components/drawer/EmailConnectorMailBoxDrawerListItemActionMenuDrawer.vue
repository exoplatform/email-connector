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
    <v-overlay
      z-index="2000"
      :value="emailBoxActionMenuDrawer"
      @click.native="close" />
    <exo-drawer
      ref="emailBoxActionMenuDrawer"
      v-model="emailBoxActionMenuDrawer"
      bottom>
      <template #content>
        <email-connector-mail-box-drawer-list-item-action-menu-items
          @close="close"
          :email="email" 
          restricted />
      </template>
    </exo-drawer>
  </div>
</template>

<script>

export default {
  data() {
    return {
      email: null,
      emailBoxActionMenuDrawer: false
    };
  },
  created() {
    this.$root.$on('open-email-action-menu-drawer', this.open);
  },
  beforeDestroy() {
    this.$root.$off('open-email-action-menu-drawer', this.open);
  },
  methods: {
    open(email) {
      this.email = email;
      this.$refs.emailBoxActionMenuDrawer.open();
    },
    close() {
      this.$refs.emailBoxActionMenuDrawer.close();
    }
  }
};
</script>