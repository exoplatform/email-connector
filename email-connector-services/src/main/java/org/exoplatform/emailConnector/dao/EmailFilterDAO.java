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

import java.util.Date;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailFilterEntity;

/**
 * The eXo rules of a user (changeset 1.0.0-71). Every read is scoped to the owner: an id
 * alone never resolves a row. A user's rules are tens at most, so the listing is not
 * paged.
 */
public interface EmailFilterDAO extends JpaRepository<EmailFilterEntity, Long> {

  /**
   * A user's rules, in the order eXo evaluates them.
   *
   * @param userId the owner
   * @return the rules, by position then id
   */
  @Query("SELECT f FROM EmailFilterEntity f WHERE f.userId = :userId ORDER BY f.position ASC, f.id ASC")
  List<EmailFilterEntity> findByUserId(@Param("userId")
  String userId);

  /**
   * One rule of its owner.
   *
   * @param id the rule's id
   * @param userId the owner
   * @return the rule, or nothing when it is not this user's
   */
  @Query("SELECT f FROM EmailFilterEntity f WHERE f.id = :id AND f.userId = :userId")
  List<EmailFilterEntity> findByIdAndUserId(@Param("id")
  long id, @Param("userId")
  String userId);

  /**
   * The rule of a user that a keyword names: how the sync finds the eXo half of a rule
   * the server tagged a mail for. Scoped to the owner, so a keyword naming another
   * user's rule finds nothing.
   *
   * @param userId the owner
   * @param tagKeyword the keyword
   * @return the rule, or nothing
   */
  @Query("SELECT f FROM EmailFilterEntity f WHERE f.userId = :userId AND f.tagKeyword = :tagKeyword")
  List<EmailFilterEntity> findByUserIdAndTagKeyword(@Param("userId")
  String userId, @Param("tagKeyword")
  String tagKeyword);

  /**
   * How many enabled rules a user has: the sync asks it before reading anything else,
   * so a user without rules costs one count.
   *
   * @param userId the owner
   * @return the count
   */
  @Query("SELECT COUNT(f) FROM EmailFilterEntity f WHERE f.userId = :userId AND f.enabled = true")
  long countEnabledByUserId(@Param("userId")
  String userId);

  /**
   * The highest position among a user's rules, where a new rule is appended after.
   *
   * @param userId the owner
   * @return the highest position, or null when the user has none
   */
  @Query("SELECT MAX(f.position) FROM EmailFilterEntity f WHERE f.userId = :userId")
  Integer findMaxPosition(@Param("userId")
  String userId);

  /**
   * Counts matches on a rule, in SQL: the sync writes this while the owner may be
   * editing the rule, and neither write may undo the other.
   *
   * @param id the rule
   * @param userId the owner
   * @param count how many mails it matched in this pass
   * @param date when
   * @return 1 when counted
   */
  @Transactional
  @Modifying
  @Query("UPDATE EmailFilterEntity f SET f.matchCount = f.matchCount + :count, f.lastMatchDate = :date"
      + " WHERE f.id = :id AND f.userId = :userId")
  int addMatches(@Param("id")
  long id, @Param("userId")
  String userId, @Param("count")
  long count, @Param("date")
  Date date);

  /**
   * Switches a rule off with the reason, in SQL, for the reason {@link #addMatches}
   * gives.
   *
   * @param id the rule
   * @param userId the owner
   * @param error the reason
   * @param date when
   * @return 1 when switched off
   */
  @Transactional
  @Modifying
  @Query("UPDATE EmailFilterEntity f SET f.enabled = false, f.lastError = :error, f.updatedDate = :date"
      + " WHERE f.id = :id AND f.userId = :userId")
  int disableWithError(@Param("id")
  long id, @Param("userId")
  String userId, @Param("error")
  String error, @Param("date")
  Date date);
}
