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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The contact store's pure string mechanics: address normalization, the
 * no-reply filter, and the sort key / letter bucket derivation the A–Z rail's
 * offset arithmetic depends on.
 */
public class EmailContactUtilsTest {

  @Test
  void normalizeAddressLowercasesAndTrims() {
    assertEquals("jane.doe@example.com", EmailContactUtils.normalizeAddress("  Jane.Doe@Example.COM "));
  }

  @Test
  void normalizeAddressRejectsWhatIsNotAnAddress() {
    assertNull(EmailContactUtils.normalizeAddress(null));
    assertNull(EmailContactUtils.normalizeAddress("   "));
    assertNull(EmailContactUtils.normalizeAddress("not-an-address"));
    assertNull(EmailContactUtils.normalizeAddress("@host"));
  }

  @Test
  void noReplyFilterCatchesTheMachinePlumbing() {
    assertTrue(EmailContactUtils.isNoReplyAddress("no-reply@example.com"));
    assertTrue(EmailContactUtils.isNoReplyAddress("noreply@example.com"));
    assertTrue(EmailContactUtils.isNoReplyAddress("do-not-reply@example.com"));
    assertTrue(EmailContactUtils.isNoReplyAddress("donotreply@example.com"));
    assertTrue(EmailContactUtils.isNoReplyAddress("mailer-daemon@example.com"));
    assertTrue(EmailContactUtils.isNoReplyAddress("postmaster@example.com"));
    assertTrue(EmailContactUtils.isNoReplyAddress("bounces-12-user=host@lists.example.com"));
    assertTrue(EmailContactUtils.isNoReplyAddress("notifications@github.com"));
    assertTrue(EmailContactUtils.isNoReplyAddress("notification+abc@facebookmail.com"));
  }

  @Test
  void noReplyFilterLeavesPeopleAlone() {
    assertFalse(EmailContactUtils.isNoReplyAddress("jane.doe@example.com"));
    assertFalse(EmailContactUtils.isNoReplyAddress("bob@example.com"));
    // The local part is what is judged, not the domain.
    assertFalse(EmailContactUtils.isNoReplyAddress("jane@notifications.example.com"));
  }

  @Test
  void sortNamePrefersFamilyGivenThenDisplayNameThenLocalPart() {
    assertEquals("DOE JANE", EmailContactUtils.computeSortName("Jane", "Doe", "whatever", "jane@example.com"));
    assertEquals("JANE DOE", EmailContactUtils.computeSortName(null, null, "Jane Doe", "jane@example.com"));
    assertEquals("JANE.DOE", EmailContactUtils.computeSortName(null, null, null, "jane.doe@example.com"));
  }

  @Test
  void sortNameStripsDiacriticsSoMuellerFilesUnderM() {
    assertEquals("MULLER ERWIN", EmailContactUtils.computeSortName("Erwin", "Müller", null, "em@example.com"));
    assertEquals("EMILE ZOLA", EmailContactUtils.computeSortName(null, null, "Émile Zola", "ez@example.com"));
  }

  @Test
  void sortBucketMapsLatinLettersAndCollectsTheRestUnderHash() {
    assertEquals(0, EmailContactUtils.sortBucketOf("ABBOTT"));
    assertEquals(25, EmailContactUtils.sortBucketOf("ZOLA"));
    assertEquals(EmailContactUtils.OTHER_SORT_BUCKET, EmailContactUtils.sortBucketOf("42 THINGS"));
    assertEquals(EmailContactUtils.OTHER_SORT_BUCKET, EmailContactUtils.sortBucketOf("北京"));
    assertEquals(EmailContactUtils.OTHER_SORT_BUCKET, EmailContactUtils.sortBucketOf(""));
    assertEquals(EmailContactUtils.OTHER_SORT_BUCKET, EmailContactUtils.sortBucketOf(null));
  }

  @Test
  void letterOfBucketIsTheRailAlphabetPlusHash() {
    assertEquals("A", EmailContactUtils.letterOfBucket(0));
    assertEquals("Z", EmailContactUtils.letterOfBucket(25));
    assertEquals(EmailContactUtils.OTHER_LETTER, EmailContactUtils.letterOfBucket(EmailContactUtils.OTHER_SORT_BUCKET));
  }

  @Test
  void authorNameIsExtractedFromTheViaPatternOnly() {
    assertEquals("Jane Doe", EmailContactUtils.authorNameFromListSender("Jane Doe via dev-list"));
    assertNull(EmailContactUtils.authorNameFromListSender("dev-list"));
    assertNull(EmailContactUtils.authorNameFromListSender(null));
  }

  @Test
  void aBirthdayNormalizesFromEverySpellingItArrivesIn() {
    // vCard basic and extended forms, with and without a year, plus the bare
    // MM-DD a person naturally types — all to one canonical form each.
    assertEquals("1985-04-12", EmailContactUtils.normalizeBirthday("1985-04-12"));
    assertEquals("1985-04-12", EmailContactUtils.normalizeBirthday("19850412"));
    assertEquals("--04-12", EmailContactUtils.normalizeBirthday("--04-12"));
    assertEquals("--04-12", EmailContactUtils.normalizeBirthday("--0412"));
    assertEquals("--04-12", EmailContactUtils.normalizeBirthday("04-12"));
    // Leap day without a year is a real birthday.
    assertEquals("--02-29", EmailContactUtils.normalizeBirthday("--02-29"));
  }

  @Test
  void whatIsNotADateIsNotABirthday() {
    assertNull(EmailContactUtils.normalizeBirthday("next tuesday"));
    assertNull(EmailContactUtils.normalizeBirthday("1985-13-01"));
    assertNull(EmailContactUtils.normalizeBirthday("--02-30"));
    assertNull(EmailContactUtils.normalizeBirthday("1985"));
    assertNull(EmailContactUtils.normalizeBirthday("  "));
    assertNull(EmailContactUtils.normalizeBirthday(null));
  }

  @Test
  void aNoteIsTrimmedAndCappedAtTheStoreLength() {
    assertEquals("kept", EmailContactUtils.truncateNote("  kept  "));
    assertNull(EmailContactUtils.truncateNote("   "));
    assertNull(EmailContactUtils.truncateNote(null));
    assertEquals(EmailContactUtils.MAX_NOTE_LENGTH,
                 EmailContactUtils.truncateNote("x".repeat(EmailContactUtils.MAX_NOTE_LENGTH + 1)).length());
  }
}
