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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The vCard corpus. Every case here is something a real address book emits —
 * these are the reasons the add-on takes a parsing library rather than reading
 * the format itself.
 */
public class EzVCardParserTest {

  private final EzVCardParser parser = new EzVCardParser();

  @Test
  void aPlainVCardReadsAsItLooks() {
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        UID:urn:uuid:12345
        N:Doe;Jane;;;
        FN:Jane Doe
        EMAIL;TYPE=INTERNET:jane@example.com
        TEL;TYPE=CELL:+33 6 12 34 56 78
        ORG:Acme
        TITLE:Head of Everything
        END:VCARD""");

    assertEquals("urn:uuid:12345", parsed.uid());
    assertEquals("Jane Doe", parsed.formattedName());
    assertEquals("Jane", parsed.givenName());
    assertEquals("Doe", parsed.familyName());
    assertEquals("jane@example.com", parsed.emails().get(0));
    assertEquals("+33 6 12 34 56 78", parsed.phones().get(0));
    assertEquals("Acme", parsed.organization());
    assertEquals("Head of Everything", parsed.title());
  }

  @Test
  void thePreferredAddressComesFirstWhateverOrderItWasWrittenIn() {
    // This decides which address the contact is keyed on, so it is not cosmetic:
    // a vCard that states a preference must not be filed under the other one.
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        EMAIL;TYPE=INTERNET:jane.work@example.com
        EMAIL;TYPE=INTERNET,PREF:jane@personal.example
        END:VCARD""");

    assertEquals("jane@personal.example", parsed.emails().get(0));
    assertEquals(2, parsed.emails().size());
  }

  @Test
  void aFoldedLineIsOneValue() {
    // Line folding is in the spec and every exporter uses it past 75 characters.
    // Read naively, this address arrives cut in half.
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Someone With A Very Long Name Indeed
        EMAIL:someone.with.a.rather.long.address@a-fairly-long-domain-na
         me.example.com
        END:VCARD""");

    assertEquals("someone.with.a.rather.long.address@a-fairly-long-domain-name.example.com", parsed.emails().get(0));
  }

  @Test
  void escapedSeparatorsSurviveInsideAValue() {
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Doe\\, Jane
        ORG:Acme\\; Subsidiary
        END:VCARD""");

    assertEquals("Doe, Jane", parsed.formattedName());
    assertEquals("Acme; Subsidiary", parsed.organization());
  }

  @Test
  void anEmbeddedPhotoComesBackAsBytes() {
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJ
         CQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBD
        END:VCARD""");

    assertNotNull(parsed.photo());
    assertTrue(parsed.photo().length > 0);
    assertEquals("image/jpeg", parsed.photoMimeType());
  }

  @Test
  void aVCardWithoutAnAddressStillParses() {
    // The sync skips it, but that is the sync's decision to make — the parser
    // reporting failure here would be indistinguishable from a broken vCard.
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Nobody In Particular
        END:VCARD""");

    assertNotNull(parsed);
    assertTrue(parsed.emails().isEmpty());
  }

  @Test
  void aNameGivenOnlyAsFullNameIsNotInvented() {
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Cher
        EMAIL:cher@example.com
        END:VCARD""");

    assertEquals("Cher", parsed.formattedName());
    assertNull(parsed.givenName());
    assertNull(parsed.familyName());
  }

  @Test
  void unusableTextIsSkippedRatherThanThrown() {
    // One unreadable entry must never fail a sync of a thousand contacts.
    assertNull(parser.parse("this is not a vCard at all"));
    assertNull(parser.parse(""));
    assertNull(parser.parse(null));
  }
}
