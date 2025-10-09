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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailBoxEntity;

public interface EmailBoxDAO extends JpaRepository<EmailBoxEntity, Long> {

  List<EmailBoxEntity> findByUserIdOrderBySentDateDesc(String userId);

  EmailBoxEntity findByUserIdAndMailRemoteId(String userId, long mailRemoteId);

  @Transactional
  @Modifying
  @Query("DELETE FROM EmailBoxEntity eb WHERE eb.id IN :ids")
  void deleteEmailsByIds(@Param("ids")
  List<Long> ids);
}
