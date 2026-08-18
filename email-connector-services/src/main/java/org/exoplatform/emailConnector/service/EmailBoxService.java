/**
 * Copyright (C) 2025 eXo Platform SAS
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

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletionService;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorCompletionService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;
import javax.mail.Authenticator;
import javax.mail.BodyPart;
import javax.mail.FetchProfile;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessageRemovedException;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Store;
import javax.mail.Transport;
import javax.mail.UIDFolder;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.mail.internet.MimeUtility;
import javax.mail.search.AndTerm;
import javax.mail.search.ComparisonTerm;
import javax.mail.search.FlagTerm;
import javax.mail.search.FromStringTerm;
import javax.mail.search.MessageIDTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.SearchException;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.ResyncData;

import org.exoplatform.commons.ObjectAlreadyExistsException;
import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.emailConnector.event.EmailSentEvent;
import org.exoplatform.emailConnector.job.EmailBoxSyncJob;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.FolderSyncSnapshot;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.MailboxSyncState;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailOutgoingAttachment;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSearchResult;
import org.exoplatform.emailConnector.model.EmailSearchResultPage;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.notification.plugin.NewEmailsNotificationPlugin;
import org.exoplatform.emailConnector.plugin.EmailCategoryPlugin;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.EmailThreadingUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.scheduler.JobInfo;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.services.scheduler.PeriodInfo;
import org.exoplatform.social.core.identity.model.Profile;
import org.exoplatform.upload.UploadResource;
import org.exoplatform.upload.UploadService;

import io.meeds.social.category.model.CategoryObject;
import io.meeds.social.category.model.CategoryWithName;
import io.meeds.social.category.service.CategoryLinkService;
import io.meeds.social.category.service.CategoryService;
import io.meeds.social.html.utils.HtmlUtils;
import io.meeds.social.util.JsonUtils;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * A Service to manage and synchronize email box
 */
@Service
public class EmailBoxService {

  private static final Log        LOG                                                         =
                                      ExoLogger.getLogger(EmailBoxService.class);

  // Sent/Archive are supplementary conversation context, so they sync a much
  // smaller window than the inbox — this bounds the (potentially slow) one-time
  // backfill and every subsequent sync on large mailboxes.
  private static final int        NON_INBOX_FOLDER_SYNC_LIMIT                                 = 100;

  // The message headers this service reads by name. Named once so the prefetch list below and
  // the call sites that read them cannot drift apart: a header missing from the prefetch costs
  // a server round-trip per message, and nothing fails loudly when that happens.
  private static final String     HEADER_REFERENCES                                           = "References";

  private static final String     HEADER_IN_REPLY_TO                                          = "In-Reply-To";

  private static final String     HEADER_THREAD_INDEX                                         = "Thread-Index";

  private static final String     HEADER_AUTO_SUBMITTED                                       = "Auto-Submitted";

  private static final String     HEADER_PRECEDENCE                                           = "Precedence";

  private static final String     HEADER_LIST_ID                                              = "List-Id";

  private static final String     HEADER_LIST_POST                                            = "List-Post";

  private static final String     HEADER_LIST_UNSUBSCRIBE                                     = "List-Unsubscribe";

  private static final String     HEADER_ORIGINAL_SENDER                                      = "X-Original-Sender";

  // The IMAP name of the inbox, as opposed to MailFolder.INBOX, our own folder discriminator.
  private static final String     INBOX_FOLDER_NAME                                           = "INBOX";

  // The store/folder teardown and connect failures. The IMAP methods predating this one still
  // inline these strings; adopting the constants there is a file-wide cleanup that belongs on
  // develop, not in a backport -- rewriting those lines drags a lot of untested legacy teardown
  // into this PR's new code. New code uses the constants.
  private static final String     STORE_CLOSE_ERROR_MESSAGE                                   = "Error when closing store";

  private static final String     INBOX_CLOSE_ERROR_MESSAGE                                   = "Error when closing inbox";

  private static final String     STORE_CONNECT_ERROR_MESSAGE                                 =
                                                                                              "Error when connecting store for user {}";

  private static final String     STORE_CONNECT_ERROR_FORMAT                                  =
                                                                                              "Error when connecting store for user %s";

  // Every header createEmails reads per message. They must be fetched in the one batched
  // FETCH: JavaMail otherwise goes back to the server for each header of each message.
  private static final List<String> PREFETCHED_HEADERS                                        =
                                                                                              List.of(HEADER_REFERENCES,
                                                                                                      HEADER_IN_REPLY_TO,
                                                                                                      HEADER_THREAD_INDEX,
                                                                                                      HEADER_AUTO_SUBMITTED,
                                                                                                      HEADER_PRECEDENCE,
                                                                                                      HEADER_LIST_ID,
                                                                                                      HEADER_LIST_POST,
                                                                                                      HEADER_LIST_UNSUBSCRIBE,
                                                                                                      HEADER_ORIGINAL_SENDER);

  // How long a new-mail notification waits for someone to classify the messages first. Short,
  // because with no such consumer this is pure added latency.
  private static final long       NOTIFICATION_GRACE_MS                                       = 10000L;

  // The outside limit once a consumer has claimed the wait: if it never reports back, the
  // notification is late rather than lost.
  // How long the notification may wait with NO sign of progress. Re-armed every time a
  // claim is taken or released, so it bounds silence rather than the run: a mailbox that
  // keeps classifying is never cut off, however large it is.
  private static final long       NOTIFICATION_MAX_WAIT_MS                                    = 15 * 60 * 1000L;

  // Cooldown before a BLOCKED mailbox is allowed to retry a sync, so BLOCKED is a temporary
  // backoff rather than a permanent dead-end (a successful retry clears it).
  private static final long       BLOCKED_RETRY_COOLDOWN_MS                                   = 30 * 60 * 1000L;

  // Caps the OR-of-Message-ID search when completing a thread from the archive on
  // open, so an unusually long conversation can't build a giant IMAP SEARCH.
  private static final int        ARCHIVE_COMPLETION_SEARCH_LIMIT                             = 50;

  // Hard cap on the hits a mailbox search returns. A SEARCH over a 161k-message
  // mailbox can match thousands of UIDs; only this many (the newest) get their
  // envelope fetched, so the result list's cost stays one bounded batched FETCH
  // whatever the match count. The full count is still reported to the caller.
  private static final int        SEARCH_MAX_RESULTS                                          = 50;

  /** How much of a message to quote when the search matched nothing in its body. */
  private static final int        EXCERPT_LENGTH                                              = 180;

  /** How much text to keep on either side of the words that matched. */
  private static final int        EXCERPT_CONTEXT                                             = 80;

  // Concurrent IMAP connections used to prefetch new message bodies during a sync.
  // Bodies are the one per-message cost the batched FETCH profile cannot absorb: each
  // body is its own FETCH BODY[n] round-trip (several for nested multiparts, plus a
  // full download per inline cid: image), all serialized on the single sync connection
  // — on a 500-message reset that latency IS the sync time. The default stays modest
  // because Gmail allows ~15 simultaneous IMAP connections per account and that budget
  // is shared with the user's other mail clients.
  private static final String     BODY_PREFETCH_WORKERS_PROPERTY                              =
                                                                 "email.connector.sync.body.fetch.threads";

  // Eight, measured rather than guessed: the same thousand-message mailbox downloaded in
  // 3m52 over five connections and 2m45 over eight, with every body arriving and no
  // connection refused. The gain is real but sub-linear -- 1.6x the connections bought
  // 1.4x the speed -- so the provider is already throttling per account and raising this
  // further buys less each time while eating into a budget shared with the user's phone
  // and browser. Raise it only with the same measurement in hand.
  private static final int        DEFAULT_BODY_PREFETCH_WORKERS                               = 8;

  // Below this many new messages the parallel prefetch is skipped: every worker pays a
  // TLS handshake + IMAP login + folder SELECT before its first FETCH, which costs more
  // than the handful of serial body fetches it would save on an ordinary periodic sync.
  // Messages per prefetch slice. Deliberately far below the connection count: one slice
  // per connection would have every worker finish at the same moment, so the mailbox
  // would stay empty for the whole download and then fill in one jump.
  private static final int        BODY_PREFETCH_SLICE_SIZE                                    = 20;

  private static final int        BODY_PREFETCH_MIN_MESSAGES                                  = 10;

  // How many drained slices are grouped into one NEW_EMAILS_SYNCED broadcast, so the AI
  // categorization starts while the rest of the mailbox is still downloading instead of
  // waiting for the whole folder. Three slices of 20 = 60 messages = exactly four of the
  // categorizer's batches of 15: broadcasting every slice would split each group into a
  // 15 + 5 and inflate the number of LLM requests by half -- and the provider's
  // rate limiting is already the categorization's observed failure mode.
  private static final int        BODY_PREFETCH_SLICES_PER_BROADCAST                          = 3;

  // Outside bound on the whole parallel prefetch. The per-connection socket timeouts
  // (see UserEmailSettingService#connect) already unstick a dead worker; this bound only
  // guarantees the sync thread itself can never wait forever, and losing the race is
  // harmless — unmapped messages are fetched serially, exactly as before this existed.
  // How long the drain may go with NO slice arriving at all. Refreshed on every slice, so
  // it bounds silence rather than the whole folder.
  private static final long       BODY_PREFETCH_TIMEOUT_MS                                    = 10 * 60 * 1000L;

  // How long the drain waits for at least ONE slice to complete before giving up on the
  // parallel prefetch. Slices are consumed in completion order, so a single slow slice no
  // longer holds anything up -- one message trickling in behind a slow connection once
  // stalled a whole mailbox for minutes while four workers sat idle holding finished
  // data. What this bounds is total silence: with several connections in flight, nothing
  // at all completing for this long means the connections are dead, not slow, and the
  // remaining bodies are fetched serially, which costs a fraction of a second each.
  private static final long       BODY_PREFETCH_SLICE_TIMEOUT_MS                              = 90 * 1000L;

  // The RFC 7162 capability behind the skip check's flag-change signal. Referenced in
  // two places on purpose: the folder OPEN asks for mod-sequences explicitly, and the
  // skip check refuses to skip without them.
  private static final String     CONDSTORE_CAPABILITY                                        = "CONDSTORE";

  // Where each user's MailboxSyncState lives (SettingService, user context, the
  // add-on's scope). Its OWN key, not a field of userEmailSetting: the setting
  // object is read-modified-written by preference flows, and sync bookkeeping
  // racing user edits over a single JSON blob is how settings get clobbered.
  private static final String     MAILBOX_SYNC_STATE_KEY                                      = "emailBoxSyncState";

  // The nameIds of the add-on's own default email categories (see default-categories.json).
  // The platform's CategoryImportService persists each nameId -> created category id in
  // SettingService, so the assignable email category ids are resolved from there.
  private static final List<String> DEFAULT_EMAIL_CATEGORY_NAME_IDS                          =
                                                                   List.of("emailImportantCategory",
                                                                           "emailInvitationCategory",
                                                                           "emailNotificationCategory",
                                                                           "emailToReviewCategory");

  // Unversioned coupling: these two literals mirror CategoryImportService's own private
  // CATEGORY_CONTEXT/CATEGORY_IMPORT_SCOPE in Meeds-io/social — there is no public accessor
  // nor nameId -> id resolution API to call instead today. If social ever renames them or
  // changes how it persists that mapping, getDefaultEmailCategoryIds() silently returns an
  // empty list (no compile error) rather than failing loudly. To be replaced by a supported
  // CategoryService lookup once social exposes one.
  private static final Context      CATEGORY_IMPORT_CONTEXT                                   = Context.GLOBAL.id("CATEGORY");

  private static final Scope        CATEGORY_IMPORT_SCOPE                                     =
                                                                          Scope.APPLICATION.id("CATEGORY_IMPORT");

  private static final String     USER_NOT_ALLOWED_FOR_SYNCHRONIZE_EMAIL_MESSAGE              =
                                                                                 "User %s is not allowed to synchronize email";

  private static final String     USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE                      =
                                                                         "User %s is not allowed to get email";

  private static final String     USER_NOT_ALLOWED_FOR_GET_EMAIL_ATTACHMENT                   =
                                                                            "User %s is not allowed to get email attachment";

  private static final String     USER_NOT_ALLOWED_FOR_BROADCAST_OPEN_EMAIL_EVENT_MESSAGE     =
                                                                                          "User %s is not allowed to broadcast open email event";

  private static final String     USER_NOT_ALLOWED_FOR_BROADCAST_ACCESS_WEBMAIL_EVENT_MESSAGE =
                                                                                              "User %s is not allowed to broadcast access webmail event";

  private static final String     USER_NOT_ALLOWED_FOR_SEARCH_EMAIL_MESSAGE                   =
                                                                            "User %s is not allowed to search email";

  private static final String     USER_NOT_ALLOWED_FOR_UPDATE_EMAIL_MESSAGE                   =
                                                                            "User %s is not allowed to update email";

  private static final String     USER_NOT_ALLOWED_FOR_DELETE_EMAIL_MESSAGE                   =
                                                                            "User %s is not allowed to delete email";

  private static final String     USER_NOT_ALLOWED_FOR_ARCHIVE_EMAIL_MESSAGE                  =
                                                                             "User %s is not allowed to archive email";

  private static final String     USER_NOT_ALLOWED_FOR_SEND_EMAIL_MESSAGE                     =
                                                                          "User %s is not allowed to send email";

  // Maximum cumulative size (bytes) allowed for the attachments of a single outgoing email (SMTP-friendly, 25 MB).
  private static final long       MAX_OUTGOING_ATTACHMENTS_SIZE                               = 25L * 1024 * 1024;

  @Autowired
  private CategoryLinkService     categoryLinkService;

  @Autowired
  private EmailFavoriteService    emailFavoriteService;

  // Mailboxes with a synchronization running right now, so two can never overlap and cache
  // the same message twice.
  //
  // In-JVM only, deliberately: on a clustered deployment two nodes can still sync the same
  // mailbox at once -- the original duplicate-row bug, at cluster scope. Closing that needs a
  // shared lock rather than a set, which is its own change; this guard covers the overlap
  // that actually happens today, where the sync job and a user-triggered reset share a JVM.
  private final Set<String>                      syncingUsers          = ConcurrentHashMap.newKeySet();

  // Notifications waiting for their messages to be classified, keyed by mailbox owner.
  private final Map<String, PendingNotification> pendingNotifications = new ConcurrentHashMap<>();

  // One daemon thread: the work is a short database read plus a notification dispatch.
  private final ScheduledExecutorService         notificationScheduler =
                                                                       Executors.newSingleThreadScheduledExecutor(runnable -> {
                                                                         Thread thread = new Thread(runnable,
                                                                                                    "email-new-mail-notification");
                                                                         thread.setDaemon(true);
                                                                         return thread;
                                                                       });

  // Stamps every PendingNotification this service creates. A scheduled backstop is armed for
  // one specific window, and by the time it runs that window may have been superseded by a
  // newer one -- cancel(false) cannot stop a task that has already started. The generation is
  // how the task tells "the window I was armed for" from "whatever is mapped now".
  private final AtomicLong                       notificationGenerations = new AtomicLong();

  @Autowired
  private CategoryService         categoryService;

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @Autowired
  private EmailBoxStorage         emailBoxStorage;

  @Autowired
  private SettingService          settingService;

  @Autowired
  private JobSchedulerService     jobSchedulerService;

  @Autowired
  private ListenerService         listenerService;

  @Autowired
  private EmailConnectorService   emailConnectorService;

  // Publishes EmailSentEvent after a successful send, so contact collection (and any
  // later consumer) learns who the user writes to without this class knowing about it.
  @Autowired
  private ApplicationEventPublisher eventPublisher;

  @PostConstruct
  public void initEmailBoxSyncJob() {
    List<Context> contexts =
                           settingService.getContextsByTypeAndScopeAndSettingName(Context.USER.getName(),
                                                                                  Scope.APPLICATION.getName(),
                                                                                  EmailConnectorService.EMAIL_CONNECTOR_SCOPE_ID,
                                                                                  EmailConnectorService.USER_EMAIL_SETTING_KEY,
                                                                                  0,
                                                                                  Integer.MAX_VALUE);
    for (Context context : contexts) {
      try {
        scheduleEmailBoxUserSyncJob(context.getId());
      } catch (Exception e) {
        LOG.warn("Error scheduling email box sync for user {}", context.getId(), e);
      }
    }
  }

  /**
   * Stops the notification scheduler with the Spring context. The thread is a daemon, so a
   * JVM shutdown never needed this; a context reload (hot redeploy, a test suite building
   * several contexts) does, otherwise each reload leaves its own timer thread behind.
   */
  @PreDestroy
  public void stopNotificationScheduler() {
    notificationScheduler.shutdownNow();
  }

  /**
   * Synchronize user email box.
   *
   * @param username user of which email box will be synchronized
   * @throws IllegalAccessException if user is not allowed to synchronize email
   *           connector
   */
  public void synchronize(String username) throws IllegalAccessException {
    synchronize(username, false);
  }

  /**
   * Synchronize the user's mailbox, optionally restricted to the inbox.
   *
   * @param username the mailbox owner
   * @param inboxOnly when {@code true}, skip the Sent and Archive folders. They are only
   *          needed so a conversation shows the user's own replies and archived messages
   *          inline, they are never mutated locally, and re-fetching them costs one message
   *          body per row -- so a caller that just needs a fresh inbox (see
   *          {@link #resetAndResynchronize(String)}) should not pay for them.
   * @throws IllegalAccessException if the user is not allowed to synchronize
   */
  private void synchronize(String username, boolean inboxOnly) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (!canSynchronize(userEmailSetting, username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SYNCHRONIZE_EMAIL_MESSAGE, username));
    }
    // The IN_PROGRESS check above lets a sync through once it looks stale, which is deliberate
    // -- a sync killed mid-flight must not lock the mailbox forever. But "stale" is judged on
    // the sync period, so a sync that simply takes longer than that period is treated as dead
    // while it is still running, and a second one starts alongside it. Both then cache the same
    // messages, and the mailbox ends up with duplicate rows that break every lookup keyed on
    // (user, folder, UID). Only one sync per user in this JVM, whatever the status says.
    if (!syncingUsers.add(username)) {
      LOG.info("A synchronization is already running for user {}; skipping this one", username);
      return;
    }
    try {
      doSynchronize(username, userEmailSetting, inboxOnly);
    } finally {
      syncingUsers.remove(username);
    }
  }

  /**
   * Runs one synchronization for a caller that already holds the {@code syncingUsers} guard
   * for this user -- {@link #synchronize(String, boolean)} or {@link #resetAndResynchronize}.
   * Split out so the guard is taken and released in exactly one place per entry point: every
   * statement that can throw is inside the caller's try/finally, so no failure path can leak
   * the per-user lock and shut that mailbox out of syncing until the JVM restarts.
   *
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding, already loaded and access-checked
   * @param inboxOnly whether to sync the INBOX alone, skipping Sent and Archive
   */
  private void doSynchronize(String username, UserEmailSetting userEmailSetting, boolean inboxOnly) {
    Store store = null;
    // The sync memory: per-folder change snapshots + discovered folder names. Loaded
    // once, mutated by the folder syncs below, persisted at the end only when it
    // actually changed (the original serialized form is the dirty check, so a run
    // that skipped everything writes nothing). Loaded inside the try so a failure to
    // read or serialize it cannot escape before the finally that releases the guard.
    MailboxSyncState syncState = null;
    String originalSyncStateJson = null;
    try {
      // Snapshotted so the badge is only notified when the number it displays
      // actually moved: a sync cycle that changed nothing would otherwise cost an
      // eviction, a WebSocket frame and a REST re-fetch for every online user,
      // every period, against the specification's "no recurring background load
      // for users who are not consulting their badges"
      long unreadCountBeforeSync = emailBoxStorage.countUnreadEmails(username);
      syncState = loadMailboxSyncState(username);
      originalSyncStateJson = JsonUtils.toJsonString(syncState);
      store = userEmailSettingService.connect(userEmailSetting);
      updateEmailSyncStatus(username, SyncStatus.IN_PROGRESS);
      int emailBoxCacheSize = emailConnectorService.getEmailBoxCacheSize();
      // INBOX drives the new-mail notifications; Sent and Archive are cached (best
      // effort — a missing folder must not fail the sync) so a conversation shows the
      // user's own replies ("Me") and previously-archived messages inline.
      syncFolderIfChanged(store, store.getFolder(INBOX_FOLDER_NAME), MailFolder.INBOX, username, userEmailSetting, emailBoxCacheSize, true, syncState);
      if (!inboxOnly) {
        int nonInboxWindow = Math.min(emailBoxCacheSize, NON_INBOX_FOLDER_SYNC_LIMIT);
        try {
          syncFolderIfChanged(store, resolveSentFolder(store, syncState), MailFolder.SENT, username, userEmailSetting, nonInboxWindow, false, syncState);
        } catch (Exception e) {
          LOG.warn("Could not sync the Sent folder for user {}", username, e);
        }
        try {
          syncFolderIfChanged(store, resolveArchiveFolder(store, syncState), MailFolder.ARCHIVE, username, userEmailSetting, nonInboxWindow, false, syncState);
        } catch (Exception e) {
          LOG.warn("Could not sync the Archive folder for user {}", username, e);
        }
      }
      updateEmailSyncStatus(username, SyncStatus.SUCCESS);
      if (emailBoxStorage.countUnreadEmails(username) != unreadCountBeforeSync) {
        broadcastUnreadCountChanged(username);
      }
      // The flags just pulled from the server are the ones the Favorites drawer
      // must show: a mail starred from a phone arrives here, and a reset gave
      // every cached mail a new id that the stored favorites no longer match.
      emailFavoriteService.reconcileFavorites(username);
    } catch (Exception e) {
      updateEmailSyncStatus(username, SyncStatus.FAILURE);
      LOG.error("Error when user {} synchronization ", username, e);
    } finally {
      // Persisted in the finally so the folders that DID sync keep their fresh
      // snapshots even when a later folder failed; a folder that failed mid-sync
      // never returned a snapshot, so its stale one keeps forcing the full path.
      // Null when the load itself failed -- there is then nothing to persist.
      if (syncState != null) {
        saveMailboxSyncState(username, syncState, originalSyncStateJson);
      }
      try {
        if (store != null && store.isConnected()) {
          store.close();
        }
      } catch (MessagingException messagingException) {
        LOG.warn("Error when closing store", messagingException);
      }
    }
  }

  /**
   * Reset the user's inbox and re-download it from the server. The cached INBOX rows
   * (and their category links) are cleared first, then the inbox is synchronized
   * again: because its cache is empty, every message in the server window is treated
   * as new and re-downloaded. The messages on the server are never modified. This is
   * a recovery action for a stale or inconsistent local cache; it also clears a
   * BLOCKED / failed-attempt backoff so the immediate resync is allowed to run.
   * Manually-applied categories are dropped (re-created rows get new local ids); AI
   * auto-categorization, when enabled, re-tags the messages on the resync.
   * <p>
   * Scoped to the inbox on purpose: Sent and Archive are read-only mirrors kept for
   * the conversation reader, so they cannot be the stale cache being recovered, and
   * re-downloading them costs one message body per row -- minutes of waiting the
   * caller gains nothing from. The scheduled sync keeps them current.
   *
   * @param username user whose mailbox is reset and re-synchronized
   * @throws IllegalAccessException if the user is not allowed to synchronize the
   *           email connector
   * @throws IllegalStateException if a synchronization is currently running
   */
  public void resetAndResynchronize(String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SYNCHRONIZE_EMAIL_MESSAGE, username));
    }
    // Refuse to reset while a sync is genuinely running (recent IN_PROGRESS), so the
    // two do not race over the cache. A stale IN_PROGRESS (past the sync period, i.e. a
    // stuck sync) is allowed through, since recovering from it is the point of a reset.
    if (SyncStatus.IN_PROGRESS.equals(userEmailSetting.getEmailSyncStatus())) {
      long nextAllowedSync = userEmailSetting.getLastEmailSyncStartDate()
          + EmailConnectorUtils.getEmailBoxUserSyncPeriod(userEmailSetting) * 60000L;
      if (System.currentTimeMillis() <= nextAllowedSync) {
        throw new IllegalStateException("emailConnector.reset.syncInProgress");
      }
    }
    // Hold the same per-user guard the sync itself takes, for the whole clear-then-resync.
    // The persisted status above is not enough on its own: a scheduled sync can start in the
    // gap between reading it and clearing the cache, and would then reconcile against rows
    // being deleted underneath it. Taking the guard here also stops the resync below from
    // being swallowed by the "already running" branch, which would leave the mailbox cleared
    // and empty until the next periodic run -- the opposite of an immediate recovery.
    if (!syncingUsers.add(username)) {
      throw new IllegalStateException("emailConnector.reset.syncInProgress");
    }
    try {
      // Clear the cached INBOX (deleteEmails also unlinks each email's category links). Sent
      // and Archive are deliberately left alone: they are never mutated locally, so they cannot
      // be the stale cache the user is recovering from, and re-downloading them costs a message
      // body per row -- on a 100-message mailbox that is minutes of waiting for folders the
      // inbox view, the notifications and the AI categorization never read.
      deleteUserEmails(username, MailFolder.INBOX);
      // Clear any BLOCKED / failed-attempt backoff so the immediate resync is not refused.
      userEmailSetting.setEmailSyncFailedAttemps(0);
      userEmailSetting.setEmailSyncStatus(SyncStatus.SUCCESS);
      userEmailSettingService.setUserEmailSetting(userEmailSetting, username, false);
      // Full re-download of the inbox; the scheduled sync keeps the other folders current.
      // Straight to doSynchronize: the guard is already held here, and the access check plus
      // the backoff reset above are exactly what synchronize() would have re-verified.
      doSynchronize(username, userEmailSettingService.getUserEmailSetting(username), true);
    } finally {
      syncingUsers.remove(username);
    }
  }

  /**
   * Synchronize one remote folder into the local cache: pull its most recent
   * {@code emailBoxCacheSize} messages, create the new ones (stamped with
   * {@code folderKey}), and drop the locally-cached ones no longer present. IMAP
   * UIDs are per-folder, so every read/write here is scoped to {@code folderKey}.
   *
   * @param folder the remote folder (may be {@code null} when not discovered)
   * @param folderKey the {@link MailFolder} discriminator to stamp
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding, used by the parallel
   *          body prefetch to open its own extra IMAP connections
   * @param emailBoxCacheSize the number of most recent messages to keep
   * @param notify whether to fire the new-mail notification (INBOX only)
   * @return the folder's change snapshot as of this sync's SELECT, for the next
   *         sync's skip-if-unchanged check; null when none could be captured (the
   *         next sync then simply takes the full path again)
   */
  private FolderSyncSnapshot syncFolder(Folder folder,
                          String folderKey,
                          String username,
                          UserEmailSetting userEmailSetting,
                          int emailBoxCacheSize,
                          boolean notify) throws MessagingException, IllegalAccessException {
    if (folder == null) {
      return null;
    }
    try {
      // Phase timings below feed the one-line summary at the end. Permanent
      // operational logging on purpose: the 500->5000 cache-size measurements showed
      // sync cost scaling with mailbox size instead of change size, and deciding WHICH
      // phase to attack next (IMAP window fetch? cache load? reconcile?) was guesswork
      // without a per-phase split.
      long openStart = System.currentTimeMillis();
      openFolderForSync(folder, folderKey, username);
      UIDFolder uidFolder = (UIDFolder) folder;
      int totalMessages = folder.getMessageCount();
      if (totalMessages == 0) {
        // No snapshot for an empty folder: today's behavior is to do nothing here (a
        // cache whose folder emptied remotely keeps its rows), and snapshotting an
        // untouched cache would only set the skip check in stone over it.
        return null;
      }
      int startIndex = Math.max(1, totalMessages - emailBoxCacheSize + 1);
      Message[] serverMessages = folder.getMessages(startIndex, totalMessages);
      // Captured NOW, from the SELECT-time values, not at close: mail landing while
      // the download runs would otherwise be recorded in the snapshot without being
      // in the cache, and the next sync would skip right over it. Anything arriving
      // after this line makes the next check mismatch, which is the safe direction.
      FolderSyncSnapshot folderSnapshot = captureFolderSnapshot(folder, totalMessages, emailBoxCacheSize);
      long windowFetchStart = System.currentTimeMillis();
      // Prefetch flags + envelope + UID + headers + MIME structure in a single
      // round-trip (see buildSyncFetchProfile for why every piece is in there).
      folder.fetch(serverMessages, buildSyncFetchProfile());
      long cacheLoadStart = System.currentTimeMillis();
      // The light sync view (no bodies, no attachments, no category links): the sync
      // only compares UIDs and flags, and loading the full entities was one of the two
      // dominant costs of a no-op sync at 5000 cached messages.
      List<Email> folderEmails = emailBoxStorage.getSyncEmails(username, folderKey);
      Map<Long, Email> knownEmailsByUid = new HashMap<>();
      for (Email folderEmail : folderEmails) {
        if (folderEmail.getMailRemoteId() != null) {
          knownEmailsByUid.putIfAbsent(folderEmail.getMailRemoteId(), folderEmail);
        }
      }
      long reconcileStart = System.currentTimeMillis();
      if (notify) {
        // Open the notification window BEFORE anything is broadcast: the groups of new
        // messages stream out below while the download is still running, and a consumer's
        // hold-back claim must always find the window to attach to.
        openNotificationWindow(username, folderEmails);
      }
      // Reconcile the already-cached messages first (flag diffs in bulk, threading
      // backfill per row only where needed), so a routine sync's visible state is
      // correct before any download starts.
      int flagUpdates = reconcileKnownEmails(uidFolder, serverMessages, knownEmailsByUid, username, folderKey);
      // Counts every MIME part body pulled for the NEW messages, across the prefetch
      // workers and the serial fallback alike — the parts-per-message ratio is the
      // measurement that decides whether deferring inline cid: images is worth building.
      MimePartStats fetchedParts = new MimePartStats();
      long downloadStart = System.currentTimeMillis();
      // Bodies are the one per-message cost the batched FETCH above cannot absorb, so
      // fetch them for the new messages over several extra IMAP connections, caching each
      // slice as it lands. Best-effort: a miss just falls back to the serial fetch.
      List<Long> newEmailIds =
                             prefetchAndCreateEmails(folder,
                                                     uidFolder,
                                                     serverMessages,
                                                     folderEmails,
                                                     knownEmailsByUid,
                                                     username,
                                                     folderKey,
                                                     userEmailSetting,
                                                     notify,
                                                     fetchedParts);
      long cleanupStart = System.currentTimeMillis();
      cleanupObsoleteEmails(uidFolder, folderEmails, serverMessages, username, emailBoxCacheSize);
      long cleanupEnd = System.currentTimeMillis();
      LOG.info("Synchronized folder {} of user {}: {} message(s) on the server, {} already known, {} newly cached, {} flag update(s)"
          + " | open {} ms, window fetch {} ms, cache load {} ms, reconcile {} ms, download+create {} ms, cleanup {} ms"
          + " | {} MIME part(s) fetched for {} new message(s) [{}]",
               folderKey,
               username,
               serverMessages.length,
               serverMessages.length - newEmailIds.size(),
               newEmailIds.size(),
               flagUpdates,
               windowFetchStart - openStart,
               cacheLoadStart - windowFetchStart,
               reconcileStart - cacheLoadStart,
               downloadStart - reconcileStart,
               cleanupStart - downloadStart,
               cleanupEnd - cleanupStart,
               fetchedParts.total(),
               newEmailIds.size(),
               fetchedParts.breakdown());
      if (notify) {
        // The per-group NEW_EMAILS_SYNCED broadcasts already went out above, inside
        // prefetchAndCreateEmails, while the download was still running. What remains
        // here is the end of the run: close the notification window (arming the grace
        // delay when no consumer claimed it), then tell consumers no more groups are
        // coming -- whole-run work like conversation alignment can only start now.
        completeNotificationWindow(username, folderEmails);
        broadcastNewEmailsSyncCompleted(username, newEmailIds);
      }
      return folderSnapshot;
    } finally {
      if (folder.isOpen()) {
        try {
          folder.close(false);
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing folder {} for user {}", folderKey, username, messagingException);
        }
      }
    }
  }

  /**
   * Opens a folder for synchronization, asking the server for mod-sequences
   * explicitly ({@code SELECT ... (CONDSTORE)}, RFC 7162) when it advertises
   * CONDSTORE. Gmail volunteers {@code HIGHESTMODSEQ} on a plain SELECT, but that
   * is generosity, not a guarantee: RFC 7162 only obliges the server once the
   * client enables CONDSTORE, and Stalwart takes the RFC at its word — observed
   * live as user benjamin's snapshots being stored with {@code highestModSeq -1}
   * and every sync logging "snapshot incomplete -&gt; full sync", forever. Asking
   * explicitly is what the RFC intends and costs Gmail nothing (one atom on the
   * SELECT line; the response it already sent unprompted).
   * <p>
   * Deliberately NOT QRESYNC: {@link ResyncData#CONDSTORE} only adds the
   * {@code (CONDSTORE)} parameter — {@code VANISHED} responses and the changed
   * expunge surfacing that come with QRESYNC require a capability we never request
   * (and JavaMail would reject without it), so the session's untagged responses
   * keep today's shape. Best-effort throughout: a server that advertises CONDSTORE
   * but rejects the parameter gets a plain open (the capture then simply yields no
   * mod-sequence and the skip stays off, exactly as before this method existed).
   *
   * @param folder the remote folder to open READ_ONLY
   * @param folderKey the {@link MailFolder} discriminator, for the log
   * @param username the mailbox owner, for the log
   * @throws MessagingException if even the plain open fails
   */
  private void openFolderForSync(Folder folder, String folderKey, String username) throws MessagingException {
    try {
      if (folder instanceof IMAPFolder imapFolder && folder.getStore() instanceof IMAPStore imapStore
          && imapStore.hasCapability(CONDSTORE_CAPABILITY)) {
        imapFolder.open(Folder.READ_ONLY, ResyncData.CONDSTORE);
        return;
      }
    } catch (Exception e) {
      if (folder.isOpen()) {
        // The CONDSTORE open itself succeeded and something later failed; the folder
        // is usable, so hand it over rather than re-opening it.
        LOG.warn("Unexpected error after opening folder {} of user {} with CONDSTORE; keeping the open folder",
                 folderKey,
                 username,
                 e);
        return;
      }
      LOG.warn("Could not open folder {} of user {} with CONDSTORE; falling back to a plain open (the change-skip"
          + " stays off for this folder until mod-sequences are available)", folderKey, username, e);
    }
    folder.open(Folder.READ_ONLY);
  }

  /**
   * The cheap-change gate in front of {@link #syncFolder}: skip the folder outright
   * when the server provably did not change since the snapshot the last full sync
   * captured, otherwise run the full sync and remember what it saw. The measured
   * motivation: a no-op INBOX sync at 1000 cached spent 8531 of 8615 ms — 98% — in
   * the window FETCH re-downloading every envelope, header and MIME structure to
   * discover nothing changed, every period, for every user, forever.
   *
   * @param store the connected store, for the CONDSTORE capability check
   * @param folder the remote folder (may be {@code null} when not discovered)
   * @param folderKey the {@link MailFolder} discriminator
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   * @param windowSize the number of most recent messages to keep
   * @param notify whether to fire the new-mail notification (INBOX only)
   * @param syncState the mailbox's sync memory, updated in place with the fresh
   *          snapshot after a successful full sync
   * @throws MessagingException if the folder cannot be read
   * @throws IllegalAccessException if the user is not allowed to cache messages
   */
  private void syncFolderIfChanged(Store store,
                                   Folder folder,
                                   String folderKey,
                                   String username,
                                   UserEmailSetting userEmailSetting,
                                   int windowSize,
                                   boolean notify,
                                   MailboxSyncState syncState) throws MessagingException, IllegalAccessException {
    if (folder == null) {
      return;
    }
    if (canSkipFolderSync(store, folder, folderKey, syncState.getSnapshot(folderKey), windowSize, username)) {
      return;
    }
    FolderSyncSnapshot folderSnapshot = syncFolder(folder, folderKey, username, userEmailSetting, windowSize, notify);
    if (folderSnapshot != null) {
      syncState.setSnapshot(folderKey, folderSnapshot);
    }
  }

  /**
   * Whether a folder's sync can be skipped because the server provably did not
   * change since the last full sync. Reads the four change signals off a STATUS on
   * the still-closed folder (two round-trips: JavaMail batches MESSAGES / UIDNEXT /
   * UIDVALIDITY into one STATUS, HIGHESTMODSEQ needs its own) and compares them to
   * the snapshot. Every exit is deliberately conservative — a wrong "unchanged"
   * silently stops a mailbox from syncing, which is a far worse failure than a slow
   * sync, so any missing signal, missing capability, unexpected value or error
   * means "run the full sync":
   * <ul>
   * <li>no snapshot (first sync, invalidated by a reset, or unparseable state) —
   * full sync;</li>
   * <li>a server not advertising CONDSTORE — full sync ALWAYS: without
   * mod-sequences, an unchanged uidNext+messageCount says nothing about
   * read/unread flags flipped in another client, and skipping would leave them
   * stale forever, not just slow. Checked BEFORE the snapshot's completeness so
   * the log tells "this server cannot do it" apart from "the capture came back
   * short" — the two were indistinguishable when Stalwart looped on "snapshot
   * incomplete" and the real question was whether it advertised CONDSTORE at
   * all;</li>
   * <li>snapshot captured with a different window size (admin changed the cache
   * size; the wider window must download even though the server is unchanged) —
   * full sync;</li>
   * <li>snapshot with any non-positive signal (typically a mod-sequence the
   * server did not provide at capture) — full sync, with the stored values
   * logged;</li>
   * <li>any server signal negative/unavailable, or the STATUS failing — full
   * sync.</li>
   * </ul>
   * The decision and both sides of the comparison are logged at INFO on purpose: a
   * silent skip is one nobody can debug when a mailbox looks stale.
   *
   * @param store the connected store, for the CONDSTORE capability check
   * @param folder the remote folder, still closed
   * @param folderKey the {@link MailFolder} discriminator, for the log
   * @param snapshot what the last full sync saw, may be null
   * @param windowSize the window size this sync would use
   * @param username the mailbox owner, for the log
   * @return true only when every change signal matches the snapshot exactly
   */
  private boolean canSkipFolderSync(Store store,
                                    Folder folder,
                                    String folderKey,
                                    FolderSyncSnapshot snapshot,
                                    int windowSize,
                                    String username) {
    if (snapshot == null) {
      // First sync of this folder (or a reset invalidated it): nothing to compare.
      return false;
    }
    // A snapshot missing a signal never matches, so the folder simply syncs the way it
    // always did. That is the outcome on a server which advertises CONDSTORE but does
    // not return HIGHESTMODSEQ in its SELECT response -- observed against Stalwart,
    // where this check stays permanently inert while Gmail skips normally. Making it
    // work there means opening the folder with ResyncData.CONDSTORE to ask for the
    // mod-sequence explicitly, which is a change worth its own measurement.
    try {
      if (!(store instanceof IMAPStore imapStore) || !imapStore.hasCapability(CONDSTORE_CAPABILITY)
          || !(folder instanceof IMAPFolder imapFolder)) {
        // No CONDSTORE: flags flipped in another client are undetectable from here,
        // and skipping would leave them stale forever. Checked FIRST (before any
        // STATUS is issued, and before the snapshot's own fields) so the log
        // separates "this server cannot do it" from "the capture came back short".
        LOG.info("Folder {} of user {} cheap change check: no CONDSTORE on this server -> full sync", folderKey, username);
        return false;
      }
      if (snapshot.getWindowSize() != windowSize) {
        LOG.info("Folder {} of user {} cheap change check: window size changed {} -> {} -> full sync",
                 folderKey,
                 username,
                 snapshot.getWindowSize(),
                 windowSize);
        return false;
      }
      if (snapshot.getUidValidity() <= 0 || snapshot.getUidNext() <= 0 || snapshot.getMessageCount() <= 0
          || snapshot.getHighestModSeq() <= 0) {
        // The stored values are in the line on purpose: this branch looped forever on
        // Stalwart with an invisible cause (highestModSeq -1 -- the server advertises
        // CONDSTORE but only sends mod-sequences when asked, which the sync now does
        // at open). If it still fires persistently, the folder's SELECT is coming
        // back without the signal even when requested.
        LOG.info("Folder {} of user {} cheap change check: snapshot incomplete (uidValidity {}, uidNext {}, {} message(s),"
            + " highestModSeq {}) -> full sync",
                 folderKey,
                 username,
                 snapshot.getUidValidity(),
                 snapshot.getUidNext(),
                 snapshot.getMessageCount(),
                 snapshot.getHighestModSeq());
        return false;
      }
      long uidValidity = imapFolder.getUIDValidity();
      long uidNext = imapFolder.getUIDNext();
      int messageCount = imapFolder.getMessageCount();
      long highestModSeq = imapFolder.getHighestModSeq();
      boolean unchanged = uidValidity > 0 && uidValidity == snapshot.getUidValidity()
          && uidNext > 0 && uidNext == snapshot.getUidNext()
          && messageCount > 0 && messageCount == snapshot.getMessageCount()
          && highestModSeq > 0 && highestModSeq == snapshot.getHighestModSeq();
      LOG.info("Folder {} of user {} cheap change check: server (uidValidity {}, uidNext {}, {} message(s), highestModSeq {})"
          + " vs last sync ({}, {}, {}, {}) -> {}",
               folderKey,
               username,
               uidValidity,
               uidNext,
               messageCount,
               highestModSeq,
               snapshot.getUidValidity(),
               snapshot.getUidNext(),
               snapshot.getMessageCount(),
               snapshot.getHighestModSeq(),
               unchanged ? "unchanged, folder sync skipped" : "changed, full sync");
      return unchanged;
    } catch (Exception e) {
      LOG.warn("Cheap change check failed on folder {} for user {}; running the full sync", folderKey, username, e);
      return false;
    }
  }

  /**
   * The folder's change signals as of this sync's SELECT — what the next sync's
   * skip check compares against. The message count is the one the window listing
   * used (NOT re-read from the folder, which untagged EXISTS responses update while
   * the download runs), and uidNext / highestModSeq come from the SELECT response
   * JavaMail parsed at open, so the snapshot describes exactly the state this sync
   * reconciled the cache to. On a server without CONDSTORE the mod-sequence is
   * negative and the snapshot is stored anyway — the skip check refuses it, which
   * is precisely the intent.
   *
   * @param folder the OPEN remote folder
   * @param totalMessages the message count at window listing
   * @param windowSize the cache window size this sync used
   * @return the snapshot, or null when the folder is not IMAP or a signal cannot
   *         be read (the next sync then takes the full path — the safe default)
   */
  private FolderSyncSnapshot captureFolderSnapshot(Folder folder, int totalMessages, int windowSize) {
    if (!(folder instanceof IMAPFolder imapFolder)) {
      return null;
    }
    try {
      return new FolderSyncSnapshot(imapFolder.getUIDValidity(),
                                    imapFolder.getUIDNext(),
                                    totalMessages,
                                    imapFolder.getHighestModSeq(),
                                    windowSize);
    } catch (Exception e) {
      LOG.warn("Could not capture the sync snapshot of folder {}; the next sync takes the full path",
               folder.getFullName(),
               e);
      return null;
    }
  }

  /**
   * The Sent folder, from the name remembered in the sync state when possible —
   * discovery walks the WHOLE subscribed folder list ({@code LIST *}) to find one
   * folder that never moves, on every sync of every user. The cached name is
   * verified with a single-folder {@code exists()} probe; a name that no longer
   * resolves (folder renamed or deleted) falls back to a full rediscovery, whose
   * result replaces the remembered name.
   *
   * @param store the connected store
   * @param syncState the mailbox's sync memory, updated in place on rediscovery
   * @return the Sent folder, or null when the mailbox has none
   * @throws MessagingException if the folder list cannot be read
   */
  private IMAPFolder resolveSentFolder(Store store, MailboxSyncState syncState) throws MessagingException {
    if (StringUtils.isNotBlank(syncState.getSentFolderName())) {
      Folder cached = store.getFolder(syncState.getSentFolderName());
      if (cached instanceof IMAPFolder imapFolder && cached.exists()) {
        return imapFolder;
      }
    }
    IMAPFolder sentFolder = findSentFolder(store);
    syncState.setSentFolderName(sentFolder != null ? sentFolder.getFullName() : null);
    return sentFolder;
  }

  /**
   * The syncable Archive folder, from the name remembered in the sync state when
   * possible — same reasoning and same fallback as {@link #resolveSentFolder}.
   *
   * @param store the connected store
   * @param syncState the mailbox's sync memory, updated in place on rediscovery
   * @return the Archive folder, or null when the mailbox has none to bulk-sync
   * @throws MessagingException if the folder list cannot be read
   */
  private IMAPFolder resolveArchiveFolder(Store store, MailboxSyncState syncState) throws MessagingException {
    if (StringUtils.isNotBlank(syncState.getArchiveFolderName())) {
      Folder cached = store.getFolder(syncState.getArchiveFolderName());
      if (cached instanceof IMAPFolder imapFolder && cached.exists()) {
        return imapFolder;
      }
    }
    IMAPFolder archiveFolder = findSyncableArchiveFolder(store);
    syncState.setArchiveFolderName(archiveFolder != null ? archiveFolder.getFullName() : null);
    return archiveFolder;
  }

  /**
   * The mailbox's persisted sync memory, or a blank one when absent or unreadable —
   * either way every folder simply takes the full path, so a corrupt state can
   * never do worse than cost one full sync. Lives under its own SettingService key,
   * NOT inside the user's email setting JSON: that object is read-modified-written
   * by preference flows, and sync bookkeeping racing user edits over one blob is
   * how settings get clobbered.
   *
   * @param username the mailbox owner
   * @return the state, never null
   */
  private MailboxSyncState loadMailboxSyncState(String username) {
    try {
      SettingValue<?> settingValue = settingService.get(Context.USER.id(username),
                                                        EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                                                        MAILBOX_SYNC_STATE_KEY);
      if (settingValue != null && settingValue.getValue() != null) {
        MailboxSyncState syncState = JsonUtils.fromJsonString(settingValue.getValue().toString(), MailboxSyncState.class);
        if (syncState != null) {
          return syncState;
        }
      }
    } catch (Exception e) {
      LOG.warn("Could not read the sync state of user {}; every folder takes the full sync path", username, e);
    }
    return new MailboxSyncState();
  }

  /**
   * Persists the mailbox's sync memory when — and only when — this run changed it,
   * compared on the serialized form so a fully-skipped sync writes nothing at all.
   * Best-effort: a failed write only costs the next sync a full pass.
   *
   * @param username the mailbox owner
   * @param syncState the possibly-mutated state
   * @param originalSyncStateJson the state as it was serialized at load time
   */
  private void saveMailboxSyncState(String username, MailboxSyncState syncState, String originalSyncStateJson) {
    try {
      String syncStateJson = JsonUtils.toJsonString(syncState);
      if (!syncStateJson.equals(originalSyncStateJson)) {
        settingService.set(Context.USER.id(username),
                           EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                           MAILBOX_SYNC_STATE_KEY,
                           SettingValue.create(syncStateJson));
      }
    } catch (Exception e) {
      LOG.warn("Could not save the sync state of user {}; the next sync takes the full path", username, e);
    }
  }

  /**
   * Drops one folder's change snapshot, forcing the next sync of that folder down
   * the full path. MUST be called whenever that folder's local cache is cleared:
   * after a reset the server still matches the old snapshot exactly, and without
   * this the skip check would conclude "nothing changed" over an empty cache — the
   * mailbox would come up blank and stay blank until new mail happened to arrive.
   *
   * @param username the mailbox owner
   * @param folderKey the {@link MailFolder} whose cache was cleared
   */
  private void clearFolderSyncSnapshot(String username, String folderKey) {
    try {
      MailboxSyncState syncState = loadMailboxSyncState(username);
      if (syncState.getSnapshot(folderKey) == null) {
        return;
      }
      syncState.setSnapshot(folderKey, null);
      settingService.set(Context.USER.id(username),
                         EmailConnectorService.EMAIL_CONNECTOR_SCOPE,
                         MAILBOX_SYNC_STATE_KEY,
                         SettingValue.create(JsonUtils.toJsonString(syncState)));
    } catch (Exception e) {
      LOG.warn("Could not clear the {} sync snapshot of user {}", folderKey, username, e);
    }
  }

  /**
   * The fetch profile every sync-side message read relies on. Flags + envelope + UID
   * come back in one round-trip; the FLAGS item carries the message's whole system
   * flag set, so reading {@code \Flagged} (the star) costs nothing beyond the SEEN
   * read that was already here. Without this, isSet(SEEN)/getFrom/getSubject/... each
   * trigger their own IMAP FETCH per message — hundreds of round-trips over a
   * high-latency provider like Gmail, which is what makes a large sync appear to take
   * forever. Every header createEmails reads is prefetched too (none is covered by
   * ENVELOPE): a header that was not prefetched costs a separate server round-trip for
   * EVERY message — on a 500-message mailbox the six delivery headers alone turned a
   * nine-minute sync into half an hour. Anything added to createEmails must be added
   * to {@link #PREFETCHED_HEADERS}. Same reasoning for the MIME structure: reading a
   * body starts by asking the message what it is made of, and an unfetched structure
   * costs a BODYSTRUCTURE round-trip per message; CONTENT_INFO brings all of them back
   * inside the one batched command.
   *
   * @return the profile to pass to {@link Folder#fetch(Message[], FetchProfile)}
   */
  private FetchProfile buildSyncFetchProfile() {
    FetchProfile fetchProfile = new FetchProfile();
    fetchProfile.add(FetchProfile.Item.FLAGS);
    fetchProfile.add(FetchProfile.Item.ENVELOPE);
    fetchProfile.add(UIDFolder.FetchProfileItem.UID);
    for (String header : PREFETCHED_HEADERS) {
      fetchProfile.add(header);
    }
    fetchProfile.add(FetchProfile.Item.CONTENT_INFO);
    return fetchProfile;
  }

  /**
   * Fetches the bodies of the folder's not-yet-cached messages over several extra IMAP
   * connections, caching each slice as soon as it lands so the mailbox fills in
   * progressively instead of staying empty until the whole download finishes.
   * <p>
   * The workers are pure IMAP I/O by design: they touch no database and no storage. Every
   * write stays on this thread, in {@link #createEmails}, because the thread computation
   * races when run concurrently and JPA dies on threads we create ourselves.
   * <p>
   * Two details here are load-bearing. The work is cut into slices <em>much smaller</em>
   * than the number of connections: one slice per connection would have them all finish
   * together, and nothing would appear until the end -- which is the whole point of this
   * method. And the slices are submitted newest-first but consumed in <em>completion</em>
   * order: the user sees today's mail land first instead of watching a reset fill
   * forward from weeks ago, and one slow slice can no longer hold hostage the finished
   * slices behind it. Caching out of order is safe because {@link #computeThreadId}
   * links a message to its conversation in both directions -- the cached messages it
   * references AND the cached messages referencing it -- so thread grouping does not
   * depend on the order messages land in.
   * <p>
   * Best effort throughout: a dead connection, one unreadable message or a total failure
   * of the prefetch only leaves UIDs unmapped, and createEmails fetches those bodies
   * serially. The sync stays correct, just slower.
   * <p>
   * When {@code streamNewEmails} is set, this method also owns the
   * {@link EmailConnectorUtils#NEW_EMAILS_SYNCED} broadcasts: every
   * {@value #BODY_PREFETCH_SLICES_PER_BROADCAST} drained slices, the group of
   * freshly-cached UIDs goes out immediately, so the AI categorization runs
   * concurrently with the rest of the download instead of after it. The serial
   * fallbacks broadcast their single pass the same way, so a consumer sees the same
   * event whichever path a sync took.
   *
   * @param folder the open remote folder being synchronized
   * @param uidFolder the same folder, for UID resolution
   * @param serverMessages the folder window listed by the sync connection
   * @param folderEmails the locally-cached rows of this folder, whose UIDs need no body
   * @param knownEmailsByUid the same rows indexed by IMAP UID, so createEmails can
   *          skip already-cached messages without a per-message database lookup
   * @param username the mailbox owner
   * @param folderKey the {@link MailFolder} discriminator to stamp
   * @param userEmailSetting the user's connector binding, to open the extra connections
   * @param streamNewEmails whether to broadcast the newly-cached UIDs as they land
   *          (INBOX only -- Sent and Archive are never broadcast)
   * @param fetchedParts counter of MIME part bodies pulled for the new messages,
   *          shared with the workers (memory-only, so worker purity holds)
   * @return the IMAP UIDs of the messages this sync newly cached
   * @throws MessagingException if the folder cannot be read
   * @throws IllegalAccessException if the user is not allowed to cache these messages
   */
  private List<Long> prefetchAndCreateEmails(Folder folder,
                                             UIDFolder uidFolder,
                                             Message[] serverMessages,
                                             List<Email> folderEmails,
                                             Map<Long, Email> knownEmailsByUid,
                                             String username,
                                             String folderKey,
                                             UserEmailSetting userEmailSetting,
                                             boolean streamNewEmails,
                                             MimePartStats fetchedParts) throws MessagingException,
                                                                      IllegalAccessException {
    List<Long> newUids = collectNewUids(uidFolder, serverMessages, folderEmails);
    int workerCount = getBodyPrefetchWorkerCount();
    if (workerCount <= 1 || newUids.size() < BODY_PREFETCH_MIN_MESSAGES) {
      // Too small to be worth extra connections: one pass, bodies fetched serially.
      return createEmailsAndBroadcast(uidFolder, serverMessages, username, folderKey, knownEmailsByUid, streamNewEmails, fetchedParts);
    }
    EmailConnector emailConnector;
    try {
      // Resolved once, on this thread: the workers must stay pure IMAP, and the one-argument
      // connect() would re-read the connector from the database on every bare worker thread.
      emailConnector = emailConnectorService.getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId()));
    } catch (Exception e) {
      LOG.warn("Could not resolve the connector of user {}; the sync falls back to fetching bodies serially", username, e);
      return createEmailsAndBroadcast(uidFolder, serverMessages, username, folderKey, knownEmailsByUid, streamNewEmails, fetchedParts);
    }
    Map<Long, Message> messagesByUid = new HashMap<>();
    for (Message message : serverMessages) {
      messagesByUid.put(uidFolder.getUID(message), message);
    }
    int sliceCount = (int) Math.ceil((double) newUids.size() / BODY_PREFETCH_SLICE_SIZE);
    List<long[]> uidSlices = partitionUids(newUids, sliceCount);
    // Newest mail first: the slices are submitted (hence fetched) in reverse mailbox
    // order, so a reset fills the inbox from today backwards instead of from weeks ago
    // forwards -- users watched a reset "stuck at July 9th" while the newest 260
    // messages were still pending. Only the slice ORDER is reversed: each slice keeps
    // its own UIDs ascending, so the workers' FETCH commands still compress their
    // contiguous UID runs into compact ranges.
    Collections.reverse(uidSlices);
    String folderFullName = folder.getFullName();
    // Daemon workers so a hung prefetch can never keep the JVM alive; the pool lives for
    // this one folder and is shut down before returning.
    AtomicInteger workerIndex = new AtomicInteger();
    ExecutorService prefetchPool = Executors.newFixedThreadPool(workerCount, runnable -> {
      Thread thread = new Thread(runnable, "email-body-prefetch-" + workerIndex.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    });
    List<Long> newEmailIds = new ArrayList<>();
    int prefetchedCount = 0;
    try {
      CompletionService<Map<Long, EmailContent>> completedSlices = new ExecutorCompletionService<>(prefetchPool);
      // Which UIDs each in-flight future carries, so a slice can be mapped back to its
      // messages whatever order it completes in -- and so the slices that never complete
      // are still cached by the serial fallback at the bottom of the loop. Insertion
      // (= submission) order is kept, so that fallback also drains newest-first.
      Map<Future<Map<Long, EmailContent>>, long[]> pendingSlices = new LinkedHashMap<>();
      for (long[] uidSlice : uidSlices) {
        pendingSlices.put(completedSlices.submit(() -> prefetchSlice(folderFullName,
                                                                     uidSlice,
                                                                     userEmailSetting,
                                                                     emailConnector,
                                                                     username,
                                                                     fetchedParts)),
                          uidSlice);
      }
      // Bounds SILENCE, not the folder: refreshed below every time a slice arrives. As an
      // absolute deadline this abandoned the prefetch mid-download on any mailbox too big
      // to finish inside it -- 5000 messages take roughly twice this -- dropping the rest
      // onto the serial path, which is the very cost the prefetch exists to avoid.
      long deadline = System.currentTimeMillis() + BODY_PREFETCH_TIMEOUT_MS;
      // The UIDs cached since the last broadcast. Replaced (never cleared) after each
      // broadcast: the list is handed to asynchronous listeners, which read it on their
      // own threads after this loop has moved on.
      List<Long> broadcastGroup = new ArrayList<>();
      int processedSliceCount = 0;
      // Slices are drained in COMPLETION order, not submission order: consuming them in
      // sequence once stalled a whole mailbox for ~4 minutes behind one slowly-trickling
      // message while four workers sat idle holding finished data. Out-of-order caching
      // is safe -- computeThreadId links conversations in both directions.
      boolean prefetchAbandoned = false;
      while (!pendingSlices.isEmpty()) {
        Future<Map<Long, EmailContent>> completed = null;
        if (!prefetchAbandoned) {
          completed = pollCompletedSlice(completedSlices, deadline, username);
          if (completed == null) {
            // Total silence inside the bound: stop the workers (left running they would
            // hold their connections busy on messages the sync has given up on) and fall
            // through to caching every remaining slice with serially-fetched bodies.
            prefetchAbandoned = true;
            pendingSlices.keySet().forEach(pending -> pending.cancel(true));
          }
        }
        long[] uidSlice;
        Map<Long, EmailContent> sliceContents;
        if (completed != null) {
          // Progress: the workers are alive, so the silence bound starts again.
          deadline = System.currentTimeMillis() + BODY_PREFETCH_TIMEOUT_MS;
          uidSlice = pendingSlices.remove(completed);
          sliceContents = completedSliceContents(completed, username);
          if (uidSlice == null) {
            // Cannot happen (every submitted future is in the map), but a null slice
            // must not NPE the sync thread mid-drain.
            continue;
          }
        } else {
          Iterator<Map.Entry<Future<Map<Long, EmailContent>>, long[]>> remaining = pendingSlices.entrySet().iterator();
          uidSlice = remaining.next().getValue();
          remaining.remove();
          sliceContents = Map.of();
        }
        prefetchedCount += sliceContents.size();
        processedSliceCount++;
        List<Long> sliceEmailIds = createEmails(uidFolder,
                                                messagesOfSlice(uidSlice, messagesByUid),
                                                username,
                                                folderKey,
                                                sliceContents,
                                                knownEmailsByUid,
                                                fetchedParts);
        newEmailIds.addAll(sliceEmailIds);
        if (streamNewEmails) {
          broadcastGroup.addAll(sliceEmailIds);
          boolean groupComplete = processedSliceCount % BODY_PREFETCH_SLICES_PER_BROADCAST == 0
              || pendingSlices.isEmpty();
          if (groupComplete && !broadcastGroup.isEmpty()) {
            broadcastNewEmailsSynced(username, broadcastGroup);
            broadcastGroup = new ArrayList<>();
          }
        }
      }
    } finally {
      prefetchPool.shutdownNow();
    }
    LOG.info("Prefetched {} of {} new message bodies in folder {} for user {} over {} extra connection(s), in {} slice(s)",
             prefetchedCount,
             newUids.size(),
             folderFullName,
             username,
             workerCount,
             uidSlices.size());
    return newEmailIds;
  }

  /**
   * The serial fallback of {@link #prefetchAndCreateEmails}: one createEmails pass over the
   * whole window, followed by the single {@link EmailConnectorUtils#NEW_EMAILS_SYNCED}
   * broadcast the streamed path would have spread over its groups. Kept next to the
   * streamed path so both stay the only two places that broadcast the event during a sync.
   *
   * @param uidFolder the folder being synchronized, for UID resolution
   * @param serverMessages the folder window listed by the sync connection
   * @param username the mailbox owner
   * @param folderKey the {@link MailFolder} discriminator to stamp
   * @param knownEmailsByUid the cached rows indexed by IMAP UID, skipped by createEmails
   * @param streamNewEmails whether the newly-cached UIDs are broadcast (INBOX only)
   * @param fetchedParts counter of MIME part bodies pulled for the new messages
   * @return the IMAP UIDs of the messages this sync newly cached
   * @throws MessagingException if the folder cannot be read
   * @throws IllegalAccessException if the user is not allowed to cache these messages
   */
  private List<Long> createEmailsAndBroadcast(UIDFolder uidFolder,
                                              Message[] serverMessages,
                                              String username,
                                              String folderKey,
                                              Map<Long, Email> knownEmailsByUid,
                                              boolean streamNewEmails,
                                              MimePartStats fetchedParts) throws MessagingException, IllegalAccessException {
    List<Long> newEmailIds = createEmails(uidFolder, serverMessages, username, folderKey, Map.of(), knownEmailsByUid, fetchedParts);
    if (streamNewEmails) {
      broadcastNewEmailsSynced(username, newEmailIds);
    }
    return newEmailIds;
  }

  /**
   * Waits for the next completed prefetch slice -- whichever of the in-flight slices
   * finishes first. Bounded by whichever comes sooner: the whole folder's deadline, or
   * {@link #BODY_PREFETCH_SLICE_TIMEOUT_MS} of complete silence. The bound is on "no
   * slice at all completed", not on one particular slice: a single slow slice just
   * keeps downloading while the finished ones are drained around it, but silence that
   * long with several connections in flight means the connections are dead, not slow.
   *
   * @param completedSlices the completion queue the workers hand finished slices to
   * @param deadline the epoch-millis bound shared by every slice of this folder
   * @param username the mailbox owner, for logging
   * @return the next completed slice, or null when the wait timed out or was
   *         interrupted -- the caller falls back to serial body fetching for whatever
   *         is still pending
   */
  private Future<Map<Long, EmailContent>> pollCompletedSlice(CompletionService<Map<Long, EmailContent>> completedSlices,
                                                             long deadline,
                                                             String username) {
    long waitBound = Math.min(deadline, System.currentTimeMillis() + BODY_PREFETCH_SLICE_TIMEOUT_MS);
    try {
      Future<Map<Long, EmailContent>> completed =
                                                completedSlices.poll(Math.max(1, waitBound - System.currentTimeMillis()),
                                                                     TimeUnit.MILLISECONDS);
      if (completed == null) {
        LOG.warn("No body prefetch slice of user {} completed in time; the remaining messages are fetched serially", username);
      }
      return completed;
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Body prefetch interrupted for user {}; the remaining bodies are fetched serially", username, e);
      return null;
    }
  }

  /**
   * Reads an already-completed slice's bodies, degrading to an empty slice rather than
   * failing the sync -- an empty map only means createEmails fetches those bodies
   * serially, exactly as before the prefetch existed.
   *
   * @param completedSlice a future the completion queue already handed back
   * @param username the mailbox owner, for logging
   * @return the slice's bodies keyed by IMAP UID, empty if the worker failed
   */
  private Map<Long, EmailContent> completedSliceContents(Future<Map<Long, EmailContent>> completedSlice, String username) {
    try {
      return completedSlice.get();
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Body prefetch interrupted for user {}; the remaining bodies are fetched serially", username, e);
    } catch (Exception e) {
      LOG.warn("A body prefetch slice failed for user {}; its messages are fetched serially", username, e);
    }
    return Map.of();
  }


  /**
   * MIME part bodies pulled per message, broken down by the kind of mail they came from.
   * <p>
   * Every part is its own {@code FETCH BODY[n]}, and a reset is almost entirely body
   * download, so this ratio is what decides whether inline images are worth deferring.
   * Split by mail type because the answer was expected to differ across them. It does,
   * but the other way round: measured over a thousand messages, personal mail costs 2.83
   * parts each and is the bulk of the mailbox, while marketing costs 1.82 -- newsletters
   * host their images remotely for open-tracking, so they carry no inline parts, whereas
   * real correspondence carries signature logos, quoted threads and attachments. Since a
   * text-plus-HTML message is already two parts before any image exists, the deferrable
   * surplus is roughly 0.8 parts per personal message and nothing at all elsewhere. That
   * is why inline images are still downloaded during the sync: deferring them would be a
   * reader change costing self-contained bodies, concentrated in exactly the mail worth
   * keeping, for far less than the raw 2.44 average suggests. Re-measure before revisiting.
   */
  private static final class MimePartStats {

    private final Map<String, long[]> byMailType = new ConcurrentHashMap<>();

    /**
     * Records one message's fetches.
     *
     * @param mailType the message's kind, as {@link EmailConnectorUtils#getMailType} names them
     * @param parts how many part bodies it cost
     */
    private void record(String mailType, long parts) {
      byMailType.compute(mailType, (type, counts) -> {
        long[] updated = counts == null ? new long[2] : counts;
        updated[0] += parts;
        updated[1]++;
        return updated;
      });
    }

    /**
     * @return every part body counted, all mail types together
     */
    private long total() {
      return byMailType.values().stream().mapToLong(counts -> counts[0]).sum();
    }

    /**
     * @return the per-type ratios, e.g. {@code bulk 3.2/msg (600), personal 1.1/msg (120)}
     */
    private String breakdown() {
      return byMailType.entrySet()
                       .stream()
                       .sorted((left, right) -> Long.compare(right.getValue()[0], left.getValue()[0]))
                       .map(entry -> String.format("%s %.2f/msg (%d)",
                                                   entry.getKey(),
                                                   entry.getValue()[1] == 0 ? 0d
                                                                            : (double) entry.getValue()[0] / entry.getValue()[1],
                                                   entry.getValue()[1]))
                       .collect(Collectors.joining(", "));
    }
  }

  /**
   * Classifies a message the same way {@link EmailConnectorUtils#getMailType} classifies a
   * cached one, but straight from its headers -- so a body fetch can be attributed before
   * the row exists. Every header it reads is in the batched fetch profile, so this costs
   * no round trip.
   *
   * @param message the live message
   * @return the mail type name
   */
  private String mailTypeOf(Message message) {
    try {
      if (isAutoSubmitted(message)) {
        return EmailConnectorUtils.MAIL_TYPE_AUTOMATED;
      }
      boolean hasListId = firstHeader(message, HEADER_LIST_ID) != null;
      if (hasListId && isPostableList(message)) {
        return EmailConnectorUtils.MAIL_TYPE_LIST;
      }
      if (hasListId || firstHeader(message, HEADER_LIST_UNSUBSCRIBE) != null) {
        return EmailConnectorUtils.MAIL_TYPE_BULK;
      }
    } catch (Exception e) {
      LOG.debug("Could not classify a message for part accounting", e);
    }
    return EmailConnectorUtils.MAIL_TYPE_PERSONAL;
  }

  /**
   * Lists the not-yet-cached messages of the folder window, in the order the server
   * listed them -- oldest first. This is only a listing order: the prefetch reverses
   * the slices so the newest mail is fetched and cached first, and thread grouping no
   * longer cares (computeThreadId links conversations in both directions).
   *
   * @param uidFolder the folder being synchronized, for UID resolution
   * @param serverMessages the folder window listed by the sync connection
   * @param folderEmails the locally-cached rows of this folder
   * @return the UIDs present on the server but not in the local cache
   * @throws MessagingException if a UID cannot be read
   */
  private List<Long> collectNewUids(UIDFolder uidFolder,
                                    Message[] serverMessages,
                                    List<Email> folderEmails) throws MessagingException {
    // The UIDs are read off the already-fetched profile (no round-trip) and compared to
    // the rows syncFolder has already loaded -- no per-message database lookup here.
    Set<Long> knownUids = folderEmails.stream().map(Email::getMailRemoteId).filter(Objects::nonNull).collect(Collectors.toSet());
    List<Long> newUids = new ArrayList<>();
    for (Message message : serverMessages) {
      long messageUid = uidFolder.getUID(message);
      if (!knownUids.contains(messageUid)) {
        newUids.add(messageUid);
      }
    }
    return newUids;
  }

  /**
   * Resolves a slice's UIDs back to the messages the sync connection listed, keeping the
   * slice's order.
   *
   * @param uidSlice the slice's UIDs
   * @param messagesByUid the folder window indexed by UID
   * @return the slice's messages, skipping any UID that has since disappeared
   */
  private Message[] messagesOfSlice(long[] uidSlice, Map<Long, Message> messagesByUid) {
    return Arrays.stream(uidSlice).mapToObj(messagesByUid::get).filter(Objects::nonNull).toArray(Message[]::new);
  }

  /**
   * One prefetch worker: opens its own store and folder, fetches its slice of UIDs with
   * the same batched profile as the sync connection, and extracts each body. Self-contained on purpose — no database, no storage, no shared JavaMail
   * objects (a JavaMail Folder is not safe to read from two threads, so each worker
   * re-resolves its messages by UID on its own connection). Any failure is logged and
   * swallowed: an unmapped UID just means createEmails fetches that body serially.
   *
   * @param folderFullName the remote folder's full name, re-opened on this connection
   * @param uids the IMAP UIDs this worker is responsible for
   * @param userEmailSetting the user's connector binding
   * @param emailConnector the resolved connector preset, so no database read happens here
   * @param username the mailbox owner, for logs
   * @param fetchedParts counter of MIME part bodies pulled — a shared in-memory adder,
   *          the one thing a worker may touch besides IMAP
   * @return the slice's bodies keyed by IMAP UID
   */
  private Map<Long, EmailContent> prefetchSlice(String folderFullName,
                                                long[] uids,
                                                UserEmailSetting userEmailSetting,
                                                EmailConnector emailConnector,
                                                String username,
                                                MimePartStats fetchedParts) {
    Map<Long, EmailContent> contents = new HashMap<>();
    Store store = null;
    Folder folder = null;
    try {
      store = userEmailSettingService.connect(userEmailSetting, emailConnector);
      folder = store.getFolder(folderFullName);
      folder.open(Folder.READ_ONLY);
      UIDFolder uidFolder = (UIDFolder) folder;
      // A UID expunged since the sync connection listed it comes back as a null (or
      // missing) entry; drop it — cleanupObsoleteEmails handles its disappearance.
      Message[] messages = Arrays.stream(uidFolder.getMessagesByUID(uids))
                                 .filter(Objects::nonNull)
                                 .toArray(Message[]::new);
      if (messages.length == 0) {
        return contents;
      }
      folder.fetch(messages, buildSyncFetchProfile());
      for (Message message : messages) {
        try {
          long messageUid = uidFolder.getUID(message);
          LongAdder messageParts = new LongAdder();
          contents.put(messageUid, EmailConnectorUtils.getMessageContent(messageUid, message, messageParts));
          if (fetchedParts != null) {
              fetchedParts.record(mailTypeOf(message), messageParts.sum());
            }
        } catch (Exception e) {
          LOG.warn("Could not prefetch the body of a message in folder {} for user {}; it will be fetched serially",
                   folderFullName,
                   username,
                   e);
        }
      }
    } catch (Exception e) {
      LOG.warn("Body prefetch worker failed on folder {} for user {}; its {} message(s) will be fetched serially",
               folderFullName,
               username,
               uids.length,
               e);
    } finally {
      if (folder != null && folder.isOpen()) {
        try {
          folder.close(false);
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing prefetch folder {} for user {}", folderFullName, username, messagingException);
        }
      }
      if (store != null && store.isConnected()) {
        try {
          store.close();
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing prefetch store for user {}", username, messagingException);
        }
      }
    }
    return contents;
  }

  /**
   * Splits the UIDs into at most {@code chunkCount} contiguous, balanced chunks — one
   * per worker. Contiguous on purpose: the UIDs arrive in mailbox order, so each chunk
   * compresses into a compact UID set in the worker's FETCH commands instead of
   * scattering every worker across the whole folder. Package-visible for tests.
   *
   * @param uids the UIDs to split, in mailbox order
   * @param chunkCount the maximum number of chunks (bounded by the number of UIDs)
   * @return the chunks, whose concatenation is exactly {@code uids}; empty when there
   *         is nothing to split
   */
  static List<long[]> partitionUids(List<Long> uids, int chunkCount) {
    List<long[]> chunks = new ArrayList<>();
    int total = uids.size();
    if (total == 0 || chunkCount <= 0) {
      return chunks;
    }
    int effectiveChunkCount = Math.min(chunkCount, total);
    int baseSize = total / effectiveChunkCount;
    int remainder = total % effectiveChunkCount;
    int index = 0;
    for (int i = 0; i < effectiveChunkCount; i++) {
      long[] chunk = new long[baseSize + (i < remainder ? 1 : 0)];
      for (int j = 0; j < chunk.length; j++) {
        chunk[j] = uids.get(index++);
      }
      chunks.add(chunk);
    }
    return chunks;
  }

  /**
   * How many IMAP connections the body prefetch may open, from the
   * {@value #BODY_PREFETCH_WORKERS_PROPERTY} system property (same convention as the
   * other {@code email.connector.sync.*} tunables in {@link EmailConnectorUtils}).
   * Read on every sync rather than at class load, so a malformed value degrades to the
   * default instead of pinning garbage for the JVM's lifetime. {@code <= 1} disables
   * the parallel prefetch entirely.
   *
   * @return the configured worker count, or {@value #DEFAULT_BODY_PREFETCH_WORKERS}
   */
  private int getBodyPrefetchWorkerCount() {
    String configured = System.getProperty(BODY_PREFETCH_WORKERS_PROPERTY);
    if (StringUtils.isBlank(configured)) {
      return DEFAULT_BODY_PREFETCH_WORKERS;
    }
    try {
      return Integer.parseInt(configured.trim());
    } catch (NumberFormatException e) {
      LOG.warn("Ignoring invalid value '{}' for {}; using the default of {}",
               configured,
               BODY_PREFETCH_WORKERS_PROPERTY,
               DEFAULT_BODY_PREFETCH_WORKERS);
      return DEFAULT_BODY_PREFETCH_WORKERS;
    }
  }

  /**
   * Broadcasts {@link EmailConnectorUtils#NEW_EMAILS_SYNCED} with the freshly fetched inbox
   * emails' ids so other add-ons can react to new mail (e.g. the enterprise AI
   * auto-categorization). The add-on stays AI-agnostic; a broadcast failure never breaks sync.
   *
   * @param username the synchronized user
   * @param newEmailIds the IMAP UIDs of the emails created during this sync
   */
  private void broadcastNewEmailsSynced(String username, List<Long> newEmailIds) {
    if (newEmailIds == null || newEmailIds.isEmpty()) {
      // Traced on purpose: consumers only ever see messages that were *created* by this sync,
      // so "nothing was new" and "the consumer is broken" are otherwise indistinguishable from
      // the outside -- both are complete silence.
      LOG.info("No new email to broadcast for user {}: this sync created no message, so '{}' consumers (e.g. AI auto-categorization) are not invoked",
               username,
               EmailConnectorUtils.NEW_EMAILS_SYNCED);
      return;
    }
    try {
      LOG.info("Broadcasting '{}' for user {} with {} newly-cached message(s)",
               EmailConnectorUtils.NEW_EMAILS_SYNCED,
               username,
               newEmailIds.size());
      listenerService.broadcast(EmailConnectorUtils.NEW_EMAILS_SYNCED, username, newEmailIds);
    } catch (Exception e) {
      LOG.warn("Error broadcasting '{}' for user {}", EmailConnectorUtils.NEW_EMAILS_SYNCED, username, e);
    }
  }

  /**
   * Broadcasts {@link EmailConnectorUtils#NEW_EMAILS_SYNC_COMPLETED} once the inbox sync has
   * cached its last message, carrying ALL the UIDs the run created. The per-group
   * {@link #broadcastNewEmailsSynced} events stream out while the download is still running,
   * so on their own a consumer can never tell "the next group has not arrived yet" from
   * "there is no next group" -- whole-run work (the categorizer's conversation alignment)
   * hangs off this event. Broadcast even when the sync cached nothing, so a consumer can
   * close any state left over from a run whose completion it never saw.
   *
   * @param username the synchronized user
   * @param newEmailIds the IMAP UIDs of every email created during this sync
   */
  private void broadcastNewEmailsSyncCompleted(String username, List<Long> newEmailIds) {
    try {
      LOG.info("Broadcasting '{}' for user {}: the inbox sync ended with {} newly-cached message(s) in total",
               EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED,
               username,
               newEmailIds.size());
      listenerService.broadcast(EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED, username, newEmailIds);
    } catch (Exception e) {
      LOG.warn("Error broadcasting '{}' for user {}", EmailConnectorUtils.NEW_EMAILS_SYNC_COMPLETED, username, e);
    }
  }

  /**
   * Get the user's inbox email box.
   *
   * @param username user getting user emails
   * @return list of stored {@link Email} in datasource
   */
  public EmailBox getEmailBox(String username) throws IllegalAccessException {
    return getEmailBox(username, MailFolder.INBOX);
  }

  /**
   * Get the user's email box for a given folder — the list can show the inbox or,
   * for the in-app folder switch, the user's Sent or Archive mail. The thread reader
   * still spans every folder; only the flat list is scoped here.
   *
   * @param username user getting user emails
   * @param folder the folder to list: {@code INBOX}, {@code SENT} or {@code ARCHIVE}
   * @return the folder's cached messages plus the per-conversation counts
   * @throws IllegalAccessException if the user is not allowed to read their mailbox
   * @throws IllegalArgumentException if {@code folder} is not a browsable folder
   */
  public EmailBox getEmailBox(String username, String folder) throws IllegalAccessException {
    return getEmailBox(username, folder, false);
  }

  /**
   * Get the user's email box for a given folder, optionally restricted to the
   * starred messages — the list's starred filter. The filter reads the cached
   * mirror of the server's {@code \Flagged} flag, so what it shows is exactly the
   * set of stars the last sync saw (a star set elsewhere appears on the next sync,
   * a star set here appears immediately because the toggle writes locally first).
   *
   * @param username user getting user emails
   * @param folder the folder to list: {@code INBOX}, {@code SENT} or {@code ARCHIVE}
   * @param starredOnly when {@code true}, only the starred messages are returned
   * @return the folder's cached messages plus the per-conversation counts
   * @throws IllegalAccessException if the user is not allowed to read their mailbox
   * @throws IllegalArgumentException if {@code folder} is not a browsable folder
   */
  public EmailBox getEmailBox(String username, String folder, boolean starredOnly) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
    }
    // Only the folders a user can browse as a list; ALL_MAIL is an internal on-demand
    // completion store, never a browsable list.
    if (!MailFolder.INBOX.equals(folder) && !MailFolder.SENT.equals(folder) && !MailFolder.ARCHIVE.equals(folder)) {
      throw new IllegalArgumentException("emailConnector.folder.notBrowsable");
    }
    List<Email> emails = starredOnly ? emailBoxStorage.getStarredEmails(username, folder)
                                     : emailBoxStorage.getEmails(username, folder);
    return new EmailBox(emails,
                        userEmailSetting.getEmailSyncStatus(),
                        userEmailSetting.getEmailConnectorWebmailUrl(),
                        emailBoxStorage.getThreadMessageCounts(username),
                        emailBoxStorage.getFolderMessageCounts(username));
  }

  /**
   * Delete user emails
   *
   * @param username user whose emails will be deleted
   */
  public void deleteUserEmails(String username) {
    List<Email> emails = emailBoxStorage.getEmails(username);
    deleteEmails(emails);
    // The whole cache is gone, so the whole sync memory must go with it: a surviving
    // snapshot would let the next sync skip folders whose local rows no longer exist.
    try {
      settingService.remove(Context.USER.id(username), EmailConnectorService.EMAIL_CONNECTOR_SCOPE, MAILBOX_SYNC_STATE_KEY);
    } catch (Exception e) {
      LOG.warn("Could not clear the sync state of user {}", username, e);
    }
  }

  /**
   * Delete the user's cached emails of a single folder, with their category links.
   *
   * @param username user whose emails will be deleted
   * @param folder the {@link MailFolder} to clear
   */
  public void deleteUserEmails(String username, String folder) {
    List<Email> emails = emailBoxStorage.getEmails(username, folder);
    deleteEmails(emails);
    // This folder's cache is gone, so its change snapshot must die with it: after a
    // reset the server still matches the old snapshot exactly, and a surviving one
    // would make the resync skip "unchanged" folders over an empty cache — the
    // mailbox would come up blank and stay blank until new mail happened to arrive.
    clearFolderSyncSnapshot(username, folder);
  }

  /**
   * Schedule email box user synchronization job
   *
   * @param username user for which email box synchronization job will be
   *          scheduled
   */
  public void scheduleEmailBoxUserSyncJob(String username) throws Exception {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    String emailBoxSyncJobName = username + EmailConnectorUtils.EMAIL_BOX_SYNC_JOB_NAME;
    JobInfo emailBoxSyncJobInfo = new JobInfo(emailBoxSyncJobName, EmailConnectorUtils.EMAIL_FEATURE, EmailBoxSyncJob.class);
    // Remove next email box sync job for the user
    jobSchedulerService.removeJob(emailBoxSyncJobInfo);
    PeriodInfo periodInfo =
                          new PeriodInfo(null, null, 0, EmailConnectorUtils.getEmailBoxUserSyncPeriod(userEmailSetting) * 60000);
    jobSchedulerService.addPeriodJob(emailBoxSyncJobInfo, periodInfo);
  }

  public EmailAttachment getAttachmentByMailRemoteIdAnIdAndUserId(long mailRemoteId,
                                                                  String attachmentId,
                                                                  String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_ATTACHMENT, username));
    }
    Store store = null;
    Folder inbox = null;
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      inbox = store.getFolder(INBOX_FOLDER_NAME);
      inbox.open(Folder.READ_ONLY);
      Message message = ((UIDFolder) inbox).getMessageByUID(mailRemoteId);
      EmailAttachment emailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(mailRemoteId,
                                                                                                 attachmentId,
                                                                                                 username);
      BodyPart bodyPart = getPartByPath(message, attachmentId);
      if (bodyPart == null) {
        throw new RuntimeException("Attachment not found in the email");
      }
      try (InputStream is = bodyPart.getInputStream(); ByteArrayOutputStream baos = new ByteArrayOutputStream()) {
        byte[] buffer = new byte[256 * 1024];
        int bytesRead;
        while ((bytesRead = is.read(buffer)) != -1) {
          baos.write(buffer, 0, bytesRead);
        }
        emailAttachment.setData(baos.toByteArray());
      }
      String fileName = bodyPart.getFileName();
      if (fileName != null) {
        emailAttachment.setName(fileName);
      }
      String mimeType = Optional.ofNullable(bodyPart.getContentType().toLowerCase())
                                .orElse("application/octet-stream")
                                .split(";")[0];
      emailAttachment.setMimeType(mimeType);
      return emailAttachment;
    } catch (Exception e) {
      LOG.error("Error when connecting store for user {}", username, e);
      throw new IllegalStateException(String.format("Error when connecting store for user %s", username));
    } finally {
      try {
        if (inbox != null && inbox.isOpen()) {
          inbox.close(false);
        }
      } catch (MessagingException messagingException) {
        LOG.warn("Error when closing inbox", messagingException);
      }
      try {
        if (store != null && store.isConnected()) {
          store.close();
        }
      } catch (MessagingException messagingException) {
        LOG.warn("Error when closing store", messagingException);
      }
    }
  }

  public String broadcastOpenEmail(String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_BROADCAST_OPEN_EMAIL_EVENT_MESSAGE, username));
    }
    try {
      listenerService.broadcast(EmailConnectorUtils.OPEN_EMAIL, username, userEmailSetting.getEmailConnectorName());
    } catch (Exception e) {
      LOG.warn("Error broadcasting event '" + EmailConnectorUtils.OPEN_EMAIL + "' using source '" + username + "' and data " +
          userEmailSetting.getEmailConnectorName(), e);
    }
    return userEmailSetting.getEmailAddress();
  }

  public void broadcastAccessWebmail(String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_BROADCAST_ACCESS_WEBMAIL_EVENT_MESSAGE, username));
    }
    try {
      listenerService.broadcast(EmailConnectorUtils.ACCESS_WEBMAIL, username, userEmailSetting.getEmailConnectorName());
    } catch (Exception e) {
      LOG.warn("Error broadcasting event '" + EmailConnectorUtils.ACCESS_WEBMAIL + "' using source '" + username + "' and data " +
          userEmailSetting.getEmailConnectorName(), e);
    }
  }

  public Email getEmailByMailRemoteIdAndUserId(long mailRemoteId,
                                               String username,
                                               boolean withAttachments,
                                               boolean withRecipients,
                                               boolean withProfile,
                                               boolean broadcast) throws IllegalAccessException {
    return getEmailByMailRemoteIdAndUserId(mailRemoteId,
                                           username,
                                           MailFolder.INBOX,
                                           withAttachments,
                                           withRecipients,
                                           withProfile,
                                           broadcast);
  }

  /**
   * Get a single cached message by its IMAP UID within a given folder. IMAP UIDs are
   * per-folder, so the folder is required to open a message the user clicked from the
   * Sent or Archive list, not only the inbox.
   *
   * @param mailRemoteId the message IMAP UID
   * @param username the mailbox owner
   * @param folder the folder the message is listed in (INBOX / SENT / ARCHIVE)
   * @param withAttachments whether to load attachments
   * @param withRecipients whether to load recipients
   * @param withProfile whether to resolve sender/recipient platform profiles
   * @param broadcast whether to broadcast the open-email event
   * @return the message, or null when not found in that folder
   * @throws IllegalAccessException if the user is not allowed to read their mailbox
   */
  public Email getEmailByMailRemoteIdAndUserId(long mailRemoteId,
                                               String username,
                                               String folder,
                                               boolean withAttachments,
                                               boolean withRecipients,
                                               boolean withProfile,
                                               boolean broadcast) throws IllegalAccessException {
    // The mailbox owner's own address, needed to tell which recipients are "me" -- so it must
    // be resolved for every caller, not only when the open-email event is broadcast. Deriving
    // it from broadcastOpenEmail() made it a side effect of broadcasting: a caller that must
    // not broadcast (a background job) silently got null here, and every recipient of every
    // message then looked like somebody else.
    UserEmailSetting readerSetting = userEmailSettingService.getUserEmailSetting(username);
    String userEmail = readerSetting == null ? null : readerSetting.getEmailAddress();
    if (broadcast) {
      broadcastOpenEmail(username);
    }
    return emailBoxStorage.getEmailByMailRemoteIdAndUserId(mailRemoteId,
                                                           username,
                                                           userEmail,
                                                           folder,
                                                           withAttachments,
                                                           withRecipients,
                                                           withProfile);
  }

  /**
   * All cached messages of a conversation, across every folder (INBOX, SENT,
   * ARCHIVE) — the read model for the conversation reader, so a user's own sent
   * replies and previously-archived messages show inline with the received ones.
   *
   * @param threadId the conversation id (see {@link #computeThreadId})
   * @param username the mailbox owner
   * @return the thread's messages, oldest first, each with body and recipients
   * @throws IllegalAccessException if the user is not allowed to read their mailbox
   */
  public List<Email> getThread(String threadId, String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
    }
    return emailBoxStorage.getEmailsByThreadId(username, threadId, userEmailSetting.getEmailAddress());
  }

  /**
   * Complete a conversation from the provider's archive superset (Gmail "All Mail")
   * and return the whole thread. Split from {@link #getThread} so the reader renders
   * the cached thread instantly and pulls the archived tail in the background — the
   * IMAP round-trip lives here, not on the drawer's opening path.
   *
   * @param threadId the conversation id opened by the user
   * @param username the mailbox owner
   * @return the thread's messages including any newly recovered archived ones
   * @throws IllegalAccessException if the user is not allowed to read their mailbox
   */
  public List<Email> completeThread(String threadId, String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
    }
    // Completion keeps the opened thread id as the canonical one, so the id the reader
    // (and the already-rendered inbox list) holds stays valid on the next open.
    completeThreadFromArchive(username, threadId, userEmailSetting);
    return emailBoxStorage.getEmailsByThreadId(username, threadId, userEmailSetting.getEmailAddress());
  }

  @Transactional
  public Email getEmailById(long id, String username) {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    return emailBoxStorage.getEmailById(id, username, userEmailSetting.getEmailAddress());
  }

  /**
   * The same read as {@link #getEmailById(long, String)}, but refusing an email
   * that is not the caller's.
   * <p>
   * The plain lookup finds a row by its technical id alone — the username only
   * decorates what comes back — which is right for callers that have already
   * established who owns it, and wrong for anything reached from outside. This
   * is the one the Favorites drawer uses: it holds an email id the platform
   * stored for the user, and an id is guessable.
   *
   * @param id the cached email's technical id
   * @param username the user asking, who must be the mailbox owner
   * @return the email, never another user's
   * @throws IllegalAccessException if the email belongs to somebody else
   */
  // The refusal is stated, not rolled back: this method only reads, so the checked
  // exception has nothing to undo -- and left implicit, Spring's rollback rules for a
  // checked exception are the kind of thing a reader has to go and look up.
  @Transactional(noRollbackFor = IllegalAccessException.class)
  public Email getOwnedEmailById(long id, String username) throws IllegalAccessException {
    Email email = getEmailById(id, username);
    if (email != null && !StringUtils.equals(email.getUserId(), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
    }
    return email;
  }

  /**
   * Update the read/unread status of one or more emails (by IMAP mailRemoteId),
   * optimistically in the local mirror first and then, when requested, on the IMAP
   * server. Each per-message remote failure (including a message that no longer
   * exists on the server) reverts the local change for that email and is counted so
   * the caller can report a truthful outcome instead of silently claiming success.
   *
   * @param mailRemoteIds the IMAP UIDs of the emails to update
   * @param username the user acting on their own mailbox
   * @param readStatus {@code true} to mark as read, {@code false} to mark as unread
   * @param updateRemoteReadStatus whether the flag must also be pushed to the IMAP
   *          server (skipped, e.g., during sync where the flag comes from the server)
   * @return the number of emails whose remote update failed (0 when everything
   *         succeeded or when no remote update was requested)
   * @throws IllegalAccessException if the user is not allowed to update email
   */
  public int updateEmailReadStatus(List<Long> mailRemoteIds,
                                   String username,
                                   boolean readStatus,
                                   boolean updateRemoteReadStatus) throws IllegalAccessException {
    int failedEmailUpdates = 0;
    if (mailRemoteIds != null && !mailRemoteIds.isEmpty()) {
      UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
      if (userEmailSetting.getEmailConnectorId() == null
          || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
        throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_UPDATE_EMAIL_MESSAGE, username));
      }
      emailBoxStorage.updateEmailReadStatusByMailRemoteIds(mailRemoteIds, username, readStatus, MailFolder.INBOX);
      Store store = null;
      Folder inbox = null;
      try {
        if (updateRemoteReadStatus) {
          store = userEmailSettingService.connect(userEmailSetting);
          inbox = store.getFolder(INBOX_FOLDER_NAME);
          inbox.open(Folder.READ_WRITE);
        }
        for (Long mailRemoteId : mailRemoteIds) {
          try {
            if (updateRemoteReadStatus) {
              Message remoteMessage = ((UIDFolder) inbox).getMessageByUID(mailRemoteId);
              // Guard the not-found case explicitly: getMessageByUID returns null
              // (rather than throwing) when the UID is unknown to the server.
              if (remoteMessage == null) {
                emailBoxStorage.updateEmailReadStatusByMailRemoteIds(List.of(mailRemoteId), username, !readStatus, MailFolder.INBOX);
                failedEmailUpdates++;
                LOG.warn("Email {} not found on IMAP server for user {}, read status update reverted", mailRemoteId, username);
                continue;
              }
              remoteMessage.setFlag(Flags.Flag.SEEN, readStatus);
            }
          } catch (Exception e) {
            emailBoxStorage.updateEmailReadStatusByMailRemoteIds(List.of(mailRemoteId), username, !readStatus, MailFolder.INBOX);
            failedEmailUpdates++;
            LOG.error("Error when updating email {} read status for user {}", mailRemoteId, username, e);
          }
        }
      } catch (Exception e) {
        emailBoxStorage.updateEmailReadStatusByMailRemoteIds(mailRemoteIds, username, !readStatus, MailFolder.INBOX);
        LOG.error("Error when connecting store for user {}", username, e);
        throw new IllegalStateException(String.format("Error when connecting store for user %s", username));
      } finally {
        try {
          if (inbox != null && inbox.isOpen()) {
            inbox.close(false);
          }
        } catch (MessagingException e) {
          LOG.warn("Error when closing inbox", e);
        }
        try {
          if (store != null && store.isConnected()) {
            store.close();
          }
        } catch (MessagingException e) {
          LOG.warn("Error when closing store", e);
        }
      }
      // Read/unread transitions are what the App Center badge reflects; without
      // this broadcast the counter would only refresh at the next sync. Inside
      // the guard, and only when at least one local update stuck: an empty call
      // or a fully reverted one changed no count and must not cost an eviction,
      // a WebSocket frame and a re-fetch for nothing.
      if (failedEmailUpdates < mailRemoteIds.size()) {
        broadcastUnreadCountChanged(username);
      }
    }
    return failedEmailUpdates;
  }

  /**
   * Counts the unread emails of the locally synced mirror, for the mailbox
   * owner only.
   *
   * @param  username the mailbox owner
   * @return          the number of unread emails
   */
  public long countUnreadEmails(String username) {
    return emailBoxStorage.countUnreadEmails(username);
  }

  /**
   * Signals that a user's unread count may have changed, so that anything
   * displaying it can refresh.
   *
   * @param username the mailbox owner
   */
  public void broadcastUnreadCountChanged(String username) {
    try {
      listenerService.broadcast(EmailConnectorUtils.UNREAD_EMAILS_CHANGED, username, null);
    } catch (Exception e) {
      LOG.warn("Error broadcasting unread emails change for user {}", username, e);
    }
  }

  /**
   * Star or unstar one or more emails (by IMAP mailRemoteId): the star IS the mail
   * server's {@code \Flagged} flag, not platform metadata, so a star set here shows
   * in Gmail and on the user's phone and vice versa. Same discipline as
   * {@link #updateEmailReadStatus}: the local mirror is updated optimistically
   * first, then the flag is pushed to the IMAP server, and each per-message remote
   * failure (including a message no longer on the server) reverts the local change
   * for that email and is counted — a star the server never took must not survive
   * locally, or the two copies silently diverge until the next sync.
   *
   * @param mailRemoteIds the IMAP UIDs of the emails to update
   * @param username the user acting on their own mailbox
   * @param starred {@code true} to star, {@code false} to unstar
   * @param updateRemoteStarredStatus whether the flag must also be pushed to the
   *          IMAP server (skipped, e.g., during sync where the flag comes from the
   *          server)
   * @return the number of emails whose remote update failed (0 when everything
   *         succeeded or when no remote update was requested)
   * @throws IllegalAccessException if the user is not allowed to update email
   */
  public int updateEmailStarredStatus(List<Long> mailRemoteIds,
                                      String username,
                                      boolean starred,
                                      boolean updateRemoteStarredStatus) throws IllegalAccessException {
    int failedEmailUpdates = 0;
    if (mailRemoteIds != null && !mailRemoteIds.isEmpty()) {
      UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
      if (userEmailSetting.getEmailConnectorId() == null
          || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
        throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_UPDATE_EMAIL_MESSAGE, username));
      }
      emailBoxStorage.updateEmailStarredStatusByMailRemoteIds(mailRemoteIds, username, starred, MailFolder.INBOX);
      Store store = null;
      Folder inbox = null;
      try {
        if (updateRemoteStarredStatus) {
          store = userEmailSettingService.connect(userEmailSetting);
          inbox = store.getFolder(INBOX_FOLDER_NAME);
          inbox.open(Folder.READ_WRITE);
        }
        for (Long mailRemoteId : mailRemoteIds) {
          try {
            if (updateRemoteStarredStatus) {
              Message remoteMessage = ((UIDFolder) inbox).getMessageByUID(mailRemoteId);
              // Guard the not-found case explicitly: getMessageByUID returns null
              // (rather than throwing) when the UID is unknown to the server.
              if (remoteMessage == null) {
                emailBoxStorage.updateEmailStarredStatusByMailRemoteIds(List.of(mailRemoteId),
                                                                        username,
                                                                        !starred,
                                                                        MailFolder.INBOX);
                failedEmailUpdates++;
                LOG.warn("Email {} not found on IMAP server for user {}, starred status update reverted",
                         mailRemoteId,
                         username);
                continue;
              }
              remoteMessage.setFlag(Flags.Flag.FLAGGED, starred);
            }
          } catch (Exception e) {
            emailBoxStorage.updateEmailStarredStatusByMailRemoteIds(List.of(mailRemoteId),
                                                                    username,
                                                                    !starred,
                                                                    MailFolder.INBOX);
            failedEmailUpdates++;
            LOG.error("Error when updating email {} starred status for user {}", mailRemoteId, username, e);
          }
        }
      } catch (Exception e) {
        emailBoxStorage.updateEmailStarredStatusByMailRemoteIds(mailRemoteIds, username, !starred, MailFolder.INBOX);
        LOG.error(STORE_CONNECT_ERROR_MESSAGE, username, e);
        throw new IllegalStateException(String.format(STORE_CONNECT_ERROR_FORMAT, username));
      } finally {
        try {
          if (inbox != null && inbox.isOpen()) {
            inbox.close(false);
          }
        } catch (MessagingException e) {
          LOG.warn(INBOX_CLOSE_ERROR_MESSAGE, e);
        }
        try {
          if (store != null && store.isConnected()) {
            store.close();
          }
        } catch (MessagingException e) {
          LOG.warn(STORE_CLOSE_ERROR_MESSAGE, e);
        }
      }
      // Realign the Favorites drawer on what the local rows now say. Reading them
      // back rather than mirroring the requested change is what keeps the drawer
      // honest when the server refused a star: that row was reverted above, and
      // the favorite has to follow it back.
      emailFavoriteService.reconcileFavorites(username);
    }
    return failedEmailUpdates;
  }

  public int deleteEmail(List<Long> mailRemoteIds, String username) throws IllegalAccessException {
    int failedEmailDeletions = 0;
    if (mailRemoteIds != null && !mailRemoteIds.isEmpty()) {
      UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
      if (userEmailSetting.getEmailConnectorId() == null
          || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
        throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_DELETE_EMAIL_MESSAGE, username));
      }
      List<Email> emails = mailRemoteIds.stream().map(mailRemoteId -> {
        try {
          return getEmailByMailRemoteIdAndUserId(mailRemoteId, username, false, false, false, false);
        } catch (Exception e) {
          LOG.error("Error getting email {} for user {}", mailRemoteId, username, e);
          return null;
        }
      }).filter(Objects::nonNull).collect(Collectors.toList());
      deleteEmails(emails);
      Store store = null;
      IMAPFolder inbox = null;
      try {
        store = (IMAPStore) userEmailSettingService.connect(userEmailSetting);
        inbox = (IMAPFolder) store.getFolder(INBOX_FOLDER_NAME);
        inbox.open(Folder.READ_WRITE);
        IMAPFolder trash = findTrashFolder(store);
        for (Long mailRemoteId : mailRemoteIds) {
          try {
            Message remoteMessage = ((UIDFolder) inbox).getMessageByUID(mailRemoteId);
            if (remoteMessage != null) {
              if (trash != null) {
                inbox.copyMessages(new Message[] { remoteMessage }, trash);
              }
              // On Gmail a COPY into [Gmail]/Trash MOVES the message (Trash is exclusive
              // with every label), so the server expunges it from INBOX right away and the
              // source handle is already gone — the delete has in fact succeeded. Only set
              // the DELETED flag when the message survived the copy (the non-Gmail case,
              // where the finally's inbox.close(true) expunges it), and treat an
              // already-expunged source as success rather than triggering the re-insert.
              try {
                if (!remoteMessage.isExpunged()) {
                  remoteMessage.setFlag(Flags.Flag.DELETED, true);
                }
              } catch (MessageRemovedException alreadyRemoved) {
                LOG.debug("Email {} already removed from INBOX by the copy to Trash for user {}", mailRemoteId, username);
              }
            }
          } catch (Exception e) {
            emails.stream().filter(email -> email.getMailRemoteId().equals(mailRemoteId)).findFirst().map(email -> {
              email.setId(null);
              return email;
            }).ifPresent(email -> {
              emailBoxStorage.createEmail(email);
              if (!CollectionUtils.isEmpty(email.getCategoryIds())) {
                email.getCategoryIds().stream().forEach(emailCategoryId -> {
                  categoryLinkService.link(emailCategoryId,
                                           new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE, String.valueOf(email.getId()), 0));
                });
              }
            });
            failedEmailDeletions++;
            LOG.error("Error when deleting email {} for user {}", mailRemoteId, username, e);
          }
        }
      } catch (Exception e) {
        LOG.error("Error when connecting store for user {}", username, e);
        emails.stream().forEach(email -> {
          email.setId(null);
          emailBoxStorage.createEmail(email);
          if (!CollectionUtils.isEmpty(email.getCategoryIds())) {
            email.getCategoryIds().stream().forEach(emailCategoryId -> {
              categoryLinkService.link(emailCategoryId,
                                       new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE, String.valueOf(email.getId()), 0));
            });
          }
        });
        throw new IllegalStateException(String.format("Error when connecting store for user %s", username));
      } finally {
        try {
          if (inbox != null && inbox.isOpen()) {
            inbox.close(true);
          }
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing inbox", messagingException);
        }
        try {
          if (store != null && store.isConnected()) {
            store.close();
          }
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing store", messagingException);
        }
        // Removing mirror rows changes the unread count whenever any of them
        // was unread, so the badge has to be told exactly as for a read/unread
        // change. In the finally because the local rows are already gone by the
        // time the remote step can fail, so a partial delete changed it too.
        broadcastUnreadCountChanged(username);
      }
    }
    return failedEmailDeletions;
  }

  public int archiveEmail(List<Long> mailRemoteIds, String username) throws IllegalAccessException {
    int failedEmailArchives = 0;
    if (mailRemoteIds != null && !mailRemoteIds.isEmpty()) {
      UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
      if (userEmailSetting.getEmailConnectorId() == null
          || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
        throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_ARCHIVE_EMAIL_MESSAGE, username));
      }
      List<Email> emails = mailRemoteIds.stream().map(mailRemoteId -> {
        try {
          return getEmailByMailRemoteIdAndUserId(mailRemoteId, username, false, false, false, false);
        } catch (Exception e) {
          LOG.error("Error getting email {} for user {}", mailRemoteId, username, e);
          return null;
        }
      }).filter(Objects::nonNull).collect(Collectors.toList());
      deleteEmails(emails);
      Store store = null;
      IMAPFolder inbox = null;
      try {
        store = (IMAPStore) userEmailSettingService.connect(userEmailSetting);
        inbox = (IMAPFolder) store.getFolder(INBOX_FOLDER_NAME);
        IMAPFolder archive = findArchiveFolder(store);
        inbox.open(Folder.READ_WRITE);
        for (Long mailRemoteId : mailRemoteIds) {
          try {
            Message remoteMessage = ((UIDFolder) inbox).getMessageByUID(mailRemoteId);
            if (remoteMessage != null) {
              if (archive != null) {
                inbox.copyMessages(new Message[] { remoteMessage }, archive);
                remoteMessage.setFlag(Flags.Flag.DELETED, true);
              }
            }
          } catch (Exception e) {
            emails.stream().filter(mail -> mail.getMailRemoteId().equals(mailRemoteId)).findFirst().map(email -> {
              email.setId(null);
              return email;
            }).ifPresent(email -> {
              emailBoxStorage.createEmail(email);
              if (!CollectionUtils.isEmpty(email.getCategoryIds())) {
                email.getCategoryIds().stream().forEach(emailCategoryId -> {
                  categoryLinkService.link(emailCategoryId,
                                           new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE, String.valueOf(email.getId()), 0));
                });
              }
            });
            failedEmailArchives++;
            LOG.error("Error when archiving email {} for user {}", mailRemoteId, username, e);
          }
        }
      } catch (Exception e) {
        LOG.error("Error when connecting store for user {}", username, e);
        emails.stream().forEach(email -> {
          email.setId(null);
          emailBoxStorage.createEmail(email);
          if (!CollectionUtils.isEmpty(email.getCategoryIds())) {
            email.getCategoryIds().stream().forEach(emailCategoryId -> {
              categoryLinkService.link(emailCategoryId,
                                       new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE, String.valueOf(email.getId()), 0));
            });
          }
        });
        throw new IllegalStateException(String.format("Error when connecting store for user %s", username));
      } finally {
        try {
          if (inbox != null && inbox.isOpen()) {
            inbox.close(true);
          }
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing inbox", messagingException);
        }
        try {
          if (store != null && store.isConnected()) {
            store.close();
          }
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing store", messagingException);
        }
        // Archiving removes the rows from the inbox the badge counts, so an
        // unread mail leaving the inbox changes the count just as reading it
        // does. In the finally because the local rows are already gone by the
        // time the remote step can fail, so a partial archive changed it too.
        broadcastUnreadCountChanged(username);
      }
    }
    return failedEmailArchives;
  }

  /**
   * Link one or more emails (by IMAP mailRemoteId) to an existing category, acting
   * as the given user (the category ACL is enforced by CategoryLinkService). Emails
   * already in the category are skipped. Returns the number of emails newly linked.
   */
  public int linkEmailsToCategory(List<Long> mailRemoteIds, long categoryId, String username) throws IllegalAccessException {
    if (CollectionUtils.isEmpty(mailRemoteIds)) {
      return 0;
    }
    if (categoryService.getCategory(categoryId) == null) {
      throw new IllegalArgumentException("emailConnector.category.notFound");
    }
    int linked = 0;
    for (Long mailRemoteId : mailRemoteIds) {
      Email email = getEmailByMailRemoteIdAndUserId(mailRemoteId, username, false, false, false, false);
      if (email == null) {
        continue;
      }
      try {
        categoryLinkService.link(categoryId,
                                 new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE, String.valueOf(email.getId()), 0),
                                 username);
        linked++;
      } catch (ObjectAlreadyExistsException e) {
        // Idempotent: the email is already in this category, nothing to do.
      } catch (ObjectNotFoundException e) {
        throw new IllegalArgumentException("emailConnector.category.notFound");
      }
    }
    return linked;
  }

  /**
   * Remove one or more emails (by IMAP mailRemoteId) from a category, acting as the
   * given user. Emails not currently in the category are skipped. Returns the number
   * of emails effectively unlinked.
   */
  public int unlinkEmailsFromCategory(List<Long> mailRemoteIds, long categoryId, String username) throws IllegalAccessException {
    if (CollectionUtils.isEmpty(mailRemoteIds)) {
      return 0;
    }
    int unlinked = 0;
    for (Long mailRemoteId : mailRemoteIds) {
      Email email = getEmailByMailRemoteIdAndUserId(mailRemoteId, username, false, false, false, false);
      if (email == null) {
        continue;
      }
      try {
        categoryLinkService.unlink(categoryId,
                                   new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE, String.valueOf(email.getId()), 0),
                                   username);
        unlinked++;
      } catch (ObjectNotFoundException e) {
        // Idempotent: the email was not linked to this category, nothing to remove.
      }
    }
    return unlinked;
  }

  /**
   * List the categories currently applied to the user's emails, resolved to their
   * display name in the given locale. Categories never used on any email are not
   * returned. Useful to discover a category id before tagging emails.
   */
  public List<EmailCategory> getEmailCategories(String username, Locale locale) throws IllegalAccessException {
    EmailBox emailBox = getEmailBox(username);
    Set<Long> categoryIds = emailBox.getEmails()
                                    .stream()
                                    .filter(email -> email.getCategoryIds() != null)
                                    .flatMap(email -> email.getCategoryIds().stream())
                                    .collect(Collectors.toCollection(LinkedHashSet::new));
    List<EmailCategory> categories = new ArrayList<>();
    for (Long categoryId : categoryIds) {
      try {
        CategoryWithName category = categoryService.getCategory(categoryId, username, locale);
        if (category != null) {
          categories.add(new EmailCategory(categoryId, category.getName()));
        }
      } catch (ObjectNotFoundException | IllegalAccessException e) {
        // Skip categories that were deleted or are no longer visible to the user.
      }
    }
    return categories;
  }

  /**
   * The add-on's own email categories a user can assign — Important / Invitation /
   * Notification / To review — resolved to their localized name and their declared icon. These are the leaf
   * categories seeded from the add-on's {@code default-categories.json}, returned
   * whether or not they are already in use, so the picker always offers the full set.
   *
   * @param username the mailbox owner
   * @param locale the locale to resolve category names in
   * @return the assignable email categories, in their defined order
   */
  public List<EmailCategory> getAvailableEmailCategories(String username, Locale locale) {
    List<EmailCategory> categories = new ArrayList<>();
    for (String nameId : DEFAULT_EMAIL_CATEGORY_NAME_IDS) {
      Long categoryId = getDefaultEmailCategoryId(nameId);
      if (categoryId == null) {
        continue;
      }
      try {
        CategoryWithName category = categoryService.getCategory(categoryId, username, locale);
        if (category != null) {
          // The nameId travels with the category: the display name is localized,
          // so the interface keys on the nameId to single out the Important
          // category (surfaced as its own filter chip in the mailbox). The icon
          // travels too — it is the category's own, persisted by the importer
          // from default-categories.json, so the interface never hardcodes one.
          categories.add(new EmailCategory(categoryId, category.getName(), nameId, category.getIcon()));
        }
      } catch (ObjectNotFoundException | IllegalAccessException e) {
        // Skip a default category the user cannot see (unexpected with *:/platform/users).
      }
    }
    return categories;
  }

  /**
   * The category ids of the add-on's own default email categories, resolved from the
   * {@code nameId -> id} mapping the platform's category importer persisted in settings.
   *
   * @return the default email category ids (empty until the importer has run)
   */
  public List<Long> getDefaultEmailCategoryIds() {
    List<Long> ids = new ArrayList<>();
    for (String nameId : DEFAULT_EMAIL_CATEGORY_NAME_IDS) {
      Long categoryId = getDefaultEmailCategoryId(nameId);
      if (categoryId != null) {
        ids.add(categoryId);
      }
    }
    return ids;
  }

  /**
   * Resolves one default email category's id from the {@code nameId -> id} mapping
   * the platform's category importer persisted in settings.
   *
   * @param nameId the category's declared nameId (see {@code default-categories.json})
   * @return the category id, or null when the importer has not created it (yet)
   */
  private Long getDefaultEmailCategoryId(String nameId) {
    SettingValue<?> settingValue = settingService.get(CATEGORY_IMPORT_CONTEXT, CATEGORY_IMPORT_SCOPE, nameId);
    if (settingValue != null && settingValue.getValue() != null) {
      try {
        return Long.parseLong(settingValue.getValue().toString());
      } catch (NumberFormatException e) {
        LOG.debug("Invalid category id stored for {}", nameId);
      }
    }
    return null;
  }

  public void sendEmail(Email email, String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SEND_EMAIL_MESSAGE, username));
    }
    String emailAddress = userEmailSetting.getEmailAddress();
    String emailPassword = userEmailSetting.getEmailPassword();
    EmailConnector emailConnector =
                                  emailConnectorService.getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId()));
    Properties props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp." + emailConnector.getSmtpSecurityType() + ".enable", "true");
    props.put("mail.smtp.host", emailConnector.getSmtpUrl());
    props.put("mail.smtp.port", emailConnector.getSmtpPort());
    Session session = Session.getInstance(props, new Authenticator() {
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(emailAddress, emailPassword);
      }
    });
    List<String> uploadIds = new ArrayList<>();
    try {
      Message message = new MimeMessage(session);
      Profile userProfile = EmailConnectorUtils.getUserProfileByEmail(emailAddress);
      message.setFrom(new InternetAddress(emailAddress, userProfile != null ? userProfile.getFullName() : null));
      if (!CollectionUtils.isEmpty(email.getTo())) {
        String toRecipients = email.getTo()
                                   .stream()
                                   .map(EmailRecipient::getAddress)
                                   .filter(Objects::nonNull)
                                   .filter(address -> !address.isBlank())
                                   .collect(Collectors.joining(","));
        message.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toRecipients));
      }
      if (!CollectionUtils.isEmpty(email.getCc())) {
        String ccRecipients = email.getCc()
                                   .stream()
                                   .map(EmailRecipient::getAddress)
                                   .filter(Objects::nonNull)
                                   .filter(address -> !address.isBlank())
                                   .collect(Collectors.joining(","));
        message.setRecipients(Message.RecipientType.CC, InternetAddress.parse(ccRecipients));
      }
      if (!CollectionUtils.isEmpty(email.getBcc())) {
        String bccRecipients = email.getBcc()
                                    .stream()
                                    .map(EmailRecipient::getAddress)
                                    .filter(Objects::nonNull)
                                    .filter(address -> !address.isBlank())
                                    .collect(Collectors.joining(","));

        message.setRecipients(Message.RecipientType.BCC, InternetAddress.parse(bccRecipients));
      }
      message.setSubject(email.getSubject());
      String currentDomain = CommonsUtils.getCurrentDomain();
      Document contentDoc = Jsoup.parseBodyFragment(HtmlUtils.transform(email.getContent().getBody(), null));
      for (Element link : contentDoc.select("a[href^=/portal]")) {
        link.select("i").remove();
        String href = link.attr("href");
        link.attr("href", currentDomain + href);
      }
      applyContentAndAttachments(message, email, contentDoc.body().html(), uploadIds);
      if (!StringUtils.isEmpty(email.getMailHeaderId())) {
        String parentMessageId = email.getMailHeaderId();
        message.setHeader(HEADER_IN_REPLY_TO, parentMessageId);
        // RFC 5322 §3.6.4: References is the parent's own References plus the parent's
        // Message-ID — not just the parent id, otherwise a third message in the chain
        // loses the link to the first and starts a new thread.
        String parentReferences = emailBoxStorage.getMailReferencesByMailHeaderId(parentMessageId, username);
        String referencesHeader = EmailThreadingUtils.buildReferencesHeader(parentReferences, parentMessageId);
        if (!StringUtils.isEmpty(referencesHeader)) {
          message.setHeader(HEADER_REFERENCES, referencesHeader);
        }
      }
      Transport.send(message);
      String emailType = StringUtils.isEmpty(email.getMailHeaderId()) ? "newEmail" : "reply";
      listenerService.broadcast(EmailConnectorUtils.SEND_EMAIL, username, emailType);
      publishEmailSentEvent(username, email);
      try {
        copyToSentFolder(message, username, userEmailSetting);
      } catch (IllegalStateException e) {
        LOG.warn("Email sent but could not be copied to Sent folder for user {}", username, e);
      }
    } catch (MessagingException | UnsupportedEncodingException e) {
      // The server, the port and the security mode belong in this line. A failure
      // here says nothing about WHICH server refused: the exception names the
      // condition ("451 4.3.2 Internal server error") and no more, so the same
      // mailbox failing on one deployment and working on another is unanswerable
      // from the log alone -- exactly the question that gets asked first. The
      // connect-failure path already prints the host; the authentication path,
      // which is the commoner failure, printed nothing.
      LOG.error("Error when sending email for user {} through {}:{} ({})",
                username,
                emailConnector.getSmtpUrl(),
                emailConnector.getSmtpPort(),
                emailConnector.getSmtpSecurityType(),
                e);
      throw new IllegalStateException(String.format("Error when sending email for user %s", username));
    } finally {
      // Free the commons temporary upload resources only after the message (and its Sent-folder copy) has been built,
      // since the attachment body parts stream their bytes lazily from those temporary files.
      removeUploadResources(uploadIds);
    }
  }

  /**
   * Publishes the {@link EmailSentEvent} carrying who the mail was addressed to
   * — To and Cc ONLY, never Bcc: Bcc is deliberately not a contact-collection
   * signal, so it is stripped here at the source rather than trusted to every
   * consumer. Fenced so an event consumer's failure can never fail a mail the
   * SMTP server already accepted.
   *
   * @param username the sender
   * @param email the composed email as sent
   */
  private void publishEmailSentEvent(String username, Email email) {
    try {
      List<EmailRecipient> recipients = new ArrayList<>();
      if (!CollectionUtils.isEmpty(email.getTo())) {
        recipients.addAll(email.getTo());
      }
      if (!CollectionUtils.isEmpty(email.getCc())) {
        recipients.addAll(email.getCc());
      }
      eventPublisher.publishEvent(new EmailSentEvent(username, recipients));
    } catch (Exception e) {
      LOG.warn("Error publishing the sent-email event for user {}", username, e);
    }
  }

  /**
   * Sets the body of an outgoing message. When the composed email carries no
   * attachment the body is a single {@code text/html} part; otherwise a
   * {@code multipart/mixed} is built with the HTML body followed by one part per
   * attachment. Each attachment is resolved from its commons upload id to the
   * temporary file backing it, so no ecms/documents dependency is required. The
   * resolved upload ids are collected into {@code uploadIds} for later cleanup.
   *
   * @param message the message being composed
   * @param email the composed email holding the optional {@code attachments}
   * @param bodyHtml the sanitized HTML body
   * @param uploadIds mutable list populated with the upload ids that were attached
   * @throws MessagingException if a body part cannot be built
   */
  private void applyContentAndAttachments(Message message,
                                          Email email,
                                          String bodyHtml,
                                          List<String> uploadIds) throws MessagingException {
    if (CollectionUtils.isEmpty(email.getAttachments())) {
      message.setContent(bodyHtml, "text/html; charset=UTF-8");
      return;
    }
    UploadService uploadService = CommonsUtils.getService(UploadService.class);
    MimeMultipart multipart = new MimeMultipart("mixed");
    MimeBodyPart htmlPart = new MimeBodyPart();
    htmlPart.setContent(bodyHtml, "text/html; charset=UTF-8");
    multipart.addBodyPart(htmlPart);
    long totalSize = 0;
    for (EmailOutgoingAttachment attachment : email.getAttachments()) {
      if (attachment == null || StringUtils.isEmpty(attachment.getUploadId())) {
        continue;
      }
      UploadResource uploadResource = uploadService.getUploadResource(attachment.getUploadId());
      if (uploadResource == null || uploadResource.getStoreLocation() == null) {
        throw new IllegalStateException(String.format("Upload resource %s is no longer available", attachment.getUploadId()));
      }
      uploadIds.add(attachment.getUploadId());
      File file = new File(uploadResource.getStoreLocation());
      totalSize += file.length();
      if (totalSize > MAX_OUTGOING_ATTACHMENTS_SIZE) {
        throw new IllegalStateException("emailConnector.mailBox.newEmail.attach.maxSize.error");
      }
      MimeBodyPart attachmentPart = new MimeBodyPart();
      attachmentPart.setDataHandler(new DataHandler(new FileDataSource(file)));
      String fileName = StringUtils.isNotBlank(attachment.getName()) ? attachment.getName() : uploadResource.getFileName();
      try {
        attachmentPart.setFileName(MimeUtility.encodeText(fileName, "UTF-8", null));
      } catch (UnsupportedEncodingException e) {
        attachmentPart.setFileName(fileName);
      }
      attachmentPart.setDisposition(Part.ATTACHMENT);
      if (StringUtils.isNotBlank(attachment.getMimeType())) {
        attachmentPart.setHeader("Content-Type", attachment.getMimeType());
      }
      multipart.addBodyPart(attachmentPart);
    }
    message.setContent(multipart);
  }

  /**
   * Removes the commons temporary upload resources that backed the attachments
   * of an outgoing email. Failures are swallowed (logged at debug level) since
   * they are not incidents and must not fail an email that was already sent.
   *
   * @param uploadIds the upload ids to release (may be empty)
   */
  private void removeUploadResources(List<String> uploadIds) {
    if (CollectionUtils.isEmpty(uploadIds)) {
      return;
    }
    UploadService uploadService = CommonsUtils.getService(UploadService.class);
    for (String uploadId : uploadIds) {
      try {
        uploadService.removeUploadResource(uploadId);
      } catch (Exception e) {
        LOG.debug("Could not remove upload resource {}", uploadId, e);
      }
    }
  }

  /**
   * Creates a local row for every server message not yet cached. Deliberately serial:
   * computeThreadId reads the threads of the messages inserted before it and merges
   * threads as it goes, so running these iterations concurrently would race the
   * merges and silently re-fragment conversations. The processing ORDER, on the
   * other hand, stopped mattering when computeThreadId learned to look up
   * conversations in both directions — single-threadedness is the contract now, not
   * sequence. The expensive part, the per-message body fetch, is instead served
   * from {@code prefetchedContents} when the parallel prefetch got there first; a miss
   * falls back to the same serial fetch as always, so the map can be empty (or
   * partial, or entirely wrong about what exists) without affecting correctness.
   * <p>
   * "Not yet cached" is decided from {@code knownEmailsByUid}, loaded once per folder
   * — NOT by a per-message database lookup. The lookup that used to run here was one
   * SELECT (with an attachments join and a category-link query behind it) for every
   * message of the window, ~5000 statements per routine sync on a 5000-message cache;
   * the already-cached rows' flag/threading reconciliation now happens in bulk in
   * {@link #reconcileKnownEmails}.
   *
   * @param uidFolder the open remote folder, to resolve each message's UID
   * @param serverMessages the folder window to reconcile, in mailbox order
   * @param username the mailbox owner
   * @param folderKey the {@link MailFolder} discriminator to stamp on new rows
   * @param prefetchedContents bodies already fetched in parallel, keyed by IMAP UID;
   *          consulted before falling back to a per-message FETCH
   * @param knownEmailsByUid the cached rows of this folder indexed by IMAP UID;
   *          messages found here are skipped
   * @param fetchedParts counter of MIME part bodies the serial fallback pulls, null
   *          when nobody is measuring
   * @return the IMAP UIDs of the messages newly cached by this pass
   */
  private List<Long> createEmails(UIDFolder uidFolder,
                            Message[] serverMessages,
                            String username,
                            String folderKey,
                            Map<Long, EmailContent> prefetchedContents,
                            Map<Long, Email> knownEmailsByUid,
                            MimePartStats fetchedParts) throws MessagingException, IllegalAccessException {
    List<Long> newEmailIds = new ArrayList<>();
    for (Message message : serverMessages) {
      try {
        long messageUid = uidFolder.getUID(message);
        if (!knownEmailsByUid.containsKey(messageUid)) {
          EmailContent emailContent = prefetchedContents.get(messageUid);
          if (emailContent == null) {
            LongAdder messageParts = new LongAdder();
            emailContent = EmailConnectorUtils.getMessageContent(messageUid, message, messageParts);
            if (fetchedParts != null) {
              fetchedParts.record(mailTypeOf(message), messageParts.sum());
            }
          }
          EmailSender emailSender = message.getFrom() != null
                                    && message.getFrom().length != 0 ?
                                                                     EmailConnectorUtils.getEmailSender(message.getFrom()[0],
                                                                                                        false) :
                                                                     null;
          List<EmailRecipient> emailToRecipients =
                                                 EmailConnectorUtils.getEmailRecipients(message.getRecipients(Message.RecipientType.TO),
                                                                                        username,
                                                                                        false);
          List<EmailRecipient> emailCcRecipients =
                                                 EmailConnectorUtils.getEmailRecipients(message.getRecipients(Message.RecipientType.CC),
                                                                                        username,
                                                                                        false);
          List<EmailRecipient> emailBccRecipients =
                                                  EmailConnectorUtils.getEmailRecipients(message.getRecipients(Message.RecipientType.BCC),
                                                                                         username,
                                                                                         false);

          List<EmailRecipient> emailReplyToRecipients = EmailConnectorUtils.getEmailRecipients(message.getReplyTo(),
                                                                                               username,
                                                                                               false);
          String mailHeaderId = ((MimeMessage) message).getMessageID();
          String inReplyTo = firstHeader(message, HEADER_IN_REPLY_TO);
          String references = firstHeader(message, HEADER_REFERENCES);
          String threadIndexRoot = EmailThreadingUtils.extractThreadIndexRoot(firstHeader(message, HEADER_THREAD_INDEX));
          String threadId = computeThreadId(username, mailHeaderId, messageUid, inReplyTo, references, threadIndexRoot);
          emailBoxStorage.createEmail(new Email(null,
                                                messageUid,
                                                mailHeaderId,
                                                username,
                                                null,
                                                message.getSubject(),
                                                emailContent,
                                                message.getReceivedDate(),
                                                emailSender,
                                                message.isSet(Flags.Flag.SEEN),
                                                true,
                                                emailToRecipients,
                                                emailCcRecipients,
                                                emailBccRecipients,
                                                emailReplyToRecipients,
                                                null,
                                                null,
                                                threadId,
                                                inReplyTo,
                                                references,
                                                folderKey,
                                                threadIndexRoot != null ? threadIndexRoot : "",
                                                isAutoSubmitted(message),
                                                firstHeader(message, HEADER_LIST_ID) != null,
                                                isPostableList(message),
                                                firstHeader(message, HEADER_LIST_UNSUBSCRIBE) != null,
                                                firstHeader(message, HEADER_ORIGINAL_SENDER),
                                                // \Flagged comes off the same prefetched FLAGS as SEEN
                                                // (buildSyncFetchProfile), so this read costs no round-trip.
                                                message.isSet(Flags.Flag.FLAGGED)));
          newEmailIds.add(messageUid);

        }
      } catch (Exception e) {
        LOG.warn("Error when storing email with subject {} for user {}", message.getSubject(), username, e);
      }
    }
    return newEmailIds;
  }

  /**
   * Reconciles the window's already-cached messages against the server, in
   * O(changes) database work: the flag diffs are computed in memory (the flags were
   * prefetched by the batched window FETCH, so this loop does no IMAP I/O) and
   * applied as at most five bulk statements — one per direction of the read flag,
   * one per direction of the starred flag, one clearing the recent badge. Before
   * this existed the known branch of
   * createEmails issued one SELECT and two guarded UPDATEs PER message, so a sync
   * that found NOTHING new still ran ~15,000 statements on a 5000-message cache —
   * the cost that made routine sync time scale with mailbox size instead of change
   * size. A steady-state sync now issues zero statements here. The rare threading
   * backfill keeps its per-row writes, gated so each row pays at most once.
   * <p>
   * Runs for the parallel download path too, which previously skipped known-message
   * reconciliation entirely (only the serial path walked the known messages): a
   * sync that downloaded ten new messages silently deferred every flag change to
   * the next quiet sync. Both paths now reconcile identically.
   *
   * @param uidFolder the open remote folder, to resolve each message's UID
   * @param serverMessages the folder window, with FLAGS already prefetched
   * @param knownEmailsByUid the cached rows of this folder indexed by IMAP UID
   * @param username the mailbox owner
   * @param folderKey the {@link MailFolder} discriminator scoping every write
   * @return the number of flag changes (read/unread and starred/unstarred) applied,
   *         for the sync summary line
   */
  private int reconcileKnownEmails(UIDFolder uidFolder,
                                   Message[] serverMessages,
                                   Map<Long, Email> knownEmailsByUid,
                                   String username,
                                   String folderKey) {
    List<Long> uidsToMarkRead = new ArrayList<>();
    List<Long> uidsToMarkUnread = new ArrayList<>();
    List<Long> uidsToClearRecent = new ArrayList<>();
    List<Long> uidsToStar = new ArrayList<>();
    List<Long> uidsToUnstar = new ArrayList<>();
    for (Message message : serverMessages) {
      try {
        long messageUid = uidFolder.getUID(message);
        Email email = knownEmailsByUid.get(messageUid);
        if (email == null) {
          // Not cached yet: createEmails owns it.
          continue;
        }
        boolean seen = message.isSet(Flags.Flag.SEEN);
        if (seen != email.isRead()) {
          (seen ? uidsToMarkRead : uidsToMarkUnread).add(messageUid);
        }
        // \Flagged rides the same prefetched FLAGS as SEEN, so diffing it here costs no
        // IMAP I/O -- and the server value always wins, which is what makes a star set
        // on a phone or in Gmail appear here (and an unstar disappear) on the next sync.
        boolean flagged = message.isSet(Flags.Flag.FLAGGED);
        if (flagged != email.isStarred()) {
          (flagged ? uidsToStar : uidsToUnstar).add(messageUid);
        }
        if (email.isRecent()) {
          uidsToClearRecent.add(messageUid);
        }
        backfillThreadingIfNeeded(email, message, messageUid, username, folderKey);
      } catch (Exception e) {
        LOG.warn("Error reconciling a cached email of user {} in folder {}", username, folderKey, e);
      }
    }
    if (!uidsToMarkRead.isEmpty()) {
      emailBoxStorage.updateEmailReadStatusByMailRemoteIds(uidsToMarkRead, username, true, folderKey);
    }
    if (!uidsToMarkUnread.isEmpty()) {
      emailBoxStorage.updateEmailReadStatusByMailRemoteIds(uidsToMarkUnread, username, false, folderKey);
    }
    if (!uidsToStar.isEmpty()) {
      emailBoxStorage.updateEmailStarredStatusByMailRemoteIds(uidsToStar, username, true, folderKey);
    }
    if (!uidsToUnstar.isEmpty()) {
      emailBoxStorage.updateEmailStarredStatusByMailRemoteIds(uidsToUnstar, username, false, folderKey);
    }
    if (!uidsToClearRecent.isEmpty()) {
      emailBoxStorage.markEmailsAsNotRecent(uidsToClearRecent, username, folderKey);
    }
    return uidsToMarkRead.size() + uidsToMarkUnread.size() + uidsToStar.size() + uidsToUnstar.size();
  }

  /**
   * Backfill threading on rows cached before the threading features existed. (a) A
   * row with no thread id yet gets one. (b) An already-threaded row gets its
   * Thread-Index root captured once and any threads sharing that root MERGED
   * (merge-only, never split) — this is what re-threads conversations cached before
   * the Thread-Index layer. The root is stored (empty string when the message
   * carries no Thread-Index) so each row is backfilled at most once — which is what
   * keeps these per-row writes out of the steady-state sync's cost.
   *
   * @param email the cached row, from the light sync view
   * @param message the live server message, headers already prefetched
   * @param messageUid the message's IMAP UID
   * @param username the mailbox owner
   * @param folderKey the {@link MailFolder} discriminator scoping the writes
   * @throws MessagingException if a header cannot be read
   */
  private void backfillThreadingIfNeeded(Email email,
                                         Message message,
                                         long messageUid,
                                         String username,
                                         String folderKey) throws MessagingException {
    if (StringUtils.isEmpty(email.getThreadId())) {
      String inReplyTo = firstHeader(message, HEADER_IN_REPLY_TO);
      String references = firstHeader(message, HEADER_REFERENCES);
      String threadIndexRoot = EmailThreadingUtils.extractThreadIndexRoot(firstHeader(message, HEADER_THREAD_INDEX));
      String threadId = computeThreadId(username, ((MimeMessage) message).getMessageID(), messageUid, inReplyTo, references, threadIndexRoot);
      emailBoxStorage.updateThreadInfo(username, messageUid, threadId, inReplyTo, references, folderKey,
                                       threadIndexRoot != null ? threadIndexRoot : "");
    } else if (email.getThreadIndexRoot() == null) {
      String threadIndexRoot = EmailThreadingUtils.extractThreadIndexRoot(firstHeader(message, HEADER_THREAD_INDEX));
      if (threadIndexRoot != null) {
        mergeThreadsSharingRoot(username, email.getThreadId(), threadIndexRoot);
      }
      emailBoxStorage.updateThreadIndexRoot(username, messageUid, folderKey, threadIndexRoot != null ? threadIndexRoot : "");
    }
  }

  /**
   * The conversation a message belongs to, resolved in BOTH directions so the answer
   * does not depend on the order messages are cached in: forward, the cached messages
   * its References / In-Reply-To point at; reverse, the cached messages whose
   * References / In-Reply-To point back at it (they were cached first — routine now
   * that the sync drains prefetch slices newest-first, in completion order). When
   * neither direction (nor a shared Thread-Index root) finds anything, the message
   * starts its own thread keyed by its Message-ID (synthesized when the sender
   * omitted one). A message that lands between several distinct threads collapses
   * them into the oldest — the canonical thread id — so the conversation stays whole.
   *
   * @param username the mailbox owner
   * @param mailHeaderId the message's own Message-ID, may be null
   * @param messageUid the message's IMAP UID, used to synthesize an id when needed
   * @param inReplyTo the raw In-Reply-To header, may be null
   * @param references the raw References header, may be null
   * @param threadIndexRoot the Exchange Thread-Index conversation root, may be null
   * @return the thread id to store on the message, never null
   */
  private String computeThreadId(String username,
                                 String mailHeaderId,
                                 long messageUid,
                                 String inReplyTo,
                                 String references,
                                 String threadIndexRoot) {
    String ownMessageId = StringUtils.isNotEmpty(mailHeaderId) ? mailHeaderId
                                                               : EmailThreadingUtils.synthesizeMessageId(messageUid, username);
    // Collect the thread ids this message belongs with, from two signals: the RFC
    // References / In-Reply-To chain, and — for Exchange/Outlook mail — a shared
    // Thread-Index conversation root, which still links messages whose References
    // chain was broken by a subject change or an external forward.
    Set<String> siblingThreadIds = new LinkedHashSet<>();
    Set<String> referencedIds = EmailThreadingUtils.collectReferencedIds(inReplyTo, references);
    if (!referencedIds.isEmpty()) {
      siblingThreadIds.addAll(emailBoxStorage.getSiblingThreadIds(username, new ArrayList<>(referencedIds)));
    }
    if (StringUtils.isNotEmpty(threadIndexRoot)) {
      siblingThreadIds.addAll(emailBoxStorage.getThreadIdsByThreadIndexRoot(username, threadIndexRoot));
    }
    // The reverse of the References lookup: messages cached BEFORE this one whose own
    // chain points back at it. The forward lookup alone made the caching order a
    // correctness contract (a parent had to be cached before its replies, or the
    // conversation silently split in two); with both directions the conversation
    // reassembles whatever order the messages land in. Only ever queried with a real
    // Message-ID — a synthesized id cannot appear in another message's References.
    if (StringUtils.isNotEmpty(mailHeaderId)) {
      siblingThreadIds.addAll(emailBoxStorage.getThreadIdsReferencingMessageId(username, ownMessageId));
    }
    if (siblingThreadIds.isEmpty()) {
      return ownMessageId;
    }
    if (siblingThreadIds.size() == 1) {
      return siblingThreadIds.iterator().next();
    }
    List<String> siblings = new ArrayList<>(siblingThreadIds);
    String canonicalThreadId = emailBoxStorage.getOldestThreadId(username, siblings);
    List<String> threadIdsToMerge = siblings.stream().filter(id -> !id.equals(canonicalThreadId)).toList();
    emailBoxStorage.mergeThreads(username, canonicalThreadId, threadIdsToMerge);
    return canonicalThreadId;
  }

  /**
   * Merge every thread that shares an Exchange Thread-Index conversation root into
   * one, collapsing to the oldest canonical thread id. Merge-only — it never resets
   * a message's thread id, so it can only join fragmented conversations, never split
   * a correctly-threaded one. Used to re-thread rows cached before the Thread-Index
   * layer.
   *
   * @param username the mailbox owner
   * @param currentThreadId the thread id of the row being backfilled
   * @param threadIndexRoot the message's Thread-Index conversation root (non-null)
   */
  private void mergeThreadsSharingRoot(String username, String currentThreadId, String threadIndexRoot) {
    Set<String> threadIds = new LinkedHashSet<>(emailBoxStorage.getThreadIdsByThreadIndexRoot(username, threadIndexRoot));
    if (StringUtils.isNotEmpty(currentThreadId)) {
      threadIds.add(currentThreadId);
    }
    if (threadIds.size() <= 1) {
      return;
    }
    List<String> ids = new ArrayList<>(threadIds);
    String canonicalThreadId = emailBoxStorage.getOldestThreadId(username, ids);
    List<String> threadIdsToMerge = ids.stream().filter(id -> !id.equals(canonicalThreadId)).toList();
    emailBoxStorage.mergeThreads(username, canonicalThreadId, threadIdsToMerge);
  }

  /**
   * On-demand cross-folder thread completion. When a conversation's cached messages
   * reference ancestors we never synced — typically because they are archived in
   * Gmail (they lost the {@code INBOX} label and live only in the {@code \All} "All
   * Mail" superset, which bulk sync deliberately excludes to avoid duplicating the
   * whole mailbox) — fetch just those ancestors from the archive superset, persist
   * them under {@link MailFolder#ALL_MAIL}, and merge them into the conversation.
   * <p>
   * Provider-agnostic: the signal is RFC 5322 {@code References}/{@code Message-ID},
   * and the only Gmail-specific step is discovering the {@code \All} folder (via its
   * special-use attribute). On a provider without such a superset it is a no-op —
   * archived mail there already lives in a synced {@code \Archive} folder. Gated on
   * there being an obviously-missing ancestor, so a completed thread reopens with no
   * IMAP round-trip. Best-effort: any failure leaves the cached thread untouched.
   *
   * @param username the mailbox owner
   * @param threadId the conversation opened by the user
   * @param userEmailSetting the user's connector binding
   * @return the canonical thread id to read back (the input id, or the older id the
   *         conversation collapsed to once its archived root was added)
   */
  private String completeThreadFromArchive(String username, String threadId, UserEmailSetting userEmailSetting) {
    try {
      List<Email> cached = emailBoxStorage.getEmailsByThreadId(username, threadId, userEmailSetting.getEmailAddress());
      if (cached.isEmpty()) {
        return threadId;
      }
      // The ids we already hold, and every id the cached messages point back to.
      Set<String> cachedOwnIds = new HashSet<>();
      Set<String> knownIds = new LinkedHashSet<>();
      for (Email email : cached) {
        if (StringUtils.isNotEmpty(email.getMailHeaderId())) {
          cachedOwnIds.add(email.getMailHeaderId());
          knownIds.add(email.getMailHeaderId());
        }
        knownIds.addAll(EmailThreadingUtils.collectReferencedIds(email.getInReplyTo(), email.getMailReferences()));
      }
      // Ancestors the thread references but that are not in the cache: the archived tail.
      List<String> missingIds = knownIds.stream()
                                        .filter(id -> !cachedOwnIds.contains(id))
                                        .limit(ARCHIVE_COMPLETION_SEARCH_LIMIT)
                                        .toList();
      if (missingIds.isEmpty()) {
        // Nothing obviously missing — skip the IMAP round-trip so repeat opens stay fast.
        return threadId;
      }
      return fetchArchivedThreadTail(username, threadId, userEmailSetting, missingIds, cachedOwnIds, knownIds);
    } catch (Exception e) {
      LOG.warn("Could not complete thread {} from archive for user {}", threadId, username, e);
      return threadId;
    }
  }

  /**
   * Fetch the archived ancestors of a conversation from the provider's {@code \All}
   * superset and merge them into the thread. See {@link #completeThreadFromArchive}.
   *
   * @param username the mailbox owner
   * @param threadId the conversation opened by the user
   * @param userEmailSetting the user's connector binding
   * @param missingIds the {@code Message-ID}s referenced but not yet cached
   * @param cachedOwnIds the {@code Message-ID}s already cached (any folder), for dedupe
   * @param knownIds every id in the conversation, used to unify thread ids after insert
   * @return the canonical thread id after any merge (see {@link #completeThreadFromArchive})
   */
  private String fetchArchivedThreadTail(String username,
                                         String threadId,
                                         UserEmailSetting userEmailSetting,
                                         List<String> missingIds,
                                         Set<String> cachedOwnIds,
                                         Set<String> knownIds) throws MessagingException, IllegalAccessException {
    Store store = null;
    IMAPFolder allMail = null;
    try {
      store = (IMAPStore) userEmailSettingService.connect(userEmailSetting);
      allMail = findAllMailFolder(store);
      if (allMail == null) {
        // Non-Gmail: archived mail lives in a real \Archive folder already synced by 4B.
        return threadId;
      }
      allMail.open(Folder.READ_ONLY);
      Message[] found = allMail.search(buildMessageIdSearchTerm(missingIds));
      if (found == null || found.length == 0) {
        return threadId;
      }
      // Prefetch flags/envelope/threading headers in one round-trip before reading ids.
      allMail.fetch(found, buildSyncFetchProfile());
      // Keep only genuinely-missing messages: a hit may be an INBOX message that is also
      // in All Mail (same Message-ID, different per-folder UID) — caching it again would
      // duplicate it. Dedupe by Message-ID, not by UID.
      List<Message> toCache = new ArrayList<>();
      for (Message message : found) {
        String id = ((MimeMessage) message).getMessageID();
        if (StringUtils.isNotEmpty(id) && !cachedOwnIds.contains(id)) {
          toCache.add(message);
        }
      }
      if (toCache.isEmpty()) {
        return threadId;
      }
      // The Message-ID dedupe above only sees ids the CONVERSATION already carries; a
      // hit may still be cached under ALL_MAIL from an earlier completion whose thread
      // diverged. createEmails no longer looks rows up itself (the sync passes it the
      // folder's cache wholesale), so this path resolves its handful of candidates
      // individually — bounded by the search limit, on a user-triggered action.
      Map<Long, Email> knownAllMailByUid = new HashMap<>();
      UIDFolder allMailUidFolder = allMail;
      for (Message message : toCache) {
        long messageUid = allMailUidFolder.getUID(message);
        Email cached = emailBoxStorage.getEmailByMailRemoteIdAndUserId(messageUid, username, null, MailFolder.ALL_MAIL, false, false, false);
        if (cached != null) {
          knownAllMailByUid.put(messageUid, cached);
        }
      }
      // A thread tail is a handful of messages, so no parallel body prefetch: pass an
      // empty map and let each body be fetched on this connection.
      createEmails(allMail, toCache.toArray(new Message[0]), username, MailFolder.ALL_MAIL, Map.of(), knownAllMailByUid, null);
      // The archived root references nothing cached, so createEmails may have started it
      // in its own thread. Unify every thread id now carried by the conversation's known
      // messages into the oldest canonical id.
      return unifyConversationThreads(username, threadId, knownIds);
    } finally {
      if (allMail != null && allMail.isOpen()) {
        try {
          allMail.close(false);
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing All Mail folder for user {}", username, messagingException);
        }
      }
      if (store != null && store.isConnected()) {
        try {
          store.close();
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing store for user {}", username, messagingException);
        }
      }
    }
  }

  /**
   * An IMAP search matching any of the given {@code Message-ID}s (an {@code OR} of
   * {@code HEADER Message-ID} terms).
   *
   * @param messageIds the ids to match, at least one
   * @return the search term
   */
  private SearchTerm buildMessageIdSearchTerm(List<String> messageIds) {
    List<SearchTerm> terms = messageIds.stream().map(id -> (SearchTerm) new MessageIDTerm(id)).toList();
    if (terms.size() == 1) {
      return terms.get(0);
    }
    return new OrTerm(terms.toArray(new SearchTerm[0]));
  }

  /**
   * Collapse into the opened conversation every thread id carried by any message whose
   * {@code Message-ID} belongs to it (e.g. an archived root just added under its own
   * id). Merge-only, and — unlike sync-time merges — the canonical id is the
   * <em>opened</em> thread id, not the oldest, so the id the reader and the already-
   * rendered inbox list hold stays valid when the conversation is reopened.
   *
   * @param username the mailbox owner
   * @param threadId the conversation opened by the user, kept as canonical
   * @param knownIds every {@code Message-ID} in the conversation
   * @return the (unchanged) opened thread id
   */
  private String unifyConversationThreads(String username, String threadId, Set<String> knownIds) {
    Set<String> threadIds = new LinkedHashSet<>(emailBoxStorage.getSiblingThreadIds(username, new ArrayList<>(knownIds)));
    threadIds.remove(threadId);
    if (threadIds.isEmpty()) {
      return threadId;
    }
    emailBoxStorage.mergeThreads(username, threadId, new ArrayList<>(threadIds));
    return threadId;
  }

  /**
   * Search only what is already cached locally, for the platform's unified search.
   * <p>
   * The global search bar fires every connector at once and shows the page when the
   * slowest answers. This add-on is the only one that could go to a mail server, and
   * an IMAP round-trip next to a set of Elasticsearch queries would make every
   * search in the platform wait on email — including the ones that were never about
   * email. So this reads the local mirror and answers immediately;
   * {@link #searchEmails} remains the way to reach the whole mailbox, offered from
   * the results as a deliberate second step.
   * <p>
   * The matching is done in Java rather than in SQL on purpose: the subject and body
   * are CLOB columns, and HSQLDB refuses {@code LOCATE} on a CLOB, so a database-side
   * text search would work on MySQL and fail on a developer's machine. The set is
   * bounded by the mailbox cache size, so filtering it in memory is cheap.
   *
   * @param username the mailbox owner
   * @param query free text matched against the subject, the sender and the body
   * @param limit how many hits to return, newest first
   * @return the newest matching cached messages plus how many matched in total
   * @throws IllegalAccessException if the user is not allowed to read their mailbox
   */
  public EmailSearchResultPage searchCachedEmails(String username, String query, int limit) throws IllegalAccessException {
    return searchCachedEmails(username, query, false, limit);
  }

  /**
   * The same search, narrowed to the messages the user favorited.
   * <p>
   * This is what the unified search's Favorites filter asks for. A favorite here is
   * the mail server's {@code \Flagged} flag, the same one the mailbox shows, so the
   * filter agrees with what the user sees in their webmail.
   *
   * @param username the mailbox owner
   * @param query free text matched against the subject, the sender and the body
   * @param favoritesOnly when {@code true}, only favorited messages match
   * @param limit how many hits to return, newest first
   * @return the newest matching cached messages plus how many matched in total
   * @throws IllegalAccessException if the user is not allowed to read their mailbox
   */
  public EmailSearchResultPage searchCachedEmails(String username,
                                                  String query,
                                                  boolean favoritesOnly,
                                                  int limit) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SEARCH_EMAIL_MESSAGE, username));
    }
    if (StringUtils.isBlank(query)) {
      throw new IllegalArgumentException("emailConnector.search.criteriaRequired");
    }
    String term = query.trim().toLowerCase();
    List<Email> matches = emailBoxStorage.getEmails(username)
                                         .stream()
                                         .filter(email -> !favoritesOnly || email.isStarred())
                                         .filter(email -> matchesCachedEmail(email, term))
                                         .sorted(Comparator.comparing(Email::getReceivedDate,
                                                                      Comparator.nullsLast(Comparator.reverseOrder())))
                                         .toList();
    List<EmailSearchResult> results = matches.stream()
                                             .limit(Math.max(limit, 1))
                                             .map(email -> new EmailSearchResult(email.getMailRemoteId(),
                                                                                 email.getFolder(),
                                                                                 email.getSubject(),
                                                                                 email.getSender(),
                                                                                 email.getReceivedDate(),
                                                                                 email.isRead(),
                                                                                 email.isStarred(),
                                                                                 true,
                                                                                 buildExcerpt(email, term)))
                                             .toList();
    return new EmailSearchResultPage(results, matches.size(), favoritesOnly);
  }

  /**
   * A short piece of the message to show under the subject in the results.
   * <p>
   * It is the text around the searched words when the body is what matched, so the
   * reader can see why the message came back; when the match was in the subject or
   * the sender, it is simply how the message opens. The body is reduced to text
   * first, or the quote would be a mouthful of markup.
   *
   * @param email the cached message
   * @param term the searched text, already lower-cased and trimmed
   * @return the excerpt, or {@code null} when the message has no readable body
   */
  private String buildExcerpt(Email email, String term) {
    String body = email.getContent() == null ? null : email.getContent().getBody();
    if (StringUtils.isBlank(body)) {
      return null;
    }
    String text = Jsoup.parse(body).text().trim();
    if (StringUtils.isBlank(text)) {
      return null;
    }
    int match = StringUtils.indexOfIgnoreCase(text, term);
    if (match < 0) {
      return StringUtils.abbreviate(text, EXCERPT_LENGTH);
    }
    int start = Math.max(0, match - EXCERPT_CONTEXT);
    int end = Math.min(text.length(), match + term.length() + EXCERPT_CONTEXT);
    String window = text.substring(start, end).trim();
    return (start > 0 ? "… " : "") + window + (end < text.length() ? " …" : "");
  }

  /**
   * Whether a cached message matches the searched text, over its subject, its sender
   * and its body.
   * <p>
   * The body is stored as HTML, so it is reduced to text before matching: without
   * that, a search for "style" or "div" would hit half the mailbox on markup the
   * user never sees.
   *
   * @param email the cached message
   * @param term the searched text, already lower-cased and trimmed
   * @return {@code true} when the message matches
   */
  private boolean matchesCachedEmail(Email email, String term) {
    if (StringUtils.containsIgnoreCase(email.getSubject(), term)) {
      return true;
    }
    EmailSender sender = email.getSender();
    if (sender != null
        && (StringUtils.containsIgnoreCase(sender.getName(), term)
            || StringUtils.containsIgnoreCase(sender.getAddress(), term))) {
      return true;
    }
    String body = email.getContent() == null ? null : email.getContent().getBody();
    return StringUtils.isNotBlank(body) && StringUtils.containsIgnoreCase(Jsoup.parse(body).text(), term);
  }

  /**
   * Server-side mailbox search: an IMAP {@code SEARCH} over one remote folder, so a
   * user finds mail anywhere in that folder — a 161k-message inbox, not just the
   * ~1000-message local cache window (0.6% of it on the reference mailbox, which is
   * why filtering the cache was never a search).
   * <p>
   * Deliberately inert towards the sync: the search runs on its OWN short-lived IMAP
   * connection (never the sync's), opens the folder READ_ONLY, writes NOTHING — no
   * cache row, no sync state, no snapshot, no notification window — and never touches
   * the {@code syncingUsers} guard. It can therefore run at any time, including while
   * a synchronization is in flight, without either one noticing the other. Its single
   * database access is one read-only IN query decorating the hits with their
   * {@code cached} flag.
   * <p>
   * The cost discipline is the sync's: the SEARCH returns UIDs, the newest
   * {@code limit} hits get their flags + envelope + UID prefetched in ONE batched
   * {@link Folder#fetch} — never one FETCH per hit, which is the per-message
   * round-trip mistake that once turned a sync into half an hour — and no body is
   * ever read (a result row does not need one, and bodies are the one per-message
   * cost a batched FETCH cannot absorb).
   *
   * @param username the mailbox owner
   * @param query free text matched against the subject OR the sender (IMAP
   *          substring, case-insensitive), may be blank
   * @param from text matched against the sender only, may be blank
   * @param unreadOnly when {@code true}, only unread messages match
   * @param sinceDays only messages received in the last N days match, null for no
   *          date bound (IMAP {@code SINCE} has day granularity anyway)
   * @param folder the folder to search: {@code INBOX}, {@code SENT} or
   *          {@code ARCHIVE} (on Gmail, ARCHIVE searches the "All Mail" superset —
   *          the place archived mail actually lives)
   * @param limit how many hits to return, clamped to [1, {@value #SEARCH_MAX_RESULTS}]
   * @return the newest matching messages (newest first) plus the total match count
   * @throws IllegalAccessException if the user is not allowed to read their mailbox
   * @throws IllegalArgumentException if the folder is not searchable or no criterion
   *           at all was given (an empty SEARCH would match the whole mailbox)
   * @throws IllegalStateException if the mailbox cannot be reached or searched
   */
  public EmailSearchResultPage searchEmails(String username,
                                            String query,
                                            String from,
                                            boolean unreadOnly,
                                            Integer sinceDays,
                                            String folder,
                                            int limit) throws IllegalAccessException {
    return searchEmails(username, query, from, unreadOnly, false, sinceDays, folder, limit);
  }

  /**
   * The same server-side search, narrowed to the messages the user favorited.
   * <p>
   * The narrowing is done by the mail server, as one more term of the IMAP SEARCH:
   * asking for everything and dropping the rest here would return the newest hits and
   * then throw most of them away, leaving an older favorite invisible behind a page of
   * discarded matches.
   *
   * @param username the mailbox owner
   * @param query free text matched against the subject or the sender
   * @param from text matched against the sender only, may be blank
   * @param unreadOnly when {@code true}, only unread messages match
   * @param favoritesOnly when {@code true}, only messages carrying \Flagged match
   * @param sinceDays only messages received in the last N days match, null for no limit
   * @param folder the folder to search: INBOX, SENT or ARCHIVE
   * @param limit how many hits to return
   * @return the newest matching messages plus the total match count
   * @throws IllegalAccessException if the user is not allowed to search their mailbox
   */
  public EmailSearchResultPage searchEmails(String username,
                                            String query,
                                            String from,
                                            boolean unreadOnly,
                                            boolean favoritesOnly,
                                            Integer sinceDays,
                                            String folder,
                                            int limit) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SEARCH_EMAIL_MESSAGE, username));
    }
    if (!isSearchableFolder(folder)) {
      throw new IllegalArgumentException("emailConnector.folder.notBrowsable");
    }
    if (sinceDays != null && sinceDays < 0) {
      // A negative window is a future-dated lower bound: it matches nothing, silently,
      // and reads to the caller as "the search is broken" rather than "the input was".
      throw new IllegalArgumentException("emailConnector.search.invalidSinceDays");
    }
    Date since = sinceDays == null ? null : new Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(sinceDays));
    SearchTerm searchTerm = buildEmailSearchTerm(query, from, unreadOnly, favoritesOnly, since);
    if (searchTerm == null) {
      throw new IllegalArgumentException("emailConnector.search.criteriaRequired");
    }
    int cappedLimit = Math.min(Math.max(limit, 1), SEARCH_MAX_RESULTS);
    Store store = null;
    Folder remoteFolder = null;
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      remoteFolder = resolveSearchFolder(store, folder, username);
      if (remoteFolder == null) {
        // The mailbox has no such folder (e.g. no Sent yet): nothing to search.
        return new EmailSearchResultPage(List.of(), 0);
      }
      remoteFolder.open(Folder.READ_ONLY);
      Message[] found = remoteFolder.search(searchTerm);
      if (found == null || found.length == 0) {
        return new EmailSearchResultPage(List.of(), 0);
      }
      // The server lists matches in mailbox order (oldest first): the page is the
      // TAIL — the newest hits — because "the mail I'm looking for" skews recent
      // even when the query matches years of history.
      Message[] page = found.length <= cappedLimit ? found : Arrays.copyOfRange(found, found.length - cappedLimit, found.length);
      // One batched round-trip for the whole page: reading subject/from/date/flags
      // below is then served from memory. Without this every getter is its own
      // per-message FETCH — the regression that must never come back.
      remoteFolder.fetch(page, buildSearchResultFetchProfile());
      UIDFolder uidFolder = (UIDFolder) remoteFolder;
      List<Long> pageUids = new ArrayList<>(page.length);
      for (Message message : page) {
        pageUids.add(uidFolder.getUID(message));
      }
      // One IN query for the whole page — never a per-hit lookup.
      Set<Long> cachedUids = new HashSet<>(emailBoxStorage.getCachedMailRemoteIds(username, folder, pageUids));
      List<EmailSearchResult> results = new ArrayList<>(page.length);
      for (int i = page.length - 1; i >= 0; i--) {
        try {
          long messageUid = pageUids.get(i);
          results.add(new EmailSearchResult(messageUid,
                                            folder,
                                            page[i].getSubject(),
                                            page[i].getFrom() != null && page[i].getFrom().length != 0
                                                ? EmailConnectorUtils.getEmailSender(page[i].getFrom()[0], false)
                                                : null,
                                            page[i].getReceivedDate(),
                                            page[i].isSet(Flags.Flag.SEEN),
                                            page[i].isSet(Flags.Flag.FLAGGED),
                                            cachedUids.contains(messageUid),
                                            // Envelope-only: quoting the body would cost
                                            // one round-trip per hit.
                                            null));
        } catch (Exception e) {
          // One unreadable hit must not lose the rest of the page.
          LOG.debug("Skipping an unreadable search hit in folder {} for user {}", folder, username, e);
        }
      }
      return new EmailSearchResultPage(results, found.length, favoritesOnly);
    } catch (SearchException e) {
      // The server refused the CRITERIA, not the mailbox — the CHARSET path: a query
      // carrying accents ("réunion") makes JavaMail issue SEARCH CHARSET UTF-8, and a
      // server rejecting the charset makes it exhaust its charset list. A rejected
      // input owes the caller a 400 with a code they can act on, not the generic 500
      // the catch-all below would produce.
      //
      // Reachable only with mail.imaps.throwsearchexception=true, which we do NOT set
      // today: IMAPFolder.search swallows both CommandFailedException ("unsupported
      // charset or search criterion") and SearchException by default and falls back to
      // Folder.search — a CLIENT-side scan that pulls the whole folder down to match
      // locally. That fallback is silent and, on the 161k-message mailboxes this
      // feature targets, is the linear scan the design avoids everywhere else (see
      // buildEmailSearchTerm on why there is no BODY term); it reads as a hang, not an
      // error. Flipping that property is the real fix but it is not search's to make
      // alone — the same Session serves the sync and the archive thread-completion
      // search, which would start throwing where they now degrade. Mapped here so the
      // contract is already right the day it is flipped.
      LOG.debug("Search criteria refused by the server for folder {} and user {}", folder, username, e);
      throw new IllegalArgumentException("emailConnector.search.criteriaNotSupported");
    } catch (Exception e) {
      LOG.error("Error searching folder {} for user {}", folder, username, e);
      throw new IllegalStateException(String.format("Error when searching mailbox of user %s", username));
    } finally {
      closeQuietly(remoteFolder, store, username);
    }
  }

  /**
   * Opens a message the search found OUTSIDE the local cache window: fetches that
   * one message on demand from the server and caches it through the ordinary
   * {@link #createEmails} path, so the whole reader, threading and category
   * machinery works on it unchanged (thread id computed bidirectionally, row
   * readable by the existing {@code (user, folder, UID)} endpoints). Already-cached
   * messages are returned straight from the database, no IMAP at all.
   * <p>
   * The row is stamped with its TRUE folder — never a synthetic one — because the
   * {@code (user, folder, UID)} key must stay unique and truthful for every read
   * path. The consequence is a deliberate lifecycle, not corruption: a fetched
   * message older than the sync window is treated by the next full sync of that
   * folder as absent-from-window and evicted by {@code cleanupObsoleteEmails} (or
   * trimmed as overflow). That is the cache-window invariant RESTORING itself; the
   * message stays readable because this method simply re-fetches it on the next
   * open. Until that eviction the row is visible wherever the folder's cache is
   * (list, counts) — transient by design. A fetched message that happens to be
   * INSIDE the window just becomes a normal window row the next sync reconciles.
   * <p>
   * That self-restoration needs a sync to actually visit the folder, which is not
   * true of ARCHIVE on a provider whose archived mail lives only in an unsynced
   * {@code \All} superset — the Gmail case this feature's ARCHIVE mapping targets.
   * {@link #trimSearchFedFolderCache} bounds that one explicitly, so the promise
   * above holds on every provider rather than on most of them.
   * <p>
   * Mutually exclusive with a synchronization of this mailbox, via the sync's own
   * {@code syncingUsers} mutex: the sync decides "new vs known" from an in-memory
   * snapshot of the cache taken when it started, so a row inserted here mid-sync
   * could be created a second time by the sync — duplicate
   * {@code (user, folder, UID)} rows break every lookup on that key. Holding the
   * mutex (not merely reading it) closes the check-then-insert window too: a sync
   * firing during this fetch skips that run, which is the sync's own policy for
   * overlapping syncs and costs one period at most. When the mutex is already
   * held, the caller retries in a few seconds (HTTP 409).
   * <p>
   * No {@code NEW_EMAILS_SYNCED} broadcast: that event drives the new-mail
   * machinery (AI categorization batches, notification claims) and is a statement
   * about a sync run, which this is not — one deliberately-opened old message must
   * not wake either.
   *
   * @param mailRemoteId the message's IMAP UID in {@code folder}
   * @param folder the folder the search hit came from (INBOX / SENT / ARCHIVE)
   * @param username the mailbox owner
   * @return the full cached message, or null when it no longer exists on the server
   * @throws IllegalAccessException if the user is not allowed to read their mailbox
   * @throws IllegalArgumentException if the folder is not searchable
   * @throws IllegalStateException with message code
   *           {@code emailConnector.search.syncInProgress} while a sync is running,
   *           or a generic one when the mailbox cannot be reached
   */
  public Email fetchSearchedEmail(long mailRemoteId, String folder, String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
    }
    if (!isSearchableFolder(folder)) {
      throw new IllegalArgumentException("emailConnector.folder.notBrowsable");
    }
    Email cached = getEmailByMailRemoteIdAndUserId(mailRemoteId, username, folder, true, true, true, false);
    if (cached != null) {
      return cached;
    }
    // See the method javadoc: caching mid-sync races the sync's in-memory
    // known-UIDs snapshot into duplicate rows. ACQUIRE the sync's own mutex
    // rather than just reading it — a contains() check would leave a window
    // between the check and the insert below for a sync to start, snapshot the
    // cache without this row, and create it a second time.
    if (!syncingUsers.add(username)) {
      throw new IllegalStateException("emailConnector.search.syncInProgress");
    }
    Store store = null;
    Folder remoteFolder = null;
    try {
      // Re-check the cache now that the mutex is held: a sync that finished
      // between the miss above and the acquisition may have cached this UID.
      cached = getEmailByMailRemoteIdAndUserId(mailRemoteId, username, folder, true, true, true, false);
      if (cached != null) {
        return cached;
      }
      store = userEmailSettingService.connect(userEmailSetting);
      remoteFolder = resolveSearchFolder(store, folder, username);
      if (remoteFolder == null) {
        return null;
      }
      remoteFolder.open(Folder.READ_ONLY);
      UIDFolder uidFolder = (UIDFolder) remoteFolder;
      Message message = uidFolder.getMessageByUID(mailRemoteId);
      if (message == null) {
        // Deleted (or moved) on the server since the search listed it.
        return null;
      }
      // The full sync profile, not the search one: createEmails reads the threading
      // and distribution headers, and each unfetched header is its own round-trip.
      remoteFolder.fetch(new Message[] { message }, buildSyncFetchProfile());
      // Empty known map: the cache miss was re-checked under the mutex just above
      // and no sync can be inserting concurrently (mutex held). All DB writes
      // happen here, on the calling thread, exactly like the sync and the
      // thread-completion paths.
      createEmails(uidFolder, new Message[] { message }, username, folder, Map.of(), Map.of(), null);
      trimSearchFedFolderCache(store, username, folder, mailRemoteId);
      return getEmailByMailRemoteIdAndUserId(mailRemoteId, username, folder, true, true, true, false);
    } catch (MessagingException | RuntimeException e) {
      LOG.error("Error fetching searched email {} of folder {} for user {}", mailRemoteId, folder, username, e);
      throw new IllegalStateException(String.format("Error when fetching searched email for user %s", username));
    } finally {
      syncingUsers.remove(username);
      closeQuietly(remoteFolder, store, username);
    }
  }

  /**
   * Bounds the cache of a folder that NO bulk sync reconciles, after a search-fetch
   * has just inserted a row into it.
   * <p>
   * {@link #fetchSearchedEmail} documents its rows as transient: the next full sync
   * of that folder sees a message older than the window as absent-from-window and
   * {@code cleanupObsoleteEmails} evicts it. That holds for every folder the sync
   * actually visits — but not for the one case this feature makes routine. On a
   * provider with no syncable {@code \Archive} (Gmail, whose archived mail lives in
   * the {@code \All} superset the bulk sync deliberately never caches),
   * {@code resolveArchiveFolder} returns null, {@code syncFolderIfChanged} returns at
   * its null-folder guard, and {@code cleanupObsoleteEmails} therefore NEVER runs for
   * ARCHIVE. Without this, every archived message a user ever opens from search stays
   * for good — and those rows are visible in the folder's list and counts, so the
   * ARCHIVE tab slowly fills with search history.
   * <p>
   * Trims to the same window the sync gives a non-inbox folder, oldest first, and
   * never the row just fetched: that one is what the caller is about to read, and it
   * is precisely the row most likely to sort oldest.
   *
   * @param store the open store, to tell a search-fed folder from a synced one
   * @param username the mailbox owner
   * @param folder the folder just written to
   * @param justFetchedUid the UID inserted by the caller, exempt from the trim
   */
  private void trimSearchFedFolderCache(Store store, String username, String folder, long justFetchedUid) {
    try {
      if (!MailFolder.ARCHIVE.equals(folder) || resolveArchiveFolder(store, loadMailboxSyncState(username)) != null) {
        // Every other folder is bulk-synced, so the documented self-restoring
        // eviction does happen and this must not second-guess it.
        return;
      }
      int window = Math.min(emailConnectorService.getEmailBoxCacheSize(), NON_INBOX_FOLDER_SYNC_LIMIT);
      List<Email> cachedEmails = emailBoxStorage.getSyncEmails(username, folder);
      if (cachedEmails.size() <= window) {
        return;
      }
      List<Email> overflow = cachedEmails.subList(window, cachedEmails.size())
                                         .stream()
                                         .filter(email -> !Objects.equals(email.getMailRemoteId(), justFetchedUid))
                                         .toList();
      if (!overflow.isEmpty()) {
        LOG.debug("Trimming {} search-fetched rows from folder {} of user {}", overflow.size(), folder, username);
        deleteEmails(overflow);
      }
    } catch (Exception e) {
      // Housekeeping: a failure here must never cost the user the message they opened.
      LOG.warn("Could not trim the search-fed cache of folder {} for user {}", folder, username, e);
    }
  }

  /**
   * The IMAP search criteria, combined with AND; each piece is optional. The
   * free-text query matches subject OR sender — the two fields a result row is
   * recognized by — while {@code from} pins the sender alone. Deliberately no
   * BODY term: on providers without a full-text index a body search is a linear
   * scan of the whole folder, which on a 161k-message mailbox is a timeout, not a
   * feature. Package-visible for tests.
   *
   * @param query free text matched against subject OR sender, may be blank
   * @param from text matched against the sender, may be blank
   * @param unreadOnly when {@code true}, restrict to messages without {@code \Seen}
   * @param since lower bound on the received date, may be null
   * @return the combined term, or null when no criterion at all was given
   */
  static SearchTerm buildEmailSearchTerm(String query, String from, boolean unreadOnly, Date since) {
    return buildEmailSearchTerm(query, from, unreadOnly, false, since);
  }

  /**
   * The same term, with the favorites narrowing the unified search's filter asks for.
   *
   * @param query free text matched against the subject or the sender
   * @param from text matched against the sender only
   * @param unreadOnly when {@code true}, only unread messages match
   * @param favoritesOnly when {@code true}, only messages carrying \Flagged match
   * @param since only messages received after this date match
   * @return the IMAP search term, or {@code null} when no criterion was given
   */
  static SearchTerm buildEmailSearchTerm(String query,
                                         String from,
                                         boolean unreadOnly,
                                         boolean favoritesOnly,
                                         Date since) {
    List<SearchTerm> terms = new ArrayList<>();
    if (StringUtils.isNotBlank(query)) {
      terms.add(new OrTerm(new SubjectTerm(query.trim()), new FromStringTerm(query.trim())));
    }
    if (StringUtils.isNotBlank(from)) {
      terms.add(new FromStringTerm(from.trim()));
    }
    if (unreadOnly) {
      terms.add(new FlagTerm(new Flags(Flags.Flag.SEEN), false));
    }
    if (favoritesOnly) {
      terms.add(new FlagTerm(new Flags(Flags.Flag.FLAGGED), true));
    }
    if (since != null) {
      terms.add(new ReceivedDateTerm(ComparisonTerm.GE, since));
    }
    if (terms.isEmpty()) {
      return null;
    }
    return terms.size() == 1 ? terms.get(0) : new AndTerm(terms.toArray(new SearchTerm[0]));
  }

  /**
   * The fetch profile of a search result page: flags + envelope + UID, everything a
   * result row renders from, in one batched round-trip. Deliberately LIGHTER than
   * {@link #buildSyncFetchProfile}: no MIME structure and none of the delivery
   * headers, because a result list never reads a body or classifies a message —
   * fetching them would tax every search with data nobody displays.
   *
   * @return the profile to pass to {@link Folder#fetch(Message[], FetchProfile)}
   */
  private FetchProfile buildSearchResultFetchProfile() {
    FetchProfile fetchProfile = new FetchProfile();
    fetchProfile.add(FetchProfile.Item.FLAGS);
    fetchProfile.add(FetchProfile.Item.ENVELOPE);
    fetchProfile.add(UIDFolder.FetchProfileItem.UID);
    return fetchProfile;
  }

  /**
   * Whether a folder key is one the search endpoints accept — the user-browsable
   * folders only. ALL_MAIL stays internal: it is a completion store, and on Gmail
   * the ARCHIVE mapping below already reaches the same "All Mail" superset.
   *
   * @param folder the requested {@link MailFolder} key
   * @return true for INBOX / SENT / ARCHIVE
   */
  private boolean isSearchableFolder(String folder) {
    return MailFolder.INBOX.equals(folder) || MailFolder.SENT.equals(folder) || MailFolder.ARCHIVE.equals(folder);
  }

  /**
   * The remote folder a search key targets. INBOX is the protocol-guaranteed name;
   * SENT reuses the sync's remembered-name resolution (the rediscovered name is
   * deliberately NOT persisted here — search must never write the sync state a
   * running sync may be about to save, so a rediscovery just costs the next search
   * one more LIST).
   * <p>
   * ARCHIVE resolves the way the SYNC does, and only falls back to the archive
   * DESTINATION lookup when the mailbox has no syncable {@code \Archive}. This is a
   * correctness requirement, not a preference: IMAP UIDs are per-folder, and every
   * cached row is keyed {@code (user, folder, UID)}. The rows filed under ARCHIVE are
   * written with UIDs read from {@link #findSyncableArchiveFolder} ({@code \Archive}
   * only), while the destination lookup {@link #findArchiveFolder} also accepts
   * {@code \All} and any name merely CONTAINING "archive"/"all"/"tous". On a mailbox
   * carrying both — a dedicated Archive folder and an All-Mail-type folder, where
   * the winner is decided by {@code listSubscribed("*")} order — the two resolvers
   * return different physical folders, and the shared ARCHIVE keyspace then mixes
   * UIDs from both: the {@code cached} flag compares UIDs across folders, the cache
   * pre-check in {@link #fetchSearchedEmail} can hand back a COMPLETELY DIFFERENT
   * message on a UID collision, and the row it inserts carries a foreign UID that
   * the next {@code \Archive} sync's {@code cleanupObsoleteEmails} misreads.
   * <p>
   * Resolving through the sync's own lookup removes the divergence at its source:
   * when a syncable Archive exists, search and sync address the same folder and the
   * keyspace has one owner. When it does not (the Gmail case — {@code \All} only),
   * the fallback still reaches "All Mail" and reach is unchanged, and the ARCHIVE
   * keyspace is then exclusively search-fed, hence self-consistent.
   *
   * @param store the connected store (the search's own, never the sync's)
   * @param folder the {@link MailFolder} key, already validated searchable
   * @param username the mailbox owner, to load the remembered folder names
   * @return the remote folder, or null when the mailbox has no such folder
   * @throws MessagingException if the folder list cannot be read
   */
  private Folder resolveSearchFolder(Store store, String folder, String username) throws MessagingException {
    if (MailFolder.INBOX.equals(folder)) {
      return store.getFolder(INBOX_FOLDER_NAME);
    }
    if (MailFolder.SENT.equals(folder)) {
      return resolveSentFolder(store, loadMailboxSyncState(username));
    }
    IMAPFolder syncableArchive = resolveArchiveFolder(store, loadMailboxSyncState(username));
    return syncableArchive != null ? syncableArchive : findArchiveFolder(store);
  }

  /**
   * Closes a search-side folder and store, best-effort — a close failure on a
   * read-only connection is noise, never an incident.
   *
   * @param folder the folder to close, possibly null or already closed
   * @param store the store to close, possibly null
   * @param username the mailbox owner, for the log
   */
  private void closeQuietly(Folder folder, Store store, String username) {
    if (folder != null && folder.isOpen()) {
      try {
        folder.close(false);
      } catch (MessagingException messagingException) {
        LOG.warn("Error when closing search folder for user {}", username, messagingException);
      }
    }
    if (store != null && store.isConnected()) {
      try {
        store.close();
      } catch (MessagingException messagingException) {
        LOG.warn("Error when closing search store for user {}", username, messagingException);
      }
    }
  }

  /**
   * The provider's "all mail" superset — Gmail's {@code \All} ("All Mail" / "Tous les
   * messages"). This is the folder bulk sync deliberately skips (see
   * {@link #findSyncableArchiveFolder}); thread completion targets it on demand.
   * Returns null when the provider exposes no such superset (most non-Gmail servers),
   * where archived mail already lives in a synced {@code \Archive} folder instead.
   *
   * @param store an open IMAP store
   * @return the {@code \All} folder, or null
   * @throws MessagingException if the folder list cannot be read
   */
  private IMAPFolder findAllMailFolder(Store store) throws MessagingException {
    for (Folder folder : store.getDefaultFolder().listSubscribed("*")) {
      if (!(folder instanceof IMAPFolder)) {
        continue;
      }
      IMAPFolder imapFolder = (IMAPFolder) folder;
      if (!imapFolder.exists()) {
        continue;
      }
      for (String attr : imapFolder.getAttributes()) {
        if (attr.equalsIgnoreCase("\\All")) {
          return imapFolder;
        }
      }
      String name = imapFolder.getFullName().toLowerCase();
      if (name.contains("all mail") || name.contains("tous les messages")) {
        return imapFolder;
      }
    }
    return null;
  }

  /**
   * The first value of a message header, or null when the header is absent.
   *
   * @param message the mail message
   * @param name the header name
   * @return the first header value, or null
   * @throws MessagingException if the header cannot be read
   */
  private static String firstHeader(Message message, String name) throws MessagingException {
    String[] values = message.getHeader(name);
    return values != null && values.length > 0 ? values[0] : null;
  }

  /**
   * Whether nobody typed this message: {@code Auto-Submitted} (RFC 3834, generated without
   * human intervention -- the explicit value {@code no} means the opposite and is ignored) or
   * the legacy {@code Precedence: bulk|junk}.
   * <p>
   * {@code Precedence: list} is deliberately excluded: mailing lists stamp it on every message
   * they relay, including one a colleague typed by hand, so treating it as automated files
   * genuine business mail as machine noise. {@code List-Unsubscribe} is excluded for the same
   * reason -- it is captured separately, since on its own it says only that the message passed
   * through bulk distribution machinery.
   *
   * @param message the freshly-fetched message
   * @return {@code true} when the message declares itself machine-generated
   */
  static boolean isAutoSubmitted(Message message) throws MessagingException {
    String autoSubmitted = firstHeader(message, HEADER_AUTO_SUBMITTED);
    if (StringUtils.isNotBlank(autoSubmitted) && !StringUtils.equalsIgnoreCase(autoSubmitted.trim(), "no")) {
      return true;
    }
    String precedence = firstHeader(message, HEADER_PRECEDENCE);
    return precedence != null && StringUtils.equalsAnyIgnoreCase(precedence.trim(), "bulk", "junk");
  }

  /**
   * Whether the message advertises an address you can post back to, i.e. it came from a
   * discussion list rather than a one-way blast. Marketing senders rarely set List-Post, so
   * together with List-Id this is what tells a colleague writing to a group apart from a
   * newsletter.
   *
   * @param message the freshly-fetched message
   * @return {@code true} when List-Post names a postable address
   */
  static boolean isPostableList(Message message) throws MessagingException {
    String listPost = firstHeader(message, HEADER_LIST_POST);
    return StringUtils.containsIgnoreCase(listPost, "mailto:");
  }


  private boolean canSynchronize(UserEmailSetting userEmailSetting, String username) {
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      return false;
    }
    if (SyncStatus.BLOCKED.equals(userEmailSetting.getEmailSyncStatus())) {
      // BLOCKED is a temporary backoff, not a permanent dead-end: after repeated failures
      // (e.g. transient IMAP/connection issues) allow one retry once a cooldown has elapsed,
      // so the user recovers automatically -- a subsequent successful sync clears BLOCKED.
      long retryAfter = userEmailSetting.getLastEmailSyncStartDate() + BLOCKED_RETRY_COOLDOWN_MS;
      return System.currentTimeMillis() > retryAfter;
    }
    if (SyncStatus.IN_PROGRESS.equals(userEmailSetting.getEmailSyncStatus())) {
      long nextAllowedSync = userEmailSetting.getLastEmailSyncStartDate() +
          EmailConnectorUtils.getEmailBoxUserSyncPeriod(userEmailSetting) * 60000L;
      return System.currentTimeMillis() > nextAllowedSync;
    }
    return true;
  }

  private void cleanupObsoleteEmails(UIDFolder uidFolder,
                                     List<Email> userEmails,
                                     Message[] serverMessages,
                                     String username,
                                     int emailBoxCacheSize) {
    Set<Long> serverMessagesUids = Arrays.stream(serverMessages).map(msg -> {
      try {
        return uidFolder.getUID(msg);
      } catch (MessagingException messagingException) {
        LOG.warn("Error when getting message uid", messagingException);
        return null;
      }
    }).collect(Collectors.toSet());
    List<Email> obsoleteEmails = userEmails.stream()
                                           .filter(email -> !serverMessagesUids.contains(email.getMailRemoteId()))
                                           .toList();
    if (!obsoleteEmails.isEmpty()) {
      deleteEmails(obsoleteEmails);
    }
    if (userEmails.size() > emailBoxCacheSize) {
      List<Email> oldUserEmailsToCleanup = userEmails.subList(emailBoxCacheSize, userEmails.size());
      deleteEmails(oldUserEmailsToCleanup);
    }
  }

  /**
   * Deletes cached rows and their category links. The links are resolved HERE, per
   * row being deleted, when the caller's rows do not already carry them — the light
   * sync view deliberately skips the per-row category lookup (it used to run once
   * per cached message per sync, 5000 lookups nobody read), so the only rows that
   * ever pay for one are the handful actually being removed.
   *
   * @param emails the rows to delete; {@code categoryIds} may be null (light sync
   *          view) or pre-resolved (full reads)
   */
  private void deleteEmails(List<Email> emails) {
    List<Long> emailsIdsToDelete = new ArrayList<Long>();
    for (Email email : emails) {
      emailsIdsToDelete.add(email.getId());
      List<Long> categoryIds = email.getCategoryIds() != null
                                                              ? email.getCategoryIds()
                                                              : categoryLinkService.getLinkedIds(new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE,
                                                                                                                    String.valueOf(email.getId()),
                                                                                                                    0));
      if (!CollectionUtils.isEmpty(categoryIds)) {
        categoryIds.stream().forEach(emailCategoryId -> {
          categoryLinkService.unlink(emailCategoryId,
                                     new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE, String.valueOf(email.getId()), 0));
        });
      }
    }
    emailBoxStorage.deleteEmailsByIds(emailsIdsToDelete);
  }

  private void updateEmailSyncStatus(String username, SyncStatus syncStatus) {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    int mailSyncFailedAttemps = userEmailSetting.getEmailSyncFailedAttemps();
    if (syncStatus == SyncStatus.SUCCESS) {
      mailSyncFailedAttemps = 0;
    }
    if (syncStatus == SyncStatus.FAILURE) {
      if (mailSyncFailedAttemps >= 2) {
        syncStatus = SyncStatus.BLOCKED;
      }
      mailSyncFailedAttemps++;
    }
    if (syncStatus == SyncStatus.IN_PROGRESS) {
      userEmailSetting.setLastEmailSyncStartDate(System.currentTimeMillis());
    }
    userEmailSetting.setEmailSyncFailedAttemps(mailSyncFailedAttemps);
    userEmailSetting.setEmailSyncStatus(syncStatus);
    userEmailSettingService.setUserEmailSetting(userEmailSetting, username, false);
  }

  /**
   * Opens the new-mail notification window for a sync that is about to cache messages.
   * <p>
   * The user can ask to be notified only for chosen categories, but the categories are applied
   * by a consumer of {@link EmailConnectorUtils#NEW_EMAILS_SYNCED} that takes minutes to run.
   * Notifying immediately means every message is still uncategorized, the filter's
   * "uncategorized always notifies" fallback applies to all of them, and the preference can
   * never suppress anything: the user picks one category and is notified about everything.
   * <p>
   * So the notification waits, and because the new-emails events now stream out one group at
   * a time while the download is still running, the wait is a counted window rather than a
   * single hand-off: every {@link #deferNewEmailsNotification(String)} adds a claim, every
   * {@link #notifyNewEmailsClassified(String)} releases one, and the notification fires only
   * once the window is complete ({@link #completeNotificationWindow}) AND the last claim is
   * gone. A single claim cannot be trusted to mean "done": the first group's classification
   * regularly finishes before the next group has even been broadcast, and firing on that
   * transient zero would notify about a mailbox that is still mostly uncategorized.
   * <p>
   * Must be called before the sync's first broadcast, so an early claim always finds the
   * window; no timer is armed here -- during the download there is nothing to time out,
   * every claim arms its own backstop, and the grace delay belongs to the window's end.
   * Package-visible for tests.
   *
   * @param username the mailbox owner
   * @param userEmails the inbox as it was before this sync, used to tell the new messages apart
   */
  void openNotificationWindow(String username, List<Email> userEmails) {
    long maxLocalUid = maxKnownUid(userEmails);
    pendingNotifications.compute(username, (user, pending) -> {
      if (pending == null) {
        return new PendingNotification(maxLocalUid, 0, false, notificationGenerations.incrementAndGet(), null);
      }
      // A leftover from a previous sync whose timer has not fired yet: absorb it, keeping
      // the earliest boundary so none of its messages is skipped, and let this sync's
      // window own the send. The new generation is what stops that leftover's backstop from
      // flushing this window if it was already running when cancel() came too late.
      cancelTimer(pending);
      return new PendingNotification(Math.min(pending.maxLocalUid(), maxLocalUid),
                                     pending.pendingClaims(),
                                     false,
                                     notificationGenerations.incrementAndGet(),
                                     null);
    });
  }

  /**
   * Marks the sync's notification window complete: every message is cached and every
   * {@link EmailConnectorUtils#NEW_EMAILS_SYNCED} group is broadcast, so no further claims
   * are expected (a still-in-flight consumer gets the short grace delay to place one, same
   * contract as before streaming). With no outstanding claim the grace timer now owns the
   * send; with claims still out, the send belongs to the last release, backstopped by the
   * extended deadline so a consumer that never reports back delays the notification rather
   * than losing it. Package-visible for tests.
   *
   * @param username the mailbox owner
   * @param userEmails the inbox as it was before this sync, used as the boundary fallback
   *          when the window was already flushed (e.g. a backstop fired mid-sync)
   */
  void completeNotificationWindow(String username, List<Email> userEmails) {
    long fallbackBoundary = maxKnownUid(userEmails);
    pendingNotifications.compute(username, (user, pending) -> {
      // The min covers the window a backstop flushed mid-sync: the entry a late claim
      // re-created has no usable boundary, and the pre-sync mailbox state does.
      long boundary = pending == null ? fallbackBoundary : Math.min(pending.maxLocalUid(), fallbackBoundary);
      int claims = pending == null ? 0 : pending.pendingClaims();
      cancelTimer(pending);
      long delayMs = claims > 0 ? NOTIFICATION_MAX_WAIT_MS : NOTIFICATION_GRACE_MS;
      long generation = notificationGenerations.incrementAndGet();
      return new PendingNotification(boundary, claims, true, generation, scheduleNotificationTask(user, delayMs, generation));
    });
  }

  /**
   * The highest IMAP UID among the given cached emails -- the boundary separating what the
   * user has already been notified about from what this sync brings in.
   *
   * @param userEmails the inbox rows cached before the sync
   * @return the highest known UID, or 0 for an empty mailbox
   */
  private long maxKnownUid(List<Email> userEmails) {
    return userEmails.stream()
                     .filter(email -> email.getMailRemoteId() != null)
                     .mapToLong(Email::getMailRemoteId)
                     .max()
                     .orElse(0L);
  }

  /**
   * Cancels a pending window's timer, if it has one -- a window opened mid-sync has none.
   *
   * @param pending the window whose timer to cancel, possibly {@code null}
   */
  private void cancelTimer(PendingNotification pending) {
    if (pending != null && pending.future() != null) {
      pending.future().cancel(false);
    }
  }

  /**
   * Schedules the deferred send and returns its handle. Whatever the window's state when
   * the delay elapses -- even with claims still out -- the task flushes it and sends: this
   * is the "late rather than lost" backstop, and a claim whose consumer died must never
   * strand the notification.
   *
   * @param username the mailbox owner
   * @param delayMs how long to wait before sending
   * @param generation the window this task is armed for; it flushes that window and no other
   * @return the scheduled task, so a later call can cancel or replace it
   */
  private ScheduledFuture<?> scheduleNotificationTask(String username, long delayMs, long generation) {
    return notificationScheduler.schedule(() -> {
      PendingNotification flushed = takePendingNotificationIfCurrent(username, generation);
      if (flushed == null) {
        return;
      }
      try {
        RequestLifeCycle.begin(PortalContainer.getInstance());
        try {
          sendNotification(username, flushed.maxLocalUid());
        } finally {
          RequestLifeCycle.end();
        }
      } catch (Exception e) {
        LOG.warn("Error sending the new-email notification for user {}", username, e);
      }
    }, delayMs, TimeUnit.MILLISECONDS);
  }

  /**
   * Removes and returns the pending window of {@code username}, but only if it is still the
   * one identified by {@code generation} -- the window the caller's backstop was armed for.
   * <p>
   * This is what stops a backstop from flushing a window it was never armed for.
   * {@code cancel(false)} does nothing once the task has started running, so a timer firing
   * at the instant a new sync installs a fresh window would otherwise remove that fresh entry
   * and send on it: an early notification, an in-flight window dropped, and no trace of
   * either -- the later release finds no entry and no-ops as an "orphaned claim".
   * <p>
   * Package-visible so the guard itself can be tested. The scheduler thread it normally runs
   * on needs a live {@link PortalContainer} to get as far as the send, which a unit test has
   * no way to provide, so asserting through the timer would prove nothing.
   *
   * @param username the mailbox owner
   * @param generation the window the caller is entitled to flush
   * @return the flushed window, or {@code null} when a newer one has superseded it
   */
  PendingNotification takePendingNotificationIfCurrent(String username, long generation) {
    PendingNotification[] flushed = new PendingNotification[1];
    pendingNotifications.compute(username, (user, pending) -> {
      if (pending == null || pending.generation() != generation) {
        return pending;
      }
      flushed[0] = pending;
      return null;
    });
    return flushed[0];
  }

  /**
   * Adds a claim on the pending new-mail notification, because the caller is about to
   * classify a group of the messages this sync cached and the user's per-category preference
   * cannot be applied until every group has been.
   * <p>
   * Claims are counted, one per {@link EmailConnectorUtils#NEW_EMAILS_SYNCED} group the
   * caller reacts to, so a consumer working group-by-group while the download continues
   * holds the notification exactly as long as it is still working. Never sends; each claim
   * is matched by a {@link #notifyNewEmailsClassified(String)}, and if one never comes, the
   * refreshed backstop deadline still fires so a notification is delayed rather than lost.
   *
   * @param username the mailbox owner
   */
  public void deferNewEmailsNotification(String username) {
    pendingNotifications.compute(username, (user, pending) -> {
      if (pending == null) {
        // A claim with no window to attach to -- the backstop already flushed it, or the
        // consumer reacted to an event this service did not broadcast. The unknown boundary
        // makes the eventual send a no-op, matching the old "nothing pending" behavior,
        // but the claim is still tracked so its release stays balanced.
        long orphanGeneration = notificationGenerations.incrementAndGet();
        return new PendingNotification(Long.MAX_VALUE,
                                       1,
                                       false,
                                       orphanGeneration,
                                       scheduleNotificationTask(user, NOTIFICATION_MAX_WAIT_MS, orphanGeneration));
      }
      cancelTimer(pending);
      long generation = notificationGenerations.incrementAndGet();
      return new PendingNotification(pending.maxLocalUid(),
                                     pending.pendingClaims() + 1,
                                     pending.syncCompleted(),
                                     generation,
                                     scheduleNotificationTask(user, NOTIFICATION_MAX_WAIT_MS, generation));
    });
  }

  /**
   * Releases one claim on the held-back notification. The send happens on the release that
   * empties a completed window -- claims gone AND the sync's last message cached. A zero
   * reached while the window is still open is deliberately NOT a send: it only means the
   * classifier caught up with the download for a moment, and firing there would notify
   * about the still-uncategorized rest of the mailbox. Does nothing when none is pending.
   *
   * @param username the mailbox owner
   */
  public void notifyNewEmailsClassified(String username) {
    PendingNotification[] readyToSend = new PendingNotification[1];
    pendingNotifications.compute(username, (user, pending) -> {
      if (pending == null) {
        return null;
      }
      int remainingClaims = Math.max(0, pending.pendingClaims() - 1);
      if (remainingClaims == 0 && pending.syncCompleted()) {
        cancelTimer(pending);
        readyToSend[0] = pending;
        return null;
      }
      // Re-arm rather than carry the old timer: the backstop must measure silence, not
      // total elapsed time. A large mailbox classifies for longer than any fixed deadline
      // -- 5000 messages take far longer than the 15 minutes that suited 500 -- and a
      // deadline that expires mid-run sends a notification counting only part of the mail.
      cancelTimer(pending);
      long generation = notificationGenerations.incrementAndGet();
      return new PendingNotification(pending.maxLocalUid(),
                                     remainingClaims,
                                     pending.syncCompleted(),
                                     generation,
                                     scheduleNotificationTask(user, NOTIFICATION_MAX_WAIT_MS, generation));
    });
    if (readyToSend[0] == null) {
      return;
    }
    try {
      // Outside the compute: the send reads the mailbox from the database, far too much
      // work to run while holding the map's bin lock.
      sendNotification(username, readyToSend[0].maxLocalUid());
    } catch (Exception e) {
      LOG.warn("Error sending the new-email notification for user {}", username, e);
    }
  }

  /**
   * A notification waiting to be sent: the UID boundary that separates the newly-cached
   * messages from the ones already there, how many classification claims still hold it
   * back, whether the sync that opened it has finished caching (a zero-claim window may
   * only fire once it has), and the timer that will flush it if nobody else does.
   *
   * @param maxLocalUid the highest UID present before the sync
   * @param pendingClaims outstanding {@link #deferNewEmailsNotification(String)} claims
   * @param syncCompleted whether the sync has cached its last message
   * @param generation this window's identity, so an already-running backstop can tell whether
   *          it is still the window it was armed for
   * @param future the scheduled backstop send, {@code null} while the window is open with
   *          no claim
   */
  private record PendingNotification(long maxLocalUid,
                                     int pendingClaims,
                                     boolean syncCompleted,
                                     long generation,
                                     ScheduledFuture<?> future) {
  }

  /**
   * Fires the new-emails notification for the messages synced into the INBOX, counting only
   * the ones that are new (IMAP UID beyond {@code maxLocalUid}, the highest one cached
   * before the sync), still unread, and allowed by the user's per-category notification
   * preference (see {@link #shouldNotifyForNewEmail(Email, UserEmailSetting)}). Category
   * links are keyed by the local email id, so the freshly-synced INBOX is re-read from the
   * local cache (its {@code categoryIds}) rather than inspected on the raw IMAP messages.
   *
   * @param userName the mailbox owner
   * @param maxLocalUid the highest UID cached before the sync -- what counts as "new"
   */
  private void sendNotification(String userName, long maxLocalUid) {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(userName);
    List<Email> currentEmails = emailBoxStorage.getEmails(userName, MailFolder.INBOX);
    long newUnreadCount = currentEmails.stream()
                                       .filter(email -> email.getMailRemoteId() != null && email.getMailRemoteId() > maxLocalUid)
                                       .filter(email -> !email.isRead())
                                       .filter(email -> shouldNotifyForNewEmail(email, userEmailSetting))
                                       .count();
    if (newUnreadCount > 0) {
      NotificationContext ctx = NotificationContextImpl.cloneInstance()
                                                       .append(NewEmailsNotificationPlugin.RECEIVER, userName)
                                                       .append(NewEmailsNotificationPlugin.NEW_EMAILS,
                                                               String.valueOf(newUnreadCount));
      ctx.getNotificationExecutor()
         .with(ctx.makeCommand(PluginKey.key(NotificationConstants.NEW_EMAILS_NOTIFICATION_PLUGIN)))
         .execute(ctx);
    }
  }

  /**
   * Decides whether a freshly-synced inbox email should trigger a new-mail notification,
   * according to the user's per-category notification preference. The rule is deliberately
   * conservative: it never silently drops a notification when category filtering cannot be
   * applied. A notification is SUPPRESSED only when all of the following hold — the user
   * asked to be notified for selected categories only ({@code notifyAllCategories == false}),
   * the email is linked to one or more categories, and none of them is among the user's
   * {@code notifyCategories}. In every other case the email notifies, including:
   * <ul>
   *   <li>{@code notifyAllCategories} is {@code null} or {@code true} — the default, notify for
   *       every new email;</li>
   *   <li>the email has no category link (uncategorized — which also covers the case where AI
   *       auto-categorization is disabled, so emails simply have no category links).</li>
   * </ul>
   *
   * @param email the freshly-synced inbox email; its {@code categoryIds} are the linked
   *          category ids
   * @param userEmailSetting the mailbox owner's settings (may be {@code null})
   * @return {@code true} to fire the notification, {@code false} to suppress it
   */
  boolean shouldNotifyForNewEmail(Email email, UserEmailSetting userEmailSetting) {
    // Default / "notify for everything": notifyAllCategories null or true.
    if (userEmailSetting == null || !Boolean.FALSE.equals(userEmailSetting.getNotifyAllCategories())) {
      return true;
    }
    // Fallback — never silently drop when we cannot filter by category: an uncategorized
    // email (also the AI-off case) always notifies.
    List<Long> emailCategoryIds = email.getCategoryIds();
    if (CollectionUtils.isEmpty(emailCategoryIds)) {
      return true;
    }
    // Category filtering is on and the email is categorized: notify only if at least one of
    // its categories is among the ones the user opted into.
    List<Long> notifyCategories = userEmailSetting.getNotifyCategories();
    return notifyCategories != null && emailCategoryIds.stream().anyMatch(notifyCategories::contains);
  }

  private BodyPart getPartByPath(Part root, String partNumber) throws Exception {
    String[] levels = partNumber.split("\\.");
    Part current = root;
    int levelIndex = 0;
    for (String level : levels) {
      if (!current.isMimeType("multipart/*")) {
        throw new IllegalStateException("Trying to go deeper but part is not multipart at level " + levelIndex);
      }
      Multipart multipart = (Multipart) current.getContent();
      int index = Integer.parseInt(level) - 1;
      if (index < 0 || index >= multipart.getCount()) {
        throw new IllegalArgumentException("Invalid attachment index " + level + " at level " + levelIndex);
      }
      current = multipart.getBodyPart(index);
      levelIndex++;
    }
    return (BodyPart) current;
  }

  private IMAPFolder findTrashFolder(Store store) throws MessagingException {
    for (Folder folder : store.getDefaultFolder().listSubscribed("*")) {
      if (!(folder instanceof IMAPFolder)) {
        continue;
      }
      IMAPFolder imapFolder = (IMAPFolder) folder;
      if (!imapFolder.exists()) {
        continue;
      }
      String[] attributes = imapFolder.getAttributes();
      for (String attr : attributes) {
        if (attr.equalsIgnoreCase("\\Trash")) {
          return imapFolder;
        }
      }
      String name = imapFolder.getFullName().toLowerCase();
      if (name.contains("trash") || name.contains("corbeille") || name.contains("deleted")) {
        return imapFolder;
      }
    }
    return null;
  }

  private IMAPFolder findArchiveFolder(Store store) throws MessagingException {
    for (Folder folder : store.getDefaultFolder().listSubscribed("*")) {
      if (!(folder instanceof IMAPFolder)) {
        continue;
      }
      IMAPFolder imapFolder = (IMAPFolder) folder;
      if (!imapFolder.exists()) {
        continue;
      }
      String[] attributes = imapFolder.getAttributes();
      for (String attr : attributes) {
        if (attr.equalsIgnoreCase("\\Archive") || attr.equalsIgnoreCase("\\All")) {
          return imapFolder;
        }
      }
      String name = imapFolder.getFullName().toLowerCase();
      if (name.contains("archive") || name.contains("archivage") || name.contains("all") || name.contains("tous")) {
        return imapFolder;
      }
    }
    return null;
  }

  /**
   * The folder to <em>synchronize</em> as ARCHIVE: a dedicated {@code \Archive}
   * folder only. Gmail's "All Mail" ({@code \All}) is deliberately excluded — it
   * is a superset of the inbox, so caching it would duplicate every received
   * message inside its conversation. (The archive <em>destination</em> still uses
   * {@link #findArchiveFolder} so archiving keeps working on Gmail.)
   */
  private IMAPFolder findSyncableArchiveFolder(Store store) throws MessagingException {
    for (Folder folder : store.getDefaultFolder().listSubscribed("*")) {
      if (!(folder instanceof IMAPFolder)) {
        continue;
      }
      IMAPFolder imapFolder = (IMAPFolder) folder;
      if (!imapFolder.exists()) {
        continue;
      }
      for (String attr : imapFolder.getAttributes()) {
        if (attr.equalsIgnoreCase("\\Archive")) {
          return imapFolder;
        }
      }
      String name = imapFolder.getFullName().toLowerCase();
      if (name.equals("archive") || name.equals("archives") || name.equals("archivage")) {
        return imapFolder;
      }
    }
    return null;
  }

  private IMAPFolder findSentFolder(Store store) throws MessagingException {
    for (Folder folder : store.getDefaultFolder().listSubscribed("*")) {
      if (!(folder instanceof IMAPFolder)) {
        continue;
      }
      IMAPFolder imapFolder = (IMAPFolder) folder;
      if (!imapFolder.exists()) {
        continue;
      }
      String[] attributes = imapFolder.getAttributes();
      for (String attr : attributes) {
        if (attr.equalsIgnoreCase("\\Sent")) {
          return imapFolder;
        }
      }
      String name = imapFolder.getFullName().toLowerCase();
      if (name.contains("sent") || name.contains("envoyé") || name.contains("envoye")) {
        return imapFolder;
      }
    }
    return null;
  }

  private void copyToSentFolder(Message message, String username, UserEmailSetting userEmailSetting) {
    Store store = null;
    IMAPFolder sentFolder = null;
    try {
      store = (IMAPStore) userEmailSettingService.connect(userEmailSetting);
      sentFolder = findSentFolder(store);
      if (sentFolder != null) {
        sentFolder.open(Folder.READ_WRITE);
        sentFolder.appendMessages(new Message[] { message });
      } else {
        LOG.warn("No Sent folder found via SPECIAL-USE or fallback names for user {}", username);
      }
    } catch (Exception e) {
      LOG.error("Error when connecting store for user {}", username, e);
      throw new IllegalStateException(String.format("Error when connecting store for user %s", username));
    } finally {
      try {
        if (sentFolder != null && sentFolder.isOpen()) {
          sentFolder.close(false);
        }
      } catch (MessagingException messagingException) {
        LOG.warn("Error when closing sent folder", messagingException);
      }
      try {
        if (store != null && store.isConnected()) {
          store.close();
        }
      } catch (MessagingException messagingException) {
        LOG.warn("Error when closing store", messagingException);
      }
    }
  }
}
