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
 */package org.exoplatform.emailConnector.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * The mboxrd quoting of an exported folder (EXO-90845): line endings become LF, and every
 * line reading {@code >*From } gets one more {@code >} -- however the writer cuts its
 * bytes -- so no line of a message can be read as the line that separates two messages.
 */
class MboxrdOutputStreamTest {

  /** CRLF becomes LF, and a message ends with its line break and the separating empty line. */
  @Test
  void lineEndingsBecomeLfAndAMessageEndsWithAnEmptyLine() throws IOException {
    assertEquals("Subject: a\n\nbody\n\n", write("Subject: a\r\n\r\nbody\r\n", false));
    assertEquals("Subject: a\n\nno final break\n\n", write("Subject: a\r\n\r\nno final break", false));
  }

  /** {@code From } at a line start is quoted, and an already quoted one gets one more. */
  @Test
  void fromLinesAreQuotedOnceMore() throws IOException {
    assertEquals("A: b\n\n>From me\n>>From you\n>>>From them\n\n", write("A: b\r\n\r\nFrom me\r\n>From you\r\n>>From them\r\n", false));
  }

  /** What only looks like {@code From } is left alone. */
  @Test
  void lookalikesAreLeftAlone() throws IOException {
    assertEquals("A: b\n\nFromage\nFrom\n From x\n>Fro\nsay From x\n>\n\n",
                 write("A: b\r\n\r\nFromage\r\nFrom\r\n From x\r\n>Fro\r\nsay From x\r\n>\r\n", false));
  }

  /** The same output whether the bytes come one by one or in one array. */
  @Test
  void theOutputDoesNotDependOnHowTheBytesAreCut() throws IOException {
    String message = "A: b\r\n\r\nFrom x\r\n>From y\r\nplain\rcarriage\r\n";
    assertEquals(write(message, false), write(message, true));
    assertEquals("A: b\n\n>From x\n>>From y\nplain\rcarriage\n\n", write(message, true));
  }

  /** Each message starts at a line start, whatever the previous one ended with. */
  @Test
  void eachMessageStartsAtALineStart() throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    MboxrdOutputStream out = new MboxrdOutputStream(bytes);
    out.startMessage();
    out.write("A: 1\r\n\r\n>Fr".getBytes(StandardCharsets.US_ASCII));
    out.endMessage();
    out.startMessage();
    out.write("From the start".getBytes(StandardCharsets.US_ASCII));
    out.endMessage();
    assertEquals("A: 1\n\n>Fr\n\n>From the start\n\n", bytes.toString(StandardCharsets.US_ASCII));
  }

  /**
   * Writes one message through the stream.
   *
   * @param message the message
   * @param byteByByte whether to write it one byte at a time
   * @return what the stream wrote
   * @throws IOException never
   */
  private static String write(String message, boolean byteByByte) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    MboxrdOutputStream out = new MboxrdOutputStream(bytes);
    out.startMessage();
    byte[] raw = message.getBytes(StandardCharsets.US_ASCII);
    if (byteByByte) {
      for (byte b : raw) {
        out.write(b);
      }
    } else {
      out.write(raw, 0, raw.length);
    }
    out.endMessage();
    out.flush();
    return bytes.toString(StandardCharsets.US_ASCII);
  }
}
