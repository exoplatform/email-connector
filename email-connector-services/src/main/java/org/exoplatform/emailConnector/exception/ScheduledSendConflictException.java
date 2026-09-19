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
 * A request on a scheduled mail, or on the draft behind it, that its current state
 * forbids: the draft is locked because it is scheduled
 * ({@code emailConnector.scheduled.locked}), or the mail is being sent or already sent
 * ({@code emailConnector.scheduled.sending}). Not an error of the caller's input, and
 * not a failure of the server: the REST layer answers 409 with the code as message.
 */
public class ScheduledSendConflictException extends RuntimeException {

  private static final long  serialVersionUID = 1L;

  /** The draft is scheduled, and so locked against edits and interactive sends. */
  public static final String LOCKED           = "emailConnector.scheduled.locked";

  /** The mail is being sent, or has been. */
  public static final String SENDING          = "emailConnector.scheduled.sending";

  /**
   * @param code the message code the interface translates
   */
  public ScheduledSendConflictException(String code) {
    super(code);
  }
}
