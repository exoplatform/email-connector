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
 * A user managed mode governs asked to disconnect their mailbox, edit its connection or
 * connect another connector than the designated one.
 * <p>
 * An {@link IllegalAccessException}, so it is answered with <b>403</b> as every refusal
 * of this kind; its own type lets the REST layer put its message code in the response,
 * for the interface to say why, without doing so for the other refusals.
 */
public class ManagedConnectionLockedException extends IllegalAccessException {

  /** The message code the interface translates. */
  public static final String MESSAGE_CODE     = "emailConnector.managed.connectionLocked";

  private static final long  serialVersionUID = 1L;

  /**
   * The refusal, carrying {@link #MESSAGE_CODE}.
   */
  public ManagedConnectionLockedException() {
    super(MESSAGE_CODE);
  }
}
