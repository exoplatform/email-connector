/*
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
package org.exoplatform.emailConnector.mcp;

import java.net.IDN;
import java.util.Locale;

import org.apache.commons.lang3.StringUtils;

/**
 * How the email tools read a recipient written by an agent, in one place: the
 * tools refuse what these rules refuse, and the argument limits of standing
 * approvals ({@link EmailGrantConstraintEvaluator}) check addresses with the
 * same rules, so the check and the send never read one entry two ways.
 */
final class EmailAddressRules {

  /**
   * Utility class: every member is static.
   */
  private EmailAddressRules() {
  }

  /**
   * Whether an entry is a bare address once trimmed: not blank, and carrying no
   * blank, angle bracket, quote, comma, semicolon or ampersand -- no display
   * name, no empty {@code <>}, no two addresses in one, no group, no character
   * entity.
   *
   * @param entry the entry as given, possibly null
   * @return true for a bare address
   */
  static boolean isBareAddress(String entry) {
    String value = StringUtils.trimToEmpty(entry);
    return !value.isEmpty() && !StringUtils.containsWhitespace(value) && !StringUtils.containsAny(value, '<', '>', '"', ',', ';', '&');
  }

  /**
   * An address as recipients are compared: trimmed and lower-cased.
   *
   * @param address the address, possibly null
   * @return the address, trimmed and lower-cased; empty for a blank one
   */
  static String normalisedAddress(String address) {
    return StringUtils.trimToEmpty(address).toLowerCase(Locale.ROOT);
  }

  /**
   * The domain of a bare address, in the form domains are compared: lower-cased
   * and converted to its ASCII (IDNA) form under the STD3 rules. An entry that
   * is not a bare address, does not hold exactly one {@code @} with text on both
   * sides, or whose domain IDNA refuses (an address literal such as
   * {@code [192.0.2.1]}, an empty label, a trailing dot) has no domain.
   *
   * @param entry the entry as given, possibly null
   * @return the normalised domain, or null when the entry has none
   */
  static String domainOf(String entry) {
    if (!isBareAddress(entry)) {
      return null;
    }
    String address = normalisedAddress(entry);
    int at = address.indexOf('@');
    if (at <= 0 || at != address.lastIndexOf('@') || at == address.length() - 1) {
      return null;
    }
    return normalisedDomain(address.substring(at + 1));
  }

  /**
   * A domain in the form domains are compared, see {@link #domainOf(String)}.
   *
   * @param domain the domain, possibly null
   * @return the normalised domain, or null when IDNA refuses it
   */
  static String normalisedDomain(String domain) {
    String value = StringUtils.trimToEmpty(domain).toLowerCase(Locale.ROOT);
    if (value.isEmpty() || value.endsWith(".") || value.startsWith(".")) {
      return null;
    }
    try {
      String ascii = IDN.toASCII(value, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
      return ascii.isEmpty() || ascii.contains("..") ? null : ascii;
    } catch (IllegalArgumentException e) {
      return null;
    }
  }

}
