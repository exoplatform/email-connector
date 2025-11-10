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

extensionRegistry.registerExtension('QuickAction', 'Extension', {
  id: 'email',
  icon: 'fa-envelope',
  name: 'quickActions.email.name',
  description: 'quickActions.email.description',
  click: () => {
    window.require(['SHARED/eXoVueI18n', 'PORTLET/email-connector/EmailConnectorUserSetting'], exoi18n => initConnectorsMailBox(exoi18n));
  },
});

if (document.readyState === 'complete' || document.readyState === 'interactive') {
  const urlParams = new URLSearchParams(window.location.search);
  const shouldOpenEmailBox = urlParams.get('openEmailBox') === 'true';
  if (shouldOpenEmailBox) {
    window.require(['SHARED/eXoVueI18n', 'PORTLET/email-connector/EmailConnectorUserSetting'], exoi18n => initConnectorsMailBox(exoi18n));
  }
}

async function initConnectorsMailBox(exoi18n) {
  const appId = 'emailConenctor-mailBox-quick-actions';
  if (!document.querySelector(`#${appId}`)) {
    const parent = document.createElement('div');
    parent.id = appId;
    document.querySelector('#vuetify-apps').appendChild(parent);
    await initConnectorsDrawerApp(appId, exoi18n, eXo.env.portal.maxFileSize);
  }
  document.dispatchEvent(new CustomEvent('quick-action-mailBox-drawer'));
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