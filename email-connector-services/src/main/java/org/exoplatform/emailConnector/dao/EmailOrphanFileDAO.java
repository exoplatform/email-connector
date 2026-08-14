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
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.exoplatform.emailConnector.entity.EmailOrphanFileEntity;

public interface EmailOrphanFileDAO extends JpaRepository<EmailOrphanFileEntity, Long> {

  /**
   * The file ids already marked, out of a candidate set — the read that keeps
   * recording idempotent without relying on the unique index to reject a duplicate.
   * <p>
   * Checked rather than caught, because a constraint violation would abort the
   * surrounding statement batch: the marker is written on a delete path, and losing
   * the whole cleanup because one file had already been recorded by an overlapping
   * one is the wrong trade for a race that is entirely normal here.
   *
   * @param fileIds the candidates
   * @return the subset already recorded, never null
   */
  @Query("SELECT orphan.fileId FROM EmailOrphanFileEntity orphan WHERE orphan.fileId IN :fileIds")
  List<Long> findRecordedFileIds(@Param("fileIds")
  List<Long> fileIds);
}
