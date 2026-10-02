/**
 * Copyright (C) 2026 eXo Platform SAS
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
package org.exoplatform.emailConnector.senderlogo;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

import org.apache.commons.lang3.StringUtils;

/**
 * Decides which URLs the sender logo fetch (EXO-90893) may read, and which addresses it
 * may connect to.
 * <p>
 * <b>Why.</b> The URL of a BIMI logo is whatever the sender's DNS says, and the icon's
 * host is whatever domain a mail claims to come from: unguarded, a mail would make the
 * server a client of its own network -- an administration console on loopback, a
 * database's HTTP port, the cloud metadata endpoint.
 * <p>
 * <b>What it decides.</b> The URL's shape -- https only, on port 443, no credentials --
 * and the addresses its host resolves to: loopback, link-local (the metadata address
 * 169.254.169.254 among them), private, carrier-grade NAT, multicast, broadcast and
 * reserved, unspecified, IPv6 unique-local and site-local, and each of those spelled as
 * an IPv4 address inside an IPv6 one (mapped, compatible, NAT64, 6to4) are refused. The
 * rules are agenda's {@code CalendarAddressGuard#isBlocked}, copied rather than
 * depended on: email-connector does not depend on agenda.
 * <p>
 * <b>Where the address is checked.</b> {@link #resolveAllowed} is the resolver of the
 * HTTP client's connection manager, so it judges the very addresses the client
 * connects to, for every connection, every redirect hop included: a name that resolves
 * to a public address once and to a private one at the next lookup (DNS rebinding) is
 * refused at the connection, not trusted from an earlier answer.
 */
public class SenderLogoAddressGuard {

  /** Longest URL accepted. */
  public static final int        MAX_URL_LENGTH = 2048;

  private final Set<String>      allowedSchemes;

  private final Set<Integer>     allowedPorts;

  private final HostResolver     resolver;

  private final Set<String>      exemptHosts;

  /**
   * The production guard: https on port 443, the JDK's resolver, no exemption.
   */
  public SenderLogoAddressGuard() {
    this(Set.of("https"), Set.of(443), InetAddress::getAllByName, Set.of());
  }

  /**
   * The seam of the tests: a stub server on loopback speaks plain http on a port of its
   * own and stands for the public internet under the host names exempted here, while
   * any other name resolving to that same, reachable, stub is refused -- which is what
   * shows the refusal happening on the connection's own lookup.
   *
   * @param allowedSchemes the schemes a URL may use
   * @param allowedPorts the ports a URL may reach, implicit default ports included
   * @param resolver name resolution
   * @param exemptHosts host names whose addresses are read as public; empty in
   *          production
   */
  SenderLogoAddressGuard(Set<String> allowedSchemes,
                         Set<Integer> allowedPorts,
                         HostResolver resolver,
                         Set<String> exemptHosts) {
    this.allowedSchemes = Set.copyOf(allowedSchemes);
    this.allowedPorts = Set.copyOf(allowedPorts);
    this.resolver = resolver;
    this.exemptHosts = Set.copyOf(exemptHosts);
  }

  /**
   * Checks the shape of a URL about to be read -- the first one or a redirect's target:
   * absolute, an allowed scheme, no credentials, a host, an allowed port, not too long,
   * no white space or control character. Nothing is resolved here.
   *
   * @param uri the URL
   * @return true when it may be requested
   */
  public boolean isAllowedTarget(URI uri) {
    if (uri == null || !uri.isAbsolute() || uri.isOpaque()) {
      return false;
    }
    String text = uri.toString();
    if (text.length() > MAX_URL_LENGTH || containsUnsafeCharacter(text)) {
      return false;
    }
    String scheme = StringUtils.lowerCase(uri.getScheme(), Locale.ROOT);
    if (!allowedSchemes.contains(scheme)) {
      return false;
    }
    if (uri.getRawUserInfo() != null || StringUtils.contains(uri.getRawAuthority(), '@') || StringUtils.isBlank(uri.getHost())) {
      return false;
    }
    int port = uri.getPort() >= 0 ? uri.getPort() : ("http".equals(scheme) ? 80 : 443);
    return allowedPorts.contains(port);
  }

  /**
   * Resolves a host and refuses it when ANY of its addresses is one the platform must
   * not reach: a name answering one public and one loopback address is a name reaching
   * loopback. This is the HTTP client's resolver: its answer is what the client
   * connects to.
   *
   * @param host the host, brackets of an IPv6 literal allowed
   * @return the addresses, every one allowed
   * @throws RefusedAddressException when an address is refused
   * @throws UnknownHostException when the host resolves to nothing
   */
  public InetAddress[] resolveAllowed(String host) throws UnknownHostException {
    String bare = StringUtils.removeEnd(StringUtils.removeStart(StringUtils.trim(host), "["), "]");
    if (StringUtils.isBlank(bare)) {
      throw new UnknownHostException("No host");
    }
    InetAddress[] addresses = resolver.resolve(bare);
    if (addresses == null || addresses.length == 0) {
      throw new UnknownHostException("The host resolves to nothing");
    }
    if (!exemptHosts.contains(bare)) {
      for (InetAddress address : addresses) {
        if (isBlocked(address)) {
          throw new RefusedAddressException();
        }
      }
    }
    return addresses;
  }

  /**
   * Whether one address is outside what a logo URL may point at.
   * <p>
   * The JDK predicates cover the any-local, loopback, link-local, site-local (RFC 1918
   * in IPv4, fec0::/10 in IPv6) and multicast addresses; the byte inspection covers the
   * rest, and the IPv4 address an IPv6 address may carry, which is how
   * {@code ::ffff:127.0.0.1} or {@code 64:ff9b::a9fe:a9fe} would otherwise walk past a
   * loopback or metadata check.
   *
   * @param address one resolved address
   * @return true when the platform must not reach it
   */
  public static boolean isBlocked(InetAddress address) {
    if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
        || address.isSiteLocalAddress() || address.isMulticastAddress()) {
      return true;
    }
    byte[] bytes = address.getAddress();
    if (bytes.length == 4) {
      return isBlockedIpv4(bytes);
    }
    if (bytes.length != 16) {
      return true;
    }
    if ((bytes[0] & 0xFE) == 0xFC) {
      // fc00::/7, unique local addresses, which isSiteLocalAddress does not know
      return true;
    }
    byte[] embedded = embeddedIpv4(bytes);
    return embedded != null && isBlockedIpv4(embedded);
  }

  /**
   * Whether four bytes name an IPv4 address the platform must not reach.
   *
   * @param bytes an IPv4 address
   * @return true when refused
   */
  private static boolean isBlockedIpv4(byte[] bytes) {
    int first = bytes[0] & 0xFF;
    int second = bytes[1] & 0xFF;
    return first == 0                                        // 0.0.0.0/8, "this network"
        || first == 10                                       // RFC 1918
        || first == 127                                      // loopback
        || first >= 224                                      // multicast, reserved, broadcast
        || (first == 100 && second >= 64 && second <= 127)   // RFC 6598 carrier-grade NAT
        || (first == 169 && second == 254)                   // link-local, cloud metadata
        || (first == 172 && second >= 16 && second <= 31)    // RFC 1918
        || (first == 192 && second == 168)                   // RFC 1918
        || (first == 192 && second == 0 && (bytes[2] & 0xFF) == 0) // IETF protocol assignments
        || (first == 198 && (second == 18 || second == 19)); // benchmarking
  }

  /**
   * The IPv4 address carried by an IPv6 one, when it carries one: IPv4-mapped
   * {@code ::ffff:a.b.c.d}, IPv4-compatible {@code ::a.b.c.d}, NAT64
   * {@code 64:ff9b::a.b.c.d} and 6to4 {@code 2002:aabb:ccdd::}.
   *
   * @param bytes an IPv6 address
   * @return the four bytes of the IPv4 address, or null when none
   */
  private static byte[] embeddedIpv4(byte[] bytes) {
    if ((bytes[0] & 0xFF) == 0x20 && (bytes[1] & 0xFF) == 0x02) {
      return new byte[] { bytes[2], bytes[3], bytes[4], bytes[5] };
    }
    boolean nat64 = bytes[0] == 0 && (bytes[1] & 0xFF) == 0x64 && (bytes[2] & 0xFF) == 0xFF && (bytes[3] & 0xFF) == 0x9B;
    for (int i = nat64 ? 4 : 0; i < 10; i++) {
      if (bytes[i] != 0) {
        return null; // NOSONAR null is "carries none"
      }
    }
    boolean mapped = (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    boolean zeroes = bytes[10] == 0 && bytes[11] == 0;
    if ((nat64 && !zeroes) || (!mapped && !zeroes)) {
      return null; // NOSONAR as above
    }
    return new byte[] { bytes[12], bytes[13], bytes[14], bytes[15] };
  }

  /**
   * Whether a URL carries white space or a control character, which no usable URL does
   * and which a request line must never receive.
   *
   * @param url the URL
   * @return true when it does
   */
  private static boolean containsUnsafeCharacter(String url) {
    for (int i = 0; i < url.length(); i++) {
      char c = url.charAt(i);
      if (Character.isWhitespace(c) || Character.isISOControl(c)) {
        return true;
      }
    }
    return false;
  }
}
