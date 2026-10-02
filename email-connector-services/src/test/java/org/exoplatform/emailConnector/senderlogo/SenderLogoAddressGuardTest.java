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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.URI;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * The sender logo's address guard (EXO-90893): the URL shapes it lets through, and the
 * addresses it refuses to connect to.
 */
class SenderLogoAddressGuardTest {

  /**
   * Only https on port 443 to a named host, without credentials, white space or an
   * oversized URL, may be requested in production.
   *
   * @throws Exception never
   */
  @Test
  void onlyPlainHttpsMayBeRequested() throws Exception {
    SenderLogoAddressGuard guard = new SenderLogoAddressGuard();
    assertTrue(guard.isAllowedTarget(new URI("https://brand.example/logo.svg")));
    assertTrue(guard.isAllowedTarget(new URI("HTTPS://brand.example:443/favicon.ico")));
    for (String refused : new String[] { "http://brand.example/logo.svg", "https://brand.example:8443/logo.svg",
        "https://user:pw@brand.example/logo.svg", "ftp://brand.example/logo.svg", "file:///etc/passwd", "/logo.svg",
        "https:///logo.svg", "https://brand.example/" + "a".repeat(SenderLogoAddressGuard.MAX_URL_LENGTH) }) {
      assertFalse(guard.isAllowedTarget(new URI(refused)), refused);
    }
    assertFalse(guard.isAllowedTarget(null));
  }

  /**
   * Every internal range is refused, in IPv4 and IPv6, including an IPv4 address
   * carried inside an IPv6 one; public addresses are not.
   *
   * @throws Exception never
   */
  @Test
  void internalAddressesAreRefused() throws Exception {
    for (String blocked : new String[] { "127.0.0.1", "10.1.2.3", "172.16.0.1", "172.31.255.255", "192.168.1.1", "169.254.169.254",
        "100.64.0.1", "0.0.0.0", "224.0.0.1", "255.255.255.255", "198.18.0.1", "192.0.0.8", "::1", "::", "fe80::1", "fc00::1",
        "fd12::1", "fec0::1", "ff02::1", "::ffff:127.0.0.1", "::ffff:169.254.169.254", "64:ff9b::a9fe:a9fe", "2002:a9fe:a9fe::1",
        "::10.0.0.1" }) {
      assertTrue(SenderLogoAddressGuard.isBlocked(InetAddress.getByName(blocked)), blocked);
    }
    for (String allowed : new String[] { "93.184.216.34", "8.8.8.8", "172.32.0.1", "100.128.0.1", "2606:2800:220:1::1",
        "::ffff:8.8.8.8" }) {
      assertFalse(SenderLogoAddressGuard.isBlocked(InetAddress.getByName(allowed)), allowed);
    }
  }

  /**
   * A host is refused when ANY of its addresses is internal; a public one resolves;
   * a host resolving to nothing is unknown.
   *
   * @throws Exception never
   */
  @Test
  void aHostWithOneInternalAddressIsRefused() throws Exception {
    InetAddress pub = InetAddress.getByName("93.184.216.34");
    Map<String, InetAddress[]> dns = Map.of("public.example",
                                            new InetAddress[] { pub },
                                            "mixed.example",
                                            new InetAddress[] { pub, InetAddress.getByName("10.0.0.5") },
                                            "empty.example",
                                            new InetAddress[0]);
    SenderLogoAddressGuard guard = new SenderLogoAddressGuard(Set.of("https"), Set.of(443), dns::get, Set.of());
    assertArrayEquals(new InetAddress[] { pub }, guard.resolveAllowed("public.example"));
    assertThrows(RefusedAddressException.class, () -> guard.resolveAllowed("mixed.example"));
    assertThrows(java.net.UnknownHostException.class, () -> guard.resolveAllowed("empty.example"));
    assertThrows(java.net.UnknownHostException.class, () -> guard.resolveAllowed(" "));

    SenderLogoAddressGuard exempting = new SenderLogoAddressGuard(Set.of("http"), Set.of(80), dns::get, Set.of("mixed.example"));
    assertArrayEquals(dns.get("mixed.example"), exempting.resolveAllowed("mixed.example"), "the tests' exemption is per name");
    SenderLogoAddressGuard loopback = new SenderLogoAddressGuard(Set.of("http"),
                                                                 Set.of(80),
                                                                 host -> new InetAddress[] { InetAddress.getLoopbackAddress() },
                                                                 Set.of("mixed.example"));
    assertThrows(RefusedAddressException.class, () -> loopback.resolveAllowed("other.example"));
  }
}
