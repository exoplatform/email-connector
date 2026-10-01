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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.exception.RawEmailCapReachedException;

/**
 * The stream the "Show original" view reads a message into (EXO-90842): it keeps what
 * fits and stops the writer at its cap.
 */
class CappedOutputStreamTest {

  /** A source exactly as long as the cap fits whole and is not marked capped. */
  @Test
  void aSourceAsLongAsTheCapIsNotCapped() throws Exception {
    CappedOutputStream stream = new CappedOutputStream(5);
    stream.write("hello".getBytes(StandardCharsets.US_ASCII), 0, 5);
    assertFalse(stream.isCapped());
    assertArrayEquals("hello".getBytes(StandardCharsets.US_ASCII), stream.toByteArray());
  }

  /** A range that does not fit keeps what fits, marks the stream and stops the writer. */
  @Test
  void aRangePastTheCapKeepsWhatFitsAndStops() throws Exception {
    CappedOutputStream stream = new CappedOutputStream(3);
    byte[] bytes = "hello".getBytes(StandardCharsets.US_ASCII);
    assertThrows(RawEmailCapReachedException.class, () -> stream.write(bytes, 0, bytes.length));
    assertTrue(stream.isCapped());
    assertArrayEquals("hel".getBytes(StandardCharsets.US_ASCII), stream.toByteArray());
  }

  /** A single byte past the cap stops the writer too. */
  @Test
  void aBytePastTheCapStops() throws Exception {
    CappedOutputStream stream = new CappedOutputStream(1);
    stream.write('a');
    assertThrows(RawEmailCapReachedException.class, () -> stream.write('b'));
    assertTrue(stream.isCapped());
    assertArrayEquals(new byte[] { 'a' }, stream.toByteArray());
  }

  /** A negative cap is a programming error. */
  @Test
  void aNegativeCapIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> new CappedOutputStream(-1));
  }
}
