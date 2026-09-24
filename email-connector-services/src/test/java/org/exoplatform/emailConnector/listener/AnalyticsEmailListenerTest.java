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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.listener.Event;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.social.core.identity.model.Identity;
import org.exoplatform.social.core.manager.IdentityManager;

import io.meeds.analytics.model.StatisticData;
import io.meeds.analytics.utils.AnalyticsUtils;

/**
 * The analytics of mail events (EXO-90583 for the mail sent in another's name).
 */
@ExtendWith(MockitoExtension.class)
class AnalyticsEmailListenerTest {

  @Mock
  private IdentityManager        identityManager;

  @Mock
  private ListenerService        listenerService;

  @InjectMocks
  private AnalyticsEmailListener listener;

  /**
   * A mail sent in a shared mailbox owner's name is counted as its own operation, with
   * its shape as the only keyword -- never the owner's address -- for the user who sent
   * it; the plain send keeps its kind of mail.
   *
   * @throws Exception never
   */
  @Test
  void aMailInTheOwnersNameIsCountedByShape() throws Exception {
    Identity bob = mock(Identity.class);
    when(bob.getId()).thenReturn("42");
    when(identityManager.getOrCreateUserIdentity("bob")).thenReturn(bob);
    try (MockedStatic<AnalyticsUtils> analytics = mockStatic(AnalyticsUtils.class)) {
      listener.onEvent(new Event<>(EmailConnectorUtils.SEND_EMAIL_IN_OWNERS_NAME, "bob", "AS"));
      listener.onEvent(new Event<>(EmailConnectorUtils.SEND_EMAIL, "bob", "reply"));

      ArgumentCaptor<StatisticData> data = ArgumentCaptor.forClass(StatisticData.class);
      analytics.verify(() -> AnalyticsUtils.addStatisticData(data.capture()), org.mockito.Mockito.times(2));
      StatisticData inOwnersName = data.getAllValues().get(0);
      assertEquals("sendEmailInOwnersName", inOwnersName.getOperation());
      assertEquals(42L, inOwnersName.getUserId());
      assertEquals("AS", inOwnersName.getParameters().get("sendMode"));
      assertEquals(1, inOwnersName.getParameters().size(), "the shape only");
      StatisticData plain = data.getAllValues().get(1);
      assertEquals("sendEmail", plain.getOperation());
      assertEquals("reply", plain.getParameters().get("emailType"));
      assertNull(plain.getParameters().get("sendMode"));
    }
  }

  /**
   * The listener subscribes to the mail sent in another's name beside the other events.
   */
  @Test
  void itListensToTheMailSentInAnothersName() {
    listener.init();
    verify(listenerService).addListener(EmailConnectorUtils.SEND_EMAIL_IN_OWNERS_NAME, listener);
    verify(listenerService).addListener(EmailConnectorUtils.SEND_EMAIL, listener);
  }
}
