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
 * Makes the pictures in a message resizable, by giving the email editor CKEditor's
 * image widget.
 *
 * RichEditor removes the plain 'image' plugin for every editor type and adds nothing
 * in its place, so an <img> in the body is inert: no handles, no way to make a
 * screenshot smaller than the width it was captured at. Notes solves this by listing
 * image2 in its own editor configuration; the email editor has no such file, and this
 * extension point is how it says the same thing.
 *
 * image2 rather than image: it is the widget version, it is what notes already runs,
 * and it writes width and height as ATTRIBUTES on the img -- which is the one form of
 * sizing mail clients agree on. A style would be dropped by Outlook.
 */
extensionRegistry.registerExtension('RichEditor', 'ckeditor-extensions-email', {
  id: 'emailResizableImages',
  rank: 40,
  // Unconditional, unlike the attach button above: this needs no other add-on, and an
  // extension without an enabled() is filtered out unless the editor asks for extra
  // plugins, which this one does not.
  enabled: () => true,
  getExtension: () => ({
    extraPlugin: 'image2',
  }),
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
  // The whole payload travels: a UID alone is not enough to find a message. UIDs are
  // per-folder, and a message the caller found on the server may not be in the local
  // cache at all -- both of which the mailbox knows how to handle, and neither of
  // which the caller should have to.
  const opening = event?.detail || {};
  window.require(['SHARED/eXoVueI18n', 'PORTLET/email-connector/EmailConnectorUserSetting'], exoi18n => initConnectorsMailBox(exoi18n, opening));
});

/*
 * Opens the mailbox on a search, from anywhere in the platform: the entry in the
 * platform's unified search uses it to hand over the term the user typed there.
 *
 * The global search only looks at the mail this add-on holds locally, so that no
 * search in the platform waits on IMAP. This is the way through to the rest: the
 * mailbox's own search field, which reaches the whole mailbox and says what it found.
 *
 * detail: {term} — the text to search for.
 */
document.addEventListener('open-email-box-search', event => {
  const searchTerm = event?.detail?.term;
  window.require(['SHARED/eXoVueI18n', 'PORTLET/email-connector/EmailConnectorUserSetting'], exoi18n => initConnectorsMailBox(exoi18n, {searchTerm}));
});

if (document.readyState === 'complete' || document.readyState === 'interactive') {
  const urlParams = new URLSearchParams(window.location.search);
  const shouldOpenEmailBox = urlParams.get('openEmailBox') === 'true';
  if (shouldOpenEmailBox) {
    window.require(['SHARED/eXoVueI18n', 'PORTLET/email-connector/EmailConnectorUserSetting'], exoi18n => initConnectorsMailBox(exoi18n));
  }
}

async function initConnectorsMailBox(exoi18n, opening) {
  const appId = 'emailConenctor-mailBox-quick-actions';
  if (!document.querySelector(`#${appId}`)) {
    const parent = document.createElement('div');
    parent.id = appId;
    document.querySelector('#vuetify-apps').appendChild(parent);
    await initConnectorsDrawerApp(appId, exoi18n, eXo.env.portal.maxFileSize);
  }
  // No payload simply means "just open the mailbox", which is what the quick action
  // does; a payload says what to open it on.
  document.dispatchEvent(new CustomEvent('quick-action-mailBox-drawer', {detail: opening || {}}));
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