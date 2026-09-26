/**
 * Copyright (C) 2026 eXo Platform SAS.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License
 * as published by the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <gnu.org/licenses>.
 */
package org.exoplatform.emailConnector.listener;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import org.exoplatform.emailConnector.event.NewInboxMailEvent;
import org.exoplatform.emailConnector.service.EmailFilterService;
import org.exoplatform.emailConnector.service.filters.FilterRunContext;

/**
 * The glue between the sync and the filters: it hands the new mail over and the filed
 * UIDs back, synchronously, and is wired by its annotation -- a test calling the method
 * by hand cannot see the annotation-removed mutant, so the wiring is asserted too.
 */
@ExtendWith(MockitoExtension.class)
public class EmailFilterSyncListenerTest {

  @Mock
  private EmailFilterService      emailFilterService;

  @InjectMocks
  private EmailFilterSyncListener listener;

  /**
   * The filed UIDs the service answers are handed back on the event.
   */
  @Test
  void theFiledMailComesBackOnTheEvent() {
    List<NewInboxMailEvent.InboxMail> mails = List.of(new NewInboxMailEvent.InboxMail(1L, Set.of(), name -> null, () -> null));
    NewInboxMailEvent event = new NewInboxMailEvent("alice", FilterRunContext.OWN_INBOX, mails);
    when(emailFilterService.applyToNewMail("alice", mails, FilterRunContext.OWN_INBOX)).thenReturn(Set.of(1L));

    listener.onNewInboxMail(event);

    assertEquals(Set.of(1L), event.getFiledUids());
  }

  /**
   * Pins the wiring: removing {@code @EventListener} leaves the filters silently
   * unreached, and an {@code @Async} one would answer after the announcement.
   *
   * @throws Exception never
   */
  @Test
  void theListenerIsWiredAndSynchronous() throws Exception {
    var method = EmailFilterSyncListener.class.getMethod("onNewInboxMail", NewInboxMailEvent.class);
    assertTrue(method.isAnnotationPresent(EventListener.class));
    assertFalse(method.isAnnotationPresent(Async.class));
    assertFalse(EmailFilterSyncListener.class.isAnnotationPresent(Async.class));
  }
}
