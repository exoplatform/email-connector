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

/*
 * Adds an "Emails" section to the platform's global Favorites drawer, holding the
 * mails the user favorited in the mailbox.
 *
 * The rank places the section among the other content types of the drawer; 55 sits
 * between the gamification rules (50) and the AI prompts (60), both of which are
 * already taken, as are space (10), activity (20), notes (30), news (40), file (45),
 * chat rooms and projects (70) and tasks (71).
 *
 * The section header reads from `UITopBarFavoritesPortlet.types.email`, which this
 * add-on ships in its own Portlets bundle — the platform merges the same-named
 * bundles across webapps, so the drawer finds our label without social knowing that
 * emails exist.
 */
export function initEmailFavoriteExtensions() {
  extensionRegistry.registerExtension('favorite', 'favorite-type', {
    rank: 55,
    id: 'email',
    name: 'Emails',
    icon: 'fa-envelope',
  });
  extensionRegistry.registerComponent('favorite-email', 'favorite-drawer-item', {
    id: 'email',
    vueComponent: Vue.options.components['email-connector-favorite-item'],
  });
}
