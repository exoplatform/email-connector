/**
 * Copyright (C) 2025 eXo Platform SAS
 *
 *  This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <gnu.org/licenses>.
 */

package org.exoplatform.emailConnector.carddav;

/**
 * One entry of an address book, as the server returned it.
 *
 * @param href the entry's path on the server — its identity, which the local row
 *          stores so later syncs can tell an edit from a new contact
 * @param etag the entry's version, so an unchanged entry is never re-parsed
 * @param vcard the raw vCard text, left unparsed here: reading it is the
 *          parser's business, and keeping the two apart is what lets the parser
 *          be swapped without touching the protocol
 */
public record ContactResource(String href, String etag, String vcard) {
}
