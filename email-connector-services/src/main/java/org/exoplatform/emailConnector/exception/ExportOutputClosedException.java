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

import java.io.IOException;

/**
 * The output of an export failed: the browser cancelled the download, or the connection
 * to it dropped (EXO-90845). Raised by {@code GuardedOutputStream} in place of the
 * output's own {@link IOException}, so that a failure of the reader's side is never
 * mistaken for one of the mail server's, which reads through IOExceptions of its own.
 */
public class ExportOutputClosedException extends IOException {

  private static final long serialVersionUID = 1L;

  /**
   * Wraps the output's failure.
   *
   * @param cause the output's own exception
   */
  public ExportOutputClosedException(IOException cause) {
    super("The export's output is closed", cause);
  }
}
