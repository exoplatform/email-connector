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

// The Contacts quick action: a sibling drawer app to the mailbox, built lazily
// into #vuetify-apps on first click, exactly the mailbox's own shape.
extensionRegistry.registerExtension('QuickAction', 'Extension', {
  id: 'contacts',
  icon: 'fa-address-book',
  name: 'quickActions.contacts.name',
  description: 'quickActions.contacts.description',
  click: () => {
    window.require(['SHARED/eXoVueI18n'], exoi18n => initContactsDrawer(exoi18n));
  },
});

/*
 * Opens the Contacts drawer from anywhere in the platform, without the caller
 * knowing how this app is mounted — the same cross-app pattern as
 * 'open-email-box-mail'. The detail may carry {contactId}: the global Favorites
 * drawer sends it so a starred contact opens straight onto its card.
 */
document.addEventListener('open-contacts-drawer', event => {
  const opening = event?.detail || {};
  window.require(['SHARED/eXoVueI18n'], exoi18n => initContactsDrawer(exoi18n, opening));
});

/**
 * Mounts the contacts app on first use, then asks it to open its drawer.
 *
 * @param {object} exoi18n - the platform i18n loader
 * @param {object} opening - what to open the drawer on, may be undefined
 * @returns {Promise<void>} resolves once the open event is dispatched
 */
async function initContactsDrawer(exoi18n, opening) {
  const appId = 'emailConnector-contacts-quick-actions';
  if (!document.querySelector(`#${appId}`)) {
    const parent = document.createElement('div');
    parent.id = appId;
    document.querySelector('#vuetify-apps').appendChild(parent);
    await initContactsDrawerApp(appId, exoi18n);
  }
  document.dispatchEvent(new CustomEvent('quick-action-contacts-drawer', {detail: opening || {}}));
}

/**
 * Builds the contacts Vue app into the given mount point, with this app's
 * translations loaded.
 *
 * @param {string} appId - the mount element id
 * @param {object} exoi18n - the platform i18n loader
 * @returns {Promise<void>} resolves when the app is mounted
 */
function initContactsDrawerApp(appId, exoi18n) {
  const lang = eXo.env.portal.language;
  const urls = [
    `/email-connector/i18n/locale.portlet.emailConnector.emailConnectorContacts?lang=${lang}`
  ];
  return new Promise(resolve => exoi18n.loadLanguageAsync(lang, urls)
    .then(i18n => Vue.createApp({
      template: `
        <email-connector-contacts-app
          id="${appId}"
          />
      `,
      mounted() {
        document.dispatchEvent(new CustomEvent('hideTopBarLoading'));
        resolve();
      },
      vuetify: Vue.prototype.vuetifyOptions,
      i18n,
    }, `#${appId}`, 'Email Contacts Quick Action')));
}
