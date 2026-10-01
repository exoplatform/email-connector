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

import org.exoplatform.emailConnector.model.CalendarLanding;
import org.exoplatform.emailConnector.model.InvitationAnswer;
import org.exoplatform.emailConnector.model.InvitationLanding;
import org.exoplatform.emailConnector.plugin.InvitationCalendarPlugin;

/**
 * The add-ons that land an answered invitation in the user's calendar are found by
 * type at the moment of the answer, asked in turn, and read as "no calendar" on
 * everything but a landing or an attempt that failed (EXO-90848).
 */
@ExtendWith(MockitoExtension.class)
class InvitationLandingServiceTest {

  private static final InvitationLanding LANDING = new InvitationLanding("john",
                                                                         "john@acme.com",
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
   * No implementer, or none that can be listed: the answer lands nowhere and the reader
   * says nothing of it.
   */
  @Test
  void withoutAnImplementerNothingLands() {
    assertNull(new InvitationLandingService(null).land(LANDING));

    when(applicationContext.getBeansOfType(InvitationCalendarPlugin.class)).thenReturn(Map.of());
    assertNull(new InvitationLandingService(applicationContext).land(LANDING));

    when(applicationContext.getBeansOfType(InvitationCalendarPlugin.class)).thenThrow(new NoClassDefFoundError("gone"));
    assertNull(new InvitationLandingService(applicationContext).land(LANDING));
  }

  /**
   * The implementers are asked in turn until one holds the user's calendar: the one
   * after it is not asked, and none landing it reads as no calendar.
   */
  @Test
  void theFirstImplementerHoldingTheUsersCalendarLandsIt() {
    givenThePlugins();
    when(first.land(LANDING)).thenReturn(false);
    when(second.land(LANDING)).thenReturn(true);
    assertEquals(CalendarLanding.LANDED, new InvitationLandingService(applicationContext).land(LANDING));

    when(first.land(LANDING)).thenReturn(true);
    assertEquals(CalendarLanding.LANDED, new InvitationLandingService(applicationContext).land(LANDING));
    verify(second).land(LANDING);

    when(first.land(LANDING)).thenReturn(false);
    when(second.land(LANDING)).thenReturn(false);
    assertNull(new InvitationLandingService(applicationContext).land(LANDING));
  }

  /**
   * An implementer that tried and failed is the one outcome the user is told of; one
   * whose classes cannot be linked is as if it were not installed.
   */
  @Test
  void anAttemptThatFailedIsToldAndAMissingLibraryIsNot() {
    givenThePlugins();
    when(first.land(LANDING)).thenThrow(new IllegalStateException("the server refused"));
    assertEquals(CalendarLanding.FAILED, new InvitationLandingService(applicationContext).land(LANDING));
    verify(second, never()).land(any());

    doThrow(new NoClassDefFoundError("net/fortuna/ical4j/model/Calendar")).when(first).land(LANDING);
    when(second.land(LANDING)).thenReturn(true);
    assertEquals(CalendarLanding.LANDED, new InvitationLandingService(applicationContext).land(LANDING));
  }

  /**
   * A landing names the user, the UID, the answer and the object, or it is no landing.
   */
  @Test
  void aLandingIsComplete() {
    assertThrows(IllegalArgumentException.class,
                 () -> new InvitationLanding(" ", "john@acme.com", "uid", null, 0, InvitationAnswer.ACCEPTED, "BEGIN:VCALENDAR"));
    assertThrows(IllegalArgumentException.class,
                 () -> new InvitationLanding("john", "john@acme.com", "", null, 0, InvitationAnswer.ACCEPTED, "BEGIN:VCALENDAR"));
    assertThrows(IllegalArgumentException.class,
                 () -> new InvitationLanding("john", "john@acme.com", "uid", null, 0, null, "BEGIN:VCALENDAR"));
    assertThrows(IllegalArgumentException.class,
                 () -> new InvitationLanding("john", "john@acme.com", "uid", null, 0, InvitationAnswer.ACCEPTED, null));
    assertNull(new InvitationLanding("john", null, "uid", null, 0, InvitationAnswer.ACCEPTED, "BEGIN:VCALENDAR").attendeeAddress(),
               "the address is the implementer's to check");
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
}
