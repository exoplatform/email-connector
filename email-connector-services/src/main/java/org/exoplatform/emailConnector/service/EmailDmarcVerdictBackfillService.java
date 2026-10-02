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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailSecurityUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import io.meeds.common.ContainerTransactional;
import jakarta.annotation.PreDestroy;

/**
 * Fills in the DMARC verdict of mail cached before it was recorded (EXO-90909), so that
 * the mail list offers their sender's brand logo (EXO-90893) without a re-sync.
 * <p>
 * <b>When.</b> A user opening one of their own folders of received mail -- the inbox,
 * the archive, the trash, the spam, a custom folder of their own; never a folder shared
 * with them, nor Sent or Drafts, whose sender is the user -- hands it here, and the
 * listing returns at once: the work runs on a background thread
 * ({@link #THREADS} threads, {@link #QUEUE} waiting folders, the rest dropped until the
 * next opening).
 * <p>
 * <b>What.</b> The {@link #ROWS_PER_RUN} newest rows of that folder with no verdict
 * ({@code DMARC_PASS} null); the mail server is asked for their
 * {@code Authentication-Results} header only, in one command
 * ({@code BODY.PEEK[HEADER.FIELDS (...)]}, nothing marked read), and each verdict is
 * computed as the sync computes it ({@code EmailSecurityUtils#dmarcPassed} under the
 * trusted authserv-ids) and stored on rows still without one. A row whose message the
 * server no longer holds under its UID is recorded as not passed, which is what its
 * null already meant, so the next run moves on to older rows. The rows that passed are
 * pushed to the user ({@link SenderLogoWebSocketService#rowsVerified}): the open list
 * shows their logos in place.
 * <p>
 * <b>Bounds.</b> One run per user per {@link #USER_INTERVAL_MS} at most, whatever the
 * number of folders opened; a folder found with nothing left to fill is not looked at
 * again until the server restarts (a row synced since carries its verdict). Nothing
 * runs at all while no mail server is named as trusted
 * ({@code EmailSecurityUtils#TRUSTED_AUTHSERV_IDS_PROPERTY}) -- without one a pass is
 * never believed, and filling a verdict would only record "not passed" for mail a
 * later setting would believe -- nor while an administrator has switched the logos off.
 */
@Service
public class EmailDmarcVerdictBackfillService {

  /** How many rows one run fills in, the newest first: about a screen of the list. */
  static final int                  ROWS_PER_RUN     = 50;

  /** The shortest time between two runs for one user, in ms. */
  static final long                 USER_INTERVAL_MS = 60_000L;

  /** How many runs go on at once. */
  static final int                  THREADS          = 1;

  /** How many runs may wait for a thread; past it they are dropped. */
  static final int                  QUEUE            = 20;

  /** The built-in folders of received mail a run covers; custom ones are added by key. */
  static final Set<String>          FOLDERS          = Set.of(MailFolder.INBOX, MailFolder.ARCHIVE, MailFolder.TRASH, MailFolder.JUNK);

  private static final Log          LOG              = ExoLogger.getLogger(EmailDmarcVerdictBackfillService.class);

  @Autowired
  private EmailConnectorService      emailConnectorService;

  @Autowired
  private EmailBoxStorage            emailBoxStorage;

  @Autowired
  private SenderLogoWebSocketService senderLogoWebSocketService;

  /** When each user's last run was started, by username. */
  private final Map<String, Long>    lastRuns         = new ConcurrentHashMap<>();

  /** The folders found with nothing left to fill, as {@code username/folder}. */
  private final Set<String>          completed        = ConcurrentHashMap.newKeySet();

  private final ExecutorService      pool             = newPool();

  private Executor                   executor         = pool;

  private LongSupplier               clock            = System::currentTimeMillis;

  /**
   * Hands a folder the user just opened to the background fill, unless the bounds say
   * otherwise. Returns at once; nothing is read on the caller's thread but the switches.
   *
   * @param username the mailbox owner, who opened the folder
   * @param folder the folder's key; one of {@link #FOLDERS} or a custom folder of the
   *          user's own -- the caller never hands a folder shared with them
   * @param reader reads the headers from the user's own mailbox and that folder
   * @return true when a run was queued
   */
  public boolean schedule(String username, String folder, AuthenticationResultsReader reader) {
    if (StringUtils.isBlank(username) || reader == null || !covers(folder) || EmailSecurityUtils.trustedAuthservIds().isEmpty()
        || completed.contains(key(username, folder)) || !emailConnectorService.isSenderLogosEnabled()) {
      return false;
    }
    long now = clock.getAsLong();
    boolean[] due = new boolean[1];
    lastRuns.compute(username, (user, last) -> {
      due[0] = last == null || now - last >= USER_INTERVAL_MS;
      return due[0] ? now : last;
    });
    if (!due[0]) {
      return false;
    }
    try {
      executor.execute(() -> run(username, folder, reader));
      return true;
    } catch (RejectedExecutionException e) {
      LOG.debug("Too many DMARC verdict fills pending; folder {} of user {} waits for its next opening", folder, username);
      return false;
    }
  }

  /**
   * One run, on the pool's thread: the container bound around it, which the storage and
   * the mail server's credentials need.
   *
   * @param username the mailbox owner
   * @param folder the folder's key
   * @param reader reads the headers
   */
  @ContainerTransactional
  public void run(String username, String folder, AuthenticationResultsReader reader) {
    fill(username, folder, reader);
  }

  /**
   * Stops the background runs.
   */
  @PreDestroy
  public void stop() {
    pool.shutdownNow();
  }

  /**
   * One run's work, with the container its caller set: the rows read, their headers
   * read from the mail server, the verdicts stored, the passes pushed.
   *
   * @param username the mailbox owner
   * @param folder the folder's key
   * @param reader reads the headers
   * @return how many rows got a verdict
   */
  int fill(String username, String folder, AuthenticationResultsReader reader) {
    Set<String> trusted = EmailSecurityUtils.trustedAuthservIds();
    if (trusted.isEmpty()) {
      return 0;
    }
    List<Email> rows = emailBoxStorage.getEmailsWithoutDmarcVerdict(username, folder, ROWS_PER_RUN);
    if (rows.isEmpty()) {
      completed.add(key(username, folder));
      return 0;
    }
    Map<Long, String[]> headers;
    try {
      headers = reader.read(rows);
    } catch (Exception e) {
      LOG.debug("Could not read the authentication headers of folder {} of user {}; tried again at a later opening", folder, username, e);
      return 0;
    }
    List<Long> passed = new ArrayList<>();
    List<Long> notPassed = new ArrayList<>();
    Set<String> passedSenders = new LinkedHashSet<>();
    for (Email row : rows) {
      String[] header = headers == null ? null : headers.get(row.getMailRemoteId());
      String address = row.getSender() == null ? null : row.getSender().getAddress();
      if (header != null && EmailSecurityUtils.dmarcPassed(header, address, trusted)) {
        passed.add(row.getId());
        passedSenders.add(address);
      } else {
        notPassed.add(row.getId());
      }
    }
    int written = emailBoxStorage.setDmarcVerdict(username, passed, true);
    written += emailBoxStorage.setDmarcVerdict(username, notPassed, false);
    if (rows.size() < ROWS_PER_RUN) {
      completed.add(key(username, folder));
    }
    senderLogoWebSocketService.rowsVerified(username, passed, passedSenders);
    return written;
  }

  /**
   * Replaces the executor, for the tests.
   *
   * @param executor the executor
   */
  void setExecutor(Executor executor) {
    this.executor = executor;
  }

  /**
   * Replaces the clock, for the tests.
   *
   * @param clock the clock, in ms
   */
  void setClock(LongSupplier clock) {
    this.clock = clock;
  }

  /**
   * Whether a folder holds received mail a run covers.
   *
   * @param folder the folder's key
   * @return true for {@link #FOLDERS} and custom folders
   */
  private static boolean covers(String folder) {
    return FOLDERS.contains(folder) || MailFolder.isCustom(folder);
  }

  /**
   * The key a user's folder is remembered as complete under.
   *
   * @param username the mailbox owner
   * @param folder the folder's key
   * @return {@code username/folder}
   */
  private static String key(String username, String folder) {
    return username + "/" + folder;
  }

  /**
   * The background pool: {@link #THREADS} daemon thread, {@link #QUEUE} waiting runs, the
   * rest refused.
   *
   * @return the pool
   */
  private static ExecutorService newPool() {
    return new ThreadPoolExecutor(THREADS,
                                  THREADS,
                                  1,
                                  TimeUnit.MINUTES,
                                  new ArrayBlockingQueue<>(QUEUE),
                                  runnable -> {
                                    Thread thread = new Thread(runnable, "email-connector-dmarc-verdict");
                                    thread.setDaemon(true);
                                    return thread;
                                  },
                                  new ThreadPoolExecutor.AbortPolicy());
  }
}
