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
  <exo-drawer
    id="emailBoxDrawer"
    ref="emailBoxDrawer"
    v-model="emailBoxDrawer"
    :loading="loading"
    :right="!$vuetify.rtl"
    :allow-expand="!$root.isMobile"
    @closed="close">
    <template #title>
      <span>{{ $t('emailConnector.mailBox.list.drawer.title') }}</span>
    </template>
    <template v-if="emailBoxDrawer" #content>
      <div class="d-flex flex-column align-center justify-center full-width full-height">
        <email-connector-admin-icon
          icon="far fa-envelope"
          :icon-size="60" />
        <div class="mt-5">
          {{ $t('emailConnector.mailBox.list.drawer.noEmail') }}
        </div>
      </div>
    </template>
    <template #footer>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    emailBoxDrawer: false,
  }),
  props: {
    userEmailSetting: {
      type: Object,
      default: null,
    },
  },
  created() {
    this.$root.$on('open-mail-box-drawer', this.open);
  },
  methods: {
    open() {
      this.$refs.emailBoxDrawer.open();
    },
    close() {
      this.$refs.emailBoxDrawer.close();
    }
  }
};
</script>