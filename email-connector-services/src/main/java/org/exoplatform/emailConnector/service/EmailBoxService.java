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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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
import javax.mail.search.MessageIDTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.SearchTerm;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import org.exoplatform.commons.ObjectAlreadyExistsException;
import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.emailConnector.job.EmailBoxSyncJob;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.MailFolder;
import org.exoplatform.emailConnector.model.EmailCategory;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailOutgoingAttachment;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailConnector;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
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
import jakarta.annotation.PostConstruct;

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

  // Every header createEmails reads per message. They must be fetched in the one batched
  // FETCH: JavaMail otherwise goes back to the server for each header of each message.
  private static final List<String> PREFETCHED_HEADERS                                        =
                                                                                              List.of("References",
                                                                                                      "In-Reply-To",
                                                                                                      "Thread-Index",
                                                                                                      "Auto-Submitted",
                                                                                                      "Precedence",
                                                                                                      "List-Id",
                                                                                                      "List-Post",
                                                                                                      "List-Unsubscribe",
                                                                                                      "X-Original-Sender");

  // How long a new-mail notification waits for someone to classify the messages first. Short,
  // because with no such consumer this is pure added latency.
  private static final long       NOTIFICATION_GRACE_MS                                       = 10000L;

  // The outside limit once a consumer has claimed the wait: if it never reports back, the
  // notification is late rather than lost.
  private static final long       NOTIFICATION_MAX_WAIT_MS                                    = 15 * 60 * 1000L;

  // Cooldown before a BLOCKED mailbox is allowed to retry a sync, so BLOCKED is a temporary
  // backoff rather than a permanent dead-end (a successful retry clears it).
  private static final long       BLOCKED_RETRY_COOLDOWN_MS                                   = 30 * 60 * 1000L;

  // Caps the OR-of-Message-ID search when completing a thread from the archive on
  // open, so an unusually long conversation can't build a giant IMAP SEARCH.
  private static final int        ARCHIVE_COMPLETION_SEARCH_LIMIT                             = 50;

  // Concurrent IMAP connections used to prefetch new message bodies during a sync.
  // Bodies are the one per-message cost the batched FETCH profile cannot absorb: each
  // body is its own FETCH BODY[n] round-trip (several for nested multiparts, plus a
  // full download per inline cid: image), all serialized on the single sync connection
  // — on a 500-message reset that latency IS the sync time. The default stays modest
  // because Gmail allows ~15 simultaneous IMAP connections per account and that budget
  // is shared with the user's other mail clients.
  private static final String     BODY_PREFETCH_WORKERS_PROPERTY                              =
                                                                 "email.connector.sync.body.fetch.threads";

  private static final int        DEFAULT_BODY_PREFETCH_WORKERS                               = 5;

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
  private static final long       BODY_PREFETCH_TIMEOUT_MS                                    = 10 * 60 * 1000L;

  // The nameIds of the add-on's own default email categories (see default-categories.json).
  // The platform's CategoryImportService persists each nameId -> created category id in
  // SettingService, so the assignable email category ids are resolved from there.
  private static final List<String> DEFAULT_EMAIL_CATEGORY_NAME_IDS                          =
                                                                   List.of("emailImportantCategory",
                                                                           "emailInvitationCategory",
                                                                           "emailNotificationCategory");

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

  // Mailboxes with a synchronization running right now, so two can never overlap and cache
  // the same message twice.
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
    Store store = null;
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      updateEmailSyncStatus(username, SyncStatus.IN_PROGRESS);
      int emailBoxCacheSize = emailConnectorService.getEmailBoxCacheSize();
      // INBOX drives the new-mail notifications; Sent and Archive are cached (best
      // effort — a missing folder must not fail the sync) so a conversation shows the
      // user's own replies ("Me") and previously-archived messages inline.
      syncFolder(store.getFolder("INBOX"), MailFolder.INBOX, username, userEmailSetting, emailBoxCacheSize, true);
      if (!inboxOnly) {
        int nonInboxWindow = Math.min(emailBoxCacheSize, NON_INBOX_FOLDER_SYNC_LIMIT);
        try {
          syncFolder(findSentFolder(store), MailFolder.SENT, username, userEmailSetting, nonInboxWindow, false);
        } catch (Exception e) {
          LOG.warn("Could not sync the Sent folder for user {}", username, e);
        }
        try {
          syncFolder(findSyncableArchiveFolder(store), MailFolder.ARCHIVE, username, userEmailSetting, nonInboxWindow, false);
        } catch (Exception e) {
          LOG.warn("Could not sync the Archive folder for user {}", username, e);
        }
      }
      updateEmailSyncStatus(username, SyncStatus.SUCCESS);
    } catch (Exception e) {
      updateEmailSyncStatus(username, SyncStatus.FAILURE);
      LOG.error("Error when user {} synchronization ", username, e);
    } finally {
      syncingUsers.remove(username);
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
    synchronize(username, true);
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
   */
  private void syncFolder(Folder folder,
                          String folderKey,
                          String username,
                          UserEmailSetting userEmailSetting,
                          int emailBoxCacheSize,
                          boolean notify) throws MessagingException, IllegalAccessException {
    if (folder == null) {
      return;
    }
    try {
      folder.open(Folder.READ_ONLY);
      UIDFolder uidFolder = (UIDFolder) folder;
      int totalMessages = folder.getMessageCount();
      if (totalMessages == 0) {
        return;
      }
      int startIndex = Math.max(1, totalMessages - emailBoxCacheSize + 1);
      Message[] serverMessages = folder.getMessages(startIndex, totalMessages);
      // Prefetch flags + envelope + UID + headers + MIME structure in a single
      // round-trip (see buildSyncFetchProfile for why every piece is in there).
      folder.fetch(serverMessages, buildSyncFetchProfile());
      List<Email> folderEmails = emailBoxStorage.getEmails(username, folderKey);
      if (notify) {
        // Open the notification window BEFORE anything is broadcast: the groups of new
        // messages stream out below while the download is still running, and a consumer's
        // hold-back claim must always find the window to attach to.
        openNotificationWindow(username, folderEmails);
      }
      // Bodies are the one per-message cost the batched FETCH above cannot absorb, so
      // fetch them for the new messages over several extra IMAP connections, caching each
      // slice as it lands. Best-effort: a miss just falls back to the serial fetch.
      List<Long> newEmailIds =
                             prefetchAndCreateEmails(folder,
                                                     uidFolder,
                                                     serverMessages,
                                                     folderEmails,
                                                     username,
                                                     folderKey,
                                                     userEmailSetting,
                                                     notify);
      LOG.info("Synchronized folder {} of user {}: {} message(s) on the server, {} newly cached, {} already known",
               folderKey,
               username,
               serverMessages.length,
               newEmailIds.size(),
               serverMessages.length - newEmailIds.size());
      cleanupObsoleteEmails(uidFolder, folderEmails, serverMessages, username, emailBoxCacheSize);
      if (notify) {
        // The per-group NEW_EMAILS_SYNCED broadcasts already went out above, inside
        // prefetchAndCreateEmails, while the download was still running. What remains
        // here is the end of the run: close the notification window (arming the grace
        // delay when no consumer claimed it), then tell consumers no more groups are
        // coming -- whole-run work like conversation alignment can only start now.
        completeNotificationWindow(username, folderEmails);
        broadcastNewEmailsSyncCompleted(username, newEmailIds);
      }
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
   * The fetch profile every sync-side message read relies on. Flags + envelope + UID
   * come back in one round-trip; without this, isSet(SEEN)/getFrom/getSubject/... each
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
   * method. And the slices are consumed in message order rather than completion order,
   * because {@link #computeThreadId} attaches a reply to its conversation by looking up
   * the messages already stored; cache a reply before its parent and the conversation
   * silently splits in two.
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
   * @param username the mailbox owner
   * @param folderKey the {@link MailFolder} discriminator to stamp
   * @param userEmailSetting the user's connector binding, to open the extra connections
   * @param streamNewEmails whether to broadcast the newly-cached UIDs as they land
   *          (INBOX only -- Sent and Archive are never broadcast)
   * @return the IMAP UIDs of the messages this sync newly cached
   * @throws MessagingException if the folder cannot be read
   * @throws IllegalAccessException if the user is not allowed to cache these messages
   */
  private List<Long> prefetchAndCreateEmails(Folder folder,
                                             UIDFolder uidFolder,
                                             Message[] serverMessages,
                                             List<Email> folderEmails,
                                             String username,
                                             String folderKey,
                                             UserEmailSetting userEmailSetting,
                                             boolean streamNewEmails) throws MessagingException,
                                                                      IllegalAccessException {
    List<Long> newUids = collectNewUids(uidFolder, serverMessages, folderEmails);
    int workerCount = getBodyPrefetchWorkerCount();
    if (workerCount <= 1 || newUids.size() < BODY_PREFETCH_MIN_MESSAGES) {
      // Too small to be worth extra connections: one pass, bodies fetched serially.
      return createEmailsAndBroadcast(uidFolder, serverMessages, username, folderKey, streamNewEmails);
    }
    EmailConnector emailConnector;
    try {
      // Resolved once, on this thread: the workers must stay pure IMAP, and the one-argument
      // connect() would re-read the connector from the database on every bare worker thread.
      emailConnector = emailConnectorService.getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId()));
    } catch (Exception e) {
      LOG.warn("Could not resolve the connector of user {}; the sync falls back to fetching bodies serially", username, e);
      return createEmailsAndBroadcast(uidFolder, serverMessages, username, folderKey, streamNewEmails);
    }
    Map<Long, Message> messagesByUid = new HashMap<>();
    for (Message message : serverMessages) {
      messagesByUid.put(uidFolder.getUID(message), message);
    }
    int sliceCount = (int) Math.ceil((double) newUids.size() / BODY_PREFETCH_SLICE_SIZE);
    List<long[]> uidSlices = partitionUids(newUids, sliceCount);
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
      List<Future<Map<Long, EmailContent>>> pendingSlices = new ArrayList<>();
      for (long[] uidSlice : uidSlices) {
        pendingSlices.add(prefetchPool.submit(() -> prefetchSlice(folderFullName,
                                                                  uidSlice,
                                                                  userEmailSetting,
                                                                  emailConnector,
                                                                  username)));
      }
      // One deadline for the whole folder, so a wedged connection cannot hold the sync
      // open indefinitely -- whatever is late is simply fetched serially instead.
      long deadline = System.currentTimeMillis() + BODY_PREFETCH_TIMEOUT_MS;
      // The UIDs cached since the last broadcast. Replaced (never cleared) after each
      // broadcast: the list is handed to asynchronous listeners, which read it on their
      // own threads after this loop has moved on.
      List<Long> broadcastGroup = new ArrayList<>();
      for (int sliceIndex = 0; sliceIndex < uidSlices.size(); sliceIndex++) {
        Map<Long, EmailContent> sliceContents = awaitSlice(pendingSlices.get(sliceIndex), deadline, username);
        prefetchedCount += sliceContents.size();
        List<Long> sliceEmailIds = createEmails(uidFolder,
                                                messagesOfSlice(uidSlices.get(sliceIndex), messagesByUid),
                                                username,
                                                folderKey,
                                                sliceContents);
        newEmailIds.addAll(sliceEmailIds);
        if (streamNewEmails) {
          broadcastGroup.addAll(sliceEmailIds);
          boolean groupComplete = (sliceIndex + 1) % BODY_PREFETCH_SLICES_PER_BROADCAST == 0
              || sliceIndex == uidSlices.size() - 1;
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
   * @param streamNewEmails whether the newly-cached UIDs are broadcast (INBOX only)
   * @return the IMAP UIDs of the messages this sync newly cached
   * @throws MessagingException if the folder cannot be read
   * @throws IllegalAccessException if the user is not allowed to cache these messages
   */
  private List<Long> createEmailsAndBroadcast(UIDFolder uidFolder,
                                              Message[] serverMessages,
                                              String username,
                                              String folderKey,
                                              boolean streamNewEmails) throws MessagingException, IllegalAccessException {
    List<Long> newEmailIds = createEmails(uidFolder, serverMessages, username, folderKey, Map.of());
    if (streamNewEmails) {
      broadcastNewEmailsSynced(username, newEmailIds);
    }
    return newEmailIds;
  }

  /**
   * Waits for one slice's bodies, degrading to an empty slice rather than failing the sync.
   *
   * @param pendingSlice the worker's pending result
   * @param deadline the epoch-millis bound shared by every slice of this folder
   * @param username the mailbox owner, for logging
   * @return the slice's bodies keyed by IMAP UID, empty if it failed or ran out of time
   */
  private Map<Long, EmailContent> awaitSlice(Future<Map<Long, EmailContent>> pendingSlice, long deadline, String username) {
    try {
      return pendingSlice.get(Math.max(1, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Body prefetch interrupted for user {}; the remaining bodies are fetched serially", username, e);
    } catch (Exception e) {
      LOG.warn("A body prefetch slice failed for user {}; its messages are fetched serially", username, e);
    }
    return Map.of();
  }

  /**
   * Lists the not-yet-cached messages of the folder window, in the order the server
   * listed them -- which is the order they must be cached in, oldest first.
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
   * @param contents the shared map to fill, keyed by IMAP UID
   */
  private Map<Long, EmailContent> prefetchSlice(String folderFullName,
                                                long[] uids,
                                                UserEmailSetting userEmailSetting,
                                                EmailConnector emailConnector,
                                                String username) {
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
          contents.put(messageUid, EmailConnectorUtils.getMessageContent(messageUid, message));
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
    List<Email> emails = emailBoxStorage.getEmails(username, folder);
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
      inbox = store.getFolder("INBOX");
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
          inbox = store.getFolder("INBOX");
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
        inbox = (IMAPFolder) store.getFolder("INBOX");
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
        inbox = (IMAPFolder) store.getFolder("INBOX");
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
   * Notification — resolved to their localized name. These are the leaf
   * categories seeded from the add-on's {@code default-categories.json}, returned
   * whether or not they are already in use, so the picker always offers the full set.
   *
   * @param username the mailbox owner
   * @param locale the locale to resolve category names in
   * @return the assignable email categories, in their defined order
   */
  public List<EmailCategory> getAvailableEmailCategories(String username, Locale locale) {
    List<EmailCategory> categories = new ArrayList<>();
    for (Long categoryId : getDefaultEmailCategoryIds()) {
      try {
        CategoryWithName category = categoryService.getCategory(categoryId, username, locale);
        if (category != null) {
          categories.add(new EmailCategory(categoryId, category.getName()));
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
      SettingValue<?> settingValue = settingService.get(CATEGORY_IMPORT_CONTEXT, CATEGORY_IMPORT_SCOPE, nameId);
      if (settingValue != null && settingValue.getValue() != null) {
        try {
          ids.add(Long.parseLong(settingValue.getValue().toString()));
        } catch (NumberFormatException e) {
          LOG.debug("Invalid category id stored for {}", nameId);
        }
      }
    }
    return ids;
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
        message.setHeader("In-Reply-To", parentMessageId);
        // RFC 5322 §3.6.4: References is the parent's own References plus the parent's
        // Message-ID — not just the parent id, otherwise a third message in the chain
        // loses the link to the first and starts a new thread.
        String parentReferences = emailBoxStorage.getMailReferencesByMailHeaderId(parentMessageId, username);
        String referencesHeader = EmailThreadingUtils.buildReferencesHeader(parentReferences, parentMessageId);
        if (!StringUtils.isEmpty(referencesHeader)) {
          message.setHeader("References", referencesHeader);
        }
      }
      Transport.send(message);
      String emailType = StringUtils.isEmpty(email.getMailHeaderId()) ? "newEmail" : "reply";
      listenerService.broadcast(EmailConnectorUtils.SEND_EMAIL, username, emailType);
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
   * Creates a local row for every server message not yet cached, and refreshes the
   * read flag / threading backfill of the ones already there. Deliberately serial:
   * computeThreadId reads the threads of the messages inserted before it and merges
   * threads as it goes, so processing order IS the threading contract — running these
   * iterations concurrently would race the merges and silently re-fragment
   * conversations. The expensive part, the per-message body fetch, is instead served
   * from {@code prefetchedContents} when the parallel prefetch got there first; a miss
   * falls back to the same serial fetch as always, so the map can be empty (or
   * partial, or entirely wrong about what exists) without affecting correctness.
   *
   * @param uidFolder the open remote folder, to resolve each message's UID
   * @param serverMessages the folder window to reconcile, in mailbox order
   * @param username the mailbox owner
   * @param folderKey the {@link MailFolder} discriminator to stamp on new rows
   * @param prefetchedContents bodies already fetched in parallel, keyed by IMAP UID;
   *          consulted before falling back to a per-message FETCH
   * @return the IMAP UIDs of the messages newly cached by this pass
   */
  private List<Long> createEmails(UIDFolder uidFolder,
                            Message[] serverMessages,
                            String username,
                            String folderKey,
                            Map<Long, EmailContent> prefetchedContents) throws MessagingException, IllegalAccessException {
    List<Long> newEmailIds = new ArrayList<>();
    for (Message message : serverMessages) {
      try {
        long messageUid = uidFolder.getUID(message);
        Email email = emailBoxStorage.getEmailByMailRemoteIdAndUserId(messageUid, username, null, folderKey, false, false, false);
        if (email == null) {
          EmailContent emailContent = prefetchedContents.get(messageUid);
          if (emailContent == null) {
            emailContent = EmailConnectorUtils.getMessageContent(messageUid, message);
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
          String inReplyTo = firstHeader(message, "In-Reply-To");
          String references = firstHeader(message, "References");
          String threadIndexRoot = EmailThreadingUtils.extractThreadIndexRoot(firstHeader(message, "Thread-Index"));
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
                                                firstHeader(message, "List-Id") != null,
                                                isPostableList(message),
                                                firstHeader(message, "List-Unsubscribe") != null,
                                                firstHeader(message, "X-Original-Sender")));
          newEmailIds.add(messageUid);

        } else {
          emailBoxStorage.updateEmailReadStatusByMailRemoteIds(List.of(messageUid),
                                                               username,
                                                               message.isSet(Flags.Flag.SEEN),
                                                               folderKey);
          emailBoxStorage.markEmailAsNotRecent(messageUid, username, folderKey);
          // Backfill threading on rows cached before these features. (a) A row with no
          // thread id yet gets one. (b) An already-threaded row gets its Thread-Index
          // root captured once and any threads sharing that root MERGED (merge-only,
          // never split) — this is what re-threads conversations cached before the
          // Thread-Index layer existed. The root is stored (empty string when the
          // message carries no Thread-Index) so each row is backfilled at most once.
          if (StringUtils.isEmpty(email.getThreadId())) {
            String inReplyTo = firstHeader(message, "In-Reply-To");
            String references = firstHeader(message, "References");
            String threadIndexRoot = EmailThreadingUtils.extractThreadIndexRoot(firstHeader(message, "Thread-Index"));
            String threadId = computeThreadId(username, ((MimeMessage) message).getMessageID(), messageUid, inReplyTo, references, threadIndexRoot);
            emailBoxStorage.updateThreadInfo(username, messageUid, threadId, inReplyTo, references, folderKey,
                                             threadIndexRoot != null ? threadIndexRoot : "");
          } else if (email.getThreadIndexRoot() == null) {
            String threadIndexRoot = EmailThreadingUtils.extractThreadIndexRoot(firstHeader(message, "Thread-Index"));
            if (threadIndexRoot != null) {
              mergeThreadsSharingRoot(username, email.getThreadId(), threadIndexRoot);
            }
            emailBoxStorage.updateThreadIndexRoot(username, messageUid, folderKey, threadIndexRoot != null ? threadIndexRoot : "");
          }
        }
      } catch (Exception e) {
        LOG.warn("Error when storing email with subject {} for user {}", message.getSubject(), username, e);
      }
    }
    return newEmailIds;
  }

  /**
   * The conversation a message belongs to. It joins an existing thread when its
   * References / In-Reply-To point at a cached message, otherwise it starts its own
   * thread keyed by its Message-ID (synthesized when the sender omitted one). A message
   * that references several distinct threads (a late, out-of-order arrival) collapses
   * them into the oldest — the canonical thread id — so the conversation stays whole.
   *
   * @param username the mailbox owner
   * @param mailHeaderId the message's own Message-ID, may be null
   * @param messageUid the message's IMAP UID, used to synthesize an id when needed
   * @param inReplyTo the raw In-Reply-To header, may be null
   * @param references the raw References header, may be null
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
      // A thread tail is a handful of messages, so no parallel body prefetch: pass an
      // empty map and let each body be fetched on this connection.
      createEmails(allMail, toCache.toArray(new Message[0]), username, MailFolder.ALL_MAIL, Map.of());
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
  private static boolean isAutoSubmitted(Message message) throws MessagingException {
    String autoSubmitted = firstHeader(message, "Auto-Submitted");
    if (StringUtils.isNotBlank(autoSubmitted) && !StringUtils.equalsIgnoreCase(autoSubmitted.trim(), "no")) {
      return true;
    }
    String precedence = firstHeader(message, "Precedence");
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
  private static boolean isPostableList(Message message) throws MessagingException {
    String listPost = firstHeader(message, "List-Post");
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

  private void deleteEmails(List<Email> emails) {
    List<Long> emailsIdsToDelete = new ArrayList<Long>();
    for (Email email : emails) {
      emailsIdsToDelete.add(email.getId());
      if (!CollectionUtils.isEmpty(email.getCategoryIds())) {
        email.getCategoryIds().stream().forEach(emailCategoryId -> {
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
        return new PendingNotification(maxLocalUid, 0, false, null);
      }
      // A leftover from a previous sync whose timer has not fired yet: absorb it, keeping
      // the earliest boundary so none of its messages is skipped, and let this sync's
      // window own the send.
      cancelTimer(pending);
      return new PendingNotification(Math.min(pending.maxLocalUid(), maxLocalUid), pending.pendingClaims(), false, null);
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
      return new PendingNotification(boundary, claims, true, scheduleNotificationTask(user, delayMs));
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
   * @return the scheduled task, so a later call can cancel or replace it
   */
  private ScheduledFuture<?> scheduleNotificationTask(String username, long delayMs) {
    return notificationScheduler.schedule(() -> {
      PendingNotification pending = pendingNotifications.remove(username);
      if (pending != null) {
        try {
          RequestLifeCycle.begin(PortalContainer.getInstance());
          try {
            sendNotification(username, pending.maxLocalUid());
          } finally {
            RequestLifeCycle.end();
          }
        } catch (Exception e) {
          LOG.warn("Error sending the new-email notification for user {}", username, e);
        }
      }
    }, delayMs, TimeUnit.MILLISECONDS);
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
        return new PendingNotification(Long.MAX_VALUE, 1, false, scheduleNotificationTask(user, NOTIFICATION_MAX_WAIT_MS));
      }
      cancelTimer(pending);
      return new PendingNotification(pending.maxLocalUid(),
                                     pending.pendingClaims() + 1,
                                     pending.syncCompleted(),
                                     scheduleNotificationTask(user, NOTIFICATION_MAX_WAIT_MS));
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
      return new PendingNotification(pending.maxLocalUid(), remainingClaims, pending.syncCompleted(), pending.future());
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
   * @param future the scheduled backstop send, {@code null} while the window is open with
   *          no claim
   */
  private record PendingNotification(long maxLocalUid, int pendingClaims, boolean syncCompleted, ScheduledFuture<?> future) {
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
