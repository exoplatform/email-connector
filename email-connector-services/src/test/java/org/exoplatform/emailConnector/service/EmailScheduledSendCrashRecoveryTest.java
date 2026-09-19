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
package org.exoplatform.emailConnector.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.file.services.FileService;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.emailConnector.dao.EmailBoxDAO;
import org.exoplatform.emailConnector.dao.EmailScheduledSendDAO;
import org.exoplatform.emailConnector.entity.EmailBoxEntity;
import org.exoplatform.emailConnector.entity.EmailScheduledSendEntity;
import org.exoplatform.emailConnector.model.DraftState;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ScheduledSendStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailScheduledSendStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.service.CategoryLinkService;

/**
 * The at-most-once guarantee across a crash, end to end over the real schedule table:
 * the real service and storage over the shipped changelog, the mailbox service mocked
 * as the transmission it wraps -- each call to {@code sendStoredDraft} counted as one
 * message put on the wire.
 * <p>
 * A crash is modelled as an {@link Error} thrown from inside the transmission: nothing
 * of the run after that point executes, exactly as when the JVM dies there, and the row
 * is left as the database held it. Then the node "restarts" (its recovery flag and its
 * in-flight set are reset) and the dispatcher ticks several times: whatever the crash
 * point, the message is never put on the wire a second time.
 */
@DataJpaTest(showSql = false)
@EnableAutoConfiguration
@Import({ EmailScheduledSendStorage.class, EmailScheduledSendService.class, EmailBoxStorage.class })
@TestPropertySource(properties = { "spring.liquibase.enabled=true",
    "spring.liquibase.change-log=classpath:db/changelog/emailConnector-rdbms.db.changelog-master.xml",
    "spring.jpa.hibernate.ddl-auto=none" })
public class EmailScheduledSendCrashRecoveryTest {

  private static final String               USER     = "alice";

  private static final String               LOCAL_ID = "draft-1";

  @Autowired
  private EmailScheduledSendService         injected;

  @Autowired
  private EmailScheduledSendDAO             dao;

  @Autowired
  private EmailBoxDAO                       emailBoxDAO;

  @Autowired
  private TestEntityManager                 entityManager;

  @MockitoBean
  private EmailBoxService                   emailBoxService;

  @MockitoBean
  private UserEmailSettingService           userEmailSettingService;

  @MockitoBean
  private IdentityManager                   identityManager;

  @MockitoBean
  private CategoryLinkService               categoryLinkService;

  @MockitoBean
  private FileService                       fileService;

  @MockitoBean
  private UploadService                     uploadService;

  private EmailScheduledSendService         service;

  private MockedStatic<ExoContainerContext> containerContext;

  private final AtomicInteger               transmissions = new AtomicInteger();

  private long                              rowId;

  /**
   * The minimal Spring slice over the changelog-built database.
   */
  @Configuration
  @EntityScan(basePackageClasses = EmailBoxEntity.class)
  @EnableJpaRepositories(basePackageClasses = EmailBoxDAO.class)
  static class JpaSliceConfiguration {
  }

  /**
   * A due scheduled draft, an active owner, this node named node-a, and a pool that runs
   * on this thread.
   *
   * @throws Exception never
   */
  @BeforeEach
  void aDueScheduledDraft() throws Exception {
    containerContext = mockStatic(ExoContainerContext.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(org.mockito.Mockito.mock(ExoContainer.class));
    ReflectionTestUtils.setField(EmailConnectorUtils.class, "syncNodeName", "node-a");
    service = AopTestUtils.getTargetObject(injected);
    ReflectionTestUtils.setField(service, "executor", new InlineExecutor());
    Identity identity = new Identity(USER);
    identity.setEnable(true);
    when(identityManager.getOrCreateUserIdentity(USER)).thenReturn(identity);
    when(userEmailSettingService.getUserEmailSetting(USER)).thenReturn(new UserEmailSetting("1",
                                                                                            "alice@example.org",
                                                                                            null,
                                                                                            null,
                                                                                            null,
                                                                                            0,
                                                                                            0L,
                                                                                            null,
                                                                                            null,
                                                                                            "c",
                                                                                            true));
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    rowId = dueRow();
  }

  /**
   * Takes the stated statics away again.
   */
  @AfterEach
  void forgetTheStatics() {
    containerContext.close();
    ReflectionTestUtils.setField(EmailConnectorUtils.class, "syncNodeName", null);
    ReflectionTestUtils.setField(service, "recovered", false);
  }

  /**
   * The node dies right after the mail server accepted the message, before anything
   * recorded it: after the restart the mail is UNCERTAIN, its Sent-folder check runs
   * once and, not finding it, leaves it for its owner -- and it is never sent again.
   *
   * @throws Exception never
   */
  @Test
  void aCrashAfterTheTransmissionBeforeItsRecordNeverSendsTwice() throws Exception {
    doAnswer(invocation -> {
      transmissions.incrementAndGet();
      throw new Crash();
    }).when(emailBoxService).sendStoredDraft(eq(USER), eq(LOCAL_ID), any(Runnable.class));
    assertThrows(Crash.class, service::dispatchDue);
    assertEquals(ScheduledSendStatus.SENDING, row().getStatus(), "the crash left the claim behind");

    restartAndTick(5);

    assertEquals(1, transmissions.get(), "put on the wire once, never again");
    EmailScheduledSendEntity after = row();
    assertEquals(ScheduledSendStatus.UNCERTAIN, after.getStatus());
    assertEquals("INTERRUPTED", after.getLastError());
    assertNull(after.getNextAttemptDate(), "its one Sent-folder check has run");
    verify(emailBoxService).isInSentFolder(eq(USER), any());
  }

  /**
   * The node dies after the "sent" record, before the draft was removed: the next ticks
   * remove the draft, and never send.
   *
   * @throws Exception never
   */
  @Test
  void aCrashAfterTheRecordBeforeTheCleanupRemovesTheDraftAndNeverSends() throws Exception {
    doAnswer(invocation -> {
      transmissions.incrementAndGet();
      ((Runnable) invocation.getArgument(2)).run();
      throw new Crash();
    }).when(emailBoxService).sendStoredDraft(eq(USER), eq(LOCAL_ID), any(Runnable.class));
    assertThrows(Crash.class, service::dispatchDue);
    assertEquals(ScheduledSendStatus.SENT, row().getStatus());
    // Older than a run can take, so the sweep no longer waits for the run to clean up.
    entityManager.getEntityManager()
                 .createQuery("UPDATE EmailScheduledSendEntity s SET s.updatedDate = :old")
                 .setParameter("old", new Date(System.currentTimeMillis() - 3_600_000L))
                 .executeUpdate();

    restartAndTick(3);

    assertEquals(1, transmissions.get(), "put on the wire once, never again");
    verify(emailBoxService, org.mockito.Mockito.atLeastOnce()).deleteSentScheduledDraft(USER, LOCAL_ID);
    assertEquals(ScheduledSendStatus.SENT, row().getStatus(), "never back to anything sendable");
  }

  /**
   * Another node died holding the claim: once the claim is older than a send can take,
   * this node makes it UNCERTAIN, and nobody sends it again.
   *
   * @throws Exception never
   */
  @Test
  void aSendAbandonedByAnotherNodeIsNeverSentAgain() throws Exception {
    entityManager.getEntityManager()
                 .createQuery("UPDATE EmailScheduledSendEntity s SET s.status = :sending, s.claimedBy = 'node-b',"
                     + " s.claimedDate = :claimed, s.attempts = 1")
                 .setParameter("sending", ScheduledSendStatus.SENDING)
                 .setParameter("claimed", new Date(System.currentTimeMillis() - 31 * 60_000L))
                 .executeUpdate();
    entityManager.clear();

    restartAndTick(3);

    verify(emailBoxService, never()).sendStoredDraft(anyString(), anyString(), any());
    assertEquals(ScheduledSendStatus.UNCERTAIN, row().getStatus());
  }

  /**
   * The row as the database holds it now.
   *
   * @return the row
   */
  private EmailScheduledSendEntity row() {
    entityManager.clear();
    return dao.findById(rowId).orElseThrow();
  }

  /**
   * A node restart (recovery pending, nothing in flight), a working transmission from
   * now on, then some ticks.
   *
   * @param ticks how many
   * @throws Exception never
   */
  private void restartAndTick(int ticks) throws Exception {
    ReflectionTestUtils.setField(service, "recovered", false);
    ((Set<?>) ReflectionTestUtils.getField(service, "inFlight")).clear();
    doAnswer(invocation -> {
      transmissions.incrementAndGet();
      return null;
    }).when(emailBoxService).sendStoredDraft(eq(USER), eq(LOCAL_ID), any(Runnable.class));
    when(emailBoxService.isInSentFolder(eq(USER), any())).thenReturn(false);
    for (int i = 0; i < ticks; i++) {
      service.dispatchDue();
    }
  }

  /**
   * Stores a draft and its schedule, due a minute ago.
   *
   * @return the schedule row id
   */
  private long dueRow() {
    EmailBoxEntity draft = new EmailBoxEntity();
    draft.setUserId(USER);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setSender("Alice,alice@example.org");
    draft.setTo("Bob,bob@example.org");
    draft.setSubject("See you tomorrow");
    draft.setBody("<p>at eight</p>");
    draft.setReceivedDate(new Date());
    draft.setMailHeaderId("<draft@example.org>");
    draft.setDraftLocalId(LOCAL_ID);
    draft.setDraftState(DraftState.LOCAL_ONLY);
    draft = emailBoxDAO.saveAndFlush(draft);
    Date due = new Date(System.currentTimeMillis() - 60_000L);
    EmailScheduledSendEntity row = new EmailScheduledSendEntity();
    row.setEmailId(draft.getId());
    row.setUserId(USER);
    row.setDraftLocalId(LOCAL_ID);
    row.setScheduledDate(due);
    row.setNextAttemptDate(due);
    row.setTimeZone("UTC");
    row.setStatus(ScheduledSendStatus.SCHEDULED);
    row.setCreatedDate(due);
    row.setUpdatedDate(due);
    return dao.saveAndFlush(row).getId();
  }

  /** A JVM dying mid-run: nothing after it executes. */
  private static class Crash extends Error {
    private static final long serialVersionUID = 1L;
  }

  /**
   * A pool that runs every task on the calling thread.
   */
  private static class InlineExecutor extends ThreadPoolExecutor {

    /**
     * A two-thread pool that never starts a thread.
     */
    InlineExecutor() {
      super(2, 2, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(2));
    }

    /**
     * Runs the task here and now.
     *
     * @param command the task
     */
    @Override
    public void execute(Runnable command) {
      command.run();
    }
  }
}
