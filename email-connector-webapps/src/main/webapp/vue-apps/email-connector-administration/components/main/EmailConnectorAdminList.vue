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
    <v-data-table
      :headers="headers"
      :items="connectors"
      hide-default-footer 
      disable-pagination
      disable-filtering
      disable-sort
      dense>
      <template #[`item.icon`]="{ item }">
        <email-connector-admin-icon
          :icon-url="item.imageUrl"
          :icon="item.icon"
          class="flex-grow-0 flex-shrink-0" />
      </template>
      <template #[`item.name`]="{ item }">
        <span>
          {{ item.name }}
        </span>
      </template>
      <template #[`item.active`]="{ item }">
        <v-list-item class="justify-center">
          <v-list-item-action class="my-0">
            <v-switch v-model="item.active" @change="activateItem(item)" />
          </v-list-item-action>
        </v-list-item>
      </template>
      <template #[`item.actions`]="{ item }">
        <v-btn
          icon
          small
          color="primary"
          @click="editItem(item)">
          <v-icon size="20">fa-edit</v-icon>
        </v-btn>
        <v-btn
          icon
          small
          color="error"
          @click="deleteItem(item)">
          <v-icon size="20">fa-trash</v-icon>
        </v-btn>
      </template>
    </v-data-table>
  </div>
</template>

<script>
export default {
  props: {
    connectors: {
      type: Array,
      default: () => [],
    },
  },
  data: () => ({
    headers: [],
  }),
  created() {
    this.headers = [
      { text: '', value: 'icon', width: '40px'},
      { text: this.$t('emailConnector.admin.connectors.list.name'), value: 'name' },
      { text: this.$t('emailConnector.admin.connectors.list.activate'), align: 'center', value: 'active' },
      { text: this.$t('emailConnector.admin.connectors.list.actions'), align: 'center', value: 'actions' }
    ];
  }
};
</script>
