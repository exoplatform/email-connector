/**
 * Copyright (C) 2026 eXo Platform SAS
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

import org.exoplatform.emailConnector.entity.EmailDelegationEntity;

/**
 * The delegation rows. Every read that takes an id takes a viewer with it -- the
 * grantee for the grantee's operations, the owner for the owner's -- so an id sent by
 * a client resolves only together with the caller's own username, and someone else's
 * row answers "not found" rather than "forbidden": ids are not enumerable.
 * <p>
 * Rows of one user are bounded by how many mailboxes they share or are shared -- a
 * handful, capped for the grantee -- which is why the per-user reads are not paged.
 */
public interface EmailDelegationDAO extends JpaRepository<EmailDelegationEntity, Long> {

  /**
   * One row by id AND grantee. A list rather than an Optional, for the reason
   * {@link EmailFolderDAO#findByIdAndUserId} gives: the query is not what should throw.
   *
   * @param id the row id
   * @param granteeId the grantee's username
   * @return the matching rows, one or none, never null
   */
  @Query("SELECT d FROM EmailDelegationEntity d WHERE d.id = :id AND d.granteeId = :granteeId")
  List<EmailDelegationEntity> findByIdAndGranteeId(@Param("id")
  long id, @Param("granteeId")
  String granteeId);

  /**
   * One row by id AND owner.
   *
   * @param id the row id
   * @param ownerId the owner's username
   * @return the matching rows, one or none, never null
   */
  @Query("SELECT d FROM EmailDelegationEntity d WHERE d.id = :id AND d.ownerId = :ownerId")
  List<EmailDelegationEntity> findByIdAndOwnerId(@Param("id")
  long id, @Param("ownerId")
  String ownerId);

  /**
   * The row of the unique key: one grantee's subscription to one mailbox on one preset.
   *
   * @param granteeId the grantee's username
   * @param connectorId the connector preset
   * @param ownerMailbox the owner's mailbox identifier
   * @return the matching rows, one or none, never null
   */
  @Query("SELECT d FROM EmailDelegationEntity d WHERE d.granteeId = :granteeId AND d.connectorId = :connectorId AND d.ownerMailbox = :ownerMailbox")
  List<EmailDelegationEntity> findByGranteeIdAndConnectorIdAndOwnerMailbox(@Param("granteeId")
  String granteeId, @Param("connectorId")
  long connectorId, @Param("ownerMailbox")
  String ownerMailbox);

  /**
   * Every row a grantee has, whatever its state, most recently changed first.
   *
   * @param granteeId the grantee's username
   * @return the rows, never null
   */
  @Query("SELECT d FROM EmailDelegationEntity d WHERE d.granteeId = :granteeId ORDER BY d.updatedDate DESC, d.id DESC")
  List<EmailDelegationEntity> findByGranteeId(@Param("granteeId")
  String granteeId);

  /**
   * Every row an owner has as owner, most recently changed first.
   *
   * @param ownerId the owner's username
   * @return the rows, never null
   */
  @Query("SELECT d FROM EmailDelegationEntity d WHERE d.ownerId = :ownerId ORDER BY d.updatedDate DESC, d.id DESC")
  List<EmailDelegationEntity> findByOwnerId(@Param("ownerId")
  String ownerId);

  /**
   * How many rows of a grantee are in one state -- what the per-grantee cap is checked
   * against, on ACCEPTED.
   *
   * @param granteeId the grantee's username
   * @param status the state
   * @return the count
   */
  @Query("SELECT COUNT(d) FROM EmailDelegationEntity d WHERE d.granteeId = :granteeId AND d.status = :status")
  long countByGranteeIdAndStatus(@Param("granteeId")
  String granteeId, @Param("status")
  String status);

  /**
   * Drops every row a user appears on, as grantee or as owner -- the disconnect wipe's
   * companion. The owner's rows go because eXo can no longer act on that mailbox; the
   * server's ACLs are not touched, by design.
   *
   * @param userId the username
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("DELETE FROM EmailDelegationEntity d WHERE d.granteeId = :userId OR d.ownerId = :userId")
  void deleteByUserId(@Param("userId")
  String userId);
}
