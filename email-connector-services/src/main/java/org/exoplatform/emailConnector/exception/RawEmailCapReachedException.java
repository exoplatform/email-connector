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
 * Thrown by {@code CappedOutputStream} once it holds as many bytes as it may, to stop the
 * writer early (EXO-90842): the "Show original" view shows the start of a large message,
 * and reading the rest of it from the mail server only to drop it would cost the whole
 * download for nothing. Not a fault: the reader that set the cap catches it and answers
 * with what it holds.
 */
public class RawEmailCapReachedException extends IOException {

  private static final long serialVersionUID = 1L;

  /**
   * Creates the signal for a stream that reached its cap.
   *
   * @param cap the number of bytes the stream holds
   */
  public RawEmailCapReachedException(int cap) {
    super("The source reached its shown limit of " + cap + " bytes");
  }
}
