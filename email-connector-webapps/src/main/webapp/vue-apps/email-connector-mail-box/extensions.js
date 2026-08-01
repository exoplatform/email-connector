/*
 * Copyright (C) 2025 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */

// The actions of a received attachment are themselves extensions, so that another
// add-on contributes one without this app knowing about it. Registered here rather
// than by the menu component: they exist as soon as the app is loaded, and other
// add-ons can look the point up before any mail is opened.
import './js/EmailConnectorAttachmentActions.js';

extensionRegistry.registerExtension('QuickAction', 'Extension', {
  id: 'email',
  icon: 'fa-envelope',
  name: 'quickActions.email.name',
  description: 'quickActions.email.description',
  click: () => {
    window.require(['SHARED/eXoVueI18n', 'PORTLET/email-connector/EmailConnectorUserSetting'], exoi18n => initConnectorsMailBox(exoi18n));
  },
});

/*
 * Whether the Documents add-on is deployed. The whole picker (paperclip + drawer)
 * is provided by Documents at runtime, exactly like the activity composer: when the
 * add-on is absent nothing is registered and the button never appears (a native
 * <v-file-input> fallback handles uploads instead). No compile/gatein dependency.
 */
function isDocumentsDeployed() {
  return extensionRegistry.loadExtensions('RichEditor', 'ckeditor-extensions').some(ext => ext.id === 'attachFile')
    || !!Vue.prototype.$attachmentService;
}

/*
 * Adds an "attach file" button to the email CKEditor (ck-editor-type="email") only
 * when Documents is deployed. The enabled() gate lets the button show without forcing
 * use-extra-plugins on the editor (which would pull unrelated global plugins), and
 * getExtension() registers our dedicated plugin lazily once CKEDITOR is loaded.
 */
extensionRegistry.registerExtension('RichEditor', 'ckeditor-extensions-email', {
  id: 'attachEmailFile',
  rank: 30,
  enabled: () => isDocumentsDeployed(),
  getExtension: () => {
    if (window.CKEDITOR) {
      CKEDITOR.plugins.addExternal('attachEmailFile', '/email-connector/ckeditor/attachEmailFile/', 'plugin.js');
    }
    return {
      extraPlugin: 'attachEmailFile',
      extraToolbarItem: 'attachEmailFile',
    };
  },
});

/*
 * Opens the mailbox on one given message, from anywhere in the platform: the global
 * Favorites drawer uses it to hand over a favorited mail, and any other add-on can do
 * the same without knowing how this app is mounted.
 *
 * The reader is the mailbox's own — the conversation, its attachments and its replies —
 * rather than a second, poorer copy of it living elsewhere. It costs nothing on a page
 * where nobody opens a mail: this listener is all that is loaded, and the app itself is
 * only built on the first open, exactly like the quick action.
 *
 * detail: {mailRemoteId} — the IMAP UID of the message to open.
 */
document.addEventListener('open-email-box-mail', event => {
  const mailRemoteId = event?.detail?.mailRemoteId;
  window.require(['SHARED/eXoVueI18n', 'PORTLET/email-connector/EmailConnectorUserSetting'], exoi18n => initConnectorsMailBox(exoi18n, mailRemoteId));
});

if (document.readyState === 'complete' || document.readyState === 'interactive') {
  const urlParams = new URLSearchParams(window.location.search);
  const shouldOpenEmailBox = urlParams.get('openEmailBox') === 'true';
  if (shouldOpenEmailBox) {
    window.require(['SHARED/eXoVueI18n', 'PORTLET/email-connector/EmailConnectorUserSetting'], exoi18n => initConnectorsMailBox(exoi18n));
  }
}

async function initConnectorsMailBox(exoi18n, mailRemoteId) {
  const appId = 'emailConenctor-mailBox-quick-actions';
  if (!document.querySelector(`#${appId}`)) {
    const parent = document.createElement('div');
    parent.id = appId;
    document.querySelector('#vuetify-apps').appendChild(parent);
    await initConnectorsDrawerApp(appId, exoi18n, eXo.env.portal.maxFileSize);
  }
  // No id simply means "just open the mailbox", which is what the quick action does.
  document.dispatchEvent(new CustomEvent('quick-action-mailBox-drawer', {detail: {mailRemoteId}}));
}

function initConnectorsDrawerApp(appId, exoi18n) {
  const lang = eXo.env.portal.language;
  const urls = [
    `/email-connector/i18n/locale.portlet.emailConnector.emailConnectorUserSetting?lang=${lang}`,
    `/email-connector/i18n/locale.portlet.emailConnector.emailConnectorMailBox?lang=${lang}`
  ];
  //const url = '';
  return new Promise(resolve => exoi18n.loadLanguageAsync(lang, urls)
    .then(i18n => Vue.createApp({
      template: `
        <email-connector-mail-box-app
          id="${appId}"
          />
      `,
      mounted() {
        document.dispatchEvent(new CustomEvent('hideTopBarLoading'));
        resolve();
      },
      vuetify: Vue.prototype.vuetifyOptions,
      i18n,
    }, `#${appId}`, 'Email Connector Quick Action')));
}