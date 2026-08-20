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

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

import org.exoplatform.emailConnector.model.PostalAddress;

/**
 * One vCard, reduced to what a contact row can hold. Everything else a vCard may
 * carry — instant-messaging handles, geo positions, arbitrary extensions — is
 * dropped here on purpose: the store has nowhere to put it, and carrying it
 * would invite a schema that mirrors vCard instead of serving the product.
 *
 * @param uid the vCard's own identity, kept so an entry that moves on the server
 *          can still be recognised
 * @param formattedName the name as the vCard presents it
 * @param givenName the first name, when the vCard separates them
 * @param familyName the last name, when the vCard separates them
 * @param emails every address, preferred first — the first is the one the
 *          contact is keyed on
 * @param phones every phone number, in vCard order
 * @param organization the company
 * @param title the job title
 * @param birthday the birthday in the store's canonical text — YYYY-MM-DD, or
 *          --MM-DD when the card carried no year (which vCard allows and Apple
 *          exports do)
 * @param address the first postal address the card carries, structured; a card
 *          may list several (home, work) and one is what the store holds, the
 *          preferred/first winning
 * @param note the first free-text note, capped at the store's note length
 * @param website the first URL the card carries
 * @param photo the picture bytes, or null; a vCard may instead point at a URL,
 *          which this deliberately does not fetch
 * @param photoMimeType the picture's type, when the vCard declared one
 */
public record ParsedVCard(String uid,
                          String formattedName,
                          String givenName,
                          String familyName,
                          List<String> emails,
                          List<String> phones,
                          String organization,
                          String title,
                          String birthday,
                          PostalAddress address,
                          String note,
                          String website,
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
    if (!(o instanceof ParsedVCard other)) {
      return false;
    }
    return Objects.equals(uid, other.uid())
           && Objects.equals(formattedName, other.formattedName())
           && Objects.equals(givenName, other.givenName())
           && Objects.equals(familyName, other.familyName())
           && Objects.equals(emails, other.emails())
           && Objects.equals(phones, other.phones())
           && Objects.equals(organization, other.organization())
           && Objects.equals(title, other.title())
           && Objects.equals(birthday, other.birthday())
           && Objects.equals(address, other.address())
           && Objects.equals(note, other.note())
           && Objects.equals(website, other.website())
           && Objects.equals(photoMimeType, other.photoMimeType())
           && Arrays.equals(photo, other.photo());
  }

  /**
   * @return a hash consistent with {@link #equals(Object)}, the picture hashed by content
   */
  @Override
  public int hashCode() {
    return 31 * Objects.hash(uid,
                             formattedName,
                             givenName,
                             familyName,
                             emails,
                             phones,
                             organization,
                             title,
                             birthday,
                             address,
                             note,
                             website,
                             photoMimeType) + Arrays.hashCode(photo);
  }

  /**
   * @return the values, the picture rendered as its size so a log line stays readable
   */
  @Override
  public String toString() {
    return "ParsedVCard[uid=" + uid
        + ", formattedName=" + formattedName
        + ", givenName=" + givenName
        + ", familyName=" + familyName
        + ", emails=" + emails
        + ", phones=" + phones
        + ", organization=" + organization
        + ", title=" + title
        + ", birthday=" + birthday
        + ", address=" + address
        + ", note=" + note
        + ", website=" + website
        + ", photoMimeType=" + photoMimeType
        + ", photo=" + (photo == null ? "none" : photo.length + " bytes")
        + "]";
  }
}
