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
import EmailConnectorMailBoxDrawerActionMenu from './components/drawer/EmailConnectorMailBoxDrawerActionMenu.vue';
import EmailConnectorMailBoxDrawerActionMenuItems from './components/drawer/EmailConnectorMailBoxDrawerActionMenuItems.vue';
import EmailConnectorMailBoxDrawerActions from './components/drawer/EmailConnectorMailBoxDrawerActions.vue';
import EmailConnectorMailBoxDrawerAttachmentActionMenu from './components/drawer/EmailConnectorMailBoxDrawerAttachmentActionMenu.vue';
import EmailConnectorMailBoxDrawerAttachmentItem from './components/drawer/EmailConnectorMailBoxDrawerAttachmentItem.vue';
import EmailConnectorMailBoxDrawerAttachmentsDrawer from './components/drawer/EmailConnectorMailBoxDrawerAttachmentsDrawer.vue';
import EmailConnectorMailBoxDrawerAttachmentsList from './components/drawer/EmailConnectorMailBoxDrawerAttachmentsList.vue';
import EmailConnectorMailBoxDrawerAttachmentsListItem from './components/drawer/EmailConnectorMailBoxDrawerAttachmentsListItem.vue';
import EmailConnectorMailBoxDrawerAttachmentsSaveAll from './components/drawer/EmailConnectorMailBoxDrawerAttachmentsSaveAll.vue';
import EmailConnectorMailBoxDrawerContent from './components/drawer/EmailConnectorMailBoxDrawerContent.vue';
import EmailConnectorMailBoxDrawerList from './components/drawer/EmailConnectorMailBoxDrawerList.vue';
import EmailConnectorMailBoxDrawerListItem from './components/drawer/EmailConnectorMailBoxDrawerListItem.vue';
import EmailConnectorMailBoxDrawerListItemActionMenu from './components/drawer/EmailConnectorMailBoxDrawerListItemActionMenu.vue';
import EmailConnectorMailBoxDrawerListItemActionMenuDrawer from './components/drawer/EmailConnectorMailBoxDrawerListItemActionMenuDrawer.vue';
import EmailConnectorMailBoxDrawerListItemActionMenuItems from './components/drawer/EmailConnectorMailBoxDrawerListItemActionMenuItems.vue';
import EmailConnectorMailBoxDrawerListItemAttachments from './components/drawer/EmailConnectorMailBoxDrawerListItemAttachments.vue';
import EmailConnectorMailBoxDrawerListItemAttachmentsListItem from './components/drawer/EmailConnectorMailBoxDrawerListItemAttachmentsListItem.vue';
import EmailConnectorMailBoxDrawerListItemDetail from './components/drawer/EmailConnectorMailBoxDrawerListItemDetail.vue';
import EmailConnectorMailBoxDrawerListItemDetailActionMenu from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailActionMenu.vue';
import EmailConnectorMailBoxDrawerListItemDetailActionMenuItems from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailActionMenuItems.vue';
import EmailConnectorMailBoxDrawerListItemDetailActions from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailActions.vue';
import EmailConnectorMailBoxDrawerListItemDetailAttachments from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailAttachments.vue';
import EmailConnectorMailBoxDrawerListItemDetailBody from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailBody.vue';
import EmailConnectorMailBoxDrawerListItemDetailContent from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailContent.vue';
import EmailConnectorMailBoxDrawerThreadContent from './components/drawer/EmailConnectorMailBoxDrawerThreadContent.vue';
import EmailConnectorMailBoxDrawerThreadMessage from './components/drawer/EmailConnectorMailBoxDrawerThreadMessage.vue';
import EmailConnectorMailBoxDrawerCategoryBar from './components/drawer/EmailConnectorMailBoxDrawerCategoryBar.vue';
import EmailConnectorMailBoxDrawerListItemDetailHeader from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailHeader.vue';
import EmailConnectorMailBoxDrawerListItemDetailHeaderItem from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailHeaderItem.vue';
import EmailConnectorMailBoxDrawerListItemDetailSenderAvatar from './components/drawer/EmailConnectorMailBoxDrawerListItemDetailSenderAvatar.vue';
import EmailConnectorMailBoxDrawerMultiSelectEmail from './components/drawer/EmailConnectorMailBoxDrawerMultiSelectEmail.vue';
import EmailConnectorMailBoxDrawerSearchResults from './components/drawer/EmailConnectorMailBoxDrawerSearchResults.vue';
import EmailConnectorMailBoxDrawerSearchResultItem from './components/drawer/EmailConnectorMailBoxDrawerSearchResultItem.vue';
import EmailConnectorMailBoxDrawerNoEmail from './components/drawer/EmailConnectorMailBoxDrawerNoEmail.vue';
import EmailConnectorMailBoxDrawerSelectEmail from './components/drawer/EmailConnectorMailBoxDrawerSelectEmail.vue';
import EmailConnectorMailBoxDrawerFavoriteToggle from './components/drawer/EmailConnectorMailBoxDrawerFavoriteToggle.vue';
import EmailConnectorMailBoxDrawerFilterChips from './components/drawer/EmailConnectorMailBoxDrawerFilterChips.vue';
import EmailConnectorNewEmailDrawer from './components/drawer/EmailConnectorNewEmailDrawer.vue';
import EmailConnectorNewEmailDrawerAttachments from './components/drawer/EmailConnectorNewEmailDrawerAttachments.vue';
import EmailConnectorNewEmailDrawerNoSubjectConfirmPopup from './components/drawer/EmailConnectorNewEmailDrawerNoSubjectConfirmPopup.vue';

const components = {
  'email-connector-mail-box-app': EmailConnectorMailBoxApp,
  'email-connector-mail-box-drawer': EmailConnectorMailBoxDrawer,
  'email-connector-mail-box-drawer-action-menu': EmailConnectorMailBoxDrawerActionMenu,
  'email-connector-mail-box-drawer-action-menu-items': EmailConnectorMailBoxDrawerActionMenuItems,
  'email-connector-mail-box-drawer-actions': EmailConnectorMailBoxDrawerActions,
  'email-connector-mail-box-drawer-attachment-action-menu': EmailConnectorMailBoxDrawerAttachmentActionMenu,
  'email-connector-mail-box-drawer-attachment-item': EmailConnectorMailBoxDrawerAttachmentItem,
  'email-connector-mail-box-drawer-attachments-drawer': EmailConnectorMailBoxDrawerAttachmentsDrawer,
  'email-connector-mail-box-drawer-attachments-list': EmailConnectorMailBoxDrawerAttachmentsList,
  'email-connector-mail-box-drawer-attachments-list-item': EmailConnectorMailBoxDrawerAttachmentsListItem,
  'email-connector-mail-box-drawer-attachments-save-all': EmailConnectorMailBoxDrawerAttachmentsSaveAll,
  'email-connector-mail-box-drawer-content': EmailConnectorMailBoxDrawerContent,
  'email-connector-mail-box-drawer-list': EmailConnectorMailBoxDrawerList,
  'email-connector-mail-box-drawer-list-item': EmailConnectorMailBoxDrawerListItem,
  'email-connector-mail-box-drawer-list-item-action-menu': EmailConnectorMailBoxDrawerListItemActionMenu,
  'email-connector-mail-box-drawer-list-item-action-menu-drawer': EmailConnectorMailBoxDrawerListItemActionMenuDrawer,
  'email-connector-mail-box-drawer-list-item-action-menu-items': EmailConnectorMailBoxDrawerListItemActionMenuItems,
  'email-connector-mail-box-drawer-list-item-attachments': EmailConnectorMailBoxDrawerListItemAttachments,
  'email-connector-mail-box-drawer-list-item-attachments-list-item': EmailConnectorMailBoxDrawerListItemAttachmentsListItem,
  'email-connector-mail-box-drawer-list-item-detail': EmailConnectorMailBoxDrawerListItemDetail,
  'email-connector-mail-box-drawer-list-item-detail-action-menu': EmailConnectorMailBoxDrawerListItemDetailActionMenu,
  'email-connector-mail-box-drawer-list-item-detail-action-menu-items': EmailConnectorMailBoxDrawerListItemDetailActionMenuItems,
  'email-connector-mail-box-drawer-list-item-detail-actions': EmailConnectorMailBoxDrawerListItemDetailActions,
  'email-connector-mail-box-drawer-list-item-detail-attachments': EmailConnectorMailBoxDrawerListItemDetailAttachments,
  'email-connector-mail-box-drawer-list-item-detail-body': EmailConnectorMailBoxDrawerListItemDetailBody,
  'email-connector-mail-box-drawer-list-item-detail-content': EmailConnectorMailBoxDrawerListItemDetailContent,
  'email-connector-mail-box-drawer-thread-content': EmailConnectorMailBoxDrawerThreadContent,
  'email-connector-mail-box-drawer-thread-message': EmailConnectorMailBoxDrawerThreadMessage,
  'email-connector-mail-box-drawer-category-bar': EmailConnectorMailBoxDrawerCategoryBar,
  'email-connector-mail-box-drawer-list-item-detail-header': EmailConnectorMailBoxDrawerListItemDetailHeader,
  'email-connector-mail-box-drawer-list-item-detail-header-item': EmailConnectorMailBoxDrawerListItemDetailHeaderItem,
  'email-connector-mail-box-drawer-list-item-detail-sender-avatar': EmailConnectorMailBoxDrawerListItemDetailSenderAvatar,
  'email-connector-mail-box-drawer-multi-select-email': EmailConnectorMailBoxDrawerMultiSelectEmail,
  'email-connector-mail-box-drawer-search-results': EmailConnectorMailBoxDrawerSearchResults,
  'email-connector-mail-box-drawer-search-result-item': EmailConnectorMailBoxDrawerSearchResultItem,
  'email-connector-mail-box-drawer-no-email': EmailConnectorMailBoxDrawerNoEmail,
  'email-connector-mail-box-drawer-select-email': EmailConnectorMailBoxDrawerSelectEmail,
  'email-connector-mail-box-drawer-favorite-toggle': EmailConnectorMailBoxDrawerFavoriteToggle,
  'email-connector-mail-box-drawer-filter-chips': EmailConnectorMailBoxDrawerFilterChips,
  'email-connector-new-email-drawer': EmailConnectorNewEmailDrawer,
  'email-connector-new-email-drawer-attachments': EmailConnectorNewEmailDrawerAttachments,
  'email-connector-new-email-drawer-no-subject-confirm-popup': EmailConnectorNewEmailDrawerNoSubjectConfirmPopup
};

for (const key in components) {
  Vue.component(key, components[key]);
}
