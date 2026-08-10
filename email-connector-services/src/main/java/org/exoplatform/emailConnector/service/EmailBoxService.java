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
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
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
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.exception.ObjectNotFoundException;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.emailConnector.job.EmailBoxSyncJob;
import org.exoplatform.emailConnector.model.Email;
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
    Folder inbox = null;
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      updateEmailSyncStatus(username, SyncStatus.IN_PROGRESS);
      inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_ONLY);
      UIDFolder uidFolder = (UIDFolder) inbox;
      int totalMessages = inbox.getMessageCount();
      if (totalMessages == 0) {
        LOG.info("Inbox empty for user {}", username);
        updateEmailSyncStatus(username, SyncStatus.SUCCESS);
        return;
      }
      int startIndex = Math.max(1, totalMessages - EmailConnectorUtils.MAX_EMAILS + 1);
      Message[] serverMessages = inbox.getMessages(startIndex, totalMessages);
      List<Email> userEmails = getEmailBox(username).getEmails();
      createEmails(uidFolder, serverMessages, username);
      cleanupObsoleteEmails(uidFolder, userEmails, serverMessages, username);
      updateEmailSyncStatus(username, SyncStatus.SUCCESS);
      sendNotification(uidFolder, userEmails, serverMessages, username);
      broadcastUnreadCountChanged(username);
    } catch (Exception e) {
      updateEmailSyncStatus(username, SyncStatus.FAILURE);
      LOG.error("Error when user {} synchronization ", username, e);
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

  /**
   * Get user email box.
   *
   * @param username user getting user emails
   * @return list of stored {@link Email} in datasource
   */
  public EmailBox getEmailBox(String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
    }
    List<Email> emails = emailBoxStorage.getEmails(username);
    return new EmailBox(emails, userEmailSetting.getEmailSyncStatus(), userEmailSetting.getEmailConnectorWebmailUrl());
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
    String userEmail = null;
    if (broadcast) {
      userEmail = broadcastOpenEmail(username);
    }
    return emailBoxStorage.getEmailByMailRemoteIdAndUserId(mailRemoteId,
                                                           username,
                                                           userEmail,
                                                           withAttachments,
                                                           withRecipients,
                                                           withProfile);
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
      emailBoxStorage.updateEmailReadStatusByMailRemoteIds(mailRemoteIds, username, readStatus);
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
                emailBoxStorage.updateEmailReadStatusByMailRemoteIds(List.of(mailRemoteId), username, !readStatus);
                failedEmailUpdates++;
                LOG.warn("Email {} not found on IMAP server for user {}, read status update reverted", mailRemoteId, username);
                continue;
              }
              remoteMessage.setFlag(Flags.Flag.SEEN, readStatus);
            }
          } catch (Exception e) {
            emailBoxStorage.updateEmailReadStatusByMailRemoteIds(List.of(mailRemoteId), username, !readStatus);
            failedEmailUpdates++;
            LOG.error("Error when updating email {} read status for user {}", mailRemoteId, username, e);
          }
        }
      } catch (Exception e) {
        emailBoxStorage.updateEmailReadStatusByMailRemoteIds(mailRemoteIds, username, !readStatus);
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
    // Read/unread transitions are what the App Center badge reflects; without
    // this broadcast the counter would only refresh at the next sync
    broadcastUnreadCountChanged(username);
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
              remoteMessage.setFlag(Flags.Flag.DELETED, true);
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
        message.setHeader("In-Reply-To", email.getMailHeaderId());
        message.setHeader("References", email.getMailHeaderId());
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

  private void createEmails(UIDFolder uidFolder, Message[] serverMessages, String username) throws MessagingException,
                                                                                            IllegalAccessException {
    for (Message message : serverMessages) {
      try {
        long messageUid = uidFolder.getUID(message);
        Email email = getEmailByMailRemoteIdAndUserId(messageUid, username, false, false, false, false);
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
                                                null));

        } else {
          updateEmailReadStatus(List.of(messageUid), username, message.isSet(Flags.Flag.SEEN), false);
          emailBoxStorage.markEmailAsNotRecent(messageUid, username);
        }
      } catch (Exception e) {
        LOG.warn("Error when storing email with subject {} for user {}", message.getSubject(), username, e);
      }
    }
  }

  private boolean canSynchronize(UserEmailSetting userEmailSetting, String username) {
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      return false;
    }
    if (SyncStatus.BLOCKED.equals(userEmailSetting.getEmailSyncStatus())) {
      return false;
    }
    if (SyncStatus.IN_PROGRESS.equals(userEmailSetting.getEmailSyncStatus())) {
      long nextAllowedSync = userEmailSetting.getLastEmailSyncStartDate() +
          EmailConnectorUtils.getEmailBoxUserSyncPeriod(userEmailSetting) * 60000L;
      return System.currentTimeMillis() > nextAllowedSync;
    }
    return true;
  }

  private void cleanupObsoleteEmails(UIDFolder uidFolder, List<Email> userEmails, Message[] serverMessages, String username) {
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
    if (userEmails.size() > EmailConnectorUtils.MAX_EMAILS) {
      List<Email> oldUserEmailsToCleanup = userEmails.subList(EmailConnectorUtils.MAX_EMAILS, userEmails.size());
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

  private void sendNotification(UIDFolder uidFolder, List<Email> userEmails, Message[] serverMessages, String userName) {
    long maxLocalUid = userEmails.stream().mapToLong(Email::getMailRemoteId).max().orElse(0L);
    long newUnreadCount = Arrays.stream(serverMessages).filter(msg -> {
      try {
        long uid = uidFolder.getUID(msg);
        boolean isNew = uid > maxLocalUid;
        boolean isUnread = !msg.isSet(Flags.Flag.SEEN);
        return isNew && isUnread;
      } catch (MessagingException e) {
        LOG.warn("Error reading message flags", e);
        return false;
      }
    }).count();
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
