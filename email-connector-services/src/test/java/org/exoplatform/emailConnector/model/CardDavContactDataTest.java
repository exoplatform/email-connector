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
package org.exoplatform.emailConnector.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The record's hand-written value members — same reason as {@code ParsedVCardTest}:
 * the picture is an array, which a record would compare by reference.
 */
class CardDavContactDataTest {

  private static CardDavContactData contact(byte[] photo) {
    return new CardDavContactData("ada@example.org",
                                  List.of("other,ada@work.example.org"),
                                  "Ada Lovelace",
                                  "Ada",
                                  "Lovelace",
                                  List.of("+33 1 23 45 67 89"),
                                  "eXo",
                                  "Engineer",
                                  "1815-12-10",
                                  new PostalAddress("1 Rue", "Paris", "IDF", "75001", "FR"),
                                  "a note",
                                  "https://example.org",
                                  "vcard-uid-1",
                                  photo,
                                  "image/png");
  }

  @Test
  void twoReadingsOfTheSameEntryAreEqual() {
    CardDavContactData one = contact(new byte[] { 4, 5 });
    CardDavContactData other = contact(new byte[] { 4, 5 });
    assertEquals(one, other, "the same entry read twice must be equal");
    assertEquals(one.hashCode(), other.hashCode(), "equal entries must hash alike");
  }

  @Test
  void aDifferentPictureMakesADifferentEntry() {
    assertNotEquals(contact(new byte[] { 4, 5 }), contact(new byte[] { 4 }), "the photo bytes are part of the value");
    assertNotEquals(contact(new byte[] { 4 }), contact(null), "an entry that lost its picture is not the same entry");
  }

  @Test
  void equalsHandlesItselfAndForeignTypes() {
    CardDavContactData one = contact(null);
    assertEquals(one, one, "an entry equals itself");
    assertFalse(one.equals(42), "an entry never equals another type");
  }

  @Test
  void toStringGivesThePictureSizeRatherThanItsBytes() {
    assertTrue(contact(new byte[] { 4, 5 }).toString().contains("photo=2 bytes"), "the size, not the bytes");
    assertTrue(contact(null).toString().contains("photo=none"), "no picture reads as none");
    assertTrue(contact(null).toString().contains("primaryEmail=ada@example.org"), "the scalar components are still there");
  }
}
