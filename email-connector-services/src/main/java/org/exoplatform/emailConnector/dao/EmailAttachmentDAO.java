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
package org.exoplatform.emailConnector.dao;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;

public interface EmailAttachmentDAO extends JpaRepository<EmailAttachmentEntity, Long> {

  /**
   * One cached attachment row, addressed the only way an attachment can be addressed:
   * its owner, the FOLDER its message is in, that message's IMAP UID, and the MIME
   * part path within it.
   * <p>
   * The folder is not a refinement, it is part of the key. IMAP UIDs are per folder,
   * so UID 1212 in INBOX and UID 1212 in Sent are two unrelated messages, and MIME
   * part paths are positional ("2" is simply the second part), so the same path
   * exists in almost every message that carries an attachment at all. Without the
   * folder this matched on (uid, path, owner) alone, and both of the two things that
   * then happen are wrong, in different ways:
   * <ul>
   * <li>both rows match, and a query declared to answer at most one throws
   * {@code IncorrectResultSizeDataAccessException} — the download fails with a 500 the
   * user reads as a broken file;</li>
   * <li>only the OTHER folder's row matches, and it is returned — so the name and
   * content type handed back belong to a different message entirely.</li>
   * </ul>
   * Neither needs an unusual mailbox: two folders numbering their messages
   * independently collide as soon as both are cached.
   *
   * @param mailRemoteId the message's IMAP UID within its folder
   * @param attachmentId the attachment's MIME part path
   * @param username the mailbox owner
   * @param folder the {@link org.exoplatform.emailConnector.model.MailFolder} the
   *          message is cached under
   * @return the attachment row, or empty when the user has no such attachment there
   */
  @Query("SELECT attachment FROM EmailAttachmentEntity attachment LEFT JOIN FETCH attachment.email email WHERE attachment.attachmentRemoteId = :attachmentId "
      + "AND email.mailRemoteId = :mailRemoteId AND email.userId = :username AND email.folder = :folder")
  Optional<EmailAttachmentEntity> findByMailRemoteIdAndAttachmentIdAndUserIdAndFolder(@Param("mailRemoteId")
  long mailRemoteId, @Param("attachmentId")
  String attachmentId, @Param("username")
  String username, @Param("folder")
  String folder);

  /**
   * Every attachment of one draft, oldest first, so the composer's chips keep the
   * order the files were attached in rather than whatever the database returns.
   * <p>
   * Addressed by the draft's LOCAL id, which is the only handle a draft has that
   * survives a save: its IMAP UID changes under the user whenever the draft is
   * re-appended, and it has none at all before the first upload.
   *
   * @param username the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @return the draft's attachments, oldest first, never null
   */
  @Query("SELECT attachment FROM EmailAttachmentEntity attachment LEFT JOIN FETCH attachment.email email "
      + "WHERE email.userId = :username AND email.draftLocalId = :draftLocalId ORDER BY attachment.id ASC")
  List<EmailAttachmentEntity> findByDraftLocalIdAndUserId(@Param("draftLocalId")
  String draftLocalId, @Param("username")
  String username);

  /**
   * One attachment of one draft, by its own row id.
   * <p>
   * The draft's local id is in the key rather than merely checked afterwards, and the
   * owner with it: this is what a download and a detach are addressed by, so the
   * lookup has to be the ownership check. Answering "no such attachment" for
   * somebody else's row is also what keeps a row id from being probed for existence.
   *
   * @param attachmentId the attachment row id
   * @param draftLocalId the composer's handle on the draft
   * @param username the mailbox owner
   * @return the attachment, or empty when this user has no such attachment on it
   */
  @Query("SELECT attachment FROM EmailAttachmentEntity attachment LEFT JOIN FETCH attachment.email email "
      + "WHERE attachment.id = :attachmentId AND email.userId = :username AND email.draftLocalId = :draftLocalId")
  Optional<EmailAttachmentEntity> findByIdAndDraftLocalIdAndUserId(@Param("attachmentId")
  long attachmentId, @Param("draftLocalId")
  String draftLocalId, @Param("username")
  String username);

  /**
   * The stored files hanging off a set of mail rows — read immediately BEFORE those
   * rows are bulk-deleted, because afterwards nothing knows which files they were.
   * <p>
   * See {@link org.exoplatform.emailConnector.entity.EmailOrphanFileEntity} for why
   * this cannot be a cascade or a callback: the delete is a bulk JPQL statement and
   * the attachment rows go with it through the database's own foreign key, entirely
   * outside Java's view.
   * <p>
   * Nulls are filtered in the query rather than by the caller, since almost every row
   * this is asked about is a mirrored message part with no file of its own — on a
   * sync cleanup of a few thousand rows that is the difference between an empty
   * answer and a list of nulls to walk.
   *
   * @param emailIds the mail rows about to be deleted
   * @return the file ids they reference, never null
   */
  @Query("SELECT attachment.fileId FROM EmailAttachmentEntity attachment "
      + "WHERE attachment.email.id IN :emailIds AND attachment.fileId IS NOT NULL")
  List<Long> findFileIdsByEmailIds(@Param("emailIds")
  List<Long> emailIds);
}
