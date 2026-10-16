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
class SieveTokenScanTest {

  /**
   * The word as a command, in a {@code require} list, or hyphenated.
   */
  @Test
  void testFindsTheWord() {
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
  void testIgnoresComments() {
    assertFalse(SieveTokenScan.containsWord("# vacation was here\r\nkeep;\r\n", "vacation"));
    assertFalse(SieveTokenScan.containsWord("/* vacation\r\n  \"still a comment\" */ keep;", "vacation"));
    assertTrue(SieveTokenScan.containsWord("keep; # \"\r\nvacation \"x\";", "vacation"));
  }

  /**
   * A comment marker inside a string or a {@code text:} block is text: the command after
   * it on the same line is still found.
   */
  @Test
  void testACommentMarkerInsideAStringHidesNothing() {
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
  void testACommentOpenerInsideATextBlockHidesNothing() {
    assertTrue(SieveTokenScan.containsWord("fileinto text:\r\n/* x\r\n.\r\n;\r\nvacation \"y\"; # */", "vacation"));
  }

  /**
   * A string, a bracket comment or a {@code text:} block that never ends runs to the end
   * of the script: the string and the block are kept, the comment is not.
   */
  @Test
  void testWhatNeverEndsRunsToTheEndOfTheScript() {
    assertTrue(SieveTokenScan.containsWord("fileinto \"vacation", "vacation"));
    assertTrue(SieveTokenScan.containsWord("keep text:\r\nvacation", "vacation"));
    assertTrue(SieveTokenScan.containsWord("vacation \"x\"; /* open", "vacation"));
    assertFalse(SieveTokenScan.containsWord("keep; /* vacation", "vacation"));
  }

  /**
   * Includes eXo cannot follow: global ones, non-literal names; a require list naming
   * the extension is not an include.
   */
  @Test
  void testUnreadableIncludes() {
    assertFalse(SieveTokenScan.hasUnreadableInclude("require [\"include\"];\r\ninclude :personal \"a\";"));
    assertTrue(SieveTokenScan.hasUnreadableInclude("require [\"include\"];\r\ninclude :global \"g\";"));
    assertTrue(SieveTokenScan.hasUnreadableInclude("require [\"include\", \"variables\"];\r\ninclude \"${name}\";"));
    assertTrue(SieveTokenScan.hasUnreadableInclude("include text:\r\nname\r\n.\r\n;"));
    assertFalse(SieveTokenScan.hasUnreadableInclude("# include :global \"g\"\r\nkeep;"));
    assertFalse(SieveTokenScan.includesAnything("require [\"include\"];\r\nkeep;"));
    assertTrue(SieveTokenScan.includesAnything("include \"b\";"));
  }

  /**
   * Personal includes eXo cannot name: a name holding an encoded character or a
   * variable, or one that is not a plain string. Global includes do not count, whatever
   * their name, and neither does a commented one.
   */
  @Test
  void testPersonalIncludesEXoCannotName() {
    assertFalse(SieveTokenScan.hasUnnamedPersonalInclude("require [\"include\"];\r\ninclude :personal \"a\";"));
    assertFalse(SieveTokenScan.hasUnnamedPersonalInclude("require [\"include\"];\r\ninclude :once :global \"${hex:65}\";"));
    assertFalse(SieveTokenScan.hasUnnamedPersonalInclude("# include text:\r\nkeep;"));
    assertTrue(SieveTokenScan.hasUnnamedPersonalInclude("require [\"include\"];\r\ninclude :personal \"${hex:65}xo-main\";"));
    assertTrue(SieveTokenScan.hasUnnamedPersonalInclude("require [\"include\", \"variables\"];\r\ninclude \"${name}\";"));
    assertTrue(SieveTokenScan.hasUnnamedPersonalInclude("include :global \"g\";\r\ninclude text:\r\nname\r\n.\r\n;"));
    assertTrue(SieveTokenScan.hasUnnamedPersonalInclude("include :personal \"a\" \"b\";"));
    assertTrue(SieveTokenScan.hasUnnamedPersonalInclude("include :personal \"a\""));
    assertTrue(SieveTokenScan.hasUnnamedPersonalInclude("include : \"a\";"));
  }

  /**
   * The word {@code include} inside a {@code text:} block is text, never a command: a
   * reply asking for a ticket number holds no include, and neither does one quoting an
   * include line after a dot-stuffed line, which does not end the block (RFC 5228
   * §2.4.2).
   */
  @Test
  void testATextBlockHoldsNoInclude() {
    String ticket = "require [\"reject\"];\r\nreject text:\r\nPlease include the ticket number.\r\n.\r\n;\r\n";
    assertFalse(SieveTokenScan.hasUnnamedPersonalInclude(ticket));
    assertFalse(SieveTokenScan.hasUnreadableInclude(ticket));
    assertFalse(SieveTokenScan.includesAnything(ticket));
    assertFalse(SieveTokenScan.hasUnnamedPersonalInclude(ticket.replace("\r\n", "\n")));
    String quoted = "reject text:\r\n..\r\ninclude :personal \"exo-rules\";\r\n.\r\n;\r\n";
    assertEquals(List.of(), SieveTokenScan.includedPersonalScripts(quoted));
    assertFalse(SieveTokenScan.includesAnything(quoted));
  }

  /**
   * An include is read where the engine reads one. Its name is the quoted string right
   * after its tags, blanks and comments allowed between them and none required:
   * Pigeonhole 2.3.21 compiles {@code include :personal"exo-rules";} and
   * {@code include:personal "exo-rules";}. A quote or a {@code :include} line inside a
   * {@code text:} block before it hides nothing. A word after a colon is a tag, so
   * {@code :text:} opens no block: Pigeonhole compiles {@code body :text:contains} as two
   * tags.
   */
  @Test
  void testAnIncludeIsReadWhereTheEngineReadsOne() {
    assertEquals(List.of("exo-main"), SieveTokenScan.includedPersonalScripts("include :personal\"exo-main\";"));
    assertEquals(List.of("a"), SieveTokenScan.includedPersonalScripts("include:personal \"a\";"));
    assertEquals(List.of("a"), SieveTokenScan.includedPersonalScripts("include :personal # c\r\n /* d */ \"a\";"));
    for (String text : List.of("Our limit is 5\" wide.", ":include \"zzz\"")) {
      String script = "reject text:\r\n" + text + "\r\n.\r\n;\r\ninclude :personal\"exo-main\";\r\n";
      assertEquals(List.of("exo-main"), SieveTokenScan.includedPersonalScripts(script), script);
      assertFalse(SieveTokenScan.hasUnnamedPersonalInclude(script), script);
    }
    String tags = "if body :text:contains \"x\" { keep; }\r\ninclude :personal \"exo-main\";\r\nreject text:\r\nNo.\r\n.\r\n;\r\n";
    assertEquals(List.of("exo-main"), SieveTokenScan.includedPersonalScripts(tags));
    assertFalse(SieveTokenScan.hasUnnamedPersonalInclude(tags));
  }

  /**
   * What the scan cannot delimit counts as an include eXo cannot name: a string, a
   * bracket comment or a {@code text:} block that never ends, which swallows the rest of
   * the script, and a {@code text:} block whose first line holds more than blanks and a
   * hash comment, which Pigeonhole refuses. A hash comment may end the script.
   */
  @Test
  void testWhatTheScanCannotDelimitIsAnIncludeEXoCannotName() {
    for (String script : List.of("keep;\r\nreject text:\r\nNo.\r\n",
                                 "fileinto \"Archive;\r\n",
                                 "keep; /* include :personal \"exo-main\";\r\n",
                                 "include :personal \"exo-main;\r\n",
                                 "reject text: /* c */\r\nNo.\r\n.\r\n;\r\n")) {
      assertTrue(SieveTokenScan.hasUnnamedPersonalInclude(script), script);
      assertTrue(SieveTokenScan.hasUnreadableInclude(script), script);
      assertTrue(SieveTokenScan.includesAnything(script), script);
    }
    assertFalse(SieveTokenScan.hasUnnamedPersonalInclude("reject text: # c\r\nNo.\r\n.\r\n;\r\nkeep; # the end"));
  }

  /**
   * Personal includes are listed, unescaped; global ones and commented ones are not.
   */
  @Test
  void testIncludedPersonalScripts() {
    String script = """
        require ["include"];\r
        include :personal "a";\r
        include :global "g";\r
        include :once "b\\"c";\r
        # include "d"\r
        include "e";""";
    assertEquals(List.of("a", "b\"c", "e"), SieveTokenScan.includedPersonalScripts(script));
    assertEquals(List.of(), SieveTokenScan.includedPersonalScripts(null));
  }

  /**
   * A script of several megabytes — one huge quoted include name full of escapes, and a
   * huge string outside any include — is scanned without a stack overflow, and the
   * answers are the ones a small script gets.
   */
  @Test
  void testScansAVeryLargeScriptWithoutStackOverflow() {
    String name = "a\\\"".repeat(400_000);
    String script = "require [\"include\"];\r\ninclude :personal \"" + name + "\";\r\nfileinto \""
        + "x\\\"".repeat(400_000) + "\";\r\n";
    assertTrue(script.length() > 2_000_000);
    assertEquals(List.of("a\"".repeat(400_000)), SieveTokenScan.includedPersonalScripts(script));
    assertTrue(SieveTokenScan.includesAnything(script));
    assertFalse(SieveTokenScan.hasUnreadableInclude(script));
  }
}
