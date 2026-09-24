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
package org.exoplatform.emailConnector.storage;

import java.util.Date;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailDelegationDAO;
import org.exoplatform.emailConnector.entity.EmailDelegationEntity;
import org.exoplatform.emailConnector.model.DelegationOrigin;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.EmailDelegation;
import org.exoplatform.emailConnector.model.FolderRole;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.social.util.JsonUtils;

/**
 * The delegation rows' persistence: entity to {@link EmailDelegation} and back. Every
 * read that takes an id takes the viewer with it, and answers null rather than throwing
 * -- the service is what decides that "no such row for you" is a 404.
 * <p>
 * No cache. A row changes on both sides (the owner revokes, the grantee accepts) and is
 * read a handful of times per settings screen; a cache would only add a key to get
 * wrong for the ACL-bearing viewer.
 */
@Component
public class EmailDelegationStorage {

  private static final Log LOG = ExoLogger.getLogger(EmailDelegationStorage.class);

  @Autowired
  private EmailDelegationDAO emailDelegationDAO;

  /**
   * One row, as its grantee.
   *
   * @param granteeId the grantee's username
   * @param id the row id
   * @return the delegation, or null when no such row belongs to that grantee
   */
  public EmailDelegation getAsGrantee(String granteeId, long id) {
    return emailDelegationDAO.findByIdAndGranteeId(id, granteeId).stream().findFirst().map(this::fromEntity).orElse(null);
  }

  /**
   * One row, as its owner.
   *
   * @param ownerId the owner's username
   * @param id the row id
   * @return the delegation, or null when no such row belongs to that owner
   */
  public EmailDelegation getAsOwner(String ownerId, long id) {
    return emailDelegationDAO.findByIdAndOwnerId(id, ownerId).stream().findFirst().map(this::fromEntity).orElse(null);
  }

  /**
   * The row of the unique key.
   *
   * @param granteeId the grantee's username
   * @param connectorId the connector preset
   * @param ownerMailbox the owner's mailbox identifier
   * @return the delegation, or null
   */
  public EmailDelegation getByKey(String granteeId, long connectorId, String ownerMailbox) {
    return emailDelegationDAO.findByGranteeIdAndConnectorIdAndOwnerMailbox(granteeId, connectorId, ownerMailbox)
                             .stream()
                             .findFirst()
                             .map(this::fromEntity)
                             .orElse(null);
  }

  /**
   * Every row of a grantee.
   *
   * @param granteeId the grantee's username
   * @return the delegations, most recently changed first, never null
   */
  public List<EmailDelegation> getReceived(String granteeId) {
    return emailDelegationDAO.findByGranteeId(granteeId).stream().map(this::fromEntity).toList();
  }

  /**
   * Every row of an owner.
   *
   * @param ownerId the owner's username
   * @return the delegations, most recently changed first, never null
   */
  public List<EmailDelegation> getGranted(String ownerId) {
    return emailDelegationDAO.findByOwnerId(ownerId).stream().map(this::fromEntity).toList();
  }

  /**
   * How many delegations a grantee has in one state.
   *
   * @param granteeId the grantee's username
   * @param status the state
   * @return the count
   */
  public long count(String granteeId, DelegationStatus status) {
    return emailDelegationDAO.countByGranteeIdAndStatus(granteeId, status.name());
  }

  /**
   * The accepted delegations a grantee is currently looking at -- the sync's tier
   * signal, and the only rows whose folders are ever fetched.
   *
   * @param granteeId the grantee's username
   * @param activeSince an activity stamp at or after this instant makes a row active
   * @return the active accepted delegations, oldest first, never null
   */
  public List<EmailDelegation> getActive(String granteeId, Date activeSince) {
    return emailDelegationDAO.findActiveByGranteeId(granteeId, DelegationStatus.ACCEPTED.name(), activeSince)
                             .stream()
                             .map(this::fromEntity)
                             .toList();
  }

  /**
   * Stamps that the grantee is looking at one shared mailbox, subject to the throttle
   * the query itself applies.
   *
   * @param granteeId the grantee's username
   * @param id the delegation id
   * @param now the stamp
   * @param throttleBefore only a stamp older than this (or none) is rewritten
   * @return true when a row was actually stamped
   */
  public boolean touchActivity(String granteeId, long id, Date now, Date throttleBefore) {
    return emailDelegationDAO.touchActivity(id, granteeId, now, throttleBefore) > 0;
  }

  /**
   * Inserts a row. The dates are stamped here; the id the DTO carries is ignored.
   *
   * @param delegation the row to create
   * @return the row as created, id set
   */
  public EmailDelegation create(EmailDelegation delegation) {
    EmailDelegationEntity entity = toEntity(delegation, new EmailDelegationEntity());
    Date now = new Date();
    entity.setId(null);
    entity.setCreatedDate(now);
    entity.setUpdatedDate(now);
    return fromEntity(emailDelegationDAO.saveAndFlush(entity));
  }

  /**
   * Writes a row back. Through a managed entity under {@code DynamicUpdate}, so only the
   * columns that changed since the load reach the database; the update date is stamped.
   *
   * @param delegation the row, id set
   * @return the row as it now stands
   * @throws IllegalArgumentException when the id names no row
   */
  public EmailDelegation update(EmailDelegation delegation) {
    EmailDelegationEntity entity = emailDelegationDAO.findById(delegation.getId())
                                                     .orElseThrow(() -> new IllegalArgumentException("emailConnector.delegation.notFound"));
    // The activity stamp is touchActivity's alone, written in SQL outside any DTO: a
    // DTO read before the grantee's last listing must not rewind it (#432-2).
    Date lastActivity = entity.getLastActivityDate();
    toEntity(delegation, entity);
    entity.setLastActivityDate(lastActivity);
    entity.setUpdatedDate(new Date());
    return fromEntity(emailDelegationDAO.saveAndFlush(entity));
  }

  /**
   * The grantee's two toggles, written alone (#432-2): a row-wide write from an earlier
   * read would put back whatever the owner changed since -- a status, a revoke date,
   * rights. The update date is stamped.
   *
   * @param granteeId the grantee, whose row it must be
   * @param id the row id
   * @param badgeIncluded whether the shared INBOX counts in the badge
   * @param notifyNewMail whether new mail there notifies
   * @return the row as it now stands, null when no such row of that grantee exists
   */
  public EmailDelegation updatePreferences(String granteeId, long id, boolean badgeIncluded, boolean notifyNewMail) {
    emailDelegationDAO.updatePreferences(id, granteeId, badgeIncluded, notifyNewMail, new Date());
    return getAsGrantee(granteeId, id);
  }

  /**
   * The grantee's search toggle, written alone (EXO-90554), for the reason
   * {@link #updatePreferences} is. The update date is stamped.
   *
   * @param granteeId the grantee, whose row it must be
   * @param id the row id
   * @param searchIncluded whether the unified search returns this shared mailbox
   * @return the row as it now stands, null when no such row of that grantee exists
   */
  public EmailDelegation updateSearchIncluded(String granteeId, long id, boolean searchIncluded) {
    emailDelegationDAO.updateSearchIncluded(id, granteeId, searchIncluded, new Date());
    return getAsGrantee(granteeId, id);
  }

  /**
   * What an owner's change of access wrote on the server, written alone (stack review
   * N-1): a row-wide write from the read made before the SETACL round-trip would put
   * back a leave or a revoke committed meanwhile. A share that ended meanwhile (revoked,
   * gone) is not written.
   *
   * @param ownerId the owner, whose row it must be
   * @param id the row id
   * @param preset the preset recorded
   * @param rights the letters the server holds
   * @param nativeRights the server's own words for them
   * @param granteeMailbox the identifier the entry was written for
   * @param checked the rights check stamp
   * @return the row as it now stands, null when it is not that owner's or has ended
   */
  public EmailDelegation updateGrantedRights(String ownerId,
                                             long id,
                                             DelegationPreset preset,
                                             String rights,
                                             String nativeRights,
                                             String granteeMailbox,
                                             Date checked) {
    int updated = emailDelegationDAO.updateGrantedRights(id,
                                                         ownerId,
                                                         preset == null ? null : preset.name(),
                                                         rights,
                                                         nativeRights,
                                                         granteeMailbox,
                                                         checked,
                                                         new Date(),
                                                         List.of(DelegationStatus.REVOKED.name(), DelegationStatus.GONE.name()));
    return updated == 0 ? null : getAsOwner(ownerId, id);
  }

  /**
   * {@link #updateGrantedRights(String, long, DelegationPreset, String, String, String, Date)}
   * for a share recorded per folder, which also records the folder roles the grant now
   * covers and the owner's folder each was written on -- in the same statement, under
   * the same guards. With no roles given, the roles stay as they are.
   *
   * @param ownerId the owner, whose row it must be
   * @param id the row id
   * @param preset the preset recorded
   * @param rights the letters the server holds
   * @param nativeRights the server's own words for them
   * @param granteeMailbox the identifier the entries were written for
   * @param checked the rights check stamp
   * @param grantedRoles the folder roles the share covers, or null to leave them
   * @param ownerRoleFolders the owner's folder per role; ignored when no roles are given
   * @return the row as it now stands, null when it is not that owner's or has ended
   */
  public EmailDelegation updateGrantedRights(String ownerId,
                                             long id,
                                             DelegationPreset preset,
                                             String rights,
                                             String nativeRights,
                                             String granteeMailbox,
                                             Date checked,
                                             String grantedRoles,
                                             Map<FolderRole, String> ownerRoleFolders) {
    if (grantedRoles == null) {
      return updateGrantedRights(ownerId, id, preset, rights, nativeRights, granteeMailbox, checked);
    }
    int updated = emailDelegationDAO.updateGrantedRightsAndRoles(id,
                                                                 ownerId,
                                                                 preset == null ? null : preset.name(),
                                                                 rights,
                                                                 nativeRights,
                                                                 granteeMailbox,
                                                                 grantedRoles,
                                                                 roleFoldersToJson(ownerRoleFolders),
                                                                 checked,
                                                                 new Date(),
                                                                 List.of(DelegationStatus.REVOKED.name(),
                                                                         DelegationStatus.GONE.name()));
    return updated == 0 ? null : getAsOwner(ownerId, id);
  }

  /**
   * A grantee's accept, written alone (EXO-90548 review, finding 1): a row-wide write
   * from the read made before the server calls would put back the folder roles an
   * owner's Extend wrote meanwhile. Only a row of that grantee still pending, declined
   * or available is written.
   *
   * @param granteeId the grantee, whose row it must be
   * @param id the row id
   * @param remoteRoot where the shared tree is on the grantee's session
   * @param rights the grantee's own MYRIGHTS letters
   * @param preset the preset to record, or null to keep the recorded one
   * @param checked the rights check and response stamp
   * @return the row as it now stands, null when nothing was written
   */
  public EmailDelegation accept(String granteeId, long id, String remoteRoot, String rights, DelegationPreset preset, Date checked) {
    int updated = emailDelegationDAO.accept(id,
                                            granteeId,
                                            DelegationStatus.ACCEPTED.name(),
                                            remoteRoot,
                                            rights,
                                            rights,
                                            preset == null ? null : preset.name(),
                                            checked,
                                            checked,
                                            new Date(),
                                            List.of(DelegationStatus.PENDING.name(),
                                                    DelegationStatus.DECLINED.name(),
                                                    DelegationStatus.AVAILABLE.name()));
    return updated == 0 ? null : getAsGrantee(granteeId, id);
  }

  /**
   * The letters the owner's ACL holds for a share on offer, written alone (EXO-90548
   * review, finding 1). A share in use or ended is not written.
   *
   * @param ownerId the owner, whose row it must be
   * @param id the row id
   * @param rights the letters the owner's ACL holds
   * @param nativeRights the server's own words for them
   * @return the row as it now stands, null when nothing was written
   */
  public EmailDelegation updateOfferedRights(String ownerId, long id, String rights, String nativeRights) {
    Date now = new Date();
    int updated = emailDelegationDAO.updateOfferedRights(id,
                                                         ownerId,
                                                         rights,
                                                         nativeRights,
                                                         now,
                                                         now,
                                                         List.of(DelegationStatus.ACCEPTED.name(),
                                                                 DelegationStatus.REVOKED.name(),
                                                                 DelegationStatus.GONE.name()));
    return updated == 0 ? null : getAsOwner(ownerId, id);
  }

  /**
   * DTO to entity, every column but the two stamps.
   *
   * @param delegation the source
   * @param entity the target
   * @return the target
   */
  private EmailDelegationEntity toEntity(EmailDelegation delegation, EmailDelegationEntity entity) {
    entity.setGranteeId(delegation.getGranteeId());
    entity.setOwnerId(delegation.getOwnerId());
    entity.setOwnerMailbox(delegation.getOwnerMailbox());
    entity.setGranteeMailbox(delegation.getGranteeMailbox());
    entity.setConnectorId(delegation.getConnectorId());
    entity.setRemoteRoot(delegation.getRemoteRoot());
    entity.setPreset(delegation.getPreset() == null ? null : delegation.getPreset().name());
    entity.setRights(delegation.getRights());
    entity.setNativeRights(delegation.getNativeRights());
    entity.setStatus(delegation.getStatus() == null ? null : delegation.getStatus().name());
    entity.setOrigin(delegation.getOrigin() == null ? null : delegation.getOrigin().name());
    entity.setBadgeIncluded(delegation.isBadgeIncluded());
    entity.setNotifyNewMail(delegation.isNotifyNewMail());
    entity.setSearchIncluded(delegation.isSearchIncluded());
    entity.setLastActivityDate(delegation.getLastActivityDate());
    entity.setLastRightsCheckDate(delegation.getLastRightsCheckDate());
    entity.setInvitedDate(delegation.getInvitedDate());
    entity.setRespondedDate(delegation.getRespondedDate());
    entity.setRevokedDate(delegation.getRevokedDate());
    entity.setGrantedRoles(delegation.getGrantedRoles());
    entity.setOwnerRoleFolders(roleFoldersToJson(delegation.getOwnerRoleFolders()));
    return entity;
  }

  /**
   * Entity to DTO.
   *
   * @param entity the row
   * @return the DTO
   */
  private EmailDelegation fromEntity(EmailDelegationEntity entity) {
    return new EmailDelegation(entity.getId(),
                               entity.getGranteeId(),
                               entity.getOwnerId(),
                               entity.getOwnerMailbox(),
                               entity.getGranteeMailbox(),
                               entity.getConnectorId(),
                               entity.getRemoteRoot(),
                               entity.getPreset() == null ? null : DelegationPreset.valueOf(entity.getPreset()),
                               entity.getRights(),
                               entity.getNativeRights(),
                               entity.getStatus() == null ? null : DelegationStatus.valueOf(entity.getStatus()),
                               entity.getOrigin() == null ? null : DelegationOrigin.valueOf(entity.getOrigin()),
                               entity.isBadgeIncluded(),
                               entity.isNotifyNewMail(),
                               entity.getLastActivityDate(),
                               entity.getLastRightsCheckDate(),
                               entity.getInvitedDate(),
                               entity.getRespondedDate(),
                               entity.getRevokedDate(),
                               entity.getCreatedDate(),
                               entity.getUpdatedDate(),
                               entity.getGrantedRoles(),
                               roleFoldersFromJson(entity.getOwnerRoleFolders()),
                               entity.isSearchIncluded());
  }

  /**
   * The owner's role-to-folder-name map as stored: a JSON object keyed by role name, or
   * null for no map.
   *
   * @param roleFolders the map
   * @return the JSON, or null
   */
  private static String roleFoldersToJson(Map<FolderRole, String> roleFolders) {
    if (roleFolders == null || roleFolders.isEmpty()) {
      return null;
    }
    Map<String, String> byName = new TreeMap<>();
    roleFolders.forEach((role, name) -> {
      if (role != null && name != null) {
        byName.put(role.name(), name);
      }
    });
    return byName.isEmpty() ? null : JsonUtils.toJsonString(byName);
  }

  /**
   * The stored map read back. An entry whose role this version does not know, or whose
   * name is not a string, is skipped; unreadable JSON reads as no map -- the delegate's
   * discovery then falls back to folder names, which is what it does on a share written
   * before the map existed.
   *
   * @param json the stored JSON
   * @return the map, empty when none
   */
  @SuppressWarnings("unchecked")
  private static Map<FolderRole, String> roleFoldersFromJson(String json) {
    Map<FolderRole, String> roleFolders = new EnumMap<>(FolderRole.class);
    if (json == null || json.isBlank()) {
      return roleFolders;
    }
    try {
      Map<String, Object> byName = JsonUtils.fromJsonString(json, Map.class);
      if (byName != null) {
        byName.forEach((name, folder) -> {
          FolderRole role = FolderRole.of(name);
          if (role != null && folder instanceof String folderName) {
            roleFolders.put(role, folderName);
          }
        });
      }
    } catch (Exception e) { // the parser may throw its checked exception undeclared
      LOG.debug("Unreadable owner role folders on a delegation row, read as none: {}", json, e);
    }
    return roleFolders;
  }
}
