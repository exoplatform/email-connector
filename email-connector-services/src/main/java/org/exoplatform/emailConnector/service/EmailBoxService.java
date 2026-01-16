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
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import javax.mail.BodyPart;
import javax.mail.Flags;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Multipart;
import javax.mail.Part;
import javax.mail.Store;
import javax.mail.UIDFolder;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.sun.mail.imap.IMAPFolder;
import com.sun.mail.imap.IMAPStore;

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.emailConnector.job.EmailBoxSyncJob;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailContent;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.notification.plugin.NewEmailsNotificationPlugin;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
import org.exoplatform.emailConnector.utils.NotificationConstants;
import org.exoplatform.services.listener.ListenerService;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;
import org.exoplatform.services.scheduler.JobInfo;
import org.exoplatform.services.scheduler.JobSchedulerService;
import org.exoplatform.services.scheduler.PeriodInfo;

import jakarta.annotation.PostConstruct;

/**
 * A Service to manage and synchronize email box
 */
@Service
public class EmailBoxService {

  private static final Log        LOG                                            = ExoLogger.getLogger(EmailBoxService.class);

  private static final String     USER_NOT_ALLOWED_FOR_SYNCHRONIZE_EMAIL_MESSAGE = "User %s is not allowed to synchronize email";

  private static final String     USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE         = "User %s is not allowed to get email";

  private static final String     USER_NOT_ALLOWED_FOR_GET_EMAIL_ATTACHMENT      =
                                                                            "User %s is not allowed to get email attachment";

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
    return new EmailBox(emails, userEmailSetting.getEmailSyncStatus());
  }

  /**
   * Delete user emails
   *
   * @param username user whose emails will be deleted
   */
  public void deleteUserEmails(String username) {
    emailBoxStorage.deleteUserEmails(username);
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

  public EmailAttachment getAttachmentByMailRemoteIdAnIdAndUserId(long emailRemoteId,
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
      Message message = ((UIDFolder) inbox).getMessageByUID(emailRemoteId);
      EmailAttachment emailAttachment = emailBoxStorage.getAttachmentByMailRemoteIdAnIdAndUserId(emailRemoteId,
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

  public void broadcastEvent(String eventName, String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
    }
    try {
      listenerService.broadcast(eventName, username, userEmailSetting.getEmailConnectorName());
    } catch (Exception e) {
      LOG.warn("Error broadcasting event '" + eventName + "' using source '" + username + "' and data "
          + userEmailSetting.getEmailConnectorName(), e);
    }
  }

  public Email getEmailByMailRemoteIdAndUserId(long mailRemoteId,
                                               String userName,
                                               boolean withAttachments,
                                               boolean withRecipients,
                                               boolean withProfile,
                                               boolean broadcast) throws IllegalAccessException {
    if (broadcast) {
      broadcastEvent(EmailConnectorUtils.OPEN_EMAIL, userName);
    }
    return emailBoxStorage.getEmailByMailRemoteIdAndUserId(mailRemoteId, userName, withAttachments, withRecipients, withProfile);
  }

  public void updateEmailReadStatus(long emailRemoteId,
                                    Message remoteMessage,
                                    String username,
                                    boolean readStatus,
                                    boolean updateRemoteReadStatus) throws IllegalAccessException {
    Email email = getEmailByMailRemoteIdAndUserId(emailRemoteId, username, false, false, false, false);
    if (email != null && readStatus != email.isRead()) {
      UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
      if (userEmailSetting.getEmailConnectorId() == null
          || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
        throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
      }
      email.setRead(readStatus);
      emailBoxStorage.updateEmail(email);
      if (updateRemoteReadStatus) {
        if (remoteMessage == null) {
          Store store = null;
          Folder inbox = null;
          try {
            store = userEmailSettingService.connect(userEmailSetting);
            inbox = store.getFolder("INBOX");
            inbox.open(Folder.READ_WRITE);
            remoteMessage = ((UIDFolder) inbox).getMessageByUID(emailRemoteId);
            if (remoteMessage != null) {
              remoteMessage.setFlag(Flags.Flag.SEEN, readStatus);
            }
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
        } else {
          try {
            remoteMessage.setFlag(Flags.Flag.SEEN, readStatus);
          } catch (MessagingException e) {
            LOG.error("Error when setting seen flag of remote message id {} for user {}", emailRemoteId, username, e);
            throw new IllegalStateException(String.format("Error when connecting setting seen flag of remote message id %s for user %s",
                                                          emailRemoteId,
                                                          username));
          }
        }
      }
    }
  }

  public void deleteEmailByMailRemoteIdAndUserId(long emailRemoteId, String username) throws IllegalAccessException {
    Email email = getEmailByMailRemoteIdAndUserId(emailRemoteId, username, false, false, false, false);
    if (email != null) {
      UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
      if (userEmailSetting.getEmailConnectorId() == null
          || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
        throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
      }
      emailBoxStorage.deleteEmails(Arrays.asList(email.getId()));
      Store store = null;
      IMAPFolder inbox = null;
      IMAPFolder trash = null;
      try {
        store = (IMAPStore) userEmailSettingService.connect(userEmailSetting);
        inbox = (IMAPFolder) store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
        Message remoteMessage = ((UIDFolder) inbox).getMessageByUID(emailRemoteId);
        if (remoteMessage != null) {
          remoteMessage.setFlag(Flags.Flag.DELETED, true);
          trash = findTrashFolder(store);
          if (trash != null) {
            inbox.moveMessages(new Message[] { remoteMessage }, trash);
          } else {
            inbox.close(true);
            return;
          }
        }
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
  }

  public void archiveEmailByMailRemoteIdAndUserId(long emailRemoteId, String username) throws IllegalAccessException {
    Email email = getEmailByMailRemoteIdAndUserId(emailRemoteId, username, false, false, false, false);
    if (email != null) {
      UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
      if (userEmailSetting.getEmailConnectorId() == null
          || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
        throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
      }
      emailBoxStorage.deleteEmails(Arrays.asList(email.getId()));
      Store store = null;
      IMAPFolder inbox = null;
      IMAPFolder archive = null;
      try {
        store = (IMAPStore) userEmailSettingService.connect(userEmailSetting);
        inbox = (IMAPFolder) store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
        Message remoteMessage = ((UIDFolder) inbox).getMessageByUID(emailRemoteId);
        if (remoteMessage != null) {
          remoteMessage.setFlag(Flags.Flag.DELETED, true);
          archive = findArchiveFolder(store);
          if (archive != null) {
            inbox.moveMessages(new Message[] { remoteMessage }, archive);
          }
        }
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
  }

  private void createEmails(UIDFolder uidFolder, Message[] serverMessages, String username) throws MessagingException,
                                                                                            IllegalAccessException {
    for (Message message : serverMessages) {
      try {
        long messageUid = uidFolder.getUID(message);
        Email email = getEmailByMailRemoteIdAndUserId(messageUid, username, false, false, false, false);
        if (email == null) {
          EmailContent emailContent = EmailConnectorUtils.getMessageContent(messageUid, message);
          String subject = message.getSubject() != null
              && message.getSubject().length() > 50 ? message.getSubject().substring(0, 50) + "..." : message.getSubject();
          EmailSender emailSender = message.getFrom() != null
              && message.getFrom().length != 0 ? EmailConnectorUtils.getEmailSender(message.getFrom()[0], false) : null;
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
          emailBoxStorage.createEmail(new Email(null,
                                                messageUid,
                                                username,
                                                subject,
                                                emailContent,
                                                message.getReceivedDate(),
                                                emailSender,
                                                message.isSet(Flags.Flag.SEEN),
                                                emailToRecipients,
                                                emailCcRecipients,
                                                emailBccRecipients));

        } else {
          updateEmailReadStatus(messageUid, message, username, message.isSet(Flags.Flag.SEEN), false);
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
      long nextAllowedSync = userEmailSetting.getLastEmailSyncStartDate()
          + EmailConnectorUtils.getEmailBoxUserSyncPeriod(userEmailSetting) * 60000L;
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
    List<Long> emailsIdsToDelete = emails.stream().map(Email::getId).toList();
    emailBoxStorage.deleteEmails(emailsIdsToDelete);
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

  @SuppressWarnings("resource")
  private IMAPFolder findTrashFolder(Store store) throws MessagingException {
    for (Folder folder : store.getDefaultFolder().listSubscribed("*")) {
      if (!(folder instanceof IMAPFolder))
        continue;
      IMAPFolder imapFolder = (IMAPFolder) folder;
      String name = imapFolder.getFullName().toLowerCase();
      if (name.contains("trash") || name.contains("corbeille") || name.contains("deleted")) {
        return imapFolder;
      }
    }
    return null;
  }

  @SuppressWarnings("resource")
  private IMAPFolder findArchiveFolder(Store store) throws MessagingException {
    for (Folder folder : store.getDefaultFolder().listSubscribed("*")) {
      if (!(folder instanceof IMAPFolder))
        continue;
      IMAPFolder imapFolder = (IMAPFolder) folder;
      String name = imapFolder.getFullName().toLowerCase();
      if (name.contains("archive") || name.contains("archivage")) {
        return imapFolder;
      }
    }
    return null;
  }
}
