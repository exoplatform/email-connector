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

import org.exoplatform.emailConnector.model.SendMode;

/**
 * A delegate asked to send a mail in the owner's name in a shape the owner's consent does
 * not cover (EXO-90583): none given, or "on my behalf" given and "as me" asked.
 * <p>
 * An {@link IllegalAccessException} on purpose, like {@link MailboxRightMissingException}:
 * it travels the add-on's refusal path, which the REST layer answers with <b>403</b>, and
 * adds what a bare refusal loses -- which shape was missing -- as a message code the
 * composer translates, so it can fall back to the sender's own name and say why.
 */
public class SendModeMissingException extends IllegalAccessException {

  private static final long serialVersionUID = 1L;

  /** The message-code prefix; the requested shape is appended ({@code …missing.AS}). */
  public static final String CODE_PREFIX = "emailConnector.sendMode.missing.";

  private final SendMode     requested;

  /**
   * @param requested the shape the delegate asked for
   */
  public SendModeMissingException(SendMode requested) {
    super(CODE_PREFIX + requested.name());
    this.requested = requested;
  }

  /**
   * @return the shape the delegate asked for
   */
  public SendMode getRequested() {
    return requested;
  }
}
