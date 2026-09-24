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
 * A mail cannot be sent in the owner's name although the request is well formed
 * (EXO-90583): the shape is unknown, no shared mailbox is named, the administrator
 * switched the feature off or does not declare that shape for the connector, or the
 * owner's mail server refused a mail in her name since she last gave her consent. The
 * message is the code the composer translates; the REST layer answers <b>400</b>. Nothing
 * was sent.
 */
public class SendModeUnavailableException extends IllegalArgumentException {

  private static final long  serialVersionUID = 1L;

  /** The owner's mail server refused a mail in her name (now, or since the consent). */
  public static final String REFUSED_BY_SERVER = "emailConnector.sendMode.refusedByServer";

  /** A shape was asked for a mail that belongs to no shared mailbox. */
  public static final String NO_MAILBOX        = "emailConnector.sendMode.noMailbox";

  /**
   * @param code the message code
   */
  public SendModeUnavailableException(String code) {
    super(code);
  }

  /**
   * @param code the message code
   * @param cause what the mail server answered, kept for the log only
   */
  public SendModeUnavailableException(String code, Throwable cause) {
    super(code, cause);
  }
}
