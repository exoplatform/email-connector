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

import java.util.Date;
import java.util.List;

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

import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.MailFolder;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The draft WRITE paths against a real database, with no ambient transaction —
 * the one condition under which the defect they carried is visible at all.
 * <p>
 * Same rig as {@link org.exoplatform.emailConnector.dao.EmailBoxDraftDAOTest}
 * (in-memory HSQLDB on the SHIPPED Liquibase changelog, {@code ddl-auto=none}),
 * with two differences that are the whole point of this class.
 * <p>
 * First, it drives {@link EmailBoxStorage} rather than the DAO, because the
 * defect is not in either the query or the row: both are correct on their own.
 * It is in what the mapper is handed afterwards.
 * <p>
 * Second, and this is the part a test is easy to get wrong here:
 * {@code @DataJpaTest} wraps each test in a transaction of its own, which would
 * keep a session open for the whole method and make every lazy collection load
 * happily — the exact opposite of production, where this storage has no
 * {@code @Transactional} anywhere and each DAO call runs and commits alone
 * ({@code spring.jpa.open-in-view=false} on top). {@link Propagation#NOT_SUPPORTED}
 * takes that ambient transaction away, so the sequence here is the sequence a
 * REST request actually performs: fetch, commit, mutate a now-detached entity,
 * save in a second transaction, map after it has closed.
 * <p>
 * Under a mocked DAO none of this exists — {@code save} returns whatever the stub
 * was told to return, attachments and all — which is how three defects on this
 * branch reached a running server through a green suite.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import(EmailBoxStorage.class)
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
@Transactional(propagation = Propagation.NOT_SUPPORTED)
public class EmailBoxDraftStorageTest {

  private static final String USERNAME = "alice";

  @Autowired
  private EmailBoxStorage     emailBoxStorage;

  @MockBean
  private CategoryLinkService categoryLinkService;

  // The storage now writes attachment bytes through the platform's file service. Mocked
  // rather than exercised: nothing in this class attaches a file, and an unmocked bean
  // would simply fail to start the context.
  @MockBean
  private FileService         fileService;

  @MockBean
  private UploadService       uploadService;

  /**
   * The minimal Spring slice: the mail entities and their repositories, over
   * Boot's auto-configured in-memory database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * The composer autosaving twice, which is what every draft that gets written at
   * all does: the first save creates the row, and every save after it updates the
   * same one.
   * <p>
   * Against the code before this test, the second save threw {@code failed to
   * lazily initialize a collection of role: EmailBoxEntity.attachments - no
   * Session} and the request came back 500 — silently, since the composer keeps
   * showing the text either way, so the user is told their words are safe while
   * nothing after the first save is stored. The first save survived only because
   * it goes through {@code createEmail}, which saves an entity it built itself.
   */
  @Test
  void aSecondSaveOfTheSameDraftComesBackReadable() {
    Email first = emailBoxStorage.saveDraft(draft("draft-twice", 1L, "the first half"));
    assertNotNull(first);
    assertNotNull(first.getId());
    assertEquals("the first half", first.getContent().getBody());

    Email second = emailBoxStorage.saveDraft(draft("draft-twice", 2L, "and the sentence finished"));

    assertNotNull(second, "the second save must come back, not blow up on its way out of the mapper");
    assertEquals(first.getId(), second.getId(), "an autosave updates the draft's row, it does not create another");
    assertEquals("and the sentence finished", second.getContent().getBody());
    assertEquals(Long.valueOf(2L), second.getDraftRevision());
    // Drafts carry no attachments today, but the returned DTO must be able to say so:
    // an empty lazy collection throws on being touched exactly as a full one does, and
    // touching it is what the mapper does.
    assertNotNull(second.getContent().getAttachments());
    assertEquals(0, second.getContent().getAttachments().size());
    assertEquals(1, second.getTo().size());
    assertEquals("bob@example.org", second.getTo().get(0).getAddress());

    // And it is really in the database, not just in the answer.
    Email reloaded = emailBoxStorage.getDraftByLocalId(USERNAME, "draft-twice");
    assertEquals("and the sentence finished", reloaded.getContent().getBody());
    assertEquals(Long.valueOf(2L), reloaded.getDraftRevision());
  }

  /**
   * The revision guard, which the fix must leave exactly where it was: a save
   * carrying a revision the row has already reached is text the user has typed
   * past, so it is dropped and the row as it stands is what comes back.
   */
  @Test
  void anOutOfOrderSaveIsDroppedAndTheStoredRowComesBack() {
    emailBoxStorage.saveDraft(draft("draft-late", 1L, "what the user typed"));
    emailBoxStorage.saveDraft(draft("draft-late", 2L, "what the user typed next"));

    Email late = emailBoxStorage.saveDraft(draft("draft-late", 2L, "a slower request from a moment ago"));

    assertEquals("what the user typed next", late.getContent().getBody(), "a late save must not revert the newest text");
    assertEquals(Long.valueOf(2L), late.getDraftRevision());
    assertEquals("what the user typed next", emailBoxStorage.getDraftByLocalId(USERNAME, "draft-late").getContent().getBody());
  }

  /**
   * The upload bookkeeping, the second of the three draft writers: it takes the
   * same fetch-mutate-save shape and so had the same defect, one server round trip
   * further along where it would have been read as a failed push.
   */
  @Test
  void recordingAnUploadComesBackReadable() {
    emailBoxStorage.saveDraft(draft("draft-uploaded", 1L, "on its way up"));

    Email uploaded = emailBoxStorage.markDraftUploaded(USERNAME, "draft-uploaded", 4242L, 1L);

    assertNotNull(uploaded);
    assertEquals(Long.valueOf(4242L), uploaded.getMailRemoteId());
    assertEquals(DraftState.SYNCED, uploaded.getDraftState(), "the uploaded revision is the stored one, so the row is synced");
    assertNotNull(uploaded.getContent().getAttachments());

    Email reloaded = emailBoxStorage.getDraftByLocalId(USERNAME, "draft-uploaded");
    assertEquals(Long.valueOf(4242L), reloaded.getMailRemoteId());
    assertEquals(DraftState.SYNCED, reloaded.getDraftState());
  }

  /**
   * A UID written for a revision the row has since moved past: the number is
   * stored anyway — it is the only record of where that copy is, and the next push
   * removes it by that number — while the state stays put so the push still runs.
   */
  @Test
  void recordingAnUploadOfAnOlderRevisionKeepsTheUidAndTheState() {
    emailBoxStorage.saveDraft(draft("draft-moved", 1L, "the text that went up"));
    emailBoxStorage.saveDraft(draft("draft-moved", 2L, "the text typed while it went up"));

    Email uploaded = emailBoxStorage.markDraftUploaded(USERNAME, "draft-moved", 77L, 1L);

    assertEquals(Long.valueOf(77L), uploaded.getMailRemoteId());
    assertEquals(DraftState.LOCAL_ONLY, uploaded.getDraftState(), "a row that has moved on is not synced by an older upload");
  }

  /**
   * The send path's claim on the row, the third draft writer and the third
   * instance of the same shape.
   */
  @Test
  void aStateChangeComesBackReadable() {
    emailBoxStorage.saveDraft(draft("draft-sending", 1L, "about to go"));

    Email claimed = emailBoxStorage.updateDraftState(USERNAME, "draft-sending", DraftState.SENDING);

    assertNotNull(claimed);
    assertEquals(DraftState.SENDING, claimed.getDraftState());
    assertNotNull(claimed.getContent().getAttachments());
    assertEquals("about to go", claimed.getContent().getBody(), "a state change writes no text and must lose none");
    assertEquals(Long.valueOf(1L), claimed.getDraftRevision(), "a state change must not make a later autosave look stale");

    assertEquals(DraftState.SENDING, emailBoxStorage.getDraftByLocalId(USERNAME, "draft-sending").getDraftState());
  }

  /**
   * Neither writer invents a draft: asked about a local id nobody has a row under,
   * both say so rather than creating one.
   */
  @Test
  void writingToADraftThatIsGoneSaysSo() {
    assertNull(emailBoxStorage.markDraftUploaded(USERNAME, "draft-never-existed", 1L, 1L));
    assertNull(emailBoxStorage.updateDraftState(USERNAME, "draft-never-existed", DraftState.SENDING));
  }

  /**
   * A save replaces the body, so it has to replace what the row says about that
   * body. The update path is the one that does not go through the mapper — it
   * mutates the loaded row column by column — and the format was not one of the
   * columns it wrote, so a draft's very first save was the only one that ever
   * answered the question.
   * <p>
   * The case is the ordinary one, not a contrived one: a draft written in plain text
   * elsewhere is imported as plain text, and the moment its author resumes it here
   * they are typing in the rich editor, so the next save is HTML. Left unwritten,
   * the row goes on claiming plain text — and the reader takes that literally now,
   * having stopped guessing from the characters, so it escapes the markup and shows
   * the user their own tags.
   */
  @Test
  void aSaveWritesTheFormatOfTheBodyItWrites() {
    Email plain = emailBoxStorage.saveDraft(draft("draft-html", 1L, "the first half, typed on a phone"));
    assertFalse(plain.getContent().isHtml());

    Email edited = emailBoxStorage.saveDraft(htmlDraft("draft-html", 2L, "<div dir=\"ltr\">and the sentence <b>finished</b></div>"));

    assertTrue(edited.getContent().isHtml(), "the update path writes the format alongside the body it describes");
    assertTrue(emailBoxStorage.getDraftByLocalId(USERNAME, "draft-html").getContent().isHtml(),
               "and the row in the database says so too, not just the answer");
  }

  /**
   * A draft as the composer saves it: the user's text and recipients, filed in
   * DRAFTS, with no copy on the server yet.
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
    draft.setTo(List.of(new EmailRecipient("Bob", "bob@example.org", null, false)));
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

  /**
   * The same draft, declaring its body HTML — which is what the composer actually
   * sends, its text coming from the rich editor.
   *
   * @param draftLocalId the composer's handle on the draft
   * @param revision the local edit counter this save carries
   * @param body the markup the user has typed so far
   * @return the draft to write
   */
  private Email htmlDraft(String draftLocalId, long revision, String body) {
    Email draft = draft(draftLocalId, revision, body);
    draft.getContent().setHtml(true);
    return draft;
  }
}
