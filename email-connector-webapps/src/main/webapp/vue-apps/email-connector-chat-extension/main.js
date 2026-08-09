/*
 * Copyright (C) 2026 eXo Platform SAS.
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

// Contributes the email connector's two actions to the chat's extension points
// (see matrix's vue-apps/matrix/js/ChatActionExtensions.js for the contract):
//
//  - ('chat', 'message-action') "Add to contacts", on a message whose attachment
//    is a vCard: reads the attachment through the context's authenticated
//    getAttachmentFile(), pushes it to the upload service, asks the server to
//    parse its FIRST card, and opens the contacts app's own add form PREFILLED —
//    the user confirms, nothing is written silently.
//
//  - ('chat', 'composer-action') "Contact", in the composer's "+" menu: opens a
//    picker over the caller's contact store, and sends the picked contact as a
//    .vcf file message through the context's sendFile() — the same upload path,
//    size gate and progress as a picked file.
//
// This module lives entirely in the email add-on; the chat discovers it by
// module name (its gatein <name> contains "ChatActionExtension", and it sits in
// matrixGRP so it is present wherever the chat renders). No matrix change, and
// both entries are simply absent when this add-on is not deployed.
import './initComponents.js';
import * as emailConnectorContactsService from '../email-connector-contacts/js/EmailConnectorContactsService.js';

if (!Vue.prototype.$emailConnectorContactsService) {
  window.Object.defineProperty(Vue.prototype, '$emailConnectorContactsService', {
    value: emailConnectorContactsService,
  });
}

// What the platform reports for a .vcf varies (text/vcard, text/x-vcard,
// text/directory, or nothing at all) — so the mime is matched liberally and the
// file name settles the rest.
const VCARD_EXTENSION = '.vcf';

/**
 * Whether a chat attachment is a vCard: any vcard-ish mime type, or a .vcf
 * name whatever the mime says.
 *
 * @param {object} attachment - the context's {fileName, mimeType} attachment
 * @returns {boolean} true for a vCard
 */
function isVCardAttachment(attachment) {
  if (!attachment) {
    return false;
  }
  const mime = (attachment.mimeType || '').toLowerCase();
  const name = (attachment.fileName || '').toLowerCase();
  return mime.includes('vcard') || mime === 'text/directory' || name.endsWith(VCARD_EXTENSION);
}

/**
 * Mounts the host app of the two drawers on first use — the same lazy
 * #vuetify-apps bootstrap the "+" quick action and the Documents "Send by
 * email" action use — with the contacts app's translations loaded.
 *
 * @returns {Promise<void>} resolves once the host is mounted and listening
 */
function ensureHostApp() {
  const appId = 'emailConnector-chat-extension';
  if (document.querySelector(`#${appId}`)) {
    return Promise.resolve();
  }
  const parent = document.createElement('div');
  parent.id = appId;
  document.querySelector('#vuetify-apps').appendChild(parent);
  return new Promise((resolve, reject) => window.require(['SHARED/eXoVueI18n'], exoi18n => {
    const lang = eXo.env.portal.language;
    const urls = [
      `/email-connector/i18n/locale.portlet.emailConnector.emailConnectorContacts?lang=${lang}`
    ];
    exoi18n.loadLanguageAsync(lang, urls)
      .then(i18n => Vue.createApp({
        template: `<email-connector-chat-extension-app id="${appId}" />`,
        mounted() {
          resolve();
        },
        vuetify: Vue.prototype.vuetifyOptions,
        i18n,
      }, `#${appId}`, 'Email Connector Chat Extension'))
      .catch(reject);
  }));
}

/**
 * The "Add to contacts" action: the attachment's bytes, uploaded, parsed
 * server-side into the contact its first card would become, and opened in the
 * contacts app's own add form for the user to confirm.
 *
 * @param {object} context - the chat's message action context
 * @returns {Promise<void>} resolves once the prefilled form is open
 */
async function addToContacts(context) {
  try {
    const file = await context.getAttachmentFile();
    const uploadId = Vue.prototype.$uploadService.generateRandomId();
    const resolvedId = await Vue.prototype.$uploadService.upload(file, uploadId);
    const contact = await emailConnectorContactsService.parseVCardFile(resolvedId || uploadId);
    await ensureHostApp();
    document.dispatchEvent(new CustomEvent('email-chat-open-contact-form', {detail: {contact}}));
  } catch (error) {
    console.error('The chat attachment could not be read as a contact card', error);
    await ensureHostApp();
    document.dispatchEvent(new CustomEvent('email-chat-contact-error', {detail: {code: 'emailConnector.contacts.chat.addToContacts.error'}}));
  }
}

/**
 * The "Contact" composer action: the picker over the caller's store, then the
 * picked contact sent as a .vcf file message through the chat's own sendFile —
 * the vCard text is the server's, the very card the QR code encodes.
 *
 * @param {object} context - the chat's composer action context
 * @returns {Promise<void>} resolves once the picker is open
 */
async function shareContact(context) {
  await ensureHostApp();
  document.dispatchEvent(new CustomEvent('email-chat-open-contact-picker', {
    detail: {
      onSelect: contact => sendContactCard(contact, context),
    },
  }));
}

/**
 * Sends one picked contact into the room as a .vcf file message.
 *
 * @param {object} contact - the picked contact
 * @param {object} context - the chat's composer action context
 * @returns {Promise<void>} resolves once the file message is sent
 */
async function sendContactCard(contact, context) {
  try {
    const vcard = await emailConnectorContactsService.getContactVCard(contact.id);
    // The card travels under the person's name; characters a filesystem or the
    // mime layer could choke on become spaces.
    const name = (contact.displayName || contact.primaryEmail || 'contact')
      .replace(/[\\/:*?"<>|]/g, ' ').replace(/\s+/g, ' ').trim();
    const file = new File([vcard], `${name}${VCARD_EXTENSION}`, {type: 'text/vcard'});
    await context.sendFile(file);
  } catch (error) {
    console.error('The contact could not be sent into the chat', error);
    document.dispatchEvent(new CustomEvent('email-chat-contact-error', {detail: {code: 'emailConnector.contacts.chatPicker.send.error'}}));
  }
}

/**
 * Registers the two actions. Registering twice is safe — the registry replaces
 * an extension carrying the same id — which is what lets init() re-run freely.
 *
 * @returns {void}
 */
function registerChatActions() {
  if (typeof extensionRegistry === 'undefined' || !extensionRegistry) {
    return;
  }
  extensionRegistry.registerExtension('chat', 'message-action', {
    id: 'emailAddToContacts',
    rank: 100,
    icon: 'fas fa-address-book',
    labelKey: 'emailConnector.chat.messageAction.addToContacts.label',
    enabled: context => isVCardAttachment(context?.attachment),
    click: context => addToContacts(context),
  });
  extensionRegistry.registerExtension('chat', 'composer-action', {
    id: 'emailShareContact',
    rank: 100,
    icon: 'fas fa-address-book',
    labelKey: 'emailConnector.chat.composerAction.contact.label',
    click: context => shareContact(context),
  });
}

// Runs when the module is required (the chat requires every page module whose
// name contains "ChatActionExtension").
registerChatActions();

// The include resolver also calls module.init() on the required module.
export function init() {
  registerChatActions();
}
