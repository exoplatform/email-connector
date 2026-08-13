/*
 * Copyright (C) 2025 eXo Platform SAS.
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
 * What to call the person behind an address, in one place.
 *
 * Every screen that shows a recipient had its own answer, and they disagreed: some
 * fell back to the address when there was no name, some printed the empty name and
 * left a gap, and none of them expected the name to be the four characters "null" —
 * which is what a whole generation of stored drafts carries, and what the owner
 * watched a composer chip read. The server no longer hands that word out, and this
 * is the second line: whatever a row, a suggestion or an older page hands the reader,
 * a person is named after their address when they have no name of their own.
 */

// The word a null display name used to be concatenated into. Treated as no name at
// all, here as on the server (see EmailBoxStorage#storedName), so a value that
// escaped an older client, an older row or a contact collected from one cannot be
// shown to the user as somebody's name.
const NULL_NAME = 'null';

/**
 * The display name of a person, or nothing when they have none.
 *
 * @param {object} person - a recipient, a sender or anything carrying {name}
 * @returns {string} the name to show, or an empty string when there is none
 */
export function personName(person) {
  const name = person?.name?.trim();
  return !name || name === NULL_NAME ? '' : name;
}

/**
 * How a person reads on screen: their name when they have one, their address
 * otherwise — never an empty gap, and never the word null.
 *
 * @param {object} person - a recipient, a sender or anything carrying {name, address}
 * @returns {string} the label to show
 */
export function personLabel(person) {
  return personName(person) || person?.address?.trim() || '';
}
