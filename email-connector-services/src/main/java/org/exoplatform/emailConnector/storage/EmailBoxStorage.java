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
package org.exoplatform.emailConnector.storage;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import javax.mail.internet.InternetAddress;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.commons.file.model.FileInfo;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.commons.utils.IOUtil;
import org.exoplatform.emailConnector.dao.EmailAttachmentDAO;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.dao.EmailOrphanFileDAO;
import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.entity.EmailOrphanFileEntity;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ThreadSummary;
import org.exoplatform.emailConnector.plugin.EmailCategoryPlugin;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.EmailThreadingUtils;

import io.meeds.social.category.model.CategoryObject;
import io.meeds.social.category.service.CategoryLinkService;
import lombok.SneakyThrows;

/**
 * Storage service to access / load and save email box. This service will be
 * used, as well, to convert from JPA entity to DTO.
 */
@Component
public class EmailBoxStorage {

  // What a missing display name looked like for as long as the writers concatenated
  // one: the word itself, in the name half of a stored name,address pair. The writers
  // no longer produce it; the rows that already hold it are still being read every
  // day, which is why it is also a value the READERS know by name.
  private static final String NULL_NAME = "null";

  private static final Log    LOG       = ExoLogger.getLogger(EmailBoxStorage.class);

  @Autowired
  private EmailBoxDAO         emailBoxDao;

  @Autowired
  private EmailAttachmentDAO  emailAttachmentDAO;

  @Autowired
  private EmailOrphanFileDAO  emailOrphanFileDAO;

  @Autowired
  private CategoryLinkService categoryLinkService;

  @Autowired
  private FileService         fileService;

  @Autowired
  private UploadService       uploadService;

  public Email createEmail(Email email) {
    if (email == null) {
      throw new IllegalArgumentException("email is mandatory");
    }
    EmailBoxEntity emailBoxEntity = toEntity(email);
    emailBoxEntity = emailBoxDao.save(emailBoxEntity);
    return fromEntity(emailBoxEntity, false, false, null, null, true, false);
  }

  public void markEmailAsNotRecent(Long mailRemoteId, String userId, String folder) {
    emailBoxDao.markEmailAsNotRecent(mailRemoteId, userId, folder);
  }

  /**
   * Clears the recent flag of all the given messages in one statement — the bulk
   * companion of {@link #markEmailAsNotRecent}, introduced when the sync stopped
   * issuing one UPDATE per already-known message. No-op on an empty list, so a
   * steady-state sync touches nothing.
   *
   * @param mailRemoteIds the IMAP UIDs whose recent flag must be cleared
   * @param userId the mailbox owner
   * @param folder the folder discriminator scoping the UIDs
   */
  public void markEmailsAsNotRecent(List<Long> mailRemoteIds, String userId, String folder) {
    if (mailRemoteIds != null && !mailRemoteIds.isEmpty()) {
      emailBoxDao.markEmailsAsNotRecent(mailRemoteIds, userId, folder);
    }
  }

  /**
   * The light view of a folder the sync reconcile runs on: each cached row's id,
   * IMAP UID, threading state and flags — no body, no attachments join, no
   * category-link lookup. The full {@link #getEmails(String, String)} load was one
   * of the two dominant costs of a sync that found nothing new: at 5000 cached
   * messages it pulled every BODY CLOB through the persistence layer and ran one
   * category-link query per row, none of which the sync ever read. Category ids
   * are resolved lazily, only for the rows actually being deleted (see the
   * service's delete path). Ordered newest-first, which cleanupObsoleteEmails
   * relies on to trim the cache overflow off the end.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator
   * @return light {@link Email} DTOs (body, recipients and category ids left null),
   *         newest first
   */
  public List<Email> getSyncEmails(String userId, String folder) {
    return emailBoxDao.findSyncViewByUserIdAndFolder(userId, folder).stream().map(row -> {
      Email email = new Email();
      email.setId((Long) row[0]);
      email.setMailRemoteId((Long) row[1]);
      email.setThreadId((String) row[2]);
      email.setThreadIndexRoot((String) row[3]);
      email.setRead(Boolean.TRUE.equals(row[4]));
      email.setRecent(Boolean.TRUE.equals(row[5]));
      email.setStarred(Boolean.TRUE.equals(row[6]));
      // Carried by the light view because the sync's cleanup has to be able to tell a
      // draft the user is still writing from a message the server no longer has — and
      // "not in the server window" looks identical for the two. See
      // EmailBoxService#cleanupObsoleteEmails.
      email.setDraftState((DraftState) row[7]);
      // And the handle every write to a draft is addressed by, since the Drafts folder
      // joined the sync: a draft whose server copy vanished has to be put back to a
      // state that will re-upload, and the row id and the UID are both unusable for
      // that (the UID moves whenever a draft is re-appended, and the storage layer's
      // draft writes deliberately go by local id).
      email.setDraftLocalId((String) row[8]);
      email.setUserId(userId);
      email.setFolder(folder);
      return email;
    }).toList();
  }

  /**
   * A draft by the composer's own handle on it, loaded in full (body and
   * recipients) because every caller either resumes it in the composer or rebuilds
   * the MIME message from it.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @return the draft, or null when the user has no draft under that id
   */
  public Email getDraftByLocalId(String userId, String draftLocalId) {
    if (StringUtils.isBlank(draftLocalId)) {
      return null;
    }
    List<EmailBoxEntity> entities = emailBoxDao.findByUserIdAndDraftLocalIdWithAttachments(userId, draftLocalId);
    return entities.isEmpty() ? null : fromEntity(entities.get(0), true, false, userId, null, true, false);
  }

  /**
   * Writes a draft: a new row the first time, an in-place update of the same row
   * every time after. This is deliberately NOT {@link #createEmail(Email)} with an
   * id set. createEmail builds a whole entity out of a DTO and hands it to
   * {@code save}, which on an existing id becomes a merge that overwrites every
   * column with whatever the DTO happened to carry — fine for the sync, which owns
   * the entire row it just built from a server message, and wrong for a draft,
   * whose row also carries columns the composer knows nothing about (the thread it
   * was merged into, its Message-ID, the IMAP UID of the copy on the server). So
   * the existing row is loaded and only the fields a draft edit can legitimately
   * change are applied.
   * <p>
   * The revision guard lives here rather than in the service because it is a
   * property of the row, not of the caller: an autosave that arrives carrying a
   * revision the row has already reached is a request from a moment the user has
   * typed past, and applying it would revert their newest sentence. It is dropped,
   * and the row as it stands is returned so the caller can see what won.
   * <p>
   * This method is the EDIT path only. Recording where an uploaded copy landed is
   * {@link #markDraftUploaded}, and it is deliberately not the same call: that write
   * carries no new text and must not look like an edit that has arrived too late.
   *
   * @param draft the draft to write; no matching row means a first save
   * @return the row as it now stands — the incoming draft when it was applied, the
   *         stored one when an out-of-order save was dropped
   */
  public Email saveDraft(Email draft) {
    if (draft == null || StringUtils.isBlank(draft.getDraftLocalId())) {
      throw new IllegalArgumentException("draftLocalId is mandatory");
    }
    List<EmailBoxEntity> existing = emailBoxDao.findByUserIdAndDraftLocalIdWithAttachments(draft.getUserId(), draft.getDraftLocalId());
    if (existing.isEmpty()) {
      return createEmail(draft);
    }
    EmailBoxEntity entity = existing.get(0);
    Long storedRevision = entity.getDraftRevision();
    Long incomingRevision = draft.getDraftRevision();
    if (storedRevision != null && incomingRevision != null && incomingRevision <= storedRevision) {
      return fromEntity(entity, true, false, draft.getUserId(), null, true, false);
    }
    entity.setSubject(draft.getSubject());
    entity.setBody(draft.getContent() != null ? draft.getContent().getBody() : null);
    // Written with the body, because it describes that body and nothing else. This
    // path is the one that does NOT go through toEntity — it mutates the loaded row
    // column by column — so a column left out here is a column that only the very
    // first save of a draft ever writes, the first being the one that goes through
    // createEmail. The body is replaced on every autosave; the answer about its
    // format has to be replaced with it, or the row keeps describing text it no
    // longer holds.
    entity.setHtml(draft.getContent() != null ? draft.getContent().isHtml() : null);
    entity.setTo(toRecipientsString(draft.getTo()));
    entity.setCc(toRecipientsString(draft.getCc()));
    entity.setBcc(toRecipientsString(draft.getBcc()));
    entity.setReceivedDate(draft.getReceivedDate());
    entity.setDraftState(draft.getDraftState());
    entity.setDraftRevision(incomingRevision);
    entity.setDraftUpdatedDate(draft.getDraftUpdatedDate());
    return saveDraftRow(entity, draft.getUserId());
  }

  /**
   * Writes a draft row that was loaded, mutated, and is being stored again — and
   * maps the answer from the instance that was LOADED, deliberately not from the one
   * {@code save} hands back.
   * <p>
   * The three draft writers below all have that shape, and all three used to map
   * {@code save}'s return value. Doing so threw "failed to lazily initialize a
   * collection of role: EmailBoxEntity.attachments - no Session" on every write to an
   * existing draft — which is to say on every autosave after the very first, the first
   * one going through {@link #createEmail} instead. The composer keeps the text on
   * screen through a failed save, so this was a 500 that told the user their words
   * were safe while nothing was being stored.
   * <p>
   * Why the returned instance cannot be mapped: nothing in this class is
   * {@code @Transactional}, so each DAO call runs and commits in a transaction of its
   * own. The entity the caller loaded is therefore detached by the time it gets here,
   * and {@code save} on a detached instance that has an id is a MERGE — it returns a
   * different, managed instance, whose attachments collection is a fresh uninitialised
   * proxy, and whose session closes as {@code save} returns. The first touch of that
   * collection, which is the first thing the mapper does, has no session left to load
   * it in. This is also why the fetch join added to
   * {@link EmailBoxDAO#findByUserIdAndDraftLocalIdWithAttachments} did not settle it:
   * the join initialises the collection of the instance it returns, and that is not
   * the instance the answer was being built from.
   * <p>
   * The instance passed in is that one, its collection initialised by the fetch join,
   * and an already-initialised collection reads fine detached. What it maps is exactly
   * what was written: the merge copies these very values into the row, and no column
   * here is generated or defaulted on write (no {@code @Version}, no lifecycle
   * callback), so there is nothing the database knows about this row that this
   * instance does not.
   * <p>
   * The alternative was to make these writes {@code @Transactional}, which would also
   * work — the fetch and the merge would then share a persistence context, and the
   * merged instance would still be attached while it is mapped. It was not taken:
   * {@link #fromEntity} calls out to {@link CategoryLinkService}, another domain's
   * service against another schema, and that would put a foreign call inside our write
   * transaction and hold the transaction open across it — to lazily re-load a
   * collection this method is already holding. The revision guard's early return in
   * {@link #saveDraft} has always mapped from the loaded instance, for the same reason;
   * this makes the other paths agree with it.
   *
   * @param entity the loaded, mutated draft row
   * @param userId the mailbox owner
   * @return the row as it now stands
   */
  private Email saveDraftRow(EmailBoxEntity entity, String userId) {
    emailBoxDao.save(entity);
    return fromEntity(entity, true, false, userId, null, true, false);
  }

  /**
   * Records that a draft's text now also lives on the server, under a given IMAP
   * UID — the upload path's bookkeeping, and the only place a draft becomes
   * {@link DraftState#SYNCED}.
   * <p>
   * It applies only if the row is still at the revision that was uploaded. The
   * service holds the draft's lock across the whole upload, so in practice nothing
   * can have moved; the check is here anyway because the consequence of being wrong
   * is precisely the failure this feature exists to prevent — a row marked as
   * safely on the server while carrying a sentence that was never sent up. If the
   * revision has moved, the UID is written anyway and the state stays whatever it
   * was, so the next push still runs.
   * <p>
   * That the UID is written unconditionally is load-bearing rather than incidental,
   * and the service side of it is worth stating here because the two halves are far
   * apart: the copy that was just appended is real, and this row is the only record
   * of where it is. Keeping the number is what lets the next push remove it. The
   * service therefore reads the UID, and not the state, to decide whether there is a
   * copy to remove — it once read the state, and a row left LOCAL_ONLY by exactly
   * this branch had its copy skipped and duplicated on the next push.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param mailRemoteId the IMAP UID of the copy just appended
   * @param uploadedRevision the revision whose text was uploaded
   * @return the row as it now stands, or null when there is no such draft
   */
  public Email markDraftUploaded(String userId, String draftLocalId, long mailRemoteId, Long uploadedRevision) {
    List<EmailBoxEntity> existing = emailBoxDao.findByUserIdAndDraftLocalIdWithAttachments(userId, draftLocalId);
    if (existing.isEmpty()) {
      return null;
    }
    EmailBoxEntity entity = existing.get(0);
    entity.setMailRemoteId(mailRemoteId);
    if (Objects.equals(entity.getDraftRevision(), uploadedRevision)) {
      entity.setDraftState(DraftState.SYNCED);
    }
    return saveDraftRow(entity, userId);
  }

  /**
   * Moves a draft's state and nothing else — the send path's claim on the row
   * ({@link DraftState#SENDING}) and the release of that claim when the send comes
   * back refused.
   * <p>
   * Deliberately not routed through {@link #saveDraft}: this write carries no text
   * at all, so passing it through the edit path would make it subject to the
   * revision guard and be dropped as an out-of-order save — and a send claim that
   * can be silently dropped is not a claim. The same reasoning as
   * {@link #markDraftUploaded}, for the same reason.
   * <p>
   * It writes no revision either, so a state change never makes an autosave the
   * user has since typed look stale.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param draftState the state to move the row to
   * @return the row as it now stands, or null when there is no such draft
   */
  public Email updateDraftState(String userId, String draftLocalId, DraftState draftState) {
    List<EmailBoxEntity> existing = emailBoxDao.findByUserIdAndDraftLocalIdWithAttachments(userId, draftLocalId);
    if (existing.isEmpty()) {
      return null;
    }
    EmailBoxEntity entity = existing.get(0);
    entity.setDraftState(draftState);
    return saveDraftRow(entity, userId);
  }

  /**
   * Cuts a draft loose from a server copy that is no longer there: back to
   * {@link DraftState#LOCAL_ONLY}, with the UID cleared.
   * <p>
   * Its own call for the same reason {@link #markDraftUploaded} and
   * {@link #updateDraftState} are: it carries no text, so routing it through
   * {@link #saveDraft} would put it under the revision guard and let it be dropped
   * as a late save — and this write is precisely the one that must not be lost, or
   * the next upload would try to remove a copy that does not exist and leave the
   * user's unsaved words on this side of a mailbox that no longer knows about them.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   */
  public void detachDraftFromServerCopy(String userId, String draftLocalId) {
    if (StringUtils.isBlank(draftLocalId)) {
      return;
    }
    emailBoxDao.detachDraftFromServerCopy(userId, draftLocalId, DraftState.LOCAL_ONLY);
  }

  /**
   * Writes a file the user attached to a draft: the bytes into the platform's file
   * store, then a row of {@code EMAIL_ATTACHMENTS} pointing at it.
   * <p>
   * The row is written through {@link EmailAttachmentDAO} DIRECTLY, and never by
   * adding to the draft entity's {@code attachments} collection and saving the draft.
   * That is not a style preference, it is the defect this branch has already shipped
   * once: nothing in this class is {@code @Transactional}, so the draft entity is
   * detached by the time it gets here, {@code save} on it is a MERGE returning a
   * different instance whose collection is an uninitialised proxy over a session that
   * closes as it returns, and the first read of that collection throws. It also would
   * not work for a second reason — the collection is {@code cascade = PERSIST}, so
   * adding to it persists the new element but REMOVING from it persists nothing at
   * all, and the detach below would silently do nothing.
   * <p>
   * The draft's own row is touched separately, by {@link #touchDraft}, and that is
   * load-bearing: a draft that has already been uploaded skips its next upload when
   * nothing has changed, so a file attached without stepping the revision would be
   * accepted by a synced draft and then never sent. Attaching IS an edit.
   * <p>
   * Order: the file first, the row second, the draft's revision last. A failure part
   * way through leaves at worst a stored file nothing references — which the orphan
   * marker exists to describe, and which is the direction to fail in. The reverse
   * would leave a row pointing at a file that was never written, which every reader
   * would then have to handle.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param uploadId the commons upload the browser produced
   * @param name the file name to show and to send it under
   * @param mimeType its content type, as the client declared it
   * @return the stored attachment, or null when the user has no such draft or the
   *         upload is gone
   */
  public EmailAttachment addDraftAttachment(String userId, String draftLocalId, String uploadId, String name, String mimeType) {
    List<EmailBoxEntity> existing = emailBoxDao.findByUserIdAndDraftLocalIdWithAttachments(userId, draftLocalId);
    if (existing.isEmpty()) {
      return null;
    }
    EmailBoxEntity draft = existing.get(0);
    UploadResource uploadResource = uploadService.getUploadResource(uploadId);
    if (uploadResource == null || uploadResource.getStoreLocation() == null) {
      // Expired, already consumed, or never there. Not an incident and not something
      // to fail the draft over: the user's words are stored, only their file is not.
      LOG.warn("Upload {} is gone; nothing was attached to the draft of user {}", uploadId, userId);
      return null;
    }
    byte[] bytes = readUpload(uploadResource);
    if (bytes == null) {
      return null;
    }
    Long fileId = saveAttachmentFileItem(bytes, StringUtils.defaultIfBlank(name, uploadResource.getFileName()), mimeType);
    if (fileId == null) {
      return null;
    }
    EmailAttachmentEntity attachment = new EmailAttachmentEntity();
    attachment.setEmail(draft);
    // No MIME part path: this file is not part of any message yet. See changeset
    // 1.0.0-44, which had to relax the NOT NULL this line would otherwise violate.
    attachment.setAttachmentRemoteId(null);
    attachment.setName(StringUtils.defaultIfBlank(name, uploadResource.getFileName()));
    attachment.setMimeType(StringUtils.defaultIfBlank(mimeType, "application/octet-stream"));
    attachment.setFileId(fileId);
    attachment.setFileSize((long) bytes.length);
    attachment = emailAttachmentDAO.save(attachment);
    touchDraft(userId, draftLocalId);
    return fromEmailAttachmentEntity(attachment);
  }

  /**
   * Removes one file from a draft: the row goes, and the file it pointed at is
   * recorded as unreferenced.
   * <p>
   * Recorded rather than deleted, deliberately, and it is the same rule the bulk
   * delete follows — one mechanism frees files, and it is the sweep. Deleting here
   * as well would be a second path with its own ordering to get right, for bytes that
   * are not urgent. The cost is that the bytes outlive the row until the sweep ships;
   * the marker is the record that they do.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param attachmentId the attachment row id
   * @return true when an attachment of that draft was found and removed
   */
  public boolean removeDraftAttachment(String userId, String draftLocalId, long attachmentId) {
    EmailAttachmentEntity attachment = emailAttachmentDAO.findByIdAndDraftLocalIdAndUserId(attachmentId, draftLocalId, userId)
                                                         .orElse(null);
    if (attachment == null) {
      return false;
    }
    Long fileId = attachment.getFileId();
    emailAttachmentDAO.delete(attachment);
    if (fileId != null) {
      recordOrphanFiles(List.of(fileId), userId);
    }
    touchDraft(userId, draftLocalId);
    return true;
  }

  /**
   * One attachment of one draft, addressed by its row id — what a download and a
   * detach both resolve first.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param attachmentId the attachment row id
   * @return the attachment without its bytes, or null when there is no such one
   */
  public EmailAttachment getDraftAttachment(String userId, String draftLocalId, long attachmentId) {
    return fromEmailAttachmentEntity(emailAttachmentDAO.findByIdAndDraftLocalIdAndUserId(attachmentId, draftLocalId, userId)
                                                       .orElse(null));
  }

  /**
   * Every attachment of one draft, oldest first — what the send path reads to put the
   * files back onto the message.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @return the attachments, oldest first, never null
   */
  public List<EmailAttachment> getDraftAttachments(String userId, String draftLocalId) {
    if (StringUtils.isBlank(draftLocalId)) {
      return List.of();
    }
    return emailAttachmentDAO.findByDraftLocalIdAndUserId(draftLocalId, userId)
                             .stream()
                             .map(this::fromEmailAttachmentEntity)
                             .filter(Objects::nonNull)
                             .toList();
  }

  /**
   * Marks a draft as edited: a stepped revision, a fresh edit time, and a state that
   * will make the next push re-upload it.
   * <p>
   * This is what makes attaching and detaching count as changes. A draft that is
   * already {@link DraftState#SYNCED} skips its next upload when nothing moved, so
   * without this a synced draft would accept a file and never send it — which the
   * design plan named as the most likely bug in the feature, and it is: everything
   * on screen looks right.
   * <p>
   * The revision is stepped from the ROW rather than from anything the client sent,
   * because the client did not send one — attaching is not an autosave and carries no
   * composed state. Stepping it here is also what makes the composer's next autosave,
   * which will carry the revision it believed in, land on the correct side of the
   * revision guard: it arrives stale, is dropped, and the composer picks the row's
   * revision back up out of the answer.
   * <p>
   * {@link DraftState#LOCAL_ONLY} is preserved rather than overwritten with DIRTY,
   * the same rule {@code buildNextDraftRevision} follows: LOCAL_ONLY means "no copy
   * has ever been up there", which stays true, and it is what the composer reads to
   * tell the user their words live only here.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   */
  private void touchDraft(String userId, String draftLocalId) {
    List<EmailBoxEntity> existing = emailBoxDao.findByUserIdAndDraftLocalIdWithAttachments(userId, draftLocalId);
    if (existing.isEmpty()) {
      return;
    }
    EmailBoxEntity entity = existing.get(0);
    Date now = new Date();
    entity.setDraftRevision(entity.getDraftRevision() == null ? 1L : entity.getDraftRevision() + 1);
    entity.setDraftUpdatedDate(now);
    // Recency, which is what this column means, and attaching a file is activity: the
    // Drafts listing and the conversation list are both newest-first, and the sync
    // cleanup trims the oldest end of the cache.
    entity.setReceivedDate(now);
    entity.setDraftState(DraftState.LOCAL_ONLY.equals(entity.getDraftState()) ? DraftState.LOCAL_ONLY : DraftState.DIRTY);
    emailBoxDao.save(entity);
  }

  /**
   * Writes attachment bytes into the add-on's file namespace — the same shape the
   * contact photo and the connector illustration use, and deliberately the same
   * namespace ({@link EmailConnectorStorage#NAME_SPACE}).
   * <p>
   * That the namespace is SHARED with those two is worth stating where the writing
   * happens, because it forbids a whole family of cleanups: nothing may ever sweep
   * this namespace by "which files the mail tables do not reference", since the other
   * two owners' files do not appear there and would all be deleted. A file is freed
   * because something recorded it as unreferenced, never because a scan failed to
   * find it.
   * <p>
   * Always a fresh file, never an update in place: an attachment is not replaced, it
   * is removed and another is attached.
   *
   * @param bytes the file content
   * @param name the file name to store it under
   * @param mimeType its content type
   * @return the stored file id, or null when the file service wrote nothing
   */
  private Long saveAttachmentFileItem(byte[] bytes, String name, String mimeType) {
    try {
      FileItem fileItem = new FileItem(null,
                                       name,
                                       StringUtils.defaultIfBlank(mimeType, "application/octet-stream"),
                                       EmailConnectorStorage.NAME_SPACE,
                                       bytes.length,
                                       new Date(),
                                       null,
                                       false,
                                       new ByteArrayInputStream(bytes));
      FileItem stored = fileService.writeFile(fileItem);
      return stored == null || stored.getFileInfo() == null ? null : stored.getFileInfo().getId();
    } catch (Exception e) {
      // A file that cannot be stored costs a file, not the draft the user is writing.
      LOG.warn("A draft attachment could not be written to the file store", e);
      return null;
    }
  }

  /**
   * Reads a stored draft attachment back, bytes and content type together — the REST
   * layer needs both to answer with an honest header.
   *
   * @param fileId the file id
   * @return the file item, or null when nothing is stored under that id
   */
  public FileItem getAttachmentFileItem(Long fileId) {
    if (fileId == null || fileId <= 0) {
      return null;
    }
    try {
      FileItem fileItem = fileService.getFile(fileId);
      return fileItem == null || fileItem.getAsByte() == null ? null : fileItem;
    } catch (Exception e) {
      LOG.warn("The stored draft attachment {} could not be read", fileId, e);
      return null;
    }
  }

  /**
   * Whether the file behind a stored attachment is still there — the cheap question,
   * asked before a draft is assembled into a message.
   * <p>
   * Metadata only, and deliberately: {@link #getAttachmentFileItem} reads the whole
   * file into memory, and asking "can this 20 MB file be read" by reading 20 MB is a
   * poor way to answer a question whose real subject is whether a row outlived its
   * file. What is checked is what can go wrong here — the file was freed while the
   * attachment row still names it — and a file whose metadata is present but whose
   * bytes are not fails one layer down, where the part is written.
   * <p>
   * A deleted file counts as gone. The file service keeps the row and flips a flag
   * rather than removing it, so "there is a FileInfo" is not the same question as
   * "there is a file".
   *
   * @param fileId the file id an attachment row carries
   * @return true when a live file sits behind it
   */
  public boolean attachmentFileExists(Long fileId) {
    if (fileId == null || fileId <= 0) {
      return false;
    }
    try {
      FileInfo fileInfo = fileService.getFileInfo(fileId);
      return fileInfo != null && !fileInfo.isDeleted();
    } catch (Exception e) {
      LOG.warn("The stored draft attachment {} could not be looked up", fileId, e);
      return false;
    }
  }

  /**
   * Records files as no longer referenced by anything, so that a later sweep can free
   * them.
   * <p>
   * Already-recorded ids are read and skipped rather than left to the unique index to
   * reject: this runs on a delete path, and a constraint violation would abort the
   * surrounding statement rather than the one row — losing an entire cleanup because
   * one file had already been recorded by an overlapping one is the wrong trade for a
   * race that is entirely ordinary here.
   *
   * @param fileIds the files to record; nulls and duplicates are tolerated
   * @param userId the owner the files hung off, for scoping and attribution
   */
  public void recordOrphanFiles(List<Long> fileIds, String userId) {
    if (fileIds == null || fileIds.isEmpty()) {
      return;
    }
    Set<Long> candidates = new HashSet<>(fileIds);
    candidates.remove(null);
    if (candidates.isEmpty()) {
      return;
    }
    candidates.removeAll(new HashSet<>(emailOrphanFileDAO.findRecordedFileIds(new ArrayList<>(candidates))));
    if (candidates.isEmpty()) {
      return;
    }
    Date now = new Date();
    emailOrphanFileDAO.saveAll(candidates.stream()
                                         .map(fileId -> new EmailOrphanFileEntity(null, fileId, userId, now))
                                         .toList());
  }

  /**
   * Whether a folder's cache already holds a message under a given Message-ID —
   * the identity test behind the stray-draft janitor, which removes a Drafts entry
   * whose Message-ID is already in Sent.
   * <p>
   * Message-ID equality and nothing else. It is the only cross-folder identity a
   * message actually has, and it is exact: subject, recipients or date would each
   * be a guess about two different messages being "the same one", which is the kind
   * of guess that deletes somebody's unsent words.
   *
   * @param userId the mailbox owner
   * @param mailHeaderId the Message-ID to look for
   * @param folder the folder discriminator to look in
   * @return true when the folder's cache holds a message under that Message-ID
   */
  public boolean isMessageCachedInFolder(String userId, String mailHeaderId, String folder) {
    if (StringUtils.isBlank(mailHeaderId)) {
      return false;
    }
    return emailBoxDao.countByMailHeaderIdAndUserIdAndFolder(mailHeaderId, userId, folder) > 0;
  }

  public void updateEmailReadStatusByMailRemoteIds(List<Long> mailRemoteIds, String userId, boolean readStatus, String folder) {
    emailBoxDao.updateReadStatusByMailRemoteIds(mailRemoteIds, userId, readStatus, folder);
  }

  /**
   * Sets or clears the starred flag of all the given messages in one statement —
   * the starred twin of {@link #updateEmailReadStatusByMailRemoteIds}, and bulk for
   * the same reason: the sync reconcile applies its whole starred diff through one
   * call per direction, never one statement per message.
   *
   * @param mailRemoteIds the IMAP UIDs to update
   * @param userId the mailbox owner
   * @param starred the target starred value
   * @param folder the folder discriminator scoping the UIDs
   */
  public void updateEmailStarredStatusByMailRemoteIds(List<Long> mailRemoteIds, String userId, boolean starred, String folder) {
    if (mailRemoteIds != null && !mailRemoteIds.isEmpty()) {
      emailBoxDao.updateStarredStatusByMailRemoteIds(mailRemoteIds, userId, starred, folder);
    }
  }

  /**
   * The {@code References} header of a cached message, looked up by its Message-ID, so
   * a reply can extend the parent's chain rather than replace it. Null when the parent
   * is no longer cached (its window slot was reclaimed).
   */
  public String getMailReferencesByMailHeaderId(String mailHeaderId, String userId) {
    List<EmailBoxEntity> entities = emailBoxDao.findByMailHeaderIdAndUserId(mailHeaderId, userId);
    return entities.isEmpty() ? null : entities.get(0).getMailReferences();
  }

  /**
   * The distinct thread ids of the cached messages a new message points back to (by
   * Message-ID). Empty when it references nothing cached — i.e. it starts a new thread.
   */
  public List<String> getSiblingThreadIds(String userId, List<String> mailHeaderIds) {
    if (mailHeaderIds == null || mailHeaderIds.isEmpty()) {
      return List.of();
    }
    return emailBoxDao.findDistinctThreadIdsByMailHeaderIds(userId, mailHeaderIds);
  }

  /**
   * The distinct thread ids of already-cached messages that point back AT the given
   * message — its Message-ID appears in their {@code References} / {@code In-Reply-To}
   * — the reverse of {@link #getSiblingThreadIds}. Without this direction a reply
   * cached before its parent is invisible to the parent, and the conversation
   * silently splits in two; with both directions, thread grouping no longer depends
   * on the order messages are cached in.
   * <p>
   * The matching runs HERE, in Java, over the chains the DAO returns — not as a SQL
   * substring function. The References column is CLOB on some dialects (HSQLDB),
   * where {@code LOCATE} throws {@code SQLFeatureNotSupportedException}; the first
   * live reset with a SQL-side match aborted the sync and cached nothing. Matching in
   * Java is dialect-proof, and cheap: the candidate set is bounded by the per-user
   * cache cap, hundreds of short strings against a sync budget that is pure IMAP
   * latency. The id is normalized to its angle-bracketed RFC 5322 form before
   * matching, because the brackets are what makes the containment check token-exact:
   * {@code <a@host>} matches neither {@code <xa@host>} nor {@code <a@host.com>},
   * while a bare {@code a@host} would match both.
   *
   * @param userId the mailbox owner
   * @param messageId the message's own Message-ID, with or without angle brackets
   * @return the distinct thread ids of the cached messages referencing it, never null
   */
  public List<String> getThreadIdsReferencingMessageId(String userId, String messageId) {
    if (StringUtils.isBlank(messageId)) {
      return List.of();
    }
    String bracketedId = messageId.startsWith("<") && messageId.endsWith(">") ? messageId : "<" + messageId + ">";
    return emailBoxDao.findThreadReferenceChainsByUserId(userId)
                      .stream()
                      .filter(chain -> chainContains((String) chain[1], bracketedId)
                          || chainContains((String) chain[2], bracketedId))
                      .map(chain -> (String) chain[0])
                      .distinct()
                      .toList();
  }

  /**
   * Whether a raw {@code References} / {@code In-Reply-To} header value contains the
   * given angle-bracketed Message-ID. A plain containment check is exact here: ids
   * cannot contain {@code <} or {@code >} internally, so the brackets delimit the
   * token on both sides.
   *
   * @param chain the raw header value, may be null
   * @param bracketedId the angle-bracketed Message-ID to look for
   * @return true when the chain references the id
   */
  private boolean chainContains(String chain, String bracketedId) {
    return chain != null && chain.contains(bracketedId);
  }

  /**
   * The distinct thread ids of cached messages sharing an Exchange Thread-Index
   * conversation root — the same conversation even when References is broken.
   */
  public List<String> getThreadIdsByThreadIndexRoot(String userId, String threadIndexRoot) {
    if (threadIndexRoot == null || threadIndexRoot.isEmpty()) {
      return List.of();
    }
    return emailBoxDao.findDistinctThreadIdsByThreadIndexRoot(userId, threadIndexRoot);
  }

  /**
   * Of several thread ids, the one whose earliest message is oldest — the canonical id
   * a merge collapses the others into.
   */
  public String getOldestThreadId(String userId, List<String> threadIds) {
    if (threadIds == null || threadIds.isEmpty()) {
      return null;
    }
    List<String> ordered = emailBoxDao.findThreadIdsOrderedByAge(userId, threadIds);
    return ordered.isEmpty() ? null : ordered.get(0);
  }

  /**
   * Of the given IMAP UIDs, the ones already cached in a folder — the bulk
   * lookup behind the search results' {@code cached} flag: one IN query for the
   * whole hit list, never a per-hit statement. No-op on an empty list.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator scoping the UIDs
   * @param mailRemoteIds the candidate IMAP UIDs
   * @return the subset of {@code mailRemoteIds} present in the local cache,
   *         never null
   */
  public List<Long> getCachedMailRemoteIds(String userId, String folder, List<Long> mailRemoteIds) {
    if (mailRemoteIds == null || mailRemoteIds.isEmpty()) {
      return List.of();
    }
    return emailBoxDao.findCachedMailRemoteIds(userId, folder, mailRemoteIds);
  }

  public void mergeThreads(String userId, String canonicalThreadId, List<String> threadIds) {
    if (threadIds != null && !threadIds.isEmpty()) {
      emailBoxDao.mergeThreads(userId, canonicalThreadId, threadIds);
    }
  }

  public void updateThreadInfo(String userId, Long mailRemoteId, String threadId, String inReplyTo, String mailReferences, String folder, String threadIndexRoot) {
    emailBoxDao.updateThreadInfo(userId, mailRemoteId, threadId, inReplyTo, mailReferences, folder, threadIndexRoot);
  }

  public void updateThreadIndexRoot(String userId, Long mailRemoteId, String folder, String threadIndexRoot) {
    emailBoxDao.updateThreadIndexRoot(userId, mailRemoteId, folder, threadIndexRoot);
  }

  public Email getEmailByMailRemoteIdAndUserId(long mailRemoteId,
                                               String userId,
                                               String userEmail,
                                               String folder,
                                               boolean withAttachments,
                                               boolean withRecipients,
                                               boolean withProfile) {
    EmailBoxEntity emailBoxEntity = emailBoxDao.findByMailRemoteIdAndUserIdAndFolder(mailRemoteId, userId, folder);
    return fromEntity(emailBoxEntity, withAttachments, false, userId, userEmail, withRecipients, withProfile);
  }

  public Email getEmailById(long id, String userId, String userEmail) {
    EmailBoxEntity emailBoxEntity = emailBoxDao.findById(id).orElse(null);
    return fromEntity(emailBoxEntity, true, false, userId, userEmail, true, true);
  }

  public List<Email> getEmails(String userId) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findByUserIdWithAttachments(userId);
    return emailBoxEntities.stream()
                           .map(emailBoxEntity -> fromEntity(emailBoxEntity, true, true, userId, null, false, false))
                           .toList();
  }

  public List<Email> getEmails(String userId, String folder) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findByUserIdAndFolderWithAttachments(userId, folder);
    return emailBoxEntities.stream()
                           .map(emailBoxEntity -> fromEntity(emailBoxEntity, true, true, userId, null, false, false))
                           .toList();
  }

  /**
   * The starred messages of a folder, for the list's starred filter. Filtered in SQL
   * (see the DAO) rather than over {@link #getEmails(String, String)}'s result, so a
   * mailbox with three starred messages does not load its whole cached folder, body
   * CLOBs included, to keep three rows.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator
   * @return the folder's starred messages, newest first
   */
  public List<Email> getStarredEmails(String userId, String folder) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findStarredByUserIdAndFolderWithAttachments(userId, folder);
    return emailBoxEntities.stream()
                           .map(emailBoxEntity -> fromEntity(emailBoxEntity, true, true, userId, null, false, false))
                           .toList();
  }

  /**
   * What the list needs to know about each of the user's conversations that the
   * folder it is listing cannot tell it: the full cross-folder message count
   * (Gmail-style, rather than only the messages that happen to be in the folder on
   * screen), whether the conversation carries a draft, and — where it does — who the
   * conversation is with.
   * <p>
   * Keyed by thread id so the caller decorates a page of rows by lookup instead of
   * by a query per row. Two statements for the whole listing, not one, and the split
   * is about cost rather than tidiness: the count is
   * {@code COUNT(DISTINCT Message-ID)} over the conversation's rows, and adding the
   * sender to that {@code GROUP BY} would break it. A sent draft is cached twice for
   * a while — the DRAFTS row and the SENT copy that went out under the very
   * Message-ID it minted — and the two rows carry the sender written by two
   * different code paths, so grouping by sender would count them as two messages and
   * the number beside the participants would jump as the user pressed Send, which is
   * the exact behaviour {@link EmailBoxDAO#summarizeThreadsByUserId} was shaped to
   * avoid. The second statement is scoped to draft-carrying conversations and
   * normally answers nothing at all.
   *
   * @param userId the mailbox owner
   * @param userEmail the owner's own address, kept OUT of the participant names —
   *          Gmail's convention is "me", never your own display name, and a draft's
   *          sender is always the owner
   * @return a map of thread id to its summary, never null
   */
  public Map<String, ThreadSummary> getThreadSummaries(String userId, String userEmail) {
    Map<String, List<String>> participants = getDraftThreadParticipants(userId, userEmail);
    Map<String, ThreadSummary> summaries = new HashMap<>();
    for (Object[] row : emailBoxDao.summarizeThreadsByUserId(userId)) {
      String threadId = (String) row[0];
      // The draft column is a SUM, so it can be null on a dialect that returns no
      // rows to add up; "no drafts" is the honest reading of that.
      boolean hasDraft = row[2] != null && ((Number) row[2]).intValue() > 0;
      summaries.put(threadId,
                    new ThreadSummary(threadId,
                                      ((Number) row[1]).intValue(),
                                      hasDraft,
                                      participants.getOrDefault(threadId, List.of())));
    }
    return summaries;
  }

  /**
   * Who each draft-carrying conversation is with, oldest correspondent first — the
   * names a draft row is labelled with in place of its own sender.
   * <p>
   * Three things happen to the raw rows here rather than in SQL, all of them because
   * SQL is the wrong place for them:
   * <ul>
   * <li>The owner is dropped. Their address is the account binding's, which the
   * database does not know; and it is the sender of every draft and of every sent
   * copy, so a conversation would otherwise name the user to themselves. Gmail says
   * "me" and only ever alongside somebody else — that is a change to how EVERY row
   * of the list is labelled, not just a draft's, and is deliberately not made here.
   * A conversation the owner is alone in therefore contributes no name and the row
   * reads "Draft", which is what Gmail shows for a draft that answers nothing.</li>
   * <li>The same person is folded into one name. A correspondent can be cached both
   * with a display name and without one (a message whose From carries no personal
   * part), which are two different stored senders for one address; a naive list
   * would name them twice, once properly and once as a bare address. The address is
   * the identity, and a name is preferred to an address for it.</li>
   * <li>The order is fixed. The rows come out of a {@code GROUP BY} with no order of
   * their own, and this listing is polled — names that re-shuffle between two polls
   * are a visible flicker. Oldest first is also the order the conversation itself
   * reads in.</li>
   * </ul>
   * The names are resolved exactly as the listing resolves a row's own sender —
   * {@code EmailConnectorUtils.getEmailSender} with no profile lookup, the same
   * arguments {@code fromEntity} passes for a listed row — so a draft row and the
   * conversation's own row cannot end up calling the same person two different
   * things. No directory lookup, no query per name.
   *
   * @param userId the mailbox owner
   * @param userEmail the owner's own address, excluded from every conversation
   * @return a map of thread id to its participant names, holding only the
   *         conversations that carry a draft, never null
   */
  private Map<String, List<String>> getDraftThreadParticipants(String userId, String userEmail) {
    List<Object[]> rows = new ArrayList<>(emailBoxDao.findDraftThreadParticipantsByUserId(userId));
    rows.sort(Comparator.comparing((Object[] row) -> (Date) row[2], Comparator.nullsLast(Comparator.naturalOrder()))
                        .thenComparing(row -> StringUtils.defaultString((String) row[1])));
    Map<String, Map<String, String>> namesByAddress = new HashMap<>();
    for (Object[] row : rows) {
      String[] senderParts = splitStoredPerson((String) row[1]);
      String address = senderParts[1];
      if (StringUtils.isBlank(address) || StringUtils.equalsIgnoreCase(address, userEmail)) {
        continue;
      }
      // Insertion-ordered, so the map IS the display order once it is filled. Keyed
      // on the address folded with ROOT rather than the default locale: the mailbox
      // is read under whatever locale the server runs in, and a Turkish one folds I
      // to a dotless letter, which would make two spellings of one address two people
      // on some deployments and not on others.
      Map<String, String> names = namesByAddress.computeIfAbsent((String) row[0], threadId -> new LinkedHashMap<>());
      String key = address.toLowerCase(Locale.ROOT);
      // Overwrite only a placeholder — the address standing in for a name we did not
      // have yet — never a real name with a later row's, which would let the last
      // message win over the first.
      if (!names.containsKey(key) || names.get(key).equals(address)) {
        names.put(key, participantName(senderParts));
      }
    }
    Map<String, List<String>> participants = new HashMap<>();
    namesByAddress.forEach((threadId, names) -> participants.put(threadId, List.copyOf(names.values())));
    return participants;
  }

  /**
   * What to call the person behind a stored sender column, as the list calls them.
   * <p>
   * It goes through the same helper the row mapper does instead of reading the name
   * half of the column directly: the helper decodes an encoded display name and
   * falls back to the address when there is none, and a second implementation of
   * that would be a second answer to "what is this person called" on the same
   * screen. No profile is resolved, because a listed row's sender is not resolved
   * either.
   *
   * @param storedSenderParts the {@code [name, address]} of a stored sender column
   * @return the name to show
   */
  @SneakyThrows
  private String participantName(String[] storedSenderParts) {
    EmailSender sender = EmailConnectorUtils.getEmailSender(new InternetAddress(storedSenderParts[1], storedSenderParts[0]),
                                                           false);
    return sender == null ? storedSenderParts[1] : sender.getName();
  }

  /**
   * The number of cached messages per folder, so the list's folder switch can hide
   * folders that have no mail (e.g. Archive on a Gmail account).
   *
   * @param userId the mailbox owner
   * @return a map of folder discriminator to its message count
   */
  public Map<String, Integer> getFolderMessageCounts(String userId) {
    Map<String, Integer> counts = new HashMap<>();
    for (Object[] row : emailBoxDao.countMessagesByFolder(userId)) {
      counts.put((String) row[0], ((Number) row[1]).intValue());
    }
    return counts;
  }

  /**
   * All cached messages of a conversation, across every folder (INBOX, SENT,
   * ARCHIVE), oldest first — the read model for the conversation reader. Bodies
   * and recipients are loaded so each message renders in full.
   * <p>
   * Mail reads in the order the query returns it, by date. A DRAFT reads after the
   * message it answers, which its date cannot say and its In-Reply-To can: see
   * {@link EmailThreadingUtils#positionDraftsAfterTheirParent}. Applied here, at the
   * one read every conversation goes through, rather than at its two callers — the
   * order of a read model is part of the read model, the way the participant order
   * of a thread summary already is, and the caller added next would otherwise have to
   * remember.
   *
   * @param userId the mailbox owner
   * @param threadId the conversation id
   * @param userEmail the owner's own address, for the "me" resolution on each row
   * @return the conversation's messages in reading order, never null
   */
  public List<Email> getEmailsByThreadId(String userId, String threadId, String userEmail) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findByUserIdAndThreadIdWithAttachments(userId, threadId);
    return EmailThreadingUtils.positionDraftsAfterTheirParent(emailBoxEntities.stream()
                                                                             .map(emailBoxEntity -> fromEntity(emailBoxEntity,
                                                                                                               true,
                                                                                                               false,
                                                                                                               userId,
                                                                                                               userEmail,
                                                                                                               true,
                                                                                                               true))
                                                                             .toList());
  }

  public long countUnreadEmails(String userId) {
    return emailBoxDao.countUnreadByUserId(userId);
  }

  /**
   * Deletes mail rows, having first written down the stored files they were the only
   * reference to.
   * <p>
   * The two statements cannot be one, and the order between them is the design rather
   * than a detail. {@link EmailBoxDAO#deleteEmailsByIds} is a bulk JPQL DELETE: no
   * JPA cascade, no entity callback, no loaded instance — the attachment rows go with
   * it through the database's own {@code ON DELETE CASCADE} (changeset 1.0.0-6), and
   * nothing in Java ever observes that happening. So there is nowhere to hang "and
   * free the file", and once the rows are gone nothing anywhere knows which files
   * they named. They have to be read first.
   * <p>
   * Recording BEFORE deleting means a crash in between leaves a marker for a file
   * that is still referenced — harmless, because the sweep verifies before it
   * deletes. The other order would leave bytes in the file store that nothing names
   * and nothing can find: a leak with no record of itself. A note nobody needed beats
   * a file nobody can reach.
   * <p>
   * The owner is not passed in because the caller's rows may span users (the
   * disconnect cleanup) and the marker's user is bookkeeping rather than a key. It is
   * read off the rows about to go.
   *
   * @param emailsIds the mail rows to delete
   */
  public void deleteEmailsByIds(List<Long> emailsIds) {
    if (emailsIds == null || emailsIds.isEmpty()) {
      return;
    }
    List<Long> fileIds = emailAttachmentDAO.findFileIdsByEmailIds(emailsIds);
    if (!fileIds.isEmpty()) {
      recordOrphanFiles(fileIds, ownerOf(emailsIds));
    }
    emailBoxDao.deleteEmailsByIds(emailsIds);
  }

  /**
   * The owner of the first of a set of rows, for the orphan marker's bookkeeping.
   * <p>
   * One lookup for the whole batch rather than one per file: every caller that
   * deletes rows carrying files deletes one user's rows (a draft discarded, a draft
   * sent, one mailbox's sync cleanup), and the marker's user is an attribution, not a
   * key — the sweep frees a file because it was recorded, not because of whose it was.
   *
   * @param emailsIds the rows about to be deleted
   * @return the owner, or null when the rows are already gone
   */
  private String ownerOf(List<Long> emailsIds) {
    return emailBoxDao.findById(emailsIds.get(0)).map(EmailBoxEntity::getUserId).orElse(null);
  }

  /**
   * The bytes behind a commons upload.
   *
   * @param uploadResource the upload the browser produced
   * @return its content, or null when the temporary file cannot be read
   */
  private byte[] readUpload(UploadResource uploadResource) {
    try {
      return IOUtil.getFileContentAsBytes(uploadResource.getStoreLocation());
    } catch (Exception e) {
      LOG.warn("The upload backing a draft attachment could not be read", e);
      return null;
    }
  }

  /**
   * The light view contact collection reads: for each cached message of a
   * folder, its sender, To/Cc recipients, the distribution headers and the
   * received date — mapped into partial {@link Email} DTOs (body, attachments
   * and categories left null) so the collection rules run on the same shapes
   * {@link EmailConnectorUtils#getMailType} judges. No profile resolution: the
   * store joins the directory at read time, never at collection time.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator
   * @param mailRemoteIds the IMAP UIDs to restrict to, or null for the whole
   *          folder (the backfill pass)
   * @return light {@link Email} DTOs, never null
   */
  public List<Email> getContactSourceEmails(String userId, String folder, List<Long> mailRemoteIds) {
    List<Object[]> rows = mailRemoteIds == null ? emailBoxDao.findContactSourceRowsByUserIdAndFolder(userId, folder)
                                                : mailRemoteIds.isEmpty() ? List.of()
                                                                          : emailBoxDao.findContactSourceRowsByUserIdAndFolderAndUids(userId,
                                                                                                                                      folder,
                                                                                                                                      mailRemoteIds);
    return rows.stream().map(row -> {
      Email email = new Email();
      email.setUserId(userId);
      email.setFolder(folder);
      email.setSender(toLightSender((String) row[0]));
      email.setTo(EmailConnectorUtils.getEmailRecipients(toRecipientsInternetAddresses((String) row[1]), userId, false));
      email.setCc(EmailConnectorUtils.getEmailRecipients(toRecipientsInternetAddresses((String) row[2]), userId, false));
      email.setAutoSubmitted(Boolean.TRUE.equals(row[3]));
      email.setHasListId(Boolean.TRUE.equals(row[4]));
      email.setHasListPost(Boolean.TRUE.equals(row[5]));
      email.setHasListUnsubscribe(Boolean.TRUE.equals(row[6]));
      email.setOriginalSender((String) row[7]);
      email.setReceivedDate((java.util.Date) row[8]);
      return email;
    }).toList();
  }

  /**
   * Decodes the stored {@code name,address} sender string without touching the
   * directory.
   *
   * @param stored the stored sender string
   * @return the sender, or null for a blank value
   */
  private EmailSender toLightSender(String stored) {
    if (StringUtils.isBlank(stored)) {
      return null;
    }
    String[] parts = splitStoredPerson(stored);
    return new EmailSender(StringUtils.isBlank(parts[0]) ? parts[1] : parts[0], parts[1], null, null);
  }

  /**
   * Takes a stored {@code name,address} pair apart, and is the ONLY place that knows
   * how that shape is written — the sender column, each entry of the To/Cc/Bcc/
   * Reply-To columns, the full mapper and the light contact view all come through
   * here, so no two of them can disagree about what a person looks like.
   * <p>
   * Three things it does that the readers used to get wrong, all of which a draft
   * made ordinary rather than theoretical:
   * <ul>
   * <li>It tolerates a value with no comma, and a blank one. The mapper indexed
   * {@code split(",")[1]} with nothing checked, on the reasoning that every row was
   * built from a delivered message and so had a From header. A draft is the first row
   * written HERE, and a blank sender column is written for any DTO that reaches
   * {@code toEntity} without one — after which every read of that row threw, not just
   * the read of its sender.</li>
   * <li>It splits on the LAST comma rather than the first. The address cannot contain
   * one; a display name legitimately can ("Doe, Jane"), and the platform profile name
   * a draft's own sender is stamped with is exactly such a name. Splitting on the
   * first comma handed back that name's tail as the address — which the recipient
   * reader still did, having been fixed nowhere, because it had its own copy of the
   * split.</li>
   * <li>It answers {@link #storedName}, so a column that carries the word
   * {@code null} comes back with no name rather than with that word as one.</li>
   * </ul>
   *
   * @param stored the stored pair, may be null or blank
   * @return {@code [name, address]}, the name null when the column carries none, the
   *         address never null
   */
  private static String[] splitStoredPerson(String stored) {
    if (StringUtils.isBlank(stored)) {
      return new String[] { null, "" };
    }
    int lastComma = stored.lastIndexOf(',');
    if (lastComma < 0) {
      // A single value and no separator: it is the address, which is the only half a
      // message cannot be without.
      return new String[] { null, stored };
    }
    return new String[] { storedName(stored.substring(0, lastComma)), stored.substring(lastComma + 1) };
  }

  /**
   * A display name as it is read from — and written into — a stored
   * {@code name,address} pair, with an absent one answered as null.
   * <p>
   * The four characters {@code null} count as absent, and that is the whole reason
   * this exists. They are what concatenating a null name produced for as long as the
   * writers did it that way, and stopping the writers was not enough: the rows
   * already written keep the word, and a reader that takes it at face value shows it
   * to the user as somebody's name — a chip in the composer reading {@code null},
   * which is what the owner is looking at. Reading it as absent retires those rows
   * without a rewrite, and without a migration that would have to guess whether a
   * person is really called that.
   * <p>
   * Also applied on the WRITE side, so a client that hands the word back (a legacy
   * draft resumed in a browser tab opened before the fix, and saved again) cannot put
   * a fresh one in the table.
   *
   * @param name the name half of a stored pair, may be null
   * @return the name to use, or null when there is none
   */
  private static String storedName(String name) {
    String trimmed = StringUtils.trimToNull(name);
    return NULL_NAME.equals(trimmed) ? null : trimmed;
  }

  /**
   * Writes a sender into the stored {@code name,address} form, the counterpart of
   * {@link #splitStoredPerson}.
   * <p>
   * A missing display name is written as an empty one, never as the four characters
   * {@code null} that string concatenation produces: a name is optional on a message
   * and routinely absent on a draft, and the reader would show that word to the user
   * as the sender's name. {@link #storedName} decides what missing means, so the word
   * cannot get back in through a client that echoes it either.
   *
   * @param sender the sender, may be null
   * @return the column value, never null
   */
  private String toSenderString(EmailSender sender) {
    if (sender == null) {
      return "";
    }
    return StringUtils.defaultString(storedName(sender.getName())) + "," + StringUtils.defaultString(sender.getAddress());
  }

  /**
   * One cached attachment row of one message of one folder.
   * <p>
   * The folder is mandatory rather than defaulted, and deliberately so: see
   * {@link EmailAttachmentDAO#findByMailRemoteIdAndAttachmentIdAndUserIdAndFolder}
   * for what a lookup without it answers. A default here would put the old collision
   * back one layer up, where nobody would see it.
   *
   * @param mailRemoteId the message's IMAP UID within its folder
   * @param attachmentId the attachment's MIME part path
   * @param userId the mailbox owner
   * @param folder the {@link MailFolder} the message is cached under
   * @return the attachment, or null when the user has no such attachment there
   */
  public EmailAttachment getAttachmentByMailRemoteIdAnIdAndUserId(long mailRemoteId,
                                                                  String attachmentId,
                                                                  String userId,
                                                                  String folder) {
    EmailAttachmentEntity emailAttachmentEntity =
                                                emailAttachmentDAO.findByMailRemoteIdAndAttachmentIdAndUserIdAndFolder(mailRemoteId,
                                                                                                                       attachmentId,
                                                                                                                       userId,
                                                                                                                       StringUtils.defaultIfBlank(folder,
                                                                                                                                                  MailFolder.INBOX))
                                                                  .orElse(null);
    return fromEmailAttachmentEntity(emailAttachmentEntity);
  }

  private EmailBoxEntity toEntity(Email email) {
    if (email == null) {
      return null;
    } else {
      EmailBoxEntity emailBoxEntity = new EmailBoxEntity(email.getId(),
                                                         email.getMailRemoteId(),
                                                         email.getMailHeaderId(),
                                                         email.getUserId(),
                                                         email.getSubject(),
                                                         email.getContent() != null ? email.getContent().getBody() : null,
                                                         toSenderString(email.getSender()),
                                                         toRecipientsString(email.getTo()),
                                                         toRecipientsString(email.getCc()),
                                                         toRecipientsString(email.getBcc()),
                                                         toRecipientsString(email.getReplyTo()),
                                                         email.getReceivedDate(),
                                                         email.isRead(),
                                                         email.isRecent(),
                                                         null,
                                                         email.getThreadId(),
                                                         email.getInReplyTo(),
                                                         email.getMailReferences(),
                                                         email.getFolder() != null ? email.getFolder() : MailFolder.INBOX,
                                                         email.getThreadIndexRoot(),
                                                         email.isAutoSubmitted(),
                                                         email.isHasListId(),
                                                         email.isHasListPost(),
                                                         email.isHasListUnsubscribe(),
                                                         email.getOriginalSender(),
                                                         email.isStarred(),
                                                         email.getDraftLocalId(),
                                                         email.getDraftState(),
                                                         email.getDraftRevision(),
                                                         email.getDraftUpdatedDate(),
                                                         // What the message said about its own body, on its way to the row.
                                                         // Null only for an Email built without content at all: there is
                                                         // no body to describe, so there is nothing to claim about it.
                                                         // A draft comes through here too, and its body is what the rich
                                                         // editor produced — so this is the one place that records that a
                                                         // draft is HTML, and resuming it depends on the flag landing.
                                                         email.getContent() != null ? email.getContent().isHtml() : null);
      List<EmailAttachmentEntity> attachments = email.getContent() != null
          && email.getContent().getAttachments() != null ? email.getContent().getAttachments().stream().map(attachment -> {
            return toEmailAttachmentEntity(attachment, emailBoxEntity);
          }).toList() : null;
      emailBoxEntity.setAttachments(attachments);
      return emailBoxEntity;
    }
  }

  @SneakyThrows
  private Email fromEntity(EmailBoxEntity emailBoxEntity,
                           boolean withAttachments,
                           boolean isExcerpt,
                           String userId,
                           String userEmail,
                           boolean withRecipients,
                           boolean withProfile) {
    if (emailBoxEntity == null) {
      return null;
    } else {
      List<EmailAttachment> attachments = withAttachments
          && emailBoxEntity.getAttachments() != null ? emailBoxEntity.getAttachments().stream().map(this::fromEmailAttachmentEntity).filter(Objects::nonNull).toList() : null;
      String excerpt = null;
      if (isExcerpt) {
        // A row with no body at all. Every message this table held before drafts had
        // one — it was fetched from the server with the message — so this parsed the
        // column unguarded, and Jsoup.parse(null) throws. A draft whose author has
        // typed a subject and no text yet is an ordinary draft, and one of them made
        // the whole folder listing answer 500 (and the cached search, and the
        // disconnect cleanup, which map the same way). An empty body has an empty
        // excerpt, which is what the list already renders as "no content".
        String body = emailBoxEntity.getBody();
        excerpt = StringUtils.isBlank(body) ? "" : Jsoup.parse(body).text().trim();
      }
      EmailContent content = new EmailContent(emailBoxEntity.getBody(), excerpt, attachments);
      content.setHtml(isHtmlBody(emailBoxEntity));
      String[] emailSenderParts = splitStoredPerson(emailBoxEntity.getSender());
      InternetAddress emailSenderAddress = new InternetAddress(emailSenderParts[1], emailSenderParts[0]);
      List<Long> categoryIds = categoryLinkService.getLinkedIds(new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE,
                                                                                   String.valueOf(emailBoxEntity.getId()),
                                                                                   0));
      Email email = new Email(emailBoxEntity.getId(),
                              emailBoxEntity.getMailRemoteId(),
                              emailBoxEntity.getMailHeaderId(),
                              emailBoxEntity.getUserId(),
                              userEmail,
                              emailBoxEntity.getSubject(),
                              content,
                              emailBoxEntity.getReceivedDate(),
                              EmailConnectorUtils.getEmailSender(emailSenderAddress, withProfile),
                              emailBoxEntity.isRead(),
                              emailBoxEntity.isRecent(),
                              null,
                              null,
                              null,
                              null,
                              categoryIds,
                              null,
                              emailBoxEntity.getThreadId(),
                              emailBoxEntity.getInReplyTo(),
                              emailBoxEntity.getMailReferences(),
                              emailBoxEntity.getFolder(),
                              emailBoxEntity.getThreadIndexRoot(),
                              emailBoxEntity.isAutoSubmitted(),
                              emailBoxEntity.isHasListId(),
                              emailBoxEntity.isHasListPost(),
                              emailBoxEntity.isHasListUnsubscribe(),
                              emailBoxEntity.getOriginalSender(),
                              emailBoxEntity.isStarred(),
                              emailBoxEntity.getDraftLocalId(),
                              emailBoxEntity.getDraftState(),
                              emailBoxEntity.getDraftRevision(),
                              emailBoxEntity.getDraftUpdatedDate(),
                              // Stored attachments are not read here. They live on
                              // content.attachments like every other attachment of a
                              // row; this field exists only so the send path can hand
                              // the draft's own files to the message builder.
                              null);

      // A draft carries its recipients on EVERY read, whatever the caller asked for.
      //
      // Not a convenience: the caller that asks for a listing without them is the
      // folder list, and a listing row shows a sender, a subject and an excerpt, so
      // leaving them out was right for as long as a row was mail. A draft row is not
      // only rendered — it is RESUMED from, straight out of the list, and the composer
      // fills its recipient fields from what it was handed. Handed a row without them
      // it showed none, and the first autosave wrote that emptiness back over the
      // stored ones: the user's draft lost the people it was addressed to by being
      // opened. Verified on a live mailbox, on drafts imported with recipients whose
      // rows came back with none after a resume.
      //
      // Here rather than at the four call sites, because "a draft is always read whole"
      // is a property of the row and not of who is asking — the next read path added
      // would otherwise have to remember, and this one did not.
      if (withRecipients || StringUtils.isNotBlank(emailBoxEntity.getDraftLocalId())) {
        InternetAddress[] emailToRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getTo());
        InternetAddress[] emailCcRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getCc());
        InternetAddress[] emailBccRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getBcc());
        InternetAddress[] emailReplyToRecipientsInternetAddresses = toRecipientsInternetAddresses(emailBoxEntity.getReplyTo());
        email.setTo(EmailConnectorUtils.getEmailRecipients(emailToRecipientsInternetAddresses, userId, withProfile));
        email.setCc(EmailConnectorUtils.getEmailRecipients(emailCcRecipientsInternetAddresses, userId, withProfile));
        email.setBcc(EmailConnectorUtils.getEmailRecipients(emailBccRecipientsInternetAddresses, userId, withProfile));
        email.setReplyTo(EmailConnectorUtils.getEmailRecipients(emailReplyToRecipientsInternetAddresses, userId, false));
      }
      return email;
    }
  }

  /**
   * Whether a cached row's body is HTML.
   * <p>
   * The row's own answer whenever it has one — that is the message's declared
   * Content-Type, recorded at sync. A row cached before the column existed was never
   * asked, so its body is read instead; that fallback is the only guessing left in the
   * whole path, it applies to no row written from now on, and those rows leave the cache
   * as the sync window moves past them.
   *
   * @param emailBoxEntity the cached row
   * @return true when the body should be rendered as HTML
   */
  private boolean isHtmlBody(EmailBoxEntity emailBoxEntity) {
    return emailBoxEntity.getHtml() != null ? emailBoxEntity.getHtml()
                                            : EmailConnectorUtils.looksLikeHtml(emailBoxEntity.getBody());
  }

  private EmailAttachmentEntity toEmailAttachmentEntity(EmailAttachment emailAttachment, EmailBoxEntity emailBoxEntity) {
    if (emailAttachment == null || emailAttachment.getName() == null) {
      return null;
    } else {
      // No file id and no size: this path builds the attachments of a message MIRRORED
      // from the server, whose bytes stay on the server and are fetched on demand. A
      // draft's own file goes through addDraftAttachment, which is the only writer of
      // those two columns.
      return new EmailAttachmentEntity(emailAttachment.getId(),
                                       emailBoxEntity,
                                       emailAttachment.getAttachmentRemoteId(),
                                       emailAttachment.getName(),
                                       emailAttachment.getMimeType(),
                                       null,
                                       null);
    }
  }

  /**
   * Maps one attachment row, carrying its message's UID and FOLDER with it.
   * <p>
   * The folder travels on the attachment because a UID is meaningless without one
   * (IMAP numbers them per folder), and every consumer that goes back for the bytes
   * addresses the attachment rather than the message it came from. Reading it here,
   * in the one mapper, is what stops each of those consumers having to remember —
   * and reading it wrong is exactly how an attachment on a Sent message could not be
   * downloaded at all.
   *
   * @param emailAttachmentEntity the row, may be null
   * @return the attachment, or null when the row is
   */
  private EmailAttachment fromEmailAttachmentEntity(EmailAttachmentEntity emailAttachmentEntity) {
    if (emailAttachmentEntity == null) {
      return null;
    } else {
      return new EmailAttachment(emailAttachmentEntity.getId(),
                                 emailAttachmentEntity.getEmail().getMailRemoteId(),
                                 emailAttachmentEntity.getAttachmentRemoteId(),
                                 emailAttachmentEntity.getName(),
                                 emailAttachmentEntity.getMimeType(),
                                 null,
                                 emailAttachmentEntity.getEmail().getFolder(),
                                 emailAttachmentEntity.getFileId(),
                                 emailAttachmentEntity.getFileSize());
    }
  }

  /**
   * Writes recipients into the stored {@code name,address;name,address} form.
   * <p>
   * A recipient with no display name is written with an empty one. It used to be
   * written with the four characters {@code null} — the result of concatenating a
   * null — which the reader then handed back as the person's name, because a
   * recipient parsed out of a delivered message always had a name to write there.
   * The composer sends addresses alone (a half-typed address has no name yet), so
   * every draft ever saved carried {@code null} as the name of everyone it was
   * addressed to.
   * <p>
   * What counts as missing is {@link #storedName}'s to say, and it counts that word
   * as missing: the composer reads a draft's recipients back into its chips and saves
   * them again, so a row written before this was fixed would otherwise write its own
   * {@code null} back out on the next autosave and outlive the fix.
   *
   * @param recipients the recipients, may be null or empty
   * @return the column value, never null
   */
  private String toRecipientsString(List<EmailRecipient> recipients) {
    if (recipients == null || recipients.isEmpty()) {
      return "";
    }
    return recipients.stream()
                     .map(recipient -> StringUtils.defaultString(storedName(recipient.getName())) + ","
                         + StringUtils.defaultString(recipient.getAddress()))
                     .collect(Collectors.joining(";"));
  }

  /**
   * Reads back what {@link #toRecipientsString} wrote.
   * <p>
   * An entry with no address is dropped rather than returned as a nameless,
   * addressless recipient: a recipient IS an address, and the caller renders what
   * comes back — an entry with nothing in it draws an empty chip nobody can act on.
   * Missing names are handed on as missing on purpose, so the display-name resolution
   * downstream (the platform profile, else the address itself) gets its chance — and
   * a stored {@code null} counts as missing, which is what retires the rows written
   * while the writer was still producing that word, with no re-save and no migration.
   * <p>
   * Each entry goes through {@link #splitStoredPerson} rather than through a split of
   * its own. It had one — on the FIRST comma, where the sender's reader had already
   * been moved to the last — so a recipient whose name carries a comma came back
   * addressed to the tail of their own name. Two readers of one written form is how
   * the same defect got fixed on one of them and stayed on the other.
   *
   * @param recipientsString the stored column, may be null or blank
   * @return the addresses, never null
   */
  private static InternetAddress[] toRecipientsInternetAddresses(String recipientsString) {
    if (recipientsString == null || recipientsString.trim().isEmpty()) {
      return new InternetAddress[0];
    }
    return Arrays.stream(recipientsString.split(";")).map(entry -> {
      String[] parts = splitStoredPerson(entry);
      String address = parts[1];
      if (StringUtils.isBlank(address)) {
        return null;
      }
      try {
        return new InternetAddress(address, parts[0]);
      } catch (Exception e) {
        return null;
      }
    }).filter(Objects::nonNull).toArray(InternetAddress[]::new);
  }
}
