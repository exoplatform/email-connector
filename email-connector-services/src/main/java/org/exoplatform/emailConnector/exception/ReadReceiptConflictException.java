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
package org.exoplatform.emailConnector.exception;

/**
 * An answer to a read-receipt request that was already answered -- by this user, on
 * another copy of the message, or by another of their mail clients. Not an error of
 * the caller's input and not a failure of the server: the REST layer answers 409 with
 * the code as message, and the reader simply drops its banner.
 */
public class ReadReceiptConflictException extends RuntimeException {

  private static final long  serialVersionUID = 1L;

  /** The request was already answered (sent or ignored). */
  public static final String ALREADY_HANDLED  = "emailConnector.readReceipt.alreadyHandled";

  /**
   * @param code the message code the interface translates
   */
  public ReadReceiptConflictException(String code) {
    super(code);
  }
}
