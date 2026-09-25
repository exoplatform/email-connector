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
        <!-- The address book, beside IMAP and SMTP because it is the same
             provider's third endpoint, not a second server to define elsewhere.
             Optional, and empty is the honest answer for the providers that have
             no CardDAV at all: the hint says which those are, so nobody fills it
             in hopefully and waits for a sync that can never work. -->
        <v-list-item class="pa-0 height-auto" dense>
          <v-list-item-content class="py-0">
            <v-list-item-title class="my-0">
              {{ $t('emailConnector.admin.connectors.drawer.connector.carddavUrl') }}
            </v-list-item-title>
            <v-text-field
              v-model="emailConnector.carddavUrl"
              :aria-label="$t('emailConnector.admin.connectors.drawer.connector.carddavUrl')"
              :placeholder="$t('emailConnector.admin.connectors.drawer.connector.placeHolder.carddavUrl')"
              class="pt-3"
              type="text"
              outlined
              dense />
            <div class="text-caption text-sub-title mb-2">
              {{ $t('emailConnector.admin.connectors.drawer.connector.carddavUrl.hint') }}
            </div>
          </v-list-item-content>
        </v-list-item>
        <!-- How this connector authenticates. One section, two halves: the
             provider is chosen here, and whatever that provider needs
             configured is drawn by commons-exo's generic renderer from the
             descriptor the provider publishes - so a new provider adds fields
             to this drawer without a line changing in it. -->
        <template v-if="providerSelectable">
          <v-list-item-title class="pa-0 mt-7 mb-4 text-header">
            {{ $t('emailConnector.admin.connectors.drawer.authentication') }}
          </v-list-item-title>
          <v-label for="emailConnectorAuthProvider">
            {{ $t('emailConnector.admin.connectors.drawer.authProvider') }}
          </v-label>
          <v-select
            id="emailConnectorAuthProvider"
            ref="emailConnectorAuthProvider"
            v-model="emailConnector.authProviderName"
            :items="providerItems"
            name="emailConnectorAuthProvider"
            class="pt-0 mt-2 mb-3"
            item-text="text"
            item-value="value"
            outlined
            dense
            @change="providerConfig = {}" />
        </template>
        <provider-config-fields
          v-model="providerConfig"
          :fields="selectedProviderFields"
          :secrets-stored="!!emailConnector.id"
          @valid="providerConfigValid = $event" />
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
      webmailUrl: '',
      carddavUrl: '',
      authProviderName: ''
    },
    // The registered providers, as the REST endpoint describes them: a name and
    // the fields each one wants configured.
    providers: [],
    // What the drawer is editing for the selected provider. Read back without
    // any secret, so a secret field shows empty and an unrelated save leaves
    // the stored one alone.
    providerConfig: {},
    // Whether the selected provider's required fields are all filled. The drawer does
    // not know what those fields are - the renderer tells it, so the save button can
    // be disabled without this file learning anything about any provider.
    providerConfigValid: true
  }),
  computed: {
    emailConnectorName() {
      return this.emailConnectorNameTranslations[eXo.env.portal.defaultLanguage];
    },
    disabled() {
      return !this.emailConnectorName || !this.emailConnector.imapUrl || !this.emailConnector.imapPort 
      || !this.emailConnector.smtpUrl || !this.emailConnector.smtpPort || !this.emailConnector.smtpSecurityType
      || (this.activeWebmailAccess && !this.emailConnector.webmailUrl)
      || !this.providerConfigValid;
    },
    drawerTitle() {
      return this.emailConnector.id && this.$t('emailConnector.admin.connectors.drawer.edit.title', {
        0: this.emailConnector.name,
      })
        || this.$t('emailConnector.admin.connectors.drawer.add.title');
    },
    /**
     * Whether the provider choice is worth showing. With a single provider
     * registered - the state before EXO-89646 ships the sudo one - a select with
     * one entry is noise, and the connector keeps the default it already has.
     *
     * @returns {boolean} true when more than one provider is registered
     */
    providerSelectable() {
      return this.providers.length > 1;
    },
    /**
     * The provider choices, labelled through i18n so a provider name stays a
     * technical key.
     *
     * @returns {Array} items for the provider select
     */
    providerItems() {
      return this.providers.map(provider => ({
        text: this.$t(`credentials.provider.${provider.name}`),
        value: provider.name,
      }));
    },
    /**
     * The configuration fields the selected provider publishes, or none when it
     * publishes none - which is the case of the personal provider.
     *
     * @returns {Array} the selected provider's field descriptors
     */
    selectedProviderFields() {
      const selected = this.providers.find(provider => provider.name === this.emailConnector.authProviderName);
      return selected && selected.fields || [];
    },
    drawerButtonLabel() {
      return this.emailConnector.id && this.$t('emailConnector.admin.connectors.drawer.save')
        || this.$t('emailConnector.admin.connectors.drawer.add');
    }
  },
  created() {
    this.$root.$on('open-email-connector-drawer', this.open);
    // Once for the drawer's life: the registered providers change with what is
    // deployed, not with what the administrator is editing. A failure here is
    // not worth an alert - the section simply does not appear, and the
    // connector keeps the provider it has.
    this.$credentialsProviderService.getCredentialsProviders()
      .then(providers => this.providers = providers)
      .catch(() => this.providers = []);
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
      this.providerConfig = emailConnector && emailConnector.id
        && await this.loadProviderConfig(emailConnector.id)
        || {};
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
      this.emailConnector.webmailUrl = '';
      this.emailConnector.authProviderName = '';
      // Not kept between two openings: it holds what an administrator typed for
      // one connector, and the next one they open is not the same connector.
      this.providerConfig = {};
      this.$refs.emailConnectorDrawer.close();
    },
    resetImage() {
      this.emailConnector.imageUrl = null;
      this.emailConnector.imageFileId = null;
    },
    /**
     * The configuration already stored for a connector, without its secrets.
     *
     * @param {number} emailConnectorId technical id of the connector being edited
     * @returns {Promise} the stored values, empty on failure
     */
    async loadProviderConfig(emailConnectorId) {
      try {
        return await this.$emailConnectorAdministrationService.getProviderConfig(emailConnectorId);
      } catch (e) {
        return {};
      }
    },
    async saveConnector() {
      this.loading = true;
      const isNew = !this.emailConnector.id;
      if (!this.activeWebmailAccess) {
        this.emailConnector.webmailUrl = '';
      }
      let emailConnector = this.emailConnector;
      try {
        this.emailConnector.icon = this.emailConnector.icon || 'fa-envelope';
        this.emailConnector.name = this.emailConnectorName;
        // Relayed as typed. The keys belong to the provider's descriptor, and the
        // server validates them against it before anything is written.
        this.emailConnector.providerConfig = this.providerConfig;
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
        // A refused configuration comes back as a message code the provider's
        // own bundle translates. Showing the generic "error" instead would tell
        // the administrator nothing about a form they can correct.
        if (e?.messageCode?.startsWith('connector.credentials.')) {
          this.$root.$emit('alert-message', this.$t(e.messageCode), 'error');
        }
        else if (isNew) {
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