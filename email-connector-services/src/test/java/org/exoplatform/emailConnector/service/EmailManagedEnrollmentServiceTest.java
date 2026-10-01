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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.concurrent.RejectedExecutionException;

import ch.qos.logback.classic.Level;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;

import io.meeds.common.ContainerTransactional;

import org.exoplatform.container.ExoContainer;
import org.exoplatform.container.ExoContainerContext;
import org.exoplatform.emailConnector.LogCapture;
import org.exoplatform.emailConnector.exception.CredentialsProviderMissingException;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.constant.EmailManagedEnrollmentOutcome;

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

  /**
   * The enrolment runs on its own thread: without a container bound by the
   * annotation, the storage and the services it calls have none.
   *
   * @throws Exception when the method is not found
   */
  @Test
  void theEnrolmentBindsTheContainerForItsThread() throws Exception {
    assertTrue(EmailManagedEnrollmentService.class.getMethod("enrollOnLogin", String.class)
                                                  .isAnnotationPresent(ContainerTransactional.class));
  }

  /** Managed mode does not apply: the user's own settings are not even opened. */
  @Test
  void doesNothingWhenManagedModeDoesNotApply() {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(null);

    assertEquals(EmailManagedEnrollmentOutcome.NOT_MANAGED, service.enrollOnLogin(USER));

    // Only the managed-mode mark is read: a user managed mode never attached is left alone.
    verify(userEmailSettingService).isConnectedByManagedMode(USER);
    verifyNoMoreInteractions(userEmailSettingService);
  }

  /**
   * A user managed mode attached, who has joined an excluded group since, is
   * disconnected at login and not attached again.
   */
  @Test
  void disconnectsAtLoginAUserManagedModeAttachedAndNoLongerGoverns() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(null);
    when(userEmailSettingService.isConnectedByManagedMode(USER)).thenReturn(true);
    when(emailManagedModeService.governingConnectorFor(USER)).thenReturn(null);
    when(userEmailSettingService.getStoredEmailConnectorId(USER)).thenReturn("7");

    assertEquals(EmailManagedEnrollmentOutcome.DETACHED, service.enrollOnLogin(USER));

    verify(userEmailSettingService).deleteUserEmailSetting(USER);
    verify(userEmailSettingService, never()).connectThroughProvider(anyLong(), anyString(), anyBoolean());
  }

  /**
   * A user managed mode attached whose identity cannot be resolved - the
   * directory failed - is not disconnected: the enrolment fails and the next login
   * decides.
   */
  @Test
  void neverDisconnectsAtLoginAUserWhoseIdentityCannotBeResolved() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(null);
    when(userEmailSettingService.isConnectedByManagedMode(USER)).thenReturn(true);
    when(emailManagedModeService.governingConnectorFor(USER)).thenThrow(new IllegalStateException("no identity"));

    assertEquals(EmailManagedEnrollmentOutcome.FAILED, service.enrollOnLogin(USER));

    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /** The same situation for a user who chose their connector: they are not touched. */
  @Test
  void neverDisconnectsAUserWhoChoseTheirConnector() {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(null);
    when(userEmailSettingService.isConnectedByManagedMode(USER)).thenReturn(false);

    assertEquals(EmailManagedEnrollmentOutcome.NOT_MANAGED, service.enrollOnLogin(USER));

    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /**
   * A user managed mode attached to a connector no longer designated - a
   * disconnection the administrator's change asked for did not go through - is
   * disconnected, then attached to the connector designated now.
   */
  @Test
  void movesAtLoginAUserManagedModeAttachedToAConnectorNoLongerDesignated() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(7L);
    when(userEmailSettingService.isConnectedByManagedMode(USER)).thenReturn(true);
    when(emailManagedModeService.governingConnectorFor(USER)).thenReturn(7L);
    when(userEmailSettingService.getStoredEmailConnectorId(USER)).thenReturn("3");
    configured(false);
    when(userEmailSettingService.connectThroughProvider(7L, USER, true)).thenReturn(true);

    assertEquals(EmailManagedEnrollmentOutcome.ATTACHED, service.enrollOnLogin(USER));

    InOrder order = inOrder(userEmailSettingService);
    order.verify(userEmailSettingService).deleteUserEmailSetting(USER);
    order.verify(userEmailSettingService).connectThroughProvider(7L, USER, true);
  }

  /** A user managed mode attached, still on the designated connector, is left alone. */
  @Test
  void leavesAloneAUserManagedModeAttachedToTheDesignatedConnector() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(7L);
    when(userEmailSettingService.isConnectedByManagedMode(USER)).thenReturn(true);
    when(emailManagedModeService.governingConnectorFor(USER)).thenReturn(7L);
    when(userEmailSettingService.getStoredEmailConnectorId(USER)).thenReturn("7");
    configured(true);

    assertEquals(EmailManagedEnrollmentOutcome.ALREADY_CONFIGURED, service.enrollOnLogin(USER));

    verify(userEmailSettingService, never()).deleteUserEmailSetting(anyString());
  }

  /** Rule one: a configuration exists, whatever connector it names, and nothing happens. */
  @Test
  void leavesAloneAUserWhoAlreadyHasAConfiguration() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(7L);
    configured(true);

    assertEquals(EmailManagedEnrollmentOutcome.ALREADY_CONFIGURED, service.enrollOnLogin(USER));

    verify(userEmailSettingService, never()).connectThroughProvider(anyLong(), anyString(), anyBoolean());
  }

  /** Rule three: attached through the one-click connect. */
  @Test
  void attachesAUserWithoutAConfiguration() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(7L);
    configured(false);
    when(userEmailSettingService.connectThroughProvider(7L, USER, true)).thenReturn(true);

    assertEquals(EmailManagedEnrollmentOutcome.ATTACHED, service.enrollOnLogin(USER));

    // Only if still unconfigured: a connection the user saves during the probe stands.
    verify(userEmailSettingService).connectThroughProvider(7L, USER, true);
  }

  /**
   * The user connected a mailbox themselves while the managed one was being
   * probed: the connect wrote nothing, and rule one stands.
   */
  @Test
  void leavesAloneAUserWhoConfiguredAMailboxDuringTheAttach() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(7L);
    configured(false);
    when(userEmailSettingService.connectThroughProvider(7L, USER, true)).thenReturn(false);

    assertEquals(EmailManagedEnrollmentOutcome.ALREADY_CONFIGURED, service.enrollOnLogin(USER));
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
    doThrow(refusal).when(userEmailSettingService).connectThroughProvider(7L, USER, true);

    assertEquals(EmailManagedEnrollmentOutcome.REFUSED, service.enrollOnLogin(USER));
  }

  /**
   * A designated connector whose credentials provider is not registered - an add-on's,
   * not installed or not started yet - leaves the user unattached as a refusal does, but
   * says nothing at INFO or above and carries no stack: it is met at every login of every
   * governed user, and the resolver has said it once for the provider's name.
   */
  @Test
  void leavesUnattachedQuietlyAUserWhoseConnectorsProviderIsNotRegistered() throws Exception {
    when(emailManagedModeService.designatedConnectorFor(USER)).thenReturn(7L);
    configured(false);
    doThrow(new CredentialsProviderMissingException("bluemind-sudo")).when(userEmailSettingService)
                                                                    .connectThroughProvider(7L, USER, true);

    try (LogCapture log = new LogCapture(EmailManagedEnrollmentService.class)) {
      assertEquals(EmailManagedEnrollmentOutcome.REFUSED, service.enrollOnLogin(USER));

      assertTrue(log.events().stream().noneMatch(event -> event.getLevel().isGreaterOrEqual(Level.INFO)),
                 log.events().toString());
      assertFalse(log.anyStack(), log.events().toString());
    }
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
    doThrow(unreachable).when(userEmailSettingService).connectThroughProvider(7L, USER, true);
    ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) org.slf4j.LoggerFactory.getLogger(EmailManagedEnrollmentService.class);
    ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> appender = new ch.qos.logback.core.read.ListAppender<>();
    ch.qos.logback.classic.Level level = logger.getLevel();
    // The test configuration may log this class above INFO: read the line anyway.
    logger.setLevel(ch.qos.logback.classic.Level.INFO);
    appender.start();
    logger.addAppender(appender);
    try {
      assertEquals(EmailManagedEnrollmentOutcome.REFUSED, service.enrollOnLogin(USER));

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

    assertEquals(EmailManagedEnrollmentOutcome.FAILED, service.enrollOnLogin(USER));
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
