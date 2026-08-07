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

import java.util.List;

/**
 * One vCard, reduced to what a contact row can hold. Everything else a vCard may
 * carry — birthdays, addresses, notes, arbitrary extensions — is dropped here on
 * purpose: the store has nowhere to put it, and carrying it would invite a
 * schema that mirrors vCard instead of serving the product.
 *
 * @param uid the vCard's own identity, kept so an entry that moves on the server
 *          can still be recognised
 * @param formattedName the name as the vCard presents it
 * @param givenName the first name, when the vCard separates them
 * @param familyName the last name, when the vCard separates them
 * @param emails every address, preferred first — the first is the one the
 *          contact is keyed on
 * @param phones every phone number, in vCard order
 * @param organization the company
 * @param title the job title
 * @param photo the picture bytes, or null; a vCard may instead point at a URL,
 *          which this deliberately does not fetch
 * @param photoMimeType the picture's type, when the vCard declared one
 */
public record ParsedVCard(String uid,
                          String formattedName,
                          String givenName,
                          String familyName,
                          List<String> emails,
                          List<String> phones,
                          String organization,
                          String title,
                          byte[] photo,
                          String photoMimeType) {
}
