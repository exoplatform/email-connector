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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import org.exoplatform.emailConnector.dao.EmailAttachmentDAO;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailAttachmentEntity;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;

import io.meeds.social.category.service.CategoryLinkService;

@SpringBootTest(classes = { EmailBoxStorage.class })
@ExtendWith(MockitoExtension.class)
public class EmailBoxStorageTest {

  private static final Long   ID = 2l;

  @MockBean
  private EmailBoxDAO         emailBoxDAO;

  @MockBean
  private EmailAttachmentDAO  emailAttachmentDAO;

  @MockBean
  private CategoryLinkService categoryLinkService;

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
    verify(emailBoxDAO).detachDraftFromServerCopy("root", "draft-1", DraftState.LOCAL_ONLY);
    // A blank handle names no row, and an unbounded UPDATE over a draft table is not
    // the way to find that out.
    emailBoxStorage.detachDraftFromServerCopy("root", " ");
    verify(emailBoxDAO, never()).detachDraftFromServerCopy(anyString(), eq(" "), any());
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
    when(emailBoxDAO.findByUserIdAndDraftLocalId("root", "draft-1")).thenReturn(Collections.emptyList());
    Email first = draft("draft-1", 1L, "first sentence");
    Email created = emailBoxStorage.saveDraft(first);
    assertNotNull(created.getId());
    // second save: the SAME row, with the new text
    EmailBoxEntity stored = draftEntity("draft-1", 1L, "first sentence");
    when(emailBoxDAO.findByUserIdAndDraftLocalId("root", "draft-1")).thenReturn(List.of(stored));
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
    when(emailBoxDAO.findByUserIdAndDraftLocalId("root", "draft-1")).thenReturn(List.of(stored));
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
    when(emailBoxDAO.findByUserIdAndDraftLocalId("root", "draft-1")).thenReturn(List.of(stored));
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
    when(emailBoxDAO.findByUserIdAndDraftLocalId("root", "draft-1")).thenReturn(List.of(stored));
    Email saved = emailBoxStorage.markDraftUploaded("root", "draft-1", 4242L, 5L);
    assertEquals(4242L, saved.getMailRemoteId());
    assertEquals(DraftState.LOCAL_ONLY, saved.getDraftState());
  }

  @Test
  void markDraftUploadedIgnoresADraftThatIsNoLongerThere() {
    when(emailBoxDAO.findByUserIdAndDraftLocalId("root", "gone")).thenReturn(Collections.emptyList());
    assertNull(emailBoxStorage.markDraftUploaded("root", "gone", 1L, 1L));
  }

  @Test
  void updateDraftStateMovesTheStateAndTouchesNothingElse() {
    // The send's claim on the row. It goes through its own call for the same reason
    // the upload's bookkeeping does: it carries no text, and through the edit path the
    // revision guard could drop it -- and a claim that can be silently dropped is not
    // a claim.
    EmailBoxEntity stored = draftEntity("draft-1", 5L, "the newest sentence");
    when(emailBoxDAO.findByUserIdAndDraftLocalId("root", "draft-1")).thenReturn(List.of(stored));
    Email claimed = emailBoxStorage.updateDraftState("root", "draft-1", DraftState.SENDING);
    assertEquals(DraftState.SENDING, claimed.getDraftState());
    assertEquals(5L, claimed.getDraftRevision());
    assertEquals("the newest sentence", claimed.getContent().getBody());
  }

  @Test
  void updateDraftStateIgnoresADraftThatIsNoLongerThere() {
    when(emailBoxDAO.findByUserIdAndDraftLocalId("root", "gone")).thenReturn(Collections.emptyList());
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
    EmailAttachment retrievedEmailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", "root");
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
                                                       null);
    Optional<EmailAttachmentEntity> emailAttachmentEntity = Optional.ofNullable(new EmailAttachmentEntity(2L,
                                                                                                          emailBoxEntity,
                                                                                                          "2",
                                                                                                          "attachment.pdf",
                                                                                                          "application/pdf"));
    when(emailAttachmentDAO.findByMailRemoteIdAndAttachmentIdAndUserId(1212l, "2", "root")).thenReturn(emailAttachmentEntity);
    retrievedEmailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212l, "2", "root");
    assertNotNull(retrievedEmailAttachment);
    retrievedEmailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(1212L, "2", "root");
    assertNotNull(retrievedEmailAttachment);
    assertEquals("attachment.pdf", retrievedEmailAttachment.getName());
    assertEquals("application/pdf", retrievedEmailAttachment.getMimeType());
    assertEquals(1212L, retrievedEmailAttachment.getMailRemoteId());
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
                     null);
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
    return new EmailAttachment(null, 1212l, "2", "attachment.pdf", "application/pdf", null);
  }
}
