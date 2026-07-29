/**
 * Copyright (C) 2025 eXo Platform SAS
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
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.SequenceGenerator;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Entity(name = "EmailBoxEntity")
@Table(name = "EMAIL_BOX")
public class EmailBoxEntity {

  @Id
  @SequenceGenerator(name = "SEQ_EMAIL_ID", sequenceName = "SEQ_EMAIL_ID", allocationSize = 1)
  @GeneratedValue(strategy = GenerationType.AUTO, generator = "SEQ_EMAIL_ID")
  @Column(name = "ID")
  private Long                        id;

  @Column(name = "MAIL_REMOTE_ID")
  private Long                        mailRemoteId;

  @Column(name = "MAIL_HEADER_ID")
  private String                      mailHeaderId;

  @Column(name = "USER_ID")
  private String                      userId;

  @Column(name = "SUBJECT")
  private String                      subject;

  @Column(name = "BODY")
  private String                      body;

  @Column(name = "SENDER")
  private String                      sender;

  @Column(name = "RECEIVER")
  private String                      to;

  @Column(name = "CC")
  private String                      cc;

  @Column(name = "BCC")
  private String                      bcc;

  @Column(name = "REPLY_TO")
  private String                      replyTo;

  @Column(name = "RECEIVED_DATE")
  private Date                        receivedDate;

  @Column(name = "IS_READ")
  private boolean                     read;

  @Column(name = "RECENT")
  private boolean                     recent;

  @OneToMany(mappedBy = "email", cascade = CascadeType.PERSIST)
  private List<EmailAttachmentEntity> attachments;

  // The conversation this message belongs to: the earliest-known Message-ID of the
  // thread. Persisted (not derived) so a thread survives the eviction of its root.
  @Column(name = "THREAD_ID")
  private String                      threadId;

  @Column(name = "IN_REPLY_TO")
  private String                      inReplyTo;

  // "REFERENCES" is a SQL reserved word, hence the MAIL_REFERENCES column / mailReferences field.
  @Column(name = "MAIL_REFERENCES")
  private String                      mailReferences;

  // The remote folder this message belongs to (INBOX / SENT / ARCHIVE). IMAP UIDs
  // are per-folder, so the cache is keyed by (USER_ID, FOLDER, MAIL_REMOTE_ID).
  @Column(name = "FOLDER")
  private String                      folder;

  // The Exchange Thread-Index conversation-root GUID (hex); messages sharing it are
  // the same conversation even when the References chain is broken.
  @Column(name = "THREAD_INDEX_ROOT")
  private String                      threadIndexRoot;

  // The distribution headers, captured at sync time because they only exist on the live
  // MimeMessage. Kept as raw facts rather than one "is this bulk" verdict: they carry
  // different meanings and the rule that combines them is still being tuned.

  // RFC 3834 Auto-Submitted (other than "no"), or the legacy Precedence: bulk|junk. Means
  // nobody typed this message. Note Precedence: list is deliberately NOT included -- mailing
  // lists stamp it on the human messages they relay.
  @Column(name = "AUTO_SUBMITTED")
  private boolean                     autoSubmitted;

  // List-Id: this message was relayed by a mailing list.
  @Column(name = "HAS_LIST_ID")
  private boolean                     hasListId;

  // List-Post with a postable address: a discussion list you can write back to, which
  // marketing senders rarely set. Together with List-Id this is what separates a colleague
  // writing to a group from a newsletter blast.
  @Column(name = "HAS_LIST_POST")
  private boolean                     hasListPost;

  // List-Unsubscribe: only means the message came through bulk distribution machinery. On its
  // own it says nothing about whether a human wrote it.
  @Column(name = "HAS_LIST_UNSUBSCRIBE")
  private boolean                     hasListUnsubscribe;

  // The real author when a mailing list rewrote From to itself (X-Original-Sender). Empty for
  // directly-delivered mail.
  @Column(name = "ORIGINAL_SENDER")
  private String                      originalSender;
}
