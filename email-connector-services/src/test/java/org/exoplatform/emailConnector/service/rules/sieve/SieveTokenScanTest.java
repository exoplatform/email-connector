/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.service.rules.sieve;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Token detection in a foreign script: found as a command or inside a string, ignored
 * inside a comment, and never fooled by a comment marker inside a string.
 */
public class SieveTokenScanTest {

  /**
   * The word as a command, in a {@code require} list, or hyphenated.
   */
  @Test
  public void testFindsTheWord() {
    assertTrue(SieveTokenScan.containsWord("vacation \"away\";", "vacation"));
    assertTrue(SieveTokenScan.containsWord("require [\"fileinto\", \"vacation\"];", "vacation"));
    assertTrue(SieveTokenScan.containsWord("require \"vacation-seconds\";", "vacation"));
    assertTrue(SieveTokenScan.containsWord("if true {\r\n  VACATION :days 3 \"x\";\r\n}", "vacation"));
    assertFalse(SieveTokenScan.containsWord("fileinto \"vacations\";", "vacation"));
    assertFalse(SieveTokenScan.containsWord("keep;", "vacation"));
  }

  /**
   * A word only in a hash or a bracket comment is not found.
   */
  @Test
  public void testIgnoresComments() {
    assertFalse(SieveTokenScan.containsWord("# vacation was here\r\nkeep;\r\n", "vacation"));
    assertFalse(SieveTokenScan.containsWord("/* vacation\r\n  \"still a comment\" */ keep;", "vacation"));
    assertTrue(SieveTokenScan.containsWord("keep; # \"\r\nvacation \"x\";", "vacation"));
  }

  /**
   * A comment marker inside a string or a {@code text:} block is text: the command after
   * it on the same line is still found.
   */
  @Test
  public void testACommentMarkerInsideAStringHidesNothing() {
    assertTrue(SieveTokenScan.containsWord("fileinto \"a#b\"; vacation \"x\";", "vacation"));
    assertTrue(SieveTokenScan.containsWord("fileinto \"a/*b\"; vacation \"x\"; # */", "vacation"));
    assertTrue(SieveTokenScan.containsWord("fileinto \"a\\\"#\"; vacation \"x\";", "vacation"));
    assertTrue(SieveTokenScan.containsWord("vacation text:\r\n# not a comment\r\n.\r\n;", "vacation"));
    assertFalse(SieveTokenScan.containsWord("keep text:\r\nfoo\r\n.\r\n# vacation\r\n", "vacation"));
  }

  /**
   * A bracket-comment opener inside a {@code text:} block is text: the {@code vacation}
   * after the block is found.
   */
  @Test
  public void testACommentOpenerInsideATextBlockHidesNothing() {
    assertTrue(SieveTokenScan.containsWord("fileinto text:\r\n/* x\r\n.\r\n;\r\nvacation \"y\"; # */", "vacation"));
  }

  /**
   * Includes eXo cannot follow: global ones, non-literal names; a require list naming
   * the extension is not an include.
   */
  @Test
  public void testUnreadableIncludes() {
    assertFalse(SieveTokenScan.hasUnreadableInclude("require [\"include\"];\r\ninclude :personal \"a\";"));
    assertTrue(SieveTokenScan.hasUnreadableInclude("require [\"include\"];\r\ninclude :global \"g\";"));
    assertTrue(SieveTokenScan.hasUnreadableInclude("require [\"include\", \"variables\"];\r\ninclude \"${name}\";"));
    assertTrue(SieveTokenScan.hasUnreadableInclude("include text:\r\nname\r\n.\r\n;"));
    assertFalse(SieveTokenScan.hasUnreadableInclude("# include :global \"g\"\r\nkeep;"));
    assertFalse(SieveTokenScan.includesAnything("require [\"include\"];\r\nkeep;"));
    assertTrue(SieveTokenScan.includesAnything("include \"b\";"));
  }

  /**
   * Personal includes are listed, unescaped; global ones and commented ones are not.
   */
  @Test
  public void testIncludedPersonalScripts() {
    String script = "require [\"include\"];\r\ninclude :personal \"a\";\r\ninclude :global \"g\";\r\n"
        + "include :once \"b\\\"c\";\r\n# include \"d\"\r\ninclude \"e\";";
    assertEquals(List.of("a", "b\"c", "e"), SieveTokenScan.includedPersonalScripts(script));
    assertEquals(List.of(), SieveTokenScan.includedPersonalScripts(null));
  }
}
