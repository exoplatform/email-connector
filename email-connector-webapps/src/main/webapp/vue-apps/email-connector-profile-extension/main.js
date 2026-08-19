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
 * The "Add to my contacts" action on a people profile header: one click files
 * the colleague into the caller's contact store as a directory-linked row and
 * opens the Contacts drawer on the new card.
 *
 * The action lives in the profile header's OWN extension point
 * ('profile-header'/'action-component'), registered by the always-on head
 * script js/emailConnectorProfileHeaderPlugin.js — NOT in the shared
 * ('profile-extension'/'action') point, which every user card surface (people
 * list, search results, org chart, popovers) renders too and where the product
 * does not want it. That point's contract is imperative: the header calls
 * init(container, username) below once its per-action container div is
 * mounted, and this module renders its own button into it.
 */

/**
 * The profile header's entry point for this action, called once per header
 * with the mounted container element and the profile owner's username. The
 * header only renders action components when the viewer is NOT the profile
 * owner, and this module only mounts for a live colleague — a disabled or
 * deleted profile shows nothing, exactly as the old card-action guard did.
 * The i18n bundle loads BEFORE mounting because the button's tooltip renders
 * through the shared i18n instance.
 *
 * @param {Element} container - the header's per-action container div
 * @param {string} username - the profile owner's platform username
 * @returns {void}
 */
export function init(container, username) {
  if (!container || !username || username === eXo.env.portal.userName
      || container.dataset.emailConnectorContactMounted) {
    return;
  }
  container.dataset.emailConnectorContactMounted = 'true';
  fetch(`/portal/rest/v1/social/users/${encodeURIComponent(username)}`, {credentials: 'include'})
    .then(response => response.ok && response.json() || null)
    .then(user => {
      if (!user?.enabled || user?.deleted) {
        return;
      }
      const lang = eXo?.env?.portal?.language || 'en';
      const url = `/email-connector/i18n/locale.portlet.emailConnector.emailConnectorContacts?lang=${lang}`;
      window.require(['SHARED/eXoVueI18n'], exoi18n =>
        exoi18n.loadLanguageAsync(lang, url).then(i18n => mountAction(container, username, i18n)));
    });
}

/**
 * Mounts the action button into the header's container — into an appended
 * child div, because Vue 2's $mount REPLACES its target and the container is
 * the header's own ref div, carrying the classes the header put on it. The
 * button mirrors the header's sibling action buttons (same v-btn chrome, same
 * breakpoint-driven icon size as the header's own iconSize computed).
 *
 * @param {Element} container - the header's per-action container div
 * @param {string} username - the profile owner's platform username
 * @param {object} i18n - the shared vue-i18n instance with our bundle merged
 * @returns {void}
 */
function mountAction(container, username, i18n) {
  const mountPoint = document.createElement('div');
  // The header centres its own action buttons with my-auto inside a `d-flex justify-end`
  // row; the container it hands an init() extension is a plain block div, two levels
  // down from that row, so my-auto had nothing to centre against and the button sat
  // low. The wrapper establishes the flex context the class expects.
  mountPoint.className = 'd-flex align-center';
  container.appendChild(mountPoint);
  Vue.createApp({
    template: `
      <v-btn
        :title="label"
        :aria-label="label"
        :class="{'ms-2': lgAndUp, 'ms-0': !lgAndUp}"
        class="no-border my-auto mb-0"
        icon
        @click="addToContacts">
        <v-icon
          class="ma-1"
          :size="iconSize"
          color="primary">
          fas fa-address-book
        </v-icon>
      </v-btn>`,
    computed: {
      label() {
        return this.$t('emailConnector.contacts.profileAction.add');
      },
      iconSize() {
        return this.$vuetify.breakpoint.width < this.$vuetify.breakpoint.thresholds.lg ? 16 : 20;
      },
      // The same start margin the header puts on its own buttons, at the same
      // breakpoint: without it this one sat flush against the call button beside it.
      lgAndUp() {
        return this.$vuetify.breakpoint.width >= this.$vuetify.breakpoint.thresholds.lg;
      },
    },
    methods: {
      addToContacts() {
        addToContacts(username);
      },
    },
    vuetify: Vue.prototype.vuetifyOptions,
    i18n,
  }, mountPoint, 'Email Connector Profile Header Action');
}

/**
 * Imports the colleague into the caller's store and opens the Contacts drawer
 * on the resulting card. A 409 means a visible row already holds the person
 * (by link or by address): that row is re-read via /by-user and opened
 * instead, so the click always lands the user on the colleague's card.
 *
 * @param {string} username - the profile owner's platform username
 * @returns {Promise<void>} resolves when the drawer was asked to open
 */
async function addToContacts(username) {
  try {
    const response = await fetch(`/email-connector/rest/contacts/directory?username=${encodeURIComponent(username)}`, {
      method: 'POST',
      credentials: 'include',
    });
    if (response.ok) {
      const contact = await response.json();
      document.dispatchEvent(new CustomEvent('alert-message', {
        detail: {
          alertType: 'success',
          alertMessageKey: 'emailConnector.contacts.profileAction.added',
        },
      }));
      openContactCard(contact?.id);
    } else if (response.status === 409) {
      const existing = await fetch(`/email-connector/rest/contacts/by-user?username=${encodeURIComponent(username)}`, {
        credentials: 'include',
      });
      if (existing.ok) {
        openContactCard((await existing.json())?.id);
      }
    } else {
      failToast();
    }
  } catch (e) {
    failToast();
  }
}

/**
 * Opens the Contacts drawer on one card, through the cross-app document event
 * the contacts app listens for — the same contract the global Favorites
 * drawer and the mailbox header use. The contacts module is require()d first,
 * exactly as the favorites item does: its listener lives in a QuickActionsGrp
 * module that a page may have defined without executing, and an event
 * dispatched before the define factory ran lands on nobody.
 *
 * @param {number} contactId - the contact row to open
 * @returns {void}
 */
function openContactCard(contactId) {
  if (contactId) {
    window.require(['SHARED/emailConnectorContactsQuickActionExtension'], () =>
      document.dispatchEvent(new CustomEvent('open-contacts-drawer', {
        detail: {contactId},
      })));
  }
}

/**
 * The platform's error toast for the imports that failed for a reason a
 * click cannot fix (server down, session gone).
 *
 * @returns {void}
 */
function failToast() {
  document.dispatchEvent(new CustomEvent('alert-message', {
    detail: {
      alertType: 'error',
      alertMessageKey: 'emailConnector.contacts.profileAction.error',
    },
  }));
}
