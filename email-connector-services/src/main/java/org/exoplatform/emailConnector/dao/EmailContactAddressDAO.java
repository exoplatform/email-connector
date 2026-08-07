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

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
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
}
