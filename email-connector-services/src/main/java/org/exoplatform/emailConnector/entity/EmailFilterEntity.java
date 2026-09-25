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
 * One rule eXo runs itself after each sync of the owner's own inbox (changeset
 * 1.0.0-71). The rules the mail server runs at delivery are never stored here.
 * <p>
 * {@link DynamicUpdate}: the sync bumps the counters of a rule while its owner may be
 * saving it from the settings drawer; without it, either write would flush every column
 * from the snapshot it read and undo the other.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@DynamicUpdate
@Entity(name = "EmailFilterEntity")
@Table(name = "EMAIL_FILTER",
    indexes = { @Index(name = "IDX_EMAIL_FILTER_USER", columnList = "USER_ID,POSITION"),
        @Index(name = "UK_EMAIL_FILTER_TAG", columnList = "TAG_KEYWORD", unique = true) })
public class EmailFilterEntity {

  @Id
  @PortableSequence(name = "SEQ_EMAIL_FILTER_ID")
  @Column(name = "ID")
  private Long    id;

  @Column(name = "USER_ID", nullable = false)
  private String  userId;

  @Column(name = "MAILBOX_SCOPE", nullable = false)
  private String  mailboxScope;

  @Column(name = "NAME", nullable = false)
  private String  name;

  @Column(name = "ENABLED", nullable = false)
  private boolean enabled;

  @Column(name = "POSITION", nullable = false)
  private int     position;

  @Column(name = "KIND", nullable = false)
  private String  kind;

  @Column(name = "MATCH_MODE")
  private String  matchMode;

  @Column(name = "CONDITIONS")
  private String  conditions;

  @Column(name = "TAG_KEYWORD")
  private String  tagKeyword;

  @Column(name = "SERVER_RULE_REF")
  private String  serverRuleRef;

  @Column(name = "ACTIONS", nullable = false)
  private String  actions;

  @Column(name = "STOP_PROCESSING", nullable = false)
  private boolean stopProcessing;

  @Column(name = "AGENT_NAME_ID")
  private String  agentNameId;

  @Column(name = "MATCH_COUNT", nullable = false)
  private long    matchCount;

  @Column(name = "LAST_MATCH_DATE")
  private Date    lastMatchDate;

  @Column(name = "LAST_ERROR")
  private String  lastError;

  @Column(name = "ACTIVE_SINCE")
  private Date    activeSince;

  @Column(name = "CREATED_DATE", nullable = false)
  private Date    createdDate;

  @Column(name = "UPDATED_DATE", nullable = false)
  private Date    updatedDate;
}
