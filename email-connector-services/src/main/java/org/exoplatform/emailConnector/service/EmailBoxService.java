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

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.MimeMessage;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.emailConnector.job.EmailBoxSyncJob;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailBox;
import org.exoplatform.emailConnector.model.EmailRecipient;
import org.exoplatform.emailConnector.model.EmailSender;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailConnectorUtils;
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

  @Autowired
  private UserEmailSettingService userEmailSettingService;

  @Autowired
  private EmailBoxStorage         emailBoxStorage;

  @Autowired
  private SettingService          settingService;

  @Autowired
  private JobSchedulerService     jobSchedulerService;

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
      createEmails(uidFolder, serverMessages, username);
      cleanupObsoleteEmails(uidFolder, serverMessages, username);
      updateEmailSyncStatus(username, SyncStatus.SUCCESS);
    } catch (Exception e) {
      updateEmailSyncStatus(username, SyncStatus.FAILURE);
      LOG.error("Error when user {} synchronization ", username, e);
    } finally {
      try {
        try {
          if (inbox != null && inbox.isOpen()) {
            inbox.close(false);
          }
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing inbox", messagingException);
        }
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
  public EmailBox getEmailBox(String username) {
    List<Email> emails = emailBoxStorage.getEmails(username);
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
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

  /**
   * Retrieve email by its id
   *
   * @param emailId email id
   * @param username user for which email will be retrieved
   * @return {@link Email} from mail server
   */
  public Email getEmailById(long emailId, String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_GET_EMAIL_MESSAGE, username));
    }
    Store store = null;
    Folder inbox = null;
    try {
      store = userEmailSettingService.connect(userEmailSetting);
      inbox = store.getFolder("INBOX");
      inbox.open(Folder.READ_ONLY);
      Message message = ((UIDFolder) inbox).getMessageByUID(emailId);
      if (message != null) {
        EmailSender emailSender = EmailConnectorUtils.getEmailSender(message.getFrom());
        List<EmailRecipient> emailToRecipients =
                                               EmailConnectorUtils.getEmailRecipients(message.getRecipients(Message.RecipientType.TO),
                                                                                      username);
        List<EmailRecipient> emailCcRecipients =
                                               EmailConnectorUtils.getEmailRecipients(message.getRecipients(Message.RecipientType.CC),
                                                                                      username);
        List<EmailRecipient> emailBccRecipients =
                                                EmailConnectorUtils.getEmailRecipients(message.getRecipients(Message.RecipientType.BCC),
                                                                                       username);
        MimeMessage mimeMessage = new MimeMessage((MimeMessage) message);
        return new Email(null,
                         emailId,
                         username,
                         message.getSubject(),
                         EmailConnectorUtils.getMessageContent(mimeMessage, false),
                         message.getSentDate() != null ? message.getSentDate() : message.getReceivedDate(),
                         emailSender,
                         emailToRecipients,
                         emailCcRecipients,
                         emailBccRecipients);
      }

    } catch (Exception e) {
      LOG.error("Error when connecting store for user {}", username, e);
      throw new IllegalStateException(String.format("Error when connecting store for user %s", username));
    } finally {
      try {
        try {
          if (inbox != null && inbox.isOpen()) {
            inbox.close(false);
          }
        } catch (MessagingException messagingException) {
          LOG.warn("Error when closing inbox", messagingException);
        }
        if (store != null && store.isConnected()) {
          store.close();
        }
      } catch (MessagingException messagingException) {
        LOG.warn("Error when closing store", messagingException);
      }
    }
    return null;
  }

  private void createEmails(UIDFolder uidFolder, Message[] serverMessages, String username) throws MessagingException {
    for (Message message : serverMessages) {
      long messageUid = uidFolder.getUID(message);
      if (emailBoxStorage.getEmailByMailRemoteIdAndUserId(username, messageUid) == null) {
        try {
          MimeMessage mimeMessage = new MimeMessage((MimeMessage) message);
          String excerpt = EmailConnectorUtils.getMessageContent(mimeMessage, true);
          String subject = message.getSubject().length() > 50 ? message.getSubject().substring(0, 50) + "..."
                                                              : message.getSubject();
          EmailSender emailSender = EmailConnectorUtils.getEmailSender(message.getFrom());
          emailBoxStorage.createEmail(new Email(null,
                                                messageUid,
                                                username,
                                                subject,
                                                excerpt,
                                                message.getSentDate() != null ? message.getSentDate() : message.getReceivedDate(),
                                                emailSender,
                                                null,
                                                null,
                                                null));
        } catch (Exception e) {
          LOG.warn("Error when storing email", e);
        }
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

  private void cleanupObsoleteEmails(UIDFolder uidFolder, Message[] serverMessages, String username) {
    Set<Long> serverMessagesUids = Arrays.stream(serverMessages).map(msg -> {
      try {
        return uidFolder.getUID(msg);
      } catch (MessagingException messagingException) {
        LOG.warn("Error when getting message uid", messagingException);
        return null;
      }
    }).collect(Collectors.toSet());
    List<Email> obsoleteEmails = getEmailBox(username).getEmails()
                                                      .stream()
                                                      .filter(email -> !serverMessagesUids.contains(email.getMailRemoteId()))
                                                      .toList();
    if (!obsoleteEmails.isEmpty()) {
      deleteEmails(obsoleteEmails);
    }
    List<Email> userEmails = getEmailBox(username).getEmails();
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
}
