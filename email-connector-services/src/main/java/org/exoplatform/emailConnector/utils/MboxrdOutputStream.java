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
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * Writes RFC 822 messages into an mbox file in the mboxrd form (EXO-90845), the variant
 * every mail application reads back without loss: each message's line endings become
 * LF, and every line that starts with any number of {@code >} followed by
 * {@code From } gets one more {@code >} in front, so that no line of a message can be
 * read as the {@code From_} line that separates two messages -- and a reader removing one
 * {@code >} gets the original line back, quoted lines included.
 * <p>
 * The {@code From_} line itself is the caller's to write, before
 * {@link #startMessage()}; {@link #endMessage()} closes the message with the empty line
 * the format puts between two messages. Bytes are written through as they come: a line
 * is held back only for its first bytes, until they tell whether it needs quoting.
 */
public class MboxrdOutputStream extends FilterOutputStream {

  private static final byte[]         FROM_ = { 'F', 'r', 'o', 'm', ' ' };

  // The first bytes of the current line, held while they could still be ">*From ".
  private final ByteArrayOutputStream lineStart = new ByteArrayOutputStream();

  private boolean                     atLineStart = true;

  private boolean                     holding;

  private boolean                     pendingCarriageReturn;

  /**
   * @param out where the mbox file goes
   */
  public MboxrdOutputStream(OutputStream out) {
    super(out);
  }

  /**
   * Starts a message: its first line is a line start, whatever the previous message
   * ended with.
   */
  public void startMessage() {
    atLineStart = true;
    holding = false;
    pendingCarriageReturn = false;
    lineStart.reset();
  }

  /**
   * Ends a message: what was held is written, the last line gets its LF when the message
   * did not end with one, and the empty line that separates two messages follows.
   *
   * @throws IOException when the output fails
   */
  public void endMessage() throws IOException {
    if (pendingCarriageReturn) {
      pendingCarriageReturn = false;
      emit('\r');
    }
    releaseHeld();
    if (!atLineStart) {
      out.write('\n');
    }
    out.write('\n');
    atLineStart = true;
  }

  /**
   * Writes one byte of a message: a CR followed by LF becomes LF, and a line start is
   * quoted when it reads {@code >*From }.
   *
   * @param b the byte, in its low eight bits
   * @throws IOException when the output fails
   */
  @Override
  public void write(int b) throws IOException {
    int value = b & 0xFF;
    if (pendingCarriageReturn) {
      pendingCarriageReturn = false;
      if (value == '\n') {
        emit('\n');
        return;
      }
      emit('\r');
    }
    if (value == '\r') {
      pendingCarriageReturn = true;
      return;
    }
    emit(value);
  }

  /**
   * Writes a range of bytes, one at a time through {@link #write(int)}: the stream
   * underneath is expected to buffer.
   *
   * @param bytes the source array
   * @param offset where the range starts
   * @param length how many bytes
   * @throws IOException when the output fails
   */
  @Override
  public void write(byte[] bytes, int offset, int length) throws IOException {
    for (int i = offset; i < offset + length; i++) {
      write(bytes[i]);
    }
  }

  /**
   * Passes one byte, line endings already converted, through the quoting of line starts.
   *
   * @param value the byte
   * @throws IOException when the output fails
   */
  private void emit(int value) throws IOException {
    if (!atLineStart && !holding) {
      out.write(value);
      atLineStart = value == '\n';
      return;
    }
    holding = true;
    atLineStart = false;
    lineStart.write(value);
    byte[] held = lineStart.toByteArray();
    int quotes = 0;
    while (quotes < held.length && held[quotes] == '>') {
      quotes++;
    }
    int matched = held.length - quotes;
    if (value != '\n' && matched < FROM_.length && matchesFrom(held, quotes, matched)) {
      // Still ">*" or a beginning of "From ": undecided.
      return;
    }
    if (value != '\n' && matched == FROM_.length && matchesFrom(held, quotes, matched)) {
      out.write('>');
    }
    releaseHeld();
    atLineStart = value == '\n';
  }

  /**
   * Whether the bytes after the leading {@code >} are a beginning of {@code From }.
   *
   * @param held the held bytes
   * @param from where the bytes after the quotes start
   * @param length how many of them
   * @return true when they match {@code From } so far
   */
  private static boolean matchesFrom(byte[] held, int from, int length) {
    for (int i = 0; i < length; i++) {
      if (held[from + i] != FROM_[i]) {
        return false;
      }
    }
    return true;
  }

  /**
   * Writes the held bytes of a line start, as they were.
   *
   * @throws IOException when the output fails
   */
  private void releaseHeld() throws IOException {
    if (holding) {
      lineStart.writeTo(out);
      lineStart.reset();
      holding = false;
    }
  }
}
