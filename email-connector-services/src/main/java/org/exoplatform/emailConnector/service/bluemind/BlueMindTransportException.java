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
package org.exoplatform.emailConnector.service.bluemind;

/**
 * A BlueMind core API call that did not succeed, with the kind of failure an engine maps
 * to a code the user can act on. The message names the call, never a credential, a
 * session key or a body.
 */
public class BlueMindTransportException extends Exception {

  private static final long serialVersionUID = 1L;

  /** What went wrong. */
  public enum Kind {
    /** The login was refused, or a session was refused (401). */
    AUTHENTICATION,
    /**
     * The server refused the call for this account: a 403, or a {@code PERMISSION_DENIED}
     * fault.
     */
    REFUSED,
    /**
     * The server has no such resource: a 404, an API this server does not offer, or a
     * {@code ServerFault} whose code is {@code NOT_FOUND}.
     */
    NOT_FOUND,
    /**
     * The server could not be reached, answered a gateway error, or failed on its side
     * (a 500 that is not a not-found or a permission fault).
     */
    UNREACHABLE,
    /** The server answered something this client does not understand. */
    PROTOCOL
  }

  private final Kind kind;

  /**
   * A failure.
   *
   * @param kind what went wrong
   * @param message the call, in words, without any credential
   */
  public BlueMindTransportException(Kind kind, String message) {
    super(message);
    this.kind = kind;
  }

  /**
   * A failure with its cause.
   *
   * @param kind what went wrong
   * @param message the call, in words, without any credential
   * @param cause the cause
   */
  public BlueMindTransportException(Kind kind, String message, Throwable cause) {
    super(message, cause);
    this.kind = kind;
  }

  /**
   * What went wrong.
   *
   * @return the kind
   */
  public Kind getKind() {
    return kind;
  }
}
