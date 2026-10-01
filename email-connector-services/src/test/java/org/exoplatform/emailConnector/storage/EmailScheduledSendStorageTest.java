/**
 * Copyright (C) 2026 eXo Platform SAS
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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailScheduledSend;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ScheduledSendError;
import org.exoplatform.emailConnector.model.ScheduledSendStatus;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The storage side of scheduled send (EXO-90434) over the shipped changelog: the
 * schedule storage's mapping and transitions as the service calls them, and the three
 * places the mailbox storage keeps a scheduled draft out of the Drafts folder -- its
 * list, its count -- while still reading it for the "Scheduled" view.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import({ EmailBoxStorage.class, EmailScheduledSendStorage.class })
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
public class EmailScheduledSendStorageTest {

  private static final String       USER = "alice";

  private static final String       NODE = "node-a";

  private static final Date         NOW  = new Date(1_800_000_000_000L);

  @Autowired
  private EmailScheduledSendStorage storage;

  @Autowired
  private EmailBoxStorage           emailBoxStorage;

  @Autowired
  private EmailBoxDAO               emailBoxDAO;

  @MockitoBean
  private CategoryLinkService       categoryLinkService;

  @MockitoBean
  private FileService               fileService;

  @MockitoBean
  private UploadService             uploadService;

  /**
   * The minimal Spring slice over the changelog-built database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * A scheduled draft is out of the Drafts list and the Drafts count, a plain draft
   * stays in both, and the "Scheduled" view reads the scheduled one by id with its
   * recipients and body.
   */
  @Test
  void aScheduledDraftLeavesTheDraftsFolderForTheScheduledView() {
    EmailBoxEntity plain = draft("plain");
    EmailBoxEntity scheduled = draft("scheduled");
    storage.create(row(scheduled));

    assertEquals(List.of("plain"),
                 emailBoxStorage.getUnscheduledDrafts(USER).stream().map(Email::getDraftLocalId).toList());
    assertEquals(2, emailBoxStorage.getEmails(USER, MailFolder.DRAFTS).size(), "every other read still sees both");
    assertEquals(1, emailBoxStorage.getFolderMessageCounts(USER).get(MailFolder.DRAFTS));

    Map<Long, Email> read = emailBoxStorage.getListedEmailsByIds(USER, List.of(scheduled.getId(), plain.getId()));
    Email scheduledEmail = read.get(scheduled.getId());
    assertNotNull(scheduledEmail);
    assertEquals("bob@example.org", scheduledEmail.getTo().get(0).getAddress());
    assertEquals("<p>at eight</p>", scheduledEmail.getContent().getBody());
    assertTrue(emailBoxStorage.getListedEmailsByIds("mallory", List.of(scheduled.getId())).isEmpty(),
               "another user's ids read nothing");
  }

  /**
   * The "Scheduled" view reads its drafts, attachments included, with no transaction
   * around the call -- as the REST thread does (open-in-view is off): a lazily loaded
   * attachment list would fail there with no session. Committed rows, removed after.
   * And a full pool asks for no due rows without being refused a page of size zero.
   * HSQLDB only: vendor behaviour of the read (Oracle refuses a DISTINCT over CLOBs, which
   * is why the query has none) is not something this suite can show.
   */
  @Test
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  void theScheduledViewReadsItsDraftsWithNoTransactionAround() {
    EmailBoxEntity scheduled = draft("outside-tx", "zoe");
    try {
      Map<Long, Email> read = emailBoxStorage.getListedEmailsByIds("zoe", List.of(scheduled.getId()));
      assertEquals(1, read.size());
      assertEquals("<p>at eight</p>", read.get(scheduled.getId()).getContent().getBody());
      assertTrue(storage.findDueToSend(NOW, 0).isEmpty(), "a full pool asks for nothing, and is not refused");
      assertTrue(storage.findDueToCheck(NOW, 0).isEmpty());
    } finally {
      emailBoxDAO.deleteEmailsByIds(List.of(scheduled.getId()));
    }
  }

  /**
   * The schedules of several drafts come in one read, keyed by draft, the owner's only.
   */
  @Test
  void theSchedulesOfSeveralDraftsAreReadAtOnce() {
    EmailScheduledSend created = storage.create(row(draft("d1")));
    draft("d2");
    Map<String, EmailScheduledSend> byDraft = storage.getByDraftLocalIds(USER, List.of("d1", "d2"));
    assertEquals(1, byDraft.size());
    assertEquals(created.getId(), byDraft.get("d1").getId());
    assertTrue(storage.getByDraftLocalIds("mallory", List.of("d1")).isEmpty(), "another user's handle reads nothing");
    assertTrue(storage.getByDraftLocalIds(USER, List.of()).isEmpty());
  }

  /**
   * The storage speaks the transitions the service needs, each answering whether it
   * landed, and the badge counts the rows needing the owner.
   */
  @Test
  void theTransitionsAnswerWhetherTheyLanded() {
    EmailScheduledSend created = storage.create(row(draft("d1")));
    assertNotNull(created.getId());
    assertTrue(storage.isScheduled(USER, "d1"));
    assertEquals(List.of(created.getId()), storage.findDueToSend(NOW, 10));

    assertTrue(storage.claim(created.getId(), NODE, NOW));
    assertFalse(storage.claim(created.getId(), NODE, NOW));
    assertFalse(storage.cancel(USER, "d1"), "a mail being sent is not cancelled");
    assertTrue(storage.endRun(created.getId(), NODE, NOW, ScheduledSendStatus.FAILED, ScheduledSendError.RECIPIENT_REFUSED, null, NOW));
    EmailScheduledSend failed = storage.get(USER, "d1");
    assertEquals(ScheduledSendStatus.FAILED, failed.getStatus());
    assertEquals("RECIPIENT_REFUSED", failed.getLastError());
    assertArrayEquals(new long[] { 1, 1 }, storage.countListedAndAttention(USER));
    assertEquals(1, storage.countListed(USER));
    assertArrayEquals(new long[] { 0, 0 }, storage.countListedAndAttention("nobody"));

    assertTrue(storage.reschedule(USER, "d1", new Date(NOW.getTime() + 60_000), "Europe/Paris", NOW));
    assertEquals(ScheduledSendStatus.SCHEDULED, storage.get(created.getId()).getStatus());
    assertTrue(storage.cancel(USER, "d1"));
    assertFalse(storage.isScheduled(USER, "d1"));
  }

  /**
   * An edit of a scheduled mail's content takes its row only while it is scheduled or
   * failed: never while it is being sent, once sent, once its sending is uncertain, nor
   * another user's (EXO-90434).
   */
  @Test
  void anEditTakesTheRowOnlyWhileTheMailIsScheduledOrFailed() {
    EmailScheduledSend created = storage.create(row(draft("d1")));
    Date later = new Date(NOW.getTime() + 5000);
    assertTrue(storage.takeForEdit(USER, "d1", later), "scheduled");
    assertEquals(later, storage.get(created.getId()).getUpdatedDate());
    assertEquals(ScheduledSendStatus.SCHEDULED, storage.get(created.getId()).getStatus(), "an edit leaves the state as it was");
    assertFalse(storage.takeForEdit("mallory", "d1", NOW), "another user's handle takes nothing");
    assertFalse(storage.takeForEdit(USER, "nothing", NOW));

    assertTrue(storage.claim(created.getId(), NODE, NOW));
    assertFalse(storage.takeForEdit(USER, "d1", NOW), "being sent");
    assertTrue(storage.endRun(created.getId(), NODE, NOW, ScheduledSendStatus.FAILED, ScheduledSendError.NETWORK, null, NOW));
    assertTrue(storage.takeForEdit(USER, "d1", NOW), "failed");
    assertEquals(ScheduledSendStatus.FAILED, storage.get(created.getId()).getStatus());

    assertTrue(storage.claimNow(USER, "d1", NODE, NOW));
    assertEquals(1, storage.markUncertainOf(NODE, List.of(), NOW));
    assertFalse(storage.takeForEdit(USER, "d1", NOW), "uncertain");

    EmailScheduledSend sent = storage.create(row(draft("d2")));
    assertTrue(storage.claim(sent.getId(), NODE, NOW));
    assertTrue(storage.markSent(sent.getId(), NODE, NOW, NOW));
    assertFalse(storage.takeForEdit(USER, "d2", NOW), "sent");
  }

  /**
   * A mail held for its Undo (EXO-90837) is out of the Drafts list and out of the
   * "Scheduled" view, its badge and the limit's count; only the held-mail scan finds it
   * once due, never the scheduled-mail scan; it is claimed from HELD, once, and never by
   * a claim from SCHEDULED. The statuses go through the real statements on the
   * changelog-built database.
   */
  @Test
  void aHeldMailIsUnlistedAndClaimedOnlyFromHeldOnceDue() {
    EmailScheduledSend held = storage.create(heldRow(draft("held"), new Date(NOW.getTime() - 1000)));
    EmailScheduledSend notYet = storage.create(heldRow(draft("not-yet"), new Date(NOW.getTime() + 10_000)));
    EmailScheduledSend scheduled = storage.create(row(draft("scheduled")));

    assertTrue(emailBoxStorage.getUnscheduledDrafts(USER).isEmpty(), "a held draft is not in Drafts");
    assertEquals(List.of("scheduled"), storage.getListed(USER, 0, 10).stream().map(EmailScheduledSend::getDraftLocalId).toList());
    assertEquals(1, storage.countListed(USER));
    assertArrayEquals(new long[] { 1, 0 }, storage.countListedAndAttention(USER));

    assertEquals(List.of(held.getId()), storage.findDueHeld(NOW, 10));
    assertEquals(List.of(scheduled.getId()), storage.findDueToSend(NOW, 10));
    assertTrue(storage.findDueHeld(NOW, 0).isEmpty());

    assertFalse(storage.claim(held.getId(), NODE, NOW), "a held mail is not claimed as a scheduled one");
    assertFalse(storage.claim(scheduled.getId(), NODE, NOW, ScheduledSendStatus.HELD), "nor a scheduled one as held");
    assertFalse(storage.claim(notYet.getId(), NODE, NOW, ScheduledSendStatus.HELD), "not before its wait is over");
    assertTrue(storage.claim(held.getId(), NODE, NOW, ScheduledSendStatus.HELD));
    assertFalse(storage.claim(held.getId(), NODE, NOW, ScheduledSendStatus.HELD), "once");
    assertEquals(ScheduledSendStatus.SENDING, storage.get(held.getId()).getStatus());
    assertEquals(NODE, storage.get(held.getId()).getClaimedBy());
  }

  /**
   * The Undo (EXO-90837) takes back a mail held, scheduled again after a failure to
   * connect, or failed -- nothing of those went out -- and never one being sent, sent,
   * or that may have gone; nor another user's.
   */
  @Test
  void anUndoTakesBackOnlyAMailThatHasNotStartedToGo() {
    EmailScheduledSend held = storage.create(heldRow(draft("held"), NOW));
    assertFalse(storage.cancelUndoable("mallory", "held"), "another user's handle takes nothing back");
    assertTrue(storage.cancelUndoable(USER, "held"));
    assertNull(storage.get(held.getId()));
    assertFalse(storage.cancelUndoable(USER, "held"), "nothing left to take back");

    EmailScheduledSend sending = storage.create(heldRow(draft("sending"), NOW));
    assertTrue(storage.claim(sending.getId(), NODE, NOW, ScheduledSendStatus.HELD));
    assertFalse(storage.cancelUndoable(USER, "sending"), "being sent");
    assertTrue(storage.endRun(sending.getId(), NODE, NOW, ScheduledSendStatus.SCHEDULED, ScheduledSendError.NETWORK, NOW, NOW));
    assertTrue(storage.cancelUndoable(USER, "sending"), "scheduled again after a failure to connect: nothing went out");

    EmailScheduledSend failed = storage.create(heldRow(draft("failed"), NOW));
    assertTrue(storage.claim(failed.getId(), NODE, NOW, ScheduledSendStatus.HELD));
    assertTrue(storage.endRun(failed.getId(), NODE, NOW, ScheduledSendStatus.FAILED, ScheduledSendError.REFUSED, null, NOW));
    assertTrue(storage.cancelUndoable(USER, "failed"), "refused before anything was accepted");

    EmailScheduledSend uncertain = storage.create(heldRow(draft("uncertain"), NOW));
    assertTrue(storage.claim(uncertain.getId(), NODE, NOW, ScheduledSendStatus.HELD));
    assertEquals(1, storage.markUncertainOf(NODE, List.of(), NOW));
    assertFalse(storage.cancelUndoable(USER, "uncertain"), "may have gone");
    assertTrue(storage.cancel(USER, "uncertain"), "while the Scheduled view's own cancel still may");

    EmailScheduledSend sent = storage.create(heldRow(draft("sent"), NOW));
    assertTrue(storage.claim(sent.getId(), NODE, NOW, ScheduledSendStatus.HELD));
    assertTrue(storage.markSent(sent.getId(), NODE, NOW, NOW));
    assertFalse(storage.cancelUndoable(USER, "sent"), "sent");
  }

  /**
   * The recovery statements work with nothing in flight: the empty list is replaced by
   * a sentinel, since some vendors refuse an empty NOT IN.
   */
  @Test
  void recoveryWorksWithNothingInFlight() {
    EmailScheduledSend created = storage.create(row(draft("d1")));
    assertTrue(storage.claim(created.getId(), NODE, NOW));
    assertEquals(1, storage.markUncertainOf(NODE, List.of(), NOW));
    assertEquals(ScheduledSendStatus.UNCERTAIN, storage.get(created.getId()).getStatus());
    assertEquals(List.of(created.getId()), storage.findDueToCheck(NOW, 10));
    assertEquals(0, storage.markStaleUncertain(NOW, List.of(), NOW));
  }

  /**
   * A due schedule row for a draft.
   *
   * @param draft the draft
   * @return the row, not stored
   */
  private EmailScheduledSend row(EmailBoxEntity draft) {
    EmailScheduledSend row = new EmailScheduledSend();
    row.setEmailId(draft.getId());
    row.setUserId(USER);
    row.setDraftLocalId(draft.getDraftLocalId());
    row.setScheduledDate(new Date(NOW.getTime() - 1000));
    row.setNextAttemptDate(new Date(NOW.getTime() - 1000));
    row.setTimeZone("UTC");
    row.setStatus(ScheduledSendStatus.SCHEDULED);
    row.setCreatedDate(NOW);
    row.setUpdatedDate(NOW);
    return row;
  }

  /**
   * A schedule row of a mail held for its Undo (EXO-90837), as the service writes it:
   * no zone, due at the end of the wait.
   *
   * @param draft the draft
   * @param due the end of the wait
   * @return the row, not stored
   */
  private EmailScheduledSend heldRow(EmailBoxEntity draft, Date due) {
    EmailScheduledSend row = row(draft);
    row.setScheduledDate(due);
    row.setNextAttemptDate(due);
    row.setTimeZone(null);
    row.setStatus(ScheduledSendStatus.HELD);
    return row;
  }

  /**
   * A stored local draft of {@link #USER}.
   *
   * @param draftLocalId its handle
   * @return the stored row
   */
  private EmailBoxEntity draft(String draftLocalId) {
    return draft(draftLocalId, USER);
  }

  /**
   * A stored local draft of a user.
   *
   * @param draftLocalId its handle
   * @param userId its owner
   * @return the stored row
   */
  private EmailBoxEntity draft(String draftLocalId, String userId) {
    EmailBoxEntity draft = new EmailBoxEntity();
    draft.setUserId(userId);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setSender("Alice,alice@example.org");
    draft.setTo("Bob,bob@example.org");
    draft.setSubject("See you tomorrow");
    draft.setBody("<p>at eight</p>");
    draft.setReceivedDate(NOW);
    draft.setRead(true);
    draft.setMailHeaderId("<" + draftLocalId + "@example.org>");
    draft.setDraftLocalId(draftLocalId);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft.setDraftRevision(1L);
    draft.setDraftUpdatedDate(NOW);
    return emailBoxDAO.saveAndFlush(draft);
  }
}
