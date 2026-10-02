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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import org.exoplatform.emailConnector.model.CalendarInvitation;
import org.exoplatform.emailConnector.model.CalendarLanding;
import org.exoplatform.emailConnector.model.HeldInvitation;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.emailConnector.model.InvitationProbe;
import org.exoplatform.emailConnector.model.LandedInvitation;
import org.exoplatform.emailConnector.plugin.InvitationCalendarPlugin;

/**
 * The add-ons that land an invitation in the user's calendar are found by type at the
 * moment of the click, asked in turn, and read as "no calendar" on everything but a
 * landing, a refusal or an attempt that failed (EXO-90848).
 */
@ExtendWith(MockitoExtension.class)
class InvitationLandingServiceTest {

  private static final InvitationLanding LANDING = new InvitationLanding("john",
                                                                         "john@acme.com",
                                                                         "request",
                                                                         "weekly-sync@google.com",
                                                                         null,
                                                                         2,
                                                                         InvitationAnswer.ACCEPTED,
                                                                         "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");

  /** The question the reader asks when it shows the invitation (EXO-90873). */
  private static final InvitationProbe   PROBE   = new InvitationProbe("john", "john@acme.com", "weekly-sync@google.com", null, "olivia@partner.example");

  @Mock
  private ApplicationContext             applicationContext;

  @Mock
  private InvitationCalendarPlugin       first;

  @Mock
  private InvitationCalendarPlugin       second;

  /**
   * Clears the wait a test may have set.
   */
  @AfterEach
  void clearTheTimeout() {
    System.clearProperty(InvitationLandingService.HELD_TIMEOUT_PROPERTY);
  }

  /**
   * No implementer, or none that can be listed: the answer lands nowhere, no calendar is
   * offered, and the reader says nothing of it.
   */
  @Test
  void withoutAnImplementerNothingLands() {
    assertNull(land(new InvitationLandingService(null)).getLanding());
    assertFalse(new InvitationLandingService(null).holdsCalendarFor("john"));

    when(applicationContext.getBeansOfType(InvitationCalendarPlugin.class)).thenReturn(Map.of());
    assertNull(land(new InvitationLandingService(applicationContext)).getLanding());

    when(applicationContext.getBeansOfType(InvitationCalendarPlugin.class)).thenThrow(new NoClassDefFoundError("gone"));
    assertNull(land(new InvitationLandingService(applicationContext)).getLanding());
    assertFalse(new InvitationLandingService(applicationContext).holdsCalendarFor("john"));
  }

  /**
   * The implementers are asked in turn until one holds the user's calendar: the one
   * after it is not asked, and none landing it reads as nothing done. The one holding a
   * calendar is also what makes the reader offer it.
   */
  @Test
  void theFirstImplementerHoldingTheUsersCalendarLandsIt() {
    givenThePlugins();
    when(first.holdsCalendarFor("john")).thenReturn(false);
    when(second.holdsCalendarFor("john")).thenReturn(true);
    when(second.land(LANDING)).thenReturn(new LandedInvitation(77L, "/portal/dw/agenda?eventId=77", false, false));
    CalendarInvitation invitation = land(new InvitationLandingService(applicationContext));
    assertEquals(CalendarLanding.LANDED, invitation.getLanding());
    assertEquals("/portal/dw/agenda?eventId=77", invitation.getLandingLink());
    assertFalse(invitation.isLandable(), "the click was honoured");
    verify(first, never()).land(any());

    when(first.holdsCalendarFor("john")).thenReturn(true);
    when(first.land(LANDING)).thenReturn(new LandedInvitation(78L, null, true, false));
    invitation = land(new InvitationLandingService(applicationContext));
    assertEquals(CalendarLanding.REMOVED, invitation.getLanding());
    assertNull(invitation.getLandingLink());
    assertFalse(invitation.isRemovable(), "the click was honoured");
    verify(second, times(1)).land(LANDING);

    // A decline on a copy the user held: the calendar shows a declined event,
    // which is not an addition.
    InvitationLanding declined = new InvitationLanding("john", "john@acme.com", "REQUEST", "weekly-sync@google.com", null, 2,
                                                       InvitationAnswer.DECLINED, "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");
    when(first.land(declined)).thenReturn(new LandedInvitation(77L, "/portal/dw/agenda?eventId=77", false, false));
    CalendarInvitation held = new CalendarInvitation();
    new InvitationLandingService(applicationContext).land(declined, held);
    assertEquals(CalendarLanding.DECLINED, held.getLanding());

    when(first.land(LANDING)).thenReturn(new LandedInvitation(79L, "/portal/dw/agenda?eventId=79", false, true));
    invitation = land(new InvitationLandingService(applicationContext));
    assertEquals(CalendarLanding.ALREADY_HELD, invitation.getLanding());
    assertEquals("/portal/dw/agenda?eventId=79", invitation.getLandingLink());
    assertFalse(invitation.isLandable());

    // The one holding the calendar answers nothing: nothing to do, and the
    // next add-on is not asked.
    when(first.land(LANDING)).thenReturn(null);
    assertNull(land(new InvitationLandingService(applicationContext)).getLanding());
    verify(second, times(1)).land(LANDING);

    when(first.holdsCalendarFor("john")).thenReturn(false);
    when(second.holdsCalendarFor("john")).thenReturn(true);
    assertTrue(new InvitationLandingService(applicationContext).holdsCalendarFor("john"));
    when(second.holdsCalendarFor("john")).thenReturn(false);
    assertFalse(new InvitationLandingService(applicationContext).holdsCalendarFor("john"));
    doThrow(new IllegalStateException("down")).when(first).holdsCalendarFor("john");
    assertFalse(new InvitationLandingService(applicationContext).holdsCalendarFor("john"));
  }

  /**
   * An implementer that tried and failed, and one that refused the invitation as it
   * is, are the two outcomes the user is told of -- told apart by the exception; one
   * whose classes cannot be linked is as if it were not installed.
   */
  @Test
  void anAttemptThatFailedIsToldAndAMissingLibraryIsNot() {
    givenThePlugins();
    when(first.holdsCalendarFor("john")).thenReturn(true);
    lenient().when(second.holdsCalendarFor("john")).thenReturn(true);
    when(first.land(LANDING)).thenThrow(new IllegalStateException("the server refused"));
    assertEquals(CalendarLanding.FAILED, land(new InvitationLandingService(applicationContext)).getLanding());
    verify(second, never()).land(any());

    doThrow(new IllegalArgumentException("one occurrence only")).when(first).land(LANDING);
    assertEquals(CalendarLanding.REFUSED, land(new InvitationLandingService(applicationContext)).getLanding());
    verify(second, never()).land(any());

    doThrow(new NoClassDefFoundError("net/fortuna/ical4j/model/Calendar")).when(first).land(LANDING);
    when(second.land(LANDING)).thenReturn(new LandedInvitation(77L, null, false, false));
    assertEquals(CalendarLanding.LANDED, land(new InvitationLandingService(applicationContext)).getLanding());
  }

  /**
   * A landing names the user, the UID and the object; the answer is optional, and the
   * method is read as written, upper-cased.
   */
  @Test
  void aLandingIsComplete() {
    assertThrows(IllegalArgumentException.class,
                 () -> new InvitationLanding(" ", "john@acme.com", null, "uid", null, 0, InvitationAnswer.ACCEPTED, "BEGIN:VCALENDAR"));
    assertThrows(IllegalArgumentException.class,
                 () -> new InvitationLanding("john", "john@acme.com", null, "", null, 0, InvitationAnswer.ACCEPTED, "BEGIN:VCALENDAR"));
    assertThrows(IllegalArgumentException.class,
                 () -> new InvitationLanding("john", "john@acme.com", null, "uid", null, 0, InvitationAnswer.ACCEPTED, null));
    InvitationLanding added = new InvitationLanding("john", null, " cancel ", "uid", null, 0, null, "BEGIN:VCALENDAR");
    assertNull(added.answer(), "added or removed without an answer");
    assertNull(added.attendeeAddress(), "the address is the implementer's to check");
    assertEquals("CANCEL", added.method());
    assertEquals("REQUEST", LANDING.method());
  }

  /**
   * Whether the calendar holds an invitation is asked of the add-on holding the user's
   * calendar, alone: the one before it, which holds none, is not asked, and the one
   * after it neither (EXO-90873).
   */
  @Test
  void heldIsAskedOfTheImplementerHoldingTheUsersCalendarAlone() {
    givenThePlugins();
    when(first.holdsCalendarFor("john")).thenReturn(false);
    when(second.holdsCalendarFor("john")).thenReturn(true);
    HeldInvitation copy = new HeldInvitation(77L, "/portal/dw/agenda?eventId=77", InvitationAnswer.DECLINED, 3);
    when(second.held(PROBE)).thenReturn(copy);

    assertEquals(copy, new InvitationLandingService(applicationContext).held(PROBE));
    verify(first, never()).held(any());

    // The one holding it says nothing is held: nothing is, and no other is asked.
    when(first.holdsCalendarFor("john")).thenReturn(true);
    when(first.held(PROBE)).thenReturn(null);
    assertNull(new InvitationLandingService(applicationContext).held(PROBE));
    verify(second, times(1)).held(PROBE);
  }

  /**
   * An implementer written before the question existed answers nothing held, and so
   * does a platform with no implementer at all.
   */
  @Test
  void anImplementerWithoutTheQuestionHoldsNothing() {
    InvitationCalendarPlugin older = new InvitationCalendarPlugin() {
      @Override
      public boolean holdsCalendarFor(String username) {
        return true;
      }

      @Override
      public LandedInvitation land(InvitationLanding landing) {
        return null;
      }
    };
    when(applicationContext.getBeansOfType(InvitationCalendarPlugin.class)).thenReturn(Map.of("older", older));
    assertNull(new InvitationLandingService(applicationContext).held(PROBE));
    assertNull(new InvitationLandingService(null).held(PROBE));
  }

  /**
   * The reader never waits long for the calendar: an add-on that does not answer in
   * time, one that fails, one that cannot be linked, and an executor with no room all
   * read as nothing held -- the card shows the invitation as it did before.
   *
   * @throws Exception never
   */
  @Test
  void heldFailsOpenOnALateOrFailedAnswer() throws Exception {
    givenThePlugins();
    when(first.holdsCalendarFor("john")).thenReturn(true);
    CountDownLatch never = new CountDownLatch(1);
    when(first.held(PROBE)).thenAnswer(call -> {
      never.await(10, TimeUnit.SECONDS);
      return new HeldInvitation(77L, null, InvitationAnswer.ACCEPTED, 2);
    });
    System.setProperty(InvitationLandingService.HELD_TIMEOUT_PROPERTY, "200");
    InvitationLandingService service = new InvitationLandingService(applicationContext);
    long started = System.nanoTime();
    assertNull(service.held(PROBE), "too late");
    assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 5000, "the reader waited for the timeout only");
    never.countDown();

    doThrow(new IllegalStateException("the calendar server is down")).when(first).held(PROBE);
    assertNull(service.held(PROBE), "failed");

    // An add-on whose classes cannot be linked is as if it were not installed: the
    // next one holding the calendar answers.
    doThrow(new NoClassDefFoundError("gone")).when(first).holdsCalendarFor("john");
    when(second.holdsCalendarFor("john")).thenReturn(true);
    HeldInvitation copy = new HeldInvitation(78L, null, null, 0);
    when(second.held(PROBE)).thenReturn(copy);
    assertEquals(copy, service.held(PROBE));

    ExecutorService full = Executors.newSingleThreadExecutor();
    full.shutdown();
    service.setHeldExecutor(full);
    assertNull(service.held(PROBE), "no room");
    service.stop();
  }

  /**
   * The wait is configurable, positive and bounded; anything else is the default.
   */
  @Test
  void theWaitIsConfigurable() {
    assertEquals(InvitationLandingService.DEFAULT_HELD_TIMEOUT, InvitationLandingService.heldTimeout());
    System.setProperty(InvitationLandingService.HELD_TIMEOUT_PROPERTY, " 1500 ");
    assertEquals(1500L, InvitationLandingService.heldTimeout());
    System.setProperty(InvitationLandingService.HELD_TIMEOUT_PROPERTY, "0");
    assertEquals(InvitationLandingService.DEFAULT_HELD_TIMEOUT, InvitationLandingService.heldTimeout());
    System.setProperty(InvitationLandingService.HELD_TIMEOUT_PROPERTY, "soon");
    assertEquals(InvitationLandingService.DEFAULT_HELD_TIMEOUT, InvitationLandingService.heldTimeout());
    System.setProperty(InvitationLandingService.HELD_TIMEOUT_PROPERTY, String.valueOf(Long.MAX_VALUE));
    assertEquals(InvitationLandingService.MAX_HELD_TIMEOUT, InvitationLandingService.heldTimeout());
  }

  /**
   * After a click, the card says what the calendar holds: the copy just landed, with the
   * answer given -- or the one held before, for an addition without one -- and nothing
   * once removed; an event of the platform's own, for which nothing was written, changes
   * nothing.
   */
  @Test
  void aLandingSaysWhatTheCalendarHoldsAfterIt() {
    givenThePlugins();
    when(first.holdsCalendarFor("john")).thenReturn(true);
    when(first.land(LANDING)).thenReturn(new LandedInvitation(77L, "/portal/dw/agenda?eventId=77", false, false));
    CalendarInvitation invitation = new CalendarInvitation();
    invitation.setNewerRevision(true);
    new InvitationLandingService(applicationContext).land(LANDING, invitation);
    assertTrue(invitation.isHeld());
    assertEquals("/portal/dw/agenda?eventId=77", invitation.getHeldLink());
    assertEquals(InvitationAnswer.ACCEPTED, invitation.getHeldResponse());
    assertFalse(invitation.isNewerRevision(), "the revision is the one held now");

    InvitationLanding added = new InvitationLanding("john", "john@acme.com", "REQUEST", "weekly-sync@google.com", null, 2, null,
                                                    "BEGIN:VCALENDAR\r\nEND:VCALENDAR\r\n");
    when(first.land(added)).thenReturn(new LandedInvitation(77L, "/portal/dw/agenda?eventId=77", false, false));
    invitation = new CalendarInvitation();
    invitation.setHeldResponse(InvitationAnswer.TENTATIVE);
    new InvitationLandingService(applicationContext).land(added, invitation);
    assertTrue(invitation.isHeld());
    assertEquals(InvitationAnswer.TENTATIVE, invitation.getHeldResponse(), "an addition keeps the answer held");

    when(first.land(added)).thenReturn(new LandedInvitation(77L, null, true, false));
    invitation.setHeldLink("/portal/dw/agenda?eventId=77");
    new InvitationLandingService(applicationContext).land(added, invitation);
    assertFalse(invitation.isHeld());
    assertNull(invitation.getHeldLink());
    assertNull(invitation.getHeldResponse());

    when(first.land(added)).thenReturn(new LandedInvitation(79L, "/portal/dw/agenda?eventId=79", false, true));
    invitation = new CalendarInvitation();
    new InvitationLandingService(applicationContext).land(added, invitation);
    assertFalse(invitation.isHeld(), "an eXo meeting is told as already held by its landing, not as a copy");
  }

  /**
   * A question names the user and the UID; the occurrence and the organiser are read trimmed.
   */
  @Test
  void aProbeIsComplete() {
    assertThrows(IllegalArgumentException.class, () -> new InvitationProbe(" ", "john@acme.com", "uid", null, null));
    assertThrows(IllegalArgumentException.class, () -> new InvitationProbe("john", "john@acme.com", "", null, null));
    assertNull(new InvitationProbe("john", null, "uid", "  ", null).recurrenceId());
    assertNull(new InvitationProbe("john", null, "uid", null, " ").organizer(), "a published event names none");
  }

  /**
   * Two plugins, in that order.
   */
  private void givenThePlugins() {
    Map<String, InvitationCalendarPlugin> plugins = new LinkedHashMap<>();
    plugins.put("first", first);
    plugins.put("second", second);
    when(applicationContext.getBeansOfType(InvitationCalendarPlugin.class)).thenReturn(plugins);
  }

  /**
   * The landing of the test's invitation, told to a fresh invitation.
   *
   * @param service the service under test
   * @return the invitation, told the outcome
   */
  private static CalendarInvitation land(InvitationLandingService service) {
    CalendarInvitation invitation = new CalendarInvitation();
    invitation.setLandable(true);
    invitation.setRemovable(true);
    service.land(LANDING, invitation);
    return invitation;
  }
}
