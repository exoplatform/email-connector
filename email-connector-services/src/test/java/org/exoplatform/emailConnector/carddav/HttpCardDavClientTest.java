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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.provider.EmailCredentialsResolver;
import org.exoplatform.services.connector.credentials.ConnectorCredentialsException;

/**
 * The protocol, exercised against canned answers. No network anywhere: the
 * transport is a mock, which is the only reason these can assert what the client
 * SENDS as well as what it makes of what comes back.
 */
public class HttpCardDavClientTest {

  private static final String  BASE     = "https://mail.example.com";

  /** What the configured provider answers, for every request of these tests. */
  private static final String  AUTHORIZATION = "Bearer produced-by-the-provider";

  private static final Long    CONNECTOR_ID  = 7L;

  private static final String  PROVIDER      = "personal";

  private static final String  USERNAME      = "alice";

  /**
   * Whose address book the calls under test are about. A real account rather than a
   * mock: this test sits in the account's own package, so it can mint one, and the
   * assertions can then check the client asks the provider with the very fields the
   * account carries rather than with whatever it had at hand.
   */
  private static final CardDavAccount ACCOUNT = new CardDavAccount(CONNECTOR_ID, PROVIDER, USERNAME);

  private static final String  BOOK_URL = "https://mail.example.com/dav/addressbooks/alice/default/";

  private HttpClient           transport;

  private HttpCardDavClient    client;

  private EmailCredentialsResolver resolver;

  private List<HttpRequest>    sent;

  @BeforeEach
  void setUp() {
    transport = mock(HttpClient.class);
    resolver = mock(EmailCredentialsResolver.class);
    client = new HttpCardDavClient(transport, resolver);
    sent = new ArrayList<>();
    try {
      // Deliberately not Basic, and derivable from no stored pair: a client that went
      // back to assembling its own header would produce something else.
      when(resolver.authorization(any(), any(), any())).thenReturn(AUTHORIZATION);
    } catch (ConnectorCredentialsException e) {
      throw new IllegalStateException(e);
    }
  }

  @Test
  void aConfiguredCollectionUrlIsUsedWithoutDiscovery() throws Exception {
    // An administrator who pasted the collection URL should not depend on
    // well-known discovery working on their server.
    givenAnswers(collectionResponse(BOOK_URL, "Alice's contacts", "ctag-1"));

    AddressBook book = client.discoverAddressBook(BOOK_URL, ACCOUNT);

    assertEquals(BOOK_URL, book.url());
    assertEquals("Alice's contacts", book.displayName());
    assertEquals("ctag-1", book.ctag());
    assertEquals(1, sent.size(), "a URL that is already a collection costs exactly one request");
    assertEquals("PROPFIND", sent.get(0).method());
  }

  @Test
  void discoveryWalksFromWellKnownToThePrincipalToTheHome() throws Exception {
    givenAnswers(notACollection(),
                 principalResponse("/dav/principals/alice/"),
                 homeSetResponse("/dav/addressbooks/alice/"),
                 collectionListResponse("/dav/addressbooks/alice/default/", "Contacts", "ctag-7"));

    AddressBook book = client.discoverAddressBook(BASE, ACCOUNT);

    assertEquals(BOOK_URL, book.url(), "the href the server answered is resolved to an absolute URL");
    assertEquals("ctag-7", book.ctag());
    assertTrue(sent.get(1).uri().toString().endsWith("/.well-known/carddav"),
               "discovery starts at the well-known path once the direct read failed");
  }

  @Test
  void everyRequestCarriesTheHeaderTheProviderProduced() throws Exception {
    givenAnswers(collectionResponse(BOOK_URL, "Contacts", "ctag-1"));

    client.discoverAddressBook(BOOK_URL, ACCOUNT);

    String authorization = sent.get(0).headers().firstValue("Authorization").orElse(null);
    // Sent unprompted rather than after a challenge: it is what CardDAV servers
    // expect, and it saves a round trip on every single request.
    // Passed through verbatim: since EXO-89708 the client assembles nothing, it asks
    // the configured provider — per request, so that a provider whose header depends
    // on the method and URI can exist.
    assertEquals(AUTHORIZATION, authorization);
  }

  @Test
  void theConfiguredUrlCarriesThePersonItIsFor() throws Exception {
    // Google puts the account inside the collection path, so one preset shared by
    // every user of a provider cannot hold it literally.
    when(resolver.targetAccount(CONNECTOR_ID, PROVIDER, USERNAME)).thenReturn("alice@example.com");

    String resolved = client.resolveUrl("https://www.googleapis.com/carddav/v1/principals/{email}/lists/default/", ACCOUNT);

    assertEquals("https://www.googleapis.com/carddav/v1/principals/alice@example.com/lists/default/", resolved);
  }

  @Test
  void theLocalPartPlaceholderTakesTheAccountUpToTheAtSign() throws Exception {
    when(resolver.targetAccount(any(), any(), any())).thenReturn("alice@example.com");

    assertEquals("https://mail.example.com/dav/alice/", client.resolveUrl("https://mail.example.com/dav/{localpart}/", ACCOUNT));
  }

  @Test
  void whoTheUrlAddressesIsWhoTheProviderNamesNotTheLoginItWasAskedFor() throws Exception {
    // The whole point of asking the provider: one authenticating as a technical
    // account says which address book it is acting on, and that is not derivable
    // from the eXo login the sync runs for.
    when(resolver.targetAccount(CONNECTOR_ID, PROVIDER, USERNAME)).thenReturn("team-books@example.com");

    String resolved = client.resolveUrl("https://mail.example.com/dav/{email}/", ACCOUNT);

    assertEquals("https://mail.example.com/dav/team-books@example.com/", resolved);
  }

  @Test
  void aFixedPathNeverAsksTheProviderWhoItIsFor() {
    // Nothing to substitute, so nothing to ask: the rest of the hrefs come from
    // discovery. Asking anyway would fail a provider that serves this connector's
    // mail channels and names no HTTP account.
    String resolved = client.resolveUrl("https://mail.example.com/dav/addressbooks/default/", ACCOUNT);

    assertEquals("https://mail.example.com/dav/addressbooks/default/", resolved);
    verifyNoInteractions(resolver);
  }

  @Test
  void aHostTypedWithoutASchemeIsStillAHost() {
    // What an administrator types is a host. Refusing it produced an error about
    // our URI parser, which says nothing about what to fix.
    assertEquals("https://webmail.example.com/dav/", client.resolveUrl("webmail.example.com/dav/", ACCOUNT));
  }

  @Test
  void aTemplatedUrlNoProviderCanFillInSaysSo() throws Exception {
    // Personal always names the address the user entered; a provider that names
    // none used to leave "{email}" in the path, and the failure surfaced as
    // "Illegal character in path at index 29" -- about our URI parser, not about
    // the account that is missing.
    when(resolver.targetAccount(any(), any(), any())).thenReturn(null);

    CardDavException failure = assertThrows(CardDavException.class,
                                            () -> client.resolveUrl("https://mail.example.com/dav/{email}/", ACCOUNT));

    assertTrue(failure.getMessage().contains(PROVIDER), "the message names the provider that could not answer");
  }

  @Test
  void anAccountThatCannotSitInAPathIsRefusedByItsOwnMessage() throws Exception {
    // A percent starts an escape the account is not, so the URI parser refuses it
    // downstream anyway -- here it is refused where the reason is still known.
    when(resolver.targetAccount(any(), any(), any())).thenReturn("jo%h@example.com");

    CardDavException failure = assertThrows(CardDavException.class,
                                            () -> client.resolveUrl("https://mail.example.com/dav/{email}/", ACCOUNT));

    assertTrue(failure.getMessage().contains("cannot be part of a URL path"), "the message says what is wrong with it");
  }

  @Test
  void theProviderIsAskedOncePerRequestNotOncePerConversation() throws Exception {
    // The reason the client is handed an account and not a produced header: a Digest
    // provider hashes the request method and URI, so one value produced for the whole
    // conversation would be wrong on every request but the first. Discovery is the
    // multi-request case, so it is where a client that cached the value once shows.
    givenAnswers(notACollection(),
                 principalResponse("/dav/principals/alice/"),
                 homeSetResponse("/dav/addressbooks/alice/"),
                 collectionListResponse("/dav/addressbooks/alice/default/", "Contacts", "ctag-7"));

    client.discoverAddressBook(BASE, ACCOUNT);

    assertTrue(sent.size() > 1, "the case only bites on a conversation of several requests");
    verify(resolver, times(sent.size())).authorization(CONNECTOR_ID, PROVIDER, USERNAME);
  }

  @Test
  void aServerWithoutCtagSupportSaysSoRatherThanFailing() throws Exception {
    givenAnswers(collectionResponse(BOOK_URL, "Contacts", null));

    String ctag = client.getCtag(new AddressBook(BOOK_URL, "Contacts", "old"), ACCOUNT);

    assertNull(ctag, "no ctag means the sync compares entry versions instead, not that the sync fails");
  }

  @Test
  void listingSkipsTheCollectionItselfAndAnythingWithoutAVersion() throws Exception {
    givenAnswers("""
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/dav/addressbooks/alice/default/</d:href>
            <d:propstat><d:prop/><d:status>HTTP/1.1 200 OK</d:status></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/addressbooks/alice/default/jane.vcf</d:href>
            <d:propstat><d:prop><d:getetag>"v1"</d:getetag></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>/dav/addressbooks/alice/default/bob.vcf</d:href>
            <d:propstat><d:prop><d:getetag>"v2"</d:getetag></d:prop></d:propstat>
          </d:response>
        </d:multistatus>""");

    Map<String, String> etags = client.listResourceEtags(new AddressBook(BOOK_URL, "Contacts", null), ACCOUNT);

    assertEquals(2, etags.size(), "the collection has no etag of its own and must not be taken for an entry");
    assertEquals("\"v1\"", etags.get("/dav/addressbooks/alice/default/jane.vcf"));
    assertEquals("\"v2\"", etags.get("/dav/addressbooks/alice/default/bob.vcf"));
  }

  @Test
  void multigetAsksForEveryHrefInOneRequestAndReadsTheCardsBack() throws Exception {
    givenAnswers("""
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
          <d:response>
            <d:href>/dav/addressbooks/alice/default/jane.vcf</d:href>
            <d:propstat><d:prop>
              <d:getetag>"v1"</d:getetag>
              <card:address-data>BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        EMAIL:jane@example.com
        END:VCARD</card:address-data>
            </d:prop></d:propstat>
          </d:response>
        </d:multistatus>""");

    List<ContactResource> resources = client.multiget(new AddressBook(BOOK_URL, "Contacts", null),
                                                      List.of("/dav/addressbooks/alice/default/jane.vcf",
                                                              "/dav/addressbooks/alice/default/bob.vcf"), ACCOUNT);

    assertEquals(1, sent.size(), "two entries, one request — that is the point of multiget");
    assertEquals("REPORT", sent.get(0).method());
    assertEquals(1, resources.size(), "an href the server chose not to return is simply absent");
    assertEquals("\"v1\"", resources.get(0).etag());
    assertTrue(resources.get(0).vcard().contains("FN:Jane Doe"));
  }

  @Test
  void multigetOfNothingDoesNotTouchTheNetwork() {
    List<ContactResource> resources = client.multiget(new AddressBook(BOOK_URL, "Contacts", null), List.of(), ACCOUNT);

    assertTrue(resources.isEmpty());
    assertTrue(sent.isEmpty(), "an empty batch is a question worth not asking");
  }

  @Test
  void aRefusedRequestIsReportedAsACardDavFailure() throws Exception {
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      return response(401, "");
    });

    CardDavException refused = assertThrows(CardDavException.class,
                                            () -> client.discoverAddressBook(BOOK_URL, ACCOUNT));

    assertTrue(refused.getMessage().contains("401"), "the status belongs in the message: it is what tells creds from outage");
  }

  @Test
  void anUnreachableServerIsReportedAsACardDavFailure() throws Exception {
    when(transport.send(any(HttpRequest.class), any())).thenThrow(new IOException("connection refused"));

    CardDavException unreachable = assertThrows(CardDavException.class,
                                                () -> client.discoverAddressBook(BOOK_URL, ACCOUNT));

    assertNotNull(unreachable.getCause());
  }

  @Test
  void aDocumentDeclaringAnExternalEntityIsRefused() throws Exception {
    // The XML comes from a server the user chose, so it is attacker-influenced. A
    // stock parser would resolve this entity and hand the server /etc/passwd.
    givenAnswers("""
        <?xml version="1.0" encoding="utf-8"?>
        <!DOCTYPE d [<!ENTITY xxe SYSTEM "file:///etc/passwd">]>
        <d:multistatus xmlns:d="DAV:">
          <d:response><d:href>&xxe;</d:href></d:response>
        </d:multistatus>""");

    assertThrows(CardDavException.class, () -> client.listResourceEtags(new AddressBook(BOOK_URL, "C", null), ACCOUNT));
  }

  @Test
  void aCreatedCardAnswersItsStatusAndEtag() throws Exception {
    givenPutAnswer(201, "\"etag-42\"");

    PutResult result = client.putVCard(BOOK_URL + "abc.vcf", "BEGIN:VCARD\nEND:VCARD\n", "*", ACCOUNT);

    assertEquals(201, result.status());
    assertEquals("\"etag-42\"", result.etag(), "the etag travels raw, quotes and all, like PROPFIND answers it");
    HttpRequest request = sent.get(0);
    assertEquals("PUT", request.method());
    // The write's own media type: request()'s application/xml belongs to
    // PROPFIND/REPORT and a server told a card is XML may refuse it.
    assertEquals("text/vcard; charset=utf-8", request.headers().firstValue("Content-Type").orElse(null));
    assertEquals("*", request.headers().firstValue("If-None-Match").orElse(null),
                 "creates-only is enforced by the server, and this header is the whole enforcement");
  }

  @Test
  void aServerThatRenamesTheEntrySaysSoThroughLocation() throws Exception {
    // BlueMind does not keep the URL a card was PUT to: it files the card under
    // a path of its own and answers it in Location. Missing this header is how
    // slice 1 bound rows to entries that did not exist.
    givenPutAnswer(201, "\"e\"", "/dav/addressbooks/alice/default/renamed-by-server.vcf");

    PutResult result = client.putVCard(BOOK_URL + "abc.vcf", "BEGIN:VCARD\nEND:VCARD\n", "*", ACCOUNT);

    assertEquals("https://mail.example.com/dav/addressbooks/alice/default/renamed-by-server.vcf",
                 result.location(),
                 "resolved absolute against the request URL, like discovery's hrefs");
  }

  @Test
  void aServerThatKeepsTheUrlAnswersNoLocation() throws Exception {
    givenPutAnswer(201, "\"e\"", null);

    PutResult result = client.putVCard(BOOK_URL + "abc.vcf", "BEGIN:VCARD\nEND:VCARD\n", "*", ACCOUNT);

    assertNull(result.location(), "no Location means the entry lives where it was PUT, and null says exactly that");
  }

  @Test
  void aServerThatSendsNoEtagAnswersNull() throws Exception {
    // Not every server returns an ETag on PUT. Null is the honest answer: the
    // caller stores it as unknown and the next sync settles the version.
    givenPutAnswer(204, null);

    PutResult result = client.putVCard(BOOK_URL + "abc.vcf", "BEGIN:VCARD\nEND:VCARD\n", "*", ACCOUNT);

    assertEquals(204, result.status());
    assertNull(result.etag());
  }

  @Test
  void aRefusedPreconditionIsAnAnswerNotAnError() throws Exception {
    // 412 under If-None-Match:* means "an entry is already there". That is the
    // server keeping the creates-only promise, and the caller must be able to
    // read it -- an exception here would make refusal indistinguishable from
    // failure.
    givenPutAnswer(412, null);

    PutResult result = client.putVCard(BOOK_URL + "abc.vcf", "BEGIN:VCARD\nEND:VCARD\n", "*", ACCOUNT);

    assertTrue(result.preconditionFailed());
  }

  @Test
  void anyOtherPutStatusIsAnError() throws Exception {
    givenPutAnswer(507, null);

    assertThrows(CardDavException.class,
                 () -> client.putVCard(BOOK_URL + "abc.vcf", "BEGIN:VCARD\nEND:VCARD\n", "*", ACCOUNT));
  }

  @Test
  void noPreconditionHeaderTravelsWhenNoneIsAsked() throws Exception {
    // No caller does this today; pinned so a future one knows an absent
    // precondition means an absent header, not an empty one the server rejects.
    givenPutAnswer(200, null);

    client.putVCard(BOOK_URL + "abc.vcf", "BEGIN:VCARD\nEND:VCARD\n", null, ACCOUNT);

    assertTrue(sent.get(0).headers().firstValue("If-None-Match").isEmpty());
  }

  /**
   * Primes the transport with one PUT answer carrying an optional ETag header,
   * recording the request like {@link #givenAnswers} does.
   *
   * @param status the status to answer
   * @param etag the ETag header value, or null to send none
   * @throws Exception when the mock cannot be primed
   */
  private void givenPutAnswer(int status, String etag) throws Exception {
    givenPutAnswer(status, etag, null);
  }

  /**
   * Primes the transport with one PUT answer carrying optional ETag and
   * Location headers.
   *
   * @param status the status to answer
   * @param etag the ETag header value, or null to send none
   * @param location the Location header value, or null to send none
   * @throws Exception when the mock cannot be primed
   */
  private void givenPutAnswer(int status, String etag, String location) throws Exception {
    Map<String, List<String>> headerMap = new java.util.HashMap<>();
    if (etag != null) {
      headerMap.put("ETag", List.of(etag));
    }
    if (location != null) {
      headerMap.put("Location", List.of(location));
    }
    java.net.http.HttpHeaders headers = java.net.http.HttpHeaders.of(headerMap, (name, value) -> true);
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      HttpResponse<String> response = response(status, "");
      when(response.headers()).thenReturn(headers);
      return response;
    });
  }

  /**
   * Queues the bodies the transport will answer, in order, recording every
   * request that was sent so the test can assert on it.
   *
   * @param bodies the response bodies, in the order they should be returned
   * @throws Exception when the mock cannot be primed
   */
  private void givenAnswers(String... bodies) throws Exception {
    List<String> queue = new ArrayList<>(List.of(bodies));
    when(transport.send(any(HttpRequest.class), any())).thenAnswer(invocation -> {
      sent.add(invocation.getArgument(0));
      String body = queue.isEmpty() ? "<d:multistatus xmlns:d=\"DAV:\"/>" : queue.remove(0);
      return response(207, body);
    });
  }

  /**
   * A canned HTTP response.
   *
   * @param status the status code
   * @param body the body
   * @return the response
   */
  @SuppressWarnings("unchecked")
  private HttpResponse<String> response(int status, String body) {
    HttpResponse<String> response = mock(HttpResponse.class);
    when(response.statusCode()).thenReturn(status);
    when(response.body()).thenReturn(body);
    return response;
  }

  /**
   * A PROPFIND answer describing one address-book collection.
   *
   * @param href the collection href
   * @param displayName its name
   * @param ctag its version, or null to omit the property
   * @return the XML
   */
  private String collectionResponse(String href, String displayName, String ctag) {
    return String.format("""
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav" xmlns:cs="http://calendarserver.org/ns/">
          <d:response>
            <d:href>%s</d:href>
            <d:propstat><d:prop>
              <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
              <d:displayname>%s</d:displayname>
              %s
            </d:prop></d:propstat>
          </d:response>
        </d:multistatus>""", href, displayName, ctag == null ? "" : "<cs:getctag>" + ctag + "</cs:getctag>");
  }

  /**
   * A PROPFIND answer for something that exists but is not an address book.
   *
   * @return the XML
   */
  private String notACollection() {
    return """
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
          </d:response>
        </d:multistatus>""";
  }

  /**
   * A well-known discovery answer naming the current user's principal.
   *
   * @param href the principal href
   * @return the XML
   */
  private String principalResponse(String href) {
    return String.format("""
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">
          <d:response>
            <d:href>/.well-known/carddav</d:href>
            <d:propstat><d:prop><d:current-user-principal><d:href>%s</d:href></d:current-user-principal></d:prop></d:propstat>
          </d:response>
        </d:multistatus>""", href);
  }

  /**
   * A principal answer naming where the address books live.
   *
   * @param href the home-set href
   * @return the XML
   */
  private String homeSetResponse(String href) {
    return String.format("""
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
          <d:response>
            <d:href>/dav/principals/alice/</d:href>
            <d:propstat><d:prop><card:addressbook-home-set><d:href>%s</d:href></card:addressbook-home-set></d:prop></d:propstat>
          </d:response>
        </d:multistatus>""", href);
  }

  /**
   * A Depth:1 listing of the home collection, holding the home itself and one
   * address book — the shape every real server answers.
   *
   * @param href the address book href
   * @param displayName its name
   * @param ctag its version
   * @return the XML
   */
  @Test
  void theBookWithTheMostEntriesIsTheOneSynced() throws Exception {
    // A server publishing several books lists them in an order that means nothing.
    // Taking the first produced a sync of one contact next to a book of hundreds.
    givenAnswers(notACollection(),
                 principalResponse("/dav/principals/alice/"),
                 homeSetResponse("/dav/addressbooks/alice/"),
                 twoBookListResponse("/dav/addressbooks/alice/collected/", "Collected", "/dav/addressbooks/alice/default/", "Contacts"),
                 entriesResponse(1),
                 entriesResponse(400));

    AddressBook book = client.discoverAddressBook(BASE, ACCOUNT);

    assertEquals(BOOK_URL, book.url());
    assertEquals("Contacts", book.displayName());
  }

  @Test
  void theStaffDirectoryIsNotSomebodysContacts() throws Exception {
    // The biggest book on a company server is everyone who works there. Importing
    // it would file every colleague as a personal contact.
    givenAnswers(notACollection(),
                 principalResponse("/dav/principals/alice/"),
                 homeSetResponse("/dav/addressbooks/alice/"),
                 twoBookListResponse("/dav/addressbooks/alice/addressbook:Directory_acme/", "Company directory",
                                     "/dav/addressbooks/alice/default/", "Contacts"),
                 entriesResponse(12));

    AddressBook book = client.discoverAddressBook(BASE, ACCOUNT);

    assertEquals(BOOK_URL, book.url(), "the directory is set aside before sizes are even compared");
  }

  @Test
  void aDirectoryIsUsedWhenItIsAllTheServerHas() throws Exception {
    givenAnswers(notACollection(),
                 principalResponse("/dav/principals/alice/"),
                 homeSetResponse("/dav/addressbooks/alice/"),
                 collectionListResponse("/dav/addressbooks/alice/addressbook:Directory_acme/", "Company directory", "ctag-9"),
                 entriesResponse(3));

    AddressBook book = client.discoverAddressBook(BASE, ACCOUNT);

    assertEquals("Company directory", book.displayName(), "setting it aside must not leave the user with nothing");
  }

  /**
   * A home set publishing two address books.
   *
   * @param firstHref the first book
   * @param firstName its name
   * @param secondHref the second book
   * @param secondName its name
   * @return the multistatus body
   */
  private String twoBookListResponse(String firstHref, String firstName, String secondHref, String secondName) {
    return String.format("""
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav" xmlns:cs="http://calendarserver.org/ns/">
          <d:response>
            <d:href>%s</d:href>
            <d:propstat><d:prop>
              <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
              <d:displayname>%s</d:displayname>
              <cs:getctag>ctag-a</cs:getctag>
            </d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>%s</d:href>
            <d:propstat><d:prop>
              <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
              <d:displayname>%s</d:displayname>
              <cs:getctag>ctag-b</cs:getctag>
            </d:prop></d:propstat>
          </d:response>
        </d:multistatus>""", firstHref, firstName, secondHref, secondName);
  }

  /**
   * A listing of a given number of entries, as the count uses.
   *
   * @param count how many entries the book holds
   * @return the multistatus body
   */
  private String entriesResponse(int count) {
    StringBuilder body = new StringBuilder("""
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:">""");
    for (int i = 0; i < count; i++) {
      body.append(String.format("""
          <d:response>
            <d:href>/dav/addressbooks/alice/default/%s.vcf</d:href>
            <d:propstat><d:prop><d:getetag>"e%s"</d:getetag></d:prop></d:propstat>
          </d:response>""", i, i));
    }
    return body.append("</d:multistatus>").toString();
  }

  private String collectionListResponse(String href, String displayName, String ctag) {
    return String.format("""
        <?xml version="1.0" encoding="utf-8"?>
        <d:multistatus xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav" xmlns:cs="http://calendarserver.org/ns/">
          <d:response>
            <d:href>/dav/addressbooks/alice/</d:href>
            <d:propstat><d:prop><d:resourcetype><d:collection/></d:resourcetype></d:prop></d:propstat>
          </d:response>
          <d:response>
            <d:href>%s</d:href>
            <d:propstat><d:prop>
              <d:resourcetype><d:collection/><card:addressbook/></d:resourcetype>
              <d:displayname>%s</d:displayname>
              <cs:getctag>%s</cs:getctag>
            </d:prop></d:propstat>
          </d:response>
        </d:multistatus>""", href, displayName, ctag);
  }
}
