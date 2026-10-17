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

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Token detection in a script eXo did not write — <b>detection, never parsing</b>.
 * <p>
 * The only things eXo ever asks of a foreign script are whether a word occurs in it
 * outside a comment, and which scripts its {@code include} commands name. Both rest on
 * just enough lexing to know where a comment, a quoted string and a {@code text:} block
 * (RFC 5228 §2.4.2) start and end. A {@code #} or a {@code /*} inside a string or a block
 * is text, not a comment: treating it as one would hide a real command further on. A
 * quote inside a block opens no string. The script is read one token at a time, and a
 * word or a number ends where Pigeonhole's lexer ends it: a number is its digits and at
 * most one {@code K}, {@code M} or {@code G}, so {@code :days 7text:} is a number followed
 * by a block.
 * <ul>
 * <li><b>A word</b> is looked for with strings and blocks kept: {@code require
 * ["vacation"]} names the extension inside a string, and a word that only occurs in a
 * string makes eXo refuse — a false positive, the safe side.</li>
 * <li><b>An include</b> is a command only outside comments, strings and blocks: the word
 * in a reply text is text. Its name is the quoted string right after its tags, followed
 * by {@code ;}. Any other include, and a string, a bracket comment or a block the scan
 * cannot delimit, is one eXo cannot name, and the callers refuse it.</li>
 * </ul>
 * Nothing is ever interpreted.
 */
public final class SieveTokenScan {

  /** The command that includes another script (RFC 6609). */
  private static final String INCLUDE = "include";

  /** The tag of an include ManageSieve cannot read. */
  private static final String GLOBAL  = "global";

  /** The token that opens a multi-line string. */
  private static final String TEXT    = "text:";

  /** The quantifiers that may end a number, in either case (RFC 5228 §2.4.1). */
  private static final String QUANTIFIERS = "kKmMgG";

  /**
   * Utility class.
   */
  private SieveTokenScan() {
  }

  /**
   * Whether a word occurs outside comments, case-insensitively, delimited by anything
   * that is not a letter, a digit or an underscore — so {@code vacation-seconds} counts
   * as {@code vacation} too.
   *
   * @param script the script text
   * @param word the word
   * @return true when found
   */
  public static boolean containsWord(String script, String word) {
    if (script == null || word == null || word.isEmpty()) {
      return false;
    }
    String text = withoutComments(script).toLowerCase(Locale.ROOT);
    String needle = word.toLowerCase(Locale.ROOT);
    int index = text.indexOf(needle);
    while (index >= 0) {
      int after = index + needle.length();
      if ((index == 0 || !isWordChar(text.charAt(index - 1))) && (after >= text.length() || !isWordChar(text.charAt(after)))) {
        return true;
      }
      index = text.indexOf(needle, index + 1);
    }
    return false;
  }

  /**
   * The names of the personal scripts a script includes (RFC 6609): the {@code include}
   * commands outside comments, strings and {@code text:} blocks that give a plain quoted
   * name. {@code :global} includes are left out: ManageSieve cannot read them.
   *
   * @param script the script text
   * @return the names, unescaped, in order of appearance
   */
  public static List<String> includedPersonalScripts(String script) {
    if (script == null) {
      return List.of();
    }
    return includes(script, false).stream().filter(Objects::nonNull).toList();
  }

  /**
   * Whether a script has an {@code include} eXo cannot follow: a {@code :global} one, one
   * whose name is not a plain quoted string (a variable, a {@code text:} block) or holds
   * <code>${</code>, or a part the scan cannot delimit.
   *
   * @param script the script text
   * @return true when at least one include cannot be read
   */
  public static boolean hasUnreadableInclude(String script) {
    return script != null && anyUnnamed(includes(script, true));
  }

  /**
   * Whether a script has a personal {@code include} whose target eXo cannot name: one
   * whose name is not a plain quoted string (a variable, a {@code text:} block), or holds
   * <code>${</code>, which the engine may decode into another name ({@code ${hex:65}} is
   * an {@code e} under RFC 5228 §2.4.2.4), or a part the scan cannot delimit.
   * {@code :global} includes are not counted, since none can be one of eXo's personal
   * scripts.
   *
   * @param script the script text
   * @return true when at least one personal include cannot be named
   */
  public static boolean hasUnnamedPersonalInclude(String script) {
    return script != null && anyUnnamed(includes(script, false));
  }

  /**
   * Whether a script has any {@code include} command outside comments, strings and
   * {@code text:} blocks, or a part the scan cannot delimit.
   *
   * @param script the script text
   * @return true when it includes another script, or may
   */
  public static boolean includesAnything(String script) {
    return script != null && !includes(script, true).isEmpty();
  }

  /**
   * The script with its {@code #} and bracket comments replaced by a space, strings and
   * {@code text:} blocks kept verbatim.
   *
   * @param script the script text
   * @return the text without comments
   */
  static String withoutComments(String script) {
    StringBuilder out = new StringBuilder(script.length());
    int i = 0;
    while (i < script.length()) {
      int end = endOrLength(script, endOfToken(script, i));
      if (script.charAt(i) == '#' || startsBracketComment(script, i)) {
        out.append(' ');
      } else {
        out.append(script, i, end);
      }
      i = end;
    }
    return out.toString();
  }

  /**
   * The include commands of a script, read in one pass over its tokens, so a word inside a
   * comment, a string or a {@code text:} block is never one. Each include is the name it
   * gives, or null when eXo cannot name it. A string, a bracket comment or a block that
   * never ends swallows the rest of the script, and a block whose first line holds more
   * than blanks and a hash comment is one Pigeonhole refuses: each adds a null too, since
   * the scan cannot tell what the engine reads there.
   *
   * @param script the script text
   * @param global whether {@code :global} includes are listed, as null since ManageSieve
   *          cannot read them, or left out
   * @return the includes, in order: a name, or null
   */
  private static List<String> includes(String script, boolean global) {
    List<String> includes = new ArrayList<>();
    int i = 0;
    while (i < script.length()) {
      int end = endOfToken(script, i);
      if (end < 0) {
        includes.add(null);
        return includes;
      }
      if (isIncludeCommand(script, i, end)) {
        end = readInclude(script, end, global, includes);
      } else if (startsMultiLine(script, i) && !plainMultiLineHeader(script, i)) {
        includes.add(null);
      }
      i = end;
    }
    return includes;
  }

  /**
   * Reads one include command from the end of its name: its tags, then its value, blanks
   * and comments allowed between them and none required, as the engine reads them. Adds
   * the name the value gives, or null; a {@code :global} include is added as null when
   * global ones are listed, and left out otherwise.
   *
   * @param script the script
   * @param start the index after the word {@code include}
   * @param global whether {@code :global} includes are listed
   * @param includes where the include is added
   * @return the index of the value, where lexing resumes, so a string or a {@code text:}
   *         block there is skipped whole
   */
  private static int readInclude(String script, int start, boolean global, List<String> includes) {
    boolean isGlobal = false;
    int i = skipBlanks(script, start);
    while (i < script.length() && script.charAt(i) == ':') {
      int end = endOfWord(script, i + 1);
      if (end == i + 1) {
        break;
      }
      isGlobal = isGlobal || (end - i - 1 == GLOBAL.length() && script.regionMatches(true, i + 1, GLOBAL, 0, GLOBAL.length()));
      i = skipBlanks(script, end);
    }
    if (!isGlobal) {
      includes.add(quotedName(script, i));
    } else if (global) {
      includes.add(null);
    }
    return i;
  }

  /**
   * The name a quoted string gives an include, when a {@code ;} ends the command after it.
   *
   * @param script the script
   * @param index where the value starts
   * @return the name, unescaped, or null when there is no quoted string there, it never
   *         ends, or something else than {@code ;} follows it
   */
  private static String quotedName(String script, int index) {
    if (index >= script.length() || script.charAt(index) != '"') {
      return null;
    }
    int end = endOfQuoted(script, index);
    if (end < 0) {
      return null;
    }
    int after = skipBlanks(script, end);
    if (after >= script.length() || script.charAt(after) != ';') {
      return null;
    }
    return unescape(script, index + 1, end - 1);
  }

  /**
   * Whether an include eXo cannot name is among them: null, or a name holding
   * <code>${</code>.
   *
   * @param includes the includes
   * @return true when one cannot be named
   */
  private static boolean anyUnnamed(List<String> includes) {
    return includes.stream().anyMatch(name -> name == null || name.contains("${"));
  }

  /**
   * Whether the token from {@code start} to {@code end} is the {@code include} command:
   * the whole word, in any case.
   *
   * @param script the script
   * @param start where the token starts
   * @param end where it ends
   * @return true for the command
   */
  private static boolean isIncludeCommand(String script, int start, int end) {
    return end - start == INCLUDE.length() && script.regionMatches(true, start, INCLUDE, 0, INCLUDE.length());
  }

  /**
   * Where the token that starts at an index ends: a quoted string past its closing quote,
   * a comment past its end, a {@code text:} block past its closing line, a number past its
   * digits and its quantifier, a word past its last character, any other character past
   * itself.
   *
   * @param script the script
   * @param index where the token starts
   * @return the index after the token, or -1 when a string, a bracket comment or a
   *         {@code text:} block never ends
   */
  private static int endOfToken(String script, int index) {
    char c = script.charAt(index);
    if (c == '"') {
      return endOfQuoted(script, index);
    }
    if (c == '#') {
      return endOfLine(script, index);
    }
    if (startsBracketComment(script, index)) {
      int close = script.indexOf("*/", index + 2);
      return close < 0 ? -1 : close + 2;
    }
    if (startsMultiLine(script, index)) {
      return endOfMultiLine(script, index);
    }
    if (isDigit(c)) {
      return endOfNumber(script, index);
    }
    return isWordChar(c) ? endOfWord(script, index) : index + 1;
  }

  /**
   * The index of the first character at or after an index that is neither a blank
   * (space, tab, CR, LF) nor inside a comment.
   *
   * @param script the script
   * @param index where to start
   * @return that index, or the end of the script
   */
  private static int skipBlanks(String script, int index) {
    int i = index;
    while (i < script.length()) {
      char c = script.charAt(i);
      if (c == ' ' || c == '\t' || c == '\r' || c == '\n') {
        i++;
      } else if (c == '#' || startsBracketComment(script, i)) {
        i = endOrLength(script, endOfToken(script, i));
      } else {
        return i;
      }
    }
    return i;
  }

  /**
   * Where a quoted string that opens at {@code start} ends, past its closing quote.
   *
   * @param script the script
   * @param start the index of the opening quote
   * @return the index after the closing quote, or -1 when the string never ends
   */
  private static int endOfQuoted(String script, int start) {
    int i = start + 1;
    while (i < script.length()) {
      char c = script.charAt(i);
      if (c == '\\') {
        i += 2;
      } else if (c == '"') {
        return i + 1;
      } else {
        i++;
      }
    }
    return -1;
  }

  /**
   * Whether a {@code text:} multi-line string opens at this token start (RFC 5228
   * §2.4.2): the word {@code text}, not a tag, followed by a colon. Pigeonhole reads
   * {@code :text:contains} as two tags. A longer word holding {@code text} is one token
   * and never starts here, while a number ends before it: {@code 7text:} opens a block.
   *
   * @param script the script
   * @param index where a token starts
   * @return true when {@code text:} starts here as a word
   */
  private static boolean startsMultiLine(String script, int index) {
    return script.regionMatches(true, index, TEXT, 0, TEXT.length()) && !isTag(script, index);
  }

  /**
   * Whether the first line of a {@code text:} block holds only blanks and an optional hash
   * comment, the only form RFC 5228 §8.1 allows and Pigeonhole compiles.
   *
   * @param script the script
   * @param start the index of {@code text:}
   * @return true when the line is plain
   */
  private static boolean plainMultiLineHeader(String script, int start) {
    int i = start + TEXT.length();
    while (i < script.length() && (script.charAt(i) == ' ' || script.charAt(i) == '\t')) {
      i++;
    }
    return i < script.length() && (script.charAt(i) == '#' || script.charAt(i) == '\n' || script.startsWith("\r\n", i));
  }

  /**
   * Where a {@code text:} block ends: after the line holding a single dot. A line that
   * starts with a dot and holds more is text (dot-stuffing).
   *
   * @param script the script
   * @param start the index of {@code text:}
   * @return the index after the block, or -1 when no line ends it
   */
  private static int endOfMultiLine(String script, int start) {
    int lineStart = script.indexOf('\n', start);
    while (lineStart >= 0) {
      int next = script.indexOf('\n', lineStart + 1);
      String line = script.substring(lineStart + 1, next < 0 ? script.length() : next);
      if (line.equals(".") || line.equals(".\r")) {
        return next < 0 ? script.length() : next + 1;
      }
      lineStart = next;
    }
    return -1;
  }

  /**
   * Where the line holding an index ends.
   *
   * @param script the script
   * @param index the index
   * @return the index of its line feed, or the end of the script
   */
  private static int endOfLine(String script, int index) {
    int end = script.indexOf('\n', index);
    return end < 0 ? script.length() : end;
  }

  /**
   * Where the word that starts at an index ends.
   *
   * @param script the script
   * @param index the index
   * @return the index after its last character
   */
  private static int endOfWord(String script, int index) {
    int i = index;
    while (i < script.length() && isWordChar(script.charAt(i))) {
      i++;
    }
    return i;
  }

  /**
   * Where the number that starts at an index ends, as Pigeonhole's lexer ends it: past
   * its digits and at most one quantifier. What follows starts a token of its own.
   *
   * @param script the script
   * @param index the index of its first digit
   * @return the index after its last digit, or after its quantifier
   */
  private static int endOfNumber(String script, int index) {
    int i = index;
    while (i < script.length() && isDigit(script.charAt(i))) {
      i++;
    }
    return i < script.length() && QUANTIFIERS.indexOf(script.charAt(i)) >= 0 ? i + 1 : i;
  }

  /**
   * Whether a bracket comment opens at this index.
   *
   * @param script the script
   * @param index the index
   * @return true on {@code /*}
   */
  private static boolean startsBracketComment(String script, int index) {
    return script.startsWith("/*", index);
  }

  /**
   * Whether the word at this index is a tag: a colon right before it.
   *
   * @param script the script
   * @param index where the word starts
   * @return true for a tag
   */
  private static boolean isTag(String script, int index) {
    return index > 0 && script.charAt(index - 1) == ':';
  }

  /**
   * An end index, or the end of the script for one that never comes.
   *
   * @param script the script
   * @param end the index, -1 when it never comes
   * @return the index
   */
  private static int endOrLength(String script, int end) {
    return end < 0 ? script.length() : end;
  }

  /**
   * The text of a quoted string with its escapes undone: a backslash keeps the character
   * after it (RFC 5228 §2.4.2).
   *
   * @param script the script
   * @param from the index after the opening quote
   * @param to the index of the closing quote
   * @return the text
   */
  private static String unescape(String script, int from, int to) {
    StringBuilder text = new StringBuilder(to - from);
    int i = from;
    while (i < to) {
      char c = script.charAt(i);
      if (c == '\\' && i + 1 < to) {
        text.append(script.charAt(i + 1));
        i += 2;
      } else {
        text.append(c);
        i++;
      }
    }
    return text.toString();
  }

  /**
   * Whether a character belongs to a word.
   *
   * @param c the character
   * @return true for letters, digits and underscore
   */
  private static boolean isWordChar(char c) {
    return Character.isLetterOrDigit(c) || c == '_';
  }

  /**
   * Whether a character is a digit a number starts with: ASCII only, as in Pigeonhole.
   *
   * @param c the character
   * @return true for {@code 0} to {@code 9}
   */
  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }
}
