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
package org.exoplatform.emailConnector.entity;

import java.util.Date;

import org.hibernate.annotations.DynamicUpdate;

import io.meeds.common.persistence.PortableSequence;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * One tool call a mail filter's assistant made, recorded instead of run (changesets
 * 1.0.0-94 to -97), until its owner approves, rejects or hands it over.
 * <p>
 * {@link DynamicUpdate}: the status moves by conditional UPDATEs (the claim of an
 * approval) while the run that recorded it may still write its rationale.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity(name = "EmailFilterProposalEntity")
@Table(name = "EMAIL_FILTER_PROPOSAL",
    indexes = { @Index(name = "UK_EMAIL_FILTER_PROPOSAL", columnList = "MATCH_ID,CALL_HASH", unique = true),
        @Index(name = "IDX_EMAIL_FILTER_PROPOSAL_USER", columnList = "USER_ID,STATUS") })
public class EmailFilterProposalEntity {

  @Id
  @PortableSequence(name = "SEQ_EMAIL_FILTER_PROPOSAL_ID")
  @Column(name = "ID")
  private Long   id;

  @Column(name = "USER_ID", nullable = false)
  private String userId;

  @Column(name = "MATCH_ID", nullable = false)
  private Long   matchId;

  @Column(name = "FILTER_ID", nullable = false)
  private Long   filterId;

  @Column(name = "TOOL_NAME", nullable = false)
  private String toolName;

  @Column(name = "TOOL_TITLE")
  private String toolTitle;

  @Column(name = "TOOL_DESCRIPTION")
  private String toolDescription;

  @Column(name = "ARGUMENTS", nullable = false)
  private String arguments;

  @Column(name = "CALL_HASH", nullable = false)
  private String callHash;

  @Column(name = "RATIONALE")
  private String rationale;

  @Column(name = "STATUS", nullable = false)
  private String status;

  @Column(name = "CREATED_DATE", nullable = false)
  private Date   createdDate;

  @Column(name = "EXPIRES_DATE", nullable = false)
  private Date   expiresDate;

  @Column(name = "DECIDED_DATE")
  private Date   decidedDate;

  @Column(name = "CONVERSATION_ID")
  private String conversationId;

  @Column(name = "RESULT")
  private String result;

  @Column(name = "LAST_ERROR")
  private String lastError;
}
