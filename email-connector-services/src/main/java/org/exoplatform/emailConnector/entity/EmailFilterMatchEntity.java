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
 * One match of one eXo rule on one mail (changeset 1.0.0-74): the log, the data an undo
 * needs, the assistant's work item and, through its unique key, the at-most-once
 * decision.
 * <p>
 * {@link DynamicUpdate}: the assistant's handler writes the outcome while the owner may
 * undo an action of the same row from the mail's panel.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity(name = "EmailFilterMatchEntity")
@Table(name = "EMAIL_FILTER_MATCH",
    indexes = { @Index(name = "UK_EMAIL_FILTER_MATCH", columnList = "USER_ID,FILTER_ID,MAIL_HEADER_HASH", unique = true),
        @Index(name = "IDX_EMAIL_FILTER_MATCH_AGENT", columnList = "USER_ID,AGENT_STATUS"),
        @Index(name = "IDX_EMAIL_FILTER_MATCH_MAIL", columnList = "USER_ID,MAIL_HEADER_HASH") })
public class EmailFilterMatchEntity {

  @Id
  @PortableSequence(name = "SEQ_EMAIL_FILTER_MATCH_ID")
  @Column(name = "ID")
  private Long   id;

  @Column(name = "USER_ID", nullable = false)
  private String userId;

  @Column(name = "FILTER_ID", nullable = false)
  private Long   filterId;

  @Column(name = "MAIL_HEADER_ID", nullable = false)
  private String mailHeaderId;

  @Column(name = "MAIL_HEADER_HASH", nullable = false)
  private String mailHeaderHash;

  @Column(name = "MAIL_REMOTE_ID")
  private Long   mailRemoteId;

  @Column(name = "FOLDER")
  private String folder;

  @Column(name = "SUBJECT")
  private String subject;

  @Column(name = "MATCHED_DATE", nullable = false)
  private Date   matchedDate;

  @Column(name = "ACTIONS_APPLIED")
  private String actionsApplied;

  @Column(name = "AGENT_STATUS", nullable = false)
  private String agentStatus;

  @Column(name = "AGENT_NAME_ID")
  private String agentNameId;

  @Column(name = "AGENT_CONVERSATION_ID")
  private String agentConversationId;

  @Column(name = "AGENT_OUTPUT")
  private String agentOutput;

  @Column(name = "AGENT_DATE")
  private Date   agentDate;

  @Column(name = "AGENT_ATTEMPTS", nullable = false)
  private int    agentAttempts;

  @Column(name = "LAST_ERROR")
  private String lastError;

  @Column(name = "CREATED_DATE", nullable = false)
  private Date   createdDate;
}
