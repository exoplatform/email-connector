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

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.LongStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.emailConnector.dao.EmailAttachmentDAO;
import org.exoplatform.emailConnector.dao.EmailThreadAiSummaryDAO;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.dao.EmailOrphanFileDAO;
import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

@SpringBootTest(classes = { EmailBoxStorage.class })
@ExtendWith(MockitoExtension.class)
public class EmailBoxStorageTest {

  private static final Long   ID = 2l;

  // The address of the mailbox the listing belongs to — the one name a row must
  // never be labelled with.
  private static final String OWNER_ADDRESS = "owner@example.org";

  @MockBean
  private EmailBoxDAO         emailBoxDAO;

  @MockBean
  private EmailAttachmentDAO  emailAttachmentDAO;

  @MockBean
  private CategoryLinkService categoryLinkService;

  // The storage records a file as unreferenced before deleting the rows that named it,
  // and writes attachment bytes through the platform's file service. Both are mocked
  // here: this class is the fully-mocked rig, and the behaviours that use them are
  // pinned in EmailBoxDraftAttachmentStorageTest against a real database.
  @MockBean
  private EmailOrphanFileDAO  emailOrphanFileDAO;

  // Nothing in this class touches a conversation summary; the storage now holds this
  // repository, so the context would simply refuse to start without it.
  @MockBean
  private EmailThreadAiSummaryDAO emailThreadAiSummaryDAO;

  @MockBean
  private FileService         fileService;

  @MockBean
  private UploadService       uploadService;

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @BeforeEach
  void setup() {

    when(emailBoxDAO.save(any())).thenAnswer(invocation -> {
      EmailBoxEntity entity = invocation.getArgument(0);
      if (entity.getId() == null) {
        entity.setId(ID);
      }
      when(emailBoxDAO.findByUserIdWithAttachments("root")).thenReturn(Optional.of(entity)
                                                                               .stream()
                                                                               .filter(email -> email.getUserId().equals("root"))
                                                                               .toList());
      when(emailBoxDAO.findByMailRemoteIdAndUserIdAndFolder(1212l, "root", "INBOX")).thenReturn(entity);

      when(emailBoxDAO.findById(ID)).thenReturn(Optional.of(entity));

      return entity;
    });

    doAnswer(invocation -> {
      when(emailBoxDAO.findByUserIdWithAttachments("root")).thenReturn(Collections.emptyList());
      return null;
    }).when(emailBoxDAO).deleteByUserId(any());

    doAnswer(invocation -> {
      when(emailBoxDAO.findByUserIdWithAttachments("root")).thenReturn(Collections.emptyList());
      return null;
    }).when(emailBoxDAO).deleteEmailsByIds(any());

    doAnswer(invocation -> {
      boolean readStatus = invocation.getArgument(2);
      EmailBoxEntity entity = emailBoxDAO.findByMailRemoteIdAndUserIdAndFolder(1212L, "root", "INBOX");
      if (entity != null) {
        entity.setRead(readStatus);
      }
      return null;
    }).when(emailBoxDAO).updateReadStatusByMailRemoteIds(anyList(), anyString(), anyBoolean(), anyString());

    doAnswer(invocation -> {
      EmailBoxEntity entity = emailBoxDAO.findByMailRemoteIdAndUserIdAndFolder(1212L, "root", "INBOX");
      if (entity != null) {
        entity.setRecent(false);
      }
      return null;
    }).when(emailBoxDAO).markEmailAsNotRecent(anyLong(), anyString(), anyString());
  }

  @Test
  void createEmail() {
    assertThrows(IllegalArgumentException.class, () -> emailBoxStorage.createEmail(null));
    Email email = email("root");
    Email storedEmail = emailBoxStorage.createEmail(email);
    assertNotNull(storedEmail);
    assertNotNull(storedEmail.getId());
    assertTrue(storedEmail.getId() > 0);
  }

  @Test
  void markEmailAsNotRecent() {
    Email email = email("root");
    emailBoxStorage.createEmail(email);
    emailBoxStorage.markEmailAsNotRecent(1212l, "root", "INBOX");
    Email updatedEmail = emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, "root", null, "INBOX", false, false, false);
    assertNotNull(updatedEmail);
    assertFalse(updatedEmail.isRecent());
  }

  @Test
  void updateEmailReadStatusByMailRemoteIds() {
    Email email = email("root");
    emailBoxStorage.createEmail(email);
    emailBoxStorage.updateEmailReadStatusByMailRemoteIds(List.of(1212l), "root", true, "INBOX");
    Email updatedEmail = emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, "root", null, "INBOX", false, false, false);
    assertNotNull(updatedEmail);
    assertTrue(updatedEmail.isRead());
  }

  @Test
  void getThreadIdsReferencingMessageIdMatchesChainsInJava() {
    // The matching runs in Java over the chains the DAO returns (SQL substring
    // functions are unsupported on the CLOB columns of some dialects -- a LOCATE
    // version aborted a live sync on HSQLDB). The storage must: match the bracketed
    // token exactly (neither <xa@host> nor <a@host.com> may match <a@host>), fall
    // back to In-Reply-To when References misses, normalize a bare id to its
    // bracketed form, de-duplicate thread ids, and skip the database entirely for a
    // blank id (a synthesized id can never be referenced).
    when(emailBoxDAO.findThreadReferenceChainsByUserId("root"))
                                                              .thenReturn(List.of(
                                                                                  new Object[] { "<t1@host>",
                                                                                      "<root@host> <a@host>", null },
                                                                                  new Object[] { "<t1@host>", null, "<a@host>" },
                                                                                  new Object[] { "<t2@host>", "<xa@host>",
                                                                                      "<a@host.com>" },
                                                                                  new Object[] { "<t3@host>", null, null }));
    assertEquals(List.of("<t1@host>"), emailBoxStorage.getThreadIdsReferencingMessageId("root", "<a@host>"));
    assertEquals(List.of("<t1@host>"), emailBoxStorage.getThreadIdsReferencingMessageId("root", "a@host"));
    assertEquals(List.of("<t2@host>"), emailBoxStorage.getThreadIdsReferencingMessageId("root", "<xa@host>"));
    assertEquals(List.of(), emailBoxStorage.getThreadIdsReferencingMessageId("root", null));
    assertEquals(List.of(), emailBoxStorage.getThreadIdsReferencingMessageId("root", " "));
  }

  @Test
  void getSyncEmailsMapsTheLightViewWithoutBodiesOrCategories() {
    // The sync view must carry exactly what the reconcile reads -- ids, flags
    // (including the starred mirror of \Flagged), threading state, and the two draft
    // columns: the state the cleanup needs to spare an unpushed draft, and the local
    // id every write to a draft is addressed by -- and nothing that costs a CLOB read
    // or a category lookup, because loading those for 5000 rows per sync was the
    // point of removing it.
    when(emailBoxDAO.findSyncViewByUserIdAndFolder("root", "INBOX"))
                                                                   .thenReturn(List.<Object[]> of(new Object[] { 7L, 1212L,
                                                                       "<t@host>", "", Boolean.TRUE, Boolean.FALSE,
                                                                       Boolean.TRUE, null, null }));
    List<Email> emails = emailBoxStorage.getSyncEmails("root", "INBOX");
    assertEquals(1, emails.size());
    Email email = emails.get(0);
    assertEquals(7L, email.getId());
    assertEquals(1212L, email.getMailRemoteId());
    assertEquals("<t@host>", email.getThreadId());
    assertEquals("", email.getThreadIndexRoot());
    assertTrue(email.isRead());
    assertFalse(email.isRecent());
    assertTrue(email.isStarred());
    assertEquals("root", email.getUserId());
    assertEquals("INBOX", email.getFolder());
    assertNull(email.getContent());
    assertNull(email.getCategoryIds());
  }

  @Test
  void getSyncEmailsCarriesTheDraftHandleTheReconcileWritesBy() {
    // A draft whose server copy vanished has to be put back to a state that will
    // re-upload, and every draft write is addressed by its local id -- never by the
    // row id or the UID, both of which move under a draft. Without it in the light
    // view the reconcile would have to re-read every draft row in full to learn it.
    when(emailBoxDAO.findSyncViewByUserIdAndFolder("root", "DRAFTS"))
                                                                    .thenReturn(List.<Object[]> of(new Object[] { 9L, 4242L,
                                                                        "<t@host>", "", Boolean.TRUE, Boolean.FALSE,
                                                                        Boolean.FALSE, DraftState.DIRTY, "draft-1" }));
    Email draft = emailBoxStorage.getSyncEmails("root", "DRAFTS").get(0);
    assertEquals(DraftState.DIRTY, draft.getDraftState());
    assertEquals("draft-1", draft.getDraftLocalId());
  }

  @Test
  void detachDraftFromServerCopyPutsTheRowBackToNeverUploaded() {
    // Its own call rather than the edit path: it carries no text, so the revision
    // guard would be entitled to drop it -- and this is the one write that must not
    // be lost, or the next upload would try to remove a copy that does not exist.
    emailBoxStorage.detachDraftFromServerCopy("root", "draft-1");
    verify(emailBoxDAO).detachDraftFromServerCopy("root", "draft-1", DraftState.LOCAL_ONLY, MailFolder.DRAFTS);
    // A blank handle names no row, and an unbounded UPDATE over a draft table is not
    // the way to find that out.
    emailBoxStorage.detachDraftFromServerCopy("root", " ");
    verify(emailBoxDAO, never()).detachDraftFromServerCopy(anyString(), eq(" "), any(), anyString());
  }

  @Test
  void isMessageCachedInFolderAsksOnlyAboutMessageIdIdentity() {
    // The janitor's whole test. Message-ID equality and nothing else: subject,
    // recipients or date would each be a guess about two different messages being
    // "the same one", and that guess deletes somebody's unsent words.
    when(emailBoxDAO.countByMailHeaderIdAndUserIdAndFolder("<sent@host>", "root", "SENT")).thenReturn(1L);
    assertTrue(emailBoxStorage.isMessageCachedInFolder("root", "<sent@host>", "SENT"));
    when(emailBoxDAO.countByMailHeaderIdAndUserIdAndFolder("<other@host>", "root", "SENT")).thenReturn(0L);
    assertFalse(emailBoxStorage.isMessageCachedInFolder("root", "<other@host>", "SENT"));
    // A draft whose client left no Message-ID has no identity to match on, so it is
    // never anybody's already-sent copy.
    assertFalse(emailBoxStorage.isMessageCachedInFolder("root", null, "SENT"));
    verify(emailBoxDAO, never()).countByMailHeaderIdAndUserIdAndFolder(null, "root", "SENT");
  }

  @Test
  void markEmailsAsNotRecentSkipsTheDatabaseOnEmptyList() {
    // The bulk clear is called once per folder sync; when nothing wears the recent
    // badge it must not cost a statement.
    emailBoxStorage.markEmailsAsNotRecent(List.of(), "root", "INBOX");
    emailBoxStorage.markEmailsAsNotRecent(null, "root", "INBOX");
    verify(emailBoxDAO, never()).markEmailsAsNotRecent(anyList(), anyString(), anyString());
    emailBoxStorage.markEmailsAsNotRecent(List.of(1L), "root", "INBOX");
    verify(emailBoxDAO).markEmailsAsNotRecent(List.of(1L), "root", "INBOX");
  }

  @Test
  void markEmailsAsNotRecentSlicesLongIdListsForOracle() {
    // Oracle refuses an IN list past 1000 literals (ORA-01795), and this list is bounded by
    // the mailbox cache size -- 1000 by default now, up to 5000 when an administrator raises
    // it. A first sync of a full cache must not fail on the very statement added to make
    // bulk syncs cheaper.
    List<Long> ids = LongStream.rangeClosed(1, 2500).boxed().toList();

    emailBoxStorage.markEmailsAsNotRecent(ids, "root", "INBOX");

    ArgumentCaptor<List<Long>> slices = ArgumentCaptor.forClass(List.class);
    verify(emailBoxDAO, times(3)).markEmailsAsNotRecent(slices.capture(), eq("root"), eq("INBOX"));
    List<List<Long>> issued = slices.getAllValues();
    assertEquals(900, issued.get(0).size());
    assertEquals(900, issued.get(1).size());
    assertEquals(700, issued.get(2).size());
    // Every id is still covered, exactly once and in order: a slice that dropped or
    // duplicated rows would leave messages wearing a stale recent badge.
    assertEquals(ids, issued.stream().flatMap(List::stream).toList());
  }

  @Test
  void updateEmailStarredStatusSkipsTheDatabaseOnEmptyList() {
    // Called once per direction on every folder sync; when no star changed it must
    // not cost a statement, exactly like the recent-badge clear.
    emailBoxStorage.updateEmailStarredStatusByMailRemoteIds(List.of(), "root", true, "INBOX");
    emailBoxStorage.updateEmailStarredStatusByMailRemoteIds(null, "root", true, "INBOX");
    verify(emailBoxDAO, never()).updateStarredStatusByMailRemoteIds(anyList(), anyString(), anyBoolean(), anyString());
    emailBoxStorage.updateEmailStarredStatusByMailRemoteIds(List.of(1L), "root", true, "INBOX");
    verify(emailBoxDAO).updateStarredStatusByMailRemoteIds(List.of(1L), "root", true, "INBOX");
  }

  @Test
  void getEmailByMailRemoteIdAndUserId() {
    Email retrievedEmail = emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, "root", null, "INBOX", false, false, false);
    assertNull(retrievedEmail);
    Email email1 = email("root");
    emailBoxStorage.createEmail(email1);
    retrievedEmail = emailBoxStorage.getEmailByMailRemoteIdAndUserId(1212l, "root", null, "INBOX", false, false, false);
    assertNotNull(retrievedEmail);
  }

  @Test
  void getEmailById() {
    Email retrievedEmail = emailBoxStorage.getEmailById(2l, "root", null);
    assertNull(retrievedEmail);
    Email email1 = email("root");
    emailBoxStorage.createEmail(email1);
    retrievedEmail = emailBoxStorage.getEmailById(2l, "root", null);
    assertNotNull(retrievedEmail);
  }

  @Test
  void getEmails() {
    List<Email> retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertEquals(0, retrievedEmailEntities.size());
    Email email1 = email("root");
    emailBoxStorage.createEmail(email1);
    retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertNotNull(retrievedEmailEntities);
    assertEquals(1, retrievedEmailEntities.size());
    assertNotNull(retrievedEmailEntities.get(0));
    assertEquals(2l, retrievedEmailEntities.get(0).getId());
    assertEquals(1212l, retrievedEmailEntities.get(0).getMailRemoteId());
    assertEquals("subject", retrievedEmailEntities.get(0).getSubject());
    assertEquals("body", retrievedEmailEntities.get(0).getContent().getBody());
    assertEquals("body", retrievedEmailEntities.get(0).getContent().getExcerpt());
    assertEquals("sender", retrievedEmailEntities.get(0).getSender().getName());
  }

  @Test
  void saveDraftInsertsTheFirstTimeAndUpdatesInPlaceAfterwards() {
    when(emailBoxDAO.findByUserIdAndDraftLocalIdWithAttachments("root", "draft-1", MailFolder.DRAFTS)).thenReturn(Collections.emptyList());
    Email first = draft("draft-1", 1L, "first sentence");
    Email created = emailBoxStorage.saveDraft(first);
    assertNotNull(created.getId());
    // second save: the SAME row, with the new text
    EmailBoxEntity stored = draftEntity("draft-1", 1L, "first sentence");
    when(emailBoxDAO.findByUserIdAndDraftLocalIdWithAttachments("root", "draft-1", MailFolder.DRAFTS)).thenReturn(List.of(stored));
    Email updated = emailBoxStorage.saveDraft(draft("draft-1", 2L, "second sentence"));
    assertEquals("second sentence", updated.getContent().getBody());
    assertEquals(2L, updated.getDraftRevision());
  }

  @Test
  void saveDraftDropsASaveThatArrivesOutOfOrder() {
    // Autosaves are fired by a typing pause and travel over the network, so a slow
    // request carrying older text can land after a newer one. Applying it would
    // silently revert the sentence the user just finished, which is the one failure
    // this whole feature exists to prevent.
    EmailBoxEntity stored = draftEntity("draft-1", 5L, "the newest sentence");
    when(emailBoxDAO.findByUserIdAndDraftLocalIdWithAttachments("root", "draft-1", MailFolder.DRAFTS)).thenReturn(List.of(stored));
    Email late = emailBoxStorage.saveDraft(draft("draft-1", 4L, "a sentence from before"));
    assertEquals("the newest sentence", late.getContent().getBody());
    assertEquals(5L, late.getDraftRevision());
    verify(emailBoxDAO, never()).save(any());
  }

  @Test
  void markDraftUploadedRecordsTheUidAndMarksTheRowSynced() {
    // The upload's bookkeeping goes through its own call, not through the edit path:
    // it carries no new text, and routed through saveDraft it would look like a save
    // arriving at a revision the row has already reached, and be dropped.
    EmailBoxEntity stored = draftEntity("draft-1", 5L, "the newest sentence");
    when(emailBoxDAO.findByUserIdAndDraftLocalIdWithAttachments("root", "draft-1", MailFolder.DRAFTS)).thenReturn(List.of(stored));
    Email saved = emailBoxStorage.markDraftUploaded("root", "draft-1", 4242L, 5L);
    assertEquals(4242L, saved.getMailRemoteId());
    assertEquals(DraftState.SYNCED, saved.getDraftState());
  }

  @Test
  void markDraftUploadedLeavesARowThatWasTypedIntoMidUploadUnsynced() {
    // Marking a row as safely on the server while it carries a sentence that was never
    // sent up is exactly the failure this feature exists to prevent. The UID is still
    // kept -- it is where the previous copy lives -- but the state does not move, so
    // the next push still runs.
    EmailBoxEntity stored = draftEntity("draft-1", 6L, "a sentence typed during the upload");
    when(emailBoxDAO.findByUserIdAndDraftLocalIdWithAttachments("root", "draft-1", MailFolder.DRAFTS)).thenReturn(List.of(stored));
    Email saved = emailBoxStorage.markDraftUploaded("root", "draft-1", 4242L, 5L);
    assertEquals(4242L, saved.getMailRemoteId());
    assertEquals(DraftState.LOCAL_ONLY, saved.getDraftState());
  }

  @Test
  void markDraftUploadedIgnoresADraftThatIsNoLongerThere() {
    when(emailBoxDAO.findByUserIdAndDraftLocalIdWithAttachments("root", "gone", MailFolder.DRAFTS)).thenReturn(Collections.emptyList());
    assertNull(emailBoxStorage.markDraftUploaded("root", "gone", 1L, 1L));
  }

  @Test
  void updateDraftStateMovesTheStateAndTouchesNothingElse() {
    // The send's claim on the row. It goes through its own call for the same reason
    // the upload's bookkeeping does: it carries no text, and through the edit path the
    // revision guard could drop it -- and a claim that can be silently dropped is not
    // a claim.
    EmailBoxEntity stored = draftEntity("draft-1", 5L, "the newest sentence");
    when(emailBoxDAO.findByUserIdAndDraftLocalIdWithAttachments("root", "draft-1", MailFolder.DRAFTS)).thenReturn(List.of(stored));
    Email claimed = emailBoxStorage.updateDraftState("root", "draft-1", DraftState.SENDING);
    assertEquals(DraftState.SENDING, claimed.getDraftState());
    assertEquals(5L, claimed.getDraftRevision());
    assertEquals("the newest sentence", claimed.getContent().getBody());
  }

  @Test
  void updateDraftStateIgnoresADraftThatIsNoLongerThere() {
    when(emailBoxDAO.findByUserIdAndDraftLocalIdWithAttachments("root", "gone", MailFolder.DRAFTS)).thenReturn(Collections.emptyList());
    assertNull(emailBoxStorage.updateDraftState("root", "gone", DraftState.SENDING));
  }

  @Test
  void saveDraftRefusesADraftWithNoLocalId() {
    assertThrows(IllegalArgumentException.class, () -> emailBoxStorage.saveDraft(draft(null, 1L, "orphan")));
  }

  @Test
  void deleteEmailsByIds() {
    Email email1 = email("root");
    Email storedEmail = emailBoxStorage.createEmail(email1);
    List<Email> retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertNotNull(retrievedEmailEntities);
    assertEquals(1, retrievedEmailEntities.size());
    List<Long> emailsIds = List.of(storedEmail.getId());
    emailBoxStorage.deleteEmailsByIds(emailsIds);
    retrievedEmailEntities = emailBoxStorage.getEmails("root");
    assertEquals(0, retrievedEmailEntities.size());
  }

  @Test
  void getAttachmentByIdAndMailRemoteId() {
    EmailAttachment retrievedEmailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", "root", MailFolder.INBOX);
    assertNull(retrievedEmailAttachment);
    EmailBoxEntity emailBoxEntity = new EmailBoxEntity(null,
                                                       1212l,
                                                       null,
                                                       "root",
                                                       "subject",
                                                       "body",
                                                       "sender",
                                                       "to",
                                                       "cc",
                                                       "bcc",
                                                       "replyTo",
                                                       new Date(),
                                                       false,
                                                       false,
                                                       null,
                                                       null,
                                                       null,
                                                       null,
                                                       "INBOX",
                                                       null,
                                                       false,
                                                       false,
                                                       false,
                                                       false,
                                                       null,
                                                       false,
                                                       null,
                                                       null,
                                                       null,
                                                       null,
                                                       null);
    Optional<EmailAttachmentEntity> emailAttachmentEntity = Optional.ofNullable(new EmailAttachmentEntity(2L,
                                                                                                          emailBoxEntity,
                                                                                                          "2",
                                                                                                          "attachment.pdf",
                                                                                                          "application/pdf", null, null));
    when(emailAttachmentDAO.findByMailRemoteIdAndAttachmentIdAndUserIdAndFolder(1212l, "2", "root", MailFolder.INBOX)).thenReturn(emailAttachmentEntity);
    retrievedEmailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", "root", MailFolder.INBOX);
    assertNotNull(retrievedEmailAttachment);
    retrievedEmailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212L, "2", "root", MailFolder.INBOX);
    assertNotNull(retrievedEmailAttachment);
    assertEquals("attachment.pdf", retrievedEmailAttachment.getName());
    assertEquals("application/pdf", retrievedEmailAttachment.getMimeType());
    assertEquals(1212L, retrievedEmailAttachment.getMailRemoteId());
  }

  /**
   * The mapping of the summary aggregate onto what the list reads: a count, and a
   * draft column read as "more than none".
   * <p>
   * Whether the QUERY answers the right rows is settled against a real database in
   * {@code EmailBoxThreadSummaryDAOTest}, not here — a mocked DAO can only be asked
   * whether three columns are read in the right order, which is all this asserts.
   */
  @Test
  void getThreadSummariesReadsTheCountAndTheDraftOutOfOneRow() {
    when(emailBoxDAO.summarizeThreadsByUserId("root", MailFolder.TRASH)).thenReturn(List.<Object[]>of(new Object[] { "thread-with-draft", 3L, 1L },
                                                                         new Object[] { "thread-without-draft", 2L, 0L }));

    var summaries = emailBoxStorage.getThreadSummaries("root", OWNER_ADDRESS);

    assertEquals(2, summaries.size());
    assertEquals(3, summaries.get("thread-with-draft").messageCount());
    assertTrue(summaries.get("thread-with-draft").hasDraft());
    assertEquals(2, summaries.get("thread-without-draft").messageCount());
    assertFalse(summaries.get("thread-without-draft").hasDraft());
  }

  /**
   * A null draft column reads as "no drafts". The column is a {@code SUM} over a
   * {@code CASE}, and a dialect is entitled to answer null where it had nothing to
   * add up; a NullPointerException in the middle of building the inbox listing is
   * not an acceptable reading of "this conversation has no draft".
   */
  @Test
  void getThreadSummariesTreatsAnAbsentDraftColumnAsNoDraft() {
    when(emailBoxDAO.summarizeThreadsByUserId("root", MailFolder.TRASH)).thenReturn(List.<Object[]>of(new Object[] { "thread-1", 1L, null }));

    var summaries = emailBoxStorage.getThreadSummaries("root", OWNER_ADDRESS);

    assertEquals(1, summaries.get("thread-1").messageCount());
    assertFalse(summaries.get("thread-1").hasDraft());
  }

  /**
   * The names a draft row is labelled with: the conversation's other correspondents,
   * oldest first, and never the account owner — whose address is the sender of every
   * draft and of every sent copy, and whose name on a draft row is the whole defect
   * this exists to fix ("benjamin benjamin, Draft" on a reply to Véronika).
   * <p>
   * Ordering is asserted rather than assumed because the rows arrive from a
   * {@code GROUP BY} with no order of their own, and this listing is polled: names
   * that re-shuffle between two polls are a flicker on screen.
   * <p>
   * Whether the QUERY answers the right conversations is settled against a real
   * database in {@code EmailBoxThreadSummaryDAOTest}. What is asserted here is the
   * part that lives in Java and cannot be expressed in SQL at all.
   */
  @Test
  void getThreadSummariesNamesTheOtherPeopleInADraftsConversation() {
    when(emailBoxDAO.summarizeThreadsByUserId("root", MailFolder.TRASH)).thenReturn(List.<Object[]>of(new Object[] { "thread-1", 3L, 1L }));
    when(emailBoxDAO.findDraftThreadParticipantsByUserId("root", MailFolder.TRASH)).thenReturn(List.<Object[]>of(new Object[] { "thread-1",
        "Benjamin,owner@example.org", date(1) }, new Object[] { "thread-1", "Gianni,gianni@example.org", date(3) },
                                                                                              new Object[] { "thread-1",
                                                                                                  "Véronika,veronika@example.org",
                                                                                                  date(2) }));

    var summaries = emailBoxStorage.getThreadSummaries("root", OWNER_ADDRESS);

    assertEquals(List.of("Véronika", "Gianni"),
                 summaries.get("thread-1").participants(),
                 "the conversation's other people, in the order they first wrote in it, the owner left out");
  }

  /**
   * A draft that answers nothing: its conversation holds no message but the draft
   * itself, so there is nobody to name and the row shows the marker alone — Gmail's
   * shape for a new message being written, and what the product owner asked for in
   * as many words ("no need to tell me my name").
   * <p>
   * The empty list matters as much as a populated one: it is what the client tests
   * to decide whether the marker gets a leading comma.
   */
  @Test
  void getThreadSummariesNamesNobodyOnAConversationTheOwnerIsAloneIn() {
    when(emailBoxDAO.summarizeThreadsByUserId("root", MailFolder.TRASH)).thenReturn(List.<Object[]>of(new Object[] { "thread-1", 1L, 1L }));
    when(emailBoxDAO.findDraftThreadParticipantsByUserId("root", MailFolder.TRASH)).thenReturn(List.<Object[]>of(new Object[] { "thread-1",
        "Benjamin,owner@example.org", date(1) }));

    var summaries = emailBoxStorage.getThreadSummaries("root", OWNER_ADDRESS);

    assertTrue(summaries.get("thread-1").participants().isEmpty(), "a draft that answers nothing is named by nothing");
  }

  /**
   * One person cached twice, once with a display name and once without — which is
   * ordinary, since a From header carries a personal part only when the sender's
   * client wrote one. They are one correspondent and must be named once, by the name
   * rather than by the bare address they were also seen under.
   * <p>
   * Two rows for one address would otherwise read "Véronika, veronika@example.org,
   * Draft": the same person twice, in a line whose whole job is to say who the
   * conversation is with.
   */
  @Test
  void getThreadSummariesNamesTheSamePersonOnce() {
    when(emailBoxDAO.summarizeThreadsByUserId("root", MailFolder.TRASH)).thenReturn(List.<Object[]>of(new Object[] { "thread-1", 3L, 1L }));
    when(emailBoxDAO.findDraftThreadParticipantsByUserId("root", MailFolder.TRASH)).thenReturn(List.<Object[]>of(new Object[] { "thread-1",
        ",veronika@example.org", date(1) }, new Object[] { "thread-1", "Véronika,veronika@example.org", date(2) }));

    var summaries = emailBoxStorage.getThreadSummaries("root", OWNER_ADDRESS);

    assertEquals(List.of("Véronika"), summaries.get("thread-1").participants(), "one address is one person, named by their name");
  }

  /**
   * A conversation that carries no draft has no names, and nothing is expected to
   * give it any: the query that gathers them is scoped to draft-carrying
   * conversations precisely so that a mailbox with no drafts pays nothing for this,
   * and only draft rows read the field.
   */
  @Test
  void getThreadSummariesLeavesAnOrdinaryConversationUnnamed() {
    when(emailBoxDAO.summarizeThreadsByUserId("root", MailFolder.TRASH)).thenReturn(List.<Object[]>of(new Object[] { "thread-1", 2L, 0L }));

    var summaries = emailBoxStorage.getThreadSummaries("root", OWNER_ADDRESS);

    assertTrue(summaries.get("thread-1").participants().isEmpty());
  }

  /**
   * A fixed instant, so the participant ordering under test is the one the dates
   * dictate rather than whatever order the stubbed rows happen to be listed in.
   *
   * @param dayOfMonth the day of January 2026 the message arrived
   * @return that date
   */
  private Date date(int dayOfMonth) {
    return java.util.Date.from(java.time.LocalDate.of(2026, 1, dayOfMonth)
                                                  .atStartOfDay(java.time.ZoneOffset.UTC)
                                                  .toInstant());
  }

  /**
   * What the message said about its own body goes into the row and comes back out of it.
   * <p>
   * The read side is the half that was broken: the mapping rebuilt the content with the
   * three-argument constructor, which leaves the flag false, so everything served from
   * the cache claimed not to be HTML however it had arrived.
   */
  @Test
  void theBodyFormatSurvivesTheMapping() {
    Email htmlEmail = email("root");
    htmlEmail.getContent().setHtml(true);
    emailBoxStorage.createEmail(htmlEmail);
    assertTrue(emailBoxStorage.getEmailById(ID, "root", null).getContent().isHtml());

    Email plainEmail = email("root");
    plainEmail.getContent().setHtml(false);
    emailBoxStorage.createEmail(plainEmail);
    assertFalse(emailBoxStorage.getEmailById(ID, "root", null).getContent().isHtml());
  }

  /**
   * A row cached before the column existed has nothing to say, so its body is read
   * instead — the one place the old browser-side guess still lives, and the only rows it
   * applies to.
   */
  @Test
  void aRowThatWasNeverAskedHasItsBodyRead() {
    Email email = email("root");
    email.getContent().setBody("<div dir=\"ltr\">Hello</div>");
    emailBoxStorage.createEmail(email);
    // Exactly what the upgrade leaves behind: a body, and no answer about it.
    emailBoxDAO.findById(ID).orElseThrow().setHtml(null);
    assertTrue(emailBoxStorage.getEmailById(ID, "root", null).getContent().isHtml());

    Email plainEmail = email("root");
    plainEmail.getContent().setBody("Hello,\n\nsee you Monday.");
    emailBoxStorage.createEmail(plainEmail);
    emailBoxDAO.findById(ID).orElseThrow().setHtml(null);
    assertFalse(emailBoxStorage.getEmailById(ID, "root", null).getContent().isHtml());
  }

  private Email email(String username) {
    EmailAttachment emailAttachment = emailAttachment();
    return new Email(null,
                     1212l,
                     null,
                     username,
                     null,
                     "subject",
                     new EmailContent("body", null, List.of(emailAttachment)),
                     new Date(),
                     new EmailSender("sender", null, null, null),
                     false,
                     false,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     null,
                     "INBOX",
                     null,
                     false,
                     false,
                     false,
                     false,
                     null,
                     false,
                     null,
                     null,
                     null,
                     null, null);
  }

  /**
   * A draft as the service hands it to the storage layer.
   *
   * @param draftLocalId the composer's handle on the draft
   * @param revision the local edit counter this save carries
   * @param body the text
   * @return the draft DTO
   */
  private Email draft(String draftLocalId, Long revision, String body) {
    Email draft = new Email();
    draft.setUserId("root");
    draft.setFolder(MailFolder.DRAFTS);
    draft.setDraftLocalId(draftLocalId);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft.setDraftRevision(revision);
    draft.setDraftUpdatedDate(new Date());
    draft.setReceivedDate(new Date());
    draft.setSubject("subject");
    draft.setContent(new EmailContent(body, null, null));
    draft.setSender(new EmailSender("sender", "sender@example.org", null, null));
    return draft;
  }

  /**
   * A draft row as it already stands in the database.
   *
   * @param draftLocalId the composer's handle on the draft
   * @param revision the revision the stored row holds
   * @param body the stored text
   * @return the stored entity
   */
  private EmailBoxEntity draftEntity(String draftLocalId, Long revision, String body) {
    EmailBoxEntity entity = new EmailBoxEntity();
    entity.setId(ID);
    entity.setUserId("root");
    entity.setFolder(MailFolder.DRAFTS);
    entity.setSubject("subject");
    entity.setBody(body);
    entity.setSender("sender,sender@example.org");
    entity.setReceivedDate(new Date());
    entity.setDraftLocalId(draftLocalId);
    entity.setDraftState(DraftState.LOCAL_ONLY);
    entity.setDraftRevision(revision);
    entity.setDraftUpdatedDate(new Date());
    return entity;
  }

  private EmailAttachment emailAttachment() {
    return new EmailAttachment(null, 1212l, "2", "attachment.pdf", "application/pdf", null, MailFolder.INBOX, null, null, null);
  }
}
