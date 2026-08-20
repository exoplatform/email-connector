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

  /** The thirteen non-array components, in the order equals compares them. */
  private static final Object[] BASELINE = { "uid-1", "Ada Lovelace", "Ada", "Lovelace", List.of("ada@example.org"),
      List.of("+33 1 23 45 67 89"), "eXo", "Engineer", "1815-12-10",
      new PostalAddress("1 Rue", "Paris", "IDF", "75001", "FR"), "a note", "https://example.org", "image/png" };

  /** A value of the right type that differs from the baseline's, per component. */
  private static final Object[] VARIED = { "uid-2", "Grace Hopper", "Grace", "Hopper", List.of("grace@example.org"),
      List.of("+1 202 555 0100"), "Meeds", "Admiral", "1906-12-09",
      new PostalAddress("2 Street", "Arlington", "VA", "22201", "US"), "another note", "https://example.com",
      "image/jpeg" };

  private static ParsedVCard card(byte[] photo) {
    return card(photo, -1);
  }

  /**
   * The baseline card, optionally with one component replaced.
   *
   * @param photo the picture bytes
   * @param varyAt the index in {@link #BASELINE} to replace, or -1 for none
   * @return the card
   */
  private static ParsedVCard card(byte[] photo, int varyAt) {
    Object[] v = BASELINE.clone();
    if (varyAt >= 0) {
      v[varyAt] = VARIED[varyAt];
    }
    return new ParsedVCard((String) v[0],
                           (String) v[1],
                           (String) v[2],
                           (String) v[3],
                           castList(v[4]),
                           castList(v[5]),
                           (String) v[6],
                           (String) v[7],
                           (String) v[8],
                           (PostalAddress) v[9],
                           (String) v[10],
                           (String) v[11],
                           photo,
                           (String) v[12]);
  }

  @SuppressWarnings("unchecked")
  private static List<String> castList(Object o) {
    return (List<String>) o;
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

  /**
   * A difference in any single component makes the cards unequal.
   * <p>
   * One case per component, which is also what walks the whole short-circuiting
   * chain in equals: a difference at position N leaves every earlier comparison
   * true and takes the false branch at N.
   */
  @Test
  void anyOneDifferingComponentMakesThemUnequal() {
    byte[] photo = { 7, 7 };
    ParsedVCard baseline = card(photo);
    for (int i = 0; i < BASELINE.length; i++) {
      assertNotEquals(baseline, card(photo, i), "component " + i + " is part of the value");
    }
  }
}
