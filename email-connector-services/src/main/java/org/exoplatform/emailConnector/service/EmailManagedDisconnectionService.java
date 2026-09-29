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
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.services.connector.credentials.managed.ManagedConnectorService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PreDestroy;

/**
 * Disconnects the users an administrator's change leaves on a mailbox that is no
 * longer theirs (EXO-89654), and counts them beforehand for the warning the
 * administration screen shows.
 * <p>
 * Two changes, two populations:
 * <ul>
 * <li>a change of the managed mode - another designated connector, managed mode off, a
 * group excluded - disconnects the users managed mode attached and no longer governs;
 * the users who chose a connector themselves are never touched;</li>
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
    executor.execute(this::reconcile);
  }

  /**
   * Disconnects, in the background, every user of a connector.
   *
   * @param emailConnectorId the connector whose provider changed
   */
  public void disconnectAllUsersOf(long emailConnectorId) {
    executor.execute(() -> disconnectAll(emailConnectorId));
  }

  /**
   * Selects and disconnects the users managed mode no longer governs, on the
   * executor's thread.
   * <p>
   * {@code @ContainerTransactional} because this runs on a bare executor thread; the
   * work itself is in {@link #reconcileNow()}, the un-advised method.
   */
  @ContainerTransactional
  public void reconcile() {
    reconcileNow();
  }

  /**
   * Disconnects every user of a connector, on the executor's thread.
   *
   * @param emailConnectorId the connector whose provider changed
   */
  @ContainerTransactional
  public void disconnectAll(long emailConnectorId) {
    disconnectAllNow(emailConnectorId);
  }

  /**
   * Selects, against the state now stored, the users managed mode attached and no
   * longer governs, and disconnects them one by one.
   *
   * @return the number of users disconnected
   */
  int reconcileNow() {
    List<String> users = usersNoLongerManaged(emailManagedModeService.getManagedConnectorId(),
                                              emailManagedModeService.getExcludedGroups());
    return (int) users.stream().filter(this::disconnectNow).count();
  }

  /**
   * Disconnects every user of a connector one by one.
   *
   * @param emailConnectorId the connector whose provider changed
   * @return the number of users disconnected
   */
  int disconnectAllNow(long emailConnectorId) {
    return (int) userEmailSettingService.getUserEmailSettingsByEmailConnectorId(emailConnectorId)
                                        .stream()
                                        .filter(this::disconnectNow)
                                        .count();
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
    return governing == null || !Objects.equals(String.valueOf(governing), userEmailSettingService.getStoredEmailConnectorId(username));
  }

  /**
   * Disconnects one user, logging a failure rather than throwing it: the next user
   * must still be processed.
   *
   * @param username the eXo login to disconnect
   * @return true when the user was disconnected
   */
  boolean disconnectNow(String username) {
    try {
      userEmailSettingService.deleteUserEmailSetting(username);
      LOG.info("User {} disconnected from their mail connector after an administrator's change", username);
      return true;
    } catch (RuntimeException e) {
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

  @PreDestroy
  public void stop() {
    if (executor instanceof ExecutorService service) {
      service.shutdownNow();
    }
  }

  /** For the tests: run the disconnections on the caller's thread. */
  void setExecutor(Executor executor) {
    this.executor = executor;
  }
}
