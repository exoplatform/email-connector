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
 * The "who and where" of a suggested tool call: the arguments a suggestion card names
 * on its folded row, before Approve, so a call is never approved without its recipients,
 * its people and its places in sight. Taken from the platform's tool definitions (the
 * ai-tool-definitions.json of each add-on): every argument naming an email recipient,
 * a user or a place the call writes to. Each maps to the group it is shown under, in
 * this order, and to how its values are read:
 * - address: an email address, shown in the warning colour when outside the owner's domain;
 * - user: a username, shown with the user's full name once resolved;
 * - space: a space id, shown with the space's display name once resolved;
 * - project: a task project id, shown with the project's name once resolved;
 * - raw: shown as given.
 * receiver_id names a user, or a space when its receiver_type says so (send_kudos).
 * Everything else -- bodies, subjects, descriptions, dates -- stays under the card's details.
 */
export const TARGET_ARGUMENTS = {
  to: { group: 'to', kind: 'address' },
  cc: { group: 'cc', kind: 'address' },
  bcc: { group: 'bcc', kind: 'address' },
  mailbox: { group: 'mailbox', kind: 'raw' },
  username: { group: 'people', kind: 'user' },
  usernames: { group: 'people', kind: 'user' },
  usernames_to_invite: { group: 'people', kind: 'user' },
  usernames_to_remove: { group: 'people', kind: 'user' },
  attendee_usernames: { group: 'people', kind: 'user' },
  assignee: { group: 'people', kind: 'user' },
  coworkers: { group: 'people', kind: 'user' },
  receiver_id: { group: 'people', kind: 'user', typeArgument: 'receiver_type' },
  space_id: { group: 'space', kind: 'space' },
  target_space_id: { group: 'space', kind: 'space' },
  parent_space_id: { group: 'space', kind: 'space' },
  space: { group: 'space', kind: 'raw' },
  space_pretty_name: { group: 'space', kind: 'raw' },
  targets: { group: 'audience', kind: 'raw' },
  project_id: { group: 'project', kind: 'project' },
  folder_id: { group: 'folder', kind: 'raw' },
  destination_folder_id: { group: 'folder', kind: 'raw' },
  parent_folder_id: { group: 'folder', kind: 'raw' },
  target_parent_note_id: { group: 'note', kind: 'raw' },
  conversation_id: { group: 'chat', kind: 'raw' },
};

/** The order the groups are shown in, on the card's folded row. */
const GROUP_ORDER = ['mailbox', 'to', 'cc', 'bcc', 'people', 'space', 'audience', 'project', 'folder', 'note', 'chat'];

/** How many values of a group the folded row shows before "+N". */
export const SHOWN_PER_GROUP = 3;

/** The owner's own address, read once per page: the connected account's, as its setting gives it. */
let ownerAddressPromise = null;

/**
 * The groups of targets a call's arguments name, in the order they are shown: each with
 * its values, as the model gave them, and how they are read.
 *
 * @param {Object|null} args - the call's arguments, as a parsed JSON object
 * @returns {Object[]} {group, values: [{key, kind, raw}]}, empty groups left out
 */
export function proposalTargets(args) {
  if (!args) {
    return [];
  }
  const groups = {};
  Object.keys(args).forEach(key => {
    const target = TARGET_ARGUMENTS[key];
    if (!target) {
      return;
    }
    const namesSpace = !!target.typeArgument && String(args[target.typeArgument]).toLowerCase() === 'space';
    const kind = namesSpace ? 'raw' : target.kind;
    const group = namesSpace ? 'space' : target.group;
    const values = (Array.isArray(args[key]) ? args[key] : [args[key]])
      .filter(value => typeof value === 'string' || typeof value === 'number')
      // A model may write several addresses in one text: each is shown, and checked, alone.
      .flatMap(value => (kind === 'address' ? String(value).split(/[,;]/) : [String(value)]))
      .map(value => value.trim())
      .filter(value => value)
      .map(value => ({ key, kind, raw: value }));
    if (values.length) {
      groups[group] = (groups[group] || []).concat(values);
    }
  });
  return GROUP_ORDER.filter(group => groups[group]).map(group => ({ group, values: groups[group] }));
}

/**
 * The bare address of a recipient as the model may write it: "Name <address>" or the
 * address alone.
 *
 * @param {String} value - the recipient
 * @returns {String} the address, lower-cased
 */
export function addressOf(value) {
  const text = String(value || '').trim();
  const bracketed = /<([^<>]+)>\s*$/.exec(text);
  return (bracketed ? bracketed[1] : text).trim().toLowerCase();
}

/**
 * The domain of an address, after its last "@".
 *
 * @param {String} value - the address, or a recipient as the model wrote it
 * @returns {String|null} the domain, lower-cased, or null when the value is no address
 */
export function domainOf(value) {
  const address = addressOf(value);
  const at = address.lastIndexOf('@');
  const domain = at > 0 ? address.substring(at + 1).trim() : '';
  return domain || null;
}

/**
 * Whether a recipient is outside the owner's organisation: any address whose domain is
 * not exactly the owner's own. A value that is no address, or any value while the
 * owner's domain is unknown, counts as outside -- the card never calls internal what
 * it could not check.
 *
 * @param {String} value - the recipient
 * @param {String|null} ownerDomain - the owner's domain, or null when unknown
 * @returns {Boolean} true when outside
 */
export function isExternalAddress(value, ownerDomain) {
  const domain = domainOf(value);
  return !ownerDomain || !domain || domain !== ownerDomain;
}

/**
 * The address of the mailbox the filters run on: the connected account's, read once
 * per page from the user's email setting. A failed read is not kept, so the next card
 * asks again.
 *
 * @param {Object} service - the add-on's common service, with getUserEmailSetting()
 * @returns {Promise<String|null>} the address, or null when unknown
 */
export function ownerAddress(service) {
  if (!service?.getUserEmailSetting) {
    return Promise.resolve(null);
  }
  if (!ownerAddressPromise) {
    ownerAddressPromise = service.getUserEmailSetting()
      .then(setting => setting?.emailAddress || null)
      .catch(() => {
        ownerAddressPromise = null;
        return null;
      });
  }
  return ownerAddressPromise;
}

/**
 * The name of a task project, read with the user's own rights from the Tasks add-on: a
 * project the user may not see, or no Tasks add-on, answers null and the id stays raw.
 *
 * @param {String|Number} projectId - the project's id
 * @returns {Promise<String|null>} the project's name, or null when not readable
 */
export function projectName(projectId) {
  return fetch(`${eXo.env.portal.context}/${eXo.env.portal.rest}/projects/projects/${encodeURIComponent(projectId)}`, {
    credentials: 'include',
    method: 'GET',
  })
    .then(resp => (resp?.ok ? resp.json() : null))
    .then(project => project?.name || null)
    .catch(() => null);
}
