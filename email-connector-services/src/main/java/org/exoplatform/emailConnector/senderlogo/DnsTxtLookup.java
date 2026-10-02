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

import java.util.List;

/**
 * Reads the TXT records of a DNS name, for the sender logo's BIMI and DMARC lookups
 * (EXO-90893): the JDK's DNS client in production ({@link JndiDnsTxtLookup}), a table
 * in the tests.
 */
@FunctionalInterface
public interface DnsTxtLookup {

  /**
   * The TXT records of a name, each as one string ({@code SenderLogoUtils#txtValue}).
   *
   * @param name the DNS name
   * @return the records, empty when the name has none or does not exist
   * @throws DnsLookupException when the DNS could not answer (timeout, server failure),
   *           which is not the same as "no record"
   */
  List<String> lookup(String name) throws DnsLookupException;
}
