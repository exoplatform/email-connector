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

import org.exoplatform.emailConnector.model.DraftState;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

  // Local mirror of the IMAP \Flagged flag ("star"). The server copy is the source of
  // truth: the sync reconcile overwrites this column with whatever the server says, so a
  // star set in Gmail or on a phone shows here and vice versa. Declared last on purpose:
  // Lombok's all-args constructor follows field order, so appending keeps every existing
  // positional call site intact but for one trailing argument.
  @Column(name = "STARRED")
  private boolean                     starred;

  // The four draft columns, null on every row that is not a draft. Appended after
  // STARRED for the reason STARRED itself gives: Lombok's all-args constructor
  // follows field order, and createEmails calls it positionally.

  // The composer's handle on this draft, minted here and never changed. The IMAP UID
  // cannot play this role: saving a draft means appending a new message and deleting
  // the old one, so the UID changes under the composer mid-sentence.
  @Column(name = "DRAFT_LOCAL_ID")
  private String                      draftLocalId;

  // Where this row stands against the copy on the server; see DraftState.
  @Enumerated(EnumType.STRING)
  @Column(name = "DRAFT_STATE")
  private DraftState                  draftState;

  // Counts local edits, so a save carrying text the row has already moved past can be
  // recognised and dropped. Autosaves race on the network; without this the slowest
  // request wins and quietly reverts the newest sentence.
  @Column(name = "DRAFT_REVISION")
  private Long                        draftRevision;

  // When the user last typed. Deliberately not RECEIVED_DATE, which every ORDER BY
  // sorts on and which will carry the server copy's INTERNALDATE once drafts sync.
  @Column(name = "DRAFT_UPDATED_DATE")
  private Date                        draftUpdatedDate;

  // Whether BODY is HTML, as the message itself declared it (the Content-Type of the
  // part the body was taken from). A fact read once at sync rather than re-guessed from
  // the characters on every render, which is what the reader used to do and got wrong.
  // A Boolean, not a boolean, and that is the point: null means "written before this
  // column existed, nobody asked the message", and the storage derives an answer for
  // those rows only. Defaulting them to false would show every cached HTML mail as
  // escaped source; to true, every plain-text one would keep collapsing its line breaks.
  // Declared last for the same reason STARRED and the draft columns were, and now after
  // the four of them: Lombok's all-args constructor follows field order, so this field
  // is the last positional argument and the draft ones keep the places they took.
  @Column(name = "IS_HTML")
  private Boolean                     html;
}
