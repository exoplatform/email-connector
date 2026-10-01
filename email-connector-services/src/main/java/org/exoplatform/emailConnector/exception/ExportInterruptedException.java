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
 * The mail server failed once an export had begun, and the file has no place to say so
 * (EXO-90845: an {@code .mbox}). Thrown out of the request on purpose: the response is
 * already committed, so the container aborts it, and the browser reports a failed
 * download rather than a complete file that silently lacks the rest of the folder.
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
