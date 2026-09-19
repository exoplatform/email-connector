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

import org.exoplatform.emailConnector.model.ReadReceiptAnswerOrigin;
import org.exoplatform.emailConnector.model.ReadReceiptState;

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
 * The durable answer to one read-receipt request (EXO-90435): user U answered the
 * request carried by the message whose Message-ID hashes to {@code messageIdHash}. See
 * changeset 1.0.0-68 for the columns.
 * <p>
 * It is a RECORD, not a cache: nothing else remembers the answer once the cached rows
 * of the message are gone (a move, an archive, a reset, the sync window) on a mailbox
 * that stores no keywords (Exchange). That is why it has no foreign key to
 * {@code EMAIL_BOX}, why nothing cascades into it, and why neither a cache reset nor a
 * disconnection deletes it. Its unique index on (USER_ID, MESSAGE_ID_HASH) is the
 * decision that makes an answer, and so a receipt, happen at most once per user and
 * message.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "EmailReadReceiptAnswerEntity")
@Table(name = "EMAIL_READ_RECEIPT_ANSWER")
public class EmailReadReceiptAnswerEntity {

  @Id
  @PortableSequence(name = "SEQ_EMAIL_RR_ANSWER_ID")
  @Column(name = "ID")
  private Long                    id;

  @Column(name = "USER_ID")
  private String                  userId;

  // SHA-256 of the Message-ID, lower-case hex. A hash rather than the id itself: a
  // Message-ID is case-sensitive and up to 1024 characters here, and the unique index
  // must hold under MySQL's case-insensitive collation and within its key length.
  @Column(name = "MESSAGE_ID_HASH")
  private String                  messageIdHash;

  @Enumerated(EnumType.STRING)
  @Column(name = "STATE")
  private ReadReceiptState        state;

  // Who answered, as far as this add-on knows. Not authoritative on the rare row whose
  // LOCAL claim then found a cached copy already answered (see ReadReceiptService#claim):
  // only the row's existence -- "answered" -- is.
  @Enumerated(EnumType.STRING)
  @Column(name = "ORIGIN")
  private ReadReceiptAnswerOrigin origin;

  @Column(name = "ANSWERED_DATE")
  private Date                    answeredDate;
}
