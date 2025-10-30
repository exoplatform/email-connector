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
import EmailConnectorMailBoxApp from './components/EmailConnectorMailBoxApp.vue';
import EmailConnectorMailBoxDrawer from './components/drawer/EmailConnectorMailBoxDrawer.vue';
import EmailConnectorMailBoxDrawerList from './components/drawer/EmailConnectorMailBoxDrawerList.vue';
import EmailConnectorMailBoxDrawerListItem from './components/drawer/EmailConnectorMailBoxDrawerListItem.vue';
import EmailConnectorMailBoxDrawerListItemDetail from './components/drawer/EmailConnectorMailBoxDrawerListItemDetail.vue';
import EmailConnectorMailBoxDrawerListItemDetailSenderAvatar from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailSenderAvatar.vue';
import EmailConnectorMailBoxDrawerListItemDetailHeader from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailHeader.vue';
import EmailConnectorMailBoxDrawerListItemDetailHeaderItem from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailHeaderItem.vue';
import EmailConnectorMailBoxDrawerListItemDetailBody from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailBody.vue';

const components = {
  'email-connector-mail-box-app': EmailConnectorMailBoxApp,
  'email-connector-mail-box-drawer': EmailConnectorMailBoxDrawer,
  'email-connector-mail-box-drawer-list': EmailConnectorMailBoxDrawerList,
  'email-connector-mail-box-drawer-list-item': EmailConnectorMailBoxDrawerListItem,
  'email-connector-mail-box-drawer-list-item-detail': EmailConnectorMailBoxDrawerListItemDetail,
  'email-connector-mail-box-drawer-list-item-detail-sender-avatar': EmailConnectorMailBoxDrawerListItemDetailSenderAvatar,
  'email-connector-mail-box-drawer-list-item-detail-header': EmailConnectorMailBoxDrawerListItemDetailHeader,
  'email-connector-mail-box-drawer-list-item-detail-header-item': EmailConnectorMailBoxDrawerListItemDetailHeaderItem,
  'email-connector-mail-box-drawer-list-item-detail-body': EmailConnectorMailBoxDrawerListItemDetailBody,
};

for (const key in components) {
  Vue.component(key, components[key]);
}
