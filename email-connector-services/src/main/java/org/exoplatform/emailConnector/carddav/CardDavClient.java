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
   * The account every other method of this client takes, minted from a resolved
   * connector row.
   * <p>
   * The one place a {@link CardDavAccount} can come from: its constructor is
   * package-private, so no caller can assemble one of its own.
   *
   * @param connectorId the connector preset the account is bound to
   * @param providerName the credentials provider that preset is configured with
   * @param username the eXo login the material is resolved for
   * @return the account to hand to every request of one conversation
   */
  CardDavAccount accountOf(Long connectorId, String providerName, String username);

  /**
   * The address-book URL for one account, from the URL as the administrator wrote
   * it.
   * <p>
   * Some providers put the account inside the collection path — Google's is
   * {@code /carddav/v1/principals/somebody@gmail.com/lists/default/} — which a
   * single preset shared by every user of that provider cannot hold literally.
   * Discovery would normally spare us this, but Google does not serve
   * {@code /.well-known/carddav} at all. So the preset carries the shape and this
   * fills in whose account it is.
   * <p>
   * <b>Whose account is the configured provider's answer, not the stored
   * address.</b> That is what lets a provider authenticating as a technical
   * account say which address book it is acting on; for Personal the answer is
   * the address the user entered, so the URL is unchanged.
   *
   * @param configuredUrl the URL as the administrator wrote it, possibly carrying
   *          a placeholder
   * @param account whose address book this is and through which provider the
   *          account is named
   * @return the URL to talk to
   * @throws CardDavException when the URL needs an account and the provider names
   *           none, or names one that cannot sit in a URL path
   */
  String resolveUrl(String configuredUrl, CardDavAccount account);

  /**
   * Finds the user's address book, starting from whatever the administrator
   * configured: a server base URL to discover from, or the collection itself.
   *
   * @param baseUrl the configured CardDAV URL
   * @param account whose address book this is and through which provider it
   *          authenticates — the material is resolved per request, so that a
   *          provider whose header depends on the request can produce one
   * @return the address book found
   * @throws CardDavException when the server cannot be reached, refuses the
   *           credentials, or exposes no address book
   */
  AddressBook discoverAddressBook(String baseUrl, CardDavAccount account);

  /**
   * Reads the collection's current version, the cheap question asked first on
   * every run.
   *
   * @param addressBook the collection to ask about
   * @param account whose address book this is and through which provider it
   *          authenticates — the material is resolved per request, so that a
   *          provider whose header depends on the request can produce one
   * @return the current ctag, or null when the server does not implement it
   * @throws CardDavException when the server cannot be reached or refuses
   */
  String getCtag(AddressBook addressBook, CardDavAccount account);

  /**
   * Lists every entry in the collection with its version, which is what tells
   * the sync who is new, who changed, and who is gone.
   *
   * @param addressBook the collection to list
   * @param account whose address book this is and through which provider it
   *          authenticates — the material is resolved per request, so that a
   *          provider whose header depends on the request can produce one
   * @return entry path to entry version, never null
   * @throws CardDavException when the server cannot be reached or refuses
   */
  Map<String, String> listResourceEtags(AddressBook addressBook, CardDavAccount account);

  /**
   * Fetches the vCards of the given entries in one request — the whole point of
   * the protocol's multiget, and why a changed address book costs one round trip
   * per batch rather than one per contact.
   *
   * @param addressBook the collection the entries belong to
   * @param hrefs the entry paths to fetch
   * @param account whose address book this is and through which provider it
   *          authenticates — the material is resolved per request, so that a
   *          provider whose header depends on the request can produce one
   * @return the entries the server returned, never null; an href it did not
   *         return is simply absent, which the caller must tolerate
   * @throws CardDavException when the server cannot be reached or refuses
   */
  List<ContactResource> multiget(AddressBook addressBook, List<String> hrefs, CardDavAccount account);

  /**
   * Reads one entry's current card and version in a single GET — what an edit
   * asks first, so the merge patches the card as the server holds it NOW rather
   * than as the last sync remembers it.
   *
   * @param url the absolute entry URL to read
   * @param account whose address book this is and through which provider it
   *          authenticates — the material is resolved per request, so that a
   *          provider whose header depends on the request can produce one
   * @return the entry as the server returned it, or null when the server says
   *         there is no such entry any more — an answer, not an error, because
   *         the caller must tell "gone" apart from "unreachable"
   * @throws CardDavException when the server cannot be reached, refuses the
   *           credentials, or errors
   */
  ContactResource fetchVCard(String url, CardDavAccount account);

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
   * @param account whose address book this is and through which provider it
   *          authenticates — the material is resolved per request, so that a
   *          provider whose header depends on the request can produce one
   * @return the status and the stored card's etag when the server sent one
   * @throws CardDavException when the server cannot be reached, refuses the
   *           credentials, or answers a status that is neither a write nor a
   *           precondition refusal
   */
  PutResult putVCard(String url, String vcard, String ifNoneMatch, CardDavAccount account);

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
   * @param account whose address book this is and through which provider it
   *          authenticates — the material is resolved per request, so that a
   *          provider whose header depends on the request can produce one
   * @return the status and the stored card's etag when the server sent one
   * @throws CardDavException when the server cannot be reached, refuses the
   *           credentials, or answers a status that is neither a write nor a
   *           precondition refusal
   */
  PutResult updateVCard(String url, String vcard, String ifMatch, CardDavAccount account);
}
