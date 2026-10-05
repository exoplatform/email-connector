/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.dao;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.exoplatform.emailConnector.entity.EmailFilterProposalEntity;

/**
 * The tool calls mail filters' assistants recorded instead of running them (changeset
 * 1.0.0-95), read once more to move them to the AI add-on's shared proposals
 * (EXO-90956): nothing writes this table any more, which is dropped by a later release.
 */
public interface EmailFilterProposalDAO extends JpaRepository<EmailFilterProposalEntity, Long> {

  /**
   * A page of every user's proposals after an id, by id.
   *
   * @param afterId the id the page starts after
   * @param pageable the page size
   * @return the proposals
   */
  @Query("SELECT p FROM EmailFilterProposalEntity p WHERE p.id > :afterId ORDER BY p.id ASC")
  List<EmailFilterProposalEntity> findPage(@Param("afterId")
  long afterId, Pageable pageable);

}
