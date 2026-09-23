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

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import org.exoplatform.emailConnector.service.EmailManagedEnrollmentService;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.Listener;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.security.ConversationRegistry;
import org.exoplatform.services.security.ConversationState;
import org.exoplatform.services.security.Identity;

import jakarta.annotation.PostConstruct;

/**
 * Hands each login to the managed-mode enrolment (EXO-89653).
 * <p>
 * Bound to {@code exo.core.security.ConversationRegistry.register}, the event the
 * platform raises when a session gets its identity - at authentication, on a
 * remembered session, or the first time an authenticated request reaches the portal
 * filters - and only when the user holds no other registered session on this node
 * ({@code ConversationRegistry.register}: {@code getStateKeys(userId).isEmpty()}). So
 * at login, and again each time the platform registers the session anew: the
 * registry keeps its states in caches that expire an hour after the registration
 * ({@code exo.cache.portal.ConversationRegistry.TimeToLive}, 3600 by default), and
 * the next request of a still-active session registers it again. A user who
 * disconnects their mailbox is therefore attached again within the hour, or at their
 * next login - the same rule either way; a user the server refuses is retried at the
 * same pace. Never per request. Nothing
 * here needs to be cheap beyond "queue and return": the enrolment itself runs on the
 * service's own executor, and this listener is deliberately <b>not</b> {@code @Asynchronous} - the kernel's
 * asynchronous listener pool is one thread shared by every asynchronous listener of
 * the platform, and a mail server that does not answer must not hold it.
 * <p>
 * Glue only: the three rules live in {@link EmailManagedEnrollmentService}.
 */
@Component
public class EmailManagedLoginListener extends Listener<ConversationRegistry, ConversationState> {

  public static final String            LOGIN_EVENT = "exo.core.security.ConversationRegistry.register";

  @Autowired
  private ListenerService               listenerService;

  @Autowired
  private EmailManagedEnrollmentService enrollmentService;

  @PostConstruct
  public void init() {
    listenerService.addListener(LOGIN_EVENT, this);
  }

  @Override
  public void onEvent(Event<ConversationRegistry, ConversationState> event) {
    String username = usernameOf(event);
    if (username != null) {
      enrollmentService.scheduleEnrollment(username);
    }
  }

  /**
   * The login the registered state belongs to, or null when the event carries no
   * identity - nobody is attached on nobody's behalf.
   *
   * @param event the registration event
   * @return the eXo login, or null
   */
  private String usernameOf(Event<ConversationRegistry, ConversationState> event) {
    ConversationState state = event == null ? null : event.getData();
    Identity identity = state == null ? null : state.getIdentity();
    return identity == null ? null : identity.getUserId();
  }
}
