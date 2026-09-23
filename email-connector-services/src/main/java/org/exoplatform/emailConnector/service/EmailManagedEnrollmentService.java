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

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PreDestroy;

/**
 * Enrols a user on the mail connector managed mode designated, when they log in
 * (EXO-89653): the login-time attachment the board describes.
 * <p>
 * Three rules, in the order the board states them and with one reordering that
 * changes no outcome: the user already has a mail configuration - whatever connector
 * it names - and nothing happens; the user is in a population the administrator
 * excluded and nothing happens; otherwise they are attached to the designated
 * connector. The designation is read first here because it is the cheapest read and
 * the one that is null on every instance where managed mode is off: such an instance
 * pays two setting reads per login and never opens the user's own settings.
 * <p>
 * Having no configuration and having removed one are the same case: a user who
 * disconnects is attached again at their next login, and disconnecting stays useful
 * because whoever connects elsewhere has a configuration, which rule one leaves
 * alone. Nothing is stored about the outcome.
 * <p>
 * The attachment is the one-click connect of EXO-90358: the mailbox is opened with
 * the material the provider produces and the connection is recorded only if that
 * passed, which also schedules the first synchronisation. A user the designated
 * server does not know is left unattached, and tried again at their next login.
 * <p>
 * <b>In the background.</b> The login never waits for the mail server: the listener
 * hands the user to a small bounded executor of this service and returns. A full
 * queue drops the attempt with a WARN - the next login retries - rather than blocking
 * the login thread.
 */
@Service
public class EmailManagedEnrollmentService {

  private static final Log        LOG         = ExoLogger.getLogger(EmailManagedEnrollmentService.class);

  /** Enrolments waiting for a thread: beyond this, a login's attempt is dropped and retried next time. */
  static final int                QUEUE_DEPTH = 256;

  @Autowired
  private EmailManagedModeService emailManagedModeService;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  private Executor                executor    = newEnrollmentExecutor();

  /**
   * Queues the enrolment of a user who just logged in. Returns at once.
   *
   * @param username the eXo login of the user who logged in
   * @return true when the attempt was queued, false when it was dropped
   */
  public boolean scheduleEnrollment(String username) {
    if (StringUtils.isBlank(username)) {
      return false;
    }
    try {
      executor.execute(() -> enrollOnLogin(username));
      return true;
    } catch (RejectedExecutionException e) {
      LOG.warn("Too many mail enrolments pending; user {} will be attached at their next login", username);
      return false;
    }
  }

  /**
   * Applies the three rules for one user, on the executor's thread.
   * <p>
   * {@code @ContainerTransactional} because this runs on a bare executor thread: the
   * aspect binds the portal container and a request lifecycle around the call, which
   * the setting reads and the recorded connection need.
   *
   * @param username the eXo login of the user who logged in
   * @return what happened, for the tests and the log
   */
  @ContainerTransactional
  public Outcome enrollOnLogin(String username) {
    try {
      Long connectorId = emailManagedModeService.designatedConnectorFor(username);
      if (connectorId == null) {
        LOG.debug("User {} not enrolled: no managed mail connector applies to them", username);
        return Outcome.NOT_MANAGED;
      }
      if (hasConfiguration(username)) {
        LOG.debug("User {} not enrolled: they already have a mail configuration", username);
        return Outcome.ALREADY_CONFIGURED;
      }
      return attach(connectorId, username);
    } catch (Exception e) {
      LOG.warn("Cannot attach user {} to the managed mail connector at login; their next login will try again", username, e);
      return Outcome.FAILED;
    }
  }

  /**
   * Rule three: the one-click connect, run for the user. A refusal records
   * nothing and is retried at the next login; any other exception is the
   * caller's failure.
   *
   * @param connectorId the designated connector
   * @param username the eXo login of the user who logged in
   * @return ATTACHED or REFUSED
   * @throws Exception an unexpected failure, logged by the caller
   */
  private Outcome attach(Long connectorId, String username) throws Exception {
    try {
      userEmailSettingService.connectThroughProvider(connectorId, username);
      LOG.info("User {} attached to the managed mail connector {} at login", username, connectorId);
      return Outcome.ATTACHED;
    } catch (IllegalAccessException | IllegalArgumentException | IllegalStateException e) {
      // The connect refused - the feature is off, the connector inactive, the
      // provider names no mailbox for this user, or the mail server would not open
      // it. Nothing is recorded, and the next login tries again; the administrator's
      // remedies are an account there or an exclusion.
      // The whole cause chain, not the outer message: the connect wraps the
      // refusal, and the message that says why is not always the innermost one.
      LOG.info("User {} left unattached: the managed mail connector {} refused ({})", username, connectorId, causeChain(e));
      return Outcome.REFUSED;
    }
  }

  /**
   * Rule one: whether the user already has a mail configuration, whatever connector
   * it names.
   *
   * @param username the eXo login
   * @return true when a configuration exists
   */
  private boolean hasConfiguration(String username) {
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    return setting != null && StringUtils.isNotBlank(setting.getEmailConnectorId());
  }

  private static ExecutorService newEnrollmentExecutor() {
    return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_DEPTH), runnable -> {
      Thread thread = new Thread(runnable, "email-managed-enrollment");
      thread.setDaemon(true);
      return thread;
    });
  }

  @PreDestroy
  public void stop() {
    if (executor instanceof ExecutorService service) {
      // Drop what is queued rather than run it against a context being torn
      // down; the next login retries.
      service.shutdownNow();
    }
  }

  /**
   * Every non-blank message of a failure's cause chain, outermost first. The
   * root alone is not enough: a transport failure's innermost exception usually
   * carries no message, and the one that says what happened - "Cannot reach
   * BlueMind on /api/auth/login" - sits a level above it. A throwable with no
   * message is named by its class.
   *
   * @param failure the refusal as the connect threw it
   * @return the chain, joined with {@code " <- "}
   */
  static String causeChain(Throwable failure) {
    return ExceptionUtils.getThrowableList(failure)
                         .stream()
                         .map(cause -> StringUtils.isBlank(cause.getMessage()) ? cause.getClass().getSimpleName()
                                                                               : cause.getMessage())
                         .collect(Collectors.joining(" <- "));
  }

  /** For the tests: run the enrolments on the caller's thread. */
  void setExecutor(Executor executor) {
    this.executor = executor;
  }

  /** What a login attempt came to, one value per branch of the three rules and their failures. */
  public enum Outcome {
    NOT_MANAGED, ALREADY_CONFIGURED, ATTACHED, REFUSED, FAILED
  }
}
