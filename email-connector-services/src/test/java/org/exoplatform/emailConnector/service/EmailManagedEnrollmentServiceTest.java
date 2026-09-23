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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.RejectedExecutionException;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.service.EmailManagedEnrollmentService.Outcome;

/**
 * EXO-89653. The three rules of the login-time enrolment, in order, and what each
 * exit records: nothing unless the mail server accepted, and nothing stored about
 * the outcome itself.
 */
@ExtendWith(MockitoExtension.class)
class EmailManagedEnrollmentServiceTest {

  private static final String           USER = "mary";

  @Mock
  private EmailManagedModeService       emailManagedModeService;

  @Mock
  private UserEmailSettingService       userEmailSettingService;

  @InjectMocks
  private EmailManagedEnrollmentService service;

  private MockedStatic<ExoContainerContext> containerContext;

  @BeforeEach
  void runOnTheCallerThread() {
    // enrollOnLogin is @ContainerTransactional: the woven aspect reads the current
    // container, and with none bound it would boot the kernel.
    containerContext = mockStatic(ExoContainerContext.class);
    ExoContainer portalContainer = mock(ExoContainer.class);
    containerContext.when(ExoContainerContext::getCurrentContainer).thenReturn(portalContainer);
    // The executor is the caller's thread: what is pinned is what runs, not when.
    service.setExecutor(Runnable::run);
  }

  @AfterEach
  void forgetTheContainer() {
    containerContext.close();
  }

  private void configured(boolean hasConfiguration) {
    UserEmailSetting setting = new UserEmailSetting();
    setting.setEmailConnectorId(hasConfiguration ? "3" : null);
    when(userEmailSettingService.getUserEmailSetting(USER)).thenReturn(setting);
  }

  /** Managed mode does not apply: the user's own settings are not even opened. */
  @Test
  void doesNothingWhenManagedModeDoesNotApply() {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(null);

    assertEquals(Outcome.NOT_MANAGED, service.enrollOnLogin(USER));

    verifyNoInteractions(userEmailSettingService);
  }

  /** Rule one: a configuration exists, whatever connector it names, and nothing happens. */
  @Test
  void leavesAloneAUserWhoAlreadyHasAConfiguration() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(7L);
    configured(true);

    assertEquals(Outcome.ALREADY_CONFIGURED, service.enrollOnLogin(USER));

    verify(userEmailSettingService, never()).connectThroughProvider(anyLong(), anyString());
  }

  /** Rule three: attached through the one-click connect. */
  @Test
  void attachesAUserWithoutAConfiguration() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(7L);
    configured(false);

    assertEquals(Outcome.ATTACHED, service.enrollOnLogin(USER));

    verify(userEmailSettingService).connectThroughProvider(7L, USER);
  }

  /**
   * A refused connect - the feature off, the connector inactive, no mailbox for this
   * user, the mail server refusing - leaves the user unattached; the next login tries
   * again.
   */
  @ParameterizedTest
  @MethodSource("refusals")
  void leavesUnattachedAUserTheConnectRefuses(Exception refusal) throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(7L);
    configured(false);
    doThrow(refusal).when(userEmailSettingService).connectThroughProvider(7L, USER);

    assertEquals(Outcome.REFUSED, service.enrollOnLogin(USER));
  }

  /** The three refusals the connect throws, one per type the catch names. */
  static java.util.stream.Stream<Exception> refusals() {
    return java.util.stream.Stream.of(new IllegalAccessException("The feature is off, or the connector is inactive"),
                                      new IllegalArgumentException("The provider of this connector names no mailbox for this user"),
                                      new IllegalStateException("Error when connecting store for user mary"));
  }

  /**
   * During a BlueMind outage the refusal reaches the enrolment wrapped, and the
   * innermost exception - the transport's own - has no message. The INFO line
   * must still say that BlueMind could not be reached.
   */
  @Test
  void namesEveryCauseOfARefusalInItsLogLine() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(7L);
    configured(false);
    Exception transport = new java.nio.channels.ClosedChannelException();
    Exception unreachable = new IllegalStateException("Error when connecting store for user mary",
                                                      new Exception("Cannot reach BlueMind on /api/auth/login", transport));
    doThrow(unreachable).when(userEmailSettingService).connectThroughProvider(7L, USER);
    ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EmailManagedEnrollmentService.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
    ch.qos.logback.classic.Level level = logger.getLevel();
    // The test configuration may log this class above INFO: read the line anyway.
    logger.setLevel(ch.qos.logback.classic.Level.INFO);
    appender.start();
    logger.addAppender(appender);
    try {
      assertEquals(Outcome.REFUSED, service.enrollOnLogin(USER));

      assertEquals("User mary left unattached: the managed mail connector 7 refused "
          + "(Error when connecting store for user mary <- Cannot reach BlueMind on /api/auth/login <- ClosedChannelException)",
                   appender.list.stream()
                                .filter(event -> event.getLevel() == ch.qos.logback.classic.Level.INFO)
                                .map(ch.qos.logback.classic.spi.ILoggingEvent::getFormattedMessage)
                                .findFirst()
                                .orElse(null));
    } finally {
      logger.detachAppender(appender);
      logger.setLevel(level);
    }
  }

  /** Any other failure is logged and swallowed: a login must never fail on this. */
  @Test
  void swallowsAFailureAndLeavesTheUserForTheNextLogin() {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenThrow(new RuntimeException("boom"));

    assertEquals(Outcome.FAILED, service.enrollOnLogin(USER));
  }

  /** Scheduling hands the user to the executor and returns; a blank login is dropped before that. */
  @Test
  void schedulesOnTheExecutorAndDropsABlankLogin() {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(null);

    assertTrue(service.scheduleEnrollment(USER));
    assertFalse(service.scheduleEnrollment(" "));

    verify(emailManagedModeService).designatedConnectorFor(USER);
  }

  /** A full queue drops the attempt with a warning rather than blocking the login thread. */
  @Test
  void dropsTheAttemptWhenTheQueueIsFull() {
    service.setExecutor(runnable -> {
      throw new RejectedExecutionException("full");
    });

    assertFalse(service.scheduleEnrollment(USER));
  }
}
