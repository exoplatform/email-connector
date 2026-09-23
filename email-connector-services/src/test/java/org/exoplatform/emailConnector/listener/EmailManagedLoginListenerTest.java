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
package org.exoplatform.emailConnector.listener;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.service.EmailManagedEnrollmentService;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.security.ConversationRegistry;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

/**
 * EXO-89653. The login listener is glue: it binds itself to the session-registration
 * event, hands the registered login to the enrolment service, and hands nothing when
 * there is no login to enrol.
 */
@ExtendWith(MockitoExtension.class)
class EmailManagedLoginListenerTest {

  @Mock
  private ListenerService               listenerService;

  @Mock
  private EmailManagedEnrollmentService enrollmentService;

  @Mock
  private ConversationRegistry          registry;

  @InjectMocks
  private EmailManagedLoginListener     listener;

  private Event<ConversationRegistry, ConversationState> loginOf(String username) {
    ConversationState state = username == null ? null : new ConversationState(new Identity(username, List.of()));
    return new Event<>(EmailManagedLoginListener.LOGIN_EVENT, registry, state);
  }

  /** A listener nobody binds enrols nobody: the binding is the event the platform raises at login. */
  @Test
  void bindsItselfToTheSessionRegistrationEvent() {
    listener.init();

    verify(listenerService).addListener("exo.core.security.ConversationRegistry.register", listener);
  }

  @Test
  void handsTheLoginToTheEnrolment() {
    when(enrollmentService.scheduleEnrollment("mary")).thenReturn(true);

    listener.onEvent(loginOf("mary"));

    verify(enrollmentService).scheduleEnrollment("mary");
  }

  @Test
  void ignoresAnEventWithNoIdentity() {
    assertDoesNotThrow(() -> listener.onEvent(loginOf(null)));
    assertDoesNotThrow(() -> listener.onEvent(null));

    verifyNoInteractions(enrollmentService);
  }
}
