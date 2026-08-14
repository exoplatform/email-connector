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

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;

public interface EmailBoxDAO extends JpaRepository<EmailBoxEntity, Long> {

  /**
   * Every cached row of a mailbox, every folder, no exception — the TOTAL read.
   * <p>
   * It has one caller and must keep having one: the cleanup that wipes a mailbox
   * when the account is disconnected or rebound. Totality is the requirement there,
   * not an accident of how the query happens to be written. A folder left out here
   * is a set of rows that outlives the account they belonged to — deleted mail
   * surviving the deletion of the mailbox it was deleted in, invisible to every
   * screen and to the user who thought they had disconnected.
   * <p>
   * Anything that SHOWS mail must use
   * {@link #findByUserIdExcludingFolderWithAttachments} instead. The two exist
   * separately for exactly that reason: one question is "what belongs to this user"
   * and the other is "what may this user be shown", and they stopped having the same
   * answer the day Trash started being cached.
   *
   * @param userId the mailbox owner
   * @return every one of the user's cached messages with their attachments, newest
   *         first
   */
  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId ORDER BY email.receivedDate DESC")
  List<EmailBoxEntity> findByUserIdWithAttachments(@Param("userId")
  String userId);

  /**
   * The same read as {@link #findByUserIdWithAttachments} minus one folder, always
   * {@link org.exoplatform.emailConnector.model.MailFolder#TRASH} — the whole
   * mailbox as it may be SHOWN.
   * <p>
   * This is what the cached search reads. A search that offers back the mail the
   * user threw away is the same defect as a conversation redisplaying it, only
   * further from the delete that caused it and therefore harder to recognise: the
   * hit does not say which folder it came from, so a deleted message simply looks
   * like a message.
   * <p>
   * A separate query rather than a flag on the total read, because the two callers
   * want opposite things and a boolean parameter would let a future one get the
   * default wrong in silence. See {@link #findByUserIdAndThreadIdWithAttachments}
   * for why the exclusion is a parameter and why a plain inequality is safe on this
   * column.
   *
   * @param userId the mailbox owner
   * @param excludedFolder the folder to leave out, always {@code MailFolder.TRASH}
   * @return the user's showable cached messages with their attachments, newest first
   */
  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId AND email.folder <> :excludedFolder ORDER BY email.receivedDate DESC")
  List<EmailBoxEntity> findByUserIdExcludingFolderWithAttachments(@Param("userId")
  String userId, @Param("excludedFolder")
  String excludedFolder);

  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId AND email.folder = :folder ORDER BY email.receivedDate DESC")
  List<EmailBoxEntity> findByUserIdAndFolderWithAttachments(@Param("userId")
  String userId, @Param("folder")
  String folder);

  /**
   * The starred subset of a folder, for the list's starred filter. A dedicated query
   * rather than a flag on {@link #findByUserIdAndFolderWithAttachments} so the common
   * unfiltered listing keeps its exact plan, and the filter runs in SQL instead of
   * dragging the whole folder (bodies included) through the persistence layer to keep
   * a fraction of it.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator
   * @return the folder's starred messages with their attachments, newest first
   */
  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId AND email.folder = :folder AND email.starred = true ORDER BY email.receivedDate DESC")
  List<EmailBoxEntity> findStarredByUserIdAndFolderWithAttachments(@Param("userId")
  String userId, @Param("folder")
  String folder);

  /**
   * Every cached message of one conversation, across every folder, oldest first —
   * the read model behind the conversation reader.
   * <p>
   * Every folder EXCEPT the excluded one, which is always
   * {@link org.exoplatform.emailConnector.model.MailFolder#TRASH}. Reading a
   * conversation is the read a deleted message must never come back through: the
   * user deleted it out of this very conversation, and a reader with no folder
   * predicate would put it straight back in the moment Trash starts being cached.
   * The other browsable folders stay in, which is the whole point of a cross-folder
   * reader — a sent reply and a previously-archived message belong in the
   * conversation they are part of.
   * <p>
   * The exclusion is a bound parameter rather than a {@code 'TRASH'} literal so the
   * folder's name lives in exactly one place ({@code MailFolder}), the way every
   * other folder-scoped query in this interface takes its folder from the caller.
   * It is not a choice offered to the caller: the storage layer passes the constant,
   * and there is no read of a conversation that should be given a different answer.
   * <p>
   * {@code FOLDER} is {@code NOT NULL} in the schema (changeset 1.0.0-20, default
   * {@code INBOX}), so the plain inequality cannot silently drop a row the way it
   * would on a nullable column — where {@code folder <> 'TRASH'} is UNKNOWN, not
   * true, for every null.
   *
   * @param userId the mailbox owner
   * @param threadId the conversation id
   * @param excludedFolder the folder to leave out, always {@code MailFolder.TRASH}
   * @return the conversation's messages with their attachments, oldest first
   */
  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId AND email.threadId = :threadId AND email.folder <> :excludedFolder ORDER BY email.receivedDate ASC")
  List<EmailBoxEntity> findByUserIdAndThreadIdWithAttachments(@Param("userId")
  String userId, @Param("threadId")
  String threadId, @Param("excludedFolder")
  String excludedFolder);

  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId AND email.folder = :folder AND email.mailRemoteId = :mailRemoteId ORDER BY email.receivedDate DESC")
  EmailBoxEntity findByMailRemoteIdAndUserIdAndFolder(@Param("mailRemoteId")
  long mailRemoteId, @Param("userId")
  String userId, @Param("folder")
  String folder);

  /**
   * Deletes every cached row of a mailbox, every folder, no exception — TRASH with
   * the rest. Deleted mail must not outlive the account it was deleted in.
   *
   * @param userId the mailbox owner
   */
  void deleteByUserId(String userId);

  @Transactional
  @Modifying
  @Query("DELETE FROM EmailBoxEntity email WHERE email.id IN :ids")
  void deleteEmailsByIds(@Param("ids")
  List<Long> ids);

  /**
   * Counts the unread emails of the locally synced mirror. Never reaches the
   * IMAP server: the badge reflects what the platform already knows.
   * <p>
   * Scoped to one folder, and the caller passes the inbox: SENT and ARCHIVE
   * rows carry the server's SEEN flag too, so an archived message that was
   * never read would otherwise be counted forever — the client can only mark
   * inbox messages read, so nothing could ever clear it. ALL_MAIL is a
   * thread-completion cache that duplicates the other folders and must never be
   * counted at all.
   *
   * @param  userId the mailbox owner
   * @param  folder the folder to count in
   * @return        the number of unread emails in that folder
   */
  @Query("SELECT COUNT(email) FROM EmailBoxEntity email WHERE email.userId = :userId AND email.folder = :folder AND (email.read IS NULL OR email.read = FALSE)")
  long countUnreadByUserIdAndFolder(@Param("userId")
  String userId, @Param("folder")
  String folder);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.read = :readStatus WHERE email.mailRemoteId IN :mailRemoteIds AND email.userId = :userId AND email.folder = :folder AND (email.read IS NULL OR email.read <> :readStatus)")
  void updateReadStatusByMailRemoteIds(@Param("mailRemoteIds")
  List<Long> mailRemoteIds, @Param("userId")
  String userId, @Param("readStatus")
  boolean readStatus, @Param("folder")
  String folder);

  /**
   * Bulk starred-flag companion of {@link #updateReadStatusByMailRemoteIds}, and the
   * same shape on purpose: one statement for any number of messages, guarded so rows
   * already carrying the target value are not rewritten. Used by both the sync
   * reconcile (server value wins) and the user toggle (optimistic local write).
   *
   * @param mailRemoteIds the IMAP UIDs to update
   * @param userId the mailbox owner
   * @param starred the target starred value
   * @param folder the folder discriminator scoping the UIDs
   */
  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.starred = :starred WHERE email.mailRemoteId IN :mailRemoteIds AND email.userId = :userId AND email.folder = :folder AND (email.starred IS NULL OR email.starred <> :starred)")
  void updateStarredStatusByMailRemoteIds(@Param("mailRemoteIds")
  List<Long> mailRemoteIds, @Param("userId")
  String userId, @Param("starred")
  boolean starred, @Param("folder")
  String folder);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.recent = false WHERE email.mailRemoteId = :mailRemoteId AND email.userId = :userId AND email.folder = :folder")
  void markEmailAsNotRecent(@Param("mailRemoteId")
  Long mailRemoteId, @Param("userId")
  String userId, @Param("folder")
  String folder);

  /**
   * Bulk companion of {@link #markEmailAsNotRecent}: clears the recent flag of all
   * the given messages in ONE statement. The sync used to issue the single-row
   * version once per already-known message — 5000 statements per routine sync on a
   * 5000-message cache, almost all of them writing nothing. The caller passes only
   * the rows whose flag is actually set, so a steady-state sync issues no statement
   * at all.
   *
   * @param mailRemoteIds the IMAP UIDs whose recent flag must be cleared
   * @param userId the mailbox owner
   * @param folder the folder discriminator scoping the UIDs
   */
  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.recent = false WHERE email.mailRemoteId IN :mailRemoteIds AND email.userId = :userId AND email.folder = :folder")
  void markEmailsAsNotRecent(@Param("mailRemoteIds")
  List<Long> mailRemoteIds, @Param("userId")
  String userId, @Param("folder")
  String folder);

  /**
   * The light per-folder view the sync reconcile runs on: row id, IMAP UID,
   * threading state and the flags — WITHOUT the body CLOB and without the
   * attachments join. The sync used to load the full entities through
   * {@link #findByUserIdAndFolderWithAttachments}: at 5000 cached messages that
   * dragged 5000 bodies out of the database every routine sync just to compare
   * UIDs and flags. Ordered newest-first because cleanupObsoleteEmails trims the
   * cache overflow off the END of the list, exactly as the full query did. The
   * starred flag rides in this projection because the reconcile diffs it exactly
   * like the read flag; loading it any other way would mean a second per-folder
   * query every sync.
   * <p>
   * The two draft columns ride along for the same "the sync itself needs them"
   * reason. The state is what tells a draft the user is still writing from a
   * message the server no longer has — the two look identical from the UID alone.
   * The local id joined it when the Drafts folder started syncing: a draft whose
   * server copy vanished has to be put back to a state that will re-upload, and
   * every write to a draft is addressed by its local id (never by its row id or
   * its UID, both of which move under a draft), so without it here the reconcile
   * would have to re-read every draft row in full just to learn the handle.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator
   * @return rows of {@code [id, mailRemoteId, threadId, threadIndexRoot, read,
   *         recent, starred, draftState, draftLocalId]}, newest first
   */
  @Query("SELECT email.id, email.mailRemoteId, email.threadId, email.threadIndexRoot, email.read, email.recent, email.starred, email.draftState, email.draftLocalId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.folder = :folder ORDER BY email.receivedDate DESC")
  List<Object[]> findSyncViewByUserIdAndFolder(@Param("userId")
  String userId, @Param("folder")
  String folder);

  /**
   * A draft by the composer's own handle on it. Returns a list rather than a single
   * row on purpose: the local id is not a database-level unique key (it is null on
   * every non-draft row, and vendors disagree about whether repeated nulls collide
   * in a unique index), so the query must not be the thing that throws when the
   * unexpected happens. The caller takes the first and the save path's per-draft
   * lock is what actually keeps there being only one.
   * <p>
   * It fetches the attachments, like every other query that returns whole rows the
   * REST layer will answer with, and for the same reason: the platform sets
   * {@code spring.jpa.open-in-view=false}, so a collection left uninitialised inside
   * the transaction cannot be initialised at all by the time the row is serialised.
   * Every caller of this query hands what it read to a mapper that reads the
   * attachments ({@code EmailBoxStorage#fromEntity} with {@code withAttachments}),
   * so leaving the join out made every draft read fail with "no Session" rather than
   * merely being slow. Drafts carry no attachments today — that is a separate,
   * deliberate gap — but the collection is still TOUCHED, and touching an empty lazy
   * bag outside a session throws exactly as touching a full one does.
   * <p>
   * This paragraph once claimed the same join fixed the draft SAVES too, and it did
   * not: the join initialises the collection of the instance returned HERE, while the
   * writers were mapping their answer from what {@code save} returned — a different,
   * managed instance out of a merge, carrying an uninitialised proxy, in a session
   * that closes with the write. The join is necessary and was never sufficient; what
   * makes the writes work is that they now map from this instance. See
   * {@code EmailBoxStorage#saveDraftRow}.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @return the matching rows with their attachments, normally exactly one, never
   *         null
   */
  @Query("SELECT email FROM EmailBoxEntity email LEFT JOIN FETCH email.attachments WHERE email.userId = :userId AND email.draftLocalId = :draftLocalId")
  List<EmailBoxEntity> findByUserIdAndDraftLocalIdWithAttachments(@Param("userId")
  String userId, @Param("draftLocalId")
  String draftLocalId);

  /**
   * The user's cached rows carrying a given Message-ID, newest first. Its one
   * reader wants the {@code References} chain off the first of them, so a reply can
   * extend its parent's chain instead of replacing it.
   * <p>
   * Deliberately unscoped by folder, TRASH included. What is read here is a header
   * chain, not a message: nothing of the row reaches a screen, and a parent the user
   * has since deleted addressed its conversation exactly as it did before they
   * deleted it. Excluding Trash would mean a reply written after the parent was
   * thrown away silently starts a new chain — the conversation splitting in the
   * recipient's mail client as a side effect of housekeeping in ours.
   *
   * @param mailHeaderId the Message-ID to look for
   * @param userId the mailbox owner
   * @return the matching rows, newest first
   */
  @Query("SELECT email FROM EmailBoxEntity email WHERE email.userId = :userId AND email.mailHeaderId = :mailHeaderId ORDER BY email.receivedDate DESC")
  List<EmailBoxEntity> findByMailHeaderIdAndUserId(@Param("mailHeaderId")
  String mailHeaderId, @Param("userId")
  String userId);

  /**
   * How many cached messages of a folder carry a given Message-ID. A count rather
   * than {@link #findByMailHeaderIdAndUserId} filtered in Java: the one caller only
   * asks "does this exist", and the full lookup drags the body CLOB of every
   * matching row through the persistence layer to answer a yes/no.
   *
   * @param userId the mailbox owner
   * @param mailHeaderId the Message-ID to look for
   * @param folder the folder discriminator to look in
   * @return the number of matching cached rows
   */
  @Query("SELECT COUNT(email.id) FROM EmailBoxEntity email WHERE email.userId = :userId AND email.mailHeaderId = :mailHeaderId AND email.folder = :folder")
  long countByMailHeaderIdAndUserIdAndFolder(@Param("mailHeaderId")
  String mailHeaderId, @Param("userId")
  String userId, @Param("folder")
  String folder);

  /**
   * Cuts a draft's row loose from the copy it had on the mail server: the state
   * goes back to {@link org.exoplatform.emailConnector.model.DraftState#LOCAL_ONLY}
   * and the UID is cleared. Used by the Drafts sync when the copy this row pointed
   * at is no longer on the server — another mail client deleted it — while the row
   * still carries text that never reached it.
   * <p>
   * Clearing the UID is not tidiness. It is what stops the next upload from
   * flagging a stranger's message for deletion: IMAP UIDs are unique within one
   * UIDVALIDITY, and a folder that changed its UIDVALIDITY (a rebuilt mailbox) is
   * seen from here as every UID having vanished at once — precisely this case, at
   * which point the remembered numbers mean nothing at all.
   * <p>
   * Neither the revision nor the text is touched: the row's words are unchanged,
   * only where they also live.
   *
   * The state is bound as a parameter rather than written as a JPQL enum literal:
   * the value is always {@code LOCAL_ONLY} (the storage layer is what says so), and
   * a bound parameter goes through the same enum conversion as every other write to
   * this column instead of relying on how a given dialect renders a literal.
   *
   * @param userId the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @param draftState the state to put the row back to
   */
  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.draftState = :draftState, email.mailRemoteId = null WHERE email.userId = :userId AND email.draftLocalId = :draftLocalId")
  void detachDraftFromServerCopy(@Param("userId")
  String userId, @Param("draftLocalId")
  String draftLocalId, @Param("draftState")
  DraftState draftState);

  /**
   * The distinct conversations of the cached messages carrying any of the given
   * Message-IDs — the forward half of thread resolution.
   * <p>
   * One of four queries (with
   * {@link #findDistinctThreadIdsByThreadIndexRoot},
   * {@link #findThreadReferenceChainsByUserId} and
   * {@link #findThreadIdsOrderedByAge}) that answer a single question: which
   * conversation does this message belong to. All four are deliberately unscoped by
   * folder, TRASH included, and the reason is the same for each — they resolve
   * IDENTITY, never content. Not one row they touch is rendered anywhere; what they
   * produce is a thread id.
   * <p>
   * Excluding Trash here would not hide anything (the reader and the summaries
   * already do that, which is what makes leaving it in safe); it would break the
   * conversation. A trashed message that stops being seen by the resolution stops
   * being merged with its siblings, so it keeps a thread id nobody else shares — and
   * comes back, on a restore or a re-sync, as a conversation of one, sitting beside
   * the conversation it has always belonged to. The exclusions and the identity
   * machinery are answering different questions and must not be given the same
   * answer.
   *
   * @param userId the mailbox owner
   * @param mailHeaderIds the Message-IDs the new message points back at
   * @return the distinct thread ids of the cached messages carrying them
   */
  @Query("SELECT DISTINCT email.threadId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.mailHeaderId IN :mailHeaderIds AND email.threadId IS NOT NULL")
  List<String> findDistinctThreadIdsByMailHeaderIds(@Param("userId")
  String userId, @Param("mailHeaderIds")
  List<String> mailHeaderIds);

  /**
   * One row per conversation: how many messages it holds, and how many of them are
   * drafts. The list shows both — the count next to the participants, and a "Draft"
   * marker when the conversation carries a reply the user never sent.
   * <p>
   * They are ONE query on purpose. The list is built from the cached rows of a
   * single folder and drafts live under {@code DRAFTS}, so an inbox listing never
   * sees the draft it has to report; the alternative to reading it here is a lookup
   * per visible row, which is an N+1 over the whole page. This aggregate already
   * had to run for the count, and carrying the draft out of the same {@code GROUP
   * BY} costs nothing beyond one more projected column.
   * <p>
   * The count is DISTINCT by Message-ID, matching the reader, which shows the same
   * message once even when it is cached in several folders (INBOX and ALL_MAIL, or
   * a draft and the SENT copy it turned into — a sent draft goes out under the
   * Message-ID it minted at its first save, so for as long as both rows exist they
   * are two rows for one message). A raw row count would over-report by exactly
   * those duplicates.
   * <p>
   * Two details of the DISTINCT expression are load-bearing:
   * <ul>
   * <li>It falls back to the draft's local id, because a draft written in ANOTHER
   * mail client may reach us with no Message-ID at all — half-written mail
   * legitimately has none, and {@code createDraftFromServerMessage} stores what the
   * server gave it. {@code COUNT(DISTINCT)} ignores nulls, so without the fallback
   * such a draft would be reported by the flag and left out of the number beside
   * it. The local id is a UUID minted here and can never collide with a
   * Message-ID.</li>
   * <li>The {@code MAIL_HEADER_ID IS NOT NULL} predicate that used to sit in the
   * WHERE clause is gone, and it has to be: it would have dropped that same
   * header-less draft out of the aggregate before either column saw it. Removing it
   * changes nothing else — {@code COUNT(DISTINCT)} was already ignoring nulls, so
   * every conversation that has at least one message with a Message-ID reports the
   * number it reported before, and a conversation of nothing but header-less mail
   * goes from being absent to reporting 0, which the client already treats the same
   * way (it falls back to what it can see in the folder).</li>
   * </ul>
   * The draft column is a count rather than a boolean because JPQL has no boolean
   * aggregate; the caller reads it as "more than none".
   * <p>
   * One folder is left out of the aggregate, always
   * {@link org.exoplatform.emailConnector.model.MailFolder#TRASH}, and it is left
   * out of BOTH columns because it has to be: a conversation whose fourth message
   * the user deleted holds three, and the number beside the participants is the one
   * place that would keep insisting on four with nothing on screen to account for
   * it. The draft column follows the same rule for a sharper reason — a draft the
   * user discarded is in Trash, and a discarded draft that still made the
   * conversation say "Draft" would advertise unfinished words that no longer exist.
   * See {@link #findByUserIdAndThreadIdWithAttachments} for why the exclusion is a
   * parameter.
   *
   * @param userId the mailbox owner
   * @param excludedFolder the folder to leave out, always {@code MailFolder.TRASH}
   * @return rows of {@code [threadId, messageCount, draftCount]}, one per
   *         conversation
   */
  @Query("SELECT email.threadId, COUNT(DISTINCT COALESCE(email.mailHeaderId, email.draftLocalId)), SUM(CASE WHEN email.draftLocalId IS NULL THEN 0 ELSE 1 END) FROM EmailBoxEntity email WHERE email.userId = :userId AND email.threadId IS NOT NULL AND email.folder <> :excludedFolder GROUP BY email.threadId")
  List<Object[]> summarizeThreadsByUserId(@Param("userId")
  String userId, @Param("excludedFolder")
  String excludedFolder);

  /**
   * Who wrote the messages of each conversation that carries an unsent draft, with
   * the date each of them first appears in it.
   * <p>
   * This is what a DRAFT row is labelled with. A draft's own sender is the account
   * owner, always — so labelling the row with it named the user to themselves on
   * every draft they had, and never named the person the conversation is actually
   * with. The names have to come from the conversation, and a Drafts listing holds
   * DRAFTS rows and nothing else, so they cannot be read off the list any more than
   * the draft flag could: the conversation's other messages are in INBOX, SENT or
   * ARCHIVE.
   * <p>
   * Restricted to draft-carrying conversations by the subquery, and that restriction
   * is the reason this is affordable rather than an optimisation on top of something
   * affordable. The listing is polled while a sync runs; an unrestricted version
   * would answer a row per (conversation, sender) pair of the whole cache — on a
   * 5000-message mailbox, thousands of rows serialised into every poll for a fact
   * only draft rows read. Restricted, a mailbox with no drafts gets no rows at all
   * and one with three drafts gets a handful. The invariant it buys and that the
   * caller depends on: {@code participants} is populated exactly for the
   * conversations {@code hasDraft} is true of, which is exactly the set of rows that
   * read it.
   * <p>
   * Drafts are excluded from the participant side ({@code draftLocalId IS NULL}) —
   * a message being written is not somebody the conversation is with, and its sender
   * is the owner the whole change exists to stop displaying.
   * <p>
   * It answers one row per (conversation, sender) pair rather than a concatenated
   * list because there is no portable aggregate for "join these strings"; the
   * grouping is what makes the row count the number of distinct senders instead of
   * the number of messages. {@code MIN(receivedDate)} rides along so the caller can
   * order the names by when each person first appears — a listing that re-orders its
   * own participant names between two polls is a flicker, and an ungrouped result
   * set has no order to rely on.
   *
   * One folder is left out, always
   * {@link org.exoplatform.emailConnector.model.MailFolder#TRASH}, and out of BOTH
   * halves of the query. On the participant side, because somebody whose only
   * message in the conversation the user deleted is no longer somebody the
   * conversation shows — the names have to match the messages the reader will
   * actually render. On the subquery side, because a DISCARDED draft is a TRASH row
   * carrying a draft local id: left in, it would keep electing its conversation into
   * the answer, and the caller's invariant (participants are populated exactly for
   * the conversations {@code hasDraft} is true of) would break in the one direction
   * that shows — names beside a row that no longer claims a draft. See
   * {@link #findByUserIdAndThreadIdWithAttachments} for why the exclusion is a
   * parameter.
   *
   * @param userId the mailbox owner
   * @param excludedFolder the folder to leave out, always {@code MailFolder.TRASH}
   * @return rows of {@code [threadId, storedSender, firstSeenDate]}, one per
   *         (conversation, sender) pair of every conversation carrying a draft
   */
  @Query("SELECT participant.threadId, participant.sender, MIN(participant.receivedDate) FROM EmailBoxEntity participant WHERE participant.userId = :userId AND participant.threadId IS NOT NULL AND participant.draftLocalId IS NULL AND participant.folder <> :excludedFolder AND participant.threadId IN (SELECT draft.threadId FROM EmailBoxEntity draft WHERE draft.userId = :userId AND draft.draftLocalId IS NOT NULL AND draft.threadId IS NOT NULL AND draft.folder <> :excludedFolder) GROUP BY participant.threadId, participant.sender")
  List<Object[]> findDraftThreadParticipantsByUserId(@Param("userId")
  String userId, @Param("excludedFolder")
  String excludedFolder);

  /**
   * Per-folder message counts, so the list's folder switch only offers folders that
   * actually have mail (e.g. no empty Archive tab on Gmail, which has no
   * {@code \Archive}).
   * <p>
   * Grouped by folder rather than scoped to any, so TRASH counts itself here as
   * every other folder does — deliberately, and it is the one place a TRASH row is
   * SUPPOSED to be visible outside a Trash listing. This is what will light the
   * Trash tab up when there is something in it and leave it out when there is not,
   * which is precisely the "browsable" half of the rule the exclusions enforce the
   * other half of.
   *
   * @param userId the mailbox owner
   * @return rows of {@code [folder, messageCount]}, one per non-empty folder
   */
  @Query("SELECT email.folder, COUNT(email.id) FROM EmailBoxEntity email WHERE email.userId = :userId GROUP BY email.folder")
  List<Object[]> countMessagesByFolder(@Param("userId")
  String userId);

  /**
   * The distinct conversations of the cached messages sharing an Exchange
   * Thread-Index conversation root — the same conversation even when the
   * {@code References} chain was broken by a subject change or an external forward.
   * <p>
   * Unscoped by folder, TRASH included, for the identity reason
   * {@link #findDistinctThreadIdsByMailHeaderIds} sets out in full.
   *
   * @param userId the mailbox owner
   * @param threadIndexRoot the conversation root GUID
   * @return the distinct thread ids sharing that root
   */
  @Query("SELECT DISTINCT email.threadId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.threadIndexRoot = :threadIndexRoot AND email.threadId IS NOT NULL")
  List<String> findDistinctThreadIdsByThreadIndexRoot(@Param("userId")
  String userId, @Param("threadIndexRoot")
  String threadIndexRoot);

  /**
   * The reference chains of the user's cached messages: each row's thread id with
   * its raw {@code References} and {@code In-Reply-To} headers. This feeds the
   * REVERSE thread lookup (which already-cached messages point AT a given
   * Message-ID — see {@code EmailBoxStorage#getThreadIdsReferencingMessageId}),
   * needed when a message is cached after the replies that reference it: the sync
   * drains prefetch slices in completion order, newest mail first, so a parent
   * routinely lands last. The substring matching itself deliberately happens in
   * Java, NOT here: {@code MAIL_REFERENCES} maps to CLOB on some dialects (verified
   * live on HSQLDB), where SQL string functions are unsupported — a
   * {@code LOCATE}-based version of this query threw
   * {@code SQLFeatureNotSupportedException} on the first live reset and aborted the
   * whole sync. Equality and null checks are all a CLOB column can be trusted with
   * across dialects. The result is bounded by the per-user cache cap
   * (a few hundred rows), and the chain-presence predicate keeps chainless
   * messages — most of a typical inbox — out of the transfer entirely. The
   * empty-string guard keeps backfill-pending rows (threading columns added after
   * their creation) out of the merge machinery.
   * <p>
   * Unscoped by folder, TRASH included, for the identity reason
   * {@link #findDistinctThreadIdsByMailHeaderIds} sets out in full: what crosses the
   * wire here is a pair of header chains, and a deleted message's chain still says
   * which conversation the message that references it belongs to.
   *
   * @param userId the mailbox owner
   * @return rows of {@code [threadId, mailReferences, inReplyTo]} for every cached
   *         message that has a thread id and at least one chain header
   */
  @Query("SELECT email.threadId, email.mailReferences, email.inReplyTo FROM EmailBoxEntity email WHERE email.userId = :userId AND email.threadId IS NOT NULL AND email.threadId <> '' AND (email.mailReferences IS NOT NULL OR email.inReplyTo IS NOT NULL)")
  List<Object[]> findThreadReferenceChainsByUserId(@Param("userId")
  String userId);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.threadIndexRoot = :threadIndexRoot WHERE email.userId = :userId AND email.folder = :folder AND email.mailRemoteId = :mailRemoteId")
  void updateThreadIndexRoot(@Param("userId")
  String userId, @Param("mailRemoteId")
  Long mailRemoteId, @Param("folder")
  String folder, @Param("threadIndexRoot")
  String threadIndexRoot);

  /**
   * The given thread ids repeated once per message, ordered by message date — the
   * caller takes the first to learn which conversation is oldest, the canonical id a
   * merge collapses the others into.
   * <p>
   * Unscoped by folder, TRASH included, for the identity reason
   * {@link #findDistinctThreadIdsByMailHeaderIds} sets out in full. A trashed
   * message is as old as it ever was, and letting it vote here keeps the canonical
   * id stable: the same conversation must not elect a different canonical id
   * depending on whether its oldest message has been deleted since.
   *
   * @param userId the mailbox owner
   * @param threadIds the candidate conversation ids
   * @return the ids, oldest message first
   */
  @Query("SELECT email.threadId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.threadId IN :threadIds ORDER BY email.receivedDate ASC")
  List<String> findThreadIdsOrderedByAge(@Param("userId")
  String userId, @Param("threadIds")
  List<String> threadIds);

  /**
   * Of the given IMAP UIDs, the ones already cached in a folder — one IN query
   * for the whole search result list, so decorating hits with their "already
   * openable locally" flag costs a single statement rather than one lookup per
   * hit (the same per-row discipline the sync reconcile follows).
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator scoping the UIDs
   * @param mailRemoteIds the candidate IMAP UIDs
   * @return the subset of {@code mailRemoteIds} present in the local cache
   */
  @Query("SELECT email.mailRemoteId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.folder = :folder AND email.mailRemoteId IN :mailRemoteIds")
  List<Long> findCachedMailRemoteIds(@Param("userId")
  String userId, @Param("folder")
  String folder, @Param("mailRemoteIds")
  List<Long> mailRemoteIds);

  /**
   * The identity of a conversation's real mail, newest first — what a stored summary
   * is checked against to decide whether it still describes the conversation.
   * <p>
   * DRAFTS ARE EXCLUDED, and that is the point of the query rather than a filter on
   * it. A draft is re-dated and re-saved on every keystroke, so a fingerprint that
   * counted it would go stale between two words; and an unsent reply is precisely the
   * thing a summary must not describe back to the person still writing it.
   * <p>
   * The four columns and no body: this runs on every read of a summary, and dragging
   * the CLOB of a whole conversation through the persistence layer to compare a UID
   * is the mistake {@link #findSyncViewByUserIdAndFolder} was written to undo.
   * {@code MAIL_HEADER_ID} rides along because the count has to be DISTINCT by
   * Message-ID exactly as {@link #summarizeThreadsByUserId} is — the same message is
   * cached once per folder, and a raw row count would report a conversation as having
   * grown when thread completion merely cached a copy of it in ALL_MAIL.
   * <p>
   * The order is by date AND THEN by UID, which a single-column ordering would leave
   * to chance: two messages of one conversation routinely share a received date (a
   * mail and its own copy in another folder, or a bulk send), and "the newest message"
   * has to be the same message on two consecutive reads or the summary flickers
   * between stale and fresh without a single mail arriving.
   *
   * @param userId the mailbox owner
   * @param threadId the conversation id
   * @return rows of {@code [folder, mailRemoteId, mailHeaderId]}, newest first
   */
  @Query("SELECT email.folder, email.mailRemoteId, email.mailHeaderId FROM EmailBoxEntity email WHERE email.userId = :userId AND email.threadId = :threadId AND email.draftLocalId IS NULL ORDER BY email.receivedDate DESC, email.mailRemoteId DESC")
  List<Object[]> findThreadFingerprintRows(@Param("userId")
  String userId, @Param("threadId")
  String threadId);

  /**
   * Collapses several conversations into one: every row carrying one of the given
   * thread ids is re-pointed at the canonical one.
   * <p>
   * Unscoped by folder, TRASH included, and here the totality is not merely safe but
   * REQUIRED. This is the write half of the identity machinery
   * {@link #findDistinctThreadIdsByMailHeaderIds} describes: a merge that skipped
   * Trash would leave the trashed rows behind on a thread id no live row shares any
   * more — a dangling conversation that reappears whole, and separate, the moment
   * one of those rows is restored or re-synced. The rows are re-labelled, never
   * shown; the exclusions on the read side are what keep them out of sight.
   *
   * @param userId the mailbox owner
   * @param canonicalThreadId the conversation id to keep
   * @param threadIds the conversation ids to fold into it
   */
  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.threadId = :canonicalThreadId WHERE email.userId = :userId AND email.threadId IN :threadIds")
  void mergeThreads(@Param("userId")
  String userId, @Param("canonicalThreadId")
  String canonicalThreadId, @Param("threadIds")
  List<String> threadIds);

  @Transactional
  @Modifying
  @Query("UPDATE EmailBoxEntity email SET email.threadId = :threadId, email.inReplyTo = :inReplyTo, email.mailReferences = :mailReferences, email.threadIndexRoot = :threadIndexRoot WHERE email.userId = :userId AND email.folder = :folder AND email.mailRemoteId = :mailRemoteId")
  void updateThreadInfo(@Param("userId")
  String userId, @Param("mailRemoteId")
  Long mailRemoteId, @Param("threadId")
  String threadId, @Param("inReplyTo")
  String inReplyTo, @Param("mailReferences")
  String mailReferences, @Param("folder")
  String folder, @Param("threadIndexRoot")
  String threadIndexRoot);

  /**
   * The light per-folder view contact collection reads: sender, recipients, the
   * distribution headers that tell a human correspondent from a robot, and the
   * received date — WITHOUT the body CLOB and without the attachments join, the
   * same discipline as {@link #findSyncViewByUserIdAndFolder}. Collection runs
   * after every sync group and over the whole cache at backfill, so dragging
   * bodies through the persistence layer here would tax every routine sync for
   * columns nobody reads.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator
   * @return rows of {@code [sender, to, cc, autoSubmitted, hasListId, hasListPost,
   *         hasListUnsubscribe, originalSender, receivedDate]}
   */
  @Query("SELECT email.sender, email.to, email.cc, email.autoSubmitted, email.hasListId, email.hasListPost, email.hasListUnsubscribe, email.originalSender, email.receivedDate FROM EmailBoxEntity email WHERE email.userId = :userId AND email.folder = :folder")
  List<Object[]> findContactSourceRowsByUserIdAndFolder(@Param("userId")
  String userId, @Param("folder")
  String folder);

  /**
   * The {@link #findContactSourceRowsByUserIdAndFolder} view restricted to the
   * given IMAP UIDs — the per-sync-group variant, one IN query for the whole
   * group rather than a per-message statement.
   *
   * @param userId the mailbox owner
   * @param folder the folder discriminator scoping the UIDs
   * @param mailRemoteIds the IMAP UIDs of the group
   * @return rows of {@code [sender, to, cc, autoSubmitted, hasListId, hasListPost,
   *         hasListUnsubscribe, originalSender, receivedDate]}
   */
  @Query("SELECT email.sender, email.to, email.cc, email.autoSubmitted, email.hasListId, email.hasListPost, email.hasListUnsubscribe, email.originalSender, email.receivedDate FROM EmailBoxEntity email WHERE email.userId = :userId AND email.folder = :folder AND email.mailRemoteId IN :mailRemoteIds")
  List<Object[]> findContactSourceRowsByUserIdAndFolderAndUids(@Param("userId")
  String userId, @Param("folder")
  String folder, @Param("mailRemoteIds")
  List<Long> mailRemoteIds);

}
