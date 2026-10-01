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
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import org.exoplatform.emailConnector.model.CalendarInvitation;
import org.exoplatform.emailConnector.model.CalendarLanding;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.InvitationLanding;
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

  @Mock
  private ApplicationContext             applicationContext;

  @Mock
  private InvitationCalendarPlugin       first;

  @Mock
  private InvitationCalendarPlugin       second;

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
    when(first.land(LANDING)).thenReturn(null);
    when(second.land(LANDING)).thenReturn(new LandedInvitation(77L, "/portal/dw/agenda?eventId=77", false));
    CalendarInvitation invitation = land(new InvitationLandingService(applicationContext));
    assertEquals(CalendarLanding.LANDED, invitation.getLanding());
    assertEquals("/portal/dw/agenda?eventId=77", invitation.getLandingLink());

    when(first.land(LANDING)).thenReturn(new LandedInvitation(78L, null, true));
    invitation = land(new InvitationLandingService(applicationContext));
    assertEquals(CalendarLanding.REMOVED, invitation.getLanding());
    assertNull(invitation.getLandingLink());
    verify(second).land(LANDING);

    when(first.land(LANDING)).thenReturn(null);
    when(second.land(LANDING)).thenReturn(null);
    assertNull(land(new InvitationLandingService(applicationContext)).getLanding());

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
    when(first.land(LANDING)).thenThrow(new IllegalStateException("the server refused"));
    assertEquals(CalendarLanding.FAILED, land(new InvitationLandingService(applicationContext)).getLanding());
    verify(second, never()).land(any());

    doThrow(new IllegalArgumentException("one occurrence only")).when(first).land(LANDING);
    assertEquals(CalendarLanding.REFUSED, land(new InvitationLandingService(applicationContext)).getLanding());
    verify(second, never()).land(any());

    doThrow(new NoClassDefFoundError("net/fortuna/ical4j/model/Calendar")).when(first).land(LANDING);
    when(second.land(LANDING)).thenReturn(new LandedInvitation(77L, null, false));
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
    service.land(LANDING, invitation);
    return invitation;
  }
}
