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
  <document-action-item
    icon="fa fa-envelope"
    :label="$t('documents.label.sendByEmail')"
    @click="sendByEmail" />
</template>
<script>
export default {
  props: {
    file: {
      type: Object,
      default: null,
    }
  },
  methods: {
    // Bootstraps the email connector's mailbox app (mounted lazily, exactly like the
    // "+" quick action does) then opens a new email pre-seeded with this document as
    // an attachment. The whole thing lives in the email add-on and only registers on
    // the Documents pages when the connector is deployed, so Documents stays
    // dependency-free.
    sendByEmail() {
      const attachment = this.toAttachment(this.file);
      // Load the email bundles on demand: the connectors drawer (user setting) and
      // the mailbox app (which registers <email-connector-mail-box-app> + the
      // 'open-email-compose-with-attachment' listener).
      window.require([
        'SHARED/eXoVueI18n',
        'PORTLET/email-connector/EmailConnectorUserSetting',
        'SHARED/emailConnectorQuickActionExtension',
      ], exoi18n => this.bootstrapAndCompose(exoi18n, attachment));
    },
    // Maps a Documents node to the attachment shape the email composer's
    // addFromDriveDocument() expects (it downloads /v1/documents/content/{id} then
    // re-uploads to a commons upload id).
    toAttachment(file) {
      if (!file) {
        return null;
      }
      return {
        eXoDrive: true,
        id: file.sourceID || file.id,
        name: file.name,
        title: file.title || file.name,
        mimetype: file.mimeType,
        mimeType: file.mimeType,
        size: file.size,
        path: file.path,
        downloadUrl: file.downloadUrl,
        isSelectedFromDrives: true,
      };
    },
    async bootstrapAndCompose(exoi18n, attachment) {
      const appId = 'emailConnector-documents-send-by-email';
      if (!document.querySelector(`#${appId}`)) {
        const parent = document.createElement('div');
        parent.id = appId;
        document.querySelector('#vuetify-apps').appendChild(parent);
        await this.initEmailApp(appId, exoi18n);
      }
      document.dispatchEvent(new CustomEvent('open-email-compose-with-attachment', {
        detail: { attachment },
      }));
    },
    initEmailApp(appId, exoi18n) {
      const lang = eXo.env.portal.language;
      const urls = [
        `/email-connector/i18n/locale.portlet.emailConnector.emailConnectorUserSetting?lang=${lang}`,
        `/email-connector/i18n/locale.portlet.emailConnector.emailConnectorMailBox?lang=${lang}`,
      ];
      return new Promise(resolve => exoi18n.loadLanguageAsync(lang, urls)
        .then(i18n => Vue.createApp({
          template: `<email-connector-mail-box-app id="${appId}" />`,
          mounted() {
            resolve();
          },
          vuetify: Vue.prototype.vuetifyOptions,
          i18n,
        }, `#${appId}`, 'Documents Send By Email')));
    },
  },
};
</script>
