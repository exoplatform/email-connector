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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.file.model.FileInfo;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.dao.EmailOrphanFileDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.entity.EmailOrphanFileEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * A draft's own attachments against a real database, on the shipped Liquibase
 * changelog, with the ambient transaction taken away.
 * <p>
 * Where the schema is concerned this is the only kind of test worth writing here, and
 * the branch has the scar to prove it: the {@code MAIL_REMOTE_ID} NOT NULL constraint
 * reached a running server past 724 green tests, because every one of them mocked the
 * DAO and a mock has no constraints. The three schema facts this slice depends on —
 * {@code ATTACHMENT_REMOTE_ID} accepting a null, the two new columns existing, and the
 * orphan table existing with a working sequence behind it — are all facts about a real
 * database and none of them can be asserted anywhere else.
 * <p>
 * The session boundaries are production's, via {@link Propagation#NOT_SUPPORTED},
 * which is what makes the second assertion of each test meaningful: this storage has
 * no {@code @Transactional} anywhere, so every DAO call here commits alone and every
 * entity is detached by the time the next one runs — the exact condition under which
 * writing an attachment by mutating the draft's collection and re-saving it fails.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxDraftAttachmentStorageTest {

  private static final String USERNAME = "alice";

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @Autowired
  private EmailOrphanFileDAO  emailOrphanFileDAO;

  @MockBean
  private CategoryLinkService categoryLinkService;

  @MockBean
  private FileService         fileService;

  @MockBean
  private UploadService       uploadService;

  private long                nextFileId = 100L;

  /**
   * The minimal Spring slice: the mail entities and their repositories, over Boot's
   * auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * The file service hands back a fresh id for every write, as the real one does — and
   * the orphan markers are cleared between tests.
   * <p>
   * Clearing is not tidiness, it is a consequence of the rig: taking the ambient
   * transaction away to get production's session boundaries also takes away the
   * rollback {@code @DataJpaTest} normally does between tests, so everything written
   * here is still there for the next one. Without this, a test asserting on the whole
   * marker table reads the previous tests' markers as well — which is exactly what it
   * did, and it is worth spelling out because the next assertion added on a table
   * would hit the same thing.
   *
   * @throws Exception when the stubbing plumbing misbehaves
   */
  @BeforeEach
  void stubFileService() throws Exception {
    emailOrphanFileDAO.deleteAll();
    when(fileService.writeFile(any())).thenAnswer(invocation -> {
      FileItem written = invocation.getArgument(0);
      FileInfo info = written.getFileInfo();
      info.setId(nextFileId++);
      return written;
    });
  }

  /**
   * A file attached to a draft: the row is written, it carries a file id and a size,
   * and — the part the schema had to be changed for — NO MIME part path, because a
   * file that has never been inside a message has none.
   * <p>
   * Red before changeset 1.0.0-44 with a NOT NULL violation, which is the same shape
   * as the defect that shipped: a column whose constraint was right for every row that
   * had ever existed and wrong for the first locally authored one.
   */
  @Test
  void aFileAttachedToADraftIsStoredWithNoMimePartPath() {
    emailBoxStorage.saveDraft(draft("draft-attach", 1L, "with a file"));

    EmailAttachment stored = emailBoxStorage.addDraftAttachment(USERNAME, "draft-attach", upload("report.pdf"), "report.pdf",
                                                               "application/pdf");

    assertNotNull(stored, "a NOT NULL on the MIME part path is what used to stop this");
    assertNotNull(stored.getId());
    assertNull(stored.getAttachmentRemoteId(), "a file that was never inside a message has no part path");
    assertNotNull(stored.getFileId(), "the bytes live in the file service and the row is what points at them");
    assertEquals(Long.valueOf("hello".length()), stored.getSize());
    assertEquals("report.pdf", stored.getName());
    assertEquals(MailFolder.DRAFTS, stored.getFolder());
  }

  /**
   * The draft is read back WITH its file — which is the whole of "an attachment
   * survives a restart", since a resumed composer is a read of this row and nothing
   * else.
   * <p>
   * The read goes through the ordinary draft read rather than a special one, so what
   * is asserted here is what the composer actually receives.
   */
  @Test
  void aDraftIsReadBackWithItsFiles() {
    emailBoxStorage.saveDraft(draft("draft-resume", 1L, "see attached"));
    emailBoxStorage.addDraftAttachment(USERNAME, "draft-resume", upload("notes.txt"), "notes.txt", "text/plain");

    Email resumed = emailBoxStorage.getDraftByLocalId(USERNAME, "draft-resume");

    assertNotNull(resumed);
    assertNotNull(resumed.getContent());
    assertEquals(1, resumed.getContent().getAttachments().size());
    assertEquals("notes.txt", resumed.getContent().getAttachments().get(0).getName());
    assertEquals("see attached", resumed.getContent().getBody(), "the words are not disturbed by the file");
  }

  /**
   * Attaching and detaching each STEP THE REVISION and mark the draft as edited.
   * <p>
   * This is the assertion that matters most in the slice, and it is asserted here
   * rather than described anywhere: a draft already uploaded to the mail server skips
   * its next upload when nothing has changed, so a file attached without stepping the
   * revision would be accepted by a synced draft and then never sent — with the
   * composer, the chip and the row all looking correct. The design plan named this as
   * the most likely bug in the feature.
   */
  @Test
  void attachingAndDetachingCountAsEdits() {
    emailBoxStorage.saveDraft(draft("draft-edited", 1L, "text"));
    emailBoxStorage.markDraftUploaded(USERNAME, "draft-edited", 4242L, 1L);
    assertEquals(DraftState.SYNCED,
                 emailBoxStorage.getDraftByLocalId(USERNAME, "draft-edited").getDraftState(),
                 "the row has to start SYNCED or this test proves nothing");

    EmailAttachment attached = emailBoxStorage.addDraftAttachment(USERNAME, "draft-edited", upload("a.pdf"), "a.pdf",
                                                                 "application/pdf");
    Email afterAttach = emailBoxStorage.getDraftByLocalId(USERNAME, "draft-edited");
    assertEquals(Long.valueOf(2L), afterAttach.getDraftRevision(), "attaching is an edit");
    assertEquals(DraftState.DIRTY, afterAttach.getDraftState(), "a synced draft that has taken a file is no longer synced");

    assertTrue(emailBoxStorage.removeDraftAttachment(USERNAME, "draft-edited", attached.getId()));
    Email afterDetach = emailBoxStorage.getDraftByLocalId(USERNAME, "draft-edited");
    assertEquals(Long.valueOf(3L), afterDetach.getDraftRevision(), "detaching is an edit too");
    assertTrue(afterDetach.getContent().getAttachments().isEmpty());
  }

  /**
   * A draft that has never been uploaded stays {@link DraftState#LOCAL_ONLY} when a
   * file is attached, rather than being moved to DIRTY.
   * <p>
   * The distinction is not bookkeeping: LOCAL_ONLY means "no copy has ever been up
   * there", which is still true, and it is what the composer reads to tell the user
   * their words live only here. Moving it to DIRTY would also make the composer
   * announce that a server copy had been removed elsewhere, which never happened.
   */
  @Test
  void attachingToANeverUploadedDraftLeavesItLocalOnly() {
    emailBoxStorage.saveDraft(draft("draft-local", 1L, "text"));

    emailBoxStorage.addDraftAttachment(USERNAME, "draft-local", upload("a.pdf"), "a.pdf", "application/pdf");

    assertEquals(DraftState.LOCAL_ONLY, emailBoxStorage.getDraftByLocalId(USERNAME, "draft-local").getDraftState());
  }

  /**
   * Detaching records the file as unreferenced rather than deleting it, and the
   * ownership check is the lookup: another user's attachment id simply is not found.
   */
  @Test
  void detachingRecordsTheFileAndRefusesSomebodyElsesRow() {
    emailBoxStorage.saveDraft(draft("draft-detach", 1L, "text"));
    EmailAttachment attached = emailBoxStorage.addDraftAttachment(USERNAME, "draft-detach", upload("a.pdf"), "a.pdf",
                                                                 "application/pdf");

    assertFalse(emailBoxStorage.removeDraftAttachment("mallory", "draft-detach", attached.getId()),
                "the lookup IS the ownership check, so somebody else's id is simply not found");
    assertTrue(emailBoxStorage.removeDraftAttachment(USERNAME, "draft-detach", attached.getId()));

    assertEquals(List.of(attached.getFileId()),
                 emailOrphanFileDAO.findAll().stream().map(EmailOrphanFileEntity::getFileId).toList());
  }

  /**
   * Deleting a draft records its files BEFORE the rows go — the case the orphan table
   * exists for, and the one nothing in Java would otherwise witness.
   * <p>
   * {@code deleteEmailsByIds} is a bulk JPQL DELETE, so the attachment rows disappear
   * through the database's own ON DELETE CASCADE with no callback and no loaded
   * instance anywhere. This asserts both halves: the rows are really gone, and the
   * file was really written down. Before the recording, the file id was simply
   * unknowable from that point on.
   */
  @Test
  void deletingADraftWritesItsFilesDownFirst() {
    Email saved = emailBoxStorage.saveDraft(draft("draft-deleted", 1L, "text"));
    EmailAttachment attached = emailBoxStorage.addDraftAttachment(USERNAME, "draft-deleted", upload("a.pdf"), "a.pdf",
                                                                 "application/pdf");

    emailBoxStorage.deleteEmailsByIds(List.of(saved.getId()));

    assertNull(emailBoxStorage.getDraftByLocalId(USERNAME, "draft-deleted"), "the row and its attachment go together");
    List<EmailOrphanFileEntity> markers = emailOrphanFileDAO.findAll();
    assertEquals(1, markers.size(), "the file id had to be read before the bulk delete or it could never be known again");
    assertEquals(attached.getFileId(), markers.get(0).getFileId());
    assertEquals(USERNAME, markers.get(0).getUserId());
  }

  /**
   * A SENT draft's files — all of them — are written down before its rows go, which is
   * what makes the send path's cleanup safe rather than a leak.
   * <p>
   * Sending a draft ends with the same row delete a discard ends with, so this asserts
   * what that delete really does against a real table: every file id read first, the
   * attachment rows then gone, and nothing left to find them by. The existing
   * single-file case does not cover it — the failure to catch is a loop that records
   * only what it happened to be holding, and one file cannot tell a loop from a
   * variable.
   * <p>
   * Deliberately at this level and not on a mock: the attachment rows disappear through
   * the database's own ON DELETE CASCADE with no callback and no loaded instance, so
   * "were they really gone, and was the marker really written first" is a question only
   * a real table can be asked.
   */
  @Test
  void aSentDraftsFilesAreAllRecordedBeforeItsRowsGo() {
    Email saved = emailBoxStorage.saveDraft(draft("draft-sent", 1L, "here they are"));
    EmailAttachment first = emailBoxStorage.addDraftAttachment(USERNAME, "draft-sent", upload("one.pdf"), "one.pdf",
                                                              "application/pdf");
    EmailAttachment second = emailBoxStorage.addDraftAttachment(USERNAME, "draft-sent", upload("two.pdf"), "two.pdf",
                                                               "application/pdf");
    assertEquals(2, emailBoxStorage.getDraftAttachments(USERNAME, "draft-sent").size());

    emailBoxStorage.deleteEmailsByIds(List.of(saved.getId()));

    assertNull(emailBoxStorage.getDraftByLocalId(USERNAME, "draft-sent"));
    assertTrue(emailBoxStorage.getDraftAttachments(USERNAME, "draft-sent").isEmpty(), "the attachment rows went with the row");
    assertEquals(List.of(first.getFileId(), second.getFileId()),
                 emailOrphanFileDAO.findAll().stream().map(EmailOrphanFileEntity::getFileId).sorted().toList(),
                 "every file, not just the last one the loop was holding");
  }

  /**
   * A file that is still there answers as such, and one that has been deleted does
   * not — the cheap check an upload and a send are both gated on.
   * <p>
   * The deleted case is the one worth pinning: the file service keeps the metadata row
   * and flips a flag rather than removing it, so "there is a FileInfo" and "there is a
   * file" are different questions, and answering the first would let a draft be
   * uploaded without a file it shows.
   */
  @Test
  void aDeletedFileDoesNotCountAsAReadableAttachment() {
    when(fileService.getFileInfo(500L)).thenReturn(new FileInfo(500L, "a.pdf", "application/pdf", "emailConnector", 5L,
                                                               new Date(), null, null, false));
    when(fileService.getFileInfo(501L)).thenReturn(new FileInfo(501L, "b.pdf", "application/pdf", "emailConnector", 5L,
                                                               new Date(), null, null, true));

    assertTrue(emailBoxStorage.attachmentFileExists(500L));
    assertFalse(emailBoxStorage.attachmentFileExists(501L), "the file service keeps the row and flips a flag");
    assertFalse(emailBoxStorage.attachmentFileExists(502L), "and a file that was never there is not there either");
    assertFalse(emailBoxStorage.attachmentFileExists(null));
  }

  /**
   * Recording the same file twice leaves one marker, without the write failing.
   * <p>
   * Two cleanups over overlapping rows is an ordinary race here, and a constraint
   * violation would abort the surrounding statement rather than the one row — losing
   * a whole cleanup because one file had already been recorded.
   */
  @Test
  void recordingAFileTwiceLeavesOneMarker() {
    emailBoxStorage.recordOrphanFiles(List.of(7L, 8L), USERNAME);
    emailBoxStorage.recordOrphanFiles(List.of(7L, 9L), USERNAME);

    assertEquals(List.of(7L, 8L, 9L),
                 emailOrphanFileDAO.findAll().stream().map(EmailOrphanFileEntity::getFileId).sorted().toList());
  }

  /**
   * An upload that is gone attaches nothing and does not disturb the draft. Expired,
   * already consumed, or never there: the user's words are stored either way, and only
   * their file is not.
   */
  @Test
  void anUploadThatIsGoneAttachesNothing() {
    emailBoxStorage.saveDraft(draft("draft-noupload", 1L, "text"));

    assertNull(emailBoxStorage.addDraftAttachment(USERNAME, "draft-noupload", "vanished", "a.pdf", "application/pdf"));

    Email draft = emailBoxStorage.getDraftByLocalId(USERNAME, "draft-noupload");
    assertEquals("text", draft.getContent().getBody());
    assertEquals(Long.valueOf(1L), draft.getDraftRevision(), "nothing was attached, so nothing was edited");
  }

  /**
   * A part of a draft's SERVER copy, brought over: the row gains a file and a size,
   * and LOSES the MIME part path it used to be addressed by.
   * <p>
   * The path going is the assertion worth having. It named a part of one particular
   * message, and the push that follows replaces that message and deletes the old one —
   * so a path kept here would, on the next read, be resolved against a DIFFERENT
   * message and answer with whatever happens to sit at that index. An address that
   * still looks valid and names something else is the failure this schema's own
   * folder-scoping commit is about; a row with no address names nothing, which is
   * honest.
   * <p>
   * The draft's revision is asserted UNCHANGED, and that is the other half. Bringing
   * bytes over says nothing new about what the draft contains, so it must not look
   * like an edit: a stepped revision here would make the composer's next autosave
   * arrive stale and be dropped, for a write the user never made.
   */
  @Test
  void bringingAServerPartOverGivesTheRowItsOwnFileAndDropsTheAddress() {
    Email draft = emailBoxStorage.saveDraft(importedDraft("draft-imported", "from-the-phone.pdf"));
    EmailAttachment before = emailBoxStorage.getDraftAttachments(USERNAME, "draft-imported").get(0);
    assertNull(before.getFileId(), "the row has to start as an address or this test proves nothing");
    assertEquals("2", before.getAttachmentRemoteId());

    EmailAttachment broughtOver = emailBoxStorage.materializeDraftAttachment(USERNAME, "draft-imported", before.getId(),
                                                                            "phone bytes".getBytes());

    assertNotNull(broughtOver);
    assertNotNull(broughtOver.getFileId(), "the bytes are on this side now");
    assertEquals(Long.valueOf("phone bytes".length()), broughtOver.getSize());
    assertNull(broughtOver.getAttachmentRemoteId(), "the address pointed into a message the next push destroys");
    assertEquals("from-the-phone.pdf", broughtOver.getName());
    EmailAttachment reread = emailBoxStorage.getDraftAttachments(USERNAME, "draft-imported").get(0);
    assertEquals(broughtOver.getFileId(), reread.getFileId(), "and it was really written, not only answered");
    assertNull(reread.getAttachmentRemoteId());
    assertEquals(draft.getDraftRevision(),
                 emailBoxStorage.getDraftByLocalId(USERNAME, "draft-imported").getDraftRevision(),
                 "bringing bytes over is bookkeeping, not an edit the composer has to catch up with");
  }

  /**
   * Doing it twice writes one file, not two — so a materialization that fails part way
   * through a draft costs only the parts it did not reach when it is retried.
   */
  @Test
  void bringingAPartOverTwiceStoresItOnce() {
    emailBoxStorage.saveDraft(importedDraft("draft-twice", "twice.pdf"));
    long attachmentId = emailBoxStorage.getDraftAttachments(USERNAME, "draft-twice").get(0).getId();

    EmailAttachment first = emailBoxStorage.materializeDraftAttachment(USERNAME, "draft-twice", attachmentId,
                                                                      "phone bytes".getBytes());
    EmailAttachment second = emailBoxStorage.materializeDraftAttachment(USERNAME, "draft-twice", attachmentId,
                                                                       "phone bytes".getBytes());

    assertEquals(first.getFileId(), second.getFileId(), "an attachment that already has its bytes is answered, not re-written");
  }

  /**
   * Somebody else's draft is not a draft this user can bring parts over from — the
   * lookup IS the ownership check here, exactly as it is for a download and a detach.
   */
  @Test
  void aPartOfSomebodyElsesDraftIsNotBroughtOver() {
    emailBoxStorage.saveDraft(importedDraft("draft-mine", "mine.pdf"));
    long attachmentId = emailBoxStorage.getDraftAttachments(USERNAME, "draft-mine").get(0).getId();

    assertNull(emailBoxStorage.materializeDraftAttachment("mallory", "draft-mine", attachmentId, "phone bytes".getBytes()));
    assertNull(emailBoxStorage.materializeDraftAttachment(USERNAME, "draft-someone-elses", attachmentId,
                                                          "phone bytes".getBytes()));
    assertNull(emailBoxStorage.getDraftAttachments(USERNAME, "draft-mine").get(0).getFileId(), "and nothing was written");
  }

  /**
   * A file taken off a message being FORWARDED becomes the draft's own file: bytes in
   * the file store, a size, no MIME part path — and the draft's revision stepped.
   * <p>
   * The stepped revision is the assertion that matters, and it is the same one attaching
   * a file needs for the same reason: a draft already uploaded to the mail server skips
   * its next upload when nothing has changed, so a forward that did not step it would be
   * accepted by a synced draft and then sent without the files the composer is showing.
   * A materialization deliberately does NOT step it — that is bookkeeping about where
   * existing content lives — and this is the opposite case: new files on the draft.
   * <p>
   * The absent part path is the other half. It would name a part of the message being
   * forwarded, and a draft's rows are read against the draft's OWN copy on the server —
   * so a path kept here would resolve, on the next read, to whatever sits at that index
   * of a completely different message.
   */
  @Test
  void aForwardedFileBecomesTheDraftsOwnFileAndCountsAsAnEdit() {
    emailBoxStorage.saveDraft(draft("draft-forward", 1L, "fyi"));
    emailBoxStorage.markDraftUploaded(USERNAME, "draft-forward", 4242L, 1L);
    assertEquals(DraftState.SYNCED,
                 emailBoxStorage.getDraftByLocalId(USERNAME, "draft-forward").getDraftState(),
                 "the row has to start SYNCED or this test proves nothing");

    EmailAttachment carried = emailBoxStorage.copyDraftAttachment(USERNAME, "draft-forward", "contract.pdf", "application/pdf",
                                                                  "the contract".getBytes());

    assertNotNull(carried);
    assertNotNull(carried.getFileId(), "the bytes are the draft's own now, not the forwarded message's");
    assertEquals(Long.valueOf("the contract".length()), carried.getSize());
    assertNull(carried.getAttachmentRemoteId(), "a path here would be read against the draft's own copy, not the original");
    assertEquals("contract.pdf", carried.getName());
    Email afterForward = emailBoxStorage.getDraftByLocalId(USERNAME, "draft-forward");
    assertEquals(Long.valueOf(2L), afterForward.getDraftRevision(), "carrying a file onto a forward is an edit");
    assertEquals(DraftState.DIRTY, afterForward.getDraftState(), "a synced draft that has taken a file is no longer synced");
    assertEquals(1, afterForward.getContent().getAttachments().size(), "and it was really written, not only answered");
  }

  /**
   * Forwarding the same message twice produces two independent files, and removing one
   * leaves the other whole.
   * <p>
   * The tempting alternative is to point the second row at the file the first already
   * stored. It would corrupt both: taking the chip off one draft records that file as
   * unreferenced, and a later sweep would free it under the draft that still shows it —
   * a forward that arrives with an attachment nothing can read. The cost of not sharing
   * is one copy of the bytes per forward, which is the right side to be wrong on.
   */
  @Test
  void forwardingTheSameFileTwiceGivesEachForwardItsOwnCopy() {
    emailBoxStorage.saveDraft(draft("draft-first", 1L, "fyi"));
    emailBoxStorage.saveDraft(draft("draft-second", 1L, "fyi too"));

    EmailAttachment first = emailBoxStorage.copyDraftAttachment(USERNAME, "draft-first", "contract.pdf", "application/pdf",
                                                                "the contract".getBytes());
    EmailAttachment second = emailBoxStorage.copyDraftAttachment(USERNAME, "draft-second", "contract.pdf", "application/pdf",
                                                                 "the contract".getBytes());

    assertNotEquals(first.getFileId(), second.getFileId(), "one file per forward: sharing would let either free the other's");
    assertTrue(emailBoxStorage.removeDraftAttachment(USERNAME, "draft-first", first.getId()));
    assertEquals(List.of(first.getFileId()),
                 emailOrphanFileDAO.findAll().stream().map(EmailOrphanFileEntity::getFileId).toList(),
                 "only the copy that was removed is unreferenced");
    List<EmailAttachment> survivors = emailBoxStorage.getDraftAttachments(USERNAME, "draft-second");
    assertEquals(1, survivors.size(), "the other forward still carries its file");
    assertEquals(second.getFileId(), survivors.get(0).getFileId());
  }

  /**
   * Somebody else's draft is not a draft this user can forward onto, and neither is one
   * that does not exist — the lookup IS the ownership check, exactly as it is everywhere
   * else a draft's files are touched.
   */
  @Test
  void aForwardOntoADraftThatIsNotYoursCarriesNothing() {
    emailBoxStorage.saveDraft(draft("draft-mine-forward", 1L, "fyi"));

    assertNull(emailBoxStorage.copyDraftAttachment("mallory", "draft-mine-forward", "contract.pdf", "application/pdf",
                                                   "the contract".getBytes()));
    assertNull(emailBoxStorage.copyDraftAttachment(USERNAME, "draft-that-is-not-there", "contract.pdf", "application/pdf",
                                                   "the contract".getBytes()));
    assertNull(emailBoxStorage.copyDraftAttachment(USERNAME, "draft-mine-forward", "contract.pdf", "application/pdf", null),
               "and no bytes is nothing to carry");

    Email untouched = emailBoxStorage.getDraftByLocalId(USERNAME, "draft-mine-forward");
    assertTrue(untouched.getContent().getAttachments().isEmpty());
    assertEquals(Long.valueOf(1L), untouched.getDraftRevision(), "nothing was carried, so nothing was edited");
  }

  /**
   * A draft as the sync imports it from another client: a row with a UID of its own and
   * one attachment that is a MIME part path into the message at that UID, with no
   * bytes on this side.
   * <p>
   * Written through {@code createEmail} rather than assembled by hand, because that is
   * the call the import really makes and the attachment rows only exist through its
   * cascade.
   *
   * @param draftLocalId the local id the import minted
   * @param fileName the file the other client attached
   * @return the draft as the import writes it
   */
  private Email importedDraft(String draftLocalId, String fileName) {
    Email imported = draft(draftLocalId, 1L, "see attached");
    imported.setMailRemoteId(4242L);
    imported.setDraftState(DraftState.SYNCED);
    imported.getContent()
            .setAttachments(List.of(new EmailAttachment(null, 4242L, "2", fileName, "application/pdf", null, MailFolder.DRAFTS,
                                                        null, null)));
    return imported;
  }

  /**
   * A commons upload backed by a real temporary file, registered with the mocked
   * upload service under a fresh id.
   *
   * @param fileName the name the upload carries
   * @return the upload id to attach by
   */
  private String upload(String fileName) {
    try {
      File temporaryFile = Files.createTempFile("draft-attachment", ".bin").toFile();
      temporaryFile.deleteOnExit();
      try (FileOutputStream out = new FileOutputStream(temporaryFile)) {
        out.write("hello".getBytes());
      }
      String uploadId = "upload-" + fileName;
      UploadResource uploadResource = new UploadResource(uploadId, fileName);
      uploadResource.setStoreLocation(temporaryFile.getAbsolutePath());
      when(uploadService.getUploadResource(uploadId)).thenReturn(uploadResource);
      return uploadId;
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  /**
   * A draft as the composer saves it.
   *
   * @param draftLocalId the composer's handle on the draft
   * @param revision the local edit counter this save carries
   * @param body the text the user has typed so far
   * @return the draft to write
   */
  private Email draft(String draftLocalId, long revision, String body) {
    Date now = new Date();
    Email draft = new Email();
    draft.setUserId(USERNAME);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setMailHeaderId("<" + draftLocalId + "@example.org>");
    draft.setSender(new EmailSender("Alice", "alice@example.org", null, null));
    draft.setSubject("Half a sentence");
    draft.setContent(new EmailContent(body, null, null));
    draft.setReceivedDate(now);
    draft.setRead(true);
    draft.setDraftLocalId(draftLocalId);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft.setDraftRevision(revision);
    draft.setDraftUpdatedDate(now);
    return draft;
  }
}
