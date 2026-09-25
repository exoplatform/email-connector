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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Token detection in a script eXo did not write — <b>detection, never parsing</b>.
 * <p>
 * The only thing eXo ever asks of a foreign script is whether a word occurs in it
 * outside a comment, and which scripts it names in an {@code include}. Comments are
 * removed with just enough lexing to know where a comment starts: a {@code #} or a
 * {@code /*} inside a quoted string or a {@code text:} block is text, not a comment,
 * and treating it as one would hide a real command further on the line. Strings are
 * kept: {@code require ["vacation"]} names the extension inside a string, and a word
 * that only occurs in a string makes eXo refuse — a false positive, the safe side.
 * Nothing is ever interpreted.
 */
public final class SieveTokenScan {

  /** {@code include}, its tagged arguments, and the quoted name of the included script. */
  private static final Pattern INCLUDE = Pattern.compile("(?i)(?<![A-Za-z0-9_])include((?:\\s+:[a-z]+)*)\\s+\"((?:[^\"\\\\]|\\\\.)*)\"");

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
   * The names of the personal scripts a script includes (RFC 6609), outside comments.
   * {@code :global} includes are left out: ManageSieve cannot read them.
   *
   * @param script the script text
   * @return the names, in order of appearance
   */
  public static List<String> includedPersonalScripts(String script) {
    List<String> names = new ArrayList<>();
    if (script == null) {
      return names;
    }
    Matcher matcher = INCLUDE.matcher(withoutComments(script));
    while (matcher.find()) {
      if (!matcher.group(1).toLowerCase(Locale.ROOT).contains(":global")) {
        names.add(matcher.group(2).replaceAll("\\\\(.)", "$1"));
      }
    }
    return names;
  }

  /**
   * Whether a script has an {@code include} eXo cannot follow: a {@code :global} one, or
   * one whose name is not a plain quoted string (a variable, a {@code text:} block).
   * Counted as the {@code include} commands outside comments and strings against those
   * {@link #includedPersonalScripts(String)} could name.
   *
   * @param script the script text
   * @return true when at least one include cannot be read
   */
  public static boolean hasUnreadableInclude(String script) {
    if (script == null) {
      return false;
    }
    int commands = includeCommands(script);
    List<String> personal = includedPersonalScripts(script);
    return commands > personal.size() || personal.stream().anyMatch(name -> name.contains("${"));
  }

  /**
   * Whether a script has any {@code include} command outside comments and strings.
   *
   * @param script the script text
   * @return true when it includes another script
   */
  public static boolean includesAnything(String script) {
    return script != null && includeCommands(script) > 0;
  }

  /**
   * The number of {@code include} commands outside comments and strings.
   *
   * @param script the script text
   * @return the count
   */
  private static int includeCommands(String script) {
    String code = withoutComments(script).replaceAll("\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"");
    Matcher matcher = Pattern.compile("(?i)(?<![A-Za-z0-9_:])include(?![A-Za-z0-9_])").matcher(code);
    int count = 0;
    while (matcher.find()) {
      count++;
    }
    return count;
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
    int length = script.length();
    while (i < length) {
      char c = script.charAt(i);
      if (c == '"') {
        int endOfString = endOfQuoted(script, i);
        out.append(script, i, endOfString);
        i = endOfString;
      } else if (c == '#') {
        while (i < length && script.charAt(i) != '\n') {
          i++;
        }
        out.append(' ');
      } else if (c == '/' && i + 1 < length && script.charAt(i + 1) == '*') {
        int close = script.indexOf("*/", i + 2);
        i = close < 0 ? length : close + 2;
        out.append(' ');
      } else if (startsMultiLine(script, i)) {
        int endOfText = endOfMultiLine(script, i);
        out.append(script, i, endOfText);
        i = endOfText;
      } else {
        out.append(c);
        i++;
      }
    }
    return out.toString();
  }

  /**
   * Where a quoted string that opens at {@code start} ends, past its closing quote.
   *
   * @param script the script
   * @param start the index of the opening quote
   * @return the index after the closing quote, or the end of the script
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
    return script.length();
  }

  /**
   * Whether a {@code text:} multi-line string opens at this index (RFC 5228 §2.4.2).
   *
   * @param script the script
   * @param index the index
   * @return true when {@code text:} starts here as a word
   */
  private static boolean startsMultiLine(String script, int index) {
    return script.regionMatches(true, index, "text:", 0, 5) && (index == 0 || !isWordChar(script.charAt(index - 1)));
  }

  /**
   * Where a {@code text:} block ends: after the line holding a single dot, or at the end
   * of the script.
   *
   * @param script the script
   * @param start the index of {@code text:}
   * @return the index after the block
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
    return script.length();
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
}
