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
      no-data-text=""
      hide-default-footer 
      disable-pagination
      disable-filtering
      disable-sort
      dense>
      <template #[`item.icon`]="{ item }">
        <email-connector-icon
          :image-url="item.imageUrl"
          :icon="item.icon"
          class="flex-grow-0 flex-shrink-0 py-1" />
      </template>
      <template #[`item.name`]="{ item }">
        <span>
          {{ item.name }}
        </span>
      </template>
      <template #[`item.active`]="{ item }">
        <div class="d-flex justify-center">
          <v-switch
            v-model="item.active"
            @change="activateItem(item)"
            class="ma-0 pa-0" 
            hide-details />
        </div>
      </template>
      <template #[`item.actions`]="{ item }">
        <v-btn
          icon
          @click="editItem(item)">
          <v-icon size="20">fa-edit</v-icon>
        </v-btn>
        <v-btn
          icon
          color="error"
          @click="openDeleteConfirmDialog(item)">
          <v-icon size="20">fa-trash</v-icon>
        </v-btn>
      </template>
    </v-data-table>
    <confirm-dialog
      ref="deleteConfirmDialog"
      :title="$t('emailConnector.admin.connectors.modal.delete.title')"
      :message="$t('emailConnector.admin.connectors.modal.delete.message')"
      :ok-label="$t('emailConnector.admin.connectors.modal.delete.confirmDelete')"
      :cancel-label="$t('emailConnector.admin.connectors.modal.delete.cancelDelete')"
      @ok="deleteEmailConnector"
      @closed="emailConnectorToDelete = null" />
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
    emailConnectorToDelete: null,
  }),
  created() {
    this.headers = [
      { text: '', value: 'icon', width: '40px'},
      { text: this.$t('emailConnector.admin.connectors.list.name'), value: 'name' },
      { text: this.$t('emailConnector.admin.connectors.list.activate'), align: 'center', value: 'active', width: '80px' },
      { text: this.$t('emailConnector.admin.connectors.list.actions'), align: 'center', value: 'actions', width: '80px' }
    ];
  },
  methods: {
    editItem(item) {
      this.$root.$emit('open-email-connector-drawer', item);
    },
    activateItem(item) {
      this.$emailConnectorAdministrationService.activateEmailConnector(item.id, item.active)
        .then(() =>
        {
          this.$root.$emit('refresh-connectors-list');
          const successAlertMessage = item.active && 'emailConnector.admin.connectors.activate.success' || 'emailConnector.admin.connectors.deactivate.success';
          this.$root.$emit('alert-message', this.$t(`${successAlertMessage}`), 'success');
        })
        .catch(() => {
          const errorAlertMessage = item.active && 'emailConnector.admin.connectors.activate.error' || 'emailConnector.admin.connectors.deactivate.error';
          this.$root.$emit('alert-message', this.$t(`${errorAlertMessage}`), 'error');
        });
    },
    deleteEmailConnector() {
      this.$emailConnectorAdministrationService.deleteEmailConnector(this.emailConnectorToDelete.id)
        .then(() =>
        {
          this.$root.$emit('refresh-connectors-list');
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.connectors.delete.success'), 'success');
          this.emailConnectorToDelete = null;
        })
        .catch(() => this.$root.$emit('alert-message', this.$t('emailConnector.admin.connectors.delete.error'), 'error'));
    },
    openDeleteConfirmDialog(item) {
      this.emailConnectorToDelete = item;
      this.$refs.deleteConfirmDialog.open();
    },
  }
};
</script>
