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
package org.exoplatform.emailConnector.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Only this deployment's own Agenda events are recognised (EXO-90840): Agenda's link, on
 * this deployment's authority, whatever the UID -- and, when the UID is Agenda's, both
 * agreeing on the deployment and the event.
 */
class AgendaEventLinksTest {

  private static final String OWN  = "https://exo.example.test";

  private static final String UID  = "agenda-event-42@exo.example.test";

  private static final String LINK = "https://exo.example.test/portal/dw/agenda?eventId=42";

  /** The UID a CalDAV copy or a calendar server's answer carries: its own, random. */
  private static final String RANDOM_UID = "3f1c0b52-9d0e-4f7e-8b5e-4f2e0a8d1c11";

  /**
   * An event this deployment mailed is recognised, and its link is rebuilt from the
   * deployment's own domain, whatever the case the sender wrote it in.
   */
  @Test
  void thisDeploymentsAgendaEventIsRecognised() {
    assertEquals(LINK, AgendaEventLinks.localAgendaLink(UID, LINK, OWN));
    assertEquals(LINK, AgendaEventLinks.localAgendaLink(UID, LINK, OWN + "/"));
    assertEquals(LINK, AgendaEventLinks.localAgendaLink("AGENDA-EVENT-42@EXO.example.test", "HTTPS://EXO.EXAMPLE.TEST/portal/dw/agenda?eventId=42", OWN));
    assertEquals("http://localhost:8080/portal/dw/agenda?eventId=7",
                 AgendaEventLinks.localAgendaLink("agenda-event-7@localhost",
                                                  "http://localhost:8080/portal/dw/agenda?eventId=7",
                                                  "http://localhost:8080"),
                 "the UID names the host without its port, as Agenda writes it");
  }

  /**
   * A copy of the event -- a CalDAV copy, a calendar server's answer about it -- keeps the
   * link and has a UID of its own: the link alone recognises it, on this deployment's
   * authority only.
   */
  @Test
  void aCopyIsRecognisedByItsLinkAlone() {
    assertEquals(LINK, AgendaEventLinks.localAgendaLink(RANDOM_UID, LINK, OWN));
    assertEquals(LINK, AgendaEventLinks.localAgendaLink(null, LINK, OWN), "no UID at all");
    assertEquals("http://localhost:8080/portal/dw/agenda?eventId=168",
                 AgendaEventLinks.localAgendaLink(RANDOM_UID, "http://localhost:8080/portal/dw/agenda?eventId=168", "http://localhost:8080"));
    for (String foreign : java.util.List.of("https://other.example.test/portal/dw/agenda?eventId=42",
                                            "https://exo.example.test:8443/portal/dw/agenda?eventId=42",
                                            "http://localhost:8081/portal/dw/agenda?eventId=42",
                                            "exo.example.test/portal/dw/agenda?eventId=42",
                                            "javascript://exo.example.test/portal/dw/agenda?eventId=42",
                                            "https://user@exo.example.test/portal/dw/agenda?eventId=42",
                                            "https://exo.example.test@evil.example.test/portal/dw/agenda?eventId=42",
                                            LINK + "#x",
                                            LINK + "&x=1",
                                            "https://exo.example.test/portal/d%22w/agenda?eventId=42",
                                            "https://exo.example.test/portal/dw/agenda?eventId=",
                                            " ")) {
      assertNull(AgendaEventLinks.localAgendaLink(RANDOM_UID, foreign, OWN), foreign);
    }
    assertNull(AgendaEventLinks.localAgendaLink(RANDOM_UID, null, OWN), "no URL");
  }

  /**
   * Another eXo's event, a forged UID with a link elsewhere, a link on another port of
   * the same host, and Agenda's UID naming another deployment or event than the link are
   * not recognised.
   */
  @Test
  void anotherDeploymentsOrAForgedEventIsNotRecognised() {
    assertNull(AgendaEventLinks.localAgendaLink("agenda-event-42@other.example.test",
                                                "https://other.example.test/portal/dw/agenda?eventId=42",
                                                OWN),
               "another eXo's event");
    assertNull(AgendaEventLinks.localAgendaLink(UID, "https://evil.example.test/portal/dw/agenda?eventId=42", OWN),
               "this deployment's UID with a link elsewhere");
    assertNull(AgendaEventLinks.localAgendaLink("agenda-event-42@other.example.test", LINK, OWN),
               "another deployment's UID with this deployment's link");
    assertNull(AgendaEventLinks.localAgendaLink(UID, "https://exo.example.test:8443/portal/dw/agenda?eventId=42", OWN),
               "another port is another deployment");
    assertNull(AgendaEventLinks.localAgendaLink("agenda-event-43@exo.example.test", LINK, OWN), "another event");
  }

  /**
   * Only the whole link, of Agenda's shape and an http(s) scheme, counts: no missing
   * link, no link without a scheme, no extra text, no odd site name, and nothing without
   * this deployment's own domain.
   */
  @Test
  void onlyAgendasWholeLinkCounts() {
    assertNull(AgendaEventLinks.localAgendaLink(UID, null, OWN), "no URL");
    assertNull(AgendaEventLinks.localAgendaLink(UID, " ", OWN));
    assertNull(AgendaEventLinks.localAgendaLink(UID, "exo.example.test/portal/dw/agenda?eventId=42", OWN), "no scheme");
    assertNull(AgendaEventLinks.localAgendaLink(UID, "javascript://exo.example.test/portal/dw/agenda?eventId=42", OWN));
    assertNull(AgendaEventLinks.localAgendaLink(UID, LINK + "&x=1", OWN), "extra parameters");
    assertNull(AgendaEventLinks.localAgendaLink(UID, LINK + "#x", OWN));
    assertNull(AgendaEventLinks.localAgendaLink(UID, "https://exo.example.test/portal/d%22w/agenda?eventId=42", OWN));
    assertNull(AgendaEventLinks.localAgendaLink(UID, "https://user@exo.example.test/portal/dw/agenda?eventId=42", OWN));
    assertNull(AgendaEventLinks.localAgendaLink(UID, LINK, null), "this deployment's domain unknown");
  }

  /**
   * An address's authority, with or without a scheme or a path.
   */
  @Test
  void theAuthorityIsHostAndPort() {
    assertEquals("localhost:8080", AgendaEventLinks.authorityOf("http://localhost:8080/"));
    assertEquals("exo.example.test", AgendaEventLinks.authorityOf("https://EXO.example.test"));
    assertEquals("exo.example.test", AgendaEventLinks.authorityOf("exo.example.test/portal"));
    assertNull(AgendaEventLinks.authorityOf(" "));
  }
}
