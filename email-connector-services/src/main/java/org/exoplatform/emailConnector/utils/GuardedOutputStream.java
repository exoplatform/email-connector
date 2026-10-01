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
package org.exoplatform.emailConnector.utils;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

import org.exoplatform.emailConnector.exception.ExportOutputClosedException;

/**
 * The output of an export, whose failures are told apart from the mail server's
 * (EXO-90845): every {@link IOException} the output raises comes out as an
 * {@link ExportOutputClosedException}. A message copied from the server fails with
 * IOExceptions of its own (a folder closed, a message removed mid-copy), and the two
 * call for opposite answers: a reader who left needs none, a server that failed must be
 * reported.
 */
public class GuardedOutputStream extends FilterOutputStream {

  /**
   * @param out the export's output, typically the HTTP response
   */
  public GuardedOutputStream(OutputStream out) {
    super(out);
  }

  /**
   * Writes one byte.
   *
   * @param b the byte
   * @throws ExportOutputClosedException when the output fails
   */
  @Override
  public void write(int b) throws ExportOutputClosedException {
    try {
      out.write(b);
    } catch (IOException e) {
      throw new ExportOutputClosedException(e);
    }
  }

  /**
   * Writes a range of bytes in one call to the output.
   *
   * @param bytes the source array
   * @param offset where the range starts
   * @param length how many bytes
   * @throws ExportOutputClosedException when the output fails
   */
  @Override
  public void write(byte[] bytes, int offset, int length) throws ExportOutputClosedException {
    try {
      out.write(bytes, offset, length);
    } catch (IOException e) {
      throw new ExportOutputClosedException(e);
    }
  }

  /**
   * Flushes the output.
   *
   * @throws ExportOutputClosedException when the output fails
   */
  @Override
  public void flush() throws ExportOutputClosedException {
    try {
      out.flush();
    } catch (IOException e) {
      throw new ExportOutputClosedException(e);
    }
  }
}
