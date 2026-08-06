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

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * CardDAV over the JDK's own HTTP client — no new transport dependency, and TLS
 * trust comes from the platform truststore exactly as the IMAP connection's does,
 * so a self-signed server that mail already trusts is trusted here too.
 * <p>
 * The XML is parsed with external entities and doctypes disabled. The payload
 * comes from whatever server the user pointed us at, so it is attacker-influenced
 * input; a stock parser would happily read files off this machine on its behalf.
 */
@Component
public class HttpCardDavClient implements CardDavClient {

  private static final Log      LOG                = ExoLogger.getLogger(HttpCardDavClient.class);

  private static final String   DAV_NS             = "DAV:";

  private static final String   CARDDAV_NS         = "urn:ietf:params:xml:ns:carddav";

  /** getctag is a CalendarServer extension, and its own namespace, not DAV's. */
  private static final String   CALENDARSERVER_NS  = "http://calendarserver.org/ns/";

  private static final Duration CONNECT_TIMEOUT    = Duration.ofSeconds(15);

  private static final Duration REQUEST_TIMEOUT    = Duration.ofSeconds(30);

  private static final String   PROPFIND_PRINCIPAL = """
      <?xml version="1.0" encoding="utf-8"?>
      <d:propfind xmlns:d="DAV:"><d:prop><d:current-user-principal/></d:prop></d:propfind>""";

  private static final String   PROPFIND_HOME      = """
      <?xml version="1.0" encoding="utf-8"?>
      <d:propfind xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
        <d:prop><card:addressbook-home-set/></d:prop>
      </d:propfind>""";

  private static final String   PROPFIND_COLLECTION = """
      <?xml version="1.0" encoding="utf-8"?>
      <d:propfind xmlns:d="DAV:" xmlns:cs="http://calendarserver.org/ns/">
        <d:prop><d:resourcetype/><d:displayname/><cs:getctag/></d:prop>
      </d:propfind>""";

  private static final String   PROPFIND_ETAGS     = """
      <?xml version="1.0" encoding="utf-8"?>
      <d:propfind xmlns:d="DAV:"><d:prop><d:getetag/></d:prop></d:propfind>""";

  private final HttpClient      httpClient;

  /**
   * The client Spring builds. Redirects are followed because well-known
   * discovery is defined as a redirect to the real endpoint.
   */
  public HttpCardDavClient() {
    this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).followRedirects(HttpClient.Redirect.NORMAL).build());
  }

  /**
   * The seam the tests use: a client whose transport is handed in, so the
   * protocol can be exercised against canned answers without a server.
   *
   * @param httpClient the transport to send on
   */
  HttpCardDavClient(HttpClient httpClient) {
    this.httpClient = httpClient;
  }

  @Override
  public AddressBook discoverAddressBook(String baseUrl, String username, String password) {
    if (StringUtils.isBlank(baseUrl)) {
      throw new CardDavException("No CardDAV URL is configured");
    }
    String configured = baseUrl.trim();
    // The configured URL may already BE the collection — some servers publish it
    // that way, and an administrator who pasted it should not need discovery to
    // work. Asked first because it is one request and answers definitively.
    //
    // Asked with the URL EXACTLY as configured: a WebDAV collection's trailing
    // slash is part of its identity, and trimming it here made every later request
    // address something the server does not consider a collection.
    AddressBook direct = readCollection(configured, username, password);
    if (direct != null) {
      return direct;
    }
    // Discovery, on the other hand, appends a path, so here the trailing slash
    // would only produce a doubled one.
    String base = StringUtils.removeEnd(configured, "/");
    String principal = discoverPrincipal(base, username, password);
    String home = discoverHomeSet(resolve(base, principal), username, password);
    AddressBook found = findFirstAddressBook(resolve(base, home), username, password);
    if (found == null) {
      throw new CardDavException("The server exposes no address book under " + home);
    }
    return found;
  }

  @Override
  public String getCtag(AddressBook addressBook, String username, String password) {
    Element response = firstResponse(propfind(addressBook.url(), PROPFIND_COLLECTION, "0", username, password));
    return response == null ? null : textOf(response, CALENDARSERVER_NS, "getctag");
  }

  @Override
  public Map<String, String> listResourceEtags(AddressBook addressBook, String username, String password) {
    Element multistatus = propfind(addressBook.url(), PROPFIND_ETAGS, "1", username, password);
    Map<String, String> etags = new LinkedHashMap<>();
    for (Element response : childElements(multistatus, DAV_NS, "response")) {
      String href = textOf(response, DAV_NS, "href");
      String etag = textOf(response, DAV_NS, "getetag");
      // The collection itself comes back in a Depth:1 listing and carries no etag;
      // so does any other member that is not a vCard. Both are skipped by the same
      // rule rather than by guessing from the path.
      if (StringUtils.isNotBlank(href) && StringUtils.isNotBlank(etag)) {
        etags.put(href, etag);
      }
    }
    return etags;
  }

  @Override
  public List<ContactResource> multiget(AddressBook addressBook, List<String> hrefs, String username, String password) {
    if (hrefs == null || hrefs.isEmpty()) {
      return List.of();
    }
    StringBuilder body = new StringBuilder("""
        <?xml version="1.0" encoding="utf-8"?>
        <card:addressbook-multiget xmlns:d="DAV:" xmlns:card="urn:ietf:params:xml:ns:carddav">
          <d:prop><d:getetag/><card:address-data/></d:prop>
        """);
    hrefs.forEach(href -> body.append("  <d:href>").append(escape(href)).append("</d:href>\n"));
    body.append("</card:addressbook-multiget>");

    Element multistatus = parse(send(request(addressBook.url(), "REPORT", body.toString(), username, password).header("Depth",
                                                                                                                     "1")
                                                                                                              .build()),
                                addressBook.url());
    List<ContactResource> resources = new ArrayList<>();
    for (Element response : childElements(multistatus, DAV_NS, "response")) {
      String href = textOf(response, DAV_NS, "href");
      String vcard = textOf(response, CARDDAV_NS, "address-data");
      if (StringUtils.isNotBlank(href) && StringUtils.isNotBlank(vcard)) {
        resources.add(new ContactResource(href, textOf(response, DAV_NS, "getetag"), vcard));
      }
    }
    return resources;
  }

  /**
   * Reads a URL as if it were an address-book collection, answering null when it
   * is not one — used to accept a configured collection URL directly.
   *
   * @param url the URL to test
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the address book, or null when this URL is not one
   */
  private AddressBook readCollection(String url, String username, String password) {
    try {
      Element response = firstResponse(propfind(url, PROPFIND_COLLECTION, "0", username, password));
      if (response == null || !isAddressBook(response)) {
        return null;
      }
      return new AddressBook(url, textOf(response, DAV_NS, "displayname"), textOf(response, CALENDARSERVER_NS, "getctag"));
    } catch (CardDavException e) {
      // Not a collection, or not readable: discovery is the next thing to try, and
      // this attempt failing is the normal path for a server base URL.
      LOG.debug("CardDAV: {} is not directly an address book, falling back to discovery", url, e);
      return null;
    }
  }

  /**
   * The well-known discovery step: asks who the authenticated user is.
   *
   * @param base the server base URL
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the principal's path
   */
  private String discoverPrincipal(String base, String username, String password) {
    Element response = firstResponse(propfind(base + "/.well-known/carddav", PROPFIND_PRINCIPAL, "0", username, password));
    String principal = response == null ? null : hrefWithin(response, DAV_NS, "current-user-principal");
    if (StringUtils.isBlank(principal)) {
      throw new CardDavException("The server did not say who the current user is");
    }
    return principal;
  }

  /**
   * Asks the principal where its address books live.
   *
   * @param principalUrl the absolute principal URL
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the address-book home path
   */
  private String discoverHomeSet(String principalUrl, String username, String password) {
    Element response = firstResponse(propfind(principalUrl, PROPFIND_HOME, "0", username, password));
    String home = response == null ? null : hrefWithin(response, CARDDAV_NS, "addressbook-home-set");
    if (StringUtils.isBlank(home)) {
      throw new CardDavException("The server did not say where the address books are");
    }
    return home;
  }

  /**
   * Picks the first real address book in the home collection. One address book
   * per user in this version: several is rare, and choosing between them is a
   * product decision nobody has made yet.
   *
   * @param homeUrl the absolute home URL
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the address book, or null when the home holds none
   */
  private AddressBook findFirstAddressBook(String homeUrl, String username, String password) {
    Element multistatus = propfind(homeUrl, PROPFIND_COLLECTION, "1", username, password);
    for (Element response : childElements(multistatus, DAV_NS, "response")) {
      if (isAddressBook(response)) {
        String href = textOf(response, DAV_NS, "href");
        return new AddressBook(resolve(homeUrl, href),
                               textOf(response, DAV_NS, "displayname"),
                               textOf(response, CALENDARSERVER_NS, "getctag"));
      }
    }
    return null;
  }

  /**
   * Whether a multistatus response describes an address-book collection.
   *
   * @param response one response element
   * @return true when its resourcetype says address book
   */
  private boolean isAddressBook(Element response) {
    return !descendants(response, CARDDAV_NS, "addressbook").isEmpty();
  }

  /**
   * Sends a PROPFIND and parses the multistatus it answers.
   *
   * @param url the target URL
   * @param body the request body
   * @param depth the Depth header value
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the multistatus element
   */
  private Element propfind(String url, String body, String depth, String username, String password) {
    return parse(send(request(url, "PROPFIND", body, username, password).header("Depth", depth).build()), url);
  }

  /**
   * Builds a request with the headers every CardDAV call needs.
   *
   * @param url the target URL
   * @param method PROPFIND or REPORT
   * @param body the XML body
   * @param username the account to authenticate as
   * @param password that account's password
   * @return the builder, so the caller can add its Depth
   */
  private HttpRequest.Builder request(String url, String method, String body, String username, String password) {
    return HttpRequest.newBuilder(uri(url))
                      .timeout(REQUEST_TIMEOUT)
                      .header("Content-Type", "application/xml; charset=utf-8")
                      .header("Authorization", basicAuth(username, password))
                      .method(method, BodyPublishers.ofString(body, StandardCharsets.UTF_8));
  }

  /**
   * Sends a request and returns its body, turning every unhappy answer into the
   * one exception the sync knows how to handle.
   *
   * @param request the request to send
   * @return the response body
   */
  private String send(HttpRequest request) {
    try {
      HttpResponse<String> response = httpClient.send(request, BodyHandlers.ofString(StandardCharsets.UTF_8));
      int status = response.statusCode();
      // 207 Multi-Status is the normal answer; 200 is tolerated because some
      // servers answer it for a single-resource PROPFIND.
      if (status != 207 && status != 200) {
        throw new CardDavException(String.format("The address book server answered %s for %s %s",
                                                 status,
                                                 request.method(),
                                                 request.uri()));
      }
      return response.body();
    } catch (IOException e) {
      throw new CardDavException("The address book server could not be reached at " + request.uri(), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new CardDavException("Interrupted while talking to " + request.uri(), e);
    }
  }

  /**
   * Parses a multistatus document.
   * <p>
   * Doctypes and external entities are disabled: this XML comes from a server the
   * user chose, and a stock parser would fetch whatever that server's entities
   * pointed at, including files on this machine.
   *
   * @param xml the response body
   * @param url the URL it came from, for the error message
   * @return the document's root element
   */
  private Element parse(String xml, String url) {
    try {
      DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
      factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
      factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
      factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
      factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
      factory.setXIncludeAware(false);
      factory.setExpandEntityReferences(false);
      factory.setNamespaceAware(true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      return builder.parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    } catch (ParserConfigurationException | SAXException | IOException e) {
      throw new CardDavException("The address book server answered something that is not CardDAV XML, from " + url, e);
    }
  }

  /**
   * The first response element of a multistatus, or null when there is none.
   *
   * @param multistatus the parsed document root
   * @return the first response, or null
   */
  private Element firstResponse(Element multistatus) {
    List<Element> responses = childElements(multistatus, DAV_NS, "response");
    return responses.isEmpty() ? null : responses.get(0);
  }

  /**
   * The text of the first descendant with this name, trimmed, or null.
   *
   * @param parent the element to search under
   * @param namespace the element namespace
   * @param name the local name
   * @return the text, or null when absent or empty
   */
  private String textOf(Element parent, String namespace, String name) {
    List<Element> found = descendants(parent, namespace, name);
    return found.isEmpty() ? null : StringUtils.trimToNull(found.get(0).getTextContent());
  }

  /**
   * The href nested inside a named element — the shape both discovery steps
   * answer in.
   *
   * @param parent the element to search under
   * @param namespace the wrapper's namespace
   * @param name the wrapper's local name
   * @return the href, or null
   */
  private String hrefWithin(Element parent, String namespace, String name) {
    List<Element> wrappers = descendants(parent, namespace, name);
    return wrappers.isEmpty() ? null : textOf(wrappers.get(0), DAV_NS, "href");
  }

  /**
   * Every descendant element with this qualified name.
   *
   * @param parent the element to search under
   * @param namespace the element namespace
   * @param name the local name
   * @return the matches, in document order
   */
  private List<Element> descendants(Element parent, String namespace, String name) {
    List<Element> found = new ArrayList<>();
    NodeList nodes = parent.getElementsByTagNameNS(namespace, name);
    for (int i = 0; i < nodes.getLength(); i++) {
      found.add((Element) nodes.item(i));
    }
    return found;
  }

  /**
   * The direct children with this qualified name — used for response elements,
   * which must not be gathered from nested documents.
   *
   * @param parent the element whose children to read
   * @param namespace the element namespace
   * @param name the local name
   * @return the matching children
   */
  private List<Element> childElements(Element parent, String namespace, String name) {
    List<Element> found = new ArrayList<>();
    NodeList children = parent.getChildNodes();
    for (int i = 0; i < children.getLength(); i++) {
      Node child = children.item(i);
      if (child.getNodeType() == Node.ELEMENT_NODE && namespace.equals(child.getNamespaceURI())
          && name.equals(child.getLocalName())) {
        found.add((Element) child);
      }
    }
    return found;
  }

  /**
   * Resolves a server-relative href against a URL already known to be absolute.
   * Servers answer paths, not URLs, and every later request needs an absolute
   * one.
   *
   * @param absoluteUrl a URL known to be absolute
   * @param href the href the server answered
   * @return the absolute form of the href
   */
  private String resolve(String absoluteUrl, String href) {
    if (StringUtils.isBlank(href)) {
      return absoluteUrl;
    }
    if (StringUtils.startsWithIgnoreCase(href, "http")) {
      return href;
    }
    return uri(absoluteUrl).resolve(href).toString();
  }

  /**
   * Parses a URL, refusing an unusable one in the terms the caller understands.
   *
   * @param url the URL to parse
   * @return the URI
   */
  private URI uri(String url) {
    try {
      return new URI(url);
    } catch (URISyntaxException e) {
      throw new CardDavException("Not a usable CardDAV URL: " + url, e);
    }
  }

  /**
   * The Basic credentials header. Sent on every request rather than waiting to be
   * challenged, which is what CardDAV servers expect and saves a round trip.
   *
   * @param username the account
   * @param password its password
   * @return the header value
   */
  private String basicAuth(String username, String password) {
    String pair = StringUtils.defaultString(username) + ":" + StringUtils.defaultString(password);
    return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Escapes text going into a request body. Hrefs come from the server, but they
   * travel back out in the multiget, and a path with an ampersand would otherwise
   * produce XML the server rejects.
   *
   * @param text the text to escape
   * @return the escaped text
   */
  private String escape(String text) {
    return StringUtils.defaultString(text)
                      .replace("&", "&amp;")
                      .replace("<", "&lt;")
                      .replace(">", "&gt;");
  }
}
