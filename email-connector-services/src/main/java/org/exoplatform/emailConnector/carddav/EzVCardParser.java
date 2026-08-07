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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import ezvcard.VCard;
import ezvcard.parameter.ImageType;
import ezvcard.property.Email;
import ezvcard.property.Photo;
import ezvcard.property.StructuredName;
import ezvcard.property.Telephone;

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
    } catch (Exception e) {
      // A vCard we cannot read is one contact skipped, never a failed sync: an
      // address book of a thousand entries must not be held hostage by one of them.
      LOG.debug("A vCard could not be parsed and was skipped", e);
      return null;
    }
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
