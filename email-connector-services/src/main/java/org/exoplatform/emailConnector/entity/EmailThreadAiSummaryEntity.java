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

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A written summary of one conversation, kept so it is produced once and read many
 * times.
 * <p>
 * It is a CACHE and not a record: every row here can be thrown away and rebuilt from
 * the mail it describes, which is why it has no foreign key to {@code EMAIL_BOX} and
 * nothing cascades into it. The conversation's own rows come and go as the sync
 * window moves, and a summary written from the fuller conversation stays the better
 * answer after the cache has trimmed its oldest messages.
 * <p>
 * Nothing in this add-on writes one. The producer lives in a separate module and
 * reaches this table through {@code EmailBoxService}; until it exists the table stays
 * empty and every read answers "there is none", which is the intended silence rather
 * than a missing piece.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "EmailThreadAiSummaryEntity")
@Table(name = "EMAIL_THREAD_AI_SUMMARY")
public class EmailThreadAiSummaryEntity {

  @Id
  @SequenceGenerator(name = "SEQ_EMAIL_THREAD_AI_SUMMARY_ID", sequenceName = "SEQ_EMAIL_THREAD_AI_SUMMARY_ID",
                     allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_EMAIL_THREAD_AI_SUMMARY_ID")
  @Column(name = "ID")
  private Long    id;

  // The mailbox owner. Half of this table's real key: a thread id is minted out of
  // the Message-IDs one mailbox holds, so two people on the same mailing list
  // legitimately produce the same id for their own copy of the conversation, and a
  // summary describes the copy it was written from.
  @Column(name = "USER_ID")
  private String  userId;

  @Column(name = "THREAD_ID")
  private String  threadId;

  @Column(name = "SUMMARY")
  private String  summary;

  // How many non-draft messages the conversation held when this was written. Half of
  // the staleness fingerprint, and compared with a STRICTLY GREATER THAN rather than
  // an inequality: the local cache trims its oldest messages on its own, so a count
  // that has FALLEN means this summary was written from more of the conversation than
  // is left, which makes it the better answer rather than a stale one.
  @Column(name = "MESSAGE_COUNT")
  private Integer messageCount;

  // The newest non-draft message at the time of writing, as "FOLDER:UID" — the other
  // half of the fingerprint. A folder and an IMAP UID rather than a date, because a
  // date is not an identity: two messages can share one, and drafts (excluded here for
  // exactly this reason) are re-dated on every keystroke.
  @Column(name = "NEWEST_MESSAGE_KEY")
  private String  newestMessageKey;

  // Which producer wrote it, so a deployment that changes producer can tell its old
  // rows from its new ones rather than serving one voice as the other.
  @Column(name = "AGENT_NAME_ID")
  private String  agentNameId;

  @Column(name = "CREATED_DATE")
  private Date    createdDate;
}
