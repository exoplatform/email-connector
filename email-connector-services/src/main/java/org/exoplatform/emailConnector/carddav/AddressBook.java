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
 * One address-book collection on a CardDAV server, as discovery found it.
 *
 * @param url the absolute URL of the collection — every later request is made
 *          against it, so discovery resolves it once and the sync stores it
 * @param displayName the server's own name for it, for logs and diagnostics
 * @param ctag the collection's version: unchanged means nothing in the whole
 *          address book changed, which is what makes the periodic sync nearly
 *          free. Null when the server does not implement it, and the sync then
 *          falls back to comparing entry versions every run.
 */
public record AddressBook(String url, String displayName, String ctag) {
}
