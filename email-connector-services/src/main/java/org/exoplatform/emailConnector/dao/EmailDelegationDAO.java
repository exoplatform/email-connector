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

import java.util.Date;
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
   * The shared mailboxes a grantee is BOTH subscribed to and currently looking at: the
   * rows the delegated branch of the sync walks, and the only ones it costs anything
   * for.
   * <p>
   * This is the whole of the tiering. A delegate who accepted a mailbox six months ago
   * and never opened it since is not in this answer, so their delegated folders are
   * never opened, never fetched and never mirrored -- the cost of an idle delegation is
   * exactly zero IMAP round-trips, which is what makes one mirror per delegate
   * affordable at all. There is deliberately no slow tier for delegated folders, unlike
   * a user's own mailbox, which keeps syncing at the inactive period: nobody is waiting
   * for mail in a shared mailbox they are not in.
   *
   * @param granteeId the grantee's username
   * @param status ACCEPTED, spelled by the caller so the enum stays out of the query
   * @param activeSince an activity stamp at or after this instant makes the delegation
   *          active; a row that was never opened (null stamp) is never active
   * @return the active accepted rows, oldest id first, never null
   */
  @Query("SELECT d FROM EmailDelegationEntity d WHERE d.granteeId = :granteeId AND d.status = :status"
      + " AND d.lastActivityDate IS NOT NULL AND d.lastActivityDate >= :activeSince ORDER BY d.id ASC")
  List<EmailDelegationEntity> findActiveByGranteeId(@Param("granteeId")
  String granteeId, @Param("status")
  String status, @Param("activeSince")
  Date activeSince);

  /**
   * Stamps that the grantee is looking at one shared mailbox right now -- the signal
   * {@link #findActiveByGranteeId} selects on.
   * <p>
   * Throttled in the WHERE clause rather than in a map, exactly as the mailbox's own
   * activity stamp is: a drawer left open on a shared mailbox costs one no-op UPDATE
   * per listing instead of a write, and the throttle then holds across nodes rather
   * than per JVM. Scoped to the grantee as every write here is, so an id from a client
   * cannot stamp somebody else's subscription. The row's {@code UPDATED_DATE} is
   * deliberately NOT moved: this is not a change to the delegation, it is a reading of
   * its viewer, and the received-delegations listing orders on that column.
   *
   * @param id the delegation id
   * @param granteeId the grantee's username
   * @param now the stamp
   * @param throttleBefore only a stamp older than this (or none) is rewritten
   * @return the rows updated: one, or zero when throttled or not the caller's row
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.lastActivityDate = :now WHERE d.id = :id AND d.granteeId = :granteeId"
      + " AND (d.lastActivityDate IS NULL OR d.lastActivityDate < :throttleBefore)")
  int touchActivity(@Param("id")
  long id, @Param("granteeId")
  String granteeId, @Param("now")
  Date now, @Param("throttleBefore")
  Date throttleBefore);

  /**
   * The grantee's two toggles, and nothing else of the row (#432-2).
   *
   * @param id the row id
   * @param granteeId the grantee, whose row it must be
   * @param badgeIncluded the badge toggle
   * @param notifyNewMail the notification toggle
   * @param updated the update stamp
   * @return the rows updated: one, or zero when no such row belongs to that grantee
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.badgeIncluded = :badgeIncluded, d.notifyNewMail = :notifyNewMail, d.updatedDate = :updated WHERE d.id = :id AND d.granteeId = :granteeId")
  int updatePreferences(@Param("id")
  long id, @Param("granteeId")
  String granteeId, @Param("badgeIncluded")
  boolean badgeIncluded, @Param("notifyNewMail")
  boolean notifyNewMail, @Param("updated")
  Date updated);

  /**
   * What an owner's change of access wrote on the server, and nothing else of the row
   * (stack review N-1): the preset, the letters, the server's own words, the identifier
   * written and the check stamp. The status, the dates and the grantee's toggles stay as
   * they stand, so a leave committed while the server was being asked is not undone;
   * and a row that ended meanwhile ({@code ended} statuses) is not written at all.
   *
   * @param id the row id
   * @param ownerId the owner, whose row it must be
   * @param preset the preset recorded, as its name
   * @param rights the letters the server holds
   * @param nativeRights the server's own words for them
   * @param granteeMailbox the identifier the entry was written for
   * @param checked the rights check stamp
   * @param updated the update stamp
   * @param ended the statuses of a share no longer on the server
   * @return the rows updated: one, or zero when the row is not that owner's or ended
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.preset = :preset, d.rights = :rights, d.nativeRights = :nativeRights, d.granteeMailbox = :granteeMailbox, d.lastRightsCheckDate = :checked, d.updatedDate = :updated WHERE d.id = :id AND d.ownerId = :ownerId AND d.status NOT IN :ended")
  int updateGrantedRights(@Param("id")
  long id, @Param("ownerId")
  String ownerId, @Param("preset")
  String preset, @Param("rights")
  String rights, @Param("nativeRights")
  String nativeRights, @Param("granteeMailbox")
  String granteeMailbox, @Param("checked")
  Date checked, @Param("updated")
  Date updated, @Param("ended")
  List<String> ended);

  /**
   * {@link #updateGrantedRights} for a share recorded per folder: the same columns, and
   * the folder roles the grant now covers with the owner's folders it was written on,
   * under the same guards -- that owner's row, and not a share that ended meanwhile.
   *
   * @param id the row id
   * @param ownerId the owner, whose row it must be
   * @param preset the preset recorded, as its name
   * @param rights the letters the server holds
   * @param nativeRights the server's own words for them
   * @param granteeMailbox the identifier the entries were written for
   * @param grantedRoles the folder roles the share covers, as stored
   * @param ownerRoleFolders the owner's folder per role, as stored
   * @param checked the rights check stamp
   * @param updated the update stamp
   * @param ended the statuses of a share no longer on the server
   * @return the rows updated: one, or zero when the row is not that owner's or ended
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.preset = :preset, d.rights = :rights, d.nativeRights = :nativeRights, d.granteeMailbox = :granteeMailbox, d.grantedRoles = :grantedRoles, d.ownerRoleFolders = :ownerRoleFolders, d.lastRightsCheckDate = :checked, d.updatedDate = :updated WHERE d.id = :id AND d.ownerId = :ownerId AND d.status NOT IN :ended")
  int updateGrantedRightsAndRoles(@Param("id")
  long id, @Param("ownerId")
  String ownerId, @Param("preset")
  String preset, @Param("rights")
  String rights, @Param("nativeRights")
  String nativeRights, @Param("granteeMailbox")
  String granteeMailbox, @Param("grantedRoles")
  String grantedRoles, @Param("ownerRoleFolders")
  String ownerRoleFolders, @Param("checked")
  Date checked, @Param("updated")
  Date updated, @Param("ended")
  List<String> ended);
}
