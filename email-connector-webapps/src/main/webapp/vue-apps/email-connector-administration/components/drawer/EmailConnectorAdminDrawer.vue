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
    ref="emailConnectorDrawer"
    v-model="emailConnectorDrawer"
    :loading="loading"
    right
    allow-expand
    @closed="close">
    <template #title>
      <span>{{ drawerTitle }}</span>
    </template>
    <template v-if="emailConnectorDrawer" #content>
      <form
        ref="adminConnectorForm"
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
        <v-list-item-title class="pa-0 mt-7 mb-4 text-header">
          {{ $t('emailConnector.admin.connectors.drawer.connector.imapSettings') }}
        </v-list-item-title>
        <v-list-item class="pa-0 mb-5" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title class="my-0">
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
            <v-list-item-title class="my-0">
              {{ $t('emailConnector.admin.connectors.drawer.connector.imapPort') }}
            </v-list-item-title>
            <v-text-field
              v-model="emailConnector.imapPort"
              :aria-label="$t('emailConnector.admin.connectors.drawer.connector.imapPort')"
              :placeholder="$t('emailConnector.admin.connectors.drawer.connector.placeHolder.imapPort')"
              class="pt-3"
              type="text"
              required="required"
              outlined
              dense />
          </v-list-item-content>
        </v-list-item>
        <v-list-item-title class="pa-0 mt-7 mb-4 text-header">
          {{ $t('emailConnector.admin.connectors.drawer.connector.smtpSettings') }}
        </v-list-item-title>
        <v-list-item class="pa-0 mb-5" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title class="my-0">
              {{ $t('emailConnector.admin.connectors.drawer.connector.smtpUrl') }}
            </v-list-item-title>
            <v-text-field
              v-model="emailConnector.smtpUrl"
              :aria-label="$t('emailConnector.admin.connectors.drawer.connector.smtpUrl')"
              :placeholder="$t('emailConnector.admin.connectors.drawer.connector.placeHolder.smtpUrl')"
              class="pt-3"
              type="text"
              required="required"
              outlined
              dense />
          </v-list-item-content>
        </v-list-item>
        <v-list-item class="pa-0 mb-5" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title>
              {{ $t('emailConnector.admin.connectors.drawer.connector.smtpPort') }}
            </v-list-item-title>
            <v-text-field
              v-model="emailConnector.smtpPort"
              :aria-label="$t('emailConnector.admin.connectors.drawer.connector.smtpPort')"
              :placeholder="$t('emailConnector.admin.connectors.drawer.connector.placeHolder.smtpPort')"
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
              {{ $t('emailConnector.admin.connectors.drawer.connector.smtpSecurityType') }}
            </v-list-item-title>
            <v-select
              v-model="emailConnector.smtpSecurityType"
              :items="smtpSecurityTypes"
              class="pt-3"
              outlined
              dense />
          </v-list-item-content>
        </v-list-item>
        <v-list-item-title class="pa-0 mt-7 mb-4 text-header">
          {{ $t('emailConnector.admin.connectors.drawer.connector.advancedSettings') }}
        </v-list-item-title>
        <v-list-item class="pa-0 height-auto" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title class="my-0">
              {{ $t('emailConnector.admin.connectors.drawer.connector.activeWebmailAccess') }}
            </v-list-item-title>
          </v-list-item-content>
          <v-list-item-action class="my-0">
            <v-switch
              v-model="activeWebmailAccess"
              @click="switchActiveWebmailAccess" />
          </v-list-item-action>
        </v-list-item>
        <v-list-item class="pa-0" dense>
          <v-list-item-content class="py-0">
            <v-text-field
              v-if="activeWebmailAccess"
              v-model="emailConnector.webmailUrl"
              :aria-label="$t('emailConnector.admin.connectors.drawer.connector.webmailUrl')"
              :placeholder="$t('emailConnector.admin.connectors.drawer.connector.placeHolder.webmailUrl')"
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
          class="btn btn-primary ms-5">
          {{ drawerButtonLabel }}
        </v-btn>
      </div>
    </template>
  </exo-drawer>
</template>

<script>
export default {
  data: () => ({
    emailConnectorDrawer: false,
    activeWebmailAccess: false,
    emailConnectorNameTranslations: {},
    loading: false,
    smtpSecurityTypes: [],
    emailConnector: {
      id: '',
      name: '',
      icon: '',
      imapUrl: '',
      imapPort: '',
      smtpUrl: '',
      smtpPort: '',
      smtpSecurityType: 'starttls',
      imageUploadId: null,
      imageFileId: null,
      imageUrl: null,
      webmailUrl: ''
    }   
  }),
  computed: {
    emailConnectorName() {
      return this.emailConnectorNameTranslations[eXo.env.portal.defaultLanguage];
    },
    disabled() {
      return !this.emailConnectorName || !this.emailConnector.imapUrl || !this.emailConnector.imapPort 
      || !this.emailConnector.smtpUrl || !this.emailConnector.smtpPort || !this.emailConnector.smtpSecurityType
      || (this.activeWebmailAccess && !this.emailConnector.webmailUrl);
    },
    drawerTitle() {
      return this.emailConnector.id && this.$t('emailConnector.admin.connectors.drawer.edit.title', {
        0: this.emailConnector.name,
      })
        || this.$t('emailConnector.admin.connectors.drawer.add.title');
    },
    drawerButtonLabel() {
      return this.emailConnector.id && this.$t('emailConnector.admin.connectors.drawer.save')
        || this.$t('emailConnector.admin.connectors.drawer.add');
    }
  },
  created() {
    this.$root.$on('open-email-connector-drawer', this.open);
    this.smtpSecurityTypes = [  
      { text: this.$t('emailConnector.admin.connectors.drawer.connector.smtpSecurityType.starttls'), value: 'starttls' },
      { text: this.$t('emailConnector.admin.connectors.drawer.connector.smtpSecurityType.ssl'), value: 'ssl' }
    ];
  },
  methods: {
    async open(emailConnector) {
      if (emailConnector) {
        this.emailConnector = { ...emailConnector };
        if (!this.emailConnector.smtpSecurityType) {
          this.emailConnector.smtpSecurityType = 'starttls';
        }
        this.emailConnectorNameTranslations = await this.$translationService.getTranslations('emailConnector', emailConnector.id, 'name');
        this.emailConnector.name = this.emailConnectorNameTranslations[eXo.env.portal.defaultLanguage];
      }
      this.activeWebmailAccess = !!this.emailConnector.webmailUrl;
      this.$refs.emailConnectorDrawer.open();
    },
    close() {
      this.emailConnectorNameTranslations = {};
      this.emailConnector.id = '';
      this.emailConnector.name = '';
      this.emailConnector.icon = null;
      this.emailConnector.imapUrl = '';
      this.emailConnector.imapPort = '';
      this.emailConnector.smtpUrl = '';
      this.emailConnector.smtpPort = '';
      this.emailConnector.smtpSecurityType = 'starttls';
      this.emailConnector.imageUploadId = null;
      this.emailConnector.imageFileId = null;
      this.emailConnector.imageUrl = null;
      this.emailConnector.webmailUrl = null;
      this.$refs.emailConnectorDrawer.close();
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
        this.emailConnector.name = this.emailConnectorName;
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
        this.$root.$emit('refresh-connectors-list');
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