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
  <v-list-item-action class="my-0">
    <v-menu
      ref="menu"
      v-model="menu"
      :nudge-top="-1"
      content-class="no-min-width border-radius z-index-modal overflow-hidden"
      close-on-content-click
      offset-y
      left
      bottom>
      <template #activator="{ on, attrs }">
        <v-btn
          v-bind="attrs"
          class="pa-0"
          width="28"
          min-width="28"
          height="28"
          :title="$t('emailConnector.mailBox.list.drawer.detail.options.tooltip')"
          icon
          v-on="on"
          @click.stop.prevent>
          <v-icon
            size="20"
            class="icon-default-color">
            fa-ellipsis-v
          </v-icon>
        </v-btn>
      </template>
      <v-list class="pa-0" @close="menu = false">
        <v-list-item
          class="ps-2 pe-3 height-auto"
          @click.stop="updateEmailReadStatus">
          <v-sheet
            class="d-flex"
            width="28"
            height="36">
            <v-icon
              class="icon-default-color mx-auto"
              size="16">
              {{ email.read ? 'fa-mail-bulk' : 'fa-envelope-open-text' }}
            </v-icon>
          </v-sheet>
          <span v-if="email.read">
            {{ $t('emailConnector.mailBox.list.drawer.detail.unread.label') }}
          </span>
          <span v-else>
            {{ $t('emailConnector.mailBox.list.drawer.detail.read.label') }}
          </span>
        </v-list-item>
      </v-list>
    </v-menu>
  </v-list-item-action>
</template>

<script>
export default {
  data() {
    return {
      menu: false,
    };
  },
  props: {
    email: {
      type: Object,
      default: () => null,
    }
  },
  watch: {
    menu() {
      if (this.menu) {
        this.$emit('open');
      } else {
        this.$emit('close');
      }
    }
  },
  methods: {
    updateEmailReadStatus() {
      this.$emit('close');
      this.$root.$emit('update-email-read-status', { emailId: this.email.mailRemoteId, read: !this.email.read });
    },
  }
};
</script>