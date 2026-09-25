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
package org.exoplatform.emailConnector.service.rules.sieve;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * What a ManageSieve server advertised in a capability response (RFC 5804 §1.7).
 * <p>
 * The client keeps only the response it read <b>after</b> {@code STARTTLS}: the one
 * before TLS is unauthenticated and may be a man in the middle's, and servers
 * legitimately advertise fewer SASL mechanisms in clear (Stalwart v0.11.8 offers only
 * {@code OAUTHBEARER} before TLS and {@code PLAIN OAUTHBEARER} after).
 *
 * @param implementation the server's {@code IMPLEMENTATION} string, possibly null
 * @param saslMechanisms the {@code SASL} mechanisms, upper-case
 * @param sieveExtensions the {@code SIEVE} extensions, lower-case
 * @param starttls whether {@code STARTTLS} was advertised
 * @param version the {@code VERSION} string, null when absent (a pre-RFC 5804 server,
 *          which has no {@code CHECKSCRIPT})
 */
public record ManageSieveCapabilities(String implementation,
                                      Set<String> saslMechanisms,
                                      Set<String> sieveExtensions,
                                      boolean starttls,
                                      String version) {

  /**
   * Keeps the sets unmodifiable.
   *
   * @param implementation the server's {@code IMPLEMENTATION} string
   * @param saslMechanisms the {@code SASL} mechanisms
   * @param sieveExtensions the {@code SIEVE} extensions
   * @param starttls whether {@code STARTTLS} was advertised
   * @param version the {@code VERSION} string
   */
  public ManageSieveCapabilities {
    saslMechanisms = saslMechanisms == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(saslMechanisms));
    sieveExtensions = sieveExtensions == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(sieveExtensions));
  }

  /**
   * Builds the capabilities from the data lines of a capability response, each a
   * capability name followed by an optional value.
   *
   * @param lines the lines, each a list of the line's string values
   * @return the parsed capabilities
   */
  static ManageSieveCapabilities fromLines(List<List<String>> lines) {
    String implementation = null;
    Set<String> sasl = new LinkedHashSet<>();
    Set<String> sieve = new LinkedHashSet<>();
    boolean starttls = false;
    String version = null;
    for (List<String> line : lines) {
      if (line.isEmpty()) {
        continue;
      }
      String name = line.get(0).toUpperCase(Locale.ROOT);
      String value = line.size() > 1 ? line.get(1) : null;
      switch (name) {
      case "IMPLEMENTATION" -> implementation = value;
      case "SASL" -> sasl.addAll(split(value, true));
      case "SIEVE" -> sieve.addAll(split(value, false));
      case "STARTTLS" -> starttls = true;
      case "VERSION" -> version = value;
      default -> {
        // NOTIFY, MAXREDIRECTS, OWNER, LANGUAGE and future capabilities are not used.
      }
      }
    }
    return new ManageSieveCapabilities(implementation, sasl, sieve, starttls, version);
  }

  /**
   * Whether the server offers a SASL mechanism.
   *
   * @param mechanism the mechanism name, any case
   * @return true when advertised
   */
  public boolean supportsSasl(String mechanism) {
    return mechanism != null && saslMechanisms.contains(mechanism.toUpperCase(Locale.ROOT));
  }

  /**
   * Whether the server's Sieve interpreter supports an extension.
   *
   * @param extension the extension name, any case, e.g. {@code vacation}
   * @return true when listed in the {@code SIEVE} capability
   */
  public boolean hasExtension(String extension) {
    return extension != null && sieveExtensions.contains(extension.toLowerCase(Locale.ROOT));
  }

  /**
   * Whether {@code CHECKSCRIPT} may be sent: RFC 5804 servers advertise {@code VERSION},
   * the drafts before it had neither.
   *
   * @return true when {@code VERSION} was advertised
   */
  public boolean supportsCheckScript() {
    return version != null;
  }

  /**
   * Splits a space-separated capability value.
   *
   * @param value the value, possibly null
   * @param upper whether to upper-case (SASL) or lower-case (SIEVE) the items
   * @return the items, never null
   */
  private static List<String> split(String value, boolean upper) {
    if (value == null || value.isBlank()) {
      return List.of();
    }
    return Arrays.stream(value.trim().split("\\s+"))
                 .map(item -> upper ? item.toUpperCase(Locale.ROOT) : item.toLowerCase(Locale.ROOT))
                 .toList();
  }
}
