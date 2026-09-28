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

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.model.EmailCategoryAdded;
import org.exoplatform.emailConnector.service.EmailFilterService;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.Asynchronous;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.ListenerService;

/**
 * The glue between a category added to inbox mail and the filters on it: it hands the
 * mails over, registers itself on the event, and runs off the thread that added the
 * category -- the registration and the annotation are asserted, a test calling
 * {@code onEvent} by hand seeing neither.
 */
@ExtendWith(MockitoExtension.class)
public class EmailFilterCategoryListenerTest {

  @Mock
  private EmailFilterService          emailFilterService;

  @Mock
  private ListenerService             listenerService;

  @InjectMocks
  private EmailFilterCategoryListener listener;

  /**
   * The owner, the category and the mails go to the filters.
   */
  @Test
  void theCategorisedMailGoesToTheFilters() {
    listener.onEvent(new Event<>(EmailConnectorUtils.EMAIL_CATEGORY_ADDED, "alice", new EmailCategoryAdded(17L, List.of(1L, 2L))));

    verify(emailFilterService).applyToCategorizedMail("alice", 17L, List.of(1L, 2L));
  }

  /**
   * Pins the wiring: registered on the event, and asynchronous, so the categorizer and
   * the user's click never wait for a filter's actions.
   */
  @Test
  void theListenerIsRegisteredAndAsynchronous() {
    listener.init();

    verify(listenerService).addListener(EmailConnectorUtils.EMAIL_CATEGORY_ADDED, listener);
    assertTrue(EmailFilterCategoryListener.class.isAnnotationPresent(Asynchronous.class));
  }
}
