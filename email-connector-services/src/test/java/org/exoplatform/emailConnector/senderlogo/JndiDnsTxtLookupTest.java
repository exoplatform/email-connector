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

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The DNS client of the sender logo (EXO-90893) asks plain DNS names only: JNDI picks a
 * provider from a name's URL scheme, so anything else is answered empty, unasked.
 */
class JndiDnsTxtLookupTest {

  /**
   * A name with a scheme, a path, white space, upper case or nothing at all is never
   * looked up.
   *
   * @throws Exception never
   */
  @Test
  void onlyPlainDnsNamesAreAsked() throws Exception {
    JndiDnsTxtLookup lookup = new JndiDnsTxtLookup();
    for (String refused : new String[] { null, "", "ldap://evil.example/o=x", "rmi://evil.example/x", "dns:evil.example",
        "default._bimi.brand.example/x", "default._bimi.Brand.example", "a b.example" }) {
      assertEquals(List.of(), lookup.lookup(refused), String.valueOf(refused));
    }
  }
}
