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

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;

public interface EmailAttachmentDAO extends JpaRepository<EmailAttachmentEntity, Long> {

  @Query("SELECT attachment FROM EmailAttachmentEntity attachment LEFT JOIN FETCH attachment.email email WHERE attachment.attachmentRemoteId = :attachmentId "
      + "AND email.mailRemoteId = :mailRemoteId AND email.userId = :username")
  Optional<EmailAttachmentEntity> findByMailRemoteIdAndAttachmentIdAndUserId(@Param("mailRemoteId")
  long mailRemoteId, @Param("attachmentId")
  String attachmentId, @Param("username")
  String username);
}
