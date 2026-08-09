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

// The contacts app's components first — the host renders its form drawer, and
// the picker renders its list. Chat pages do not necessarily run the contacts
// bundle, so this bundle carries them itself; re-registration is harmless when
// both bundles happen to run on the same page (identical components, same WAR).
import '../email-connector-contacts/initComponents.js';

import EmailConnectorChatExtensionApp from './components/EmailConnectorChatExtensionApp.vue';
import EmailConnectorChatContactPickerDrawer from './components/EmailConnectorChatContactPickerDrawer.vue';

const components = {
  'email-connector-chat-extension-app': EmailConnectorChatExtensionApp,
  'email-connector-chat-contact-picker-drawer': EmailConnectorChatContactPickerDrawer,
};

for (const key in components) {
  Vue.component(key, components[key]);
}
