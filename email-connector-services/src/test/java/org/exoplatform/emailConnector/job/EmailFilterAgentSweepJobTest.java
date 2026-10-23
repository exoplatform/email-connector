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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.emailConnector.service.EmailFilterService;

import io.meeds.common.ContainerTransactional;

/**
 * The once-after-boot release of the mail filters' matches left waiting for an
 * assistant: glue, with a flag. The container is stated for the woven
 * {@code @ContainerTransactional}, as {@link EmailSyncDispatcherTest} explains.
 */
@ExtendWith(MockitoExtension.class)
public class EmailFilterAgentSweepJobTest {

  @Mock
  private EmailFilterService                emailFilterService;

  @Mock
  private ExoContainer                      container;

  @InjectMocks
  private EmailFilterAgentSweepJob          job;

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
   * The wiring, read by reflection: a component, scheduled on a property with a default,
   * establishing its container.
   *
   * @throws Exception never
   */
  @Test
  void theSweepIsScheduledOnThePropertyAndEstablishesItsContainer() throws Exception {
    assertNotNull(EmailFilterAgentSweepJob.class.getAnnotation(Component.class));
    Method sweep = EmailFilterAgentSweepJob.class.getMethod("sweep");
    Scheduled scheduled = sweep.getAnnotation(Scheduled.class);
    assertNotNull(scheduled);
    assertEquals("${email.connector.filters.agent.sweep.cron:30 * * * * ?}", scheduled.cron());
    assertNotNull(sweep.getAnnotation(ContainerTransactional.class));
  }

  /**
   * Without a handler, the first tick releases the waiting matches, and the next ones do
   * nothing.
   */
  @Test
  void withoutAHandlerTheFirstTickSweepsOnce() {
    when(emailFilterService.isAgentHandled()).thenReturn(false);
    when(emailFilterService.releaseUnansweredAgentMatches()).thenReturn(2);

    job.sweep();
    job.sweep();

    verify(emailFilterService, times(1)).releaseUnansweredAgentMatches();
    assertTrue(job.isSwept());
  }

  /**
   * With a handler, nothing is released and the job is done.
   */
  @Test
  void withAHandlerNothingIsReleased() {
    when(emailFilterService.isAgentHandled()).thenReturn(true);

    job.sweep();
    job.sweep();

    verify(emailFilterService, never()).releaseUnansweredAgentMatches();
    verify(emailFilterService, times(1)).isAgentHandled();
    assertTrue(job.isSwept());
  }

  /**
   * A sweep that failed as a whole does not escape, and the next tick tries again.
   */
  @Test
  void aFailedSweepIsTriedAgain() {
    when(emailFilterService.isAgentHandled()).thenReturn(false);
    when(emailFilterService.releaseUnansweredAgentMatches()).thenThrow(new IllegalStateException("the database is away")).thenReturn(0);

    job.sweep();
    assertFalse(job.isSwept());
    job.sweep();

    verify(emailFilterService, times(2)).releaseUnansweredAgentMatches();
    assertTrue(job.isSwept());
  }
}
