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
package org.exoplatform.emailConnector.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The record's hand-written value members. A record compares an array component by
 * reference, so two readings of the same logo would never match — and "has this
 * signature changed" is exactly that comparison.
 */
class EmailSignatureLogoTest {

  private static final byte[] IMAGE = { 1, 2, 3 };

  private static EmailSignatureLogo logo(byte[] bytes, String mimeType, String fileName) {
    return new EmailSignatureLogo(bytes, mimeType, fileName);
  }

  @Test
  void twoLogosHoldingTheSameImageAreEqual() {
    EmailSignatureLogo one = logo(new byte[] { 1, 2, 3 }, "image/png", "logo.png");
    EmailSignatureLogo other = logo(new byte[] { 1, 2, 3 }, "image/png", "logo.png");

    assertEquals(one, other, "the same image read twice must compare equal");
    assertEquals(one.hashCode(), other.hashCode(), "equal logos must hash alike");
  }

  @Test
  void aDifferentImageIsADifferentLogo() {
    assertNotEquals(logo(IMAGE, "image/png", "logo.png"), logo(new byte[] { 9, 9, 9 }, "image/png", "logo.png"));
  }

  @Test
  void eachValueMemberIsCompared() {
    EmailSignatureLogo baseline = logo(IMAGE, "image/png", "logo.png");

    assertNotEquals(baseline, logo(IMAGE, "image/jpeg", "logo.png"), "the mime type is part of the value");
    assertNotEquals(baseline, logo(IMAGE, "image/png", "other.png"), "the file name is part of the value");
  }

  @Test
  void aLogoEqualsItselfAndNothingOfAnotherType() {
    EmailSignatureLogo one = logo(IMAGE, "image/png", "logo.png");

    assertEquals(one, one);
    assertFalse(one.equals("logo.png"), "a logo is not the string of its name");
  }

  @Test
  void anAbsentImageIsHandled() {
    EmailSignatureLogo none = logo(null, "image/png", "logo.png");

    assertEquals(none, logo(null, "image/png", "logo.png"));
    assertNotEquals(none, logo(IMAGE, "image/png", "logo.png"));
    assertTrue(none.toString().contains("none"), "an absent image reads as none, not as null bytes");
  }

  @Test
  void toStringRendersTheImageAsItsSize() {
    String rendered = logo(IMAGE, "image/png", "logo.png").toString();

    assertTrue(rendered.contains("3 bytes"), "the image is rendered as its size, so a log line stays readable");
    assertTrue(rendered.contains("image/png"), "the mime type is rendered");
    assertTrue(rendered.contains("logo.png"), "the file name is rendered");
    assertFalse(rendered.contains("[B@"), "the raw array reference must not leak into a log line");
  }
}
