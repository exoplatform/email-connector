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

import java.io.ByteArrayOutputStream;
import java.io.OutputStream;

import org.exoplatform.emailConnector.exception.RawEmailCapReachedException;

/**
 * An in-memory stream that keeps at most a given number of bytes (EXO-90842). The write
 * that would pass the cap keeps what fits, marks the stream {@link #isCapped() capped}
 * and throws {@link RawEmailCapReachedException}, so a writer copying a large message
 * stops there instead of reading the rest for nothing.
 */
public class CappedOutputStream extends OutputStream {

  // The buffer's first allocation: a typical message fits, a large one grows it.
  private static final int            INITIAL_CAPACITY = 64 * 1024;

  private final int                   cap;

  private final ByteArrayOutputStream buffer;

  private boolean                     capped;

  /**
   * Creates a stream keeping at most {@code cap} bytes.
   *
   * @param cap the number of bytes kept, at least zero
   */
  public CappedOutputStream(int cap) {
    if (cap < 0) {
      throw new IllegalArgumentException("A cap is never negative");
    }
    this.cap = cap;
    this.buffer = new ByteArrayOutputStream(Math.min(cap, INITIAL_CAPACITY));
  }

  /**
   * Writes one byte, or stops the writer when the stream is full.
   *
   * @param b the byte, in its low eight bits
   * @throws RawEmailCapReachedException when the stream already holds its cap
   */
  @Override
  public void write(int b) throws RawEmailCapReachedException {
    if (buffer.size() >= cap) {
      capped = true;
      throw new RawEmailCapReachedException(cap);
    }
    buffer.write(b);
  }

  /**
   * Writes what fits of a range of bytes, and stops the writer when the range does not
   * fit whole.
   *
   * @param bytes the source array
   * @param offset where the range starts in it
   * @param length how many bytes the range holds
   * @throws RawEmailCapReachedException when part of the range was left out
   */
  @Override
  public void write(byte[] bytes, int offset, int length) throws RawEmailCapReachedException {
    int room = cap - buffer.size();
    if (length <= room) {
      buffer.write(bytes, offset, length);
      return;
    }
    buffer.write(bytes, offset, Math.max(room, 0));
    capped = true;
    throw new RawEmailCapReachedException(cap);
  }

  /**
   * Whether a write was cut at the cap, which is the only way to know the writer had more
   * to give: a source exactly as long as the cap fills the stream without capping it.
   *
   * @return true once a write was cut
   */
  public boolean isCapped() {
    return capped;
  }

  /**
   * The bytes kept.
   *
   * @return a copy of what the stream holds
   */
  public byte[] toByteArray() {
    return buffer.toByteArray();
  }
}
