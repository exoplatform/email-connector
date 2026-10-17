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
package org.exoplatform.emailConnector.constant;

/** Why a ManageSieve conversation failed. */
public enum ManageSieveErrorKind {
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
