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

package org.exoplatform.emailConnector.model;

import java.util.List;

/**
 * One address-book entry, in the terms the store keeps rather than the terms
 * vCard speaks. The protocol layer parses; this is what it hands over, so nothing
 * below the service knows what a vCard is.
 *
 * @param primaryEmail the normalized address the contact is keyed on
 * @param secondaryEmails the other addresses, already normalized
 * @param displayName the name to show
 * @param givenName the first name, when the entry separates them
 * @param familyName the last name, when the entry separates them
 * @param phones the phone numbers
 * @param organization the company
 * @param title the job title
 * @param vcardUid the entry's own identity on the server
 * @param photo the picture bytes, or null when the entry carries none
 * @param photoMimeType the picture's type
 */
public record CardDavContactData(String primaryEmail,
                                 List<String> secondaryEmails,
                                 String displayName,
                                 String givenName,
                                 String familyName,
                                 List<String> phones,
                                 String organization,
                                 String title,
                                 String vcardUid,
                                 byte[] photo,
                                 String photoMimeType) {
}
