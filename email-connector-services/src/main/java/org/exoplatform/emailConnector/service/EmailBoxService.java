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
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import javax.activation.DataHandler;
import javax.activation.DataSource;
import javax.activation.FileDataSource;
import javax.mail.Address;
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
import javax.mail.search.HeaderTerm;
import javax.mail.search.MessageIDTerm;
import javax.mail.search.OrTerm;
import javax.mail.search.ReceivedDateTerm;
import javax.mail.search.RecipientStringTerm;
import javax.mail.search.SearchTerm;
import javax.mail.search.SubjectTerm;

import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.sun.mail.imap.AppendUID;
import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;
import com.sun.mail.imap.ResyncData;

import org.exoplatform.commons.ObjectAlreadyExistsException;
import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.SettingValue;
import org.exoplatform.commons.file.model.FileItem;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.container.component.RequestLifeCycle;
import org.exoplatform.container.PortalContainer;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.emailConnector.event.EmailSentEvent;
import org.exoplatform.emailConnector.event.MailboxResetEvent;
import org.exoplatform.emailConnector.job.EmailBoxSyncJob;
import org.exoplatform.emailConnector.model.DraftState;
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
import org.exoplatform.emailConnector.model.ForwardedAttachments;
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

  // The RFC 6154 SPECIAL-USE attribute that names a mailbox's Drafts folder. The
  // server saying so beats any name we could guess, which is why it is tried first.
  private static final String     DRAFTS_SPECIAL_USE_ATTRIBUTE                                = "\\Drafts";

  // The well-known Drafts folder names, for the servers that never learned
  // SPECIAL-USE, in the locales the product ships plus the few its users' other
  // clients create. Matched on the folder's last path segment, for equality — see
  // findDraftsFolder for why this list is not applied as a "contains".
  private static final Set<String> DRAFTS_FOLDER_NAMES                                        =
                                                                                              Set.of("drafts",
                                                                                                     "draft",
                                                                                                     "brouillons",
                                                                                                     "brouillon",
                                                                                                     "entwürfe",
                                                                                                     "entwuerfe",
                                                                                                     "bozze",
                                                                                                     "borradores",
                                                                                                     "rascunhos",
                                                                                                     "concepten",
                                                                                                     "utkast",
                                                                                                     "kladde",
                                                                                                     "luonnokset");

  // How many stray Drafts copies of already-sent mail one sync may remove from the
  // mail server. A bound, not a policy: the janitor exists for the occasional copy a
  // failed send-cleanup left behind, so a run finding dozens is describing something
  // nobody has diagnosed yet — and deleting from a user's mailbox at scale is not how
  // that should be discovered. The rest are simply identified again next sync.
  private static final int        STRAY_DRAFT_REMOVAL_LIMIT                                   = 10;

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
  // data. What this bounds is total silence among connections that are ALREADY fetching:
  // nothing arriving for this long from a fleet that has reached the folder means the
  // connections stalled or died mid-fetch, and the remaining bodies are fetched serially,
  // which costs a fraction of a second each.
  // This window used to start when the slices were submitted, and that was wrong: a
  // worker pays a TLS handshake, an IMAP login and a folder SELECT before its first
  // FETCH, so a provider merely slow to AUTHENTICATE was judged as a fleet that had
  // stopped FETCHING. On 12 Aug a Gmail login blew past the 30 s read timeout and, three
  // minutes later, the same sync abandoned its healthy prefetch reporting dead
  // connections -- one cause, two symptoms, and the log named the wrong one. The window
  // now starts when the first worker reports itself connected and SELECTed (see
  // BodyPrefetchFleet), so a fleet still logging in is never mistaken for a silent one.
  private static final long       BODY_PREFETCH_SLICE_TIMEOUT_MS                              = 90 * 1000L;

  // The outer bound on "not one worker has managed to log in yet". Silence cannot be
  // judged before the fleet is fetching, but the wait must still end: a provider that
  // refuses or black-holes logins would otherwise keep the sync thread parked until the
  // folder deadline. Three minutes is derived from the worst plausible login chain
  // against the timeouts UserEmailSettingService#connect sets -- a 15 s connect plus the
  // greeting, CAPABILITY, AUTHENTICATE and SELECT round-trips, each allowed to stall to
  // just under the 30 s read timeout (~135 s) -- plus margin. A merely slow provider
  // lands well inside it; a worker still not fetching after it is being refused, and
  // abandoning promptly is then the right call, because the serial fallback reuses the
  // sync connection that is ALREADY authenticated.
  private static final long       BODY_PREFETCH_CONNECT_TIMEOUT_MS                            = 3 * 60 * 1000L;

  // How often the drain resurfaces while the fleet is still logging in, so it notices the
  // first worker reaching the folder instead of staying parked on the connect bound. Only
  // paid before the fleet fetches: once it does, the wait is a single blocking poll again.
  private static final long       BODY_PREFETCH_CONNECT_POLL_MS                               = 5 * 1000L;

  // "No worker has reported itself fetching yet" -- kept out of band rather than using 0,
  // which is a legal (if absurd) epoch-millis value. Package-visible so the tests can
  // state that case by name instead of hard-coding the sentinel.
  static final long               BODY_PREFETCH_NOT_FETCHING                                  = -1L;

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

  private static final String     USER_NOT_ALLOWED_FOR_SEARCH_EMAIL_MESSAGE                   =
                                                                            "User %s is not allowed to search email";

  private static final String     USER_NOT_ALLOWED_FOR_UPDATE_EMAIL_MESSAGE                   =
                                                                            "User %s is not allowed to update email";

  private static final String     USER_NOT_ALLOWED_FOR_DELETE_EMAIL_MESSAGE                   =
                                                                            "User %s is not allowed to delete email";

  private static final String     USER_NOT_ALLOWED_FOR_ARCHIVE_EMAIL_MESSAGE                  =
                                                                             "User %s is not allowed to archive email";

  /**
   * The administrator's kill switch for the server-side half of drafts, in the
   * style of {@code email.connector.contacts.publish.enabled}: a JVM property read
   * on every call, so flipping it needs no restart. Default ON. Turning it off
   * leaves drafts working entirely — they are still saved, listed, resumed and sent
   * — and only stops them being uploaded to the mail server, which is the half that
   * writes into a store shared with the user's other clients. That is the half an
   * administrator might want to withdraw in a hurry, and the reason the switch
   * exists at all: an APPEND loop against a misbehaving server is the one failure
   * mode here that is visible outside this application.
   */
  public static final String      DRAFTS_SERVER_ENABLED_PROPERTY                              =
                                                                                              "email.connector.drafts.server.enabled";

  private static final String     USER_NOT_ALLOWED_FOR_SAVE_DRAFT_MESSAGE                     =
                                                                                              "User %s is not allowed to save a draft";

  private static final String     USER_NOT_ALLOWED_FOR_SEND_EMAIL_MESSAGE                     =
                                                                          "User %s is not allowed to send email";

  // Maximum cumulative size (bytes) allowed for the attachments of a single outgoing email (SMTP-friendly, 25 MB).
  private static final long       MAX_OUTGOING_ATTACHMENTS_SIZE                               = 25L * 1024 * 1024;

  // What an attachment is called and what it is declared as when the message it came
  // from says neither. Both are load-bearing rather than cosmetic: a row with a blank
  // name is not written at all (the entity mapper answers null for one), and a part
  // with a blank content type reaches the data handler, which is a difference the
  // recipient sees.
  private static final String     DEFAULT_ATTACHMENT_NAME                                     = "attachment";

  private static final String     DEFAULT_ATTACHMENT_MIME_TYPE                                = "application/octet-stream";

  @Autowired
  private CategoryLinkService     categoryLinkService;

  @Autowired
  private EmailFavoriteService    emailFavoriteService;

  // Mailboxes with a synchronization running right now, so two can never overlap and cache
  // the same message twice.
  private final Set<String>                      syncingUsers          = ConcurrentHashMap.newKeySet();

  // One lock per draft, keyed "user/draftLocalId". Every write to a draft goes
  // through it, INCLUDING the upload, which is why it is a lock and not a
  // compare-and-set: the upload spans an IMAP round-trip, and its final act is to
  // stamp the row SYNCED. Without holding the lock across the whole thing, an
  // autosave landing mid-upload would be marked as already on the server, and the
  // sentence the user typed while the upload was in flight would never be uploaded
  // at all. Entries are removed when the draft is discarded or sent; a draft the
  // user simply abandons leaves one unused lock object behind per draft per JVM
  // lifetime, which is the cheaper of the two mistakes available here (the other
  // being to remove a lock somebody is about to acquire).
  private final Map<String, ReentrantLock>       draftLocks            = new ConcurrentHashMap<>();

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
   * @param inboxOnly when {@code true}, skip the Sent, Archive and Drafts folders. Sent
   *          and Archive are only needed so a conversation shows the user's own replies
   *          and archived messages inline, they are never mutated locally, and
   *          re-fetching them costs one message body per row -- so a caller that just
   *          needs a fresh inbox (see {@link #resetAndResynchronize(String)}) should not
   *          pay for them. Drafts is skipped for a stronger reason than cost: the only
   *          caller of this mode is the cache reset, whose whole premise is that the
   *          server is the truth and the local copy is disposable — which is false for
   *          the one folder whose rows are authored here.
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
    // The sync memory: per-folder change snapshots + discovered folder names. Loaded
    // once, mutated by the folder syncs below, persisted at the end only when it
    // actually changed (the original serialized form is the dirty check, so a run
    // that skipped everything writes nothing).
    MailboxSyncState syncState = loadMailboxSyncState(username);
    String originalSyncStateJson = JsonUtils.toJsonString(syncState);
    // Snapshotted so the badge is only notified when the number it displays
    // actually moved: a sync cycle that changed nothing would otherwise cost an
    // eviction, a WebSocket frame and a REST re-fetch for every online user,
    // every period, against the specification's "no recurring background load
    // for users who are not consulting their badges"
    long unreadCountBeforeSync = emailBoxStorage.countUnreadEmails(username);
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      updateEmailSyncStatus(username, SyncStatus.IN_PROGRESS);
      int emailBoxCacheSize = emailConnectorService.getEmailBoxCacheSize();
      // INBOX drives the new-mail notifications; Sent and Archive are cached (best
      // effort — a missing folder must not fail the sync) so a conversation shows the
      // user's own replies ("Me") and previously-archived messages inline.
      syncFolderIfChanged(store, store.getFolder("INBOX"), MailFolder.INBOX, username, userEmailSetting, emailBoxCacheSize, true, syncState);
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
        // Drafts last, and that ordering is a dependency rather than a habit: the
        // Drafts reconcile asks whether a message it does not know about has already
        // been sent, and it asks the SENT cache — which the sync a few lines up is what
        // brings up to date. Run first, it would import the copy a failed send-cleanup
        // left behind as a live draft of a mail the user has already sent.
        // Gated on the server-side switch: with server drafts off we neither write to
        // nor read the mailbox's Drafts folder, so a draft written elsewhere simply
        // stays elsewhere, which is what "off" has to mean to be worth having.
        try {
          if (isServerDraftsEnabled()) {
            syncFolderIfChanged(store, resolveDraftsFolder(store, syncState), MailFolder.DRAFTS, username, userEmailSetting, nonInboxWindow, false, syncState);
          }
        } catch (Exception e) {
          LOG.warn("Could not sync the Drafts folder for user {}", username, e);
        }
      }
      updateEmailSyncStatus(username, SyncStatus.SUCCESS);
      // The flags just pulled from the server are the ones the Favorites drawer
      // must show: a mail starred from a phone arrives here, and a reset gave
      // every cached mail a new id that the stored favorites no longer match.
      emailFavoriteService.reconcileFavorites(username);
      if (!inboxOnly) {
        // Only now is the whole mailbox cached. Anything that reads more than the
        // inbox has to wait for this rather than for the inbox's own completion --
        // an inbox-only sync never caches Sent, so it never claims the run is whole.
        broadcastMailboxSyncCompleted(username);
      }
      if (emailBoxStorage.countUnreadEmails(username) != unreadCountBeforeSync) {
        broadcastUnreadCountChanged(username);
      }
    } catch (Exception e) {
      updateEmailSyncStatus(username, SyncStatus.FAILURE);
      LOG.error("Error when user {} synchronization ", username, e);
    } finally {
      // Persisted in the finally so the folders that DID sync keep their fresh
      // snapshots even when a later folder failed; a folder that failed mid-sync
      // never returned a snapshot, so its stale one keeps forcing the full path.
      saveMailboxSyncState(username, syncState, originalSyncStateJson);
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
   * <p>
   * Drafts are excluded for a different reason, and it is not a matter of cost: a
   * draft is not a copy of anything. Clearing the Drafts rows would throw away every
   * unsent word the user has written and nothing would bring them back, so a repair
   * action for a stale MIRROR must not touch the one folder that is not one.
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
    // A reset rebuilds the collected contacts too, through a listener rather than a
    // call: they were collected from the cache being thrown away, and the incremental
    // pass cannot rebuild them -- it judges each inbox sender against cached sent mail,
    // which the resync has not read yet.
    eventPublisher.publishEvent(new MailboxResetEvent(username));
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
      if (totalMessages == 0 && !MailFolder.DRAFTS.equals(folderKey)) {
        // No snapshot for an empty folder: today's behavior is to do nothing here (a
        // cache whose folder emptied remotely keeps its rows), and snapshotting an
        // untouched cache would only set the skip check in stone over it.
        // Drafts do NOT take this exit. An emptied Drafts folder is a thing the user
        // did — they cleared their drafts on another client — and it is the ONE folder
        // where "the server has nothing" must reach the cache, or a draft the user
        // deliberately threw away on their phone stays here forever. The reconcile
        // below handles an empty window on its own terms, and protects what was never
        // up there in the first place.
        return null;
      }
      int startIndex = Math.max(1, totalMessages - emailBoxCacheSize + 1);
      Message[] serverMessages = totalMessages == 0 ? new Message[0] : folder.getMessages(startIndex, totalMessages);
      // Captured NOW, from the SELECT-time values, not at close: mail landing while
      // the download runs would otherwise be recorded in the snapshot without being
      // in the cache, and the next sync would skip right over it. Anything arriving
      // after this line makes the next check mismatch, which is the safe direction.
      FolderSyncSnapshot folderSnapshot = captureFolderSnapshot(folder, totalMessages, emailBoxCacheSize);
      long windowFetchStart = System.currentTimeMillis();
      // Prefetch flags + envelope + UID + headers + MIME structure in a single
      // round-trip (see buildSyncFetchProfile for why every piece is in there).
      if (serverMessages.length > 0) {
        folder.fetch(serverMessages, buildSyncFetchProfile());
      }
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
      // Drafts diverge here, and only here. Everything above — the open, the window
      // listing, the snapshot, the batched FETCH, the light cache load — is the same
      // work whatever the folder holds, and the skip check in front of it is what keeps
      // a Drafts folder nobody has touched from being re-read every period. What cannot
      // be shared is what comes next: the rest of this method treats the server as the
      // truth and the cache as its copy, and for drafts that is exactly backwards.
      if (MailFolder.DRAFTS.equals(folderKey)) {
        syncDraftRows(uidFolder, serverMessages, folderEmails, knownEmailsByUid, username, userEmailSetting, emailBoxCacheSize);
        return folderSnapshot;
      }
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
   * The Drafts folder, from the name remembered in the sync state when possible —
   * same reasoning and same fallback as {@link #resolveSentFolder}, and more
   * pressing here: Sent and Archive are resolved once per scheduled sync, whereas
   * the Drafts folder is resolved on every draft upload, and a draft is uploaded
   * whenever a compose drawer closes. Re-walking the whole folder list ({@code LIST
   * *}) to re-find a folder that never moves would sit in front of each of those.
   * <p>
   * A mailbox with no Drafts folder resolves to null and STAYS null-resolving: we
   * deliberately never create one (see {@link #findDraftsFolder}), so the caller's
   * only correct reaction is to keep the draft local and say so.
   *
   * @param store the connected store
   * @param syncState the mailbox's sync memory, updated in place on rediscovery
   * @return the Drafts folder, or null when the mailbox has none
   * @throws MessagingException if the folder list cannot be read
   */
  private IMAPFolder resolveDraftsFolder(Store store, MailboxSyncState syncState) throws MessagingException {
    if (StringUtils.isNotBlank(syncState.getDraftsFolderName())) {
      Folder cached = store.getFolder(syncState.getDraftsFolderName());
      if (cached instanceof IMAPFolder imapFolder && cached.exists()) {
        return imapFolder;
      }
    }
    IMAPFolder draftsFolder = findDraftsFolder(store);
    syncState.setDraftsFolderName(draftsFolder != null ? draftsFolder.getFullName() : null);
    return draftsFolder;
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
      // Tells the drain when the fleet is actually FETCHING rather than merely submitted,
      // so a provider slow to authenticate is not judged as a fleet that has gone silent.
      BodyPrefetchFleet fleet = new BodyPrefetchFleet(System.currentTimeMillis());
      for (long[] uidSlice : uidSlices) {
        pendingSlices.put(completedSlices.submit(() -> prefetchSlice(folderFullName,
                                                                     uidSlice,
                                                                     userEmailSetting,
                                                                     emailConnector,
                                                                     username,
                                                                     fleet,
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
          completed = pollCompletedSlice(completedSlices, deadline, fleet, username);
          if (completed == null) {
            // Nothing arrived inside the bound -- either the fleet never got logged in, or
            // it went silent after reaching the folder; pollCompletedSlice tells the two
            // apart in the log. Stop the workers (left running they would hold their
            // connections busy on messages the sync has given up on) and fall through to
            // caching every remaining slice with serially-fetched bodies.
            // Deliberately no middle gear here: no retry with fewer workers. When a
            // provider is refusing or stalling logins, opening more connections is exactly
            // the wrong move, and the serial fallback is the one path that does NOT need a
            // new login -- it reuses the sync connection, already authenticated. There is
            // evidence for the misjudgement this bound used to make (12 Aug, see
            // BODY_PREFETCH_SLICE_TIMEOUT_MS) and none at all for a smaller fleet helping.
            // Bring measurements before adding one.
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
   * the wait bound {@link #bodyPrefetchWaitBound} computes from what the fleet is doing.
   * The bound is on "no slice at all completed", not on one particular slice: a single
   * slow slice just keeps downloading while the finished ones are drained around it.
   * <p>
   * Two different bounds, because there are two different failures. While no worker has
   * finished logging in, silence proves nothing about fetching and only the connect bound
   * applies; once the fleet is fetching, {@link #BODY_PREFETCH_SLICE_TIMEOUT_MS} of
   * silence means the connections stalled. The wait therefore resurfaces every
   * {@value #BODY_PREFETCH_CONNECT_POLL_MS} ms while the fleet is still logging in, so
   * the moment a worker reaches the folder the silence window starts from there instead
   * of the drain staying parked on the connect bound. Once the fleet fetches, the wait is
   * a single blocking poll again.
   *
   * @param completedSlices the completion queue the workers hand finished slices to
   * @param deadline the epoch-millis bound shared by every slice of this folder
   * @param fleet what the prefetch connections have reported doing so far
   * @param username the mailbox owner, for logging
   * @return the next completed slice, or null when the wait timed out or was
   *         interrupted -- the caller falls back to serial body fetching for whatever
   *         is still pending
   */
  Future<Map<Long, EmailContent>> pollCompletedSlice(CompletionService<Map<Long, EmailContent>> completedSlices,
                                                     long deadline,
                                                     BodyPrefetchFleet fleet,
                                                     String username) {
    long pollStartedAt = System.currentTimeMillis();
    try {
      while (true) {
        long now = System.currentTimeMillis();
        long fetchingSince = fleet.fetchingSince();
        long waitBound = bodyPrefetchWaitBound(pollStartedAt, fleet.submittedAt(), fetchingSince, deadline);
        if (now >= waitBound) {
          logBodyPrefetchGivenUp(fleet, username);
          return null;
        }
        // Capped while the fleet is still logging in, so a worker reaching the folder is
        // noticed within the granularity rather than at the connect bound.
        long step = fetchingSince == BODY_PREFETCH_NOT_FETCHING ? Math.min(waitBound - now, BODY_PREFETCH_CONNECT_POLL_MS)
                                                                : waitBound - now;
        Future<Map<Long, EmailContent>> completed = completedSlices.poll(Math.max(1, step), TimeUnit.MILLISECONDS);
        if (completed != null) {
          return completed;
        }
      }
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      LOG.warn("Body prefetch interrupted for user {}; the remaining bodies are fetched serially", username, e);
      return null;
    }
  }

  /**
   * When a drain wait that began at {@code pollStartedAt} must stop waiting.
   * <p>
   * Pure and package-visible so the timing rule can be tested at any instant instead of
   * by really sitting out a ninety-second window. The rule:
   * <ul>
   * <li>no worker fetching yet -- the fleet is still authenticating, so silence says
   * nothing about fetching: wait until {@link #BODY_PREFETCH_CONNECT_TIMEOUT_MS} after
   * the slices were <em>submitted</em>;</li>
   * <li>the fleet is fetching -- wait {@link #BODY_PREFETCH_SLICE_TIMEOUT_MS} of silence,
   * counted from the later of this wait's start and the instant the first worker reached
   * the folder. The {@code max} is what makes the FIRST wait connect-aware (it started at
   * submission, before any login completed) while leaving every later wait exactly as it
   * was: those start after slices have been arriving, so they measure silence from their
   * own start.</li>
   * </ul>
   * The folder deadline caps both, so this can never push past the outer bound.
   *
   * @param pollStartedAt when this wait began, epoch millis
   * @param submittedAt when the slices were handed to the pool, epoch millis
   * @param fetchingSince when the first worker reached the folder, epoch millis, or
   *          {@link #BODY_PREFETCH_NOT_FETCHING} while none has
   * @param deadline the folder's outer silence deadline, epoch millis
   * @return the epoch-millis instant at which the wait gives up
   */
  static long bodyPrefetchWaitBound(long pollStartedAt, long submittedAt, long fetchingSince, long deadline) {
    if (fetchingSince == BODY_PREFETCH_NOT_FETCHING) {
      return Math.min(deadline, submittedAt + BODY_PREFETCH_CONNECT_TIMEOUT_MS);
    }
    return Math.min(deadline, Math.max(pollStartedAt, fetchingSince) + BODY_PREFETCH_SLICE_TIMEOUT_MS);
  }

  /**
   * Says which of the two failures the drain just hit, in words that ask the reader for
   * different things. One message covering both cost real diagnosis time on 12 Aug: the
   * sync reported dead connections while the actual incident was a login blowing past the
   * read timeout, and whoever read the log went looking for the wrong thing. Also recorded
   * on the fleet, so the decision is assertable without parsing the log.
   *
   * @param fleet what the prefetch connections reported doing before the wait expired
   * @param username the mailbox owner, for logging
   */
  private void logBodyPrefetchGivenUp(BodyPrefetchFleet fleet, String username) {
    if (fleet.fetchingSince() == BODY_PREFETCH_NOT_FETCHING) {
      fleet.givenUp(BodyPrefetchGiveUp.LOGINS_NEVER_COMPLETED);
      LOG.warn("Not one body prefetch connection of user {} completed its IMAP login and folder SELECT within {}s:"
          + " the mail provider is refusing or stalling logins, the download itself never started."
          + " The remaining messages are fetched serially over the sync connection, which is already authenticated."
          + " Look for a login failure or a read timeout on this account, not for a stalled transfer",
               username,
               BODY_PREFETCH_CONNECT_TIMEOUT_MS / 1000);
    } else {
      fleet.givenUp(BodyPrefetchGiveUp.FETCHING_WENT_SILENT);
      LOG.warn("No body prefetch slice of user {} completed within {}s although {} connection(s) had reached the folder"
          + " and started fetching: those connections stalled or died mid-fetch."
          + " The remaining messages are fetched serially over the sync connection."
          + " Look at what happened to the transfers, the logins succeeded",
               username,
               BODY_PREFETCH_SLICE_TIMEOUT_MS / 1000,
               fleet.connectedWorkers());
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
   * The two ways the parallel body prefetch can be given up on. They look identical from
   * the drain -- nothing arrived -- and are opposite incidents: one is the provider not
   * letting the connections in, the other is the connections dying once inside. They are
   * named apart because they ask different things of whoever reads the log, and because
   * conflating them is precisely what misled the 12 Aug diagnosis.
   */
  enum BodyPrefetchGiveUp {

    /** Not one worker finished its TLS handshake, IMAP login and folder SELECT. */
    LOGINS_NEVER_COMPLETED,

    /** Workers reached the folder and started fetching, then stopped delivering. */
    FETCHING_WENT_SILENT
  }

  /**
   * What the prefetch connections have reported doing, shared between the workers and the
   * sync thread draining them.
   * <p>
   * It exists for one reason: the drain must be able to tell a fleet that is still
   * <em>authenticating</em> from a fleet that has stopped <em>fetching</em>. Submission
   * time cannot tell them apart -- every worker pays a TLS handshake, an IMAP login and a
   * folder SELECT before its first FETCH -- so each worker reports itself here the moment
   * it has the folder open, and the drain's silence window starts from that instead
   * (see {@link EmailBoxService#bodyPrefetchWaitBound}).
   * <p>
   * Read from the sync thread and written from the workers, hence the atomics; the give-up
   * reason is written and read on the sync thread alone, and is kept only so the decision
   * is assertable without parsing the log.
   */
  static final class BodyPrefetchFleet {

    private final long          submittedAt;

    private final AtomicLong    fetchingSince    = new AtomicLong(BODY_PREFETCH_NOT_FETCHING);

    private final AtomicInteger connectedWorkers = new AtomicInteger();

    private BodyPrefetchGiveUp  givenUp;

    /**
     * @param submittedAt when the slices were handed to the pool, epoch millis -- the
     *          origin the connect bound is measured from
     */
    BodyPrefetchFleet(long submittedAt) {
      this.submittedAt = submittedAt;
    }

    /**
     * Called by a worker that has connected, authenticated and SELECTed its folder, i.e.
     * that is about to FETCH. Only the FIRST such report moves the clock: the silence
     * window opens as soon as any connection is capable of delivering, and a straggler
     * logging in later must not push it back and hide a fleet that has since gone quiet.
     */
    void markFetching() {
      connectedWorkers.incrementAndGet();
      fetchingSince.compareAndSet(BODY_PREFETCH_NOT_FETCHING, System.currentTimeMillis());
    }

    /**
     * @return when the first worker reached the folder, epoch millis, or
     *         {@link EmailBoxService#BODY_PREFETCH_NOT_FETCHING} while none has
     */
    long fetchingSince() {
      return fetchingSince.get();
    }

    /**
     * @return when the slices were submitted, epoch millis
     */
    long submittedAt() {
      return submittedAt;
    }

    /**
     * @return how many workers reported themselves fetching, which is what tells a
     *         partially-connected fleet from a fully-connected one in the log
     */
    int connectedWorkers() {
      return connectedWorkers.get();
    }

    /**
     * Records why the drain stopped waiting.
     *
     * @param reason the failure the drain concluded
     */
    void givenUp(BodyPrefetchGiveUp reason) {
      this.givenUp = reason;
    }

    /**
     * @return the failure the drain concluded, or null while the prefetch has not been
     *         given up on
     */
    BodyPrefetchGiveUp givenUp() {
      return givenUp;
    }
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
      boolean hasListId = firstHeader(message, "List-Id") != null;
      if (hasListId && isPostableList(message)) {
        return EmailConnectorUtils.MAIL_TYPE_LIST;
      }
      if (hasListId || firstHeader(message, "List-Unsubscribe") != null) {
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
   * @param fleet the fleet clock this worker reports to once it is actually fetching
   * @param fetchedParts counter of MIME part bodies pulled — a shared in-memory adder,
   *          the one thing a worker may touch besides IMAP
   * @return the slice's bodies keyed by IMAP UID
   */
  private Map<Long, EmailContent> prefetchSlice(String folderFullName,
                                                long[] uids,
                                                UserEmailSetting userEmailSetting,
                                                EmailConnector emailConnector,
                                                String username,
                                                BodyPrefetchFleet fleet,
                                                MimePartStats fetchedParts) {
    Map<Long, EmailContent> contents = new HashMap<>();
    Store store = null;
    Folder folder = null;
    try {
      store = userEmailSettingService.connect(userEmailSetting, emailConnector);
      folder = store.getFolder(folderFullName);
      folder.open(Folder.READ_ONLY);
      // Everything the drain must not confuse with fetching is now behind us: TLS
      // handshake, IMAP login, folder SELECT. From here the connection does nothing but
      // FETCH, so silence from here on IS a stalled transfer -- which is exactly what the
      // drain's silence window is allowed to judge, and not one millisecond earlier.
      fleet.markFetching();
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
  /**
   * Broadcasts {@link EmailConnectorUtils#MAILBOX_SYNC_COMPLETED} once every folder of
   * the run has been cached.
   * <p>
   * Separate from {@link #broadcastNewEmailsSyncCompleted} because that one fires at
   * the end of the INBOX, and Sent is cached after it. A consumer that reads sent mail
   * — the contact backfill does, to learn who the user writes to — starts on the wrong
   * one and finds an empty folder on a first connection.
   *
   * @param username the mailbox owner
   */
  private void broadcastMailboxSyncCompleted(String username) {
    try {
      listenerService.broadcast(EmailConnectorUtils.MAILBOX_SYNC_COMPLETED, username, List.<Long> of());
    } catch (Exception e) {
      LOG.warn("Error broadcasting '{}' for user {}", EmailConnectorUtils.MAILBOX_SYNC_COMPLETED, username, e);
    }
  }

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
   * The listing is the folder's rows and nothing else, which is what keeps a
   * conversation the user has only started — an unsent draft with no mail behind it
   * — out of the inbox. It has no INBOX row to be listed by, and it belongs in
   * Drafts. The conversations that DO appear carry their draft as a summary flag
   * rather than as a row (see {@link EmailBoxStorage#getThreadSummaries}): the list
   * says a reply is unfinished, the reader shows the reply itself.
   * <p>
   * The same summary is what a DRAFTS listing labels its rows with. A draft's own
   * sender is the account owner, so a row rendered from it named the user to
   * themselves and never named the person the conversation is with; the summary
   * carries the conversation's other correspondents instead, which is why the
   * owner's address has to be passed down to it.
   *
   * @param username user getting user emails
   * @param folder the folder to list: {@code INBOX}, {@code SENT}, {@code ARCHIVE}
   *          or {@code DRAFTS}
   * @param starredOnly when {@code true}, only the starred messages are returned
   * @return the folder's cached messages plus the per-conversation summaries
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
    // completion store, never a browsable list. This list and availableFolders in
    // EmailConnectorMailBoxDrawer.vue are the same list expressed twice, with no
    // shared constant between them — change one and the other has to change with it,
    // or the client offers a folder this refuses (or hides one it would serve).
    if (!MailFolder.INBOX.equals(folder) && !MailFolder.SENT.equals(folder) && !MailFolder.ARCHIVE.equals(folder)
        && !MailFolder.DRAFTS.equals(folder)) {
      throw new IllegalArgumentException("emailConnector.folder.notBrowsable");
    }
    List<Email> emails = starredOnly ? emailBoxStorage.getStarredEmails(username, folder)
                                     : emailBoxStorage.getEmails(username, folder);
    return new EmailBox(emails,
                        userEmailSetting.getEmailSyncStatus(),
                        userEmailSetting.getEmailConnectorWebmailUrl(),
                        emailBoxStorage.getThreadSummaries(username, userEmailSetting.getEmailAddress()),
                        emailBoxStorage.getFolderMessageCounts(username));
  }

  /**
   * Delete user emails — every folder, drafts included.
   * <p>
   * Drafts are not exempted here, and that is deliberate rather than an oversight
   * inherited from before they existed. The only caller is the cleanup that runs
   * when a mailbox is disconnected or rebound to a different account, and a draft
   * belongs to the account it was written from: it carries that account's address
   * as its sender, a Message-ID minted in that account's domain, and a place in a
   * conversation cached from that account's server. Carrying it over to whatever
   * mailbox the user binds next would produce a draft that cannot be sent as what
   * it claims to be. A draft that only ever lived here does go with it — which is
   * why the composer pushes to the server on close, so that anything the user
   * cared to finish already exists somewhere the disconnect cannot reach.
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

  /**
   * The bytes of one attachment, fetched live from the mail server, together with the
   * name and content type the message actually declares for it.
   * <p>
   * The FOLDER is the parameter that makes this work at all. It used to open
   * {@code INBOX} unconditionally, which is right for exactly one of the five folders
   * this cache holds: an attachment on a message in Sent or Archive was looked for
   * under a UID that either names nothing there (a 500 the user reads as "the file is
   * broken") or, worse, names an unrelated inbox message whose part "2" happens to
   * exist — so the download quietly hands back somebody else's file. The cached row
   * is now read under the same folder, for the reason
   * {@link EmailAttachmentDAO#findByMailRemoteIdAndAttachmentIdAndUserIdAndFolder}
   * gives: the two halves of one answer must not be looked up under different keys.
   * <p>
   * A blank folder means INBOX, which is what every caller written before folders
   * existed meant.
   *
   * @param mailRemoteId the message's IMAP UID within that folder
   * @param attachmentId the attachment's MIME part path
   * @param username the mailbox owner
   * @param folder the {@link MailFolder} the message is listed in; blank means INBOX
   * @return the attachment with its bytes loaded
   * @throws IllegalAccessException if the user has no connected mailbox
   */
  public EmailAttachment getAttachmentByMailRemoteIdAnIdAndUserId(long mailRemoteId,
                                                                  String attachmentId,
                                                                  String username,
                                                                  String folder) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_ATTACHMENT, username));
    }
    String cachedFolder = StringUtils.defaultIfBlank(folder, MailFolder.INBOX);
    Store store = null;
    Folder inbox = null;
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      inbox = resolveCachedFolder(store, cachedFolder, username);
      if (inbox == null) {
        // The mailbox has no such folder any more (renamed, deleted, or never had one
        // and the rows came from an account that did). Nothing to read; the caller
        // answers 404 rather than reporting a server fault.
        return null;
      }
      inbox.open(Folder.READ_ONLY);
      Message message = ((UIDFolder) inbox).getMessageByUID(mailRemoteId);
      EmailAttachment emailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(mailRemoteId,
                                                                                                 attachmentId,
                                                                                                 username,
                                                                                                 cachedFolder);
      if (emailAttachment == null) {
        // The user has no such attachment in that folder. An answer, not a fault: the
        // caller turns it into a 404. It used to be reachable only through a deleted
        // row, and the code below then failed on a null with a 500; now that the lookup
        // is folder-scoped it is also what a caller asking under the wrong folder gets,
        // which is worth saying plainly rather than reporting as a broken mailbox.
        return null;
      }
      BodyPart bodyPart = getPartByPath(message, attachmentId);
      if (bodyPart == null) {
        throw new RuntimeException("Attachment not found in the email");
      }
      emailAttachment.setData(readPartBytes(bodyPart));
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
   * <p>
   * DRAFTS included, and that is why a draft is a row of this table rather than a
   * table of its own: an unsent reply belongs in the conversation it answers, and
   * putting it there costs nothing here. It reads directly under the message it
   * answers, which the storage layer settles for every caller of a conversation (see
   * {@code EmailThreadingUtils#positionDraftsAfterTheirParent}), so nothing in this
   * method distinguishes it. The reader is where a draft stops looking like mail.
   *
   * @param threadId the conversation id (see {@link #computeThreadId})
   * @param username the mailbox owner
   * @return the thread's messages in reading order, each with body and recipients
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
  @Transactional
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
          inbox = store.getFolder("INBOX");
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
      // Realign the Favorites drawer on what the local rows now say. Reading them
      // back rather than mirroring the requested change is what keeps the drawer
      // honest when the server refused a star: that row was reverted above, and
      // the favorite has to follow it back.
      emailFavoriteService.reconcileFavorites(username);
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
   * Notification — resolved to their localized name and their declared icon.
   * These are the leaf categories seeded from the add-on's
   * {@code default-categories.json}, returned whether or not they are already
   * in use, so the picker always offers the full set.
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

  /**
   * Sends what the composer holds, over the user's own SMTP connector, and files a
   * copy in their Sent folder.
   * <p>
   * This is the ordinary send: a message that has no draft row behind it. Sending a
   * draft is {@link #sendDraft}, which needs a different order of operations and
   * carries the draft's own Message-ID out onto the wire.
   *
   * @param email the composed email
   * @param username the mailbox owner
   * @throws IllegalAccessException if the user may not send from their mailbox
   */
  public void sendEmail(Email email, String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SEND_EMAIL_MESSAGE, username));
    }
    EmailConnector emailConnector =
                                  emailConnectorService.getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId()));
    List<String> uploadIds = new ArrayList<>();
    try {
      MimeMessage message = buildOutgoingMessage(email, userEmailSetting, emailConnector, null, uploadIds);
      applyThreadingHeaders(message, email, username);
      deliver(message, email, StringUtils.isNotEmpty(email.getMailHeaderId()), username, userEmailSetting);
    } catch (MessagingException | UnsupportedEncodingException e) {
      logSendFailure(username, emailConnector, e);
      throw new IllegalStateException(String.format("Error when sending email for user %s", username));
    } finally {
      // Free the commons temporary upload resources only after the message (and its Sent-folder copy) has been built,
      // since the attachment body parts stream their bytes lazily from those temporary files.
      removeUploadResources(uploadIds);
    }
  }

  /**
   * The SMTP session a send runs on, built from the user's connector and
   * authenticated as the user themselves.
   *
   * @param emailConnector the connector the user is bound to
   * @param userEmailSetting the user's credentials
   * @return the session to build and transmit the message on
   */
  private Session smtpSession(EmailConnector emailConnector, UserEmailSetting userEmailSetting) {
    String emailAddress = userEmailSetting.getEmailAddress();
    String emailPassword = userEmailSetting.getEmailPassword();
    Properties props = new Properties();
    props.put("mail.smtp.auth", "true");
    props.put("mail.smtp." + emailConnector.getSmtpSecurityType() + ".enable", "true");
    props.put("mail.smtp.host", emailConnector.getSmtpUrl());
    props.put("mail.smtp.port", emailConnector.getSmtpPort());
    return Session.getInstance(props, new Authenticator() {
      @Override
      protected PasswordAuthentication getPasswordAuthentication() {
        return new PasswordAuthentication(emailAddress, emailPassword);
      }
    });
  }

  /**
   * Builds the message a send transmits: the user as From, the recipients they
   * typed, the subject, the body with its portal links absolutized, and the
   * attachments streamed from their upload ids.
   * <p>
   * Extracted out of {@link #sendEmail} so that {@link #sendDraft} composes exactly
   * the same message rather than a second one that drifts from it. The threading
   * headers are NOT applied here, because the two callers take them from different
   * places: an ordinary send derives them from the parent it is replying to, while
   * a draft has carried its own since its first save.
   *
   * @param email the composed email
   * @param userEmailSetting the user's connector binding, for their own address
   * @param emailConnector the connector the user is bound to
   * @param pinnedMessageId the Message-ID this message must go out with, or null to
   *          let JavaMail mint one — see {@link PinnedMessageIdMimeMessage}
   * @param uploadIds mutable list populated with the upload ids that were attached
   * @return the message, ready for its threading headers and the wire
   * @throws MessagingException if the message cannot be built
   * @throws UnsupportedEncodingException if the user's display name cannot be encoded
   */
  private MimeMessage buildOutgoingMessage(Email email,
                                           UserEmailSetting userEmailSetting,
                                           EmailConnector emailConnector,
                                           String pinnedMessageId,
                                           List<String> uploadIds) throws MessagingException, UnsupportedEncodingException {
    String emailAddress = userEmailSetting.getEmailAddress();
    MimeMessage message = new PinnedMessageIdMimeMessage(smtpSession(emailConnector, userEmailSetting), pinnedMessageId);
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
    // Defaulted rather than dereferenced: a draft sent straight after being resumed
    // can legitimately have an empty body, and an empty mail is a thing a user is
    // allowed to send.
    String body = email.getContent() != null ? StringUtils.defaultString(email.getContent().getBody()) : "";
    Document contentDoc = Jsoup.parseBodyFragment(HtmlUtils.transform(body, null));
    for (Element link : contentDoc.select("a[href^=/portal]")) {
      link.select("i").remove();
      String href = link.attr("href");
      link.attr("href", currentDomain + href);
    }
    applyContentAndAttachments(message, email, contentDoc.body().html(), uploadIds);
    return message;
  }

  /**
   * Puts a built message on the wire and does everything a delivered mail entails:
   * the {@code SEND_EMAIL} broadcast, the sent-recipients event contact collection
   * feeds on, and the copy in the user's Sent folder.
   * <p>
   * The Sent copy is deliberately fenced: the mail is already delivered by then, and
   * failing the whole call over a filing problem would tell the user their message
   * did not go out when it did.
   *
   * @param message the message to transmit
   * @param email the composed email behind it, for the sent-recipients event
   * @param reply whether this message answers another one, which is all the
   *          {@code SEND_EMAIL} broadcast wants to know. Passed in rather than
   *          re-derived here because the two callers read it from different places:
   *          an ordinary send from the parent id the composer sent, a draft from the
   *          In-Reply-To settled on its row at its first save
   * @param username the sender
   * @param userEmailSetting the user's connector binding, for the Sent folder
   * @throws MessagingException if the transmission fails
   */
  private void deliver(MimeMessage message,
                       Email email,
                       boolean reply,
                       String username,
                       UserEmailSetting userEmailSetting) throws MessagingException {
    Transport.send(message);
    listenerService.broadcast(EmailConnectorUtils.SEND_EMAIL, username, reply ? "reply" : "newEmail");
    publishEmailSentEvent(username, email);
    try {
      copyToSentFolder(message, username, userEmailSetting);
    } catch (IllegalStateException e) {
      LOG.warn("Email sent but could not be copied to Sent folder for user {}", username, e);
    }
  }

  /**
   * Logs a refused send with the connector that refused it.
   * <p>
   * The server, the port and the security mode belong in this line. A failure here
   * says nothing about WHICH server refused: the exception names the condition ("451
   * 4.3.2 Internal server error") and no more, so the same mailbox failing on one
   * deployment and working on another is unanswerable from the log alone -- exactly
   * the question that gets asked first. The connect-failure path already prints the
   * host; the authentication path, which is the commoner failure, printed nothing.
   *
   * @param username the sender
   * @param emailConnector the connector the send went through
   * @param e the failure
   */
  private void logSendFailure(String username, EmailConnector emailConnector, Exception e) {
    LOG.error("Error when sending email for user {} through {}:{} ({})",
              username,
              emailConnector.getSmtpUrl(),
              emailConnector.getSmtpPort(),
              emailConnector.getSmtpSecurityType(),
              e);
  }

  /**
   * Saves a draft: always locally, and — when {@code pushToServer} is asked for and
   * the account can take it — up to the mail server's Drafts folder as well.
   * <p>
   * The two halves are deliberately unequal. The local write is what protects the
   * user's words: it is instant, it costs nothing, and the composer asks for it on
   * every pause in typing. The server copy exists so the user's OTHER mail clients
   * can see the draft, and those do not need it within thirty seconds — so the
   * composer only asks for a push when the drawer closes, before a send, and after
   * a couple of minutes of genuine inactivity. The asymmetry is not caution, it is
   * arithmetic: IMAP has no update, so every push re-uploads the entire message,
   * attachments included, and a push per keystroke-pause would mean megabytes an
   * hour for a draft nobody but the author is reading.
   * <p>
   * A push whose row is already {@link DraftState#SYNCED} does nothing at all —
   * that is the whole reason the state is stored.
   * <p>
   * A draft carrying files goes up like any other, attachments and all — see
   * {@link #buildDraftMessage}, which streams each one out of the file store as the
   * APPEND writes it. One condition, and it is the invariant this feature is built
   * around: a draft whose files cannot ALL be read is not uploaded at all (see
   * {@link #draftFilesAreAllReadable}). A copy in the user's Drafts folder that looks
   * complete and is not is the failure to avoid — opened on their phone it shows the
   * text with a file silently missing, and a send from there sends that version. The
   * row is returned unchanged, which leaves the composer saying the draft lives only
   * here, which is true.
   * <p>
   * A draft written in ANOTHER client and imported by the sync is the one case where
   * something has to happen before the message can be built at all: its attachments are
   * MIME part paths into the copy sitting in the Drafts folder, which is precisely the
   * copy this push replaces and deletes. So the bytes are brought over to this side
   * first (see {@link #materializeRemoteDraftParts}) and only then is the new message
   * appended — after which the draft is an ordinary local one and this never runs for
   * it again. If they cannot be brought over, the push does not happen: the stale copy
   * up there still holds the files, which is better than a fresh one that does not.
   * <p>
   * Why the arithmetic above matters more now than it did: every push re-uploads the
   * WHOLE message, so a 20 MB file goes up again on every push. The composer answers
   * that by not arming its idle timer at all while the draft carries a file — a draft
   * with attachments is pushed when the drawer closes and when it is sent, and never
   * in the middle of somebody typing.
   * <p>
   * What the caller may change and what it may not: subject, body, recipients and
   * the revision, yes. The draft's identity — its local id, its Message-ID, the
   * conversation it belongs to, the parent it replies to — is settled at the FIRST
   * save and owned by this service from then on. In particular the incoming
   * {@code mailHeaderId} is read as the PARENT's Message-ID (the same meaning
   * {@link #sendEmail} gives it) on the first save only; on every later save it is
   * ignored, because a resumed draft carries its OWN minted id in that field and
   * threading it against itself is exactly the loop that would follow.
   *
   * @param draft the composed draft; a blank {@code draftLocalId} means a first save
   * @param username the mailbox owner
   * @param pushToServer whether to also upload the draft to the mail server
   * @return the draft as it now stands, carrying its local id, state and revision, or
   *         null when the save carried a local id the user has no draft under (the
   *         draft has since been sent or discarded — see below)
   * @throws IllegalAccessException if the user may not use their mailbox
   */
  public Email saveDraft(Email draft, String username, boolean pushToServer) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SAVE_DRAFT_MESSAGE, username));
    }
    String draftLocalId = StringUtils.isNotBlank(draft.getDraftLocalId()) ? draft.getDraftLocalId()
                                                                         : UUID.randomUUID().toString();
    ReentrantLock lock = draftLocks.computeIfAbsent(draftLockKey(username, draftLocalId), key -> new ReentrantLock());
    lock.lock();
    try {
      Email stored = emailBoxStorage.getDraftByLocalId(username, draftLocalId);
      if (stored == null && StringUtils.isNotBlank(draft.getDraftLocalId())) {
        // The client asked to save UNDER AN ID, and there is no row under it. A local
        // id only ever comes from a previous answer of ours, so this is not a first
        // save: it is an autosave that left the browser before the draft was sent or
        // discarded and landed after. Re-creating the row here would put a draft of an
        // already-sent mail back in front of the user and invite them to send it
        // twice, which is the one outcome this feature must never produce. Answering
        // "there is no such draft" lets the composer's existing catch shrug it off.
        return null;
      }
      Email toStore = stored == null ? buildFirstDraftRevision(draft, draftLocalId, username, userEmailSetting)
                                     : buildNextDraftRevision(draft, stored);
      Email saved = emailBoxStorage.saveDraft(toStore);
      if (!pushToServer || !isServerDraftsEnabled() || DraftState.SYNCED.equals(saved.getDraftState())) {
        return saved;
      }
      // A draft imported from another client carries addresses into its copy on the
      // server, and that copy is what this push is about to replace and delete. The
      // bytes come over FIRST, so the delete never destroys the only holder of a file.
      // Nothing happens here for a draft that has no such parts, which is every draft
      // written in this composer and every imported draft after the first push.
      if (!remoteDraftParts(draftAttachmentRows(saved)).isEmpty()) {
        if (!materializeRemoteDraftParts(saved, username, userEmailSetting)) {
          // Refused, not failed: the user's words are stored, the copy up there is stale
          // but still complete, and the composer's existing notice says the draft lives
          // only here — which is true of the sentence they just typed.
          return saved;
        }
        Email materialized = emailBoxStorage.getDraftByLocalId(username, draftLocalId);
        saved = materialized != null ? materialized : saved;
      }
      if (!draftFilesAreAllReadable(saved, username)) {
        return saved;
      }
      return uploadDraft(saved, username, userEmailSetting);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Sends a draft, and only then takes it apart: save, send, remove the copy on the
   * mail server, remove the local row — in that order, and the order is the whole
   * method.
   * <p>
   * <b>Save first.</b> The composer sends the text it is showing, and that text is
   * written to the row BEFORE anything is transmitted. Everything after this point
   * is destructive, and the row is what the user gets back if any of it fails.
   * <p>
   * <b>Then the two removals, and never before the send.</b> This is deliberately
   * the REVERSE of the compensating re-insert that {@link #deleteEmails delete} and
   * archive use, and the asymmetry will read as an inconsistency to the next person
   * through here, so: those two undo a local change when the server refuses, which
   * is safe because their row is only a MIRROR of a message that still exists on the
   * server — re-creating it costs nothing and loses nothing. For a draft the row IS
   * the content. Nothing else holds the user's unsent words, so re-creating one
   * through an exception path — from a DTO, in a catch block, hoping every column
   * survived the round trip — is not a bet worth taking when simply not deleting it
   * yet is available.
   * <p>
   * <b>Send succeeded, cleanup failed.</b> The local row still goes. The mail is out
   * and cannot be recalled; showing someone a draft of a message they have already
   * sent, and inviting them to send it a second time, is a worse outcome than a
   * stray copy left in a Drafts folder. Both failures are logged at warn, in the
   * same shape as "Email sent but could not be copied to Sent folder", and for the
   * same reason: the user's action succeeded, so it must not be reported as an error.
   * <p>
   * <b>Send failed.</b> Nothing had been removed yet, so the draft is intact here
   * and on the server. Its state goes back to what it was, and the failure travels
   * up for the composer to report the way it already reports a failed send. Its files
   * are intact too — the send never frees them, whatever becomes of it (see
   * {@link #transmitDraft}); they belong to the draft until the draft is gone.
   * <p>
   * <b>Send refused before it starts.</b> A draft that shows a file it can no longer
   * read cannot be sent, and finding that out is the FIRST thing this does — before
   * the composer's text is written to the row and before the row is claimed as
   * {@link DraftState#SENDING}. Nothing is saved, nothing is claimed, nothing is
   * removed: the draft is left exactly where it was, on both sides, for the user to
   * take the broken chip off and try again. Delivering a mail whose attachments are
   * missing is the one failure here that the sender cannot see and cannot take back.
   * <p>
   * <b>Sending a draft written elsewhere.</b> Its files may still be MIME part paths
   * into its copy in the Drafts folder rather than bytes on this side — a draft that
   * was edited and sent without a push in between. They are brought over first (see
   * {@link #materializeRemoteDraftParts}), in the same breath and before the same
   * claim, and a draft whose files cannot be brought over is refused with the same
   * code as one whose file has gone. It is the same failure: a file the composer shows
   * and the message would not carry.
   * <p>
   * A second send of the same draft cannot start while the first is in flight: the
   * row is claimed as {@link DraftState#SENDING} before the message goes out (see
   * there for why the claim is persisted rather than left to the lock).
   *
   * @param draft the composed draft as the composer is showing it, carrying the
   *          local id of the row it is editing
   * @param username the mailbox owner
   * @throws IllegalAccessException if the user may not send from their mailbox
   * @throws ObjectNotFoundException if the user has no draft under that local id
   * @throws IllegalArgumentException if the draft carries no local id, or a send of
   *           it is already in flight
   * @throws IllegalStateException if the send was refused
   */
  public void sendDraft(Email draft, String username) throws IllegalAccessException, ObjectNotFoundException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SEND_EMAIL_MESSAGE, username));
    }
    if (draft == null || StringUtils.isBlank(draft.getDraftLocalId())) {
      // A composer with nothing saved yet has no draft to send; it sends through
      // sendEmail, which owns that path and does not have a row to take apart.
      throw new IllegalArgumentException("emailConnector.drafts.send.localIdMandatory");
    }
    String draftLocalId = draft.getDraftLocalId();
    String lockKey = draftLockKey(username, draftLocalId);
    ReentrantLock lock = draftLocks.computeIfAbsent(lockKey, key -> new ReentrantLock());
    lock.lock();
    boolean sent = false;
    try {
      Email stored = emailBoxStorage.getDraftByLocalId(username, draftLocalId);
      if (stored == null) {
        // Discarded, or already sent — from another tab, or by a request that got here
        // first. Either way there is nothing left to send, and saying so is the whole
        // answer.
        throw new ObjectNotFoundException("emailConnector.drafts.send.gone");
      }
      if (DraftState.SENDING.equals(stored.getDraftState())) {
        // Not a server error and not something to retry as-is: the draft is in the
        // middle of being sent, and the honest thing is to refuse rather than put a
        // second copy of the same mail on the wire.
        throw new IllegalArgumentException("emailConnector.drafts.send.alreadyInFlight");
      }
      // The files the draft has been carrying since some earlier session, read once and
      // checked BEFORE anything is written or claimed. A send that cannot carry every
      // file it shows must leave the draft exactly where it was: nothing saved, nothing
      // claimed, nothing removed here or on the server, and the user still holding a
      // draft they can fix.
      List<EmailAttachment> storedAttachments = emailBoxStorage.getDraftAttachments(username, draftLocalId);
      if (!remoteDraftParts(storedAttachments).isEmpty()) {
        // An imported draft being sent without ever having been pushed since it was
        // edited. Its files are still addresses into the copy on the server, and the
        // message about to go out is built here — so they come over now or the send is
        // refused. Refused and not "sent without them": a mail delivered without the
        // file its sender attached cannot be discovered by them or taken back.
        if (!materializeRemoteDraftParts(stored, username, userEmailSetting)) {
          LOG.warn("The draft of user {} cannot be sent: the files it shows are still only in a server copy that cannot be read",
                   username);
          throw new IllegalStateException("emailConnector.drafts.send.attachmentGone");
        }
        storedAttachments = emailBoxStorage.getDraftAttachments(username, draftLocalId);
      }
      requireReadableDraftFiles(storedAttachments, username);
      DraftState stateBeforeSend = saveDraftBeforeSend(draft, stored);
      emailBoxStorage.updateDraftState(username, draftLocalId, DraftState.SENDING);
      try {
        transmitDraft(draft, stored, storedAttachments, username, userEmailSetting);
      } catch (RuntimeException e) {
        // Nothing has been removed, here or on the server. Put the claim back down at
        // exactly the state the row was in and let the composer report the refusal.
        emailBoxStorage.updateDraftState(username, draftLocalId, stateBeforeSend);
        throw e;
      }
      sent = true;
      cleanupSentDraft(stored, username, userEmailSetting);
    } finally {
      lock.unlock();
      if (sent) {
        // Only once the draft is actually gone. A failed send leaves a row somebody is
        // about to retry, and dropping its lock would let the retry run against a
        // different lock object from the one a concurrent autosave is holding.
        draftLocks.remove(lockKey);
      }
    }
  }

  /**
   * Writes the text the composer is showing onto the draft's row, immediately before
   * the send.
   * <p>
   * The revision is forced past the stored one when the client did not send a newer
   * one. Everywhere else a save carrying a revision the row has already reached is
   * dropped, and rightly so — it is a late autosave carrying text the user has typed
   * past. A send is not that: it is an explicit act, it carries the text on screen at
   * the moment the user pressed the button, and there is nothing newer it could be
   * losing. Letting the guard drop it would mean sending one thing and keeping
   * another, which is precisely what saving first exists to prevent.
   *
   * The row's identity — its id, its Message-ID, its IMAP UID, its threading — is
   * untouched by this and stays where the caller already read it, so the send that
   * follows never depends on what the storage layer chose to hand back.
   *
   * @param draft the composed draft as the composer is showing it
   * @param stored the row as it currently stands
   * @return the state the row now carries, which is also the state to put back if the
   *         send is refused
   */
  private DraftState saveDraftBeforeSend(Email draft, Email stored) {
    Email toStore = buildNextDraftRevision(draft, stored);
    long storedRevision = stored.getDraftRevision() == null ? 0L : stored.getDraftRevision();
    if (toStore.getDraftRevision() == null || toStore.getDraftRevision() <= storedRevision) {
      toStore.setDraftRevision(storedRevision + 1);
    }
    emailBoxStorage.saveDraft(toStore);
    return toStore.getDraftState();
  }

  /**
   * Puts a draft on the wire, as the message it has been claiming to be since its
   * first save.
   * <p>
   * Two things come from the STORED row rather than from what the composer sent, and
   * both are the draft's settled identity: its own minted Message-ID, and the
   * threading headers written when it was first saved. Pinning the Message-ID is
   * what makes the sent message BE the draft as far as every mail client's threading
   * is concerned — and what lets the reader recognise the arriving Sent copy as the
   * same message the draft was, so it replaces it instead of appearing beside it.
   * See {@link PinnedMessageIdMimeMessage} for why pinning it needs a subclass and
   * cannot be a {@code setHeader} call.
   * <p>
   * Everything else — body, recipients and subject — comes from what the composer
   * sent, because that is the text on screen.
   * <p>
   * The attachments come from BOTH, and the difference between the two is the whole
   * reason they do. The composer's payload carries upload ids for files attached in
   * the session it is in; a draft resumed after a restart has none of those, so a
   * message built from the payload alone goes out with the words and without the files
   * — silent loss the sender cannot discover or take back. The draft's own stored
   * files, read off the row by the caller, ride alongside them: what was attached
   * before, and what was attached just now.
   *
   * @param draft the composed draft as the composer is showing it
   * @param stored the row, for the identity the composer does not own
   * @param storedAttachments the draft's own files, read and checked by
   *          {@link #sendDraft} before anything was claimed
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   */
  private void transmitDraft(Email draft,
                             Email stored,
                             List<EmailAttachment> storedAttachments,
                             String username,
                             UserEmailSetting userEmailSetting) {
    EmailConnector emailConnector =
                                  emailConnectorService.getEmailConnector(Long.parseLong(userEmailSetting.getEmailConnectorId()));
    List<String> uploadIds = new ArrayList<>();
    draft.setStoredAttachments(storedAttachments);
    try {
      MimeMessage message = buildOutgoingMessage(draft, userEmailSetting, emailConnector, stored.getMailHeaderId(), uploadIds);
      applyStoredThreadingHeaders(message, stored);
      deliver(message, draft, StringUtils.isNotBlank(stored.getInReplyTo()), username, userEmailSetting);
    } catch (MessagingException | UnsupportedEncodingException e) {
      logSendFailure(username, emailConnector, e);
      throw new IllegalStateException(String.format("Error when sending email for user %s", username));
    } finally {
      // The commons uploads, and only them. Those are this session's temporary files,
      // consumed by the message that was just built, and they cannot be freed before it
      // (and its Sent-folder copy) has been written, because their parts stream lazily
      // from exactly those files. Same as the ordinary send.
      //
      // The draft's STORED files are deliberately NOT freed here, and that asymmetry is
      // the point: they belong to the DRAFT, not to this attempt at sending it. A send
      // that comes back refused leaves the draft standing, and a draft whose files had
      // been released by the failed attempt would be one the user can open, read and
      // never send. They are freed when the draft itself goes — recorded as
      // unreferenced by the row delete that follows a SUCCESSFUL send (see
      // cleanupSentDraft), which is the only place that knows the draft is over.
      removeUploadResources(uploadIds);
    }
  }

  /**
   * Stamps a draft's stored threading headers onto the message it is being sent as.
   * <p>
   * Not {@link #applyThreadingHeaders}: that one derives the headers from a PARENT
   * Message-ID and looks its chain up in the cache, which is right for a mail
   * composed and sent in one go. A draft settled its In-Reply-To and References at
   * its first save, precisely so that it could sit inside its conversation while it
   * was being written; re-deriving them now could only produce the same values, or
   * different ones, and different ones would mean the sent mail lands in a different
   * thread from the draft the user was looking at.
   *
   * @param message the message being sent
   * @param storedDraft the draft's row
   * @throws MessagingException if a header cannot be set
   */
  private void applyStoredThreadingHeaders(Message message, Email storedDraft) throws MessagingException {
    if (StringUtils.isNotBlank(storedDraft.getInReplyTo())) {
      message.setHeader("In-Reply-To", storedDraft.getInReplyTo());
    }
    if (StringUtils.isNotBlank(storedDraft.getMailReferences())) {
      message.setHeader("References", storedDraft.getMailReferences());
    }
  }

  /**
   * Takes a sent draft apart: the copy on the mail server first, then the local row.
   * <p>
   * Neither failure stops the other, and neither is reported to the user. The send
   * has already happened, so there is no error left to act on — only bookkeeping
   * that did not complete, which is what the warn lines are for. Above all the local
   * row goes either way: see {@link #sendDraft} for why a leftover server draft is
   * the better of the two failures available here.
   * <p>
   * <b>This is where the draft's files are released.</b> Not by this method directly —
   * by {@link EmailBoxStorage#deleteEmailsByIds}, which reads the file ids off the
   * attachment rows and records them as unreferenced BEFORE it deletes anything. The
   * order is not a nicety: that delete is a bulk JPQL statement, the attachment rows go
   * with it through the database's own {@code ON DELETE CASCADE}, nothing in Java ever
   * observes them dying, and afterwards nothing anywhere knows which files they named.
   * Read first or never. A marker written for a file that turns out to still be
   * referenced is harmless (the sweep verifies); bytes with nothing naming them are a
   * leak with no record of itself.
   * <p>
   * And this is the ONLY place a send frees a draft's files. The send itself does not,
   * however it ends: see {@link #transmitDraft}.
   *
   * @param sentDraft the row that was just sent, as it was read before the send —
   *          which is where its technical id and the UID of its server copy live
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   */
  private void cleanupSentDraft(Email sentDraft, String username, UserEmailSetting userEmailSetting) {
    // Whether there is a copy up there is the UID's answer, not the state's: a row can
    // be LOCAL_ONLY and still carry the UID of a copy we appended (see
    // serverDraftCopyUid), and skipping it here left that copy behind in the Drafts
    // folder of a mail the user had already sent.
    long serverCopyUid = serverDraftCopyUid(sentDraft);
    if (serverCopyUid > 0 && isServerDraftsEnabled()
        && !removeServerDraftCopy(serverCopyUid, sentDraft.getMailHeaderId(), username, userEmailSetting)) {
      LOG.warn("Email sent but its draft copy could not be removed from the Drafts folder for user {}", username);
    }
    try {
      deleteEmails(List.of(sentDraft));
    } catch (Exception e) {
      LOG.warn("Email sent but its local draft row could not be removed for user {}", username, e);
    }
  }

  /**
   * Discards a draft: the copy on the mail server first, then the local row.
   * <p>
   * That order is the opposite of the upload's, and for the same underlying reason.
   * Uploading appends before deleting because a visible duplicate beats lost
   * content; discarding removes the server copy first because the failure to avoid
   * here is the mirror image — a draft the user has thrown away reappearing in
   * their other mail client. If the server removal fails the local row stays too,
   * so the two never disagree about whether the draft exists, and the user can try
   * again.
   *
   * @param draftLocalId the composer's handle on the draft
   * @param username the mailbox owner
   * @return true when a draft was found and removed
   * @throws IllegalAccessException if the user may not use their mailbox
   */
  public boolean deleteDraft(String draftLocalId, String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_DELETE_EMAIL_MESSAGE, username));
    }
    String lockKey = draftLockKey(username, draftLocalId);
    ReentrantLock lock = draftLocks.computeIfAbsent(lockKey, key -> new ReentrantLock());
    lock.lock();
    try {
      Email stored = emailBoxStorage.getDraftByLocalId(username, draftLocalId);
      if (stored == null) {
        return false;
      }
      // Again the UID and not the state: a LOCAL_ONLY row that carries one has a copy
      // up there just the same, and reading the state here meant a draft the user had
      // thrown away was left in their Drafts folder — and, being a Drafts message we
      // no longer had a row for, imported back by the next sync as a draft they had
      // deliberately discarded.
      long serverCopyUid = serverDraftCopyUid(stored);
      if (serverCopyUid > 0 && isServerDraftsEnabled()
          && !removeServerDraftCopy(serverCopyUid, stored.getMailHeaderId(), username, userEmailSetting)) {
        // The server still has it. Keeping the local row is what stops the two from
        // disagreeing, and what lets the user simply try again.
        throw new IllegalStateException("emailConnector.drafts.discard.serverCopyRemains");
      }
      deleteEmails(List.of(stored));
      return true;
    } finally {
      lock.unlock();
      // The draft is gone, so nobody can be about to take this lock for it. Removing
      // it here is what keeps the map bounded by the drafts that actually exist
      // rather than by every draft ever written in this JVM's lifetime.
      draftLocks.remove(lockKey);
    }
  }

  /**
   * Attaches a file to a draft: the bytes are stored in the platform's file service,
   * a row records where, and the draft is marked as edited.
   * <p>
   * The bytes are kept HERE rather than left as a commons upload, and that is the
   * whole slice. An upload is a temporary file with a lifetime measured against one
   * browser session; a draft's whole purpose is to outlive the session, the tab and
   * the server restart. A draft resumed tomorrow whose files were uploads would show
   * chips that resolve to nothing and send a mail with nothing attached — the user's
   * words kept and their files quietly dropped.
   * <p>
   * Under the draft's own lock, like every other write to a draft: the composer
   * autosaves on a typing pause, so attaching a file races an autosave by
   * construction, and both of them step the row's revision.
   * <p>
   * The draft is NOT pushed to the mail server from here, and that is a decision
   * rather than an omission: a push is an IMAP round trip, and hanging one off a
   * paperclip click would make the file appear a couple of seconds after the user
   * attached it. The next save pushes it, which is on the drawer closing or on the
   * send — see {@link #saveDraft} for why an attachment-carrying draft is deliberately
   * not pushed on the idle timer either.
   *
   * @param draftLocalId the composer's handle on the draft
   * @param username the mailbox owner
   * @param attachment the upload to attach, carrying its commons upload id, name and
   *          content type
   * @return the draft as it now stands, attachments included, or null when the user
   *         has no draft under that id
   * @throws IllegalAccessException if the user may not use their mailbox
   * @throws IllegalArgumentException if the upload is gone, or the draft would go over
   *           the size a message may carry
   */
  public Email addDraftAttachment(String draftLocalId,
                                  String username,
                                  EmailOutgoingAttachment attachment) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SAVE_DRAFT_MESSAGE, username));
    }
    if (StringUtils.isBlank(draftLocalId) || attachment == null || StringUtils.isBlank(attachment.getUploadId())) {
      throw new IllegalArgumentException("emailConnector.drafts.attach.uploadMandatory");
    }
    ReentrantLock lock = draftLocks.computeIfAbsent(draftLockKey(username, draftLocalId), key -> new ReentrantLock());
    lock.lock();
    try {
      Email stored = emailBoxStorage.getDraftByLocalId(username, draftLocalId);
      if (stored == null) {
        return null;
      }
      // Refused before the bytes are written rather than after, so a file that cannot
      // be sent is never stored: the cap is the one the send path already enforces,
      // and a draft that has gone over it is a draft that cannot leave.
      if (storedAttachmentsSize(username, draftLocalId) + Math.max(attachment.getSize(), 0) > MAX_OUTGOING_ATTACHMENTS_SIZE) {
        throw new IllegalArgumentException("emailConnector.mailBox.newEmail.attach.maxSize.error");
      }
      if (emailBoxStorage.addDraftAttachment(username,
                                             draftLocalId,
                                             attachment.getUploadId(),
                                             attachment.getName(),
                                             attachment.getMimeType()) == null) {
        throw new IllegalArgumentException("emailConnector.drafts.attach.uploadGone");
      }
      // The upload has been copied into the file store, so the temporary file has no
      // further purpose. Released here rather than left to expire: the send path does
      // the same for the attachments it consumes.
      removeUploadResources(List.of(attachment.getUploadId()));
      return emailBoxStorage.getDraftByLocalId(username, draftLocalId);
    } finally {
      lock.unlock();
    }
  }

  /**
   * Puts the files of a message being FORWARDED onto the draft that forwards it, so
   * the forward arrives carrying what the original carried.
   * <p>
   * <b>The defect this exists for.</b> Forwarding was a prefill and nothing else: the
   * composer assembled a quoted header and the original's body, and never looked at its
   * files. The forward went out with the text of a message and none of its attachments,
   * and nothing said so — the sender saw a composer with no chips and had no reason to
   * think anything was missing, and the recipient received a mail referring to a
   * document that was not on it.
   * <p>
   * <b>When the bytes are copied: now, on opening the forward.</b> The alternative —
   * recording the parts as addresses into the original and fetching them at send time,
   * which is what an imported draft does (see {@link #materializeRemoteDraftParts}) —
   * is wrong HERE for one reason that outweighs the saving. The message being forwarded
   * belongs to the mailbox, not to the draft, and "forward this, then archive or delete
   * it" is an ordinary thing to do in the seconds that follow. A late copy would resolve
   * addresses into a message the user has since moved or deleted, and the failure would
   * land at the moment of sending, when the original is gone and nothing can be
   * recovered. Copying at open time moves the only possible failure to the moment the
   * user is still looking at the message, can see that the chips did not appear, and can
   * do something about it. It costs a copy on a forward the user may abandon; an
   * abandoned forward is a draft they can discard, which is a recoverable cost, and the
   * other one is not.
   * <p>
   * <b>Per forward, never shared.</b> Every forward copies the bytes again rather than
   * pointing at a file another draft owns — see {@code EmailBoxStorage#copyDraftAttachment}
   * for why sharing would let one draft's removal free the other's file. Forwarding one
   * message twice therefore produces two independent forwards.
   * <p>
   * <b>Forwarding a draft, or anything whose bytes are already here.</b> A row that
   * already carries a file id is read out of the file store instead of the mail server,
   * with no IMAP connection opened at all when NO row needs one. That is what a draft's
   * own files are, so a forward of a draft works rather than silently producing nothing.
   * <p>
   * <b>Size.</b> The cap is the send path's, counted across what the draft already
   * carries — so a forward the user then adds their own file to cannot creep over it.
   * A file that would take the draft over is not stored, and is NAMED in the answer:
   * attaching it anyway would produce a draft that can never be sent, and dropping it
   * quietly would be this very defect one layer up. The remaining files are still
   * attached, because a 30 MB video should not stop the three documents beside it from
   * being forwarded.
   * <p>
   * <b>Nothing throws for a file.</b> A part that cannot be read — the message moved
   * between the click and this call, the mailbox will not answer — is named in the
   * answer too, and the rest are still copied. The user then sees exactly what the
   * forward will carry, as chips, and exactly what it will not, as a notice. Failing
   * the whole call would strand the files already copied and tell them less.
   * <p>
   * Under the draft's lock and stepping its revision, like every other write to a
   * draft: attaching IS an edit, and a synced draft that did not notice one would be
   * sent without the files it shows.
   * <p>
   * Inline images referenced from the body by {@code cid:} are deliberately not part of
   * this: they are not attachments here — the MIME walk turns them into data URLs
   * inside the body — so a forward carries them already, embedded in the quoted text.
   *
   * @param draftLocalId the composer's handle on the draft the forward is written in
   * @param username the mailbox owner
   * @param mailRemoteId the IMAP UID of the message being forwarded
   * @param folder the {@link MailFolder} that message is listed in; blank means INBOX
   * @return the draft as it now stands with the files that were left behind, or null
   *         when the user has no such draft or no such message in that folder
   * @throws IllegalAccessException if the user may not use their mailbox
   * @throws IllegalArgumentException if no draft was named
   */
  public ForwardedAttachments addForwardedAttachments(String draftLocalId,
                                                      String username,
                                                      long mailRemoteId,
                                                      String folder) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SAVE_DRAFT_MESSAGE, username));
    }
    if (StringUtils.isBlank(draftLocalId)) {
      throw new IllegalArgumentException("emailConnector.drafts.forward.draftMandatory");
    }
    String cachedFolder = StringUtils.defaultIfBlank(folder, MailFolder.INBOX);
    ReentrantLock lock = draftLocks.computeIfAbsent(draftLockKey(username, draftLocalId), key -> new ReentrantLock());
    lock.lock();
    try {
      if (emailBoxStorage.getDraftByLocalId(username, draftLocalId) == null) {
        LOG.warn("Forward for user {}: draft {} does not exist, nothing was attached", username, draftLocalId);
        return null;
      }
      // The message is read from the CACHE, under this user and this folder, and the
      // caller never says which parts to take. That is the whole of the access check:
      // a UID is not a name, and a caller that could hand in a part path would be
      // asking this method to read an arbitrary part of an arbitrary message and
      // attach it to their own draft. The rows this answers with are the ones the
      // sync already wrote for a message that is theirs.
      Email source = emailBoxStorage.getEmailByMailRemoteIdAndUserId(mailRemoteId,
                                                                     username,
                                                                     userEmailSetting.getEmailAddress(),
                                                                     cachedFolder,
                                                                     true,
                                                                     false,
                                                                     false);
      if (source == null) {
        LOG.warn("Forward for user {}: message {} is not cached in folder {}, so its files could not be carried over",
                 username,
                 mailRemoteId,
                 cachedFolder);
        return null;
      }
      List<EmailAttachment> forwarded = forwardableAttachments(source);
      List<String> notAttached = forwarded.isEmpty() ? new ArrayList<>()
                                                     : copyForwardedAttachments(forwarded,
                                                                                draftLocalId,
                                                                                username,
                                                                                userEmailSetting,
                                                                                cachedFolder,
                                                                                mailRemoteId);
      // Said once, on the way out, and said even when there was nothing to carry.
      // A forward that worked and a forward that never ran used to look identical
      // from the outside: the whole path was silent unless a single file failed. On
      // an acceptance server, where nobody can watch a browser's network tab, that
      // silence is the difference between "it works" and "no evidence either way" -
      // and it cost an afternoon of testing to notice. The per-file reasons are
      // already warned about individually; this is the line that says the operation
      // happened at all.
      LOG.info("Forward for user {}: {} of {} file(s) of message {} in folder {} carried onto draft {}, {} refused",
               username,
               forwarded.size() - notAttached.size(),
               forwarded.size(),
               mailRemoteId,
               cachedFolder,
               draftLocalId,
               notAttached.size());
      return new ForwardedAttachments(emailBoxStorage.getDraftByLocalId(username, draftLocalId), notAttached);
    } finally {
      lock.unlock();
    }
  }

  /**
   * The attachments of a message that a forward can actually carry: the ones this can
   * find bytes for.
   * <p>
   * A row is usable when it has either a file of its own (a draft's file, already in
   * the platform's file store) or a MIME part path (a received message's file, still on
   * the server). A row with neither addresses nothing — it can be neither downloaded
   * nor copied — so it is dropped here rather than reported to the user as a file that
   * was left behind, which would be a notice about a row rather than about a file.
   *
   * @param source the message being forwarded, read with its attachments
   * @return the attachments to copy, in the order the message carries them, never null
   */
  private List<EmailAttachment> forwardableAttachments(Email source) {
    if (source == null || source.getContent() == null || CollectionUtils.isEmpty(source.getContent().getAttachments())) {
      return List.of();
    }
    return source.getContent()
                 .getAttachments()
                 .stream()
                 .filter(attachment -> attachment != null
                     && (attachment.getFileId() != null || StringUtils.isNotBlank(attachment.getAttachmentRemoteId())))
                 .toList();
  }

  /**
   * Copies each of a forwarded message's files onto the draft, one at a time, and
   * answers with the names of the ones that did not make it.
   * <p>
   * <b>One connection at most, and only if one is needed.</b> The store is opened on
   * the first row whose bytes are on the server and reused for the rest; a forward of a
   * message whose files are all in the file store opens nothing. A connection that
   * cannot be opened is not retried per file — it is one failure, and it makes every
   * remaining row unreadable rather than one.
   * <p>
   * <b>One part at a time in memory</b>, read, written to the file store and forgotten
   * before the next is fetched, which is the same limit
   * {@link #materializeRemoteDraftParts} and the attachment download already work
   * under. The peak is the single largest file of the message — including one that is
   * then refused by the cap, which is read before its size is known for certain and
   * discarded. The declared part size would avoid that read, but it is the ENCODED
   * size: using it would refuse a 20 MB file that base64 declares as 27 MB, and
   * refusing a file that fits is the worse error here.
   * <p>
   * The running total starts from what the draft already carries rather than from zero,
   * so the cap holds across a forward whose files the user then adds to, and across a
   * second forward into the same draft.
   *
   * @param forwarded the attachments to copy, in the message's own order
   * @param draftLocalId the composer's handle on the draft
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   * @param folder the folder the forwarded message is listed in
   * @param mailRemoteId the IMAP UID of the forwarded message within that folder
   * @return the names of the files that were not attached, never null
   */
  private List<String> copyForwardedAttachments(List<EmailAttachment> forwarded,
                                                String draftLocalId,
                                                String username,
                                                UserEmailSetting userEmailSetting,
                                                String folder,
                                                long mailRemoteId) {
    List<String> notAttached = new ArrayList<>();
    long total = storedAttachmentsSize(username, draftLocalId);
    ForwardSource source = new ForwardSource();
    try {
      for (EmailAttachment attachment : forwarded) {
        String name = StringUtils.defaultIfBlank(attachment.getName(), DEFAULT_ATTACHMENT_NAME);
        byte[] bytes = forwardedAttachmentBytes(attachment, source, username, userEmailSetting, folder, mailRemoteId);
        if (bytes == null) {
          LOG.warn("The file {} of message {} in {} could not be read for the forward of user {}",
                   name,
                   mailRemoteId,
                   folder,
                   username);
          notAttached.add(name);
          continue;
        }
        if (total + bytes.length > MAX_OUTGOING_ATTACHMENTS_SIZE) {
          // Named rather than attached: a draft over the cap is a draft that cannot
          // leave, and a file dropped in silence is the defect this feature is about.
          notAttached.add(name);
          continue;
        }
        if (emailBoxStorage.copyDraftAttachment(username, draftLocalId, name, attachment.getMimeType(), bytes) == null) {
          LOG.warn("The file {} of the forward of user {} could not be written to the file store", name, username);
          notAttached.add(name);
          continue;
        }
        total += bytes.length;
      }
    } finally {
      closeQuietly(source.folder, source.store, username);
    }
    return notAttached;
  }

  /**
   * The bytes of one file the forward has to carry, from wherever that file lives.
   * <p>
   * Two kinds of row, and they are told apart by the file id rather than by where the
   * message came from: a row with one already has its bytes in the platform's file
   * store (a draft's own file), and a row without one is a MIME part of a message on
   * the server, which is what every received attachment is. The two are mutually
   * exclusive by construction.
   * <p>
   * Answers null for every way this can fail, because the caller's reaction is the same
   * for all of them — name the file as one the forward will not carry — and because
   * throwing would strand the files already copied.
   *
   * @param attachment the row to read
   * @param source the lazily opened connection to the forwarded message, carried across
   *          the loop so it is opened at most once
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   * @param folder the folder the forwarded message is listed in
   * @param mailRemoteId the IMAP UID of the forwarded message within that folder
   * @return its content, or null when it cannot be read
   */
  private byte[] forwardedAttachmentBytes(EmailAttachment attachment,
                                          ForwardSource source,
                                          String username,
                                          UserEmailSetting userEmailSetting,
                                          String folder,
                                          long mailRemoteId) {
    try {
      if (attachment.getFileId() != null) {
        FileItem fileItem = emailBoxStorage.getAttachmentFileItem(attachment.getFileId());
        return fileItem == null ? null : fileItem.getAsByte();
      }
      Message message = openForwardSource(source, userEmailSetting, folder, mailRemoteId, username);
      if (message == null) {
        return null;
      }
      BodyPart bodyPart = getPartByPath(message, attachment.getAttachmentRemoteId());
      return bodyPart == null ? null : readPartBytes(bodyPart);
    } catch (Exception e) {
      LOG.warn("A file of message {} in {} could not be read for the forward of user {}", mailRemoteId, folder, username, e);
      return null;
    }
  }

  /**
   * The message a forward is copying from, opened at most once and closed by the loop
   * that used it.
   * <p>
   * A holder rather than three local variables because the opening is lazy — a forward
   * of files that are all in the file store must not connect to the mail server at all
   * — and a failure to open must be remembered, or every remaining file would retry a
   * connection that has already been refused.
   */
  private static final class ForwardSource {

    private Store   store;

    private Folder  folder;

    private Message message;

    private boolean attempted;
  }

  /**
   * The forwarded message, connecting and opening its folder on the first call and
   * answering what the first call found on every one after it.
   * <p>
   * The "attempted" flag is what makes a refused connection cost one failure rather
   * than one per file: without it, a mailbox that will not answer would be dialled once
   * for every attachment of the message, each with its own timeout, before the user is
   * told anything.
   *
   * @param source the holder carrying the connection across the loop
   * @param userEmailSetting the user's connector binding
   * @param folderName the folder the forwarded message is listed in
   * @param mailRemoteId its IMAP UID within that folder
   * @param username the mailbox owner
   * @return the message, or null when it cannot be reached
   * @throws MessagingException if the mailbox refuses the connection or the folder
   */
  private Message openForwardSource(ForwardSource source,
                                    UserEmailSetting userEmailSetting,
                                    String folderName,
                                    long mailRemoteId,
                                    String username) throws MessagingException {
    if (source.attempted) {
      return source.message;
    }
    source.attempted = true;
    source.store = userEmailSettingService.connect(userEmailSetting);
    source.folder = resolveCachedFolder(source.store, folderName, username);
    if (source.folder == null) {
      return null;
    }
    // READ_ONLY: a forward reads the message it copies from and changes nothing about
    // it. The original stays exactly where it was, unread flags and all.
    source.folder.open(Folder.READ_ONLY);
    source.message = ((UIDFolder) source.folder).getMessageByUID(mailRemoteId);
    return source.message;
  }

  /**
   * Removes one file from a draft.
   * <p>
   * Under the draft's lock and stepping its revision, for the reason
   * {@link #addDraftAttachment} gives: a detach is an edit, and a synced draft that
   * does not notice one would send a file the user has taken off.
   *
   * @param draftLocalId the composer's handle on the draft
   * @param username the mailbox owner
   * @param attachmentId the attachment row id
   * @return the draft as it now stands, or null when the user has no such draft or no
   *         such attachment on it
   * @throws IllegalAccessException if the user may not use their mailbox
   */
  public Email removeDraftAttachment(String draftLocalId, String username, long attachmentId) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SAVE_DRAFT_MESSAGE, username));
    }
    ReentrantLock lock = draftLocks.computeIfAbsent(draftLockKey(username, draftLocalId), key -> new ReentrantLock());
    lock.lock();
    try {
      if (!emailBoxStorage.removeDraftAttachment(username, draftLocalId, attachmentId)) {
        return null;
      }
      return emailBoxStorage.getDraftByLocalId(username, draftLocalId);
    } finally {
      lock.unlock();
    }
  }

  /**
   * The bytes of one file attached to a draft, read back out of the file store.
   * <p>
   * Deliberately NOT reachable through {@code /attachments/{mailRemoteId}/...}, which
   * is the endpoint every received attachment is downloaded from. That one addresses
   * a message by its IMAP UID, and an unpushed draft has none — its MAIL_REMOTE_ID is
   * null, which is the very column that query joins on. A draft's file is addressed
   * the way everything else about a draft is: by the draft's local id.
   *
   * @param draftLocalId the composer's handle on the draft
   * @param username the mailbox owner
   * @param attachmentId the attachment row id
   * @return the attachment with its bytes, or null when the user has no such one
   * @throws IllegalAccessException if the user may not use their mailbox
   */
  public EmailAttachment getDraftAttachment(String draftLocalId,
                                            String username,
                                            long attachmentId) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_ATTACHMENT, username));
    }
    EmailAttachment attachment = emailBoxStorage.getDraftAttachment(username, draftLocalId, attachmentId);
    if (attachment == null || attachment.getFileId() == null) {
      return null;
    }
    FileItem fileItem = emailBoxStorage.getAttachmentFileItem(attachment.getFileId());
    if (fileItem == null) {
      // The row survived its file. Not something the user can act on and not a fault
      // of the request, so it reads as "no such attachment" rather than a 500 — and it
      // is worth a warning, because it means something freed a file that was still
      // referenced.
      LOG.warn("The draft attachment {} of user {} has no file behind it", attachmentId, username);
      return null;
    }
    attachment.setData(fileItem.getAsByte());
    return attachment;
  }

  /**
   * The files a draft carries on this side: its attachments that have bytes in the
   * platform's file store, which is what a locally authored draft's attachment is.
   * <p>
   * Attachments with no file id are filtered out rather than tolerated, because they
   * are a different thing entirely: a MIME part path pointing INTO a message on the
   * server. Nothing here can put one of those on a message it is building — the bytes
   * are not on this side — so treating the two as one list would silently produce a
   * message missing a part.
   *
   * @param draft the draft as stored, read with its attachments
   * @return its own stored files, oldest first, never null
   */
  private List<EmailAttachment> storedAttachmentsOf(Email draft) {
    if (draft == null || draft.getContent() == null || CollectionUtils.isEmpty(draft.getContent().getAttachments())) {
      return List.of();
    }
    return draft.getContent()
                .getAttachments()
                .stream()
                .filter(attachment -> attachment != null && attachment.getFileId() != null)
                .toList();
  }

  /**
   * Whether every file a draft carries is still there to be read — the condition an
   * upload to the mail server is gated on, and the invariant this whole feature is
   * built around.
   * <p>
   * An IMAP APPEND writes the ENTIRE message. A draft uploaded without one of its
   * files puts a copy in the user's Drafts folder that looks complete and is not:
   * opened on their phone it shows the text with a file silently missing, and — worse
   * — a send from that client sends the incomplete version. So a draft that cannot be
   * assembled in full is not uploaded at all. The caller returns the row unchanged,
   * and the composer's existing "your draft lives only here" notice, which fires
   * whenever a push leaves the row unsynced, says the true thing with nothing new
   * built.
   * <p>
   * A draft that was uploaded BEFORE it lost a file keeps the copy it has: stale
   * rather than wrong, removed when the draft is sent or discarded.
   * <p>
   * The check is on the file's METADATA rather than on its bytes, deliberately. It is
   * one cheap read per file instead of pulling megabytes into memory to prove they can
   * be pulled into memory, and it catches the failure that actually happens — the file
   * was freed while the row still names it. A file whose metadata is there and whose
   * bytes are not still fails, one layer down: the part throws while the message is
   * being written, the APPEND fails, and {@link #uploadDraft} leaves the row unsynced.
   * Same outcome, slower road.
   * <p>
   * It asks about EVERY attachment row and not only the ones with a file
   * ({@link #draftAttachmentRows}, deliberately not {@link #storedAttachmentsOf}). A
   * row with no file id is an imported draft's remote part that
   * {@link #materializeRemoteDraftParts} did not manage to bring over, and the message
   * builder would silently leave it out — which is the same failure as a missing file,
   * arrived at from the other side. Structural rather than trusting the caller to have
   * materialized first: the invariant is "never a message with a part quietly missing",
   * and a gate that can be bypassed by adding a call site is not one.
   *
   * @param draft the draft as stored, read with its attachments
   * @param username the mailbox owner, for the log
   * @return true when the draft can be assembled whole
   */
  private boolean draftFilesAreAllReadable(Email draft, String username) {
    for (EmailAttachment attachment : draftAttachmentRows(draft)) {
      if (attachment.getFileId() == null) {
        LOG.warn("The draft of user {} shows the file {}, whose bytes are still only in its copy on the server;"
            + " it is not uploaded to the Drafts folder", username, attachment.getName());
        return false;
      }
      if (!emailBoxStorage.attachmentFileExists(attachment.getFileId())) {
        // Warn rather than debug: a row naming a file that is gone means something
        // freed a file that was still referenced, which is a bug somewhere else and is
        // worth seeing in a log.
        LOG.warn("The draft of user {} references the file {} which is gone; it is not uploaded to the Drafts folder",
                 username,
                 attachment.getFileId());
        return false;
      }
    }
    return true;
  }

  /**
   * Every attachment row a draft carries, whatever kind it is — the list the two gates
   * ask their question of.
   * <p>
   * Deliberately not {@link #storedAttachmentsOf}, which answers only the rows that
   * have bytes on this side. That filter is right where a message is being ASSEMBLED
   * (nothing else can be put on a part), and it is exactly wrong where the question is
   * "may this message be assembled at all": a row it silently skips is a file the user
   * can see and the message would not carry.
   *
   * @param draft the draft as stored, read with its attachments
   * @return its attachment rows, never null
   */
  private List<EmailAttachment> draftAttachmentRows(Email draft) {
    if (draft == null || draft.getContent() == null || CollectionUtils.isEmpty(draft.getContent().getAttachments())) {
      return List.of();
    }
    return draft.getContent().getAttachments().stream().filter(Objects::nonNull).toList();
  }

  /**
   * The attachments of a draft that are still only an ADDRESS: a MIME part path into
   * the copy sitting in the user's Drafts folder, with no bytes on this side.
   * <p>
   * That is what an imported draft's files are (see
   * {@link #createDraftFromServerMessage}) and what
   * {@link #materializeRemoteDraftParts} exists to convert. The two kinds are mutually
   * exclusive by construction — a file id or a part path, never both — so this and
   * {@link #storedAttachmentsOf} partition the rows between them.
   *
   * @param attachments the draft's attachment rows, may be null
   * @return the ones whose bytes are still only on the server, never null
   */
  private List<EmailAttachment> remoteDraftParts(List<EmailAttachment> attachments) {
    if (CollectionUtils.isEmpty(attachments)) {
      return List.of();
    }
    return attachments.stream()
                      .filter(attachment -> attachment != null && attachment.getFileId() == null
                          && StringUtils.isNotBlank(attachment.getAttachmentRemoteId()))
                      .toList();
  }

  /**
   * Brings the bytes of a draft's remote parts over to this side, out of the copy on
   * the mail server, so that the draft stops depending on a message that is about to
   * be destroyed.
   * <p>
   * <b>Why this has to happen before anything else.</b> A draft written on the user's
   * phone carries addresses, not content: its attachments name parts of ONE message,
   * at one UID, in the Drafts folder. Editing that draft makes it
   * {@link DraftState#DIRTY}, and a push then APPENDs a message rebuilt from the row
   * and deletes the previous copy — IMAP has no update. Rebuilt from addresses, that
   * message carries no files, and the copy being deleted is the only thing that held
   * them. The user would watch chips they could see turn into a mail with nothing
   * attached, on both sides at once.
   * <p>
   * <b>Copy first, then append, then delete.</b> Doing this here rather than streaming
   * the parts straight from the old copy into the APPEND is what makes the ordering
   * safe rather than merely usual: when the previous copy is deleted the bytes are
   * already in the platform's file store, so there is no window in which the only
   * holder of a file is a message being removed. Streaming would also have to keep the
   * old message readable across a write of the new one on the same connection, for a
   * saving of one copy on a path that runs once in a draft's life.
   * <p>
   * <b>Once, and only when needed.</b> A materialized row is indistinguishable from a
   * file the user attached here, so this runs on the first push or send AFTER an
   * imported draft is edited and never again — and never at all for the imported draft
   * nobody opens, which is the common case and the reason the sync does not do this.
   * <p>
   * <b>Memory.</b> One part at a time, and no more: each part is read, written to the
   * file store and forgotten before the next is fetched. The IMAP side does stream —
   * a part's {@code getInputStream} is a partial {@code FETCH BODY[n]} rather than a
   * whole-message read — but {@code FileService} takes bytes, so the peak cost of this
   * is the single largest part of the draft. That is the same limit
   * {@link StoredFileDataSource} states from the other direction, and the same one
   * {@code EmailBoxStorage#addDraftAttachment} already pays for a file attached here.
   * <p>
   * <b>All or nothing, and false rather than a lie.</b> A part that cannot be found or
   * read stops the whole thing, and the caller is expected to refuse: the push leaves
   * the row unsynced (so the copy up there, stale but complete, keeps the files and the
   * composer says the draft lives only here), and the send fails outright. What has
   * already been brought over stays brought over — each part is independent, and a
   * retry picks up where this stopped.
   * <p>
   * <b>The copy is identified before it is read</b>, through the same
   * {@link #isOurDraftCopy} check the removal paths use. A UID is not a name, and
   * reading someone else's message here would not fail loudly — it would attach a
   * stranger's file to the user's draft and then send it.
   *
   * @param draft the draft's row, for the UID of its server copy and the Message-ID
   *          that copy is pinned with
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   * @return true when the draft carries no remote parts any more
   */
  private boolean materializeRemoteDraftParts(Email draft, String username, UserEmailSetting userEmailSetting) {
    String draftLocalId = draft.getDraftLocalId();
    long serverCopyUid = serverDraftCopyUid(draft);
    if (serverCopyUid <= 0) {
      // The row shows files whose only copy was in a message it no longer points at —
      // the draft was deleted from the other client between the sync that imported it
      // and now. Nothing can be recovered; the caller refuses rather than producing a
      // message without them.
      LOG.warn("The draft {} of user {} shows files that lived in a server copy it no longer has", draftLocalId, username);
      return false;
    }
    Store store = null;
    IMAPFolder draftsFolder = null;
    MailboxSyncState syncState = loadMailboxSyncState(username);
    String originalSyncStateJson = JsonUtils.toJsonString(syncState);
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      draftsFolder = resolveDraftsFolder(store, syncState);
      if (draftsFolder == null) {
        LOG.warn("No Drafts folder for user {}; the files of draft {} cannot be brought over", username, draftLocalId);
        return false;
      }
      // READ_ONLY: this reads a message and writes nothing to the mailbox. The copy is
      // removed later, by the push, and only once the new one is up.
      draftsFolder.open(Folder.READ_ONLY);
      Message serverCopy = draftsFolder.getMessageByUID(serverCopyUid);
      if (serverCopy == null || !isOurDraftCopy(serverCopy, draft.getMailHeaderId(), serverCopyUid, username)) {
        LOG.warn("The Drafts copy (uid {}) holding the files of draft {} of user {} is gone",
                 serverCopyUid,
                 draftLocalId,
                 username);
        return false;
      }
      for (EmailAttachment part : remoteDraftParts(emailBoxStorage.getDraftAttachments(username, draftLocalId))) {
        if (!materializeRemoteDraftPart(serverCopy, part, draftLocalId, username)) {
          return false;
        }
      }
      return true;
    } catch (Exception e) {
      LOG.warn("Could not bring over the files of draft {} of user {} from its copy in the Drafts folder",
               draftLocalId,
               username,
               e);
      return false;
    } finally {
      // Persisted like the push's, so the discovered Drafts folder name survives and the
      // upload that follows does not re-walk the whole folder list.
      saveMailboxSyncState(username, syncState, originalSyncStateJson);
      closeDraftsFolderQuietly(draftsFolder, false, username);
      closeQuietly(null, store, username);
    }
  }

  /**
   * One remote part, read out of the server copy and written into the file store.
   * <p>
   * Failure is answered rather than thrown, because the caller's decision is the same
   * for every way this can go wrong — the part is not there, its bytes cannot be read,
   * the file store wrote nothing — and it is "do not build a message that would be
   * missing it".
   *
   * @param serverCopy the draft's copy in the Drafts folder, already identified as ours
   * @param part the attachment row, carrying the MIME part path to read
   * @param draftLocalId the composer's handle on the draft
   * @param username the mailbox owner
   * @return true when the row now owns its bytes
   */
  private boolean materializeRemoteDraftPart(Message serverCopy, EmailAttachment part, String draftLocalId, String username) {
    try {
      BodyPart bodyPart = getPartByPath(serverCopy, part.getAttachmentRemoteId());
      if (bodyPart == null) {
        LOG.warn("The Drafts copy of draft {} of user {} has no part {} any more",
                 draftLocalId,
                 username,
                 part.getAttachmentRemoteId());
        return false;
      }
      if (emailBoxStorage.materializeDraftAttachment(username, draftLocalId, part.getId(), readPartBytes(bodyPart)) == null) {
        LOG.warn("The file {} of draft {} of user {} could not be written to the file store",
                 part.getName(),
                 draftLocalId,
                 username);
        return false;
      }
      return true;
    } catch (Exception e) {
      LOG.warn("The file {} of draft {} of user {} could not be read from its copy in the Drafts folder",
               part.getName(),
               draftLocalId,
               username,
               e);
      return false;
    }
  }

  /**
   * The bytes of one MIME part, decoded.
   * <p>
   * Extracted from the attachment download so that the download and the bringing-over
   * of a draft's parts read a part the same way — one buffer size, one decode, one
   * place for the next person to change.
   *
   * @param bodyPart the part to read
   * @return its content
   * @throws IOException if the part cannot be read
   * @throws MessagingException if the part's stream cannot be opened
   */
  private byte[] readPartBytes(BodyPart bodyPart) throws IOException, MessagingException {
    try (InputStream input = bodyPart.getInputStream(); ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[256 * 1024];
      int bytesRead;
      while ((bytesRead = input.read(buffer)) != -1) {
        output.write(buffer, 0, bytesRead);
      }
      return output.toByteArray();
    }
  }

  /**
   * The same question the upload asks, asked before a SEND, and answered by refusing
   * rather than by carrying on quietly.
   * <p>
   * The asymmetry with {@link #draftFilesAreAllReadable} is deliberate. An upload that
   * does not happen costs the user nothing they can see: their words are stored, and
   * the composer tells them the draft lives only here. A mail that goes out without
   * the file the sender attached cannot be discovered by them and cannot be taken
   * back, so it is the one case worth failing the whole operation for.
   * <p>
   * Asked BEFORE the row is claimed as {@link DraftState#SENDING} and before the
   * composer's text is written to it, so a refused send leaves the draft exactly as it
   * was — locally and on the server — with nothing claimed and nothing to undo.
   *
   * @param attachments the draft's stored files
   * @param username the mailbox owner, for the log
   * @throws IllegalStateException when one of them cannot be read
   */
  private void requireReadableDraftFiles(List<EmailAttachment> attachments, String username) {
    for (EmailAttachment attachment : attachments) {
      if (attachment.getFileId() == null || !emailBoxStorage.attachmentFileExists(attachment.getFileId())) {
        LOG.warn("The draft of user {} cannot be sent: its attachment {} has no file behind it any more",
                 username,
                 attachment.getName());
        throw new IllegalStateException("emailConnector.drafts.send.attachmentGone");
      }
    }
  }

  /**
   * The total size of the files already attached to a draft, for the send cap.
   *
   * @param username the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @return the total in bytes
   */
  private long storedAttachmentsSize(String username, String draftLocalId) {
    return emailBoxStorage.getDraftAttachments(username, draftLocalId)
                          .stream()
                          .mapToLong(attachment -> attachment.getSize() == null ? 0L : attachment.getSize())
                          .sum();
  }

  /**
   * The first stored revision of a draft: everything the composer sent, plus the
   * identity this service mints once and never changes — the local id, our own
   * Message-ID, the parent linkage, and the conversation the draft belongs to.
   * <p>
   * Threading is settled HERE rather than at send time, and that is the point of
   * the whole feature: a reply that only joins its conversation once it is sent is
   * a reply the author cannot see in context during the entire time they are
   * writing it. It resolves through the same {@link #computeThreadId} every synced
   * message goes through, so a draft merges and collapses threads exactly as real
   * mail does.
   *
   * @param draft the composed draft as the client sent it
   * @param draftLocalId the local id, minted by the caller
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding, for their own address
   * @return the row to store
   */
  private Email buildFirstDraftRevision(Email draft, String draftLocalId, String username, UserEmailSetting userEmailSetting) {
    Date now = new Date();
    Email toStore = new Email();
    toStore.setUserId(username);
    toStore.setFolder(MailFolder.DRAFTS);
    toStore.setDraftLocalId(draftLocalId);
    toStore.setDraftState(DraftState.LOCAL_ONLY);
    toStore.setDraftRevision(draft.getDraftRevision() != null ? draft.getDraftRevision() : 1L);
    toStore.setDraftUpdatedDate(now);
    toStore.setReceivedDate(now);
    // A draft is never unread and never "recent": it is the user's own text, and the
    // new-mail notification must not be able to see it.
    toStore.setRead(true);
    toStore.setRecent(false);
    toStore.setSubject(draft.getSubject());
    toStore.setContent(draft.getContent() != null ? draft.getContent() : new EmailContent(null, null, null));
    toStore.setTo(draft.getTo());
    toStore.setCc(draft.getCc());
    toStore.setBcc(draft.getBcc());
    toStore.setSender(ownSender(userEmailSetting));
    // Our own Message-ID, minted now and reused when the draft is finally sent. It is
    // also, on a server without UIDPLUS, the only handle we have on a message we just
    // appended -- so it cannot be deferred to send time even if threading did not
    // already require it. NOT EmailThreadingUtils#synthesizeMessageId: that mints an
    // @email-connector.local id for messages whose sender omitted one, and such an id
    // is a local placeholder that must never leave this box. This one goes out on the
    // wire, so it carries the user's own domain, as RFC 5322 §3.6.4 intends.
    toStore.setMailHeaderId(mintMessageId(userEmailSetting.getEmailAddress()));
    String parentMessageId = draft.getMailHeaderId();
    if (StringUtils.isNotBlank(parentMessageId)) {
      toStore.setInReplyTo(parentMessageId);
      String parentReferences = emailBoxStorage.getMailReferencesByMailHeaderId(parentMessageId, username);
      toStore.setMailReferences(EmailThreadingUtils.buildReferencesHeader(parentReferences, parentMessageId));
    }
    toStore.setThreadId(computeThreadId(username,
                                        toStore.getMailHeaderId(),
                                        0L,
                                        toStore.getInReplyTo(),
                                        toStore.getMailReferences(),
                                        null));
    return toStore;
  }

  /**
   * A later revision of an existing draft: the composer's new text over the stored
   * row's settled identity. The revision is taken from the client when it sent one
   * (the storage layer is what decides whether it is new enough to apply) and
   * otherwise stepped here, so a caller that does not track revisions still cannot
   * write a row that looks unchanged.
   *
   * @param draft the composed draft as the client sent it
   * @param stored the row as it currently stands
   * @return the row to store
   */
  private Email buildNextDraftRevision(Email draft, Email stored) {
    Date now = new Date();
    Email toStore = new Email();
    toStore.setUserId(stored.getUserId());
    toStore.setFolder(MailFolder.DRAFTS);
    toStore.setDraftLocalId(stored.getDraftLocalId());
    toStore.setDraftRevision(draft.getDraftRevision() != null ? draft.getDraftRevision()
                                                              : (stored.getDraftRevision() == null ? 1L
                                                                                                   : stored.getDraftRevision()
                                                                                                       + 1));
    toStore.setDraftUpdatedDate(now);
    // Still stamped on every save, though it no longer decides where the draft sits
    // in its conversation — that is settled from In-Reply-To now, precisely because
    // this line moved a reply to Monday's message below a mail that arrived tonight.
    // What still reads it: the Drafts folder listing and the conversation list, both
    // newest-first, where the draft the user was just writing belongs at the top; the
    // cached search's ordering; and the sync cleanup, which trims the oldest end of
    // the cache and would otherwise be free to evict a draft that has been open for
    // weeks. Recency is what this column means, and typing IS recent activity.
    toStore.setReceivedDate(now);
    toStore.setSubject(draft.getSubject());
    toStore.setContent(draft.getContent() != null ? draft.getContent() : new EmailContent(null, null, null));
    toStore.setTo(draft.getTo());
    toStore.setCc(draft.getCc());
    toStore.setBcc(draft.getBcc());
    // Whatever was on the server is now stale. A draft that had never reached it stays
    // LOCAL_ONLY, which is what the composer reads to tell the user their words live
    // only here. It is NOT what says whether there is a copy to replace up there — the
    // row's UID is, because a row can be left LOCAL_ONLY while carrying one (see
    // serverDraftCopyUid); reading the state for that left orphaned copies behind.
    toStore.setDraftState(DraftState.LOCAL_ONLY.equals(stored.getDraftState()) ? DraftState.LOCAL_ONLY : DraftState.DIRTY);
    return toStore;
  }

  /**
   * The UID of the copy this draft has on the mail server, or -1 when it has none —
   * the single answer the three paths that must remove a copy (a push replacing it,
   * a discard throwing it away, a send taking it with the mail) all ask.
   * <p>
   * It is the UID that answers it, and deliberately not {@link DraftState}. The
   * three paths used to ask the state instead, on the reading that
   * {@link DraftState#LOCAL_ONLY} means "never uploaded, so nothing up there to
   * remove". That reading is not true, and the case where it breaks is the ordinary
   * one it was written for: {@link EmailBoxStorage#markDraftUploaded} records the UID
   * of the copy it just appended but leaves the state alone when the row's revision
   * has moved — the user typed while the APPEND was in flight — precisely so the
   * revision guard cannot mark a row SYNCED over a sentence that never went up. That
   * leaves a LOCAL_ONLY row carrying a perfectly real UID, and asking the state would
   * decline to remove the copy it points at: the next push appends a second one, and
   * the user's other mail clients show the duplicate draft this whole design exists
   * to prevent.
   * <p>
   * Trusting the UID is safe in the other direction too, because it is only ever
   * written when we appended a copy, and cleared the moment we learn one is gone
   * (see {@link EmailBoxStorage#detachDraftFromServerCopy}, and
   * {@link #removePreviousDraftCopy} for the identity check that makes a UID we do
   * still hold safe to act on).
   *
   * @param draft the draft's row
   * @return the UID of its copy in the Drafts folder, or -1 when there is none
   */
  private long serverDraftCopyUid(Email draft) {
    Long mailRemoteId = draft == null ? null : draft.getMailRemoteId();
    return mailRemoteId != null && mailRemoteId > 0 ? mailRemoteId : -1;
  }

  /**
   * Uploads a draft to the mail server's Drafts folder by APPENDing the whole
   * message, and records the UID of the copy it created.
   * <p>
   * Append BEFORE anything else is removed, always — the previous copy goes only once
   * the new one is up and the row points at it. If this method appends and then
   * everything afterwards fails, the user sees the same draft twice in their other mail
   * client; if it were the other way round, they would see it nowhere. A visible
   * duplicate beats lost content, every time.
   * <p>
   * Getting the UID back is the awkward part. With UIDPLUS (RFC 4315) the server
   * returns it in the APPENDUID response and JavaMail hands it over as an
   * {@link AppendUID}. Without it there is no way to ask "what UID did the message I
   * just wrote get" — hence the fallback: search the folder for our own minted
   * Message-ID, which is a header we control precisely so that this is possible.
   * <p>
   * Failure here is never fatal to the save. The row stays whatever it was
   * ({@link DraftState#LOCAL_ONLY} or {@link DraftState#DIRTY}), the user's words
   * are already safely stored locally, and the next push tries again.
   *
   * @param saved the draft as just stored locally
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   * @return the draft, marked {@link DraftState#SYNCED} and carrying the new UID when
   *         the upload worked, unchanged when it did not
   */
  private Email uploadDraft(Email saved, String username, UserEmailSetting userEmailSetting) {
    Store store = null;
    IMAPFolder draftsFolder = null;
    // The UID, and not the state, is what says a copy is up there — see
    // serverDraftCopyUid for why reading the state here left copies behind.
    long previousUid = serverDraftCopyUid(saved);
    boolean expungeOnClose = false;
    MailboxSyncState syncState = loadMailboxSyncState(username);
    String originalSyncStateJson = JsonUtils.toJsonString(syncState);
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      draftsFolder = resolveDraftsFolder(store, syncState);
      if (draftsFolder == null) {
        // Not an error, and deliberately not a folder we create. The account simply has
        // no Drafts folder, so it gets no server-side drafts; the composer reads the
        // state back and tells the user their draft lives only here.
        LOG.info("No Drafts folder found for user {}; the draft stays local only", username);
        return saved;
      }
      MimeMessage message = buildDraftMessage(saved, userEmailSetting);
      draftsFolder.open(Folder.READ_WRITE);
      long appendedUid = appendDraftMessage(draftsFolder, message, saved.getMailHeaderId(), username);
      if (appendedUid <= 0) {
        LOG.warn("Appended the draft of user {} but could not resolve its UID; it stays unsynced", username);
        return saved;
      }
      // Bookkeeping, not an edit: this write carries no new text, only where the text
      // that is already stored now also lives. It goes through its own storage call for
      // exactly that reason — routed through the edit path it would look like a save
      // arriving at a revision the row has already reached, and be dropped.
      Email uploaded = emailBoxStorage.markDraftUploaded(username,
                                                         saved.getDraftLocalId(),
                                                         appendedUid,
                                                         saved.getDraftRevision());
      // Only now, with the new copy safely on the server and the row pointing at it,
      // does the previous one go. Never the other way round.
      if (previousUid > 0 && previousUid != appendedUid) {
        try {
          expungeOnClose = removePreviousDraftCopy(draftsFolder, previousUid, saved.getMailHeaderId(), username);
        } catch (Exception e) {
          // The new copy is up and the row points at it, so the user's draft is
          // correct. What is left behind is a duplicate — the failure we deliberately
          // chose over losing content — and the next push tries to remove it again.
          LOG.warn("Could not remove the previous draft copy (uid {}) of user {}", previousUid, username, e);
        }
      }
      return uploaded != null ? uploaded : saved;
    } catch (Exception e) {
      LOG.warn("Could not upload the draft of user {} to the Drafts folder; it stays saved locally", username, e);
      return saved;
    } finally {
      // Persisted so the discovered Drafts folder name survives: without it every push
      // re-walks the whole folder list, and pushes happen far more often than syncs.
      saveMailboxSyncState(username, syncState, originalSyncStateJson);
      closeDraftsFolderQuietly(draftsFolder, expungeOnClose, username);
      closeQuietly(null, store, username);
    }
  }

  /**
   * Removes the copy of a draft that the APPEND just superseded, so the user's other
   * mail clients show one draft rather than a growing pile of them.
   * <p>
   * Two ways to do it, and the difference matters. With UIDPLUS (RFC 4315) the
   * message can be expunged BY UID — precisely the one message, nothing else. JavaMail
   * exposes that as {@code expunge(Message[])} and refuses it when the server does not
   * advertise the extension.
   * <p>
   * Without UIDPLUS the only expunge IMAP offers is the whole-folder one, reached here
   * through {@code close(true)}. Its limitation is worth stating plainly rather than
   * discovering later: it removes EVERY message in the Drafts folder currently flagged
   * {@code \Deleted}, including ones some other client of the user's flagged and has
   * not yet expunged itself. We do it anyway, for two reasons. The blast radius is one
   * folder, and it is the folder whose entire purpose is to hold superseded copies of
   * unfinished messages. And the alternative — leaving the flag set and never
   * expunging — is not "safe", it is a Drafts folder that fills up with struck-through
   * copies of the same half-written mail, which is the visible failure this step exists
   * to prevent.
   * <p>
   * A previous copy that is simply not there any more (another client removed it) is
   * not a failure: there is nothing left to do, and saying so at debug level is enough.
   * Neither is a UID that now holds somebody else's message — see
   * {@link #isOurDraftCopy} for why that is checked before anything is flagged.
   * <p>
   * Throws rather than swallowing, because the two callers want opposite things from a
   * failure: the upload has already put the new copy up and must not fail over a
   * leftover duplicate, while the discard must not claim the draft is gone when it is
   * not.
   *
   * @param draftsFolder the open Drafts folder
   * @param previousUid the UID of the copy being superseded
   * @param expectedMessageId the Message-ID the row says that copy carries
   * @param username the mailbox owner
   * @return true when the caller must close the folder with expunge, because the
   *         server offered no way to remove just this one message
   * @throws MessagingException if the message could not be flagged for removal
   */
  private boolean removePreviousDraftCopy(IMAPFolder draftsFolder,
                                          long previousUid,
                                          String expectedMessageId,
                                          String username) throws MessagingException {
    Message previous = draftsFolder.getMessageByUID(previousUid);
    if (previous == null) {
      LOG.debug("The previous draft copy of user {} (uid {}) is already gone", username, previousUid);
      return false;
    }
    if (!isOurDraftCopy(previous, expectedMessageId, previousUid, username)) {
      return false;
    }
    previous.setFlag(Flags.Flag.DELETED, true);
    try {
      // UID EXPUNGE: this message and no other. JavaMail throws when the server has
      // not advertised UIDPLUS, which is exactly the signal we want.
      draftsFolder.expunge(new Message[] { previous });
      return false;
    } catch (MessagingException e) {
      LOG.debug("No UID EXPUNGE for user {}; the previous draft copy goes on close instead", username, e);
      return true;
    }
  }

  /**
   * Whether the message sitting at a remembered UID is really the draft copy we put
   * there — asked immediately before it is flagged {@code \Deleted}, and the only
   * thing standing between a remembered number and somebody else's mail.
   * <p>
   * A UID is not a name. It identifies a message only within one UIDVALIDITY of one
   * folder, so a mailbox that was rebuilt, restored from backup or migrated hands the
   * same numbers out again to entirely different messages. The sync notices that (every
   * UID vanishing at once) and clears the ones it holds, but it notices on its own
   * schedule, and a push can run first. Message-ID equality is what settles it, exactly
   * as it does for the stray-copy janitor: it is the only identity a message really
   * carries, and every copy we append is pinned to the one on the row.
   * <p>
   * Both copies of a draft carry that same pinned Message-ID, which is not a problem
   * here and is the reason the caller compares UIDs first: the copy just appended is
   * excluded by number before identity is ever asked about.
   * <p>
   * A row that carries no Message-ID at all cannot be checked, and is let through. That
   * is not a hole so much as the older, narrower trust: the UID was written by our own
   * append and no other path invents one. Refusing there would guarantee the leftover
   * copy this method exists to remove, in exchange for a doubt we have no way to
   * resolve.
   *
   * @param message the message found at the remembered UID
   * @param expectedMessageId the Message-ID the row says its copy carries
   * @param uid the remembered UID, for the log
   * @param username the mailbox owner
   * @return true when the message is the draft copy the row is pointing at
   * @throws MessagingException if the message's headers cannot be read
   */
  private boolean isOurDraftCopy(Message message,
                                 String expectedMessageId,
                                 long uid,
                                 String username) throws MessagingException {
    if (StringUtils.isBlank(expectedMessageId)) {
      return true;
    }
    String[] messageIds = message.getHeader("Message-ID");
    String actualMessageId = messageIds != null && messageIds.length > 0 ? StringUtils.trim(messageIds[0]) : null;
    if (StringUtils.equals(actualMessageId, StringUtils.trim(expectedMessageId))) {
      return true;
    }
    // Warn rather than debug: nothing was lost, but the mailbox has renumbered itself
    // under us and that is worth seeing in a log next to whatever else went odd that day.
    LOG.warn("The Drafts message at uid {} of user {} is {} and not the draft copy {} the row remembers; leaving it alone",
             uid,
             username,
             actualMessageId,
             expectedMessageId);
    return false;
  }

  /**
   * Removes one copy of a draft from the mail server's Drafts folder, on its own
   * connection — the discard path's counterpart to the removal the upload does
   * inline while it already has the folder open.
   *
   * @param mailRemoteId the UID of the copy to remove
   * @param expectedMessageId the Message-ID the row says that copy carries
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   * @return true when the copy is gone (or was already gone, or was never ours, or
   *         there is no Drafts folder to hold one), false when the server still has it
   */
  private boolean removeServerDraftCopy(long mailRemoteId,
                                        String expectedMessageId,
                                        String username,
                                        UserEmailSetting userEmailSetting) {
    Store store = null;
    IMAPFolder draftsFolder = null;
    boolean expungeOnClose = false;
    MailboxSyncState syncState = loadMailboxSyncState(username);
    String originalSyncStateJson = JsonUtils.toJsonString(syncState);
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      draftsFolder = resolveDraftsFolder(store, syncState);
      if (draftsFolder == null) {
        // No Drafts folder means no copy up there to disagree with the local row.
        return true;
      }
      draftsFolder.open(Folder.READ_WRITE);
      expungeOnClose = removePreviousDraftCopy(draftsFolder, mailRemoteId, expectedMessageId, username);
      return true;
    } catch (Exception e) {
      LOG.warn("Could not remove the server copy (uid {}) of a discarded draft of user {}", mailRemoteId, username, e);
      return false;
    } finally {
      saveMailboxSyncState(username, syncState, originalSyncStateJson);
      closeDraftsFolderQuietly(draftsFolder, expungeOnClose, username);
      closeQuietly(null, store, username);
    }
  }

  /**
   * Closes the Drafts folder, expunging it only when {@link #removePreviousDraftCopy}
   * had no way to remove one message on its own — see there for what that costs.
   *
   * @param draftsFolder the folder to close, may be null or already closed
   * @param expunge whether the close must expunge the folder
   * @param username the mailbox owner, for the log
   */
  private void closeDraftsFolderQuietly(IMAPFolder draftsFolder, boolean expunge, String username) {
    if (draftsFolder == null || !draftsFolder.isOpen()) {
      return;
    }
    try {
      draftsFolder.close(expunge);
    } catch (MessagingException e) {
      LOG.warn("Error when closing the Drafts folder of user {}", username, e);
    }
  }

  /**
   * APPENDs a draft and answers with the UID the server gave it.
   * <p>
   * {@code appendUIDMessages} is JavaMail's UIDPLUS-aware append: on a server that
   * advertises UIDPLUS it returns the APPENDUID the server reported, and on one that
   * does not it appends fine and returns nulls. That is what the Message-ID search
   * underneath is for — our own minted id, searched for in the folder we just wrote
   * to. It is not free (a SEARCH round-trip) but it only runs on servers that left us
   * no alternative.
   *
   * @param draftsFolder the open Drafts folder
   * @param message the message to append
   * @param messageId our own minted Message-ID, the fallback search key
   * @param username the mailbox owner, for the log
   * @return the UID of the appended copy, or -1 when it could not be established
   * @throws MessagingException if the append itself fails
   */
  private long appendDraftMessage(IMAPFolder draftsFolder,
                                  MimeMessage message,
                                  String messageId,
                                  String username) throws MessagingException {
    AppendUID[] appendUids = draftsFolder.appendUIDMessages(new Message[] { message });
    if (appendUids != null && appendUids.length > 0 && appendUids[0] != null) {
      return appendUids[0].uid;
    }
    LOG.debug("Server gave no APPENDUID for the draft of user {}; falling back to a Message-ID search", username);
    if (StringUtils.isBlank(messageId)) {
      return -1;
    }
    Message[] found = draftsFolder.search(new HeaderTerm("Message-ID", messageId));
    if (found == null || found.length == 0) {
      return -1;
    }
    return draftsFolder.getUID(found[found.length - 1]);
  }

  /**
   * Builds the MIME message a draft is appended as: the user's own address as From,
   * the recipients they have typed so far, the body, and the threading headers that
   * put it in its conversation.
   * <p>
   * The Message-ID is pinned through {@link PinnedMessageIdMimeMessage} rather than
   * set as a header, and that is load-bearing rather than stylistic:
   * {@code saveChanges} regenerates the Message-ID from scratch every time it runs,
   * and {@code MimeMessage#writeTo} — which is what an APPEND ends up calling —
   * invokes it when the message has not been saved yet. A message appended under a
   * different id from the one we recorded would leave the fallback UID search finding
   * nothing and the sent message not matching the draft, splitting the conversation.
   * <p>
   * Stamping the header after an explicit {@code saveChanges} also works HERE, and is
   * what this method did when it was written; it stopped being good enough the moment
   * the same problem turned up in the send path, where {@code Transport.send} calls
   * {@code saveChanges} itself and undoes any such stamp. One mechanism, used by both
   * paths, is worth more than two that differ only in how far they can be trusted.
   * <p>
   * The message is flagged {@code \Draft} (which is what makes other mail clients open
   * it in a composer instead of a reader) and {@code \Seen} (the author has, by
   * definition, read their own unfinished sentence).
   * <p>
   * <b>The files.</b> A draft carrying attachments is a {@code multipart/mixed}: the
   * HTML body first, then one part per stored file, built by the very same
   * {@link #attachmentBodyPart} the send path uses. Two places writing MIME parts
   * their own way would drift on the things nobody notices until a recipient does —
   * how a non-ASCII filename is encoded, whether the disposition is set, what content
   * type goes out — and the copy in the Drafts folder is supposed to be the message
   * that will be sent.
   * <p>
   * Each part reads its bytes through {@link StoredFileDataSource}, which RE-OPENS the
   * file every time it is asked for a stream. That is not defensive, it is required:
   * {@code IMAPFolder}'s append computes the literal's size by writing the message
   * once and then writes it again for anything larger than its buffer, so a part
   * backed by a one-shot stream arrives empty on the server — the exact shape of "the
   * attachment is there and it is 0 bytes". It also means the peak memory of an append
   * is one file rather than all of them.
   * <p>
   * The caller has already established that every one of those files can be read (see
   * {@link #draftFilesAreAllReadable}); a message must never be appended with a part
   * quietly left out.
   *
   * @param draft the stored draft
   * @param userEmailSetting the user's connector binding, for their own address
   * @return the message to append
   * @throws MessagingException if the message cannot be built
   * @throws UnsupportedEncodingException if the user's display name cannot be encoded
   */
  private MimeMessage buildDraftMessage(Email draft,
                                        UserEmailSetting userEmailSetting) throws MessagingException,
                                                                          UnsupportedEncodingException {
    String emailAddress = userEmailSetting.getEmailAddress();
    // A draft is never transmitted, so this session needs no transport properties at
    // all -- it exists only to give the MimeMessage a context to be built in.
    MimeMessage message = new PinnedMessageIdMimeMessage(Session.getInstance(new Properties()), draft.getMailHeaderId());
    Profile userProfile = EmailConnectorUtils.getUserProfileByEmail(emailAddress);
    message.setFrom(new InternetAddress(emailAddress, userProfile != null ? userProfile.getFullName() : null));
    setDraftRecipients(message, Message.RecipientType.TO, draft.getTo());
    setDraftRecipients(message, Message.RecipientType.CC, draft.getCc());
    setDraftRecipients(message, Message.RecipientType.BCC, draft.getBcc());
    message.setSubject(draft.getSubject());
    message.setSentDate(draft.getDraftUpdatedDate() != null ? draft.getDraftUpdatedDate() : new Date());
    String body = draft.getContent() != null && draft.getContent().getBody() != null ? draft.getContent().getBody() : "";
    List<EmailAttachment> attachments = storedAttachmentsOf(draft);
    if (attachments.isEmpty()) {
      message.setContent(body, "text/html; charset=UTF-8");
    } else {
      MimeMultipart multipart = new MimeMultipart("mixed");
      MimeBodyPart htmlPart = new MimeBodyPart();
      htmlPart.setContent(body, "text/html; charset=UTF-8");
      multipart.addBodyPart(htmlPart);
      addStoredAttachmentParts(multipart, attachments);
      message.setContent(multipart);
    }
    if (StringUtils.isNotBlank(draft.getInReplyTo())) {
      message.setHeader("In-Reply-To", draft.getInReplyTo());
    }
    if (StringUtils.isNotBlank(draft.getMailReferences())) {
      message.setHeader("References", draft.getMailReferences());
    }
    message.setFlag(Flags.Flag.DRAFT, true);
    message.setFlag(Flags.Flag.SEEN, true);
    return message;
  }

  /**
   * Sets one recipient field of a draft message, tolerating the half-typed state a
   * draft is normally in — a blank list, or entries with no address yet, simply
   * leave the header off rather than failing the save.
   *
   * @param message the message being built
   * @param type the recipient field to set
   * @param recipients the recipients as stored, may be null or partly blank
   * @throws MessagingException if the addresses cannot be parsed
   */
  private void setDraftRecipients(MimeMessage message,
                                  Message.RecipientType type,
                                  List<EmailRecipient> recipients) throws MessagingException {
    if (CollectionUtils.isEmpty(recipients)) {
      return;
    }
    String addresses = recipients.stream()
                                 .map(EmailRecipient::getAddress)
                                 .filter(Objects::nonNull)
                                 .filter(address -> !address.isBlank())
                                 .collect(Collectors.joining(","));
    if (StringUtils.isNotBlank(addresses)) {
      message.setRecipients(type, InternetAddress.parse(addresses));
    }
  }

  /**
   * The user themselves, as the sender of their own draft. Stored on the row because
   * the reader renders every message from its sender, and because
   * {@code EmailBoxStorage} splits that column on a comma and would fail on a blank
   * one.
   *
   * @param userEmailSetting the user's connector binding
   * @return the sender to stamp on a draft row
   */
  private EmailSender ownSender(UserEmailSetting userEmailSetting) {
    String emailAddress = userEmailSetting.getEmailAddress();
    Profile userProfile = EmailConnectorUtils.getUserProfileByEmail(emailAddress);
    String name = userProfile != null && StringUtils.isNotBlank(userProfile.getFullName()) ? userProfile.getFullName()
                                                                                           : emailAddress;
    return new EmailSender(name, emailAddress, null, null);
  }

  /**
   * A Message-ID of our own for a draft, minted at its first save and reused when it
   * is eventually sent — so the sent message IS the draft, as far as every other mail
   * client's threading is concerned.
   * <p>
   * The domain is the user's own, taken from their address, because that is what RFC
   * 5322 §3.6.4 asks for and what makes the id look like what it is: a real message
   * from a real mailbox. It is emphatically not the {@code @email-connector.local}
   * form {@code EmailThreadingUtils#synthesizeMessageId} produces — that one exists to
   * give a LOCAL anchor to a message whose sender omitted an id, and it must never
   * leave this box.
   *
   * @param emailAddress the user's own address
   * @return an angle-bracketed, globally unique Message-ID
   */
  private String mintMessageId(String emailAddress) {
    String domain = StringUtils.substringAfterLast(StringUtils.defaultString(emailAddress), "@");
    return "<" + UUID.randomUUID() + "@" + (StringUtils.isNotBlank(domain) ? domain : "email-connector") + ">";
  }

  /**
   * The key a draft's lock lives under. Scoped by user as well as by draft id
   * because the id is minted per draft, not per installation, and two users must
   * never be able to serialize each other by guessing one.
   *
   * @param username the mailbox owner
   * @param draftLocalId the composer's handle on the draft
   * @return the lock key
   */
  private String draftLockKey(String username, String draftLocalId) {
    return username + "/" + draftLocalId;
  }

  /**
   * Whether the server-side half of drafts is switched on — see
   * {@link #DRAFTS_SERVER_ENABLED_PROPERTY}. Read on every call rather than cached,
   * so an administrator can withdraw it without a restart.
   *
   * @return true when drafts may be uploaded to the mail server
   */
  private boolean isServerDraftsEnabled() {
    return Boolean.parseBoolean(System.getProperty(DRAFTS_SERVER_ENABLED_PROPERTY, "true"));
  }

  /**
   * Stamps a composed message with the RFC 5322 headers that put it back into its
   * conversation. Extracted from {@link #sendEmail} unchanged so that the draft
   * upload path can write the SAME headers — a draft has to carry them from its
   * first save, not gain them at send time, or the draft sits outside the thread it
   * is a reply to for its entire life, which is precisely the moment the user is
   * looking at it.
   * <p>
   * Note what {@code mailHeaderId} means on a COMPOSED email: it is the PARENT's
   * Message-ID, not the composed message's own. (On a cached, synced email the same
   * field means the message's own id.) The compose drawer sets it when replying and
   * leaves it empty for a new mail, which is exactly the "is this a reply" test
   * below.
   *
   * @param message the message being composed
   * @param email the composed email, whose {@code mailHeaderId} carries the parent's
   *          Message-ID when this is a reply
   * @param username the mailbox owner, to look the parent's chain up in the cache
   * @throws MessagingException if a header cannot be set
   */
  private void applyThreadingHeaders(Message message, Email email, String username) throws MessagingException {
    if (StringUtils.isEmpty(email.getMailHeaderId())) {
      return;
    }
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
    if (CollectionUtils.isEmpty(email.getAttachments()) && CollectionUtils.isEmpty(email.getStoredAttachments())) {
      message.setContent(bodyHtml, "text/html; charset=UTF-8");
      return;
    }
    UploadService uploadService = CommonsUtils.getService(UploadService.class);
    MimeMultipart multipart = new MimeMultipart("mixed");
    MimeBodyPart htmlPart = new MimeBodyPart();
    htmlPart.setContent(bodyHtml, "text/html; charset=UTF-8");
    multipart.addBodyPart(htmlPart);
    // The draft's own stored files first, then this session's uploads, which is the
    // order the user attached them in — the stored ones are by definition older. Their
    // sizes count towards the same cap: what matters to the receiving server is the
    // size of the message, not where each part came from.
    long totalSize = applyStoredAttachments(multipart, email);
    if (totalSize > MAX_OUTGOING_ATTACHMENTS_SIZE) {
      throw new IllegalStateException("emailConnector.mailBox.newEmail.attach.maxSize.error");
    }
    for (EmailOutgoingAttachment attachment : email.getAttachments() == null ? List.<EmailOutgoingAttachment> of()
                                                                            : email.getAttachments()) {
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
      String fileName = StringUtils.isNotBlank(attachment.getName()) ? attachment.getName() : uploadResource.getFileName();
      multipart.addBodyPart(attachmentBodyPart(new FileDataSource(file), fileName, attachment.getMimeType()));
    }
    message.setContent(multipart);
  }

  /**
   * One file, as a body part of an outgoing message — the single place this add-on
   * writes an attachment part, whatever the bytes come from and wherever the message
   * is going.
   * <p>
   * Extracted rather than duplicated because the three lines below are exactly the
   * ones that drift. A message being SENT and a draft being APPENDED are supposed to
   * be the same message, so a filename encoded one way here and another way there, or
   * a disposition set on one path and forgotten on the other, is a difference the user
   * discovers as "the attachment has a mangled name on my phone" or "it shows inline
   * instead of as a file". One builder, one answer.
   * <p>
   * The filename is RFC 2047 encoded, with the raw name as the fallback: a name that
   * cannot be encoded is still better sent than not sent. The content type is stamped
   * only when the caller has one — for an upload it may be blank, and the
   * {@code DataHandler} then infers it from the file, which is a better guess than
   * {@code application/octet-stream}.
   *
   * @param dataSource where the bytes come from; for anything read back from the file
   *          store this must RE-OPEN on every call (see {@link StoredFileDataSource})
   * @param fileName the name to send the file under
   * @param mimeType its content type, or blank to let the data handler decide
   * @return the body part, ready to add to a multipart
   * @throws MessagingException if the part cannot be built
   */
  private MimeBodyPart attachmentBodyPart(DataSource dataSource,
                                          String fileName,
                                          String mimeType) throws MessagingException {
    MimeBodyPart attachmentPart = new MimeBodyPart();
    attachmentPart.setDataHandler(new DataHandler(dataSource));
    String name = StringUtils.defaultIfBlank(fileName, DEFAULT_ATTACHMENT_NAME);
    try {
      attachmentPart.setFileName(MimeUtility.encodeText(name, "UTF-8", null));
    } catch (UnsupportedEncodingException e) {
      attachmentPart.setFileName(name);
    }
    attachmentPart.setDisposition(Part.ATTACHMENT);
    if (StringUtils.isNotBlank(mimeType)) {
      attachmentPart.setHeader("Content-Type", mimeType);
    }
    return attachmentPart;
  }

  /**
   * Adds a draft's own stored files to a message being assembled — the send's and the
   * draft upload's shared step.
   * <p>
   * Nothing is read here. Each part carries a {@link StoredFileDataSource} that opens
   * the file when the message is written, and again if it is written a second time,
   * which is what an IMAP APPEND does to anything past its buffer. The size counted
   * for the cap is the one recorded on the row when the file was stored, so counting
   * it costs no read at all.
   *
   * @param multipart the message body being assembled
   * @param attachments the draft's stored files, already filtered to the ones that
   *          have bytes on this side
   * @return the total size of the parts added, in bytes
   * @throws MessagingException if a body part cannot be built
   */
  private long addStoredAttachmentParts(MimeMultipart multipart,
                                        List<EmailAttachment> attachments) throws MessagingException {
    long totalSize = 0;
    for (EmailAttachment attachment : attachments) {
      String mimeType = StringUtils.defaultIfBlank(attachment.getMimeType(), DEFAULT_ATTACHMENT_MIME_TYPE);
      String fileName = StringUtils.defaultIfBlank(attachment.getName(), DEFAULT_ATTACHMENT_NAME);
      totalSize += attachment.getSize() == null ? 0L : attachment.getSize();
      multipart.addBodyPart(attachmentBodyPart(new StoredFileDataSource(emailBoxStorage,
                                                                       attachment.getFileId(),
                                                                       fileName,
                                                                       mimeType),
                                               fileName,
                                               mimeType));
    }
    return totalSize;
  }

  /**
   * One file of the platform's file store, seen as a MIME part's source of bytes —
   * and the whole point of it is that it RE-OPENS.
   * <p>
   * {@code IMAPFolder}'s append does not write a message once. It writes it to measure
   * the IMAP literal's byte count, keeps what fits in its buffer, and writes the
   * message a SECOND time when it does not fit — and {@code Transport.send} can
   * re-write a message too. A part backed by a stream that has already been consumed
   * contributes nothing the second time, which is how an attachment arrives on the
   * server present, correctly named, and zero bytes long. Opening on every call is
   * what makes that impossible.
   * <p>
   * It is also what keeps the memory cost of an append down to one file at a time
   * rather than every file at once: the bytes are fetched while the part is being
   * written and are garbage the moment it has been. That is as close to streaming as
   * the platform's file service allows — {@code FileService#getFile} answers with a
   * {@code FileItem} that has already read the file into a byte array, so there is no
   * streaming read to ask for. Nothing here holds one longer than the write.
   * <p>
   * A file that has gone answers with an {@code IOException} rather than an empty
   * stream, so the write fails and the message is not delivered half-assembled. Both
   * callers check first (see {@link #draftFilesAreAllReadable} and
   * {@link #requireReadableDraftFiles}); this is the backstop for the file that
   * disappears between the check and the write.
   */
  private static final class StoredFileDataSource implements DataSource {

    private final EmailBoxStorage storage;

    private final Long            fileId;

    private final String          name;

    private final String          contentType;

    /**
     * @param storage the storage that can read the file back
     * @param fileId the platform file id holding the bytes
     * @param name the file name the part is sent under
     * @param contentType the part's content type
     */
    private StoredFileDataSource(EmailBoxStorage storage, Long fileId, String name, String contentType) {
      this.storage = storage;
      this.fileId = fileId;
      this.name = name;
      this.contentType = contentType;
    }

    /**
     * A fresh stream over the file, read out of the store on every call.
     *
     * @return the file's bytes
     * @throws IOException when the file is no longer there to be read
     */
    @Override
    public InputStream getInputStream() throws IOException {
      FileItem fileItem = storage.getAttachmentFileItem(fileId);
      InputStream stream = fileItem == null ? null : fileItem.getAsStream();
      if (stream == null) {
        throw new IOException(String.format("The stored attachment %s (file %s) could not be read", name, fileId));
      }
      return stream;
    }

    /**
     * Never: a part of an outgoing message is read from, not written to.
     *
     * @return nothing
     * @throws IOException always
     */
    @Override
    public OutputStream getOutputStream() throws IOException {
      throw new IOException("A stored attachment is read-only");
    }

    /**
     * @return the part's content type
     */
    @Override
    public String getContentType() {
      return contentType;
    }

    /**
     * @return the file's name
     */
    @Override
    public String getName() {
      return name;
    }
  }

  /**
   * Adds a draft's stored files to the message being sent, reading each one's bytes
   * back out of the platform's file service.
   * <p>
   * This is what makes a draft resumed after a restart send what it shows. Everything
   * else on the send path works from commons upload ids, which only exist for files
   * attached during the browser session that is still open; the files a draft has been
   * carrying since yesterday have none, and a message built from upload ids alone goes
   * out with the text and without them.
   * <p>
   * A file that cannot be read is a hard failure rather than a part quietly left out.
   * Sending a mail whose attachments are missing, to someone who is expecting them, is
   * not something the sender can discover afterwards or take back — the branch's own
   * rule for the mail server holds here too: refuse rather than deliver something that
   * looks complete and is not. {@link #sendDraft} asks that question before it claims
   * the row; {@link StoredFileDataSource} is what answers it again at the moment the
   * bytes are actually written.
   *
   * @param multipart the message body being assembled
   * @param email the draft being sent
   * @return the total size of the parts added, for the cap the caller keeps counting
   * @throws MessagingException if a body part cannot be built
   */
  private long applyStoredAttachments(MimeMultipart multipart, Email email) throws MessagingException {
    if (CollectionUtils.isEmpty(email.getStoredAttachments())) {
      return 0L;
    }
    return addStoredAttachmentParts(multipart,
                                    email.getStoredAttachments()
                                         .stream()
                                         .filter(attachment -> attachment != null && attachment.getFileId() != null)
                                         .toList());
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
                                                firstHeader(message, "X-Original-Sender"),
                                                // \Flagged comes off the same prefetched FLAGS as SEEN
                                                // (buildSyncFetchProfile), so this read costs no round-trip.
                                                message.isSet(Flags.Flag.FLAGGED),
                                                // The four draft columns. A message pulled from the server
                                                // is never a draft as far as this path is concerned: even
                                                // once the Drafts folder joins the sync, a draft that
                                                // arrives here is one another client wrote, and it takes
                                                // its local id and state from the draft sync path, not
                                                // from this one.
                                                null,
                                                null,
                                                null,
                                                null,
                                                // And no stored attachments: that field is the send path's
                                                // way of carrying a draft's own files, and this row's
                                                // attachments are parts of a message on the server.
                                                null));
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
    return searchEmails(username, query, from, null, unreadOnly, false, sinceDays, folder, limit);
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
   * @param to text matched against the To or Cc recipients only, may be blank —
   *          the way to pin a person in the SENT folder, where every sender is
   *          the user themselves
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
                                            String to,
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
    Date since = sinceDays == null ? null : new Date(System.currentTimeMillis() - sinceDays * 86400000L);
    SearchTerm searchTerm = buildEmailSearchTerm(query, from, to, unreadOnly, favoritesOnly, since);
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
    return buildEmailSearchTerm(query, from, null, unreadOnly, false, since);
  }

  /**
   * The same term, with the favorites narrowing the unified search's filter asks
   * for and the recipient criterion the contact card's correspondence needs: a
   * SENT-folder search cannot pin a person by sender (the user wrote everything
   * in there), so {@code to} matches the To or Cc recipients instead. Bcc is
   * deliberately out: a person the user deliberately hid from the recipients
   * must not surface as visible correspondence with them.
   *
   * @param query free text matched against the subject or the sender
   * @param from text matched against the sender only
   * @param to text matched against the To or Cc recipients only
   * @param unreadOnly when {@code true}, only unread messages match
   * @param favoritesOnly when {@code true}, only messages carrying \Flagged match
   * @param since only messages received after this date match
   * @return the IMAP search term, or {@code null} when no criterion was given
   */
  static SearchTerm buildEmailSearchTerm(String query,
                                         String from,
                                         String to,
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
    if (StringUtils.isNotBlank(to)) {
      terms.add(new OrTerm(new RecipientStringTerm(Message.RecipientType.TO, to.trim()),
                           new RecipientStringTerm(Message.RecipientType.CC, to.trim())));
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
   * one more LIST); ARCHIVE uses the archive DESTINATION lookup, which on Gmail is
   * the {@code \All} "All Mail" superset — exactly where archived mail lives, and
   * the one folder the bulk sync deliberately never covers.
   *
   * @param store the connected store (the search's own, never the sync's)
   * @param folder the {@link MailFolder} key, already validated searchable
   * @param username the mailbox owner, to load the remembered folder names
   * @return the remote folder, or null when the mailbox has no such folder
   * @throws MessagingException if the folder list cannot be read
   */
  private Folder resolveSearchFolder(Store store, String folder, String username) throws MessagingException {
    if (MailFolder.INBOX.equals(folder)) {
      return store.getFolder("INBOX");
    }
    if (MailFolder.SENT.equals(folder)) {
      return resolveSentFolder(store, loadMailboxSyncState(username));
    }
    return findArchiveFolder(store);
  }

  /**
   * The remote folder a cached row of a given {@link MailFolder} came from — the
   * inverse of the discriminator the sync writes, and what any read that has to go
   * back to the server for one message needs.
   * <p>
   * Each arm goes through the SAME resolver the rows were cached by, which is the
   * whole point of having this in one place rather than a folder name at each call
   * site: ARCHIVE takes the syncable {@code \Archive} folder, while ALL_MAIL takes
   * Gmail's {@code \All} superset, and the two are deliberately different folders
   * (see {@link #findSyncableArchiveFolder}). Resolving ARCHIVE through
   * {@link #findArchiveFolder} instead — which answers {@code \All} on Gmail —
   * would look right and read the wrong mailbox, under UIDs that belong to another
   * one.
   * <p>
   * The sync state is loaded but deliberately NOT written back, the same rule
   * {@link #resolveSearchFolder} follows: a read must never save the state a running
   * sync is about to save, so a rediscovery here costs one extra {@code LIST} and
   * nothing else.
   *
   * @param store the connected store (this read's own)
   * @param folder the {@link MailFolder} discriminator the row carries
   * @param username the mailbox owner, to load the remembered folder names
   * @return the remote folder, or null when the mailbox has no such folder
   * @throws MessagingException if the folder list cannot be read
   */
  private Folder resolveCachedFolder(Store store, String folder, String username) throws MessagingException {
    if (StringUtils.isBlank(folder) || MailFolder.INBOX.equals(folder)) {
      return store.getFolder("INBOX");
    }
    MailboxSyncState syncState = loadMailboxSyncState(username);
    if (MailFolder.SENT.equals(folder)) {
      return resolveSentFolder(store, syncState);
    }
    if (MailFolder.ARCHIVE.equals(folder)) {
      return resolveArchiveFolder(store, syncState);
    }
    if (MailFolder.DRAFTS.equals(folder)) {
      return resolveDraftsFolder(store, syncState);
    }
    if (MailFolder.ALL_MAIL.equals(folder)) {
      return findAllMailFolder(store);
    }
    // An unknown discriminator is a caller passing something this schema never wrote.
    // Answering INBOX would silently read the wrong mailbox; answering nothing lets
    // the caller say so.
    return null;
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

  /**
   * The Drafts folder's reconcile: what the sync does about drafts that changed
   * somewhere other than here.
   * <p>
   * There is no reliable cross-client identity for a draft, and this method is
   * written around that fact rather than around a wish that it were otherwise. Most
   * mail clients mint a FRESH Message-ID every time they save a draft, so the same
   * half-written reply, saved twice from a phone, is two unrelated messages as far
   * as any header can tell. The only identity that survives is the one we control:
   * the UID of the copy WE appended, remembered on the row.
   * <p>
   * From that, three rules, in this order:
   * <ul>
   * <li><b>A Drafts message whose UID we do not know becomes a NEW local draft.</b>
   * Our own row, if the same reply is also being written here, is untouched. The
   * user may briefly see two drafts of one reply, and that is the intended outcome:
   * merging them by heuristic — same subject, same In-Reply-To, same recipients —
   * is EXPLICITLY REJECTED, because every such merge silently discards one side's
   * writing, which is the exact failure this whole design exists to prevent. Two
   * visible drafts is a situation the user can resolve in a second; a sentence
   * quietly overwritten is one they cannot even detect.</li>
   * <li><b>A row whose UID is gone from the server, with nothing unsaved, is
   * deleted.</b> Not here — {@link #cleanupObsoleteEmails} already does exactly
   * that to any row the server no longer has, and a SYNCED draft is deliberately
   * not protected from it. The user deleted the draft on their phone; that is what
   * they meant.</li>
   * <li><b>A row whose UID is gone but which carries unsaved text is KEPT</b>, and
   * put back to {@link DraftState#LOCAL_ONLY} so the next save re-uploads it from
   * scratch — see {@link #detachDraftDeletedElsewhere}.</li>
   * </ul>
   * A row a send has claimed ({@link DraftState#SENDING}) is not touched by any of
   * the three: the send is taking it apart itself, in an order chosen for what
   * happens when a step fails.
   *
   * @param uidFolder the open Drafts folder, to resolve each message's UID
   * @param serverMessages the Drafts window this sync read, possibly empty
   * @param cachedDrafts the cached draft rows, newest first (light sync view)
   * @param knownDraftsByUid those same rows indexed by IMAP UID
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   * @param windowSize the number of most recent drafts to keep
   */
  private void syncDraftRows(UIDFolder uidFolder,
                             Message[] serverMessages,
                             List<Email> cachedDrafts,
                             Map<Long, Email> knownDraftsByUid,
                             String username,
                             UserEmailSetting userEmailSetting,
                             int windowSize) {
    // Keyed by UID, valued by the Message-ID the copy was identified through, because
    // the janitor removes them on its own connection and must be able to prove, at that
    // point, that the message still sitting at that number is the one it judged.
    Map<Long, String> strayCopies = new LinkedHashMap<>();
    int imported = importServerDrafts(uidFolder, serverMessages, knownDraftsByUid, username, userEmailSetting, strayCopies);
    int detached = detachDraftsDeletedElsewhere(uidFolder, serverMessages, cachedDrafts, username);
    // The same cleanup every other folder gets, and the moment its draft guard stops
    // being theoretical: rows the server no longer has go, EXCEPT the ones this
    // feature exists to protect (see isProtectedDraft).
    cleanupObsoleteEmails(uidFolder, cachedDrafts, serverMessages, username, windowSize);
    int removedStrays = removeStrayDraftCopies(strayCopies, username, userEmailSetting);
    LOG.info("Synchronized folder {} of user {}: {} draft(s) on the server, {} written in another client and imported,"
        + " {} kept locally after their server copy vanished, {} stray copy(ies) of already-sent mail found, {} removed",
             MailFolder.DRAFTS,
             username,
             serverMessages.length,
             imported,
             detached,
             strayCopies.size(),
             removedStrays);
  }

  /**
   * Creates a local draft row for every Drafts message this mailbox does not
   * already know — a draft the user wrote in another mail client.
   * <p>
   * One message is deliberately NOT imported: one whose Message-ID is already in
   * the Sent cache. That is not a draft, it is the copy a send left behind when its
   * cleanup failed (see {@link #cleanupSentDraft}), and importing it would put a
   * draft of an already-sent mail in front of the user and invite them to send it
   * twice — the one outcome the send path bends over backwards to prevent. Its UID
   * goes to the janitor instead.
   *
   * @param uidFolder the open Drafts folder, to resolve each message's UID
   * @param serverMessages the Drafts window this sync read
   * @param knownDraftsByUid the cached draft rows indexed by IMAP UID
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   * @param strayCopies collects the copies of already-sent mail, by UID and by the
   *          Message-ID they were recognised through, for the janitor to remove
   * @return the number of drafts imported
   */
  private int importServerDrafts(UIDFolder uidFolder,
                                 Message[] serverMessages,
                                 Map<Long, Email> knownDraftsByUid,
                                 String username,
                                 UserEmailSetting userEmailSetting,
                                 Map<Long, String> strayCopies) {
    int imported = 0;
    for (Message message : serverMessages) {
      try {
        long messageUid = uidFolder.getUID(message);
        if (knownDraftsByUid.containsKey(messageUid)) {
          // A copy we appended ourselves — including one a send has claimed. Whatever
          // state its row is in, this sync is not the thing that gets to change it.
          continue;
        }
        String messageId = message instanceof MimeMessage mimeMessage ? mimeMessage.getMessageID() : null;
        if (emailBoxStorage.isMessageCachedInFolder(username, messageId, MailFolder.SENT)) {
          strayCopies.put(messageUid, messageId);
          continue;
        }
        createDraftFromServerMessage(message, messageUid, messageId, username, userEmailSetting);
        imported++;
      } catch (Exception e) {
        // Per message, like the rest of the sync: one unreadable draft must not stop
        // the others from arriving.
        LOG.warn("Error importing a draft of user {} from the Drafts folder", username, e);
      }
    }
    return imported;
  }

  /**
   * Turns a Drafts message written in another client into a local draft row.
   * <p>
   * It gets a local id of its own, because that is the handle everything about a
   * draft is addressed by here — the composer resumes, saves and discards by it, and
   * the UID cannot play that role since re-saving a draft appends a new message and
   * removes the old one. Its state is {@link DraftState#SYNCED}: the text on the row
   * IS the text on the server, which is exactly what that state means, and it is
   * also what allows the row to disappear again if the other client deletes it.
   * <p>
   * Threading goes through {@link #computeThreadId}, the same call every synced
   * message and every locally-authored draft goes through, so an imported draft
   * lands in the conversation it answers instead of starting one of its own. There
   * is deliberately no second notion of a conversation anywhere in this feature.
   * <p>
   * The sender is guarded rather than trusted: a row whose stored sender is blank is
   * unreadable (the entity mapper splits that column on a comma and takes the second
   * half), and a draft is the one kind of message that legitimately arrives with no
   * From header at all — half-written mail is exactly what a Drafts folder holds. A
   * draft in the user's own Drafts folder is the user's, so their own address is the
   * honest fallback.
   * <p>
   * <b>Attachments are imported as what they are: addresses.</b> Each one is a MIME
   * part path INTO the message sitting at this UID — the same descriptor every
   * received message's attachments are cached as, produced by the same walk of the
   * MIME tree, and downloaded through the same folder-scoped read (see
   * {@link #getAttachmentByMailRemoteIdAnIdAndUserId}, which resolves DRAFTS like any
   * other folder). No bytes are pulled here: a sync that copied every file out of
   * every imported draft would cost megabytes for drafts the user never opens.
   * <p>
   * The bytes come over later and only if they are needed — at the first push or send
   * after the draft is edited, see {@link #materializeRemoteDraftParts}. That pairing
   * is not optional and the two halves must not be separated: showing the chips
   * without it would let the user edit the draft, watch the push rebuild the message
   * from a row that has only an address, and delete the copy that held the file.
   * <p>
   * A part with no path is dropped, because a row that cannot be addressed can neither
   * be downloaded nor brought over; a part with no filename is kept under a default
   * one, because dropping it would be exactly the silent loss this is here to stop.
   *
   * @param message the Drafts message, headers already prefetched
   * @param messageUid its IMAP UID in the Drafts folder
   * @param messageId its own Message-ID, may be blank
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding, for their own address
   * @throws MessagingException if the message cannot be read
   */
  private void createDraftFromServerMessage(Message message,
                                            long messageUid,
                                            String messageId,
                                            String username,
                                            UserEmailSetting userEmailSetting) throws MessagingException {
    // When the other client last wrote it. Both dates are stamped with it, so an
    // imported draft is as recent as its author left it rather than as recent as the
    // sync that noticed it — which is what the listings order on, and what decides
    // whether the cache trim may evict it.
    Date writtenAt = message.getSentDate() != null ? message.getSentDate()
                                                   : (message.getReceivedDate() != null ? message.getReceivedDate()
                                                                                        : new Date());
    Email draft = new Email();
    draft.setUserId(username);
    draft.setFolder(MailFolder.DRAFTS);
    draft.setMailRemoteId(messageUid);
    draft.setMailHeaderId(messageId);
    draft.setDraftLocalId(UUID.randomUUID().toString());
    draft.setDraftState(DraftState.SYNCED);
    draft.setDraftRevision(1L);
    draft.setDraftUpdatedDate(writtenAt);
    draft.setReceivedDate(writtenAt);
    // Never unread, never recent: it is the user's own text, and the new-mail
    // notification must not be able to see it.
    draft.setRead(true);
    draft.setRecent(false);
    draft.setSubject(message.getSubject());
    EmailContent messageContent = EmailConnectorUtils.getMessageContent(messageUid, message);
    draft.setContent(new EmailContent(messageContent != null ? StringUtils.defaultString(messageContent.getBody()) : "",
                                      null,
                                      importedDraftAttachments(messageContent)));
    draft.setSender(serverDraftSender(message, userEmailSetting));
    draft.setTo(EmailConnectorUtils.getEmailRecipients(message.getRecipients(Message.RecipientType.TO), username, false));
    draft.setCc(EmailConnectorUtils.getEmailRecipients(message.getRecipients(Message.RecipientType.CC), username, false));
    draft.setBcc(EmailConnectorUtils.getEmailRecipients(message.getRecipients(Message.RecipientType.BCC), username, false));
    String inReplyTo = firstHeader(message, "In-Reply-To");
    String references = firstHeader(message, "References");
    String threadIndexRoot = EmailThreadingUtils.extractThreadIndexRoot(firstHeader(message, "Thread-Index"));
    draft.setInReplyTo(inReplyTo);
    draft.setMailReferences(references);
    draft.setThreadIndexRoot(threadIndexRoot != null ? threadIndexRoot : "");
    draft.setThreadId(computeThreadId(username, messageId, messageUid, inReplyTo, references, threadIndexRoot));
    emailBoxStorage.createEmail(draft);
  }

  /**
   * The attachment descriptors to cache for an imported draft: the ones the MIME walk
   * produced, filtered to those that can actually be addressed, with the two fields
   * every consumer reads unguarded filled in.
   * <p>
   * <b>A part with a blank path is dropped.</b> The path is the whole of the row's
   * usefulness — it is how the download reaches the bytes and how
   * {@link #materializeRemoteDraftParts} brings them over — so a row without one is a
   * chip that can never resolve to anything, on a draft that could then never be
   * uploaded (see {@link #draftFilesAreAllReadable}, which refuses a draft carrying a
   * row with neither a file nor a way to get one). Dropping it is what keeps the
   * draft usable.
   * <p>
   * <b>A part with a blank NAME is kept</b>, under a default one, and the asymmetry is
   * deliberate: a nameless part is still fetchable and still belongs on the message
   * that gets rebuilt, so dropping it would be precisely the silent loss this slice
   * exists to stop. It costs a chip reading "attachment", which is honest about what
   * is known. The name also has to be non-blank for the row to be written at all —
   * {@code EmailBoxStorage#toEmailAttachmentEntity} maps a nameless descriptor to null,
   * and a null in a cascade-persisted collection is not a row, it is a failure.
   *
   * @param messageContent the content the MIME walk extracted, may be null
   * @return the attachments to cache with the draft row, or null when it has none
   */
  private List<EmailAttachment> importedDraftAttachments(EmailContent messageContent) {
    if (messageContent == null || CollectionUtils.isEmpty(messageContent.getAttachments())) {
      return null;
    }
    List<EmailAttachment> attachments = messageContent.getAttachments().stream().filter(attachment -> attachment != null
        && StringUtils.isNotBlank(attachment.getAttachmentRemoteId())).map(attachment -> {
          attachment.setName(StringUtils.defaultIfBlank(attachment.getName(), DEFAULT_ATTACHMENT_NAME));
          attachment.setMimeType(StringUtils.defaultIfBlank(attachment.getMimeType(), DEFAULT_ATTACHMENT_MIME_TYPE));
          return attachment;
        }).toList();
    return attachments.isEmpty() ? null : attachments;
  }

  /**
   * The sender to stamp on an imported draft: the message's own From when it has
   * one, the mailbox owner when it does not.
   * <p>
   * The fallback is not cosmetic. A row stored with a blank sender cannot be read
   * back at all — {@code EmailBoxStorage#fromEntity} splits that column on a comma
   * and indexes the second half unguarded — so a From-less draft written by another
   * client would be cached and then throw on every read of it. Half-written mail
   * with no From is normal in a Drafts folder, and a draft sitting in the user's own
   * Drafts folder is the user's, so their address is both safe and true.
   *
   * @param message the Drafts message
   * @param userEmailSetting the user's connector binding, for their own address
   * @return the sender to stamp, never null
   * @throws MessagingException if the From header cannot be read
   */
  private EmailSender serverDraftSender(Message message, UserEmailSetting userEmailSetting) throws MessagingException {
    Address[] from = message.getFrom();
    if (from != null && from.length > 0) {
      EmailSender sender = EmailConnectorUtils.getEmailSender(from[0], false);
      if (sender != null && StringUtils.isNotBlank(sender.getAddress())) {
        return sender;
      }
    }
    return ownSender(userEmailSetting);
  }

  /**
   * Keeps the drafts whose server copy has gone but whose text has not: rows that
   * are {@link DraftState#DIRTY} — uploaded once, typed into since — and whose UID
   * the server no longer has.
   * <p>
   * The other states are handled by not being handled here, and each for its own
   * reason. {@link DraftState#SYNCED} means the row and the server copy said the
   * same thing, so the copy's removal is the whole story and the row goes with it
   * (through the ordinary cleanup). {@link DraftState#SENDING} belongs to a send that
   * is still in the air.
   * <p>
   * {@link DraftState#LOCAL_ONLY} is skipped as well, and not — as this said when it
   * was written — because such a row never had a copy to lose: one that was uploaded
   * while its author kept typing keeps its UID (see {@link #serverDraftCopyUid}). It
   * is skipped because leaving that UID in place costs nothing. Every path that acts
   * on it looks the message up first and finds nothing, or finds a message that is not
   * ours and says so; and the row is already protected from the cleanup, so its words
   * are in no danger while it waits for the next save to replace the number.
   *
   * @param uidFolder the open Drafts folder, to resolve each server message's UID
   * @param serverMessages the Drafts window this sync read
   * @param cachedDrafts the cached draft rows (light sync view), updated in place so
   *          the cleanup that follows sees the new state
   * @param username the mailbox owner
   * @return the number of rows kept and put back to LOCAL_ONLY
   */
  private int detachDraftsDeletedElsewhere(UIDFolder uidFolder,
                                           Message[] serverMessages,
                                           List<Email> cachedDrafts,
                                           String username) {
    Set<Long> serverUids = serverMessageUids(uidFolder, serverMessages);
    int detached = 0;
    for (Email cachedDraft : cachedDrafts) {
      if (!DraftState.DIRTY.equals(cachedDraft.getDraftState()) || cachedDraft.getMailRemoteId() == null
          || StringUtils.isBlank(cachedDraft.getDraftLocalId()) || serverUids.contains(cachedDraft.getMailRemoteId())) {
        continue;
      }
      if (detachDraftDeletedElsewhere(cachedDraft, username)) {
        detached++;
      }
    }
    return detached;
  }

  /**
   * Puts one such row back to {@link DraftState#LOCAL_ONLY}, with the UID of the
   * copy that is gone cleared — the state a draft that has never been uploaded is
   * in, which is precisely what this row is now, so the next save appends a fresh
   * copy instead of trying to replace one that does not exist.
   * <p>
   * The draft's own lock is taken, and NOT waited for: a save holding it right now
   * is a newer truth than a window listing read seconds ago, and its upload ends by
   * stamping the row with the UID it just created. Waiting would mean overwriting
   * that with a judgement made before it happened; skipping means the next sync
   * looks again, by which time the row says something current.
   * <p>
   * The row is then re-read under the lock and re-checked, because between the
   * listing and the lock the user may have saved, sent or discarded it. Only a row
   * still DIRTY against the SAME vanished UID is touched.
   * <p>
   * The user finds out through the composer: a draft it believed was on the server
   * coming back LOCAL_ONLY is a transition nothing else produces, and the composer
   * says so rather than letting the words quietly stop being where the user thinks
   * they are.
   *
   * @param cachedDraft the row from the light sync view, updated in place on success
   * @param username the mailbox owner
   * @return true when the row was put back to LOCAL_ONLY
   */
  private boolean detachDraftDeletedElsewhere(Email cachedDraft, String username) {
    String lockKey = draftLockKey(username, cachedDraft.getDraftLocalId());
    ReentrantLock lock = draftLocks.computeIfAbsent(lockKey, key -> new ReentrantLock());
    if (!lock.tryLock()) {
      LOG.debug("A save is in flight on draft {} of user {}; leaving its state to that save", cachedDraft.getDraftLocalId(), username);
      return false;
    }
    try {
      Email stored = emailBoxStorage.getDraftByLocalId(username, cachedDraft.getDraftLocalId());
      if (stored == null || !DraftState.DIRTY.equals(stored.getDraftState())
          || !Objects.equals(stored.getMailRemoteId(), cachedDraft.getMailRemoteId())) {
        return false;
      }
      emailBoxStorage.detachDraftFromServerCopy(username, cachedDraft.getDraftLocalId());
      LOG.info("The Drafts folder copy (uid {}) of a draft of user {} is gone from the server; the row is kept and will be"
          + " uploaded again on the next save", cachedDraft.getMailRemoteId(), username);
      cachedDraft.setDraftState(DraftState.LOCAL_ONLY);
      cachedDraft.setMailRemoteId(null);
      return true;
    } catch (Exception e) {
      LOG.warn("Could not detach the draft {} of user {} from its vanished server copy", cachedDraft.getDraftLocalId(), username, e);
      return false;
    } finally {
      lock.unlock();
    }
  }

  /**
   * The stray-copy janitor: removes from the mail server's Drafts folder the copies
   * of messages the user has already sent.
   * <p>
   * They exist because {@link #sendDraft} chooses to leave them. A send whose
   * cleanup fails still removes the local row — showing someone a draft of a mail
   * they have already sent, and inviting them to send it twice, is worse than a
   * stray copy in a Drafts folder — and this is where that accepted debt is paid
   * off, later and from a different connection.
   * <p>
   * What it removes: a Drafts entry whose Message-ID is already in the Sent cache.
   * Nothing else. That identity is exact — a draft goes out under the very
   * Message-ID it was pinned with, which is what makes the sent mail BE the draft
   * (see {@link #transmitDraft}) — and it is the only test used, on purpose. A
   * draft that merely LOOKS sent (same subject, same recipients, a reply to
   * something that was sent) is not touched by any of that, because deciding it by
   * resemblance would eventually delete a message somebody was still writing.
   * <p>
   * What it also will not touch: any UID a local draft row still claims. Those never
   * reach this list — the caller only offers UIDs it did not recognise — so a draft
   * being written here, or one a send has claimed, is out of reach by construction.
   * <p>
   * Idempotent and best-effort throughout. A copy already gone is not a failure; a
   * removal that fails is logged and retried on the next sync, since the same
   * message will be identified the same way again. Capped per run because quietly
   * deleting from a user's mailbox at scale is not how an unexpected situation
   * should be discovered.
   * <p>
   * The one case this rule gets wrong is a message DELIBERATELY copied into Drafts
   * after being sent (some clients offer it) — it carries the sent Message-ID and is
   * indistinguishable from the leftover. Knowingly accepted: it is rare, the copy is
   * recoverable from Sent, and every tighter test available would be a resemblance
   * test, which is the thing that must not decide this.
   *
   * @param strayCopies the copies of already-sent mail, by UID and by the Message-ID
   *          each was recognised through
   * @param username the mailbox owner
   * @param userEmailSetting the user's connector binding
   * @return the number of copies removed
   */
  private int removeStrayDraftCopies(Map<Long, String> strayCopies, String username, UserEmailSetting userEmailSetting) {
    if (MapUtils.isEmpty(strayCopies)) {
      return 0;
    }
    Map<Long, String> copiesToRemove = strayCopies.entrySet()
                                                  .stream()
                                                  .limit(STRAY_DRAFT_REMOVAL_LIMIT)
                                                  .collect(Collectors.toMap(Map.Entry::getKey,
                                                                            Map.Entry::getValue,
                                                                            (first, second) -> first,
                                                                            LinkedHashMap::new));
    Store store = null;
    IMAPFolder draftsFolder = null;
    boolean expungeOnClose = false;
    int removed = 0;
    // Its own connection, and its own READ_WRITE open: the sync holds the same folder
    // open READ_ONLY at this point, which is what keeps the sync's own reading honest
    // — a folder the sync could write to is a folder the sync could damage.
    MailboxSyncState syncState = loadMailboxSyncState(username);
    String originalSyncStateJson = JsonUtils.toJsonString(syncState);
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      draftsFolder = resolveDraftsFolder(store, syncState);
      if (draftsFolder == null) {
        return 0;
      }
      draftsFolder.open(Folder.READ_WRITE);
      for (Map.Entry<Long, String> strayCopy : copiesToRemove.entrySet()) {
        try {
          expungeOnClose = removePreviousDraftCopy(draftsFolder, strayCopy.getKey(), strayCopy.getValue(), username)
              || expungeOnClose;
          removed++;
        } catch (Exception e) {
          LOG.warn("Could not remove the stray Drafts copy (uid {}) of an already-sent mail of user {}",
                   strayCopy.getKey(),
                   username,
                   e);
        }
      }
      return removed;
    } catch (Exception e) {
      LOG.warn("Could not remove {} stray Drafts copy(ies) of already-sent mail of user {}", copiesToRemove.size(), username, e);
      return removed;
    } finally {
      saveMailboxSyncState(username, syncState, originalSyncStateJson);
      closeDraftsFolderQuietly(draftsFolder, expungeOnClose, username);
      closeQuietly(null, store, username);
    }
  }

  /**
   * The IMAP UIDs of a folder window, as a set — the "does the server still have
   * this" test both halves of the sync's cleanup ask. A message whose UID cannot be
   * read is left out, which makes the answer "the server does not have it": the
   * conservative direction for the callers that keep rows, and the same behaviour
   * this had when it was inlined in {@link #cleanupObsoleteEmails}.
   *
   * @param uidFolder the open remote folder
   * @param serverMessages the window this sync read
   * @return the window's UIDs
   */
  private Set<Long> serverMessageUids(UIDFolder uidFolder, Message[] serverMessages) {
    return Arrays.stream(serverMessages).map(message -> {
      try {
        return uidFolder.getUID(message);
      } catch (MessagingException messagingException) {
        LOG.warn("Error when getting message uid", messagingException);
        return null;
      }
    }).collect(Collectors.toSet());
  }

  /**
   * Drops the cached rows the server no longer has, then trims the cache back to
   * its configured size.
   * <p>
   * Both halves rest on the same assumption, and it is the assumption drafts break:
   * that the server is the truth and a local row is a copy of something up there,
   * so a row the server does not have is a row nobody wants. A draft inverts that.
   * It is AUTHORED here and pushed up afterwards, so between the moment the user
   * types and the moment the push lands, "not on the server" is the normal state of
   * the newest thing they have written — and a rule that deletes it is silent data
   * loss on the user's own words, which is the worst failure this feature can have.
   * The same goes for the size trim: a cache overflow may evict any amount of mail
   * that still exists on the server and can be fetched again, but an unpushed draft
   * exists nowhere else.
   * <p>
   * So an unsaved or dirty draft is never touched by either half, and the exclusion
   * is written HERE rather than left to the fact that the Drafts folder does not
   * currently join the sync: the whole hazard is that it will, and by then this
   * method has to already be safe.
   * <p>
   * It now has: the Drafts folder joined the sync, and both halves are what the
   * Drafts reconcile leans on to delete a draft the user removed from another client
   * ({@link #syncDraftRows}). The guard is applied to BOTH — the obsolete-delete and
   * the size trim — and the second is not a formality: draft rows are stamped with
   * the moment their author last typed, so they sort NEWEST and would normally be at
   * the front of the list rather than in the overflow, which means a trim that ate
   * one would do it rarely and unrepeatably. That is the shape of bug that survives
   * for years, hence the filter on both lists rather than only on the one where the
   * danger is obvious.
   *
   * @param uidFolder the open remote folder, to resolve each server message's UID
   * @param userEmails the folder's cached rows, newest first
   * @param serverMessages the server window this sync read
   * @param username the mailbox owner
   * @param emailBoxCacheSize the number of most recent messages to keep
   */
  private void cleanupObsoleteEmails(UIDFolder uidFolder,
                                     List<Email> userEmails,
                                     Message[] serverMessages,
                                     String username,
                                     int emailBoxCacheSize) {
    Set<Long> serverMessagesUids = serverMessageUids(uidFolder, serverMessages);
    List<Email> obsoleteEmails = userEmails.stream()
                                           .filter(email -> !isProtectedDraft(email))
                                           .filter(email -> !serverMessagesUids.contains(email.getMailRemoteId()))
                                           .toList();
    if (!obsoleteEmails.isEmpty()) {
      deleteEmails(obsoleteEmails);
    }
    if (userEmails.size() > emailBoxCacheSize) {
      List<Email> oldUserEmailsToCleanup = userEmails.subList(emailBoxCacheSize, userEmails.size())
                                                     .stream()
                                                     .filter(email -> !isProtectedDraft(email))
                                                     .toList();
      if (!oldUserEmailsToCleanup.isEmpty()) {
        deleteEmails(oldUserEmailsToCleanup);
      }
    }
  }

  /**
   * Whether a cached row is a draft whose text is not (or no longer) safely on the
   * server, and which the sync must therefore leave alone.
   * <p>
   * {@link DraftState#SYNCED} is deliberately NOT protected: its text does exist on
   * the server, so the ordinary rules apply to it and a draft genuinely deleted from
   * another client can still disappear from here. A null state is not a draft at all
   * — every other row in the table carries one — and takes the ordinary path too.
   * That non-protection is now load-bearing rather than incidental: since the Drafts
   * folder joined the sync, it IS the rule that carries out "deleted on the phone,
   * so deleted here".
   * <p>
   * {@link DraftState#SENDING} is protected for a different reason from the other
   * two: not because its text exists nowhere else, but because the send that claimed
   * it is going to take the row apart itself, in an order chosen for what happens
   * when a step fails. A sync deleting the row from underneath that would turn the
   * "send succeeded, cleanup failed" branch into a silent double outcome.
   *
   * @param email the cached row, from the light sync view
   * @return true when the row is a draft the sync must leave alone
   */
  private boolean isProtectedDraft(Email email) {
    return DraftState.LOCAL_ONLY.equals(email.getDraftState()) || DraftState.DIRTY.equals(email.getDraftState())
        || DraftState.SENDING.equals(email.getDraftState());
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
      // Re-arm rather than carry the old timer: the backstop must measure silence, not
      // total elapsed time. A large mailbox classifies for longer than any fixed deadline
      // -- 5000 messages take far longer than the 15 minutes that suited 500 -- and a
      // deadline that expires mid-run sends a notification counting only part of the mail.
      cancelTimer(pending);
      return new PendingNotification(pending.maxLocalUid(),
                                     remainingClaims,
                                     pending.syncCompleted(),
                                     scheduleNotificationTask(user, NOTIFICATION_MAX_WAIT_MS));
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

  /**
   * The mailbox's Drafts folder: the SPECIAL-USE {@code \Drafts} attribute
   * (RFC 6154) first, then a name match for the servers that never learned
   * SPECIAL-USE. If neither finds one, server-side drafts are simply OFF for that
   * account — we never CREATE a Drafts folder. Creating folders in someone's
   * mailbox is a visible, permanent change to a store the user shares with every
   * other client they own, and it is not ours to make on the strength of them
   * having typed two words into a compose window.
   * <p>
   * The name match is deliberately STRICTER than {@link #findSentFolder}'s: it
   * compares the last path segment for EQUALITY against a known token list, rather
   * than asking whether the full name merely {@code contains} one. The two folders
   * carry different risk. Guessing Sent wrong loses a copy of a mail that was
   * already delivered; guessing Drafts wrong means we APPEND the user's unsent
   * words into a folder of their own making — "Draft ideas", "Drafts of the
   * contract" — and then, once slice 2 lands, delete the previous copy out of it.
   * Last-segment equality still catches the one nested layout that matters,
   * Gmail's {@code [Gmail]/Drafts}, because the segment after the separator is
   * exactly {@code Drafts}.
   * <p>
   * Subscribed folders are scanned first (as everywhere else here), then ALL
   * folders if that found nothing. Sent is auto-subscribed by practically every
   * client that writes to it; Drafts much less reliably so, and a mailbox that
   * plainly HAS a Drafts folder silently behaving as though it had none is a worse
   * outcome than one extra {@code LIST *} on the accounts where the first pass
   * misses.
   *
   * @param store the connected store
   * @return the Drafts folder, or null when the mailbox has none
   * @throws MessagingException if the folder list cannot be read
   */
  private IMAPFolder findDraftsFolder(Store store) throws MessagingException {
    IMAPFolder subscribed = findDraftsFolderIn(store.getDefaultFolder().listSubscribed("*"));
    return subscribed != null ? subscribed : findDraftsFolderIn(store.getDefaultFolder().list("*"));
  }

  /**
   * Scans one folder listing for the Drafts folder — see {@link #findDraftsFolder}
   * for the matching rules and why they are what they are. Split out so the
   * subscribed listing and the full listing run the exact same test.
   *
   * @param folders the folder listing to scan
   * @return the first matching folder, or null when the listing holds none
   * @throws MessagingException if a folder's attributes cannot be read
   */
  private IMAPFolder findDraftsFolderIn(Folder[] folders) throws MessagingException {
    IMAPFolder nameMatch = null;
    for (Folder folder : folders) {
      if (!(folder instanceof IMAPFolder imapFolder) || !imapFolder.exists()) {
        continue;
      }
      for (String attribute : imapFolder.getAttributes()) {
        if (attribute.equalsIgnoreCase(DRAFTS_SPECIAL_USE_ATTRIBUTE)) {
          return imapFolder;
        }
      }
      // Remembered, not returned: a SPECIAL-USE match found later in the listing must
      // still win over a name match found earlier. The attribute is the server telling
      // us which folder this is; the name is us guessing.
      if (nameMatch == null && isDraftsFolderName(imapFolder.getFullName())) {
        nameMatch = imapFolder;
      }
    }
    return nameMatch;
  }

  /**
   * Whether a folder's full name is one of the well-known Drafts names, judged on
   * its LAST path segment only and by equality — so {@code [Gmail]/Drafts} and
   * {@code INBOX.Drafts} match while a user's own "Draft ideas" does not.
   *
   * @param fullName the folder's full name, hierarchy separators included
   * @return true when the last segment is a known Drafts name
   */
  private boolean isDraftsFolderName(String fullName) {
    if (StringUtils.isBlank(fullName)) {
      return false;
    }
    // Split on both separators actually seen in the wild ('/' on Gmail and Dovecot's
    // default, '.' on the Maildir++ layouts) rather than asking the folder for its
    // own: this is a pure string test, kept free of an IMAP round-trip.
    String lastSegment = fullName.substring(Math.max(fullName.lastIndexOf('/'), fullName.lastIndexOf('.')) + 1);
    return DRAFTS_FOLDER_NAMES.contains(lastSegment.trim().toLowerCase());
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

  /**
   * A {@link MimeMessage} that goes out under a Message-ID we chose, instead of one
   * JavaMail invents.
   * <p>
   * This exists because {@code setHeader("Message-ID", ...)} does not survive.
   * {@code MimeMessage#saveChanges} calls {@code updateHeaders}, which calls
   * {@code updateMessageID}, which overwrites the header unconditionally — and
   * {@code saveChanges} runs at every point a message reaches the outside world:
   * {@code Transport.send(msg)} calls it as its first statement, and
   * {@code writeTo} (what an IMAP APPEND ends up calling) calls it on any message
   * not already saved. So there is no ordering of {@code setHeader} and
   * {@code saveChanges} that holds for both paths: stamp before and it is discarded
   * immediately, stamp after and the next {@code saveChanges} discards it. Overriding
   * the method that does the overwriting is the only version that cannot be undone by
   * a later call.
   * <p>
   * Why it matters enough to subclass for: the draft mints its own Message-ID at its
   * first save, the APPENDed copy carries it, the sent message carries the same one,
   * and that identity is what makes the arriving Sent copy recognisable as the draft
   * it grew out of — which is what lets the reader show one message where there would
   * otherwise be two, and what makes every other mail client thread the reply with
   * the conversation it answers.
   * <p>
   * A blank id falls back to JavaMail's own generator, so the ordinary send path can
   * use this class without behaving differently from a plain {@code MimeMessage}.
   */
  private static class PinnedMessageIdMimeMessage extends MimeMessage {

    private final String messageId;

    /**
     * @param session the session the message is built in
     * @param messageId the Message-ID to keep, or null/blank to let JavaMail mint one
     */
    PinnedMessageIdMimeMessage(Session session, String messageId) {
      super(session);
      this.messageId = messageId;
    }

    /**
     * Keeps our own Message-ID where JavaMail would write a fresh one.
     *
     * @throws MessagingException if the header cannot be set
     */
    @Override
    protected void updateMessageID() throws MessagingException {
      if (StringUtils.isBlank(messageId)) {
        super.updateMessageID();
        return;
      }
      setHeader("Message-ID", messageId);
    }
  }
}
