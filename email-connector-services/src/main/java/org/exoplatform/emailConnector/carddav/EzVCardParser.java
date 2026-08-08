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

import java.io.IOException;
import java.io.Reader;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import ezvcard.VCard;
import ezvcard.VCardVersion;
import ezvcard.io.text.VCardReader;
import ezvcard.io.text.VCardWriter;
import ezvcard.parameter.EmailType;
import ezvcard.parameter.ImageType;
import ezvcard.property.Email;
import ezvcard.property.Photo;
import ezvcard.property.StructuredName;
import ezvcard.property.Telephone;
import ezvcard.property.Uid;

import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

/**
 * The vCard reader, on top of ez-vcard.
 * <p>
 * A library rather than our own parsing because vCard in the wild is hostile:
 * folded lines, vCard 2.1 quoted-printable from old Outlook exports, charset
 * parameters, three photo encodings across 2.1/3.0/4.0, escaped separators. A
 * hand-written parser mangles real Gmail and iCloud exports, and those failures
 * would look like sync bugs rather than parsing ones.
 * <p>
 * Everything ez-vcard knows stays behind {@link VCardParser}: no other class in
 * the add-on imports the library, so replacing it stays a one-file change.
 */
@Component
public class EzVCardParser implements VCardParser {

  private static final Log LOG = ExoLogger.getLogger(EzVCardParser.class);

  @Override
  public ParsedVCard parse(String vcardText) {
    if (StringUtils.isBlank(vcardText)) {
      return null;
    }
    try {
      VCard vcard = ezvcard.Ezvcard.parse(vcardText).first();
      if (vcard == null) {
        return null;
      }
      return toParsed(vcard);
    } catch (Exception e) {
      // A vCard we cannot read is one contact skipped, never a failed sync: an
      // address book of a thousand entries must not be held hostage by one of them.
      LOG.debug("A vCard could not be parsed and was skipped", e);
      return null;
    }
  }

  @Override
  public void parseAll(Reader vcards, VCardSink sink) throws IOException {
    // The library's own streaming reader: one card in memory at a time, however
    // large the file, which is what makes the import's size cap a real bound
    // rather than a hope.
    VCardReader reader = new VCardReader(vcards);
    VCard vcard;
    while ((vcard = reader.readNext()) != null) {
      boolean more;
      try {
        more = sink.accept(toParsed(vcard));
      } catch (Exception e) {
        // The card, not the file: whatever this card carried that the mapping
        // choked on, the next card deserves its chance.
        LOG.debug("A vCard could not be read and was reported unreadable", e);
        more = sink.acceptUnreadable();
      }
      if (!more) {
        return;
      }
    }
  }

  @Override
  public String format(ParsedVCard card) {
    VCard vcard = new VCard();
    if (StringUtils.isNotBlank(card.uid())) {
      vcard.setUid(new Uid(card.uid()));
    }
    // FN and N both, always: 3.0 requires them and every importer leans on one
    // or the other. A card with no name at all still gets its address as FN,
    // because an FN-less card makes Outlook invent "Untitled contact".
    String fallbackName = StringUtils.defaultIfBlank(card.formattedName(),
                                                     StringUtils.trimToNull(StringUtils.trimToEmpty(card.givenName()) + " "
                                                         + StringUtils.trimToEmpty(card.familyName())));
    vcard.setFormattedName(StringUtils.defaultIfBlank(fallbackName,
                                                      card.emails().isEmpty() ? "" : card.emails().get(0)));
    StructuredName structuredName = new StructuredName();
    structuredName.setGiven(card.givenName());
    structuredName.setFamily(card.familyName());
    vcard.setStructuredName(structuredName);
    boolean first = true;
    for (String address : card.emails()) {
      // The first address is the one the contact is keyed on, so the export
      // says so: TYPE=PREF is the 3.0 spelling, and it is exactly what
      // emailsOf() reads back — an exported store re-imports keyed the same.
      vcard.addEmail(address, first ? new EmailType[] { EmailType.INTERNET, EmailType.PREF }
                                    : new EmailType[] { EmailType.INTERNET });
      first = false;
    }
    for (String phone : card.phones()) {
      vcard.addTelephoneNumber(phone);
    }
    if (StringUtils.isNotBlank(card.organization())) {
      vcard.setOrganization(card.organization());
    }
    if (StringUtils.isNotBlank(card.title())) {
      vcard.addTitle(card.title());
    }
    if (card.photo() != null && card.photo().length > 0) {
      vcard.addPhoto(new Photo(card.photo(), ImageType.get(null, StringUtils.defaultIfBlank(card.photoMimeType(), "image/jpeg"), null)));
    }
    return write(vcard);
  }

  /**
   * Serializes one built card as 3.0 text — the writer half of the library,
   * behind the same seam as the reader.
   *
   * @param vcard the built card
   * @return the vCard text
   */
  private String write(VCard vcard) {
    StringWriter out = new StringWriter();
    try (VCardWriter writer = new VCardWriter(out, VCardVersion.V3_0)) {
      writer.write(vcard);
    } catch (IOException e) {
      // A StringWriter cannot actually fail; the writer's contract says it may.
      throw new IllegalStateException("A vCard could not be written", e);
    }
    return out.toString();
  }

  /**
   * The library's card reduced to the fields a contact row keeps — the one
   * mapping both the single-card and the streaming reads go through.
   *
   * @param vcard the parsed card
   * @return the reduced fields
   */
  private ParsedVCard toParsed(VCard vcard) {
    StructuredName structuredName = vcard.getStructuredName();
    Photo photo = firstPhoto(vcard);
    return new ParsedVCard(vcard.getUid() == null ? null : StringUtils.trimToNull(vcard.getUid().getValue()),
                           vcard.getFormattedName() == null ? null
                                                            : StringUtils.trimToNull(vcard.getFormattedName().getValue()),
                           structuredName == null ? null : StringUtils.trimToNull(structuredName.getGiven()),
                           structuredName == null ? null : StringUtils.trimToNull(structuredName.getFamily()),
                           emailsOf(vcard),
                           phonesOf(vcard),
                           vcard.getOrganization() == null || vcard.getOrganization().getValues().isEmpty() ? null
                                                                                                           : StringUtils.trimToNull(vcard.getOrganization()
                                                                                                                                         .getValues()
                                                                                                                                         .get(0)),
                           vcard.getTitles().isEmpty() ? null : StringUtils.trimToNull(vcard.getTitles().get(0).getValue()),
                           photo == null ? null : photo.getData(),
                           mimeTypeOf(photo));
  }

  /**
   * Every address in the vCard, the preferred one first.
   * <p>
   * Order matters beyond presentation: the first address becomes the contact's
   * key, so a vCard that marks a preference must not be keyed on whichever
   * address happened to be written first.
   *
   * @param vcard the parsed vCard
   * @return the addresses, never null
   */
  private List<String> emailsOf(VCard vcard) {
    List<Email> emails = new ArrayList<>(vcard.getEmails());
    emails.sort(Comparator.comparing(this::preferenceOf));
    List<String> values = new ArrayList<>();
    for (Email email : emails) {
      String value = StringUtils.trimToNull(email.getValue());
      if (value != null && !values.contains(value)) {
        values.add(value);
      }
    }
    return values;
  }

  /**
   * How strongly a vCard prefers an address, as a sort key where lower wins.
   * <p>
   * Two spellings exist and both are common: vCard 4.0's numeric PREF parameter,
   * and 3.0's TYPE=PREF flag. A vCard saying neither sorts after both.
   *
   * @param email the address property
   * @return the sort key
   */
  private int preferenceOf(Email email) {
    Integer pref = email.getPref();
    if (pref != null) {
      return pref;
    }
    return email.getTypes().stream().anyMatch(type -> "pref".equalsIgnoreCase(type.getValue())) ? 1 : Integer.MAX_VALUE;
  }

  /**
   * Every phone number, in the order the vCard lists them.
   *
   * @param vcard the parsed vCard
   * @return the numbers, never null
   */
  private List<String> phonesOf(VCard vcard) {
    List<String> values = new ArrayList<>();
    for (Telephone phone : vcard.getTelephoneNumbers()) {
      String value = StringUtils.trimToNull(phone.getText());
      if (value != null && !values.contains(value)) {
        values.add(value);
      }
    }
    return values;
  }

  /**
   * The first picture carried inside the vCard.
   * <p>
   * Only inline bytes count. A vCard may instead point at a URL, and fetching
   * that would mean an unbounded number of extra requests to wherever a contact's
   * vCard says — a sync must not become a crawler.
   *
   * @param vcard the parsed vCard
   * @return the photo, or null when there is none embedded
   */
  private Photo firstPhoto(VCard vcard) {
    return vcard.getPhotos().stream().filter(photo -> photo.getData() != null && photo.getData().length > 0).findFirst().orElse(null);
  }

  /**
   * The declared type of a picture, defaulting to JPEG.
   * <p>
   * A vCard photo without a declared type is overwhelmingly a JPEG, and every
   * browser sniffs the bytes anyway — but something has to be stored, and
   * guessing wrong here costs only a header.
   *
   * @param photo the photo property, may be null
   * @return the mime type, or null when there is no photo
   */
  private String mimeTypeOf(Photo photo) {
    if (photo == null) {
      return null;
    }
    ImageType type = photo.getContentType();
    return type == null || StringUtils.isBlank(type.getMediaType()) ? "image/jpeg" : type.getMediaType();
  }
}
