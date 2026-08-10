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

  /**
   * Reads one entry's current card and version in a single GET — what an edit
   * asks first, so the merge patches the card as the server holds it NOW rather
   * than as the last sync remembers it.
   *
   * @param url the absolute entry URL to read
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the entry as the server returned it, or null when the server says
   *         there is no such entry any more — an answer, not an error, because
   *         the caller must tell "gone" apart from "unreachable"
   * @throws CardDavException when the server cannot be reached, refuses the
   *           credentials, or errors
   */
  ContactResource fetchVCard(String url, String username, String password);

  /**
   * Stores one vCard at an entry URL — the protocol's only write, and the only
   * one this connector performs.
   * <p>
   * The conditional header is what makes the write safe to offer: with
   * {@code If-None-Match: *} the server itself guarantees the PUT can only
   * CREATE — an entry already at that URL answers 412 instead of being
   * overwritten, whatever race led there. A 412 is answered as a
   * {@link PutResult}, not thrown: it is the server keeping a promise, and the
   * caller decides what the refusal means.
   *
   * @param url the absolute entry URL to store the card at
   * @param vcard the card text to store
   * @param ifNoneMatch the {@code If-None-Match} value to send — {@code "*"}
   *          to insist on creating, null to send no precondition at all (no
   *          caller does today; an unconditional PUT overwrites silently)
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the status and the stored card's etag when the server sent one
   * @throws CardDavException when the server cannot be reached, refuses the
   *           credentials, or answers a status that is neither a write nor a
   *           precondition refusal
   */
  PutResult putVCard(String url, String vcard, String ifNoneMatch, String username, String password);

  /**
   * Replaces one existing entry's card — the write an edit performs, guarded
   * the opposite way from {@link #putVCard}: {@code If-Match} with the version
   * the caller just read means the server only accepts the write when nobody
   * changed the entry since that read. A 412 is answered as a
   * {@link PutResult}, not thrown — it is the server protecting somebody
   * else's change, and the caller decides what the refusal means.
   *
   * @param url the absolute entry URL to overwrite
   * @param vcard the card text to store
   * @param ifMatch the {@code If-Match} value — the etag the caller's own
   *          fetch just answered; null sends no precondition at all, which the
   *          caller may only choose when the server answered no etag to match
   *          against
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the status and the stored card's etag when the server sent one
   * @throws CardDavException when the server cannot be reached, refuses the
   *           credentials, or answers a status that is neither a write nor a
   *           precondition refusal
   */
  PutResult updateVCard(String url, String vcard, String ifMatch, String username, String password);
}
