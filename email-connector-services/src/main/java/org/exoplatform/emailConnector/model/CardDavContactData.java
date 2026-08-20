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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * One address-book entry, in the terms the store keeps rather than the terms
 * vCard speaks. The protocol layer parses; this is what it hands over, so nothing
 * below the service knows what a vCard is.
 *
 * @param primaryEmail the normalized address the contact is keyed on
 * @param secondaryEmails the other addresses, already normalized
 * @param displayName the name to show
 * @param givenName the first name, when the entry separates them
 * @param familyName the last name, when the entry separates them
 * @param phones the phone numbers
 * @param organization the company
 * @param title the job title
 * @param birthday the birthday in the store's canonical text (YYYY-MM-DD, or
 *          --MM-DD when the entry states no year)
 * @param address the structured postal address, or null
 * @param note the free-text note, already capped to the store's length
 * @param website the entry's web page
 * @param vcardUid the entry's own identity on the server
 * @param photo the picture bytes, or null when the entry carries none
 * @param photoMimeType the picture's type
 */
public record CardDavContactData(String primaryEmail,
                                 List<String> secondaryEmails,
                                 String displayName,
                                 String givenName,
                                 String familyName,
                                 List<String> phones,
                                 String organization,
                                 String title,
                                 String birthday,
                                 PostalAddress address,
                                 String note,
                                 String website,
                                 String vcardUid,
                                 byte[] photo,
                                 String photoMimeType) {

  /**
   * Value equality that compares the picture by its bytes.
   * <p>
   * A record's generated members compare {@code photo} by reference, so two
   * readings of the same card would never be equal — which is the comparison
   * the sync's "has this entry changed" logic actually needs.
   *
   * @param o the object to compare with
   * @return whether both carry the same values
   */
  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof CardDavContactData other)) {
      return false;
    }
    return Objects.equals(primaryEmail, other.primaryEmail())
           && Objects.equals(secondaryEmails, other.secondaryEmails())
           && Objects.equals(displayName, other.displayName())
           && Objects.equals(givenName, other.givenName())
           && Objects.equals(familyName, other.familyName())
           && Objects.equals(phones, other.phones())
           && Objects.equals(organization, other.organization())
           && Objects.equals(title, other.title())
           && Objects.equals(birthday, other.birthday())
           && Objects.equals(address, other.address())
           && Objects.equals(note, other.note())
           && Objects.equals(website, other.website())
           && Objects.equals(vcardUid, other.vcardUid())
           && Objects.equals(photoMimeType, other.photoMimeType())
           && Arrays.equals(photo, other.photo());
  }

  /**
   * @return a hash consistent with {@link #equals(Object)}, the picture hashed by content
   */
  @Override
  public int hashCode() {
    return 31 * Objects.hash(primaryEmail,
                             secondaryEmails,
                             displayName,
                             givenName,
                             familyName,
                             phones,
                             organization,
                             title,
                             birthday,
                             address,
                             note,
                             website,
                             vcardUid,
                             photoMimeType) + Arrays.hashCode(photo);
  }

  /**
   * @return the values, the picture rendered as its size so a log line stays readable
   */
  @Override
  public String toString() {
    return "CardDavContactData[primaryEmail=" + primaryEmail
        + ", secondaryEmails=" + secondaryEmails
        + ", displayName=" + displayName
        + ", givenName=" + givenName
        + ", familyName=" + familyName
        + ", phones=" + phones
        + ", organization=" + organization
        + ", title=" + title
        + ", birthday=" + birthday
        + ", address=" + address
        + ", note=" + note
        + ", website=" + website
        + ", vcardUid=" + vcardUid
        + ", photoMimeType=" + photoMimeType
        + ", photo=" + (photo == null ? "none" : photo.length + " bytes")
        + "]";
  }
}
