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
      <span>{{ drawerTitle }}</span>
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
          :disabled="disabled"
          @click="saveConnector"
          class="btn btn-primary ms-5 me-5">
          {{ drawerButtonLabel }}
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
    disabled() {
      return !this.emailConnector.name || !this.emailConnector.imapUrl || !this.emailConnector.port;
    },
    drawerTitle() {
      return this.emailConnector.id && `${this.emailConnector.name} ${this.$t('emailConnector.admin.connectors.drawer.edit.title')}`
        || this.$t('emailConnector.admin.connectors.drawer.add.title');
    },
    drawerButtonLabel() {
      return this.emailConnector.id && this.$t('emailConnector.admin.connectors.drawer.save')
        || this.$t('emailConnector.admin.connectors.drawer.add');
    }
  },
  watch: {
    name(newVal) {
      if (this.emailConnector) {
        this.emailConnector.name = newVal;
      }
    }
  },
  created() {
    this.$root.$on('open-email-connector-drawer', this.open);
  },
  methods: {
    async open(emailConnector) {
      this.$refs.drawer.open();
      if (emailConnector) {
        this.emailConnector = { ...emailConnector };
        this.emailConnectorNameTranslations = await this.$translationService.getTranslations('emailConnector', emailConnector.id, 'name');
        this.emailConnector.name = this.emailConnectorNameTranslations[eXo.env.portal.defaultLanguage];
      }
    },
    close() {
      this.emailConnectorNameTranslations = {};
      this.emailConnector.icon = null;
      this.emailConnector.imapUrl = '';
      this.emailConnector.id = '';
      this.emailConnector.port = '';
      this.emailConnector.imageUploadId = null;
      this.emailConnector.imageUrl = null;
      this.$refs.drawer.close();
    },
    resetImage() {
      this.emailConnector.imageUrl = null;
      this.emailConnector.imageFileId = null;
    },
    async saveConnector() {
      this.loading = true;
      const isNew = !this.emailConnector.id;
      let emailConnector = this.emailConnector;
      try {
        this.emailConnector.icon = this.emailConnector.icon || 'fa-envelope';
        this.emailConnector.name = this.emailConnectorNameTranslations[eXo.env.portal.defaultLanguage];
        if (isNew) {
          emailConnector = await this.$emailConnectorAdministrationService.createEmailConnector(this.emailConnector);
        }
        else {
          await this.$emailConnectorAdministrationService.updateEmailConnector(this.emailConnector);
        }
        await this.$translationService.saveTranslations('emailConnector',  emailConnector.id, 'name', this.emailConnectorNameTranslations);
        if (isNew) {
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.connectors.drawer.add.success'), 'success');
        }
        else {
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.connectors.drawer.edit.success'), 'success');
        }
        this.$emit('emailConnector-saved');
        this.close();
      } catch (e) {
        if (isNew) {
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.connectors.drawer.add.error'), 'error');
        }
        else {
          this.$root.$emit('alert-message', this.$t('emailConnector.admin.connectors.drawer.edit.error'), 'error');
        } 
      } finally {
        this.loading = false;
      }
    }
  }
};
</script>