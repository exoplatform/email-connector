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
import EmailConnectorAdminApp from './components/EmailConnectorAdminApp.vue';
import EmailConnectorAdminHeader from './components/header/EmailConnectorAdminHeader.vue';
import EmailConnectorAdminList from './components/main/EmailConnectorAdminList.vue';
import EmailConnectorAdminIcon from './components/main/EmailConnectorAdminIcon.vue';
import EmailConnectorAdminDrawer from './components/drawer/EmailConnectorAdminDrawer.vue';
import EmailConnectorAdminImageInput from './components/drawer/EmailConnectorAdminImageInput.vue';
import EmailConnectorAdminFooter from './components/footer/EmailConnectorAdminFooter.vue';

const components = {
  'email-connector-admin-app': EmailConnectorAdminApp,
  'email-connector-admin-header': EmailConnectorAdminHeader,
  'email-connector-admin-footer': EmailConnectorAdminFooter,
  'email-connector-admin-list': EmailConnectorAdminList,
  'email-connector-admin-drawer': EmailConnectorAdminDrawer,
  'email-connector-admin-image-input': EmailConnectorAdminImageInput,
  'email-connector-admin-icon': EmailConnectorAdminIcon,
};

for (const key in components) {
  Vue.component(key, components[key]);
}
