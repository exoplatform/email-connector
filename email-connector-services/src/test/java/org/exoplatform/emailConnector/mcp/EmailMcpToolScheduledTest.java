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
package org.exoplatform.emailConnector.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.CannotAcquireLockException;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.exception.ScheduledSendConflictException;
import org.exoplatform.emailConnector.mcp.model.ScheduledEmailModel;
import org.exoplatform.emailConnector.model.DraftMailbox;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.ScheduledEmail;
import org.exoplatform.emailConnector.model.ScheduledSendStatus;
import org.exoplatform.emailConnector.service.EmailBoxService;
import org.exoplatform.emailConnector.service.EmailDelegationService;
import org.exoplatform.emailConnector.service.EmailScheduledSendService;
import org.exoplatform.emailConnector.service.EmailSignatureService;
import org.exoplatform.emailConnector.service.UserEmailSettingService;

import io.meeds.social.timezone.service.UserTimeZoneService;

/**
 * The scheduled-send tools (EXO-91996): a draft scheduled as it is stored, at a local
 * time read in the user's zone, only when it still says what the user approved; the
 * Scheduled view listed; a scheduled mail rescheduled or cancelled only when it is the
 * one the user approved.
 */
class EmailMcpToolScheduledTest {

  private static final String       USERNAME = "testuser1";

  private static final String       DRAFT_ID = "draft-1";

  private static final String       LINK     = "https://exo.example.com/portal/dw?openEmailBox=true&folder=SCHEDULED";

  private EmailBoxService           emailBoxService;

  private EmailScheduledSendService emailScheduledSendService;

  private UserTimeZoneService       userTimeZoneService;

  private EmailMcpTool              emailMcpTool;

  /**
   * A tool over mocked services, acting as {@link #USERNAME}, with a fixed Scheduled
   * view link and the user's zone recorded as Europe/Paris.
   */
  @BeforeEach
  void setUp() {
    emailBoxService = Mockito.mock(EmailBoxService.class);
    emailScheduledSendService = Mockito.mock(EmailScheduledSendService.class);
    userTimeZoneService = Mockito.mock(UserTimeZoneService.class);
    emailMcpTool = new EmailMcpTool(emailBoxService,
                                    Mockito.mock(UserEmailSettingService.class),
                                    Mockito.mock(EmailDelegationService.class),
                                    Mockito.mock(EmailSignatureService.class),
                                    emailScheduledSendService,
                                    userTimeZoneService) {
      @Override
      public String getCurrentUserName() {
        return USERNAME;
      }

      @Override
      String scheduledLink() {
        return LINK;
      }
    };
    when(userTimeZoneService.getUserTimeZone(USERNAME)).thenReturn("Europe/Paris");
  }

  /**
   * A draft of the user's own mailbox.
   *
   * @param subject its subject
   * @param to its recipients
   * @return the draft
   */
  private static Email storedDraft(String subject, String... to) {
    Email draft = new Email();
    draft.setDraftLocalId(DRAFT_ID);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setSubject(subject);
    draft.setTo(recipients(to));
    draft.setCc(List.of());
    draft.setBcc(List.of());
    return draft;
  }

  /**
   * Recipients of the given addresses.
   *
   * @param addresses the addresses
   * @return the recipients
   */
  private static List<EmailRecipient> recipients(String... addresses) {
    return java.util.Arrays.stream(addresses).map(address -> new EmailRecipient(null, address, null, false)).toList();
  }

  /**
   * A scheduled mail of the user, as the service lists it.
   *
   * @param subject its subject
   * @param at when it is sent, epoch milliseconds
   * @param zone the zone it was scheduled in
   * @param to its recipients
   * @return the scheduled mail
   */
  private static ScheduledEmail scheduled(String subject, long at, String zone, String... to) {
    ScheduledEmail scheduled = new ScheduledEmail();
    scheduled.setDraftLocalId(DRAFT_ID);
    scheduled.setSubject(subject);
    scheduled.setTo(recipients(to));
    scheduled.setScheduledDate(at);
    scheduled.setTimeZone(zone);
    scheduled.setStatus(ScheduledSendStatus.SCHEDULED);
    return scheduled;
  }

  /**
   * The instant a local time names in a zone.
   *
   * @param local the local time
   * @param zone the zone
   * @return epoch milliseconds
   */
  private static long at(String local, String zone) {
    return LocalDateTime.parse(local).atZone(ZoneId.of(zone)).toInstant().toEpochMilli();
  }

  /**
   * Makes the service schedule the stored draft: it runs the tool's check on the row it
   * is handed, then answers the scheduled mail.
   *
   * @param row the row as stored under the lock
   */
  private void scheduleAsStoredRuns(Email row) throws Exception {
    when(emailScheduledSendService.scheduleAsStored(eq(DRAFT_ID), anyLong(), anyString(), eq(USERNAME), any())).thenAnswer(invocation -> {
      Consumer<Email> check = invocation.getArgument(4);
      check.accept(row);
      return scheduled(row.getSubject(), invocation.getArgument(1), invocation.getArgument(2), "bob@acme.com");
    });
  }

  /**
   * A local time is read in the user's own zone when the call names none, and the draft
   * is scheduled as stored, through the service's stored-draft path: the answer states
   * the time in that zone and in UTC.
   */
  @Test
  void aDraftIsScheduledAsStoredAtTheLocalTimeOfTheUsersZone() throws Exception {
    Email stored = storedDraft("Proposal", "bob@acme.com");
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(stored);
    scheduleAsStoredRuns(stored);

    ScheduledEmailModel model = emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null);

    verify(emailScheduledSendService).scheduleAsStored(eq(DRAFT_ID),
                                                       eq(at("2026-12-03T08:30", "Europe/Paris")),
                                                       eq("Europe/Paris"),
                                                       eq(USERNAME),
                                                       any());
    assertEquals("2026-12-03T08:30:00", model.getSendAt());
    assertEquals("2026-12-03T07:30:00Z", model.getSendAtUtc());
    assertEquals("Europe/Paris", model.getTimeZone());
    assertEquals("SCHEDULED", model.getStatus());
    assertEquals(LINK, model.getLink());
  }

  /**
   * The zone the call names wins over the user's; a time carrying an offset is refused,
   * since the approval card names the zone the time is read in, not an offset.
   */
  @Test
  void aNamedZoneDecidesTheInstantAndAnOffsetIsRefused() throws Exception {
    Email stored = storedDraft("Proposal", "bob@acme.com");
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(stored);
    scheduleAsStoredRuns(stored);

    emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", "America/New_York", List.of("bob@acme.com"), "Proposal", null, null);
    verify(emailScheduledSendService).scheduleAsStored(eq(DRAFT_ID),
                                                       eq(at("2026-12-03T13:30", "UTC")),
                                                       eq("America/New_York"),
                                                       eq(USERNAME),
                                                       any());

    IllegalArgumentException offset =
                                    assertThrows(IllegalArgumentException.class,
                                                 () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30+05:00", "Asia/Tokyo", List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(offset.getMessage().contains("with no offset"), offset.getMessage());
  }

  /**
   * A local time the zone skips, or shows twice, when its clocks change is refused
   * rather than moved to another instant than the one approved; a year out of any reach
   * is refused as too far, never as an arithmetic failure.
   */
  @Test
  void aTimeTheClocksSkipOrRepeatOrOutOfReachIsRefused() throws Exception {
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(storedDraft("Proposal", "bob@acme.com"));

    IllegalArgumentException skipped =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2027-03-28T02:30", "Europe/Paris", List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(skipped.getMessage().contains("that hour is skipped"), skipped.getMessage());
    IllegalArgumentException twice =
                                   assertThrows(IllegalArgumentException.class,
                                                () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2027-10-31T02:30", "Europe/Paris", List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(twice.getMessage().contains("that hour comes twice"), twice.getMessage());
    IllegalArgumentException farAway =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "+300000000-01-01T00:00", "UTC", List.of("bob@acme.com"), "Proposal", null, null));
    assertEquals("send_at is too far ahead: pick an earlier time. Nothing was scheduled.", farAway.getMessage());
    verify(emailScheduledSendService, never()).scheduleAsStored(anyString(), anyLong(), anyString(), anyString(), any());
  }

  /**
   * With no zone named and none recorded for the user, nothing is scheduled and the
   * model is told to ask; an unreadable time or zone is refused the same way.
   */
  @Test
  void noKnownZoneOrAnUnreadableTimeSchedulesNothing() throws Exception {
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(storedDraft("Proposal", "bob@acme.com"));
    when(userTimeZoneService.getUserTimeZone(USERNAME)).thenReturn(null);

    IllegalArgumentException noZone =
                                    assertThrows(IllegalArgumentException.class,
                                                 () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(noZone.getMessage().contains("time zone is not known"), noZone.getMessage());
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", "Mars/Olympus", List.of("bob@acme.com"), "Proposal", null, null));
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "tomorrow morning", "UTC", List.of("bob@acme.com"), "Proposal", null, null));
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, null, "UTC", List.of("bob@acme.com"), "Proposal", null, null));
    verify(emailScheduledSendService, never()).scheduleAsStored(anyString(), anyLong(), anyString(), anyString(), any());
  }

  /**
   * Arguments that are not what the draft says -- before the call, or by the time the
   * draft's lock is held -- schedule nothing.
   */
  @Test
  void aDraftThatNoLongerSaysWhatWasApprovedIsNotScheduled() throws Exception {
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(storedDraft("Proposal", "bob@acme.com"));

    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("eve@evil.com"), "Proposal", null, null));
    verify(emailScheduledSendService, never()).scheduleAsStored(anyString(), anyLong(), anyString(), anyString(), any());

    Email editedMeanwhile = storedDraft("Proposal", "bob@acme.com", "new@acme.com");
    scheduleAsStoredRuns(editedMeanwhile);
    IllegalArgumentException refusal =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(refusal.getMessage().startsWith("Nothing was scheduled") && refusal.getMessage().contains("new@acme.com"),
               refusal.getMessage());
  }

  /**
   * A draft written in a shared mailbox, or already scheduled, is refused before the
   * service is called; one scheduled meanwhile, under the lock, too.
   */
  @Test
  void aSharedOrScheduledDraftIsNotScheduledHere() throws Exception {
    Email shared = storedDraft("Proposal", "bob@acme.com");
    shared.setSendDelegationId(7L);
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(shared);
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));

    Email scheduled = storedDraft("Proposal", "bob@acme.com");
    scheduled.setScheduled(true);
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(scheduled);
    IllegalArgumentException refusal =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(refusal.getMessage().contains("reschedule_email"), refusal.getMessage());
    verify(emailScheduledSendService, never()).scheduleAsStored(anyString(), anyLong(), anyString(), anyString(), any());

    Email stored = storedDraft("Proposal", "bob@acme.com");
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(stored);
    Email scheduledMeanwhile = storedDraft("Proposal", "bob@acme.com");
    scheduledMeanwhile.setScheduled(true);
    scheduleAsStoredRuns(scheduledMeanwhile);
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));
  }

  /**
   * The service's message codes reach the model as words it can act on, and a draft
   * that is gone is named as such.
   */
  @Test
  void theServicesRefusalsAreAnsweredInWords() throws Exception {
    when(emailBoxService.getDraft(DRAFT_ID, USERNAME)).thenReturn(storedDraft("Proposal", "bob@acme.com"));
    when(emailScheduledSendService.scheduleAsStored(eq(DRAFT_ID), anyLong(), anyString(), eq(USERNAME), any()))
                                                                                                             .thenThrow(new IllegalArgumentException(EmailScheduledSendService.DATE_TOO_SOON));
    IllegalArgumentException tooSoon =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));
    assertEquals("send_at is too soon: pick a later time. Nothing was scheduled.", tooSoon.getMessage());

    when(emailScheduledSendService.scheduleAsStored(eq(DRAFT_ID), anyLong(), anyString(), eq(USERNAME), any()))
                                                                                                             .thenThrow(new IllegalArgumentException(EmailScheduledSendService.LIMIT_REACHED));
    IllegalArgumentException limit =
                                   assertThrows(IllegalArgumentException.class,
                                                () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(limit.getMessage().contains("as many scheduled mails as allowed"), limit.getMessage());

    when(emailScheduledSendService.scheduleAsStored(eq(DRAFT_ID), anyLong(), anyString(), eq(USERNAME), any()))
                                                                                                             .thenThrow(new ScheduledSendConflictException(ScheduledSendConflictException.LOCKED));
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));

    when(emailScheduledSendService.scheduleAsStored(eq(DRAFT_ID), anyLong(), anyString(), eq(USERNAME), any()))
                                                                                                             .thenThrow(new ScheduledSendConflictException(ScheduledSendConflictException.SENDING));
    IllegalArgumentException sending =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(sending.getMessage().contains("is being sent right now"), sending.getMessage());

    when(emailScheduledSendService.scheduleAsStored(eq(DRAFT_ID), anyLong(), anyString(), eq(USERNAME), any()))
                                                                                                             .thenThrow(new IllegalStateException("emailConnector.scheduled.serverCopyRemains"));
    IllegalStateException serverCopy =
                                     assertThrows(IllegalStateException.class,
                                                  () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(serverCopy.getMessage().contains("could not be removed"), serverCopy.getMessage());

    when(emailScheduledSendService.scheduleAsStored(eq(DRAFT_ID), anyLong(), anyString(), eq(USERNAME), any()))
                                                                                                             .thenThrow(new IllegalArgumentException("emailConnector.some.newCode"));
    IllegalArgumentException unknown =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(unknown.getMessage().contains("(emailConnector.some.newCode)") && unknown.getMessage().endsWith("Nothing was scheduled."),
               unknown.getMessage());

    when(emailScheduledSendService.scheduleAsStored(eq(DRAFT_ID), anyLong(), anyString(), eq(USERNAME), any()))
                                                                                                             .thenThrow(new ObjectNotFoundException("emailConnector.drafts.send.gone"));
    ObjectNotFoundException gone =
                                 assertThrows(ObjectNotFoundException.class,
                                              () -> emailMcpTool.scheduleEmailDraft(DRAFT_ID, "2026-12-03T08:30", null, List.of("bob@acme.com"), "Proposal", null, null));
    assertTrue(gone.getMessage().contains("No draft with draft_id"), gone.getMessage());
  }

  /**
   * The Scheduled view is listed with each mail's time in its own zone and in UTC, its
   * status and error, the shared mailbox it was written in, and the link; the limit is
   * bounded.
   */
  @Test
  void theScheduledViewIsListedWithEachMailsTimeStatusAndMailbox() throws Exception {
    ScheduledEmail own = scheduled("Proposal", at("2026-12-03T08:30", "Europe/Paris"), "Europe/Paris", "bob@acme.com");
    ScheduledEmail failed = scheduled("Invoice", at("2026-12-04T17:00", "America/New_York"), "America/New_York", "ann@acme.com");
    failed.setStatus(ScheduledSendStatus.FAILED);
    failed.setLastError("RECIPIENT_REFUSED");
    failed.setMailbox(new DraftMailbox(7L, "Sales Team", "sales@acme.com", true));
    when(emailScheduledSendService.getScheduledEmails(USERNAME, 0, 100)).thenReturn(List.of(own, failed));

    List<ScheduledEmailModel> models = emailMcpTool.listScheduledEmails(500, null);

    assertEquals(2, models.size());
    assertEquals("2026-12-03T08:30:00", models.get(0).getSendAt());
    assertEquals(List.of("bob@acme.com"), models.get(0).getTo());
    assertNull(models.get(0).getMailbox());
    assertEquals("2026-12-04T17:00:00", models.get(1).getSendAt());
    assertEquals("2026-12-04T22:00:00Z", models.get(1).getSendAtUtc());
    assertEquals("FAILED", models.get(1).getStatus());
    assertEquals("RECIPIENT_REFUSED", models.get(1).getError());
    assertEquals("sales@acme.com", models.get(1).getMailbox());
    assertEquals(LINK, models.get(1).getLink());
  }

  /**
   * A scheduled mail gets its new time only when it is the one the user approved; the
   * new local time is read in the user's zone.
   */
  @Test
  void aScheduledMailIsRescheduledOnlyWhenItIsTheOneApproved() throws Exception {
    ScheduledEmail current = scheduled("Proposal", at("2026-12-03T08:30", "Europe/Paris"), "Europe/Paris", "bob@acme.com");
    when(emailScheduledSendService.getScheduledEmail(DRAFT_ID, USERNAME)).thenReturn(current);

    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.rescheduleEmail(DRAFT_ID, "2026-12-04T09:00", null, "Another", List.of("bob@acme.com")));
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.rescheduleEmail(DRAFT_ID, "2026-12-04T09:00", null, "Proposal", List.of("eve@evil.com")));
    verify(emailScheduledSendService, never()).reschedule(anyString(), anyLong(), anyString(), anyString(), any());

    long newTime = at("2026-12-04T09:00", "Europe/Paris");
    rescheduleRuns(current, newTime);
    ScheduledEmailModel model = emailMcpTool.rescheduleEmail(DRAFT_ID, "2026-12-04T09:00", null, "Proposal", List.of("BOB@acme.com"));
    assertEquals("2026-12-04T09:00:00", model.getSendAt());
    verify(emailScheduledSendService).reschedule(eq(DRAFT_ID), eq(newTime), eq("Europe/Paris"), eq(USERNAME), any());

    ScheduledEmail editedMeanwhile = scheduled("Proposal", current.getScheduledDate(), "Europe/Paris", "bob@acme.com", "new@acme.com");
    rescheduleRuns(editedMeanwhile, newTime);
    IllegalArgumentException refusal =
                                     assertThrows(IllegalArgumentException.class,
                                                  () -> emailMcpTool.rescheduleEmail(DRAFT_ID, "2026-12-04T09:00", null, "Proposal", List.of("bob@acme.com")));
    assertTrue(refusal.getMessage().startsWith("Nothing was rescheduled") && refusal.getMessage().contains("new@acme.com"),
               refusal.getMessage());
  }

  /**
   * Makes the service reschedule: it runs the tool's check on the mail as it stands
   * under the draft's lock, then answers it at the new time.
   *
   * @param standing the scheduled mail as it stands under the lock
   * @param newTime the new time
   */
  private void rescheduleRuns(ScheduledEmail standing, long newTime) throws Exception {
    when(emailScheduledSendService.reschedule(eq(DRAFT_ID), anyLong(), anyString(), eq(USERNAME), any())).thenAnswer(invocation -> {
      Consumer<ScheduledEmail> check = invocation.getArgument(4);
      check.accept(standing);
      return scheduled(standing.getSubject(), newTime, invocation.getArgument(2), "bob@acme.com");
    });
  }

  /**
   * Makes the service cancel: it runs the tool's check on the mail as it stands under the
   * draft's lock, then answers it.
   *
   * @param standing the scheduled mail as it stands under the lock
   */
  private void cancelRuns(ScheduledEmail standing) throws Exception {
    when(emailScheduledSendService.cancel(eq(DRAFT_ID), eq(USERNAME), any())).thenAnswer(invocation -> {
      Consumer<ScheduledEmail> check = invocation.getArgument(2);
      check.accept(standing);
      return standing;
    });
  }

  /**
   * With no zone named nor recorded for the user, a new time is read in the mail's own
   * zone; a mail whose sending could not be confirmed, or that is being sent, is refused
   * in words.
   */
  @Test
  void aRescheduleFallsBackOnTheMailsZoneAndRefusesAMailOnItsWay() throws Exception {
    when(userTimeZoneService.getUserTimeZone(USERNAME)).thenReturn(null);
    ScheduledEmail current = scheduled("Proposal", at("2026-12-03T08:30", "Asia/Tokyo"), "Asia/Tokyo", "bob@acme.com");
    when(emailScheduledSendService.getScheduledEmail(DRAFT_ID, USERNAME)).thenReturn(current);
    long newTime = at("2026-12-04T09:00", "Asia/Tokyo");
    rescheduleRuns(current, newTime);

    emailMcpTool.rescheduleEmail(DRAFT_ID, "2026-12-04T09:00", null, "Proposal", List.of("bob@acme.com"));
    verify(emailScheduledSendService).reschedule(eq(DRAFT_ID), eq(newTime), eq("Asia/Tokyo"), eq(USERNAME), any());

    when(emailScheduledSendService.reschedule(anyString(), anyLong(), anyString(), anyString(), any()))
                                                                                                    .thenThrow(new ScheduledSendConflictException(EmailScheduledSendService.UNCERTAIN_CONFLICT));
    IllegalArgumentException uncertain =
                                       assertThrows(IllegalArgumentException.class,
                                                    () -> emailMcpTool.rescheduleEmail(DRAFT_ID, "2026-12-04T09:00", null, "Proposal", List.of("bob@acme.com")));
    assertTrue(uncertain.getMessage().contains("may already have been sent"), uncertain.getMessage());
  }

  /**
   * A scheduled mail is cancelled only when it is the one the user approved; one being
   * sent is refused in words, and a draft_id naming no scheduled mail is not found.
   */
  @Test
  void aScheduledMailIsCancelledOnlyWhenItIsTheOneApproved() throws Exception {
    ScheduledEmail current = scheduled("Proposal", at("2026-12-03T08:30", "Europe/Paris"), "Europe/Paris", "bob@acme.com");
    when(emailScheduledSendService.getScheduledEmail(DRAFT_ID, USERNAME)).thenReturn(current);

    assertThrows(IllegalArgumentException.class, () -> emailMcpTool.cancelScheduledEmail(DRAFT_ID, "Proposal", List.of("eve@evil.com")));
    verify(emailScheduledSendService, never()).cancel(anyString(), anyString(), any());
    IllegalArgumentException notBare = assertThrows(IllegalArgumentException.class,
                                                    () -> emailMcpTool.cancelScheduledEmail(DRAFT_ID, "Proposal", List.of("Bob <bob@acme.com>")));
    assertTrue(notBare.getMessage().startsWith("Nothing was cancelled"), notBare.getMessage());
    ScheduledEmail sent = scheduled("Proposal", current.getScheduledDate(), "Europe/Paris", "bob@acme.com");
    sent.setStatus(ScheduledSendStatus.SENT);
    when(emailScheduledSendService.getScheduledEmail("sent", USERNAME)).thenReturn(sent);
    assertThrows(ObjectNotFoundException.class, () -> emailMcpTool.cancelScheduledEmail("sent", "Proposal", List.of("bob@acme.com")));
    ScheduledEmail inUndoWait = scheduled("Proposal", current.getScheduledDate(), "Europe/Paris", "bob@acme.com");
    inUndoWait.setStatus(ScheduledSendStatus.HELD);
    when(emailScheduledSendService.getScheduledEmail("held", USERNAME)).thenReturn(inUndoWait);
    IllegalArgumentException undoWait = assertThrows(IllegalArgumentException.class,
                                                     () -> emailMcpTool.cancelScheduledEmail("held", "Proposal", List.of("bob@acme.com")));
    assertTrue(undoWait.getMessage().contains("Undo delay"), undoWait.getMessage());
    verify(emailScheduledSendService, never()).cancel(eq("held"), anyString(), any());


    cancelRuns(current);
    String answer = emailMcpTool.cancelScheduledEmail(DRAFT_ID, "Proposal", List.of("bob@acme.com"));
    verify(emailScheduledSendService).cancel(eq(DRAFT_ID), eq(USERNAME), any());
    verify(emailScheduledSendService, never()).cancel(anyString(), anyString());
    assertTrue(answer.contains("back in your drafts") && !answer.contains("may already have reached"), answer);
    ScheduledEmail uncertain = scheduled("Proposal", current.getScheduledDate(), "Europe/Paris", "bob@acme.com");
    uncertain.setStatus(ScheduledSendStatus.UNCERTAIN);
    when(emailScheduledSendService.getScheduledEmail(DRAFT_ID, USERNAME)).thenReturn(uncertain);
    String uncertainAnswer = emailMcpTool.cancelScheduledEmail(DRAFT_ID, "Proposal", List.of("bob@acme.com"));
    assertTrue(uncertainAnswer.contains("may already have reached its recipients"), uncertainAnswer);
    when(emailScheduledSendService.getScheduledEmail(DRAFT_ID, USERNAME)).thenReturn(current);

    cancelRuns(scheduled("Changed in the Email app", current.getScheduledDate(), "Europe/Paris", "bob@acme.com"));
    IllegalArgumentException edited = assertThrows(IllegalArgumentException.class,
                                                   () -> emailMcpTool.cancelScheduledEmail(DRAFT_ID, "Proposal", List.of("bob@acme.com")));
    assertTrue(edited.getMessage().startsWith("Nothing was cancelled"), edited.getMessage());

    when(emailScheduledSendService.cancel(eq(DRAFT_ID), eq(USERNAME), any()))
                                                                          .thenThrow(new ScheduledSendConflictException(ScheduledSendConflictException.SENDING));
    IllegalArgumentException sending = assertThrows(IllegalArgumentException.class,
                                                    () -> emailMcpTool.cancelScheduledEmail(DRAFT_ID, "Proposal", List.of("bob@acme.com")));
    assertTrue(sending.getMessage().contains("is being sent or was sent"), sending.getMessage());

    when(emailScheduledSendService.cancel(eq(DRAFT_ID), eq(USERNAME), any()))
                                                                          .thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
    IllegalArgumentException held = assertThrows(IllegalArgumentException.class,
                                                 () -> emailMcpTool.cancelScheduledEmail(DRAFT_ID, "Proposal", List.of("bob@acme.com")));
    assertTrue(held.getMessage().contains("is being changed in the Email app"), held.getMessage());
    when(emailScheduledSendService.reschedule(eq(DRAFT_ID), anyLong(), anyString(), eq(USERNAME), any()))
                                                                                                       .thenThrow(new CannotAcquireLockException("Lock wait timeout exceeded"));
    assertThrows(IllegalArgumentException.class,
                 () -> emailMcpTool.rescheduleEmail(DRAFT_ID, "2026-12-04T09:00", null, "Proposal", List.of("bob@acme.com")));

    when(emailScheduledSendService.getScheduledEmail("gone", USERNAME)).thenThrow(new ObjectNotFoundException("gone"));
    ObjectNotFoundException notFound = assertThrows(ObjectNotFoundException.class,
                                                    () -> emailMcpTool.cancelScheduledEmail("gone", "Proposal", List.of("bob@acme.com")));
    assertTrue(notFound.getMessage().contains("No scheduled mail with draft_id gone"), notFound.getMessage());
  }
}
