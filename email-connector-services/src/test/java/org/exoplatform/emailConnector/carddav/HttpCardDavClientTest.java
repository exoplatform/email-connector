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

/**
 * The protocol, exercised against canned answers. No network anywhere: the
 * transport is a mock, which is the only reason these can assert what the client
 * SENDS as well as what it makes of what comes back.
 */
public class HttpCardDavClientTest {

  private static final String  BASE     = "https://mail.example.com";

  private static final String  BOOK_URL = "https://mail.example.com/dav/addressbooks/alice/default/";

  private HttpClient           transport;

  private HttpCardDavClient    client;

  private List<HttpRequest>    sent;

  @BeforeEach
  void setUp() {
    transport = mock(HttpClient.class);
    client = new HttpCardDavClient(transport);
    sent = new ArrayList<>();
  }

  @Test
  void aConfiguredCollectionUrlIsUsedWithoutDiscovery() throws Exception {
    // An administrator who pasted the collection URL should not depend on
    // well-known discovery working on their server.
    givenAnswers(collectionResponse(BOOK_URL, "Alice's contacts", "ctag-1"));

    AddressBook book = client.discoverAddressBook(BOOK_URL, "alice", "secret");

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

    AddressBook book = client.discoverAddressBook(BASE, "alice", "secret");

    assertEquals(BOOK_URL, book.url(), "the href the server answered is resolved to an absolute URL");
    assertEquals("ctag-7", book.ctag());
    assertTrue(sent.get(1).uri().toString().endsWith("/.well-known/carddav"),
               "discovery starts at the well-known path once the direct read failed");
  }

  @Test
  void everyRequestCarriesBasicCredentials() throws Exception {
    givenAnswers(collectionResponse(BOOK_URL, "Contacts", "ctag-1"));

    client.discoverAddressBook(BOOK_URL, "alice", "secret");

    String authorization = sent.get(0).headers().firstValue("Authorization").orElse(null);
    // Sent unprompted rather than after a challenge: it is what CardDAV servers
    // expect, and it saves a round trip on every single request.
    assertEquals("Basic YWxpY2U6c2VjcmV0", authorization);
  }

  @Test
  void aServerWithoutCtagSupportSaysSoRatherThanFailing() throws Exception {
    givenAnswers(collectionResponse(BOOK_URL, "Contacts", null));

    String ctag = client.getCtag(new AddressBook(BOOK_URL, "Contacts", "old"), "alice", "secret");

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

    Map<String, String> etags = client.listResourceEtags(new AddressBook(BOOK_URL, "Contacts", null), "alice", "secret");

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
                                                              "/dav/addressbooks/alice/default/bob.vcf"),
                                                      "alice",
                                                      "secret");

    assertEquals(1, sent.size(), "two entries, one request — that is the point of multiget");
    assertEquals("REPORT", sent.get(0).method());
    assertEquals(1, resources.size(), "an href the server chose not to return is simply absent");
    assertEquals("\"v1\"", resources.get(0).etag());
    assertTrue(resources.get(0).vcard().contains("FN:Jane Doe"));
  }

  @Test
  void multigetOfNothingDoesNotTouchTheNetwork() {
    List<ContactResource> resources = client.multiget(new AddressBook(BOOK_URL, "Contacts", null), List.of(), "alice", "secret");

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
                                            () -> client.discoverAddressBook(BOOK_URL, "alice", "wrong"));

    assertTrue(refused.getMessage().contains("401"), "the status belongs in the message: it is what tells creds from outage");
  }

  @Test
  void anUnreachableServerIsReportedAsACardDavFailure() throws Exception {
    when(transport.send(any(HttpRequest.class), any())).thenThrow(new IOException("connection refused"));

    CardDavException unreachable = assertThrows(CardDavException.class,
                                                () -> client.discoverAddressBook(BOOK_URL, "alice", "secret"));

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

    assertThrows(CardDavException.class, () -> client.listResourceEtags(new AddressBook(BOOK_URL, "C", null), "a", "b"));
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
