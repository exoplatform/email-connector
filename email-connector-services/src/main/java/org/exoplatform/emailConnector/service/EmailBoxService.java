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

  // Cooldown before a BLOCKED mailbox is allowed to retry a sync, so BLOCKED is a temporary
  // backoff rather than a permanent dead-end (a successful retry clears it).
  private static final long       BLOCKED_RETRY_COOLDOWN_MS                                   = 30 * 60 * 1000L;

  // Caps the OR-of-Message-ID search when completing a thread from the archive on
  // open, so an unusually long conversation can't build a giant IMAP SEARCH.
  private static final int        ARCHIVE_COMPLETION_SEARCH_LIMIT                             = 50;

  // The nameIds of the add-on's own default email categories (see default-categories.json).
  // The platform's CategoryImportService persists each nameId -> created category id in
  // SettingService, so the assignable email category ids are resolved from there.
  private static final List<String> DEFAULT_EMAIL_CATEGORY_NAME_IDS                          =
                                                                   List.of("emailImportantCategory",
                                                                           "emailInvitationCategory",
                                                                           "emailNotificationCategory",
                                                                           "emailToReviewCategory");

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
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (!canSynchronize(userEmailSetting, username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_SYNCHRONIZE_EMAIL_MESSAGE, username));
    }
    Store store = null;
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      updateEmailSyncStatus(username, SyncStatus.IN_PROGRESS);
      int emailBoxCacheSize = emailConnectorService.getEmailBoxCacheSize();
      // INBOX drives the new-mail notifications; Sent and Archive are cached (best
      // effort — a missing folder must not fail the sync) so a conversation shows the
      // user's own replies ("Me") and previously-archived messages inline.
      syncFolder(store.getFolder("INBOX"), MailFolder.INBOX, username, emailBoxCacheSize, true);
      int nonInboxWindow = Math.min(emailBoxCacheSize, NON_INBOX_FOLDER_SYNC_LIMIT);
      try {
        syncFolder(findSentFolder(store), MailFolder.SENT, username, nonInboxWindow, false);
      } catch (Exception e) {
        LOG.warn("Could not sync the Sent folder for user {}", username, e);
      }
      try {
        syncFolder(findSyncableArchiveFolder(store), MailFolder.ARCHIVE, username, nonInboxWindow, false);
      } catch (Exception e) {
        LOG.warn("Could not sync the Archive folder for user {}", username, e);
      }
      updateEmailSyncStatus(username, SyncStatus.SUCCESS);
    } catch (Exception e) {
      updateEmailSyncStatus(username, SyncStatus.FAILURE);
      LOG.error("Error when user {} synchronization ", username, e);
    } finally {
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
   * Reset the user's mailbox and run a full re-synchronization from the server.
   * The locally-cached emails (and their category links) are cleared first, then a
   * fresh {@link #synchronize(String)} is run: because the cache is empty, every
   * message in the server window is treated as new and re-downloaded. The messages
   * on the server are never modified. This is a recovery action for a stale or
   * inconsistent local cache; it also clears a BLOCKED / failed-attempt backoff so
   * the immediate resync is allowed to run. Manually-applied categories are dropped
   * (re-created rows get new local ids); AI auto-categorization, when enabled,
   * re-tags the messages on the resync.
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
    // Clear the local cache (deleteEmails also unlinks each email's category links).
    deleteUserEmails(username);
    // Clear any BLOCKED / failed-attempt backoff so the immediate resync is not refused.
    userEmailSetting.setEmailSyncFailedAttemps(0);
    userEmailSetting.setEmailSyncStatus(SyncStatus.SUCCESS);
    userEmailSettingService.setUserEmailSetting(userEmailSetting, username, false);
    // Full re-download from the server.
    synchronize(username);
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
   * @param emailBoxCacheSize the number of most recent messages to keep
   * @param notify whether to fire the new-mail notification (INBOX only)
   */
  private void syncFolder(Folder folder,
                          String folderKey,
                          String username,
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
      // Prefetch flags + envelope + UID in a single round-trip. Without this,
      // isSet(SEEN)/getFrom/getSubject/... each trigger their own IMAP FETCH per
      // message — hundreds of round-trips over a high-latency provider like Gmail,
      // which is what makes a large sync appear to take forever.
      FetchProfile fetchProfile = new FetchProfile();
      fetchProfile.add(FetchProfile.Item.FLAGS);
      fetchProfile.add(FetchProfile.Item.ENVELOPE);
      fetchProfile.add(UIDFolder.FetchProfileItem.UID);
      // Prefetch the threading headers too (not covered by ENVELOPE), so computing /
      // backfilling thread ids reads them from cache instead of one FETCH per message.
      fetchProfile.add("References");
      fetchProfile.add("In-Reply-To");
      fetchProfile.add("Thread-Index");
      folder.fetch(serverMessages, fetchProfile);
      List<Email> folderEmails = emailBoxStorage.getEmails(username, folderKey);
      List<Long> newEmailIds = createEmails(uidFolder, serverMessages, username, folderKey);
      LOG.info("Synchronized folder {} of user {}: {} message(s) on the server, {} newly cached, {} already known",
               folderKey,
               username,
               serverMessages.length,
               newEmailIds.size(),
               serverMessages.length - newEmailIds.size());
      cleanupObsoleteEmails(uidFolder, folderEmails, serverMessages, username, emailBoxCacheSize);
      if (notify) {
        // Fire the new-emails event FIRST (categorization must not depend on the
        // notification step) and isolate sendNotification: it re-hits the IMAP folder
        // per message, so a dropped connection (FolderClosedException) must neither skip
        // categorization nor fail the whole sync (which would escalate the user to BLOCKED).
        broadcastNewEmailsSynced(username, newEmailIds);
        try {
          sendNotification(folderEmails, username);
        } catch (Exception e) {
          LOG.warn("Error sending the new-email notification for user {}", username, e);
        }
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
    String userEmail = null;
    if (broadcast) {
      userEmail = broadcastOpenEmail(username);
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
   * Notification / To review — resolved to their localized name. These are the leaf
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
      LOG.error("Error when sending email for user {}", username, e);
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

  private List<Long> createEmails(UIDFolder uidFolder,
                            Message[] serverMessages,
                            String username,
                            String folderKey) throws MessagingException, IllegalAccessException {
    List<Long> newEmailIds = new ArrayList<>();
    for (Message message : serverMessages) {
      try {
        long messageUid = uidFolder.getUID(message);
        Email email = emailBoxStorage.getEmailByMailRemoteIdAndUserId(messageUid, username, null, folderKey, false, false, false);
        if (email == null) {
          EmailContent emailContent = EmailConnectorUtils.getMessageContent(messageUid, message);
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
                                                threadIndexRoot != null ? threadIndexRoot : ""));
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
      FetchProfile fetchProfile = new FetchProfile();
      fetchProfile.add(FetchProfile.Item.FLAGS);
      fetchProfile.add(FetchProfile.Item.ENVELOPE);
      fetchProfile.add(UIDFolder.FetchProfileItem.UID);
      fetchProfile.add("References");
      fetchProfile.add("In-Reply-To");
      fetchProfile.add("Thread-Index");
      allMail.fetch(found, fetchProfile);
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
      createEmails(allMail, toCache.toArray(new Message[0]), username, MailFolder.ALL_MAIL);
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
   * Fires the new-emails notification for the messages just synced into the INBOX,
   * counting only the ones that are both new (IMAP UID beyond the highest one already
   * cached before this sync) and still unread, and — crucially — only those the user's
   * per-category notification preference allows (see
   * {@link #shouldNotifyForNewEmail(Email, UserEmailSetting)}). Category links are keyed
   * by the local email id, so the freshly-synced INBOX is re-read from the local cache
   * (its {@code categoryIds}) rather than inspected on the raw IMAP messages.
   *
   * @param userEmails the INBOX emails cached before this sync (used to derive the
   *          highest already-known UID, i.e. what counts as "new")
   * @param userName the mailbox owner
   */
  private void sendNotification(List<Email> userEmails, String userName) {
    long maxLocalUid = userEmails.stream()
                                 .filter(email -> email.getMailRemoteId() != null)
                                 .mapToLong(Email::getMailRemoteId)
                                 .max()
                                 .orElse(0L);
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
