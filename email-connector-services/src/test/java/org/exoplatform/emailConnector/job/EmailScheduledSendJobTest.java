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
package org.exoplatform.emailConnector.job;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.emailConnector.service.EmailScheduledSendService;

import io.meeds.common.ContainerTransactional;

/**
 * The scheduled-send tick is glue: it is scheduled on its property, establishes its
 * container, calls the service's one dispatch and nothing else, and lets nothing
 * escape. The container is stated for the woven aspect, as {@code EmailSyncDispatcherTest}
 * explains.
 */
@ExtendWith(MockitoExtension.class)
public class EmailScheduledSendJobTest {

  @Mock
  private EmailScheduledSendService         emailScheduledSendService;

  @Mock
  private ExoContainer                      container;

  @InjectMocks
  private EmailScheduledSendJob             job;

  private MockedStatic<ExoContainerContext> containerContext;

  /**
   * States a container the woven aspect can work with.
   */
  @BeforeEach
  void establishAContainer() {
    containerContext = mockStatic(ExoContainerContext.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(container);
  }

  /**
   * Takes the stated container away again.
   */
  @AfterEach
  void forgetTheContainer() {
    containerContext.close();
  }

  /**
   * The wiring, read by reflection: a component, scheduled every minute by default on
   * a property that can turn it off, establishing its container.
   *
   * @throws Exception if the method is missing
   */
  @Test
  void theTickIsScheduledOnThePropertyAndEstablishesItsContainer() throws Exception {
    assertNotNull(EmailScheduledSendJob.class.getAnnotation(Component.class));
    Method tick = EmailScheduledSendJob.class.getMethod("tick");
    assertEquals("${email.connector.scheduledSend.cron:0 * * * * ?}", tick.getAnnotation(Scheduled.class).cron());
    assertNotNull(tick.getAnnotation(ContainerTransactional.class));
  }

  /**
   * The tick delegates the whole pass and does nothing else.
   */
  @Test
  void theTickOnlyDelegates() {
    when(emailScheduledSendService.dispatchDue()).thenReturn(2);
    job.tick();
    verify(emailScheduledSendService).dispatchDue();
    verifyNoMoreInteractions(emailScheduledSendService);
  }

  /**
   * A failed pass does not escape the tick.
   */
  @Test
  void aFailedPassDoesNotEscape() {
    when(emailScheduledSendService.dispatchDue()).thenThrow(new IllegalStateException("the database is away"));
    assertDoesNotThrow(() -> job.tick());
  }
}
