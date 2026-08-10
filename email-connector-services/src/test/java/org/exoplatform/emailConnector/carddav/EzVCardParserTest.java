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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import org.exoplatform.emailConnector.model.PostalAddress;

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
    // The CELL type survives the read as the store's type,value entry -- it
    // used to be dropped here, which is what flattened every imported number.
    assertEquals("cell,+33 6 12 34 56 78", parsed.phones().get(0));
    assertEquals("Acme", parsed.organization());
    assertEquals("Head of Everything", parsed.title());
  }

  @Test
  void phoneTypesReadAsTheStoreVocabularyAndOnlyIt() {
    // Four TELs, four cases: a multi-typed line resolves to the best-known
    // type (CELL wins over VOICE), a WORK line keeps work, a line typed only
    // in words the store does not name stays a bare number, and an untyped
    // line stays bare too -- no invented vocabulary either way.
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        TEL;TYPE=CELL,VOICE:+33 6 12 34 56 78
        TEL;TYPE=WORK:+33 1 23 45 67 89
        TEL;TYPE=VOICE:+33 2 11 22 33 44
        TEL:+33 3 55 66 77 88
        END:VCARD""");

    assertEquals(List.of("cell,+33 6 12 34 56 78",
                         "work,+33 1 23 45 67 89",
                         "+33 2 11 22 33 44",
                         "+33 3 55 66 77 88"),
                 parsed.phones());
  }

  @Test
  void aTypedPhoneEntryExportsItsTypeParameter() {
    // The write side of the same promise: a work,value entry leaves as
    // TEL;TYPE=WORK -- the vocabulary another address book actually reads.
    ParsedVCard card = new ParsedVCard(null, "Jane Doe", "Jane", "Doe",
                                       List.of("jane@example.com"),
                                       List.of("work,+33 1 23 45 67 89", "+33 6 12 34 56 78"),
                                       null, null, null, null, null, null, null, null);

    String vcf = parser.format(card);

    assertTrue(vcf.contains("TEL;TYPE=WORK:+33 1 23 45 67 89") || vcf.contains("TEL;TYPE=work:+33 1 23 45 67 89"));
    assertTrue(vcf.contains("TEL:+33 6 12 34 56 78"));
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
  void aBirthdayWithAYearReadsAsAFullDate() {
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        BDAY:1985-04-12
        END:VCARD""");

    assertEquals("1985-04-12", parsed.birthday());
  }

  @Test
  void aBirthdayWithoutAYearIsHeldWithoutInventingOne() {
    // --MMDD is legal vCard and common: people know birthdays, not birth years.
    // Storing this as a date would force a fake year onto it, which is exactly
    // the corruption the canonical --MM-DD form exists to avoid.
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        BDAY:--0412
        END:VCARD""");

    assertEquals("--04-12", parsed.birthday());
  }

  @Test
  void aStructuredAddressKeepsItsComponentsApart() {
    // The reason the store holds the address structured: these components must
    // come back out as themselves, not as one long street line.
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        ADR;TYPE=HOME:;;12 rue de la Paix;Paris;Île-de-France;75002;France
        END:VCARD""");

    assertEquals("12 rue de la Paix", parsed.address().street());
    assertEquals("Paris", parsed.address().city());
    assertEquals("Île-de-France", parsed.address().region());
    assertEquals("75002", parsed.address().postalCode());
    assertEquals("France", parsed.address().country());
  }

  @Test
  void aMultiLineNoteKeepsItsLineBreaks() {
    // vCard escapes newlines as \n inside the value; a note is prose and its
    // paragraphs are part of what the user wrote.
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        NOTE:Met at FOSDEM.\\nPrefers email over phone.
        END:VCARD""");

    assertEquals("Met at FOSDEM.\nPrefers email over phone.", parsed.note());
  }

  @Test
  void aWebsiteReadsAsItsUrl() {
    ParsedVCard parsed = parser.parse("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        URL:https://janedoe.example
        END:VCARD""");

    assertEquals("https://janedoe.example", parsed.website());
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

  @Test
  void aGmailExportOfSeveralCardsDeliversThemAll() throws IOException {
    // The shape google.com/contacts writes: 3.0, grouped item properties, a
    // categories line — and, decisively, MANY cards in one file. The old
    // single-card read took the first and silently dropped the rest.
    List<ParsedVCard> cards = parseAll("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Jane Doe
        N:Doe;Jane;;;
        EMAIL;TYPE=INTERNET:jane@example.com
        TEL;TYPE=CELL:+33612345678
        ORG:Acme
        CATEGORIES:myContacts
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:John Roe
        N:Roe;John;;;
        item1.EMAIL;TYPE=INTERNET:john@example.com
        item1.X-ABLabel:Work
        CATEGORIES:myContacts,starred
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:Phone Only
        N:Only;Phone;;;
        TEL;TYPE=HOME:+33199999999
        CATEGORIES:myContacts
        END:VCARD""");

    assertEquals(3, cards.size());
    assertEquals("jane@example.com", cards.get(0).emails().get(0));
    assertEquals("john@example.com", cards.get(1).emails().get(0));
    assertTrue(cards.get(2).emails().isEmpty());
  }

  @Test
  void anOutlookExportReadsItsQuotedPrintableNames() throws IOException {
    // Outlook still writes vCard 2.1 with quoted-printable accents — the
    // encoding that mangles every hand-written parser first.
    List<ParsedVCard> cards = parseAll("""
        BEGIN:VCARD
        VERSION:2.1
        N;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:Deguerry;Am=C3=A9lie
        FN;ENCODING=QUOTED-PRINTABLE;CHARSET=UTF-8:Am=C3=A9lie Deguerry
        EMAIL;PREF;INTERNET:amelie@example.fr
        TEL;WORK;VOICE:+33 1 23 45 67 89
        ORG:Exemple SARL
        TITLE:Directrice
        END:VCARD""");

    assertEquals(1, cards.size());
    assertEquals("Amélie Deguerry", cards.get(0).formattedName());
    assertEquals("Amélie", cards.get(0).givenName());
    assertEquals("amelie@example.fr", cards.get(0).emails().get(0));
    assertEquals("Exemple SARL", cards.get(0).organization());
  }

  @Test
  void anICloudExportKeepsItsPhotoAndItsSecondAddress() throws IOException {
    // iCloud's dialect: 3.0, item-grouped addresses, an inline base64 photo.
    List<ParsedVCard> cards = parseAll("""
        BEGIN:VCARD
        VERSION:3.0
        PRODID:-//Apple Inc.//iCloud Web Address Book 2312B29//EN
        N:Doe;Jane;;;
        FN:Jane Doe
        item1.EMAIL;type=INTERNET;type=pref:jane@icloud.example
        item2.EMAIL;type=INTERNET:jane.work@example.com
        TEL;type=CELL;type=VOICE:+1 555 0100
        PHOTO;ENCODING=b;TYPE=JPEG:/9j/4AAQSkZJRgABAQAAAQABAAD/2wBDAAgGBgcGBQgHBwcJ
         CQgKDBQNDAsLDBkSEw8UHRofHh0aHBwgJC4nICIsIxwcKDcpLDAxNDQ0Hyc5PTgyPC4zNDL/2wBD
        END:VCARD""");

    assertEquals(1, cards.size());
    assertEquals("jane@icloud.example", cards.get(0).emails().get(0));
    assertEquals(2, cards.get(0).emails().size());
    assertNotNull(cards.get(0).photo());
    assertTrue(cards.get(0).photo().length > 0);
  }

  @Test
  void garbageBetweenTwoCardsLosesNeitherOfThem() throws IOException {
    List<ParsedVCard> cards = parseAll("""
        BEGIN:VCARD
        VERSION:3.0
        FN:First Person
        EMAIL:first@example.com
        END:VCARD
        this line is not vCard syntax at all &%$
        neither: is; this one
        BEGIN:VCARD
        VERSION:3.0
        FN:Second Person
        EMAIL:second@example.com
        END:VCARD""");

    assertEquals(2, cards.size());
    assertEquals("first@example.com", cards.get(0).emails().get(0));
    assertEquals("second@example.com", cards.get(1).emails().get(0));
  }

  @Test
  void aTruncatedFileKeepsTheCardsBeforeTheCut() throws IOException {
    // A download cut mid-transfer: whatever ended cleanly must land, and the
    // ragged tail must not throw the whole import away.
    List<ParsedVCard> cards = parseAll("""
        BEGIN:VCARD
        VERSION:3.0
        FN:Whole Card
        EMAIL:whole@example.com
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:Cut Card
        EMA""");

    assertTrue(cards.size() >= 1);
    assertEquals("whole@example.com", cards.get(0).emails().get(0));
  }

  @Test
  void aSinkSayingStopStopsTheReader() throws IOException {
    // The import's card cap rides on this: the reader must not walk the rest of
    // a hostile file once the sink has had enough.
    List<ParsedVCard> cards = new ArrayList<>();
    parser.parseAll(new StringReader("""
        BEGIN:VCARD
        VERSION:3.0
        FN:One
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:Two
        END:VCARD
        BEGIN:VCARD
        VERSION:3.0
        FN:Three
        END:VCARD"""), card -> {
      cards.add(card);
      return cards.size() < 2;
    });

    assertEquals(2, cards.size());
  }

  @Test
  void formattingThenParsingGivesTheSameContactBack() {
    // The round trip is the export's whole promise: what leaves as a .vcf must
    // come back as the same contact, preferred address still first.
    ParsedVCard original = new ParsedVCard("urn:uuid:98765",
                                           "Jane Doe",
                                           "Jane",
                                           "Doe",
                                           List.of("jane@personal.example", "jane.work@example.com"),
                                           // One typed entry and one bare number: the trip must keep the
                                           // work type AND must not invent one for the untyped line.
                                           List.of("work,+33 6 12 34 56 78", "+33 1 23 45 67 89"),
                                           "Acme",
                                           "Head of Everything",
                                           "1985-04-12",
                                           new PostalAddress("12 rue de la Paix", "Paris", "Île-de-France", "75002", "France"),
                                           "Met at FOSDEM.\nPrefers email over phone.",
                                           "https://janedoe.example",
                                           new byte[] { (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0 },
                                           "image/jpeg");

    ParsedVCard reparsed = parser.parse(parser.format(original));

    assertEquals(original.uid(), reparsed.uid());
    assertEquals(original.formattedName(), reparsed.formattedName());
    assertEquals(original.givenName(), reparsed.givenName());
    assertEquals(original.familyName(), reparsed.familyName());
    assertEquals(original.emails(), reparsed.emails());
    assertEquals(original.phones(), reparsed.phones());
    assertEquals(original.organization(), reparsed.organization());
    assertEquals(original.title(), reparsed.title());
    assertEquals(original.birthday(), reparsed.birthday());
    assertEquals(original.address(), reparsed.address());
    assertEquals(original.note(), reparsed.note());
    assertEquals(original.website(), reparsed.website());
    assertArrayEquals(original.photo(), reparsed.photo());
    assertEquals(original.photoMimeType(), reparsed.photoMimeType());
  }

  @Test
  void aYearlessBirthdaySurvivesTheRoundTripWithoutInventingAYear() {
    // The one corruption this field must never commit: a birthday given as
    // "April 12th" leaving as April 12th of some year.
    ParsedVCard original = new ParsedVCard(null,
                                           "Jane Doe",
                                           null,
                                           null,
                                           List.of("jane@example.com"),
                                           List.of(),
                                           null,
                                           null,
                                           "--04-12",
                                           null,
                                           null,
                                           null,
                                           null,
                                           null);

    ParsedVCard reparsed = parser.parse(parser.format(original));

    assertEquals("--04-12", reparsed.birthday());
  }

  @Test
  void aFormattedCardAlwaysCarriesBothNames() {
    // FN and N are 3.0 requirements, and importers that miss either invent a
    // name — an address-only contact still needs both lines present.
    String formatted = parser.format(new ParsedVCard(null,
                                                     null,
                                                     null,
                                                     null,
                                                     List.of("nameless@example.com"),
                                                     List.of(),
                                                     null,
                                                     null,
                                                     null,
                                                     null,
                                                     null,
                                                     null,
                                                     null,
                                                     null));

    assertTrue(formatted.contains("FN:nameless@example.com"));
    assertTrue(formatted.contains("N:"));
    assertTrue(formatted.contains("BEGIN:VCARD"));
    assertTrue(formatted.contains("VERSION:3.0"));
    assertTrue(formatted.contains("END:VCARD"));
  }

  /**
   * Streams a whole text through the multi-card read, collecting every card.
   *
   * @param vcards the raw text
   * @return the delivered cards, in file order
   * @throws IOException never, from a string
   */
  private List<ParsedVCard> parseAll(String vcards) throws IOException {
    List<ParsedVCard> cards = new ArrayList<>();
    parser.parseAll(new StringReader(vcards), card -> {
      cards.add(card);
      return true;
    });
    return cards;
  }
}
