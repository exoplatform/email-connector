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
}
