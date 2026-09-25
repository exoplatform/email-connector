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
 * A ManageSieve conversation that did not end the way the caller asked, classified so
 * that the caller can answer the user without reading the server's text: the kind says
 * whether the server was unreachable, refused the credentials, refused a command, or
 * broke the protocol.
 * <p>
 * The message never carries a credential: the client builds it from the command name
 * and the server's own answer, and it never echoes an {@code AUTHENTICATE} argument.
 * It may carry the server's human-readable text, which is meant for {@code LOG.debug}
 * and never for a response body.
 */
public class ManageSieveException extends Exception {

  private static final long serialVersionUID = 4035518375420212764L;

  /** Why the conversation failed. */
  public enum Kind {
    /** The server could not be reached, timed out, or closed the connection. */
    UNAVAILABLE,
    /** The server does not offer {@code STARTTLS}; the client never talks in clear. */
    TLS_REQUIRED,
    /** The server does not offer the SASL mechanism the client needs after TLS. */
    UNSUPPORTED_MECHANISM,
    /** The server refused the credentials. */
    AUTHENTICATION,
    /**
     * There was no login or password to send; the server was not asked, so nothing it
     * could have refused.
     */
    NO_CREDENTIALS,
    /** The server answered {@code NO} to a command. */
    REFUSED,
    /** The server's answer does not follow RFC 5804, or it said {@code BYE}. */
    PROTOCOL
  }

  /** Why the conversation failed. */
  private final Kind   kind;

  /** The server's response code, such as {@code NONEXISTENT} or {@code QUOTA/MAXSIZE}; null when none. */
  private final String responseCode;

  /**
   * A failure without a server response code.
   *
   * @param kind why the conversation failed
   * @param message what failed, without any credential
   */
  public ManageSieveException(Kind kind, String message) {
    this(kind, null, message, null);
  }

  /**
   * A failure caused by an I/O or TLS error.
   *
   * @param kind why the conversation failed
   * @param message what failed, without any credential
   * @param cause the underlying error
   */
  public ManageSieveException(Kind kind, String message, Throwable cause) {
    this(kind, null, message, cause);
  }

  /**
   * A failure the server answered with a response code.
   *
   * @param kind why the conversation failed
   * @param responseCode the server's response code, possibly null
   * @param message what failed, without any credential
   * @param cause the underlying error, possibly null
   */
  public ManageSieveException(Kind kind, String responseCode, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
    this.responseCode = responseCode;
  }

  /**
   * Why the conversation failed.
   *
   * @return the kind, never null
   */
  public Kind getKind() {
    return kind;
  }

  /**
   * The server's response code, upper-case as the server sent it.
   *
   * @return the code, such as {@code NONEXISTENT}, or null when the server sent none
   */
  public String getResponseCode() {
    return responseCode;
  }
}
