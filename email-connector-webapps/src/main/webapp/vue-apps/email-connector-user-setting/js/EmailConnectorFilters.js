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

/** The root event the Settings row opens the filters drawer with. */
export const OPEN_FILTERS_DRAWER_EVENT = 'open-email-filters-drawer';

/** The document event the drawer dispatches after a write, so the row reads again. */
export const FILTERS_UPDATED_EVENT = 'email-filters-updated';

/** The condition fields, in the form's order. */
export const FIELDS = ['FROM', 'TO', 'CC', 'ANY_RECIPIENT', 'SUBJECT', 'HEADER', 'MESSAGE_SIZE', 'IS_LIST', 'IS_AUTOMATED'];

/** The operators on text. */
const TEXT_OPERATORS = ['CONTAINS', 'NOT_CONTAINS', 'EQUALS', 'STARTS_WITH', 'ENDS_WITH'];

/**
 * The operators a field accepts, in the form's order.
 *
 * @param {string} field - one of FIELDS
 * @returns {string[]} the operators
 */
export function operatorsOf(field) {
  switch (field) {
  case 'FROM':
  case 'TO':
  case 'CC':
  case 'ANY_RECIPIENT':
    return [...TEXT_OPERATORS, 'MATCHES_DOMAIN'];
  case 'MESSAGE_SIZE':
    return ['GT', 'LT'];
  case 'IS_LIST':
  case 'IS_AUTOMATED':
    return ['IS_TRUE', 'IS_FALSE'];
  default:
    return TEXT_OPERATORS;
  }
}

/**
 * Whether a field takes no value: it is true or false of a mail.
 *
 * @param {string} field - one of FIELDS
 * @returns {boolean} true for a flag field
 */
export function isFlagField(field) {
  return field === 'IS_LIST' || field === 'IS_AUTOMATED';
}

/**
 * Whether the mail server can run an element of the form.
 *
 * @param {object} capabilities - the probe's answer
 * @param {string} element - a field or an action type
 * @returns {boolean} true when supported
 */
export function isSupported(capabilities, element) {
  return !!capabilities?.supported && !!capabilities?.elements?.[element]?.supported;
}

/**
 * Notifies the Settings row that the filters changed.
 *
 * @returns {void}
 */
export function notifyFiltersUpdated() {
  document.dispatchEvent(new CustomEvent(FILTERS_UPDATED_EVENT));
}

/**
 * The user's words for a refused filters request: the filter-specific text of its code
 * when there is one, the automatic reply's text of a transport code otherwise, a
 * generic sentence last.
 *
 * @param {Function} t - the translation function
 * @param {Error} error - the refusal, its message the server's code
 * @returns {string} the localized message
 */
export function filtersMessage(t, error) {
  const code = error?.message || '';
  const candidates = [];
  if (code.startsWith('emailConnector.absence.')) {
    candidates.push(`UserSettings.emailConnector.filters.${code.substring('emailConnector.absence.'.length)}`);
    candidates.push(`UserSettings.${code}`);
  } else if (code.startsWith('emailConnector.')) {
    candidates.push(`UserSettings.${code}`);
    if (code.startsWith('emailConnector.rules.unsupported.')) {
      candidates.push('UserSettings.emailConnector.rules.unsupported');
    }
  }
  for (const key of candidates) {
    const text = t(key, { 0: error?.scriptName || '' });
    if (text !== key) {
      return text;
    }
  }
  return t('UserSettings.emailConnector.filters.error');
}
