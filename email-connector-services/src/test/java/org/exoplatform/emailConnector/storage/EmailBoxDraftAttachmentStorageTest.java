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
