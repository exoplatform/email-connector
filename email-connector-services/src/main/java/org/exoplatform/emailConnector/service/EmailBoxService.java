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
import java.io.UnsupportedEncodingException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

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
import javax.mail.internet.MimeMessage;

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

import org.exoplatform.commons.api.notification.NotificationContext;
import org.exoplatform.commons.api.notification.model.PluginKey;
import org.exoplatform.commons.api.settings.SettingService;
import org.exoplatform.commons.api.settings.data.Context;
import org.exoplatform.commons.api.settings.data.Scope;
import org.exoplatform.commons.notification.impl.NotificationContextImpl;
import org.exoplatform.commons.utils.CommonsUtils;
import org.exoplatform.emailConnector.job.EmailBoxSyncJob;
import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.EmailAttachment;
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

import io.meeds.social.category.model.CategoryObject;
import io.meeds.social.category.service.CategoryLinkService;
import io.meeds.social.html.utils.HtmlUtils;
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

  private static final String     USER_NOT_ALLOWED_FOR_BROADCAST_EVENT_MESSAGE   =
                                                                               "User %s is not allowed to broadcast open email event";

  private static final String     USER_NOT_ALLOWED_FOR_UPDATE_EMAIL_MESSAGE      = "User %s is not allowed to update email";

  private static final String     USER_NOT_ALLOWED_FOR_DELETE_EMAIL_MESSAGE      = "User %s is not allowed to delete email";

  private static final String     USER_NOT_ALLOWED_FOR_ARCHIVE_EMAIL_MESSAGE     = "User %s is not allowed to archive email";

  private static final String     USER_NOT_ALLOWED_FOR_SEND_EMAIL_MESSAGE        = "User %s is not allowed to send email";
  
  private CategoryLinkService     categoryLinkService;

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

  public void broadcastOpenEmail(String username) throws IllegalAccessException {
    UserEmailSetting userEmailSetting = userEmailSettingService.getUserEmailSetting(username);
    if (userEmailSetting.getEmailConnectorId() == null
        || !userEmailSettingService.canConnect(Long.parseLong(userEmailSetting.getEmailConnectorId()), username)) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED_FOR_BROADCAST_EVENT_MESSAGE, username));
    }
    try {
      listenerService.broadcast(EmailConnectorUtils.OPEN_EMAIL, username, userEmailSetting.getEmailConnectorName());
    } catch (Exception e) {
      LOG.warn("Error broadcasting event '" + EmailConnectorUtils.OPEN_EMAIL + "' using source '" + username + "' and data "
          + userEmailSetting.getEmailConnectorName(), e);
    }
  }

  public Email getEmailByMailRemoteIdAndUserId(long mailRemoteId,
                                               String username,
                                               boolean withAttachments,
                                               boolean withRecipients,
                                               boolean withProfile,
                                               boolean broadcast) throws IllegalAccessException {
    if (broadcast) {
      broadcastOpenEmail(username);
    }
    return emailBoxStorage.getEmailByMailRemoteIdAndUserId(mailRemoteId, username, withAttachments, withRecipients, withProfile);
  }

  @Transactional
  public Email getEmailById(long id, String username) {
    return emailBoxStorage.getEmailById(id, username);
  }

  public void updateEmailReadStatus(List<Long> mailRemoteIds,
                                    String username,
                                    boolean readStatus,
                                    boolean updateRemoteReadStatus) throws IllegalAccessException {
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
              remoteMessage.setFlag(Flags.Flag.SEEN, readStatus);
            }
          } catch (Exception e) {
            Email email = getEmailByMailRemoteIdAndUserId(mailRemoteId, username, false, false, false, false);
            email.setRead(!readStatus);
            emailBoxStorage.updateEmail(email);
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
      boolean needExpunge = false;
      try {
        store = (IMAPStore) userEmailSettingService.connect(userEmailSetting);
        inbox = (IMAPFolder) store.getFolder("INBOX");
        inbox.open(Folder.READ_WRITE);
        IMAPFolder trash = findTrashFolder(store);
        for (Long mailRemoteId : mailRemoteIds) {
          try {
            Message remoteMessage = ((UIDFolder) inbox).getMessageByUID(mailRemoteId);
            if (remoteMessage != null) {
              remoteMessage.setFlag(Flags.Flag.DELETED, true);
              if (trash != null) {
                inbox.moveMessages(new Message[] { remoteMessage }, trash);
              } else {
                needExpunge = true;
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
                  getCategoryLinkService().link(emailCategoryId,
                                                new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE,
                                                                   String.valueOf(email.getId()),
                                                                   0));
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
              getCategoryLinkService().link(emailCategoryId,
                                            new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE,
                                                               String.valueOf(email.getId()),
                                                               0));
            });
          }
        });
        throw new IllegalStateException(String.format("Error when connecting store for user %s", username));
      } finally {
        try {
          if (inbox != null && inbox.isOpen()) {
            inbox.close(needExpunge);
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
        inbox.open(Folder.READ_WRITE);
        IMAPFolder archive = findArchiveFolder(store);
        for (Long mailRemoteId : mailRemoteIds) {
          try {
            Message remoteMessage = ((UIDFolder) inbox).getMessageByUID(mailRemoteId);
            if (remoteMessage != null) {
              remoteMessage.setFlag(Flags.Flag.DELETED, true);
              if (archive != null) {
                inbox.moveMessages(new Message[] { remoteMessage }, archive);
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
                  getCategoryLinkService().link(emailCategoryId,
                                                new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE,
                                                                   String.valueOf(email.getId()),
                                                                   0));
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
              getCategoryLinkService().link(emailCategoryId,
                                            new CategoryObject(EmailCategoryPlugin.OBJECT_TYPE,
                                                               String.valueOf(email.getId()),
                                                               0));
            });
          }
        });
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
    return failedEmailArchives;
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
      message.setContent(contentDoc.body().html(), "text/html; charset=UTF-8");
      if (!StringUtils.isEmpty(email.getMailHeaderId())) {
        message.setHeader("In-Reply-To", email.getMailHeaderId());
        message.setHeader("References", email.getMailHeaderId());
      }
      Transport.send(message);
      String emailType = StringUtils.isEmpty(email.getMailHeaderId()) ? "newEmail" : "reply";
      listenerService.broadcast(EmailConnectorUtils.SEND_EMAIL, username, emailType);
    } catch (MessagingException | UnsupportedEncodingException e) {
      LOG.error("Error when sending email for user {}", username, e);
      throw new IllegalStateException(String.format("Error when sending email for user %s", username));
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
          String mailHeaderId = ((MimeMessage) message).getMessageID();
          emailBoxStorage.createEmail(new Email(null,
                                                messageUid,
                                                mailHeaderId,
                                                username,
                                                subject,
                                                emailContent,
                                                message.getReceivedDate(),
                                                emailSender,
                                                message.isSet(Flags.Flag.SEEN),
                                                true,
                                                emailToRecipients,
                                                emailCcRecipients,
                                                emailBccRecipients,
                                                null));

        } else {
          updateEmailReadStatus(List.of(messageUid), username, message.isSet(Flags.Flag.SEEN), false);
          email.setRecent(false);
          emailBoxStorage.updateEmail(email);
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
    List<Long> emailsIdsToDelete = new ArrayList<Long>();
    for (Email email : emails) {
      emailsIdsToDelete.add(email.getId());
      if (!CollectionUtils.isEmpty(email.getCategoryIds())) {
        email.getCategoryIds().stream().forEach(emailCategoryId -> {
          getCategoryLinkService().unlink(emailCategoryId,
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
  
  private CategoryLinkService getCategoryLinkService() {
    if (categoryLinkService == null) {
      categoryLinkService = CommonsUtils.getService(CategoryLinkService.class);
    }
    return categoryLinkService;
  }
}
