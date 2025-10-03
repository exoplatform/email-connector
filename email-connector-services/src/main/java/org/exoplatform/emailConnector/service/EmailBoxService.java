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

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import javax.mail.BodyPart;
import javax.mail.Folder;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Part;
import javax.mail.Store;
import javax.mail.UIDFolder;
import javax.mail.internet.MimeMultipart;

import org.jsoup.Jsoup;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import org.exoplatform.emailConnector.model.Email;
import org.exoplatform.emailConnector.model.SyncStatus;
import org.exoplatform.emailConnector.model.UserEmailSetting;
import org.exoplatform.emailConnector.storage.EmailBoxStorage;
import org.exoplatform.emailConnector.utils.EmailBoxUtils;
import org.exoplatform.services.log.ExoLogger;
import org.exoplatform.services.log.Log;

import lombok.SneakyThrows;

/**
 * A Service to access and store email box
 */
@Service
public class EmailBoxService {

  private static final String   USER_NOT_ALLOWED = "User %s is not allowed to synchronize email box";

  private static final Log      LOG              = ExoLogger.getLogger(EmailBoxService.class);

  @Autowired
  private EmailConnectorService emailConnectorService;

  @Autowired
  private EmailBoxStorage       emailBoxStorage;

  /**
   * Synchronize user email box.
   *
   * @param username user of which email box will be synchronized
   * @throws IllegalAccessException if user is not allowed to synchronize email
   *           box
   * @throws MessagingException if email box synchronization occurs messaging
   *           exception
   */
  public void synchronize(String username) throws IllegalAccessException, MessagingException {
    UserEmailSetting userEmailSetting = emailConnectorService.getUserEmailSetting(username);
    Store store = emailConnectorService.connect(userEmailSetting);
    if (store == null || !store.isConnected()) {
      throw new IllegalAccessException(String.format(USER_NOT_ALLOWED, username));
    }
    userEmailSetting.setSyncStatus(SyncStatus.IN_PROGRESS);
    emailConnectorService.setUserEmailSetting(userEmailSetting, username);
    Folder inbox = store.getFolder("INBOX");
    inbox.open(Folder.READ_ONLY);

    // get a list of javamail messages as an array of messages
    UIDFolder uidFolder = (UIDFolder) inbox;
    Message[] messages = inbox.getMessages();
    int count = 0;
    int maxEmails = Integer.parseInt(System.getProperty("email.connector.sync.emails.number", "100"));
    int i = messages.length - 1;
    while (i >= 0 && count < maxEmails) {
      Message message = messages[i--];
      try {
        String excerpt = EmailBoxUtils.getMessageContent(message, true);
        String subject = message.getSubject().length() > 50 ? message.getSubject().substring(0, 50) + "..." : message.getSubject();
        emailBoxStorage.createEmail(new Email(null,
                                              uidFolder.getUID(message),
                                              username,
                                              subject,
                                              excerpt,
                                              message.getFrom() != null
                                                  && message.getFrom()[0] != null ? message.getFrom()[0].toString() : "",
                                              message.getSentDate() != null ? message.getSentDate() : message.getReceivedDate()));
        count++;
      } catch (Exception e) {
        LOG.warn("Error when storing email", e);
      }
    }
    userEmailSetting = emailConnectorService.getUserEmailSetting(username);
    userEmailSetting.setSyncStatus(SyncStatus.SUCCESS);
    emailConnectorService.setUserEmailSetting(userEmailSetting, username);
    try {
      store.close();
    } catch (MessagingException e) {
      LOG.warn("Error when closing store", e.getMessage());
    }
  }

  /**
   * Mark user email box synchronization as failed
   *
   * @param username user of which email box synchronization is failed
   */
  public void markSynchronizeAsFailed(String username) {
    UserEmailSetting userEmailSetting = emailConnectorService.getUserEmailSetting(username);
    userEmailSetting.setSyncStatus(SyncStatus.FAILURE);
    emailConnectorService.setUserEmailSetting(userEmailSetting, username);
  }

  /**
   * Get user emails.
   *
   * @param username user getting user emails
   * @return list of stored {@link Email} in datasource
   */
  public List<Email> getEmails(String username) {
    return emailBoxStorage.getEmails(username);
  }
}
