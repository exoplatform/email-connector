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

/** The condition fields of a rule eXo alone runs: the server's but the size, plus three only eXo reads. */
export const EXO_FIELDS = ['FROM', 'TO', 'CC', 'ANY_RECIPIENT', 'SUBJECT', 'BODY', 'SUBJECT_OR_BODY', 'HEADER', 'IS_LIST',
  'IS_AUTOMATED', 'HAS_ATTACHMENT'];

/** The fields only eXo evaluates: a rule that also runs at delivery cannot use them. */
export const EXO_ONLY_FIELDS = ['BODY', 'SUBJECT_OR_BODY', 'HAS_ATTACHMENT'];

/** The post-actions eXo applies itself, in the form's order; the assistant comes through the extension point. */
export const EXO_ACTIONS = ['MOVE_TO_FOLDER', 'ADD_CATEGORY', 'MARK_READ', 'STAR', 'MARK_JUNK', 'DELETE', 'NOTIFY'];

/** The filing actions: a rule has one at most. */
export const FILING_ACTIONS = ['MOVE_TO_FOLDER', 'MARK_JUNK', 'DELETE'];

/**
 * Every condition field of the one filter form, in its order: the server's and the three
 * only eXo reads. Where a filter runs follows from them and from its actions (routeOf).
 */
export const ALL_FIELDS = ['FROM', 'TO', 'CC', 'ANY_RECIPIENT', 'SUBJECT', 'BODY', 'SUBJECT_OR_BODY', 'HEADER', 'MESSAGE_SIZE',
  'IS_LIST', 'IS_AUTOMATED', 'HAS_ATTACHMENT'];

/** The actions a mail server may run itself, when its capabilities say so. */
export const SERVER_ACTIONS = ['MOVE_TO_FOLDER', 'MARK_READ', 'STAR', 'MARK_JUNK', 'DELETE'];

/**
 * Where a filter will run, as the server decides it when it is saved
 * (EmailFilterService.route): SERVER when the mail server can run every condition and
 * every action, HOP when it can test every condition and set eXo's keyword but not run
 * every action, EXO otherwise. Used for the form's hint; the server's answer is the one
 * that counts.
 *
 * @param {object} filter - {conditions, actions}
 * @param {object} capabilities - the probe's answer, or null
 * @returns {string} SERVER, HOP or EXO
 */
export function routeOf(filter, capabilities) {
  if (!capabilities?.supported) {
    return 'EXO';
  }
  const conditions = filter?.conditions || [];
  const serverConditions = conditions.every(condition => !EXO_ONLY_FIELDS.includes(condition.field)
    && isSupported(capabilities, condition.field));
  if (!serverConditions) {
    return 'EXO';
  }
  const actions = filter?.actions || [];
  if (actions.every(action => SERVER_ACTIONS.includes(action.type) && isSupported(capabilities, action.type))) {
    return 'SERVER';
  }
  return isSupported(capabilities, 'TAG') ? 'HOP' : 'EXO';
}

/**
 * A server rule as an item of the one list, in the eXo rules' shape: kind SERVER, its
 * reference, and "stop" as stopProcessing.
 *
 * @param {object} rule - the rule, as the server group lists it
 * @returns {object} the item
 */
export function serverItem(rule) {
  return {
    kind: 'SERVER',
    ref: rule.ref,
    name: rule.name,
    enabled: !!rule.enabled,
    matchAll: rule.matchAll !== false,
    conditions: rule.conditions || [],
    actions: (rule.actions || []).map(action => ({ type: action.type, folderKey: action.folderKey, folderPath: action.folderPath })),
    stopProcessing: !!rule.stop,
  };
}

/**
 * The extension point of an eXo rule's actions a module adds (the assistant, shipped by
 * the enterprise glue): extensionRegistry.registerExtension('EmailFilter',
 * 'email-filter-action', {id, type, rank, labelKey, vueComponent}). The form renders
 * each one's vueComponent with v-model on the action object {type, ...} it owns -- for
 * type AGENT {agentNameId, instruction, outputs} -- and a `capabilities` prop.
 */
export const FILTER_ACTION_EXTENSION = { app: 'EmailFilter', type: 'email-filter-action' };

/**
 * The extension point of what an assistant made of one mail, in its Automations panel:
 * extensionRegistry.registerExtension('EmailFilter', 'email-filter-outcome', {id, rank,
 * vueComponent}); rendered per match that has an assistant, with the match as its
 * `match` prop and the mail as its `email` prop.
 */
export const FILTER_OUTCOME_EXTENSION = { app: 'EmailFilter', type: 'email-filter-outcome' };

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
  case 'HAS_ATTACHMENT':
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
  return field === 'IS_LIST' || field === 'IS_AUTOMATED' || field === 'HAS_ATTACHMENT';
}

/**
 * The rule a mail suggests, for "Create a filter from this mail": its sender's domain
 * for a list or automated mail, its sender otherwise, and a subject condition with the
 * numbers taken out, left out of the rule until the user keeps it.
 *
 * @param {object} email - the mail, as the mailbox lists it
 * @returns {object} {name, matchAll, conditions, subjectSuggestion}
 */
export function ruleFromMail(email) {
  const address = (email?.sender?.address || '').trim().toLowerCase();
  const domain = address.includes('@') ? address.substring(address.lastIndexOf('@') + 1) : '';
  const bulk = !!(email?.hasListId || email?.hasListPost || email?.hasListUnsubscribe || email?.autoSubmitted);
  const byDomain = bulk && domain;
  const subject = (email?.subject || '').replace(/[0-9]+/g, '').replace(/\s+/g, ' ').trim();
  return {
    name: (byDomain ? domain : (email?.sender?.name || address)).substring(0, 100),
    matchAll: true,
    conditions: [byDomain
      ? { field: 'FROM', operator: 'MATCHES_DOMAIN', value: domain }
      : { field: 'FROM', operator: 'EQUALS', value: address }],
    subjectSuggestion: subject ? { field: 'SUBJECT', operator: 'CONTAINS', value: subject.substring(0, 500) } : null,
  };
}

/** A size in kilobytes, as the form takes it. */
const SIZE = /^[1-9][0-9]{0,7}$/;

/** A header name, as the form takes it. */
const HEADER_NAME = /^[A-Za-z0-9-]{1,76}$/;

/**
 * What is wrong with a condition's header name, shared by the condition row that shows
 * it and the forms that decide whether they may save.
 *
 * @param {string} value - the header name
 * @returns {string} the i18n key of the reason, or null when valid
 */
export function headerError(value) {
  return HEADER_NAME.test((value || '').trim()) ? null : 'UserSettings.emailConnector.filters.form.headerInvalid';
}

/**
 * What is wrong with a condition's value, shared by the condition row that shows it and
 * the forms that decide whether they may save. A flag field takes no value.
 *
 * @param {string} field - one of FIELDS
 * @param {string} value - the value
 * @returns {string} the i18n key of the reason, or null when valid
 */
export function valueError(field, value) {
  if (isFlagField(field)) {
    return null;
  }
  const text = (value || '').trim();
  if (field === 'MESSAGE_SIZE') {
    return SIZE.test(text) ? null : 'UserSettings.emailConnector.filters.form.sizeInvalid';
  }
  return text.length > 0 && !/[\r\n]/.test(text) ? null : 'UserSettings.emailConnector.filters.form.required';
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
