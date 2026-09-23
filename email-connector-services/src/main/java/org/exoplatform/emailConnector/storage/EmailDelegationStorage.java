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
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailDelegationDAO;
import org.exoplatform.emailConnector.entity.EmailDelegationEntity;
import org.exoplatform.emailConnector.model.DelegationOrigin;
import org.exoplatform.emailConnector.model.DelegationPreset;
import org.exoplatform.emailConnector.model.DelegationStatus;
import org.exoplatform.emailConnector.model.EmailDelegation;

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
    toEntity(delegation, entity);
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
    entity.setLastActivityDate(delegation.getLastActivityDate());
    entity.setLastRightsCheckDate(delegation.getLastRightsCheckDate());
    entity.setInvitedDate(delegation.getInvitedDate());
    entity.setRespondedDate(delegation.getRespondedDate());
    entity.setRevokedDate(delegation.getRevokedDate());
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
                               entity.getUpdatedDate());
  }
}
