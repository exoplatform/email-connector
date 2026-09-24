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
   * The grantee's search toggle, and nothing else of the row (EXO-90554) -- the same
   * narrow write as {@link #updatePreferences}, for the same reason.
   *
   * @param id the row id
   * @param granteeId the grantee, whose row it must be
   * @param searchIncluded whether the unified search returns this shared mailbox
   * @param updated the update stamp
   * @return the rows updated: one, or zero when no such row belongs to that grantee
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.searchIncluded = :searchIncluded, d.updatedDate = :updated WHERE d.id = :id AND d.granteeId = :granteeId")
  int updateSearchIncluded(@Param("id")
  long id, @Param("granteeId")
  String granteeId, @Param("searchIncluded")
  boolean searchIncluded, @Param("updated")
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

  /**
   * An owner's per-folder change (EXO-90556), and nothing else of the row: the role
   * folders the share covers, the owner's folder of each, the per-folder exceptions, and
   * the update stamp -- under the same guards as the other owner writes, that owner's
   * row and not a share that ended meanwhile.
   *
   * @param id the row id
   * @param ownerId the owner, whose row it must be
   * @param grantedRoles the role folders the share covers, as stored
   * @param ownerRoleFolders the owner's folder per role, as stored
   * @param folderAccess the per-folder exceptions, as stored
   * @param updated the update stamp
   * @param ended the statuses of a share no longer on the server
   * @return the rows updated: one, or zero when the row is not that owner's or ended
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.grantedRoles = :grantedRoles, d.ownerRoleFolders = :ownerRoleFolders, d.folderAccess = :folderAccess, d.updatedDate = :updated WHERE d.id = :id AND d.ownerId = :ownerId AND d.status NOT IN :ended")
  int updateFolderGrants(@Param("id")
  long id, @Param("ownerId")
  String ownerId, @Param("grantedRoles")
  String grantedRoles, @Param("ownerRoleFolders")
  String ownerRoleFolders, @Param("folderAccess")
  String folderAccess, @Param("updated")
  Date updated, @Param("ended")
  List<String> ended);

  /**
   * The owner's consent to the grantee writing mail in the owner's name (EXO-90582), and
   * nothing else of the row: the mode, when it was set, and the server's last refusal
   * cleared -- a consent set again is a fresh one. Only that owner's row, and only a
   * share on offer or in use ({@code live}): a declined, available, revoked or gone share
   * never carries a consent, stricter than the other owner writes, which merely skip an
   * ended share.
   *
   * @param id the row id
   * @param ownerId the owner, whose row it must be
   * @param sendMode the consent as stored, null for none
   * @param sendModeDate when it was set, null with no consent
   * @param updated the update stamp
   * @param live the statuses a consent may be written on
   * @return the rows updated: one, or zero when the row is not that owner's or not live
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.sendMode = :sendMode, d.sendModeDate = :sendModeDate, d.sendRefusedDate = NULL,"
      + " d.updatedDate = :updated WHERE d.id = :id AND d.ownerId = :ownerId AND d.status IN :live")
  int updateSendMode(@Param("id")
  long id, @Param("ownerId")
  String ownerId, @Param("sendMode")
  String sendMode, @Param("sendModeDate")
  Date sendModeDate, @Param("updated")
  Date updated, @Param("live")
  List<String> live);

  /**
   * Takes the owner's consent to writing in her name off a share that is no longer on
   * offer or in use (EXO-90582): run after the write that ended it, so a consent written
   * while that write was on its way -- the status still live then -- goes too, and one
   * asked after it finds the share ended. A row that is live again (re-invited, accepted)
   * is left alone, and so is a row with nothing to take off.
   *
   * @param id the row id
   * @param live the statuses a consent may stay on
   * @return the rows updated: one, or zero when the row is live, carries no consent, or
   *         is unknown
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.sendMode = NULL, d.sendModeDate = NULL, d.sendRefusedDate = NULL"
      + " WHERE d.id = :id AND d.status NOT IN :live"
      + " AND (d.sendMode IS NOT NULL OR d.sendModeDate IS NOT NULL OR d.sendRefusedDate IS NOT NULL)")
  int clearSendMode(@Param("id")
  long id, @Param("live")
  List<String> live);

  /**
   * Records that the owner's mail server refused a mail the grantee sent in the owner's
   * name (EXO-90583), and nothing else of the row: the refusal's date and the update
   * stamp. Only that grantee's row, only a share in use or on offer, and only while it
   * still carries the consent the mail was sent under ({@code sendModeDate}): a consent
   * withdrawn or set again since is left as the owner made it.
   *
   * @param id the row id
   * @param granteeId the grantee, whose row it must be
   * @param consentDate when the consent the mail was sent under was set
   * @param refused when the server refused it
   * @param live the statuses a consent may live on
   * @return the rows updated: one, or zero when the row is not that grantee's, not live,
   *         or no longer carries that consent
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.sendRefusedDate = :refused, d.updatedDate = :refused"
      + " WHERE d.id = :id AND d.granteeId = :granteeId AND d.status IN :live"
      + " AND d.sendMode IS NOT NULL AND d.sendModeDate = :consentDate")
  int markSendRefused(@Param("id")
  long id, @Param("granteeId")
  String granteeId, @Param("consentDate")
  Date consentDate, @Param("refused")
  Date refused, @Param("live")
  List<String> live);

  /**
   * A grantee's accept, and nothing else of the row (EXO-90548 review, finding 1): the
   * status, where the shared tree is, the grantee's own letters, the server's words only
   * when none were recorded, the preset only when one is given, and the stamps. The
   * owner's columns -- the folder roles and their folders an Extend may have written
   * while the server was being asked -- are left as they stand. Only a row of that
   * grantee still in an acceptable status is written.
   *
   * @param id the row id
   * @param granteeId the grantee, whose row it must be
   * @param status the accepted status, as its name
   * @param remoteRoot where the shared tree is on the grantee's session
   * @param rights the grantee's own MYRIGHTS letters
   * @param nativeRights the letters to keep as the server's words when none are recorded
   * @param preset the preset to record, or null to keep the recorded one
   * @param checked the rights check stamp
   * @param responded the response stamp
   * @param updated the update stamp
   * @param acceptable the statuses an accept may leave
   * @return the rows updated: one, or zero when the row is not that grantee's or moved on
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.status = :status, d.remoteRoot = :remoteRoot, d.rights = :rights,"
      + " d.nativeRights = CASE WHEN d.nativeRights IS NULL OR d.nativeRights = '' THEN :nativeRights ELSE d.nativeRights END,"
      + " d.preset = COALESCE(:preset, d.preset), d.lastRightsCheckDate = :checked, d.respondedDate = :responded,"
      + " d.updatedDate = :updated WHERE d.id = :id AND d.granteeId = :granteeId AND d.status IN :acceptable")
  int accept(@Param("id")
  long id, @Param("granteeId")
  String granteeId, @Param("status")
  String status, @Param("remoteRoot")
  String remoteRoot, @Param("rights")
  String rights, @Param("nativeRights")
  String nativeRights, @Param("preset")
  String preset, @Param("checked")
  Date checked, @Param("responded")
  Date responded, @Param("updated")
  Date updated, @Param("acceptable")
  List<String> acceptable);

  /**
   * The letters the owner's ACL holds for a share on offer, and nothing else of the row
   * (EXO-90548 review, finding 1): never on a share in use, whose letters are the
   * grantee's own MYRIGHTS, nor on one that ended; an Extend's folder roles written
   * meanwhile are left as they stand.
   *
   * @param id the row id
   * @param ownerId the owner, whose row it must be
   * @param rights the letters the owner's ACL holds
   * @param nativeRights the server's own words for them
   * @param checked the rights check stamp
   * @param updated the update stamp
   * @param excluded the statuses not written: in use, or ended
   * @return the rows updated
   */
  @Transactional
  @Modifying(clearAutomatically = true, flushAutomatically = true)
  @Query("UPDATE EmailDelegationEntity d SET d.rights = :rights, d.nativeRights = :nativeRights, d.lastRightsCheckDate = :checked,"
      + " d.updatedDate = :updated WHERE d.id = :id AND d.ownerId = :ownerId AND d.status NOT IN :excluded")
  int updateOfferedRights(@Param("id")
  long id, @Param("ownerId")
  String ownerId, @Param("rights")
  String rights, @Param("nativeRights")
  String nativeRights, @Param("checked")
  Date checked, @Param("updated")
  Date updated, @Param("excluded")
  List<String> excluded);
}
