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
 * The mail server failed while a message was copied into an {@code .mbox} export
 * (EXO-90845): thrown by the visitor once it closed the message where the copy stopped,
 * so the export stops there and the file says it is incomplete
 * ({@code RawEmailVisitor#interrupted}). Never thrown out of a request: a committed
 * response would end normally anyway.
 */
public class ExportInterruptedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  /**
   * @param message what stopped
   * @param cause the mail server's failure
   */
  public ExportInterruptedException(String message, Throwable cause) {
    super(message, cause);
  }
}
