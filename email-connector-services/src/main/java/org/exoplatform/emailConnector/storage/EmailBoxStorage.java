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

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import javax.mail.internet.InternetAddress;

import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.dao.EmailAttachmentDAO;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.plugin.EmailCategoryPlugin;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;

import io.meeds.social.category.model.CategoryObject;
import io.meeds.social.category.service.CategoryLinkService;
import lombok.SneakyThrows;

/**
 * Storage service to access / load and save email box. This service will be
 * used, as well, to convert from JPA entity to DTO.
 */
@Component
public class EmailBoxStorage {

  @Autowired
  private EmailBoxDAO         emailBoxDao;

  @Autowired
  private EmailAttachmentDAO  emailAttachmentDAO;

  @Autowired
  private CategoryLinkService categoryLinkService;

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
    List<EmailBoxEntity> entities = emailBoxDao.findByUserIdAndDraftLocalId(userId, draftLocalId);
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
    List<EmailBoxEntity> existing = emailBoxDao.findByUserIdAndDraftLocalId(draft.getUserId(), draft.getDraftLocalId());
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
    entity.setTo(toRecipientsString(draft.getTo()));
    entity.setCc(toRecipientsString(draft.getCc()));
    entity.setBcc(toRecipientsString(draft.getBcc()));
    entity.setReceivedDate(draft.getReceivedDate());
    entity.setDraftState(draft.getDraftState());
    entity.setDraftRevision(incomingRevision);
    entity.setDraftUpdatedDate(draft.getDraftUpdatedDate());
    return fromEntity(emailBoxDao.save(entity), true, false, draft.getUserId(), null, true, false);
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
    List<EmailBoxEntity> existing = emailBoxDao.findByUserIdAndDraftLocalId(userId, draftLocalId);
    if (existing.isEmpty()) {
      return null;
    }
    EmailBoxEntity entity = existing.get(0);
    entity.setMailRemoteId(mailRemoteId);
    if (Objects.equals(entity.getDraftRevision(), uploadedRevision)) {
      entity.setDraftState(DraftState.SYNCED);
    }
    return fromEntity(emailBoxDao.save(entity), true, false, userId, null, true, false);
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
    List<EmailBoxEntity> existing = emailBoxDao.findByUserIdAndDraftLocalId(userId, draftLocalId);
    if (existing.isEmpty()) {
      return null;
    }
    EmailBoxEntity entity = existing.get(0);
    entity.setDraftState(draftState);
    return fromEntity(emailBoxDao.save(entity), true, false, userId, null, true, false);
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
   * The total number of cached messages per conversation, across every folder, keyed
   * by thread id — so the inbox list can show the full conversation count (Gmail-style)
   * rather than only the messages that happen to be in the inbox.
   *
   * @param userId the mailbox owner
   * @return a map of thread id to its cached message count
   */
  public Map<String, Integer> getThreadMessageCounts(String userId) {
    Map<String, Integer> counts = new HashMap<>();
    for (Object[] row : emailBoxDao.countMessagesByThread(userId)) {
      counts.put((String) row[0], ((Number) row[1]).intValue());
    }
    return counts;
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
   */
  public List<Email> getEmailsByThreadId(String userId, String threadId, String userEmail) {
    List<EmailBoxEntity> emailBoxEntities = emailBoxDao.findByUserIdAndThreadIdWithAttachments(userId, threadId);
    return emailBoxEntities.stream()
                           .map(emailBoxEntity -> fromEntity(emailBoxEntity, true, false, userId, userEmail, true, true))
                           .toList();
  }

  public long countUnreadEmails(String userId) {
    // The inbox is the only folder the eXo client can mark read, so it is the
    // only one whose unread count a user can ever bring back to zero
    return emailBoxDao.countUnreadByUserIdAndFolder(userId, MailFolder.INBOX);
  }

  public void deleteEmailsByIds(List<Long> emailsIds) {
    emailBoxDao.deleteEmailsByIds(emailsIds);
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
   * directory. Split on the LAST comma: the address cannot contain one, while a
   * display name legitimately can ("Doe, Jane") — the first-comma split the full
   * mapper inherited would hand the rules a truncated address.
   *
   * @param stored the stored sender string
   * @return the sender, or null for a blank value
   */
  private EmailSender toLightSender(String stored) {
    if (StringUtils.isBlank(stored)) {
      return null;
    }
    int lastComma = stored.lastIndexOf(',');
    if (lastComma < 0) {
      return new EmailSender(stored, stored, null, null);
    }
    String name = stored.substring(0, lastComma);
    String address = stored.substring(lastComma + 1);
    return new EmailSender(StringUtils.isBlank(name) ? address : name, address, null, null);
  }

  public EmailAttachment getAttachmentByMailRemoteIdAnIdAndUserId(long mailRemoteId, String attachmentId, String userId) {
    EmailAttachmentEntity emailAttachmentEntity = emailAttachmentDAO
                                                                    .findByMailRemoteIdAndAttachmentIdAndUserId(mailRemoteId,
                                                                                                                attachmentId,
                                                                                                                userId)
                                                                    .orElse(null);
    ;
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
                                                         email.getSender() != null ? email.getSender().getName() + ","
                                                             + email.getSender().getAddress() : "",
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
                                                         email.getDraftUpdatedDate());
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
        excerpt = Jsoup.parse(emailBoxEntity.getBody()).text().trim();
      }
      String[] emailSenderParts = emailBoxEntity.getSender().split(",");
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
                              new EmailContent(emailBoxEntity.getBody(), excerpt, attachments),
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
                              emailBoxEntity.getDraftUpdatedDate());

      if (withRecipients) {
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

  private EmailAttachmentEntity toEmailAttachmentEntity(EmailAttachment emailAttachment, EmailBoxEntity emailBoxEntity) {
    if (emailAttachment == null || emailAttachment.getName() == null) {
      return null;
    } else {
      return new EmailAttachmentEntity(emailAttachment.getId(),
                                       emailBoxEntity,
                                       emailAttachment.getAttachmentRemoteId(),
                                       emailAttachment.getName(),
                                       emailAttachment.getMimeType());
    }
  }

  private EmailAttachment fromEmailAttachmentEntity(EmailAttachmentEntity emailAttachmentEntity) {
    if (emailAttachmentEntity == null) {
      return null;
    } else {
      return new EmailAttachment(emailAttachmentEntity.getId(),
                                 emailAttachmentEntity.getEmail().getMailRemoteId(),
                                 emailAttachmentEntity.getAttachmentRemoteId(),
                                 emailAttachmentEntity.getName(),
                                 emailAttachmentEntity.getMimeType(),
                                 null);
    }
  }

  private String toRecipientsString(List<EmailRecipient> recipients) {
    if (recipients == null || recipients.isEmpty()) {
      return "";
    }
    return recipients.stream()
                     .map(recipient -> recipient.getName() + "," + recipient.getAddress())
                     .collect(Collectors.joining(";"));
  }

  private static InternetAddress[] toRecipientsInternetAddresses(String recipientsString) {
    if (recipientsString == null || recipientsString.trim().isEmpty()) {
      return new InternetAddress[0];
    }
    return Arrays.stream(recipientsString.split(";")).map(entry -> {
      String[] parts = entry.split(",", 2);
      String name = parts.length > 0 ? parts[0] : "";
      String address = parts.length > 1 ? parts[1] : "";
      try {
        return new InternetAddress(address, name);
      } catch (Exception e) {
        return null;
      }
    }).filter(Objects::nonNull).toArray(InternetAddress[]::new);
  }
}
