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
package org.exoplatform.emailConnector.entity;

import java.util.Date;

import org.hibernate.annotations.DynamicUpdate;

import org.exoplatform.emailConnector.model.ScheduledSendStatus;

import io.meeds.common.persistence.PortableSequence;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * The schedule of one draft: when it goes, and where its sending stands. See
 * changeset 1.0.0-63 for the columns and {@code ScheduledSendStatus} for the state
 * machine.
 * <p>
 * {@code EMAIL_ID} is a plain column, not a relation: the database owns the link
 * (a foreign key with ON DELETE CASCADE, 1.0.0-65), because every removal of a draft
 * row is a bulk statement no JPA cascade would see. Every write after the insert is
 * a targeted conditional UPDATE in {@code EmailScheduledSendDAO};
 * {@link DynamicUpdate} is the second line of defence for the one save that goes
 * through the entity.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity(name = "EmailScheduledSendEntity")
@Table(name = "EMAIL_SCHEDULED_SEND")
public class EmailScheduledSendEntity {

  @Id
  @PortableSequence(name = "SEQ_EMAIL_SCHEDULED_SEND_ID")
  @Column(name = "ID")
  private Long                id;

  @Column(name = "EMAIL_ID", nullable = false)
  private Long                emailId;

  @Column(name = "USER_ID", nullable = false)
  private String              userId;

  @Column(name = "DRAFT_LOCAL_ID", nullable = false)
  private String              draftLocalId;

  @Column(name = "SCHEDULED_DATE", nullable = false)
  private Date                scheduledDate;

  @Column(name = "TIME_ZONE")
  private String              timeZone;

  @Enumerated(EnumType.STRING)
  @Column(name = "STATUS", nullable = false)
  private ScheduledSendStatus status;

  @Column(name = "NEXT_ATTEMPT_DATE")
  private Date                nextAttemptDate;

  @Column(name = "ATTEMPTS", nullable = false)
  private int                 attempts;

  @Column(name = "CLAIMED_BY")
  private String              claimedBy;

  @Column(name = "CLAIMED_DATE")
  private Date                claimedDate;

  @Column(name = "LAST_ERROR")
  private String              lastError;

  @Column(name = "CREATED_DATE", nullable = false)
  private Date                createdDate;

  @Column(name = "UPDATED_DATE")
  private Date                updatedDate;
}
