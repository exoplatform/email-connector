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

/**
 * Look-ahead tables over a piece of decoded CSS, built in one backward pass, so that
 * reading the {@code url()} calls of a sender's stylesheet ({@link EmailHtmlSanitizer})
 * costs time linear in its length (EXO-90841). Each table answers "the first index at or
 * after {@code i} where ..." in constant time; an answer equal to the length means
 * "none".
 * <p>
 * White space is what {@code \s} matches in a Java regular expression: space, tab, line
 * feed, vertical tab, form feed and carriage return.
 */
final class CssScan {

  private final String css;

  private final int[]  nonSpace;

  private final int[]  doubleQuote;

  private final int[]  singleQuote;

  private final int[]  stop;

  /**
   * Builds the tables of a text.
   *
   * @param css the decoded CSS
   */
  CssScan(String css) {
    this.css = css;
    int length = css.length();
    nonSpace = new int[length + 1];
    doubleQuote = new int[length + 1];
    singleQuote = new int[length + 1];
    stop = new int[length + 1];
    nonSpace[length] = length;
    doubleQuote[length] = length;
    singleQuote[length] = length;
    stop[length] = length;
    for (int i = length - 1; i >= 0; i--) {
      char c = css.charAt(i);
      boolean space = isSpace(c);
      nonSpace[i] = space ? nonSpace[i + 1] : i;
      doubleQuote[i] = c == '"' ? i : doubleQuote[i + 1];
      singleQuote[i] = c == '\'' ? i : singleQuote[i + 1];
      stop[i] = space || c == ')' || c == '"' || c == '\'' ? i : stop[i + 1];
    }
  }

  /**
   * The text the tables were built over.
   *
   * @return the decoded CSS
   */
  String css() {
    return css;
  }

  /**
   * The first index at or after {@code from} that is not white space.
   *
   * @param from the index to start at, at most the length
   * @return that index, or the length when there is none
   */
  int nonSpaceFrom(int from) {
    return nonSpace[from];
  }

  /**
   * The first index at or after {@code from} holding the given quote.
   *
   * @param quote {@code "} or {@code '}
   * @param from the index to start at, at most the length
   * @return that index, or the length when there is none
   */
  int quoteFrom(char quote, int from) {
    return quote == '"' ? doubleQuote[from] : singleQuote[from];
  }

  /**
   * The first index at or after {@code from} that ends an unquoted {@code url()}
   * argument: white space, a quote or {@code )}.
   *
   * @param from the index to start at, at most the length
   * @return that index, or the length when there is none
   */
  int stopFrom(int from) {
    return stop[from];
  }

  /**
   * Whether a character is white space as {@code \s} reads it.
   *
   * @param c the character
   * @return true for space, tab, line feed, vertical tab, form feed or carriage return
   */
  static boolean isSpace(char c) {
    return c == ' ' || c == '\t' || c == '\n' || c == '\u000B' || c == '\f' || c == '\r';
  }
}
