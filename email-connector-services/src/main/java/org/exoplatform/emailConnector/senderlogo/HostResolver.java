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
import java.net.UnknownHostException;

/**
 * Resolves a host name to its addresses, for the sender logo's address guard
 * (EXO-90893): the JDK's resolver in production, a table in the tests.
 */
@FunctionalInterface
public interface HostResolver {

  /**
   * Resolves a host.
   *
   * @param host the host, an IPv6 literal without brackets
   * @return every address the host resolves to
   * @throws UnknownHostException when it resolves to nothing
   */
  InetAddress[] resolve(String host) throws UnknownHostException;
}
