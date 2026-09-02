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
import EmailConnectorUserSettingApp from './components/EmailConnectorUserSettingApp.vue';
import EmailConnectorUserSettingBody from './components/main/EmailConnectorUserSettingBody.vue';
import EmailConnectorUserSettingFolders from './components/main/EmailConnectorUserSettingFolders.vue';
import EmailConnectorUserSettingConnectorsDrawer from './components/drawer/connectors/EmailConnectorUserSettingConnectorsDrawer.vue';
import EmailConnectorUserSettingConnectorsDrawerList from './components/drawer/connectors/EmailConnectorUserSettingConnectorsDrawerList.vue';
import EmailConnectorUserSettingConnectorsDrawerListItem from './components/drawer/connectors/EmailConnectorUserSettingConnectorsDrawerListItem.vue';
import EmailConnectorUserSettingDrawer from './components/drawer/user-setting/EmailConnectorUserSettingDrawer.vue';
import EmailConnectorUserSettingDisconnectDrawer from './components/drawer/disconnect/EmailConnectorUserSettingDisconnectDrawer.vue';
import EmailConnectorUserSettingSignatureDrawer from './components/drawer/signature/EmailConnectorUserSettingSignatureDrawer.vue';
import EmailConnectorContactsChoiceStep from './components/drawer/common/EmailConnectorContactsChoiceStep.vue';

const components = {
  'email-connector-user-setting-app': EmailConnectorUserSettingApp,
  'email-connector-user-setting-body': EmailConnectorUserSettingBody,
  'email-connector-user-setting-folders': EmailConnectorUserSettingFolders,
  'email-connector-user-setting-connectors-drawer': EmailConnectorUserSettingConnectorsDrawer,
  'email-connector-user-setting-connectors-drawer-list': EmailConnectorUserSettingConnectorsDrawerList,
  'email-connector-user-setting-connectors-drawer-list-item': EmailConnectorUserSettingConnectorsDrawerListItem,
  'email-connector-user-setting-drawer': EmailConnectorUserSettingDrawer,
  'email-connector-user-setting-disconnect-drawer': EmailConnectorUserSettingDisconnectDrawer,
  'email-connector-user-setting-signature-drawer': EmailConnectorUserSettingSignatureDrawer,
  'email-connector-contacts-choice-step': EmailConnectorContactsChoiceStep,
};

for (const key in components) {
  Vue.component(key, components[key]);
}
