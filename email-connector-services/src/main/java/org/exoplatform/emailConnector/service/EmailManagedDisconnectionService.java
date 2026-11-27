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

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PreDestroy;

/**
 * Disconnects the users an administrator's change leaves on a mailbox that is no
 * longer theirs, and counts them beforehand for the warning the
 * administration screen shows.
 * <p>
 * Two changes, two populations:
 * <ul>
 * <li>a change of the managed mode - another designated connector, managed mode off, a
 * group excluded - disconnects the users managed mode attached and no longer governs;
 * the users who chose a connector themselves are left alone: each user is checked again
 * just before the delete (see {@code isStillNoLongerManaged} for the short
 * window that remains);</li>
 * <li>a connector moved to another credentials provider disconnects every user of that
 * connector, whoever made the connection: the authentication changed for all of them.
 * Nobody is reconnected automatically.</li>
 * </ul>
 * Every disconnection is {@link UserEmailSettingService#deleteUserEmailSetting(String)},
 * the path a user's own disconnection takes, so the mailbox cleanup runs in its usual
 * order. They run in the background, one user at a time: the administrator's request
 * does not wait for them, and the failure of one is logged without abandoning the
 * rest - a user left connected is caught at their next login, or at the next change.
 */
@Service
public class EmailManagedDisconnectionService {

  private static final Log        LOG = ExoLogger.getLogger(EmailManagedDisconnectionService.class);

  @Autowired
  private ManagedConnectorService managedConnectorService;

  @Autowired
  private EmailManagedModeService emailManagedModeService;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @Autowired
  private EmailConnectorService   emailConnectorService;

  private Executor                executor = newDisconnectionExecutor();

  /**
   * How many accounts a managed-mode change would disconnect, before it is applied: the
   * users managed mode attached that the proposed state no longer governs.
   *
   * @param designation the connector the change designates, null when it switches
   *          managed mode off
   * @param excludedGroups the groups the change excludes, null for none
   * @param username the eXo login of the caller
   * @return the number of accounts the change would disconnect
   * @throws IllegalAccessException when the caller may not administer email connectors
   */
  public int countUsersNoLongerManaged(Long designation, List<String> excludedGroups, String username) throws IllegalAccessException {
    requireAdministrator(username);
    return usersNoLongerManaged(designation, excludedGroups).size();
  }

  /**
   * How many accounts moving a connector to another provider would disconnect: every
   * user connected to it.
   *
   * @param emailConnectorId the connector
   * @param username the eXo login of the caller
   * @return the number of users connected to the connector
   * @throws IllegalAccessException when the caller may not administer email connectors
   * @throws ObjectNotFoundException when no connector has this id
   */
  public int countUsersOf(long emailConnectorId, String username) throws IllegalAccessException, ObjectNotFoundException {
    requireAdministrator(username);
    if (emailConnectorService.getEmailConnector(emailConnectorId) == null) {
      throw new ObjectNotFoundException("No email connector " + emailConnectorId);
    }
    return userEmailSettingService.getUserEmailSettingsByEmailConnectorId(emailConnectorId).size();
  }

  /**
   * Disconnects, in the background, the users managed mode attached and no longer
   * governs in the state now stored. The selection itself runs in the background too:
   * the administrator's request returns as soon as the change is stored.
   */
  public void disconnectUsersNoLongerManaged() {
    executor.execute(this::reconcileNow);
  }

  /**
   * Disconnects, in the background, every user of a connector.
   *
   * @param emailConnectorId the connector whose provider changed
   */
  public void disconnectAllUsersOf(long emailConnectorId) {
    executor.execute(() -> disconnectAllNow(emailConnectorId));
  }

  /**
   * Disconnects the users whose stored connector id is a number written in another
   * form than its canonical one ({@link UserEmailSettingService#getUsersWithNonCanonicalConnectorId()}),
   * and waits for the run to end. While stored ids were compared as text, such a user
   * was left out of {@link #disconnectAllUsersOf(long)} and of the cleanup of a deleted
   * connector; a one-shot data upgrade calls this method to catch them up. A user on a
   * connector that did not change is disconnected too: the two cannot be told apart.
   * <p>
   * The run executes on the executor's thread, in the request lifecycles
   * {@link #reconcileNow()} describes, not in the caller's: an upgrade runs inside a
   * request lifecycle of its own, which would hold every user's turn in one persistence
   * context. A user who reconnected since the selection is left alone, and a failed
   * disconnection is logged without abandoning the others.
   *
   * @return the number of users left connected because their disconnection failed
   * @throws InterruptedException when the caller is interrupted while it waits
   * @throws ExecutionException when the selection fails: nobody is disconnected
   */
  public int disconnectUsersWithNonCanonicalConnectorId() throws InterruptedException, ExecutionException {
    return CompletableFuture.supplyAsync(this::disconnectNonCanonicalNow, executor).get();
  }

  @PreDestroy
  public void stop() {
    if (executor instanceof ExecutorService service) {
      service.shutdownNow();
    }
  }

  /**
   * Selects, against the state now stored, the users managed mode attached and no
   * longer governs, and disconnects them one by one, each one checked again when their
   * turn comes ({@link #isStillNoLongerManaged(String)}).
   * <p>
   * The selection runs in a request lifecycle of its own, closed before the first user's
   * turn, and each user's turn - the re-check and the delete - in another
   * ({@link #inItsOwnLifecycle(Supplier)}). A setting read in a lifecycle stays in its
   * persistence context until the lifecycle ends, and is served from there as it was
   * first loaded: a lifecycle shared with the selection would answer the re-check with
   * the selection's rows, and a user who moved in the meantime would be disconnected.
   *
   * @return the number of users disconnected
   */
  int reconcileNow() {
    List<String> users = inItsOwnLifecycle(() -> usersNoLongerManaged(emailManagedModeService.getManagedConnectorId(),
                                                                     emailManagedModeService.getExcludedGroups()));
    return (int) users.stream()
                      .filter(user -> inItsOwnLifecycle(() -> isStillNoLongerManaged(user) && disconnectNow(user)))
                      .count();
  }

  /**
   * Disconnects every user of a connector one by one, each one checked again when their
   * turn comes ({@link #isStillOn(String, String)}), in the request lifecycles
   * {@link #reconcileNow()} describes.
   *
   * @param emailConnectorId the connector whose provider changed
   * @return the number of users disconnected
   */
  int disconnectAllNow(long emailConnectorId) {
    String connectorId = String.valueOf(emailConnectorId);
    List<String> users = inItsOwnLifecycle(() -> userEmailSettingService.getUserEmailSettingsByEmailConnectorId(emailConnectorId));
    return (int) users.stream()
                      .filter(user -> inItsOwnLifecycle(() -> isStillOn(user, connectorId) && disconnectNow(user)))
                      .count();
  }

  /**
   * Disconnects the users whose stored connector id is not in its canonical form, one by
   * one, each one checked again when their turn comes ({@link #isStillNonCanonical(String)}),
   * in the request lifecycles {@link #reconcileNow()} describes.
   *
   * @return the number of users whose disconnection failed
   */
  int disconnectNonCanonicalNow() {
    List<String> users = inItsOwnLifecycle(userEmailSettingService::getUsersWithNonCanonicalConnectorId);
    int failed = 0;
    for (String user : users) {
      if (Boolean.FALSE.equals(inItsOwnLifecycle(() -> !isStillNonCanonical(user) || disconnectNonCanonical(user)))) {
        failed++;
      }
    }
    LOG.info("{} of {} users with a non-canonical mail connector id left connected after their disconnection failed",
             failed,
             users.size());
    return failed;
  }

  /**
   * Runs one step of a run - its selection, or one user's turn - in a request lifecycle
   * of its own. The runs execute on the executor's bare thread, where no lifecycle is
   * open around them: each step's lifecycle is the outermost one, and closing it ends
   * its persistence context.
   *
   * @param <T> what the step returns
   * @param step the step
   * @return what the step returned
   */
  @ContainerTransactional
  <T> T inItsOwnLifecycle(Supplier<T> step) {
    return step.get();
  }

  /**
   * Whether a user a run selected is still to be disconnected when their turn comes.
   * The users are selected once, before the first delete, and each delete takes the
   * user's cached mail with it, so a run lasts. Meanwhile a user may connect their own
   * mailbox, which clears the managed-mode mark, or log in and be attached to the
   * connector now designated. So the mark is read again, and the verdict is computed
   * again against the designation and the exclusions read again. The time between this
   * check and the delete remains: no lock is shared with the connect paths.
   *
   * @param username the eXo login the run selected
   * @return true when the user is still to be disconnected
   */
  boolean isStillNoLongerManaged(String username) {
    try {
      return userEmailSettingService.isConnectedByManagedMode(username)
          && isNoLongerManaged(username, emailManagedModeService.getManagedConnectorId(), emailManagedModeService.getExcludedGroups());
    } catch (Exception e) {
      // Exception, not RuntimeException: a malformed stored document surfaces as
      // Jackson's checked exception, thrown sneakily.
      LOG.warn("Cannot tell whether managed mode still governs user {}; left connected, their next login will decide",
               username,
               e);
      return false;
    }
  }

  /**
   * Whether a user of a connector whose provider changed is still stored on it when
   * their turn comes: a user who moved to another connector, or disconnected, during the
   * run is left alone. A user who connected to this same connector again during the run
   * cannot be told apart from one who did not, and is disconnected too: telling them
   * apart needs a lock shared with the connect paths.
   *
   * @param username the eXo login the run selected
   * @param connectorId the connector whose provider changed
   * @return true when the user is still to be disconnected
   */
  boolean isStillOn(String username, String connectorId) {
    try {
      return EmailConnectorUtils.sameConnector(userEmailSettingService.getStoredEmailConnectorId(username), connectorId);
    } catch (Exception e) {
      // Exception, not RuntimeException: see isStillNoLongerManaged.
      LOG.warn("Cannot read the mail setting of user {}; left connected to connector {}", username, connectorId, e);
      return false;
    }
  }

  /**
   * Whether a user selected for a non-canonical connector id still has one when their
   * turn comes: a user who reconnected, or disconnected, during the run is left alone.
   *
   * @param username the eXo login the run selected
   * @return true when the user is still to be disconnected
   */
  boolean isStillNonCanonical(String username) {
    try {
      String stored = userEmailSettingService.getStoredEmailConnectorId(username);
      String canonical = EmailConnectorUtils.canonicalConnectorId(stored);
      return canonical != null && !canonical.equals(stored);
    } catch (Exception e) {
      // Exception, not RuntimeException: see isStillNoLongerManaged.
      LOG.warn("Cannot read the mail setting of user {}; left connected", username, e);
      return false;
    }
  }

  /**
   * Disconnects one user whose stored connector id is not in its canonical form,
   * logging a failure rather than throwing it, as {@link #disconnectNow(String)} does.
   *
   * @param username the eXo login to disconnect
   * @return true when the user was disconnected
   */
  boolean disconnectNonCanonical(String username) {
    try {
      userEmailSettingService.deleteUserEmailSetting(username);
      LOG.info("User {} disconnected from their mail connector: its stored id was not in its canonical form", username);
      return true;
    } catch (Exception e) {
      LOG.warn("Cannot disconnect user {} from their mail connector, whose stored id is not in its canonical form", username, e);
      return false;
    }
  }

  /**
   * Whether managed mode, in the given state, no longer governs a user it attached: it
   * designates nothing for them, or another connector than the one they are on. A user
   * already on the connector the state designates is left alone.
   *
   * @param username the eXo login of a user managed mode attached
   * @param designation the designated connector in that state, null when off
   * @param excludedGroups the excluded groups in that state
   * @return true when the user is to be disconnected
   */
  boolean isNoLongerManaged(String username, Long designation, List<String> excludedGroups) {
    Long governing = managedConnectorService.designatedConnectorFor(designation, excludedGroups, username);
    return governing == null || !EmailConnectorUtils.sameConnector(userEmailSettingService.getStoredEmailConnectorId(username), governing);
  }

  /**
   * Disconnects one user, logging a failure rather than throwing it - any failure, a
   * checked exception thrown sneakily included: the next user must still be processed.
   *
   * @param username the eXo login to disconnect
   * @return true when the user was disconnected
   */
  boolean disconnectNow(String username) {
    try {
      userEmailSettingService.deleteUserEmailSetting(username);
      LOG.info("User {} disconnected from their mail connector after an administrator's change", username);
      return true;
    } catch (Exception e) {
      LOG.warn("Cannot disconnect user {} from their mail connector after an administrator's change; the next change or their next login will retry",
               username,
               e);
      return false;
    }
  }

  /**
   * The users managed mode attached that the given state no longer governs. A user
   * whose verdict cannot be computed - an unreadable setting, an identity the platform
   * cannot resolve - is logged and skipped rather than abandoning the others: their
   * next login decides for them.
   */
  private List<String> usersNoLongerManaged(Long designation, List<String> excludedGroups) {
    return userEmailSettingService.getUsersConnectedByManagedMode()
                                  .stream()
                                  .filter(user -> {
                                    try {
                                      return isNoLongerManaged(user, designation, excludedGroups);
                                    } catch (Exception e) {
                                      // Exception, not RuntimeException: a malformed stored document
                                      // surfaces as Jackson's checked exception, thrown sneakily.
                                      LOG.warn("Cannot tell whether managed mode still governs user {}; their next login will decide",
                                               user,
                                               e);
                                      return false;
                                    }
                                  })
                                  .toList();
  }

  private void requireAdministrator(String username) throws IllegalAccessException {
    if (!emailConnectorService.canEdit(username)) {
      throw new IllegalAccessException("User " + username + " may not administer email connectors");
    }
  }

  private static ExecutorService newDisconnectionExecutor() {
    // One thread, an unbounded queue: an administrator's change is rare, and every
    // user it affects must be processed - none may be dropped as a login's attempt is.
    return new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>(), runnable -> {
      Thread thread = new Thread(runnable, "email-managed-disconnection");
      thread.setDaemon(true);
      return thread;
    });
  }

  /** For the tests: run the disconnections on the caller's thread. */
  void setExecutor(Executor executor) {
    this.executor = executor;
  }
}
