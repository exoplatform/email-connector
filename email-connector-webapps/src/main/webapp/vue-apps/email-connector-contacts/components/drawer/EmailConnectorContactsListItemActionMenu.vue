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
  <!-- One row's 3-dots menu. This component exists only while its row is
       hovered or focused (the row mounts it, see there), so everything it costs
       is paid once, by one row, and never five hundred times.

       Not attached to the row: an attached menu renders inside the row, and the
       list clips horizontally (overflow-x: hidden, for the long addresses) —
       the menu would be cut off at the drawer's edge. Detached, it is placed by
       Vuetify at the app root and clipped by nothing. The price is that the
       pointer leaving the row for the menu is a real mouseleave, which is why
       the row is told when the menu opens and closes. -->
  <v-menu
    v-model="menu"
    content-class="no-min-width border-radius z-index-modal overflow-hidden text-no-wrap"
    close-on-content-click
    offset-y
    left
    bottom>
    <template #activator="{on, attrs}">
      <v-btn
        v-bind="attrs"
        class="pa-0"
        width="28"
        min-width="28"
        height="28"
        :title="$t('emailConnector.contacts.menu.tooltip')"
        icon
        v-on="on"
        @click.stop.prevent
        @keydown.enter.stop
        @keydown.space.stop>
        <v-icon
          size="16"
          class="icon-default-color">
          fas fa-ellipsis-v
        </v-icon>
      </v-btn>
    </template>
    <email-connector-contacts-list-item-action-menu-items
      :contact="contact"
      @start-select="$emit('start-select')"
      @close="menu = false" />
  </v-menu>
</template>

<script>
export default {
  data() {
    return {
      menu: false,
    };
  },
  props: {
    // The contact this row's menu acts on.
    contact: {
      type: Object,
      required: true,
    },
  },
  watch: {
    menu() {
      this.$emit(this.menu ? 'open' : 'close');
    },
  },
};
</script>
