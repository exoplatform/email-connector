/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.model;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The one shape a forward's destination may take, wherever it comes from: a plain
 * {@code local@domain} address, letters, digits and {@code . _ % + -} before the
 * {@code @}, dot-separated labels after it, lower-cased. Nothing that could need
 * quoting, carry a display name, a comment, a route or a second address -- so the
 * address eXo checks against the allowed domains, the one the confirmation code is sent
 * to and the one written into a {@code redirect} are the same string.
 */
public final class ForwardingDestination {

  /** A destination that is not a plain address. */
  public static final String   INVALID     = "emailConnector.forwarding.destination.invalid";

  /** The longest address accepted (RFC 5321 §4.5.3.1.3 path limit, less the brackets). */
  public static final int      MAX_LENGTH  = 254;

  /** A plain address: a simple local part, dot-separated domain labels, lower-case. */
  private static final Pattern ADDRESS     = Pattern.compile("[a-z0-9._%+-]{1,64}@"
      + "((?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)++[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)");

  /**
   * Utility class.
   */
  private ForwardingDestination() {
  }

  /**
   * The destination in its one accepted form.
   *
   * @param address the address as typed
   * @return the address, trimmed and lower-cased
   * @throws IllegalArgumentException {@value #INVALID} for anything but a plain address
   */
  public static String normalize(String address) {
    String value = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
    if (value.isEmpty() || value.length() > MAX_LENGTH || !ADDRESS.matcher(value).matches() || value.startsWith(".")
        || value.contains("..") || value.contains(".@")) {
      throw new IllegalArgumentException(INVALID);
    }
    return value;
  }

  /**
   * The domain of an address in its accepted form.
   *
   * @param address a normalised address
   * @return the part after the {@code @}
   */
  public static String domainOf(String address) {
    return address.substring(address.lastIndexOf('@') + 1);
  }
}
