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
 * opens the Contacts drawer on the new card. Slice 1 is the plain action; the
 * stateful Add / View toggle is slice 2.
 */

/**
 * Loads this add-on's contact translations, then registers the profile action.
 * The i18n load comes FIRST because the profile header renders the action's
 * tooltip through the shared i18n instance ($t on titleKey), and registering
 * after the merge is safe: the header re-runs its extension load on the
 * extension-profile-extension-action-updated event registration fires.
 *
 * @returns {void}
 */
export function init() {
  const lang = eXo?.env?.portal?.language || 'en';
  const url = `/email-connector/i18n/locale.portlet.emailConnector.emailConnectorContacts?lang=${lang}`;
  window.require(['SHARED/eXoVueI18n'], exoi18n =>
    exoi18n.loadLanguageAsync(lang, url).then(registerProfileAction));
}

/**
 * Registers the icon action into social's 'profile-extension'/'action' point.
 * The enabled signature is (user, spaceId, isCard) — the point is consumed
 * with different arities: the profile header passes the user alone, people
 * cards pass all three with isCard=true. The action shows only on the full
 * header (not cards), for a live colleague who is not the viewer.
 *
 * @returns {void}
 */
function registerProfileAction() {
  extensionRegistry.registerExtension('profile-extension', 'action', {
    id: 'email-connector-add-contact',
    titleKey: 'emailConnector.contacts.profileAction.add',
    icon: 'fas fa-address-book',
    order: 20,
    enabled: (user, spaceId, isCard) => !isCard
      && !!user?.enabled
      && !user?.deleted
      && user?.username !== eXo.env.portal.userName,
    click: user => addToContacts(user?.username),
  });
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
