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
import java.util.Map;

/**
 * The CardDAV protocol, and nothing else: it fetches, it does not decide. No
 * business rule, no storage, no notion of what a contact means locally — the
 * sync service owns all of that.
 * <p>
 * It exists as an interface so the sync can be tested against canned answers
 * rather than a server, and so a different implementation could be dropped in
 * without the service noticing.
 */
public interface CardDavClient {

  /**
   * Finds the user's address book, starting from whatever the administrator
   * configured: a server base URL to discover from, or the collection itself.
   *
   * @param baseUrl the configured CardDAV URL
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the address book found
   * @throws CardDavException when the server cannot be reached, refuses the
   *           credentials, or exposes no address book
   */
  AddressBook discoverAddressBook(String baseUrl, String username, String password);

  /**
   * Reads the collection's current version, the cheap question asked first on
   * every run.
   *
   * @param addressBook the collection to ask about
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the current ctag, or null when the server does not implement it
   * @throws CardDavException when the server cannot be reached or refuses
   */
  String getCtag(AddressBook addressBook, String username, String password);

  /**
   * Lists every entry in the collection with its version, which is what tells
   * the sync who is new, who changed, and who is gone.
   *
   * @param addressBook the collection to list
   * @param username the account to authenticate as
   * @param password that account's password
   * @return entry path to entry version, never null
   * @throws CardDavException when the server cannot be reached or refuses
   */
  Map<String, String> listResourceEtags(AddressBook addressBook, String username, String password);

  /**
   * Fetches the vCards of the given entries in one request — the whole point of
   * the protocol's multiget, and why a changed address book costs one round trip
   * per batch rather than one per contact.
   *
   * @param addressBook the collection the entries belong to
   * @param hrefs the entry paths to fetch
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the entries the server returned, never null; an href it did not
   *         return is simply absent, which the caller must tolerate
   * @throws CardDavException when the server cannot be reached or refuses
   */
  List<ContactResource> multiget(AddressBook addressBook, List<String> hrefs, String username, String password);
}
