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

import * as service from './EmailConnectorMailBoxService.js';

/*
 * What a received attachment can be done with, as extensions rather than as a menu
 * hard-coded in the email app: another add-on contributes its own action without
 * this app knowing about it, the same way Documents lets us contribute "Send by
 * email" to its file menu.
 *
 *   extensionRegistry.registerExtension('emailConnector', 'mail-attachment-action', {
 *     id: 'my-action',                  // unique, also the menu item id
 *     rank: 50,                         // order in the menu, ascending
 *     labelKey: 'my.label.key',         // resolved against the email bundle
 *     icon: 'fa-magic',
 *     enabled: attachment => true,      // absent means always
 *     click: (attachment, context) => {},
 *   });
 *
 * labelKey is resolved against the bundle of the mail box, so a contributor ships
 * its own key in a locale/portlet/emailConnector/emailConnectorMailBox_en.properties
 * of its own — eXo merges same-named bundles across webapps. Same as what this
 * add-on does with Documents_en.properties for the action it contributes there.
 *
 * An action whose enabled() answers false is never rendered: a menu entry that
 * cannot do anything is worse than an absent one. A contributor needing more than a
 * label and a click (an asynchronous availability check, a badge, its own loader)
 * registers a `vueComponent` name instead of labelKey/icon/click; it is rendered in
 * place with the attachment as a prop and decides on its own whether to show.
 *
 * The `context` handed to click() carries what only the row itself can do:
 * - download(): download to the device, with the row's progress and abort handling;
 * - openInEditor(mode): store the attachment in the Drive then open the editor on it.
 */
const EXTENSION_TYPE = 'emailConnector';

const EXTENSION_NAME = 'mail-attachment-action';

/**
 * The actions that apply to an attachment, in the order they are shown.
 *
 * @param {Object} attachment the received attachment the menu is opened on
 * @returns {Array} the applicable action descriptors, sorted by rank
 */
export function getAttachmentActions(attachment) {
  return (extensionRegistry?.loadExtensions(EXTENSION_TYPE, EXTENSION_NAME) || [])
    .filter(action => !action.enabled || action.enabled(attachment))
    .sort((first, second) => (first.rank || 0) - (second.rank || 0));
}

extensionRegistry.registerExtension(EXTENSION_TYPE, EXTENSION_NAME, {
  id: 'download',
  rank: 10,
  labelKey: 'emailConnector.mailBox.attachment.action.download.label',
  icon: 'fa-download',
  click: (attachment, context) => context.download(),
});

extensionRegistry.registerExtension(EXTENSION_TYPE, EXTENSION_NAME, {
  id: 'saveInDocuments',
  rank: 20,
  labelKey: 'emailConnector.mailBox.attachment.action.saveInDocuments.label',
  icon: 'fa-hdd',
  enabled: () => service.isDocumentsDeployed(),
  click: attachment => service.saveAttachmentsInDocuments([attachment]),
});

extensionRegistry.registerExtension(EXTENSION_TYPE, EXTENSION_NAME, {
  id: 'forward',
  rank: 30,
  labelKey: 'emailConnector.mailBox.attachment.action.forward.label',
  icon: 'fa-share',
  click: attachment => service.forwardAttachment(attachment),
});

// Read only rather than editable on purpose: the copy stored in the Drive is a copy
// of somebody else's file, and editing it would only ever change that copy.
extensionRegistry.registerExtension(EXTENSION_TYPE, EXTENSION_NAME, {
  id: 'openReadOnly',
  rank: 40,
  labelKey: 'emailConnector.mailBox.attachment.action.openReadOnly.label',
  icon: 'fa-book-open',
  enabled: attachment => service.isEditorPreviewable(attachment),
  click: (attachment, context) => context.openInEditor('view'),
});

// "Analyse with AI" is deliberately absent. The AI add-on binds a menu action by
// reading its UX bindings for an application (aiUxBindingService.getUxBindings) then
// opening its chat on the object, and there is no binding for a document or a file:
// the Documents add-on exposes no AI action at all, so there is nothing to reuse
// here. It belongs on this extension point the day such a binding exists, contributed
// as a vueComponent by whoever owns that binding rather than guessed at from here.
