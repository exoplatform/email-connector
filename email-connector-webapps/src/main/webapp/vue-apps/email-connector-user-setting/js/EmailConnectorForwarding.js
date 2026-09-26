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
 * The document event every view of the user's forward listens to (EXO-90656): the
 * Settings row, the drawer and the mailbox band live in two apps, so a change made in
 * one is told to the others through the document. Whoever changes the forward
 * dispatches it; each view reads the server again.
 */
export const FORWARDING_UPDATED_EVENT = 'email-forwarding-updated';

/**
 * The root event that opens the forward's drawer, in whichever app mounts it (the
 * Settings page, the mailbox).
 */
export const OPEN_FORWARDING_DRAWER_EVENT = 'open-email-forwarding-drawer';

/** A plain address, as the server accepts a forwarding destination. */
const ADDRESS = /^[a-z0-9._%+-]{1,64}@(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?$/;

/**
 * Tells every view of the forward that it changed on the mail server.
 *
 * @returns {void}
 */
export function notifyForwardingUpdated() {
  document.dispatchEvent(new CustomEvent(FORWARDING_UPDATED_EVENT));
}

/**
 * A destination in the one form the server accepts, or null when it is not a plain
 * address: trimmed and lower-cased.
 *
 * @param {string} value - the address as typed
 * @returns {string} the address, or null
 */
export function normalizeDestination(value) {
  const address = (value || '').trim().toLowerCase();
  return address.length <= 254 && ADDRESS.test(address) && !address.includes('..') && !address.includes('.@') ? address : null;
}

/**
 * Whether a destination is in one of the allowed domains, exactly.
 *
 * @param {string} destination - a normalised address
 * @param {string[]} allowedDomains - the allowed domains
 * @returns {boolean} true when allowed
 */
export function isAllowedDestination(destination, allowedDomains) {
  const domain = destination ? destination.substring(destination.lastIndexOf('@') + 1) : '';
  return !!domain && (allowedDomains || []).includes(domain);
}

/**
 * The user's words for a refused forwarding request: the forwarding text of its code,
 * the automatic reply's text of a transport code, a generic sentence last.
 *
 * @param {Function} t - the translation function
 * @param {Error} error - the refusal, its message the server's code
 * @returns {string} the localized message
 */
export function forwardingMessage(t, error) {
  const code = error?.message || '';
  if (code.startsWith('emailConnector.')) {
    const key = `UserSettings.${code}`;
    const text = t(key, { 0: error?.scriptName || '' });
    if (text !== key) {
      return text;
    }
  }
  return t('UserSettings.emailConnector.forwarding.error');
}
