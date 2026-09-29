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
package org.exoplatform.emailConnector.service.filters;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;

/**
 * Whether the mail server flagged a mail as spam, read from what the server itself wrote
 * on it: no classifier of eXo's own. Two kinds of marks count:
 * <ul>
 * <li>the IMAP keywords {@code $Junk} (RFC 5788) and {@code Junk} (the one Thunderbird
 * and some servers set), unless {@code $NotJunk} or {@code NotJunk} says the user
 * decided otherwise;</li>
 * <li>the verdict headers the spam filters write: {@code X-Spam-Flag: YES}
 * (SpamAssassin), {@code X-Spam: Yes} (Rspamd) and {@code X-Spam-Status: Yes, ...}
 * (SpamAssassin, Stalwart).</li>
 * </ul>
 * A mail filed into the Junk folder is spam as well; that is read from the folder, not
 * from here.
 */
public final class SpamSignals {

  /** The keywords that mark a mail as spam, lower case. */
  public static final Set<String>  JUNK_KEYWORDS     = Set.of("$junk", "junk");

  /** The keywords that say the user took the spam mark back, lower case. */
  public static final Set<String>  NOT_JUNK_KEYWORDS = Set.of("$notjunk", "notjunk");

  /** The headers whose value is a yes/no verdict, read from their first word. */
  public static final List<String> VERDICT_HEADERS   = List.of("X-Spam-Flag", "X-Spam", "X-Spam-Status");

  /**
   * Not instantiable.
   */
  private SpamSignals() {
  }

  /**
   * Whether the server flagged a mail as spam.
   *
   * @param keywords the mail's keywords, any case; null when unknown
   * @param header reads the values of one of the mail's headers; null when unknown, and
   *          it may answer null for a header it cannot read
   * @return true when a keyword or a verdict header says spam
   */
  public static boolean isFlagged(Set<String> keywords, Function<String, List<String>> header) {
    if (keywords != null && !keywords.isEmpty()) {
      Set<String> lower = keywords.stream()
                                  .filter(StringUtils::isNotBlank)
                                  .map(keyword -> keyword.toLowerCase(Locale.ROOT))
                                  .collect(Collectors.toSet());
      if (lower.stream().anyMatch(NOT_JUNK_KEYWORDS::contains)) {
        return false;
      }
      if (lower.stream().anyMatch(JUNK_KEYWORDS::contains)) {
        return true;
      }
    }
    if (header == null) {
      return false;
    }
    for (String name : VERDICT_HEADERS) {
      List<String> values = header.apply(name);
      if (values != null && values.stream().anyMatch(SpamSignals::saysYes)) {
        return true;
      }
    }
    return false;
  }

  /**
   * Whether a mail the rules read was flagged as spam by the server.
   *
   * @param mail the mail
   * @return true when a keyword or a verdict header says spam
   */
  public static boolean isFlagged(FilterMail mail) {
    return mail != null && isFlagged(mail.keywords(), mail::header);
  }

  /**
   * Whether a verdict header's value says yes: its first word, before any comma or
   * space, is {@code yes} or {@code true}.
   *
   * @param value the value
   * @return true for a yes
   */
  static boolean saysYes(String value) {
    String first = StringUtils.trimToEmpty(value).split("[\\s,;]", 2)[0].toLowerCase(Locale.ROOT);
    return "yes".equals(first) || "true".equals(first);
  }
}
