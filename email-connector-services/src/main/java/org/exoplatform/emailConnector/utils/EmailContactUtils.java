/**
 * Copyright (C) 2025 eXo Platform SAS
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

import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;

/**
 * The pure string mechanics of the contact store: address normalization, the
 * machine-address filter, and the sort key / letter bucket derivation. Static
 * and side-effect free so the collection rules stay testable without a Spring
 * context.
 */
public final class EmailContactUtils {

  private EmailContactUtils() {
  }

  /** The bucket of every sort key that does not start with a Latin letter ("#" in the rail). */
  public static final int      OTHER_SORT_BUCKET = 26;

  /** The rail label of {@link #OTHER_SORT_BUCKET}. */
  public static final String   OTHER_LETTER      = "#";

  // Local parts that mean "no human answers here". Matched as a containment on the
  // local part: list bounce addresses look like "bounces-12-user=host" and notification
  // senders like "notifications+abc", so anchoring would miss most of them.
  private static final Pattern NO_REPLY_LOCAL_PART =
                                                   Pattern.compile("no-?reply|do-?not-?reply|mailer-daemon|postmaster|bounce|notifications?",
                                                                   Pattern.CASE_INSENSITIVE);

  // Combining diacritical marks, removed after NFD decomposition so "Müller" files under M.
  private static final Pattern COMBINING_MARKS  = Pattern.compile("\\p{M}+");

  /**
   * The most a contact note may hold, matching the NOTE_TEXT column. Applied on
   * every write path — form, import, sync — so the cap is a property of the
   * store, not of whichever door the note came in by.
   */
  public static final int      MAX_NOTE_LENGTH   = 2000;

  /**
   * Normalizes an address to the form the store keys by: trimmed and lowercased.
   * Returns null for anything that is not plausibly an address (blank, or no
   * {@code @}), so callers can skip in one test.
   *
   * @param address the raw address, may be null
   * @return the normalized address, or null when it is not usable as a key
   */
  public static String normalizeAddress(String address) {
    if (StringUtils.isBlank(address)) {
      return null;
    }
    String normalized = address.trim().toLowerCase(Locale.ROOT);
    return normalized.indexOf('@') > 0 ? normalized : null;
  }

  /**
   * The local part of an address (everything before the last {@code @}).
   *
   * @param address a normalized address
   * @return the local part, or the whole string when there is no {@code @}
   */
  public static String localPart(String address) {
    int at = address == null ? -1 : address.lastIndexOf('@');
    return at > 0 ? address.substring(0, at) : address;
  }

  /**
   * The domain of an address, lower-cased — the part that says which organisation
   * a correspondent belongs to.
   *
   * @param address a normalized address
   * @return the domain, or {@code null} when the address has none
   */
  /**
   * The domains where sharing a domain means nothing about the people.
   *
   * The collection rule asks "has the user ever written to this organisation?",
   * which is a good question at a company domain and a meaningless one at a
   * consumer mail provider: writing to one friend at gmail.com would otherwise
   * admit every gmail.com sender on earth, transactional ones included. These
   * are the providers where the domain says nothing, so the rule falls back to
   * the address itself.
   *
   * Deliberately a short list of the large providers rather than an attempt at
   * completeness: a domain missing from it merely keeps the old, more generous
   * behaviour, while a wrong entry would cost a real correspondent.
   */
  private static final Set<String> FREEMAIL_DOMAINS =
                                                    Set.of("gmail.com",
                                                           "googlemail.com",
                                                           "outlook.com",
                                                           "outlook.fr",
                                                           "hotmail.com",
                                                           "hotmail.fr",
                                                           "hotmail.co.uk",
                                                           "live.com",
                                                           "live.fr",
                                                           "msn.com",
                                                           "yahoo.com",
                                                           "yahoo.fr",
                                                           "yahoo.co.uk",
                                                           "ymail.com",
                                                           "aol.com",
                                                           "icloud.com",
                                                           "me.com",
                                                           "mac.com",
                                                           "proton.me",
                                                           "protonmail.com",
                                                           "pm.me",
                                                           "gmx.com",
                                                           "gmx.de",
                                                           "gmx.fr",
                                                           "web.de",
                                                           "mail.com",
                                                           "mail.ru",
                                                           "yandex.com",
                                                           "yandex.ru",
                                                           "zoho.com",
                                                           "fastmail.com",
                                                           "tutanota.com",
                                                           "free.fr",
                                                           "orange.fr",
                                                           "wanadoo.fr",
                                                           "sfr.fr",
                                                           "laposte.net",
                                                           "bbox.fr",
                                                           "qq.com",
                                                           "163.com",
                                                           "126.com",
                                                           "naver.com",
                                                           "hanmail.net");

  /**
   * Whether this domain is a consumer mail provider, where having written to one
   * person says nothing about anyone else who writes from it.
   *
   * @param domain the lower-cased domain, may be null
   * @return true when the domain is a known consumer provider
   */
  public static boolean isFreemailDomain(String domain) {
    return domain != null && FREEMAIL_DOMAINS.contains(domain);
  }

  public static String domainOf(String address) {
    if (StringUtils.isBlank(address)) {
      return null;
    }
    int at = address.lastIndexOf('@');
    return at < 0 || at == address.length() - 1 ? null : address.substring(at + 1).toLowerCase();
  }

  /**
   * Whether an address is machine plumbing nobody should have in their contacts:
   * no-reply variants, mailer-daemon, postmaster, bounce and notification
   * senders. Judged on the local part only, so a person named
   * "bounce@family.example" is a known, accepted false positive.
   *
   * @param address a normalized address
   * @return true when the local part matches the no-reply pattern
   */
  public static boolean isNoReplyAddress(String address) {
    return address != null && NO_REPLY_LOCAL_PART.matcher(localPart(address)).find();
  }

  /**
   * Derives the sort key a contact files under: "FAMILY GIVEN" when structured
   * names exist, else the display name, else the address local-part — uppercased
   * and diacritic-stripped so "Müller" sorts (and buckets) under M on every
   * database collation.
   *
   * @param givenName the structured given name, may be null
   * @param familyName the structured family name, may be null
   * @param displayName the free-form display name, may be null
   * @param primaryEmail the normalized address, the last-resort key
   * @return the derived sort key, never null for a keyed contact
   */
  public static String computeSortName(String givenName, String familyName, String displayName, String primaryEmail) {
    String base;
    if (StringUtils.isNotBlank(familyName) || StringUtils.isNotBlank(givenName)) {
      base = (StringUtils.trimToEmpty(familyName) + " " + StringUtils.trimToEmpty(givenName)).trim();
    } else if (StringUtils.isNotBlank(displayName)) {
      base = displayName.trim();
    } else {
      base = StringUtils.trimToEmpty(localPart(primaryEmail));
    }
    String stripped = COMBINING_MARKS.matcher(Normalizer.normalize(base, Normalizer.Form.NFD)).replaceAll("");
    return StringUtils.abbreviate(stripped.toUpperCase(Locale.ROOT), 255);
  }

  /**
   * The letter bucket of a sort key: 0..25 for A..Z first characters, 26 for
   * everything else (digits, symbols, non-Latin scripts). The bucket, not the
   * character, is what the list orders and groups by — integer order is the one
   * thing every database collation agrees on.
   *
   * @param sortName the derived sort key
   * @return the bucket index
   */
  public static int sortBucketOf(String sortName) {
    if (StringUtils.isEmpty(sortName)) {
      return OTHER_SORT_BUCKET;
    }
    char first = sortName.charAt(0);
    return first >= 'A' && first <= 'Z' ? first - 'A' : OTHER_SORT_BUCKET;
  }

  /**
   * The rail label of a bucket: "A".."Z", or "#" for the everything-else bucket.
   *
   * @param bucket the bucket index
   * @return the label the drawer shows
   */
  public static String letterOfBucket(int bucket) {
    return bucket >= 0 && bucket < OTHER_SORT_BUCKET ? String.valueOf((char) ('A' + bucket)) : OTHER_LETTER;
  }

  /**
   * Best-effort author name for a message relayed by a mailing list that rewrote
   * From to itself: Google Groups and Mailman emit "Jane Doe via the-list", so
   * everything before the last " via " is the author. Null when the pattern is
   * absent — an address-only contact is better than a contact named after the
   * transport.
   *
   * @param senderName the rewritten From display name, may be null
   * @return the author's name, or null when it cannot be told apart from the list's
   */
  public static String authorNameFromListSender(String senderName) {
    if (StringUtils.isBlank(senderName)) {
      return null;
    }
    int via = senderName.lastIndexOf(" via ");
    return via > 0 ? senderName.substring(0, via).trim() : null;
  }

  // The spellings a birthday may arrive in: vCard basic and extended forms, with
  // or without a year, plus the bare MM-DD a person naturally types when they do
  // not know the year. Everything normalizes to YYYY-MM-DD or --MM-DD.
  private static final Pattern BIRTHDAY_WITH_YEAR    = Pattern.compile("(\\d{4})-?(\\d{2})-?(\\d{2})");

  private static final Pattern BIRTHDAY_WITHOUT_YEAR = Pattern.compile("(?:--)?(\\d{2})-?(\\d{2})");

  /**
   * Normalizes a birthday to the store's canonical text: {@code YYYY-MM-DD}
   * when a year is present, {@code --MM-DD} (vCard 4.0's own year-less form)
   * when it is not. The month and day are validated as a real calendar date —
   * leap day allowed, since year-less February 29 birthdays exist.
   *
   * @param birthday the raw text, may be null
   * @return the canonical form, or null when the input is blank OR not a date —
   *         a caller that must tell the two apart checks blankness itself
   */
  public static String normalizeBirthday(String birthday) {
    String trimmed = StringUtils.trimToNull(birthday);
    if (trimmed == null) {
      return null;
    }
    try {
      java.util.regex.Matcher withYear = BIRTHDAY_WITH_YEAR.matcher(trimmed);
      if (withYear.matches()) {
        return java.time.LocalDate.of(Integer.parseInt(withYear.group(1)),
                                      Integer.parseInt(withYear.group(2)),
                                      Integer.parseInt(withYear.group(3)))
                                  .toString();
      }
      java.util.regex.Matcher withoutYear = BIRTHDAY_WITHOUT_YEAR.matcher(trimmed);
      if (withoutYear.matches()) {
        java.time.MonthDay monthDay = java.time.MonthDay.of(Integer.parseInt(withoutYear.group(1)),
                                                            Integer.parseInt(withoutYear.group(2)));
        return String.format("--%02d-%02d", monthDay.getMonthValue(), monthDay.getDayOfMonth());
      }
    } catch (java.time.DateTimeException e) {
      // A month or day outside the calendar: same answer as a shape that never
      // matched — not a date.
    }
    return null;
  }

  /**
   * Trims a note and caps it at {@link #MAX_NOTE_LENGTH} — the store's cap,
   * applied wherever a note is written. Truncation over refusal: a note is
   * prose, and losing its tail is better than losing the contact carrying it
   * (the import and the sync have no user to ask).
   *
   * @param note the raw note, may be null
   * @return the trimmed, capped note, or null when there is nothing in it
   */
  public static String truncateNote(String note) {
    String trimmed = StringUtils.trimToNull(note);
    return trimmed == null ? null : StringUtils.left(trimmed, MAX_NOTE_LENGTH);
  }
}
