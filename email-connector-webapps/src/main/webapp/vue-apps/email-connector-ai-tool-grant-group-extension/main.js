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

/**
 * The "In your mail filters" group of the AI assistant's standing approvals drawer
 * (EXO-90956): the approvals the user gave a rule's assistant with "Don't ask again for
 * this filter", each shown with the rule's name and opening the mail settings, as the
 * server describes them. Registered through the AI add-on's extension point, app
 * 'AiToolGrants', type 'grant-origin-group'; the drawer runs this module because its
 * name holds 'AiToolGrantGroupExtension'.
 */
extensionRegistry.registerExtension('AiToolGrants', 'grant-origin-group', {
  id: 'email-filters',
  rank: 20,
  titleKey: 'AiUserSettings.grants.group.emailFilters',
  title: 'In your mail filters',
  icon: 'fa-filter',
  unknownSourceKey: 'AiUserSettings.grants.aMailFilter',
  matches: grant => grant?.origin === 'EMAIL_FILTER',
});
