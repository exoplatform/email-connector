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
  <v-list-item>
    <email-connector-icon
      :image-url="userEmailConnector.imageUrl"
      :icon="userEmailConnector.icon" 
      class="me-5" />
    <v-list-item-content class="py-0">
      <span>{{ userEmailConnector.name }}</span>
    </v-list-item-content>
    <v-list-item-action class="my-0">
      <v-btn
        v-if="userEmailConnector.userConnected"
        :title="$t('UserSettings.emailConnector.connectors.drawer.connector.button.edit.tooltip')"
        @click="$root.$emit('open-user-setting-drawer', userEmailConnector)"
        icon>
        <v-icon size="20" class="icon-default-color">fa-edit</v-icon>
      </v-btn>
    </v-list-item-action>
    <v-list-item-action class="ml-3 my-0">
      <v-btn
        v-if="userEmailConnector.canConnect"
        class="btn"
        @click="connect()">
        {{ connectButtonLabel }}
      </v-btn>
      <v-tooltip
        v-else
        bottom>
        <template #activator="{on, attrs}">
          <div
            v-on="on"
            v-bind="attrs">
            <v-btn
              class="btn"
              disabled
              @click="connect()">
              {{ connectButtonLabel }}
            </v-btn>
          </div>
        </template>
        <span>
          {{ $t('UserSettings.emailConnector.connectors.drawer.connector.button.connect.tooltip') }}
        </span>
      </v-tooltip>
    </v-list-item-action>
  </v-list-item>
</template>

<script>
export default {
  props: {
    userEmailConnector: {
      type: Object,
      default: () => null,
    },
  },
  computed: {
    connectButtonLabel() {
      return this.userEmailConnector.userConnected
        ? this.$t('UserSettings.emailConnector.connectors.drawer.connector.button.disconnect')
        : this.$t('UserSettings.emailConnector.connectors.drawer.connector.button.connect');
    },    
  },
  methods: {
    /**
     * Hands the one button over to whichever flow owns the direction it points.
     * <p>
     * Both directions cost the user their contacts if taken carelessly, so
     * neither is decided here: connecting opens the credentials drawer and
     * disconnecting opens the drawer that asks what should happen to the store
     * -- and, when the store is empty, releases the account in that same click.
     *
     * @returns {void}
     */
    connect() {
      if (!this.userEmailConnector.userConnected) {
        this.$root.$emit('open-user-setting-drawer', this.userEmailConnector);
      }
      else {
        this.$root.$emit('open-user-setting-disconnect-drawer', this.userEmailConnector);
      }
    }
  }
};
</script>