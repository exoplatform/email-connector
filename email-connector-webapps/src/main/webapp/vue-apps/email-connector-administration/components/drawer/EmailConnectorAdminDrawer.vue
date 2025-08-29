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
    id="emailConnectorDrawer"
    ref="drawer"
    v-model="drawer"
    :loading="loading"
    :right="!$vuetify.rtl"
    :allow-expand="!$root.isMobile"
    @closed="close">
    <template #title>
      <span>{{ $t('emailConnector.admin.connectors.drawer.title') }}</span>
    </template>
    <template v-if="drawer" #content>
      <form
        ref="addConnectorForm"
        class="mx-5 mt-5"
        @submit.stop.prevent="0">
        <div class="mb-3">
          {{ $t('emailConnector.admin.connectors.drawer.connector.updateTheIcon') }}
        </div>
        <email-connector-admin-image-input
          v-model="emailConnector.imageUploadId"
          :email-connector="emailConnector"
          class="mb-7"
          @icon="emailConnector.icon = $event"
          @reset="resetImage" />
        <v-label for="emailConnectorName">
          {{ $t('emailConnector.admin.connectors.drawer.connector.name') }}
        </v-label>
        <translation-text-field
          ref="emailConnectorName"
          id="emailConnectorName"
          v-model="emailConnectorNameTranslations"
          :placeholder="$t('emailConnector.admin.connectors.drawer.connector.placeHolder.name')"
          name="emailConnectorName"
          drawer-title="emailConnector.admin.connectors.drawer.connector.name"
          class="width-auto flex-grow-1 mt-3 mb-7"
          no-expand-icon
          back-icon
          required />
        <v-list-item class="pa-0 mb-7" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('emailConnector.admin.connectors.drawer.connector.imapUrl') }}
            </v-list-item-title>
            <v-text-field
              v-model="emailConnector.imapUrl"
              :aria-label="$t('emailConnector.admin.connectors.drawer.connector.imapUrl')"
              :placeholder="$t('emailConnector.admin.connectors.drawer.connector.placeHolder.imapUrl')"
              class="pt-3"
              type="text"
              required="required"
              outlined
              dense />
          </v-list-item-content>
        </v-list-item>
        <v-list-item class="pa-0" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('emailConnector.admin.connectors.drawer.connector.port') }}
            </v-list-item-title>
            <v-text-field
              v-model="emailConnector.port"
              :aria-label="$t('emailConnector.admin.connectors.drawer.connector.port')"
              :placeholder="$t('emailConnector.admin.connectors.drawer.connector.placeHolder.port')"
              class="pt-3"
              type="text"
              required="required"
              outlined
              dense />
          </v-list-item-content>
        </v-list-item>
      </form>
    </template>
    <template #footer>
      <div class="d-flex">
        <v-spacer />
        <v-btn
          class="btn"
          @click="close">
          {{ $t('emailConnector.admin.connectors.drawer.cancel') }}
        </v-btn>
        <v-btn
          :disabled="addDisabled"
          @click="createConnector"
          class="btn btn-primary ms-5 me-5">
          {{ $t('emailConnector.admin.connectors.drawer.add') }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    drawer: false,
    emailConnectorNameTranslations: {},
    loading: false,
    emailConnector: {},
  }),
  computed: {
    name() {
      return this.emailConnectorNameTranslations[eXo.env.portal.defaultLanguage];
    },
    addDisabled() {
      return !this.emailConnector.name || !this.emailConnector.imapUrl || !this.emailConnector.port;
    },
  },
  watch: {
    name(newVal) {
      if (this.emailConnector) {
        this.emailConnector.name = newVal;
      }
    }
  },
  created() {
    this.$root.$on('open-add-email-connector-drawer', this.open);
  },
  methods: {
    open() {
      this.$refs.drawer.open();
    },
    close() {
      this.emailConnectorNameTranslations = {};
      this.emailConnector.icon = null;
      this.emailConnector.imapUrl = '';
      this.emailConnector.port = '';
      this.emailConnector.imageUploadId = null;
      this.$refs.drawer.close();
    },
    resetImage() {
      this.emailConnector.imageUrl = null;
      this.emailConnector.imageFileId = null;
    },
    async createConnector() {
      this.loading = true;
      try {
        this.emailConnector.icon = this.emailConnector.icon || 'fa-envelope';
        const emailConnector = await this.$emailConnectorAdministrationService.createEmailConnector(this.emailConnector);
        await this.$translationService.saveTranslations('emailConnector',  emailConnector.id, 'name', this.emailConnectorNameTranslations);
        this.$root.$emit('alert-message', this.$t('emailConnector.admin.connectors.drawer.add.success'), 'success');
        this.$emit('emailConnector-created');
        this.close();
      } catch (e) {
        this.$root.$emit('alert-message', this.$t('emailConnector.admin.connectors.drawer.add.error'), 'error');
      } finally {
        this.loading = false;
      }
    }
  }
};
</script>