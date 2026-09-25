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

/**
 * How a user string becomes a Sieve quoted string (RFC 5228 §2.4.2) on a given server.
 * <p>
 * Both strategies escape {@code "} as {@code \"}, which every server handles. They differ
 * on the backslash. RFC 5228 says {@code \\}, and Pigeonhole honours it, but Stalwart
 * v0.11.8 un-escapes the string a second time: {@code C:\\temp} arrives in the reply as
 * {@code C:\<TAB>emp} (V0 spike, row 17). {@link #ENCODED_CHARACTER} writes the backslash
 * as {@code ${unicode:5C}} instead (RFC 5228 §2.4.2.4, the {@code encoded-character}
 * extension), and the dollar sign as {@code ${unicode:24}}, so that a {@code ${…}} the
 * user typed stays text and is never decoded. It is chosen whenever the server
 * advertises {@code encoded-character}; the script then requires that extension when a
 * string actually needed it.
 * <p>
 * <b>Constraint</b>: {@code ${unicode:24}} protects a {@code $} only while the script does
 * not require {@code variables}. Under that extension the decoded {@code ${…}} is expanded
 * as a variable reference afterwards (observed on Pigeonhole 0.5.21), so a typed
 * {@code ${x}} would match the variable's value. eXo's script never requires it, and
 * {@link ExoSieveScript#toScript(SieveStringEncoding, boolean)} refuses to generate one
 * that does.
 */
public enum SieveStringEncoding {

  /** RFC 5228 escaping: {@code \} as {@code \\}, {@code "} as {@code \"}. */
  ESCAPED,

  /**
   * {@code \} as {@code ${unicode:5C}}, {@code $} as {@code ${unicode:24}}, {@code "} as
   * {@code \"}; needs {@code require "encoded-character"}.
   */
  ENCODED_CHARACTER;

  /** The extension {@link #ENCODED_CHARACTER} needs. */
  public static final String EXTENSION = "encoded-character";

  /**
   * The extension under which {@link #ENCODED_CHARACTER} no longer protects a {@code $}:
   * eXo's script never requires it.
   */
  public static final String VARIABLES_EXTENSION = "variables";

  /**
   * The strategy for a server: encoded characters when it advertises them, RFC escaping
   * otherwise.
   *
   * @param capabilities the capabilities the server advertised after TLS
   * @return the strategy
   */
  public static SieveStringEncoding forCapabilities(ManageSieveCapabilities capabilities) {
    return capabilities != null && capabilities.hasExtension(EXTENSION) ? ENCODED_CHARACTER : ESCAPED;
  }

  /**
   * Encodes a value as a quoted string, quotes included.
   *
   * @param value the value
   * @return the quoted string
   */
  public String quote(String value) {
    String text = value.replace("\\", this == ENCODED_CHARACTER ? "\u0000" : "\\\\");
    if (this == ENCODED_CHARACTER) {
      text = text.replace("$", "${unicode:24}").replace("\u0000", "${unicode:5C}");
    }
    return "\"" + text.replace("\"", "\\\"") + "\"";
  }

  /**
   * Whether encoding this value needs the {@code encoded-character} extension.
   *
   * @param value the value
   * @return true under {@link #ENCODED_CHARACTER} when the value holds {@code \} or
   *         {@code $}
   */
  public boolean needsExtension(String value) {
    return this == ENCODED_CHARACTER && value != null && (value.indexOf('\\') >= 0 || value.indexOf('$') >= 0);
  }
}
