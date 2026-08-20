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
package org.exoplatform.emailConnector.carddav;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.PostalAddress;

/**
 * The record's hand-written value members. A record compares an array component
 * by reference, so two readings of the same card would never match — and the
 * sync's "has this entry changed" decision is exactly that comparison.
 */
class ParsedVCardTest {

  private static ParsedVCard card(byte[] photo) {
    return new ParsedVCard("uid-1",
                           "Ada Lovelace",
                           "Ada",
                           "Lovelace",
                           List.of("ada@example.org"),
                           List.of("+33 1 23 45 67 89"),
                           "eXo",
                           "Engineer",
                           "1815-12-10",
                           new PostalAddress("1 Rue", "Paris", "IDF", "75001", "FR"),
                           "a note",
                           "https://example.org",
                           photo,
                           "image/png");
  }

  @Test
  void twoReadingsOfTheSameCardAreEqual() {
    ParsedVCard one = card(new byte[] { 1, 2, 3 });
    ParsedVCard other = card(new byte[] { 1, 2, 3 });
    assertEquals(one, other, "the same card read twice must be equal, distinct photo arrays and all");
    assertEquals(one.hashCode(), other.hashCode(), "equal cards must hash alike");
  }

  @Test
  void aDifferentPictureMakesADifferentCard() {
    assertNotEquals(card(new byte[] { 1, 2, 3 }), card(new byte[] { 9 }), "the photo bytes are part of the value");
    assertNotEquals(card(new byte[] { 1 }), card(null), "a card that lost its picture is not the same card");
  }

  @Test
  void equalsHandlesItselfAndForeignTypes() {
    ParsedVCard one = card(new byte[] { 1 });
    assertEquals(one, one, "a card equals itself");
    assertFalse(one.equals("not a card"), "a card never equals another type");
  }

  @Test
  void toStringGivesThePictureSizeRatherThanItsBytes() {
    assertTrue(card(new byte[] { 1, 2, 3 }).toString().contains("photo=3 bytes"), "the size, not the bytes");
    assertTrue(card(null).toString().contains("photo=none"), "no picture reads as none");
    assertTrue(card(null).toString().contains("uid=uid-1"), "the scalar components are still there");
  }
}
