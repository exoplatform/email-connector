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
import EmailConnectorContactsApp from './components/EmailConnectorContactsApp.vue';
import EmailConnectorContactsDrawer from './components/drawer/EmailConnectorContactsDrawer.vue';
import EmailConnectorContactsList from './components/drawer/EmailConnectorContactsList.vue';
import EmailConnectorContactsListItem from './components/drawer/EmailConnectorContactsListItem.vue';
import EmailConnectorContactsSourceFilter from './components/drawer/EmailConnectorContactsSourceFilter.vue';
import EmailConnectorContactsAlphabetRail from './components/drawer/EmailConnectorContactsAlphabetRail.vue';
import EmailConnectorContactsDetail from './components/drawer/EmailConnectorContactsDetail.vue';
import EmailConnectorContactsCorrespondence from './components/drawer/EmailConnectorContactsCorrespondence.vue';
import EmailConnectorContactsDetailDrawer from './components/drawer/EmailConnectorContactsDetailDrawer.vue';
import EmailConnectorContactFormDrawer from './components/drawer/EmailConnectorContactFormDrawer.vue';
import EmailConnectorContactsTransferMenu from './components/drawer/EmailConnectorContactsTransferMenu.vue';
import EmailConnectorContactsImportBanner from './components/drawer/EmailConnectorContactsImportBanner.vue';

const components = {
  'email-connector-contacts-app': EmailConnectorContactsApp,
  'email-connector-contacts-drawer': EmailConnectorContactsDrawer,
  'email-connector-contacts-list': EmailConnectorContactsList,
  'email-connector-contacts-list-item': EmailConnectorContactsListItem,
  'email-connector-contacts-source-filter': EmailConnectorContactsSourceFilter,
  'email-connector-contacts-alphabet-rail': EmailConnectorContactsAlphabetRail,
  'email-connector-contacts-detail': EmailConnectorContactsDetail,
  'email-connector-contacts-correspondence': EmailConnectorContactsCorrespondence,
  'email-connector-contacts-detail-drawer': EmailConnectorContactsDetailDrawer,
  'email-connector-contact-form-drawer': EmailConnectorContactFormDrawer,
  'email-connector-contacts-transfer-menu': EmailConnectorContactsTransferMenu,
  'email-connector-contacts-import-banner': EmailConnectorContactsImportBanner
};

for (const key in components) {
  Vue.component(key, components[key]);
}
