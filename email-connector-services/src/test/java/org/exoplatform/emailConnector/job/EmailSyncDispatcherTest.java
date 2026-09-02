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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
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
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.emailConnector.service.EmailSyncService;

import io.meeds.common.ContainerTransactional;

/**
 * The scheduled tick, which is glue and must stay glue: the shape of
 * {@code CaldavSyncSweepJobTest}, for the same job shape.
 * <p>
 * <b>Why a container is stated here at all.</b> {@code tick()} carries
 * {@code @ContainerTransactional}, and that aspect is woven into the class under
 * test: it reads the current container and, finding the JVM-wide root one a unit
 * test ends up with, calls {@code PortalContainer.getInstance()} -- which in a unit
 * test tries to build a real portal and dies on the first optional add-on missing
 * from this module's classpath. That is the annotation working, not failing. So
 * the condition is <i>stated</i>, a container that is not the root one, with the
 * {@code mockStatic} discipline the CalDAV test uses, scoped to this class and to
 * this thread.
 */
@ExtendWith(MockitoExtension.class)
public class EmailSyncDispatcherTest {

  @Mock
  private EmailSyncService                  emailSyncService;

  @Mock
  private ExoContainer                      container;

  @InjectMocks
  private EmailSyncDispatcher               dispatcher;

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
   * The wiring, read by reflection so that removing any of it fails a test rather
   * than a deployment: scheduling is enabled for this add-on's context by
   * {@link SchedulingConfig}, the tick is scheduled on the property with a
   * once-a-minute default, and it establishes the container it needs.
   */
  @Test
  void theTickIsScheduledOnThePropertyAndEstablishesItsContainer() throws Exception {
    assertNotNull(SchedulingConfig.class.getAnnotation(EnableScheduling.class), "scheduling must be enabled for this add-on");
    assertNotNull(SchedulingConfig.class.getAnnotation(Configuration.class));
    assertNotNull(EmailSyncDispatcher.class.getAnnotation(Component.class), "the job is a plain component, like the CalDAV sweep");

    Method tick = EmailSyncDispatcher.class.getMethod("tick");
    Scheduled scheduled = tick.getAnnotation(Scheduled.class);
    assertNotNull(scheduled, "the tick must be scheduled");
    assertEquals("${email.connector.sync.tick.cron:0 * * * * ?}", scheduled.cron(), "a property with a once-a-minute default, '-' to switch off");
    assertTrue(scheduled.fixedDelayString().isEmpty() && scheduled.fixedDelay() < 0, "cron, not a fixed delay");
    assertNotNull(tick.getAnnotation(ContainerTransactional.class),
                  "the tick must establish the container itself; the legacy @ExoTransactional throws on a scheduler thread");
  }

  @Test
  void theTickDelegatesAndHoldsNoLogicOfItsOwn() {
    // A scheduled task hands the work to the service. If this ever needs more
    // than one call, the decision has moved into the job and belongs back in the
    // service.
    when(emailSyncService.dispatchDueSyncs()).thenReturn(3);

    dispatcher.tick();

    verify(emailSyncService).dispatchDueSyncs();
  }

  @Test
  void aTickThatDispatchesNothingIsSilent() {
    // Most ticks on most instances. A line per tick would drown the log in
    // notices that nothing happened.
    when(emailSyncService.dispatchDueSyncs()).thenReturn(0);

    assertDoesNotThrow(() -> dispatcher.tick());
  }

  @Test
  void anExceptionFromTheServiceDoesNotEscapeTheTick() {
    when(emailSyncService.dispatchDueSyncs()).thenThrow(new IllegalStateException("the database is away"));

    assertDoesNotThrow(() -> dispatcher.tick());
  }
}
