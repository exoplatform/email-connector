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

// Contributes a "Send by email" item to the Documents file action menu, under the
// existing "Actions" group (next to "Post on feed"). This module lives entirely in
// the email add-on; the Documents app discovers it by module name (its gatein
// <name> contains "DocumentsExtension", matched by includeExtensions('DocumentsExtension')),
// so the action is present only when the email connector is deployed. No Documents change.
extensionRegistry.registerExtension('DocumentMenu', 'menuActionMenu', {
  id: 'send-by-email',
  labelKey: 'documents.label.sendByEmail',
  align: 'center',
  sortable: true,
  cssClass: 'text-truncate',
  width: '190px',
  rank: 92,
  parent: 'actionsGroup',
  enabled: (file) => {
    return file && !file.folder && !file.cloudDriveFolder;
  },
  enabledForMultiSelection: () => false,
  componentOptions: {
    vueComponent: Vue.options.components['email-connector-send-by-email-menu-action'],
  },
});
