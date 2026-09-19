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
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.command.NotificationCommand;
import org.exoplatform.commons.api.notification.command.NotificationExecutor;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.emailConnector.exception.ScheduledSendConflictException;
import org.exoplatform.emailConnector.exception.ScheduledSendFailure;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailScheduledSend;
import org.exoplatform.emailConnector.model.ScheduledEmail;
import org.exoplatform.emailConnector.model.ScheduledSendError;
import org.exoplatform.emailConnector.model.ScheduledSendStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.notification.plugin.ScheduledEmailFailedNotificationPlugin;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.storage.EmailScheduledSendStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

/**
 * The scheduled-send rules, against a mocked storage and a mocked mailbox service, with
 * the pool replaced by one that runs on the calling thread (see
 * {@code EmailSyncServiceTest} for why the container is stated through a mocked static
 * and why the work must then run on this thread).
 */
@ExtendWith(MockitoExtension.class)
public class EmailScheduledSendServiceTest {

  private static final String                         USER     = "alice";

  private static final String                         LOCAL_ID = "draft-1";

  // A whole second, as the service's clock always answers.
  private static final Date                           NOW      = new Date(1_800_000_000_000L);

  @Mock
  private EmailScheduledSendStorage                   storage;

  @Mock
  private EmailBoxService                             emailBoxService;

  @Mock
  private EmailBoxStorage                             emailBoxStorage;

  @Mock
  private UserEmailSettingService                     userEmailSettingService;

  @Mock
  private IdentityManager                             identityManager;

  @Mock
  private ExoContainer                                container;

  @InjectMocks
  private EmailScheduledSendService                   service;

  private MockedStatic<ExoContainerContext>           containerContext;

  private MockedStatic<NotificationContextImpl>       notifications;

  private final List<NotificationContext>             notified = new ArrayList<>();

  /**
   * States a container, an inline pool, the database clock, an active owner, and a
   * notification pipeline that records instead of sending.
   */
  @BeforeEach
  void setUp() {
    containerContext = mockStatic(ExoContainerContext.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(container);
    ReflectionTestUtils.setField(service, "executor", new InlineExecutor(2));
    ReflectionTestUtils.setField(service, "recovered", true);
    ReflectionTestUtils.setField(EmailConnectorUtils.class, "syncNodeName", "node-a");
    lenient().when(storage.currentTimestamp()).thenReturn(new Date(System.currentTimeMillis()));
    Identity identity = new Identity(USER);
    identity.setEnable(true);
    lenient().when(identityManager.getOrCreateUserIdentity(USER)).thenReturn(identity);
    UserEmailSetting setting = new UserEmailSetting("1", "alice@example.org", null, null, null, 0, 0L, null, null, "c", true);
    lenient().when(userEmailSettingService.getUserEmailSetting(USER)).thenReturn(setting);
    lenient().when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(true);
    Email draft = new Email();
    draft.setSubject("See you tomorrow");
    draft.setMailHeaderId("<draft@example.org>");
    lenient().when(emailBoxStorage.getDraftByLocalId(USER, LOCAL_ID)).thenReturn(draft);
    notifications = mockStatic(NotificationContextImpl.class);
    notifications.when(NotificationContextImpl::cloneInstance).thenAnswer(invocation -> {
      NotificationContext ctx = mock(NotificationContext.class, org.mockito.Answers.RETURNS_SELF);
      NotificationExecutor executor = mock(NotificationExecutor.class, org.mockito.Answers.RETURNS_SELF);
      lenient().when(ctx.getNotificationExecutor()).thenReturn(executor);
      lenient().when(ctx.makeCommand(any())).thenReturn(mock(NotificationCommand.class));
      notified.add(ctx);
      return ctx;
    });
  }

  /**
   * Takes the stated statics away again.
   */
  @AfterEach
  void tearDown() {
    containerContext.close();
    notifications.close();
    ReflectionTestUtils.setField(EmailConnectorUtils.class, "syncNodeName", null);
  }

  /**
   * A date is refused below the minimum delay and beyond the horizon, measured on the
   * clock; a zone must be a real one; a mail needs a recipient; nothing reaches the
   * mailbox service when any of them fails.
   *
   * @throws Exception never
   */
  @Test
  void aScheduleIsValidatedAgainstTheClockBeforeAnythingIsTouched() throws Exception {
    long now = service.now().getTime();
    assertRefused(EmailScheduledSendService.DATE_TOO_SOON, () -> service.schedule(draft(), now + 30_000, "UTC", USER));
    assertRefused(EmailScheduledSendService.DATE_TOO_FAR,
                  () -> service.schedule(draft(), now + 366L * 86_400_000L, "UTC", USER));
    assertRefused(EmailScheduledSendService.INVALID_TIME_ZONE, () -> service.schedule(draft(), now + 3_600_000, "Mars/Olympus", USER));
    assertRefused(EmailScheduledSendService.INVALID_TIME_ZONE, () -> service.schedule(draft(), now + 3_600_000, null, USER));
    Email noRecipient = draft();
    noRecipient.setTo(List.of());
    assertRefused(EmailScheduledSendService.RECIPIENTS_MANDATORY, () -> service.schedule(noRecipient, now + 3_600_000, "UTC", USER));
    verify(emailBoxService, never()).scheduleDraft(any(), anyString(), any());
  }

  /**
   * A valid schedule creates a SCHEDULED row due at the chosen instant, with its zone,
   * through the mailbox service's freezing of the draft; the per-user limit refuses the
   * one too many.
   *
   * @throws Exception never
   */
  @Test
  @SuppressWarnings("unchecked")
  void aValidScheduleCreatesARowDueAtTheChosenInstantWithinTheLimit() throws Exception {
    long at = service.now().getTime() + 3_600_000L;
    Email saved = draft();
    saved.setId(9L);
    saved.setDraftLocalId(LOCAL_ID);
    when(emailBoxService.scheduleDraft(any(Email.class), eq(USER), any())).thenAnswer(invocation -> {
      Function<Email, EmailScheduledSend> scheduler = invocation.getArgument(2);
      return scheduler.apply(saved);
    });
    when(storage.create(any(EmailScheduledSend.class))).thenAnswer(invocation -> invocation.getArgument(0));

    ScheduledEmail scheduled = service.schedule(draft(), at, "Europe/Paris", USER);

    ArgumentCaptor<EmailScheduledSend> row = ArgumentCaptor.forClass(EmailScheduledSend.class);
    verify(storage).create(row.capture());
    assertEquals(9L, row.getValue().getEmailId());
    assertEquals(ScheduledSendStatus.SCHEDULED, row.getValue().getStatus());
    assertEquals(at, row.getValue().getScheduledDate().getTime());
    assertEquals(at, row.getValue().getNextAttemptDate().getTime());
    assertEquals("Europe/Paris", scheduled.getTimeZone());
    assertEquals(LOCAL_ID, scheduled.getDraftLocalId());

    when(storage.countListed(USER)).thenReturn(100L);
    assertRefused(EmailScheduledSendService.LIMIT_REACHED, () -> service.schedule(draft(), at, "UTC", USER));
  }

  /**
   * A due mail is claimed and sent as its owner; the "sent" record lands under the run's
   * own claim; nothing is notified. A mail another node claimed first is not sent here.
   *
   * @throws Exception never
   */
  @Test
  void aDueMailIsClaimedSentAndRecordedUnderItsOwnClaim() throws Exception {
    EmailScheduledSend claimed = claimedRow(1);
    when(storage.findDueToSend(any(Date.class), eq(2))).thenReturn(List.of(31L, 32L));
    when(storage.claim(eq(31L), eq("node-a"), any(Date.class))).thenReturn(true);
    when(storage.claim(eq(32L), eq("node-a"), any(Date.class))).thenReturn(false);
    when(storage.get(31L)).thenReturn(claimed);
    doAnswer(invocation -> {
      ((Runnable) invocation.getArgument(2)).run();
      return null;
    }).when(emailBoxService).sendStoredDraft(eq(USER), eq(LOCAL_ID), any(Runnable.class));
    when(storage.markSent(eq(31L), eq("node-a"), eq(NOW), any(Date.class))).thenReturn(true);

    assertEquals(1, service.dispatchDue());

    verify(emailBoxService, times(1)).sendStoredDraft(eq(USER), eq(LOCAL_ID), any(Runnable.class));
    verify(storage).markSent(eq(31L), eq("node-a"), eq(NOW), any(Date.class));
    verify(storage, never()).get(32L);
    assertTrue(notified.isEmpty(), "a sent mail notifies nobody");
  }

  /**
   * A pool with no free thread asks the storage for nothing and ends the tick quietly
   * (a page of size zero is refused by Spring Data).
   */
  @Test
  void aFullPoolDispatchesNothingAndAsksForNothing() {
    InlineExecutor full = new InlineExecutor(2);
    full.active = 2;
    ReflectionTestUtils.setField(service, "executor", full);
    assertEquals(0, service.dispatchDue());
    verify(storage, never()).findDueToSend(any(Date.class), org.mockito.ArgumentMatchers.anyInt());
    verify(storage, never()).findDueToCheck(any(Date.class), org.mockito.ArgumentMatchers.anyInt());
  }

  /**
   * "Send now" is in flight before its claim: a first-tick recovery running meanwhile
   * excludes it.
   *
   * @throws Exception never
   */
  @Test
  void aSendNowIsInFlightBeforeItsClaim() throws Exception {
    EmailScheduledSend claimed = claimedRow(1);
    when(storage.get(USER, LOCAL_ID)).thenReturn(claimed);
    when(storage.claimNow(eq(USER), eq(LOCAL_ID), eq("node-a"), any(Date.class))).thenAnswer(invocation -> {
      assertTrue(((java.util.Set<?>) ReflectionTestUtils.getField(service, "inFlight")).contains(31L));
      return true;
    });
    service.sendNow(LOCAL_ID, USER);
    assertTrue(((java.util.Set<?>) ReflectionTestUtils.getField(service, "inFlight")).isEmpty());
  }

  /**
   * A failure to connect is retried after a back-off (+1, +5, +15 minutes), never
   * beyond the retry budget, where it becomes FAILED and the owner is told.
   *
   * @throws Exception never
   */
  @Test
  void aFailureToConnectIsRetriedWithABackOffThenFails() throws Exception {
    givenTheSendFails(ScheduledSendFailure.Kind.TRANSIENT, ScheduledSendError.NETWORK);
    long[] expectedMinutes = { 1, 5, 15 };
    for (int attempts = 1; attempts <= 3; attempts++) {
      EmailScheduledSend claimed = claimedRow(attempts);
      service.runClaimed(claimed);
      ArgumentCaptor<Date> next = ArgumentCaptor.forClass(Date.class);
      verify(storage).endRun(eq(31L),
                             eq("node-a"),
                             eq(NOW),
                             eq(ScheduledSendStatus.SCHEDULED),
                             eq(ScheduledSendError.NETWORK),
                             next.capture(),
                             any(Date.class));
      long waited = next.getValue().getTime() - service.now().getTime();
      assertTrue(Math.abs(waited - expectedMinutes[attempts - 1] * 60_000L) < 5_000L, "attempt " + attempts + " waits " + waited);
      org.mockito.Mockito.clearInvocations(storage);
    }
    service.runClaimed(claimedRow(4));
    verify(storage).endRun(eq(31L),
                           eq("node-a"),
                           eq(NOW),
                           eq(ScheduledSendStatus.FAILED),
                           eq(ScheduledSendError.NETWORK),
                           isNull(),
                           any(Date.class));
    assertEquals(1, notified.size(), "the owner is told once it has failed for good");
  }

  /**
   * A refusal fails at once and is notified with its reason code and the mail's subject;
   * a failure that may have reached the server is UNCERTAIN, its Sent-folder check due
   * shortly, never SCHEDULED, and not notified yet.
   *
   * @throws Exception never
   */
  @Test
  void aRefusalFailsAtOnceAndAnAmbiguousFailureIsUncertainNeverRetried() throws Exception {
    givenTheSendFails(ScheduledSendFailure.Kind.PERMANENT, ScheduledSendError.RECIPIENT_REFUSED);
    service.runClaimed(claimedRow(1));
    verify(storage).endRun(eq(31L),
                           eq("node-a"),
                           eq(NOW),
                           eq(ScheduledSendStatus.FAILED),
                           eq(ScheduledSendError.RECIPIENT_REFUSED),
                           isNull(),
                           any(Date.class));
    assertEquals(1, notified.size());
    verify(notified.get(0)).append(ScheduledEmailFailedNotificationPlugin.REASON, "RECIPIENT_REFUSED");
    verify(notified.get(0)).append(ScheduledEmailFailedNotificationPlugin.SUBJECT, "See you tomorrow");

    givenTheSendFails(ScheduledSendFailure.Kind.AMBIGUOUS, ScheduledSendError.UNCONFIRMED);
    service.runClaimed(claimedRow(1));
    ArgumentCaptor<Date> next = ArgumentCaptor.forClass(Date.class);
    verify(storage).endRun(eq(31L),
                           eq("node-a"),
                           eq(NOW),
                           eq(ScheduledSendStatus.UNCERTAIN),
                           eq(ScheduledSendError.UNCONFIRMED),
                           next.capture(),
                           any(Date.class));
    assertTrue(next.getValue().after(new Date()), "its check is due later");
    verify(storage, never()).endRun(anyLong(),
                                    anyString(),
                                    any(),
                                    eq(ScheduledSendStatus.SCHEDULED),
                                    eq(ScheduledSendError.UNCONFIRMED),
                                    any(),
                                    any());
    assertEquals(1, notified.size(), "not notified before its check");
  }

  /**
   * An owner whose account is disabled, or whose mailbox can no longer connect, never
   * has a mail sent as them: FAILED, DISCONNECTED, notified.
   *
   * @throws Exception never
   */
  @Test
  void aDisabledOrDisconnectedOwnerNeverSendsAndIsNotified() throws Exception {
    when(storage.endRun(anyLong(), anyString(), any(), any(), any(), any(), any())).thenReturn(true);
    Identity disabled = new Identity(USER);
    disabled.setEnable(false);
    when(identityManager.getOrCreateUserIdentity(USER)).thenReturn(disabled);
    service.runClaimed(claimedRow(1));
    when(identityManager.getOrCreateUserIdentity(USER)).thenReturn(enabled());
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    service.runClaimed(claimedRow(1));

    verify(emailBoxService, never()).sendStoredDraft(anyString(), anyString(), any());
    verify(storage, times(2)).endRun(eq(31L),
                                     eq("node-a"),
                                     eq(NOW),
                                     eq(ScheduledSendStatus.FAILED),
                                     eq(ScheduledSendError.DISCONNECTED),
                                     isNull(),
                                     any(Date.class));
    assertEquals(2, notified.size());
  }

  /**
   * The first tick after a boot makes this node's interrupted sends UNCERTAIN (never
   * SCHEDULED), every tick does so for claims older than a send can take, and a mail
   * recorded SENT whose draft was not removed is cleaned up, never sent.
   *
   * @throws Exception never
   */
  @Test
  void aBootRecoversInterruptedSendsAsUncertainAndCleansUpSentOnes() throws Exception {
    ReflectionTestUtils.setField(service, "recovered", false);
    EmailScheduledSend sent = claimedRow(1);
    sent.setStatus(ScheduledSendStatus.SENT);
    when(storage.findSentBefore(any(Date.class), eq(100))).thenReturn(List.of(sent));

    service.dispatchDue();
    service.dispatchDue();

    verify(storage, times(1)).markUncertainOf(eq("node-a"), any(), any(Date.class));
    verify(storage, times(2)).markStaleUncertain(any(Date.class), any(), any(Date.class));
    verify(emailBoxService, times(2)).deleteSentScheduledDraft(USER, LOCAL_ID);
    verify(emailBoxService, never()).sendStoredDraft(anyString(), anyString(), any());
  }

  /**
   * An uncertain mail's check: found in the Sent folder, it is recorded SENT under the
   * check's claim and its draft removed; not found, the owner is told; never a send.
   *
   * @throws Exception never
   */
  @Test
  void anUncertainMailIsResolvedByTheSentFolderNeverBySendingAgain() throws Exception {
    EmailScheduledSend checked = claimedRow(1);
    checked.setStatus(ScheduledSendStatus.UNCERTAIN);
    when(emailBoxService.isInSentFolder(USER, "<draft@example.org>")).thenReturn(true);
    when(storage.markCheckedSent(eq(31L), eq("node-a"), eq(NOW), any(Date.class))).thenReturn(true);
    service.runCheck(checked);
    verify(emailBoxService).deleteSentScheduledDraft(USER, LOCAL_ID);
    assertTrue(notified.isEmpty());

    when(emailBoxService.isInSentFolder(USER, "<draft@example.org>")).thenThrow(new IllegalStateException("imap down"));
    service.runCheck(checked);
    assertEquals(1, notified.size(), "unresolved: the owner decides");
    verify(notified.get(0)).append(ScheduledEmailFailedNotificationPlugin.REASON, "UNCONFIRMED");
    verify(emailBoxService, never()).sendStoredDraft(anyString(), anyString(), any());
  }

  /**
   * "Send now" shares the dispatcher's claim: when it does not land, the owner is told
   * why -- no such mail (404), being sent (409), or uncertain (409, its own code).
   *
   * @throws Exception never
   */
  @Test
  void sendNowAndTheOtherActionsAnswerWhyTheyDidNotLand() throws Exception {
    when(storage.claimNow(eq(USER), eq(LOCAL_ID), eq("node-a"), any(Date.class))).thenReturn(false);
    when(storage.get(USER, LOCAL_ID)).thenReturn(null);
    assertThrows(ObjectNotFoundException.class, () -> service.sendNow(LOCAL_ID, USER));
    assertThrows(ObjectNotFoundException.class, () -> service.cancel(LOCAL_ID, USER));

    EmailScheduledSend sending = claimedRow(1);
    when(storage.get(USER, LOCAL_ID)).thenReturn(sending);
    assertEquals(ScheduledSendConflictException.SENDING,
                 assertThrows(ScheduledSendConflictException.class, () -> service.sendNow(LOCAL_ID, USER)).getMessage());
    assertEquals(ScheduledSendConflictException.SENDING,
                 assertThrows(ScheduledSendConflictException.class, () -> service.cancel(LOCAL_ID, USER)).getMessage());
    long at = service.now().getTime() + 3_600_000L;
    sending.setStatus(ScheduledSendStatus.UNCERTAIN);
    assertEquals(EmailScheduledSendService.UNCERTAIN_CONFLICT,
                 assertThrows(ScheduledSendConflictException.class, () -> service.reschedule(LOCAL_ID, at, "UTC", USER))
                                                                                                                  .getMessage());
    when(userEmailSettingService.canConnect(anyLong(), anyString())).thenReturn(false);
    assertThrows(IllegalAccessException.class, () -> service.sendNow(LOCAL_ID, USER));
  }

  /**
   * "Send now" that lands sends on the caller's thread and answers SENT once the row is
   * gone with its draft.
   *
   * @throws Exception never
   */
  @Test
  void aSendNowThatLandsSendsAtOnceAndAnswersSent() throws Exception {
    EmailScheduledSend claimed = claimedRow(1);
    when(storage.claimNow(eq(USER), eq(LOCAL_ID), eq("node-a"), any(Date.class))).thenReturn(true);
    when(storage.get(USER, LOCAL_ID)).thenReturn(claimed, claimed, (EmailScheduledSend) null);
    ScheduledEmail result = service.sendNow(LOCAL_ID, USER);
    verify(emailBoxService, times(1)).sendStoredDraft(eq(USER), eq(LOCAL_ID), any(Runnable.class));
    assertEquals(ScheduledSendStatus.SENT, result.getStatus());
  }

  /**
   * The clock is the database's, to the second, when it is close to this JVM's; a
   * database clock hours away (a session time zone the driver does not expect) is not
   * trusted, and this JVM's is used.
   */
  @Test
  void theDatabaseClockIsUsedToTheSecondUnlessItIsHoursAway() {
    long jvm = System.currentTimeMillis();
    when(storage.currentTimestamp()).thenReturn(new Date(jvm - 20_000L));
    Date near = service.now();
    assertEquals(0, near.getTime() % 1000);
    assertTrue(Math.abs(near.getTime() - (jvm - 20_000L)) < 1500L, "the database clock, truncated");

    when(storage.currentTimestamp()).thenReturn(new Date(jvm + 2 * 3_600_000L));
    assertTrue(Math.abs(service.now().getTime() - System.currentTimeMillis()) < 2000L, "this JVM's clock");
  }

  /**
   * Makes the mailbox service's stored send fail with a classified failure.
   *
   * @param kind what it means
   * @param error the code
   * @throws Exception never
   */
  private void givenTheSendFails(ScheduledSendFailure.Kind kind, ScheduledSendError error) throws Exception {
    doThrow(new ScheduledSendFailure(kind, error, null)).when(emailBoxService)
                                                         .sendStoredDraft(eq(USER), eq(LOCAL_ID), any(Runnable.class));
    lenient().when(storage.endRun(anyLong(), anyString(), any(), any(), any(), any(), any())).thenReturn(true);
  }

  /**
   * A row claimed by node-a at {@link #NOW}.
   *
   * @param attempts its attempt count after the claim
   * @return the row
   */
  private EmailScheduledSend claimedRow(int attempts) {
    return new EmailScheduledSend(31L,
                                  9L,
                                  USER,
                                  LOCAL_ID,
                                  NOW,
                                  "UTC",
                                  ScheduledSendStatus.SENDING,
                                  NOW,
                                  attempts,
                                  "node-a",
                                  NOW,
                                  null,
                                  NOW,
                                  NOW);
  }

  /**
   * A composed draft with a recipient.
   *
   * @return the draft
   */
  private Email draft() {
    Email draft = new Email();
    draft.setDraftLocalId(LOCAL_ID);
    draft.setSubject("See you tomorrow");
    draft.setContent(new EmailContent("<p>at eight</p>", null, null));
    draft.setTo(List.of(new EmailRecipient("Bob", "bob@example.org", null, false)));
    return draft;
  }

  /**
   * An enabled identity of the owner.
   *
   * @return the identity
   */
  private Identity enabled() {
    Identity identity = new Identity(USER);
    identity.setEnable(true);
    return identity;
  }

  /**
   * Asserts a call is refused with a message code.
   *
   * @param code the code
   * @param call the call
   */
  private void assertRefused(String code, org.junit.jupiter.api.function.Executable call) {
    assertEquals(code, assertThrows(IllegalArgumentException.class, call).getMessage());
  }

  /**
   * A pool that runs every task on the calling thread.
   */
  private static class InlineExecutor extends ThreadPoolExecutor {

    private int active;

    /**
     * @param threads the core and maximum size
     */
    InlineExecutor(int threads) {
      super(threads, threads, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(threads));
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

    /**
     * The busy-thread count the test set.
     *
     * @return the stated number of running tasks
     */
    @Override
    public int getActiveCount() {
      return active;
    }
  }
}
