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

import org.exoplatform.emailConnector.constant.EmailManagedEnrollmentOutcome;
import org.exoplatform.emailConnector.exception.CredentialsProviderMissingException;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PreDestroy;

/**
 * Enrols a user on the mail connector managed mode designated, when they log in
 * and again each time the platform registers their session anew, about every
 * hour while they stay logged in ({@link
 * org.exoplatform.emailConnector.listener.EmailManagedLoginListener}, EXO-89653).
 * <p>
 * Three rules: the user is in a population the administrator excluded, or nothing
 * is designated, and nothing happens; the user already has a mail configuration on the
 * designated connector and nothing happens; otherwise they are put on the designated
 * connector - attached when they have no configuration, switched when they are on
 * another connector (EXO-90836). The designation is read first because it is the
 * cheapest read and the one that is null on every instance where managed mode is off.
 * <p>
 * A switch opens the designated mailbox before it writes anything: a refusal leaves
 * the user on the connector they are on, and the next attempt tries again. A user
 * managed mode governs cannot disconnect, edit their connection or connect elsewhere
 * themselves ({@link EmailManagedModeService#checkUserMayChangeConnection(String, Long)}),
 * so the connector the instance designates is the only one they end up on.
 * <p>
 * An attachment is marked as made by managed mode
 * ({@link UserEmailSettingService#CONNECTED_BY_MANAGED_MODE_KEY}), and the
 * mark is checked first: a marked user managed mode no longer governs - they joined an
 * excluded group, or an administrator's change could not disconnect them - is
 * disconnected, then attached again when another connector is designated for them.
 * <p>
 * The attachment is the one-click connect of EXO-90358: the mailbox is opened with
 * the material the provider produces and the connection is recorded only if that
 * passed, which also schedules the first synchronisation. A user the designated
 * server does not know is left unattached, and tried again at the same pace.
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
   * <p>
   * The login thread opens nothing (EXO-90573): {@link #enrollOnLogin} is called on
   * {@code this} from the executor thread and still binds its container, because
   * {@link ContainerTransactional} is woven by ajc into the method body, not applied by
   * the Spring proxy.
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
   * Applies the rules for one user, on the executor's thread.
   * <p>
   * {@code @ContainerTransactional} because this runs on a bare executor thread: the
   * aspect binds the portal container and a request lifecycle around the call, which
   * the setting reads and the recorded connection need.
   *
   * @param username the eXo login of the user who logged in
   * @return what happened, for the tests and the log
   */
  @ContainerTransactional
  public EmailManagedEnrollmentOutcome enrollOnLogin(String username) {
    try {
      Long connectorId = emailManagedModeService.designatedConnectorFor(username);
      if (isNoLongerGoverned(username)) {
        // Managed mode attached this user and no longer governs them - they joined an
        // excluded group since, or a disconnection an administrator's change asked for
        // did not go through. Disconnected here, then attached again below
        // when another connector is designated for them.
        userEmailSettingService.deleteUserEmailSetting(username);
        LOG.info("User {} disconnected from the mail connector managed mode attached them to: it no longer applies to them",
                 username);
        if (connectorId == null) {
          return EmailManagedEnrollmentOutcome.DETACHED;
        }
      } else if (connectorId == null) {
        LOG.debug("User {} not enrolled: no managed mail connector applies to them", username);
        return EmailManagedEnrollmentOutcome.NOT_MANAGED;
      }
      String configuredConnectorId = configuredConnectorId(username);
      if (String.valueOf(connectorId).equals(configuredConnectorId)) {
        LOG.debug("User {} not enrolled: they are already on the managed mail connector", username);
        return EmailManagedEnrollmentOutcome.ALREADY_CONFIGURED;
      }
      return attach(connectorId, username, StringUtils.isNotBlank(configuredConnectorId));
    } catch (Exception e) {
      LOG.warn("Cannot attach user {} to the managed mail connector at login; their next login will try again", username, e);
      return EmailManagedEnrollmentOutcome.FAILED;
    }
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
   * Rule three: the one-click connect, run for the user - an attachment when they have
   * no configuration, a switch when they are on another connector. A refusal records no
   * connection and is retried at the next login; any other exception is the caller's
   * failure. The connect records one thing on a refusal: when the provider reports the
   * user's account refused, or names no mailbox, it stores the user's managed refusal
   * (EXO-91017), which the next connection that succeeds clears.
   *
   * @param connectorId the designated connector
   * @param username the eXo login of the user who logged in
   * @param switching true when the user is on another connector
   * @return ATTACHED or SWITCHED, REFUSED, or ALREADY_CONFIGURED when the stored
   *         setting changed during the connect
   * @throws Exception an unexpected failure, logged by the caller
   */
  private EmailManagedEnrollmentOutcome attach(Long connectorId, String username, boolean switching) throws Exception {
    try {
      boolean recorded = switching ? userEmailSettingService.switchThroughProvider(connectorId, username)
                                   : userEmailSettingService.connectThroughProvider(connectorId, username, true);
      if (!recorded) {
        // Another writer stored a setting while the managed mailbox was being probed:
        // a mailbox the user connected before the lock reached them, or a concurrent
        // switch of the same user. The next attempt judges what is stored then.
        LOG.debug("User {} not enrolled: their mail setting changed during the connect", username);
        return EmailManagedEnrollmentOutcome.ALREADY_CONFIGURED;
      }
      if (switching) {
        LOG.info("User {} switched to the managed mail connector {} at login", username, connectorId);
        return EmailManagedEnrollmentOutcome.SWITCHED;
      }
      LOG.info("User {} attached to the managed mail connector {} at login", username, connectorId);
      return EmailManagedEnrollmentOutcome.ATTACHED;
    } catch (CredentialsProviderMissingException e) {
      // Met at every login of every governed user while the provider is not
      // registered, which the resolver has said once for its name: nothing more than
      // a debug line here, and nothing recorded, so the first login after the
      // provider appears attaches.
      LOG.debug("User {} left unattached: the managed mail connector {} names a provider that is not registered ({})",
                username,
                connectorId,
                e.getProviderName());
      return EmailManagedEnrollmentOutcome.REFUSED;
    } catch (IllegalAccessException | IllegalArgumentException | IllegalStateException e) {
      // The connect refused - the feature is off, the connector inactive, the
      // provider names no mailbox for this user, or the mail server would not open
      // it. No connection is recorded, and the next login tries again; the
      // administrator's remedies are an account there or an exclusion. A provider
      // naming no mailbox, or reporting the user's account refused, has stored the
      // user's managed refusal (EXO-91017).
      // The whole cause chain, not the outer message: the connect wraps the
      // refusal, and the message that says why is not always the innermost one.
      LOG.info("User {} left unattached: the managed mail connector {} refused ({})", username, connectorId, causeChain(e));
      return EmailManagedEnrollmentOutcome.REFUSED;
    }
  }

  /**
   * Whether managed mode attached this user and no longer governs them: it designates
   * nothing for them, or another connector than the one they are on. A user who made
   * their own connection is never concerned. Judged by the verdict that refuses a user
   * whose identity cannot be resolved: that refusal fails the login's enrolment, and
   * nothing is deleted.
   *
   * @param username the eXo login
   * @return true when the user is to be disconnected
   */
  private boolean isNoLongerGoverned(String username) {
    return userEmailSettingService.isConnectedByManagedMode(username)
        && !String.valueOf(emailManagedModeService.governingConnectorFor(username))
                  .equals(userEmailSettingService.getStoredEmailConnectorId(username));
  }

  /**
   * The connector the user's mail configuration names, whatever it is.
   *
   * @param username the eXo login
   * @return the connector id as stored, null or blank when there is no configuration
   */
  private String configuredConnectorId(String username) {
    UserEmailSetting setting = userEmailSettingService.getUserEmailSetting(username);
    return setting == null ? null : setting.getEmailConnectorId();
  }

  private static ExecutorService newEnrollmentExecutor() {
    return new ThreadPoolExecutor(2, 2, 0L, TimeUnit.MILLISECONDS, new ArrayBlockingQueue<>(QUEUE_DEPTH), runnable -> {
      Thread thread = new Thread(runnable, "email-managed-enrollment");
      thread.setDaemon(true);
      return thread;
    });
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

}
