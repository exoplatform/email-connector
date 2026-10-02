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

package org.exoplatform.emailConnector.dao;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import org.exoplatform.emailConnector.entity.EmailContactAddressEntity;

/**
 * The addresses contacts can be reached at. Small on purpose: this table exists
 * to answer one question quickly — which contact, if any, owns this address —
 * and to make de-duplication a database fact through its unique index.
 */
@Repository
public interface EmailContactAddressDAO extends JpaRepository<EmailContactAddressEntity, Long> {

  /**
   * The row claiming this address for this user, if any.
   *
   * @param userId the store owner
   * @param address the lower-cased address
   * @return the row, or empty
   */
  Optional<EmailContactAddressEntity> findByUserIdAndAddress(String userId, String address);

  /**
   * Every address of one contact.
   *
   * @param contactId the contact
   * @return the rows, never null
   */
  List<EmailContactAddressEntity> findByContactId(Long contactId);

  /**
   * Drops every address of a contact, so a save can write the current set.
   *
   * @param contactId the contact
   */
  void deleteByContactId(Long contactId);

  /**
   * The contacts of one user's own store that carry a picture, for a set of the
   * addresses they can be reached at: the mail's avatars after the platform
   * profile (EXO-90908).
   * <p>
   * Both rows are held to the owner -- the address row, whose owner is
   * denormalised, and the contact it points at -- so no address can name a
   * contact of another store. A suppressed contact is not shown anywhere, and a
   * directory row's picture is the platform's, never a stored one. An address
   * names at most one contact of a store ({@code UQ_EMAIL_CONTACT_ADDRESS} on
   * {@code USER_ID, ADDRESS}), so the answer holds one row per address at most;
   * the ordering only makes the answer stable. Unpaged: the caller bounds the
   * addresses (50 a request), and so the rows.
   *
   * @param userId the store owner, the viewing user
   * @param addresses the lower-cased addresses, never null nor empty -- the caller
   *          short-circuits an empty set rather than emitting {@code IN ()}
   * @param directorySource the directory rows' source, left out
   * @return {@code [address, contactId, updatedDate, photoFileId]} per address
   *         that names a contact with a picture, ordered by address then contact
   */
  @Query("SELECT a.address, c.id, c.updatedDate, c.photoFileId FROM EmailContactAddressEntity a, EmailContactEntity c"
      + " WHERE a.userId = :userId AND a.address IN :addresses AND c.id = a.contactId AND c.userId = :userId"
      + " AND c.suppressed = false AND c.photoFileId IS NOT NULL AND c.photoFileId > 0 AND c.source <> :directorySource"
      + " ORDER BY a.address, c.id")
  List<Object[]> findPhotoContactsByAddresses(@Param("userId")
  String userId, @Param("addresses")
  Collection<String> addresses, @Param("directorySource")
  String directorySource);
}
