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
package org.exoplatform.emailConnector.exception;

/**
 * The mail server a rules engine talks to could not be used for this request: it could
 * not be reached, its certificate does not name the host eXo connected to, it refused
 * the caller's credentials, it lacks what eXo requires of it (TLS, SASL {@code PLAIN}),
 * or no endpoint is configured. Not an error of the caller's input: the REST layer
 * answers 502 with the code as message, a fixed phrase per code, and the server's own
 * text goes to {@code LOG.debug} only.
 */
public class ServerRuleUnavailableException extends Exception {

  private static final long  serialVersionUID   = 1L;

  /** The server could not be reached, timed out, or broke the protocol. */
  public static final String SERVER_UNREACHABLE = "emailConnector.absence.serverUnreachable";

  /**
   * The server's certificate does not name the host eXo connected to: the administrator
   * sets {@code email.connector.sieve.host} to the name on the certificate.
   */
  public static final String TLS_HOST_NAME      = "emailConnector.absence.tlsHostName";

  /** The server refused the caller's mail credentials, or none could be produced. */
  public static final String AUTHENTICATION     = "emailConnector.absence.authenticationFailed";

  /** The server refused a command: a quota, a script it does not accept. */
  public static final String SERVER_REFUSED     = "emailConnector.absence.serverRefused";

  /** No endpoint is configured for this connector. */
  public static final String NOT_CONFIGURED     = "emailConnector.absence.serverNotConfigured";

  /** The server does not offer what eXo requires: STARTTLS, then SASL PLAIN. */
  public static final String SERVER_UNSUPPORTED = "emailConnector.absence.serverUnsupported";

  /**
   * A failure without an underlying cause.
   *
   * @param code one of the codes of this class, the exception's message
   */
  public ServerRuleUnavailableException(String code) {
    super(code);
  }

  /**
   * A failure caused by the transport.
   *
   * @param code one of the codes of this class, the exception's message
   * @param cause the underlying failure, kept for {@code LOG.debug}
   */
  public ServerRuleUnavailableException(String code, Throwable cause) {
    super(code, cause);
  }
}
