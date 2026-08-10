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
 * Registers the "Add to my contacts" action into the profile header's OWN
 * extension point, ('profile-header', 'action-component') — the point that is
 * rendered on the profile page alone, unlike the shared
 * ('profile-extension', 'action') point which every user card (people list,
 * search results, org chart, popovers) renders too.
 *
 * WHY THIS FILE IS A TOP-LEVEL <javascript> RESOURCE and not an AMD module:
 * profileHeader.bundle.js snapshots the point ONCE, in a module-scope const
 * evaluated when its AMD factory runs, and listens to no *-updated event for
 * it — an extension registered after that factory has run is silently never
 * rendered. A top-level <javascript> script (gatein-resources.xml) is served
 * in the page's synchronous head javascript, so the require below is enqueued
 * while the HTML head is still parsing — before the portlet bootstrap even
 * requires the header module, whose factory additionally waits on vue,
 * vuetify and commonVueComponents. This is exactly the mechanism the
 * platform's only other registrant of this point ships (webconferencing's
 * call button, webconferencing-call-plugin.js).
 *
 * The descriptor itself is inert chrome: everything real (i18n, the user
 * checks, the button) loads lazily in init(), which the header calls once
 * with the mounted container element and the profile owner's username — so
 * this always-on script costs every other page nothing but its parse.
 */
(function() {
  if (!window.require) {
    return;
  }
  window.require(['SHARED/extensionRegistry'], function(extensionRegistry) {
    extensionRegistry.registerExtension('profile-header', 'action-component', {
      // the registry de-duplicates on id||key, and the header uses key as the
      // container ref — keep both stable
      id: 'email-connector-add-contact',
      key: 'emailConnectorAddContact',
      // after webconferencing's call button (rank 24)
      rank: 30,
      // the header renders the container as `${appClass} ${typeClass}
      // ${mobileClass}` — all three must be strings or the class list reads
      // "undefined"
      appClass: 'email-connector-add-contact',
      typeClass: 'email-connector-add-contact--profile',
      mobileClass: '',
      // a boolean, not a function: the header filters on it as-is
      enabled: true,
      /**
       * Called by the profile header once its container div is mounted, only
       * on the profile page and only when the viewer is not the profile
       * owner. Defers everything to the add-on's AMD module, which decides
       * whether to render at all (live colleague, not the viewer) and mounts
       * the button.
       *
       * @param {Element} container - the header's per-action container div
       * @param {string} username - the profile owner's platform username
       * @returns {void}
       */
      init: function(container, username) {
        window.require(['SHARED/EmailConnectorProfileHeaderAction'], function(action) {
          action.init(container, username);
        });
      },
    });
  });
})();
