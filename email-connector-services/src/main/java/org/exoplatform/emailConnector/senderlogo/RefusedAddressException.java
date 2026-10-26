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

import java.net.UnknownHostException;

/**
 * Thrown by {@link SenderLogoAddressGuard#resolveAllowed} for a host resolving to an
 * address the platform must not reach (EXO-90893). An {@link UnknownHostException} so
 * that it travels out of the HTTP client's connection code like a resolution failure,
 * and a type of its own so that it is told apart from one.
 */
public class RefusedAddressException extends UnknownHostException {

  private static final long serialVersionUID = -2364913374123660183L;

  /**
   * Builds the exception; the message never names the host or the address.
   */
  public RefusedAddressException() {
    super("The sender logo points at an address the platform may not reach");
  }
}
