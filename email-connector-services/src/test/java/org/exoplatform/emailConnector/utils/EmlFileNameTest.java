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

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * The file name a message is downloaded under (EXO-90842). The subject is the sender's
 * text and ends up in a {@code Content-Disposition} header and on the user's disk, so
 * every character that could break either is replaced.
 */
class EmlFileNameTest {

  /** A plain subject is the name, with {@code .eml}. */
  @Test
  void aPlainSubjectIsTheName() {
    assertEquals("Quarterly report.eml", EmailConnectorUtils.emlFileName("Quarterly report"));
  }

  /** No subject, a blank one, or one made only of dots, gives the default name. */
  @Test
  void noUsableSubjectGivesTheDefaultName() {
    assertEquals("message.eml", EmailConnectorUtils.emlFileName(null));
    assertEquals("message.eml", EmailConnectorUtils.emlFileName("   "));
    assertEquals("message.eml", EmailConnectorUtils.emlFileName(" ... "));
  }

  /**
   * Line breaks and other control characters cannot reach the header, and the path and
   * wildcard characters cannot reach the disk.
   */
  @Test
  void controlAndPathCharactersAreReplaced() {
    assertEquals("a__Set-Cookie_ x.eml", EmailConnectorUtils.emlFileName("a\r\nSet-Cookie: x"));
    assertEquals("_etc_passwd_ _x_ _ _ _ _.eml", EmailConnectorUtils.emlFileName("/etc/passwd\\ \"x\" < > | ?"));
  }

  /** An invisible bidirectional override cannot disguise the extension. */
  @Test
  void aBidirectionalOverrideIsReplaced() {
    assertEquals("invoice_exe.pdf.eml", EmailConnectorUtils.emlFileName("invoice\u202Eexe.pdf"));
  }

  /** Runs of spaces become one, and leading or trailing dots and spaces go. */
  @Test
  void spacesAndDotsAreTidied() {
    assertEquals("Re hello.eml", EmailConnectorUtils.emlFileName("  .Re   hello. "));
  }

  /** A long subject is cut at the limit in code points, never inside a surrogate pair. */
  @Test
  void aLongSubjectIsCutInCodePoints() {
    String emoji = "\uD83D\uDE00";
    String name = EmailConnectorUtils.emlFileName(emoji.repeat(150));
    assertEquals(emoji.repeat(EmailConnectorUtils.EML_NAME_MAX_LENGTH) + ".eml", name);
  }
}
